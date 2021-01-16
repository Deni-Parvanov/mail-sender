package mail.sender.sendgrid;

import static java.lang.String.format;

public class SenderDoesNotExistException extends RuntimeException {

    SenderDoesNotExistException(String sendGridId) {
        super(format("Sender with ID '%s' doesn't exist.", sendGridId));
    }
}