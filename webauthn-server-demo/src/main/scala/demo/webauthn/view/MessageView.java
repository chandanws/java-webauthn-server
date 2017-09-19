package demo.webauthn.view;

import io.dropwizard.views.View;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.Getter;

@Getter
public class MessageView extends View {

    private final List<String> messages;

    public MessageView(List<String> messages) {
        super("message.ftl");

        this.messages = messages.stream().filter(Objects::nonNull).collect(Collectors.toList());
    }

    public MessageView(String... messages) {
        this(Arrays.asList(messages));
    }

    public MessageView(String message) {
        this(Arrays.asList(message));
    }

    public MessageView(Throwable cause) {
        this(cause.getMessage());
    }

}
