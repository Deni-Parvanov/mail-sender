package mail.sender.domain;

import io.vavr.collection.Map;
import io.vavr.control.Option;
import lombok.Value;

@Value
public class Email {

    String templateId;
    String senderEmail;
    String recipientEmail;
    Map<String, String> templateParameters;
    Option<Attachment> attachment;
}