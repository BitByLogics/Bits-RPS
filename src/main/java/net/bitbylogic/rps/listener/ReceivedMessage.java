package net.bitbylogic.rps.listener;

import lombok.Getter;
import net.bitbylogic.rps.client.RedisClient;
import net.bitbylogic.rps.model.RedisMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Getter
public class ReceivedMessage<T> {

    private final RedisClient client;
    private final RedisMessage message;
    private final T payload;

    public ReceivedMessage(@NotNull RedisClient client, @NotNull RedisMessage message, @NotNull T payload) {
        this.client = client;
        this.message = message;
        this.payload = payload;
    }

    public @NotNull UUID getMessageId() {
        return message.id();
    }

    public @NotNull String getChannel() {
        return message.channel();
    }

    public @NotNull String getSourceServerId() {
        return message.sourceServerId();
    }

    public String getSourceClientId() {
        return message.sourceClientId();
    }

    public boolean reply(@NotNull Object responsePayload) {
        return client.reply(this, responsePayload);
    }

    public @NotNull CompletableFuture<Boolean> replyAsync(@NotNull Object responsePayload) {
        return client.replyAsync(this, responsePayload);
    }

}
