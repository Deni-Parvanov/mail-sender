package mail.sender.sendgrid;

import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.control.Option;
import mail.sender.config.ObjectMapperConfig;
import mail.sender.domain.Attachment;
import mail.sender.domain.Email;
import mail.sender.util.WebClientTest;
import mail.sender.web.client.WebClientFactory;
import okhttp3.mockwebserver.RecordedRequest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static mail.sender.util.ReactiveAsserts.*;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ACCEPT;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@ExtendWith(SpringExtension.class)
@Import({ObjectMapperConfig.class, WebClientFactory.class})
class SendGridClientTest extends WebClientTest {

    private static final String SENDER_NICKNAME = "testNickname";
    private static final String SEND_GRID_ID = "1325731";
    private static final Email EMAIL = new Email(
            "test-template-id",
            "noreply@johndoe.com",
            "john.doe@mycompany.com",
            HashMap.of("subject", "Hello, World!", "key", "value", "senderSignatureName", "John Doe"),
            Option.some(new Attachment("anyBase64Content", "some.pdf", "application/pdf")));

    private final SendGridClient client;

    @Autowired
    public SendGridClientTest(WebClientFactory clientFactory) {
        this.client = new SendGridClient(clientFactory, getServerUrl(), "test-api-key", 1, Duration.ofSeconds(10));
    }

    @Test
    void whenSendingEmail_thenCorrectRequestIsSent() {
        prepareResponse(createResponse(ACCEPTED));

        assertEmptyMono(client.sendEmail(EMAIL));

        RecordedRequest request = awaitRequest();
        assertThat(request.getPath()).isEqualTo("/mail/send");
        assertThat(request.getMethod()).isEqualTo(HttpMethod.POST.name());
        assertThat(request.getHeader(AUTHORIZATION)).isEqualTo("Bearer test-api-key");
        assertThatJson(request.getBody().readString(UTF_8)).isEqualTo(contentOf(getClass(), "sendEmailRequest.json"));
    }

    @Test
    void givenUnexpectedError_whenSendingEmail_thenTheErrorIsPropagated() {
        prepareResponse(createResponse(INTERNAL_SERVER_ERROR));

        assertUnknownError(client.sendEmail(EMAIL));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void givenExistingSender_whenFetchingVerificationStatus_thenTheStatusIsReturned(boolean verified) {
        String responseBody = contentOf(getClass(), verified ?
                "verifiedSenderResponse.json" : "unverifiedSenderResponse.json");
        prepareResponse(createJsonResponse(OK, responseBody));

        assertMonoElement(client.fetchVerificationStatus(SEND_GRID_ID), verified);

        RecordedRequest request = awaitRequest();
        assertThat(request.getPath()).isEqualTo("/marketing/senders/1325731");
        assertThat(request.getMethod()).isEqualTo(HttpMethod.GET.name());
        assertThat(request.getHeader(AUTHORIZATION)).isEqualTo("Bearer test-api-key");
        assertThat(request.getHeader(ACCEPT)).isEqualTo(APPLICATION_JSON_VALUE);
    }

    @Test
    void givenNotExistingSender_whenFetchingVerificationStatus_thenSenderDoesNotExistExceptionIsReturned() {
        prepareResponse(createJsonResponse(NOT_FOUND, contentOf(getClass(), "senderNotFoundResponse.json")));

        assertMonoError(client.fetchVerificationStatus(SEND_GRID_ID), SenderDoesNotExistException.class);
    }

    @Test
    void givenUnexpectedError_whenFetchingVerificationStatus_thenTheErrorIsPropagated() {
        prepareResponse(createResponse(INTERNAL_SERVER_ERROR));

        assertUnknownError(client.fetchVerificationStatus(SEND_GRID_ID));
    }

    @Test
    void givenUnverifiedUser_whenResendingVerificationEmail_thenCorrectRequestIsSent() {
        prepareResponse(createResponse(NO_CONTENT));

        assertEmptyMono(client.resendVerificationEmail(SEND_GRID_ID));

        RecordedRequest request = awaitRequest();
        assertThat(request.getPath()).isEqualTo("/marketing/senders/1325731/resend_verification");
        assertThat(request.getMethod()).isEqualTo(HttpMethod.POST.name());
        assertThat(request.getHeader(AUTHORIZATION)).isEqualTo("Bearer test-api-key");
    }

    @Test
    void givenVerifiedUser_whenResendingVerificationEmail_thenSenderAlreadyVerifiedExceptionIsReturned() {
        prepareResponse(createJsonResponse(BAD_REQUEST, "senderAlreadyVerifiedResponse.json"));

        assertMonoError(client.resendVerificationEmail(SEND_GRID_ID), SenderAlreadyVerifiedException.class);
    }

    @Test
    void givenNotExistingSender_whenResendingVerificationEmail_thenSenderDoesNotExistExceptionIsReturned() {
        prepareResponse(createJsonResponse(NOT_FOUND, "senderNotFoundResponse.json"));

        assertMonoError(client.resendVerificationEmail(SEND_GRID_ID), SenderDoesNotExistException.class);
    }

    @Test
    void givenUnexpectedError_whenResendingVerificationEmail_thenTheErrorIsPropagated() {
        prepareResponse(createResponse(INTERNAL_SERVER_ERROR));

        assertUnknownError(client.resendVerificationEmail(SEND_GRID_ID));
    }

    @Test
    void givenNotExistingSenderAndValidData_whenCreatingUser_thenTheSendGridIdOfTheCreatedUserIsReturned() {
        prepareResponse(createJsonResponse(CREATED, contentOf(getClass(), "createdSenderResponse.json")));

        assertMonoElement(createSender(client), SEND_GRID_ID);

        RecordedRequest request = awaitRequest();
        assertThat(request.getPath()).isEqualTo("/marketing/senders");
        assertThat(request.getMethod()).isEqualTo(HttpMethod.POST.name());
        assertThat(request.getHeader(AUTHORIZATION)).isEqualTo("Bearer test-api-key");
        assertThat(request.getHeader(ACCEPT)).isEqualTo(APPLICATION_JSON_VALUE);
        assertThatJson(request.getBody().readString(UTF_8)).isEqualTo(contentOf(getClass(), "createSenderRequest.json"));
    }

    @Test
    void givenExistingSenderAndValidData_whenUpdatingSender_thenTheCorrectRequestIsSent() {
        prepareResponse(createJsonResponse(OK, contentOf(getClass(), "updatedSenderResponse.json")));

        assertEmptyMono(client.updateSender(SEND_GRID_ID, "Jane Doe", "jane.doe@mycompany.com", "Oxford street 23", "Dublin", "Ireland"));

        RecordedRequest request = awaitRequest();
        assertThat(request.getPath()).isEqualTo("/marketing/senders/1325731");
        assertThat(request.getMethod()).isEqualTo(HttpMethod.PATCH.name());
        assertThat(request.getHeader(AUTHORIZATION)).isEqualTo("Bearer test-api-key");
        assertThat(request.getHeader(ACCEPT)).isEqualTo(APPLICATION_JSON_VALUE);
        assertThatJson(request.getBody().readString(UTF_8)).isEqualTo(contentOf(getClass(), "updateSenderRequest.json"));
    }

    @Test
    void givenExistingSenderAndInvalidData_whenUpdatingSender_thenInvalidSenderExceptionIsReturned() {
        prepareResponse(createJsonResponse(BAD_REQUEST, contentOf(getClass(), "invalidEmailResponse.json")));

        assertMonoError(
                client.updateSender(SEND_GRID_ID, "Jane Doe", "jane.doe@mycompany.com", "Oxford street 23", "Dublin", "Ireland"),
                InvalidSenderException.class);
    }

    @Test
    void givenNotExistingSender_whenUpdatingUser_thenSenderDoesNotExistExceptionIsReturned() {
        prepareResponse(createJsonResponse(NOT_FOUND, contentOf(getClass(), "senderNotFoundResponse.json")));

        assertMonoError(
                client.updateSender(SEND_GRID_ID, "Jane Doe", "jane.doe@mycompany.com", "Oxford street 23", "Dublin", "Ireland"),
                SenderDoesNotExistException.class);
    }

    @Test
    void givenUnexpectedError_whenUpdatingUser_thenTheErrorIsPropagated() {
        prepareResponse(createResponse(INTERNAL_SERVER_ERROR));

        assertUnknownError(client.updateSender(SEND_GRID_ID, "Jane Doe", "jane.doe@mycompany.com", "Oxford street 23", "Dublin", "Ireland"));
    }

    @Test
    void givenExistingSender_whenDeletingUser_thenTheCorrectRequestIsSent() {
        prepareResponse(createResponse(NO_CONTENT));

        assertEmptyMono(client.deleteSender(SEND_GRID_ID));

        RecordedRequest request = awaitRequest();
        assertThat(request.getPath()).isEqualTo("/marketing/senders/1325731");
        assertThat(request.getMethod()).isEqualTo(HttpMethod.DELETE.name());
        assertThat(request.getHeader(AUTHORIZATION)).isEqualTo("Bearer test-api-key");
    }

    @Test
    void givenNotExistingSender_whenDeletingUser_thenTheErrorIsIgnored() {
        prepareResponse(createJsonResponse(NOT_FOUND, contentOf(getClass(), "senderNotFoundResponse.json")));

        assertEmptyMono(client.deleteSender(SEND_GRID_ID));
    }

    @Test
    void givenUnexpectedError_whenDeletingUser_thenTheErrorIsPropagated() {
        prepareResponse(createResponse(INTERNAL_SERVER_ERROR));

        assertUnknownError(client.deleteSender(SEND_GRID_ID));
    }

    private static void assertUnknownError(Mono<?> mono) {
        StepVerifier.create(mono).verifyErrorSatisfies(e ->
                List.of(SenderAlreadyVerifiedException.class, InvalidSenderException.class, SenderDoesNotExistException.class)
                        .forEach(blackListed -> assertThat(e).isNotInstanceOf(blackListed)));
    }

    private static Mono<String> createSender(SendGridClient client) {
        return client.createSender(SENDER_NICKNAME, "John Doe", "john.doe@mycompany.com", "", "Edmonton", "Canada");
    }

    private static String contentOf(Class<?> clazz, String fileName) {
        return Assertions.contentOf(clazz.getResource(fileName), UTF_8);
    }
}