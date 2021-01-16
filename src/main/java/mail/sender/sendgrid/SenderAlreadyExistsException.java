package mail.sender.sendgrid;

import static java.lang.String.format;

public class SenderAlreadyExistsException extends RuntimeException {

    SenderAlreadyExistsException(String id) {
        super(format("Sender with nickname '%s' already exists.", id));
    }
}