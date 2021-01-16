package mail.sender.sendgrid;

import static java.lang.String.format;

public class SenderAlreadyVerifiedException extends RuntimeException {

    SenderAlreadyVerifiedException(String sendGridId) {
        super(format("Sender with ID '%s' already verified.", sendGridId));
    }
}