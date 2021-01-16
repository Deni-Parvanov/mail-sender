package mail.sender.sendgrid;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Attachments;
import com.sendgrid.helpers.mail.objects.Personalization;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import mail.sender.domain.Attachment;
import mail.sender.domain.Email;
import mail.sender.web.client.WebClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URL;
import java.time.Duration;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.vavr.Predicates.instanceOf;
import static java.lang.String.format;
import static java.util.function.Predicate.isEqual;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Service
public class SendGridClient {

    private static final String SENDERS_ENDPOINT = "marketing/senders";

    private final WebClient client;
    private final String authorizationHeader;

    public SendGridClient
            (WebClientFactory clientFactory,
             @Value("${sendgrid.rest-base-url}") URL baseUrl,
             @Value("${sendgrid.api-key}") String apiKey,
             @Value("${sendgrid.connections}") int connections,
             @Value("${sendgrid.timeout}") Duration timeout) {
        this.client = clientFactory.createWebClient(getClass().getSimpleName(), baseUrl, connections, timeout);
        this.authorizationHeader = format("Bearer %s", apiKey);
    }

    public Mono<Void> sendEmail(Email email) {
        return client
                .post()
                .uri("mail/send")
                .header(AUTHORIZATION, authorizationHeader)
                .bodyValue(generateMail(email))
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    public Mono<String> createSender(String id, String name, String email, String address, String city, String country) {
        EmailForm form = new EmailForm(name, email);
        return client
                .post()
                .uri(SENDERS_ENDPOINT)
                .accept(APPLICATION_JSON)
                .header(AUTHORIZATION, authorizationHeader)
                .bodyValue(new CreateSenderRequest(id, form, form, address, city, country))
                .retrieve()
                .onStatus(isEqual(BAD_REQUEST), response -> response
                        .bodyToMono(Error.class)
                        .map(error -> error.getErrors()
                                .map(Error.SendGridError::getMessage)
                                .equals(List.of("DUPLICATE_NICKNAME_ERROR_MESSAGE")) // the only error
                                ? new SenderAlreadyExistsException(id)
                                : new InvalidSenderException(error)))
                .bodyToMono(Sender.class)
                .map(Sender::getId)
                .retryWhen(Retry.max(1)
                        .filter(instanceOf(SenderAlreadyExistsException.class))
                        .doBeforeRetryAsync(unused -> deleteSenderByNickname(id)));
    }

    public Mono<Void> updateSender(String sendGridId, String name, String email, String address, String city, String country) {
        EmailForm form = new EmailForm(name, email);
        return client
                .patch()
                .uri(UriComponentsBuilder.fromPath(SENDERS_ENDPOINT).pathSegment(sendGridId).toUriString())
                .accept(APPLICATION_JSON)
                .header(AUTHORIZATION, authorizationHeader)
                .bodyValue(new UpdateSenderRequest(form, form, address, city, country))
                .retrieve()
                .onStatus(isEqual(HttpStatus.BAD_REQUEST), response -> response.bodyToMono(Error.class).map(InvalidSenderException::new))
                .onStatus(isEqual(NOT_FOUND), unused -> Mono.just(new SenderDoesNotExistException(sendGridId)))
                .toBodilessEntity()
                .then();
    }

    public Mono<Void> deleteSender(String sendGridId) {
        return client
                .delete()
                .uri(UriComponentsBuilder.fromPath(SENDERS_ENDPOINT).pathSegment(sendGridId).toUriString())
                .header(AUTHORIZATION, authorizationHeader)
                .retrieve()
                .onStatus(isEqual(NOT_FOUND), unused -> Mono.empty())
                .toBodilessEntity()
                .then();
    }

    public Mono<Boolean> fetchVerificationStatus(String sendGridId) {
        return client
                .get()
                .uri(UriComponentsBuilder.fromPath(SENDERS_ENDPOINT).pathSegment(sendGridId).toUriString())
                .accept(APPLICATION_JSON)
                .header(AUTHORIZATION, authorizationHeader)
                .retrieve()
                .onStatus(isEqual(NOT_FOUND), unused -> Mono.just(new SenderDoesNotExistException(sendGridId)))
                .bodyToMono(Sender.class)
                .map(sender -> sender.getVerified().get("status").getOrElseThrow(IllegalStateException::new));
    }

    public Mono<Void> resendVerificationEmail(String sendGridId) {
        return client
                .post()
                .uri(UriComponentsBuilder.fromPath(SENDERS_ENDPOINT).pathSegment(sendGridId, "resend_verification").toUriString())
                .header(AUTHORIZATION, authorizationHeader)
                .retrieve()
                .onStatus(isEqual(HttpStatus.BAD_REQUEST), unused -> Mono.just(new SenderAlreadyVerifiedException(sendGridId)))
                .onStatus(isEqual(NOT_FOUND), unused -> Mono.just(new SenderDoesNotExistException(sendGridId)))
                .toBodilessEntity()
                .then();
    }

    private static Mail generateMail(Email email) {
        Mail mail = new Mail();
        mail.setTemplateId(email.getTemplateId());
        mail.setFrom(new com.sendgrid.helpers.mail.objects.Email(email.getSenderEmail(), email.getTemplateParameters().get("senderSignatureName").getOrElse("")));
        mail.setReplyTo(new com.sendgrid.helpers.mail.objects.Email(email.getSenderEmail(), email.getTemplateParameters().get("senderSignatureName").getOrElse("")));
        mail.addPersonalization(generatePersonalization(email));
        email.getAttachment().forEach(attachment -> mail.addAttachments(generateAttachment(attachment)));
        return mail;
    }

    private static Personalization generatePersonalization(Email email) {
        Personalization personalization = new Personalization();
        personalization.addTo(new com.sendgrid.helpers.mail.objects.Email(email.getRecipientEmail()));
        email.getTemplateParameters().forEach(personalization::addDynamicTemplateData);
        return personalization;
    }

    private static Attachments generateAttachment(Attachment attachment) {
        return new Attachments.Builder(attachment.getFileName(), attachment.getContent())
                .withType(attachment.getFileType())
                .build();
    }

    private Mono<Void> deleteSenderByNickname(String nickname) {
        return client
                .get()
                .uri(SENDERS_ENDPOINT)
                .accept(APPLICATION_JSON)
                .header(AUTHORIZATION, authorizationHeader)
                .retrieve()
                .bodyToFlux(Sender.class)
                .filter(send -> send.getNickname().equals(nickname))
                .singleOrEmpty()
                .onErrorMap(IndexOutOfBoundsException.class, IllegalStateException::new)
                .map(Sender::getId)
                .flatMap(this::deleteSender);
    }

    @lombok.Value
    private static class EmailForm {

        String name;
        String email;
    }

    @lombok.Value
    private static class CreateSenderRequest {

        String nickname;
        EmailForm from;
        @JsonProperty("reply_to")
        EmailForm replyTo;
        String address;
        String city;
        String country;
    }

    @lombok.Value
    private static class UpdateSenderRequest {

        EmailForm from;
        @JsonProperty("reply_to")
        EmailForm replyTo;
        String address;
        String city;
        String country;
    }

    @lombok.Value
    private static class Sender {

        String id;
        String nickname;
        Map<String, Boolean> verified;
    }

    @lombok.Value
    static class Error {

        List<SendGridError> errors;

        @lombok.Value
        static class SendGridError {

            String message;
        }
    }
}