package mail.sender.sendgrid;

import static java.lang.String.format;

public class InvalidSenderException extends RuntimeException {

    InvalidSenderException(SendGridClient.Error error) {
        super(format("SendGrid error response: '%s'.", error));
    }
}