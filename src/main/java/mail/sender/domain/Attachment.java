package mail.sender.domain;

import lombok.Value;

@Value
public class Attachment {

    String content;
    String fileName;
    String fileType;
}