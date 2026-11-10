package net.bitbylogic.rps.client;

import net.bitbylogic.rps.listener.ReceivedMessage;
import net.bitbylogic.rps.model.RedisMessage;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import tools.jackson.core.JacksonException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public class RedisRequest<R> {

    private final RedisClient client;
    private final String channel;
    private final Object payload;
    private final Class<R> responseType;
    private final Duration timeout;
    private final AtomicBoolean sent = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();

    private volatile String targetServerId;
    private volatile Consumer<ReceivedMessage<R>> successCallback;
    private volatile Runnable timeoutCallback;
    private volatile ScheduledFuture<?> timeoutTask;

    RedisRequest(@NotNull RedisClient client, @NotNull String channel, @NotNull Object payload, @NotNull Class<R> responseType, @NotNull Duration timeout) {
        this.client = client;
        this.channel = channel;
        this.payload = payload;
        this.responseType = responseType;
        this.timeout = timeout;
    }

    @Contract("_ -> this")
    public RedisRequest<R> targetServer(String targetServerId) {
        this.targetServerId = targetServerId;
        return this;
    }

    @Contract("_ -> this")
    public RedisRequest<R> onSuccess(@NotNull Consumer<ReceivedMessage<R>> callback) {
        this.successCallback = callback;
        return this;
    }

    @Contract("_ -> this")
    public RedisRequest<R> onTimeout(@NotNull Runnable callback) {
        this.timeoutCallback = callback;
        return this;
    }

    public boolean send() {
        if (!sent.compareAndSet(false, true)) {
            client.getManager().getLogger().warning("[REDIS]: Attempted to send request on channel '" + channel + "' twice.");
            return false;
        }

        return client.dispatchRequest(this);
    }

    public @NotNull CompletableFuture<Boolean> sendAsync() {
        if (!sent.compareAndSet(false, true)) {
            client.getManager().getLogger().warning("[REDIS]: Attempted to send request on channel '" + channel + "' twice.");
            return CompletableFuture.completedFuture(false);
        }

        return client.dispatchRequestAsync(this);
    }

    String getChannel() {
        return channel;
    }

    Object getPayload() {
        return payload;
    }

    String getTargetServerId() {
        return targetServerId;
    }

    Duration getTimeout() {
        return timeout;
    }

    void setTimeoutTask(ScheduledFuture<?> timeoutTask) {
        this.timeoutTask = timeoutTask;
    }

    boolean complete(@NotNull RedisMessage message) {
        R value;

        try {
            value = client.getManager().getObjectMapper().treeToValue(message.payload(), responseType);
        } catch (JacksonException exception) {
            client.getManager().getLogger().log(Level.WARNING, "[REDIS]: Response on channel '" + channel + "' could not be read as " + responseType.getSimpleName() + ".", exception);
            return false;
        }

        if (value == null) {
            client.getManager().getLogger().warning("[REDIS]: Response on channel '" + channel + "' had no payload.");
            return false;
        }

        if (!finished.compareAndSet(false, true)) {
            return true;
        }

        cancelTimeoutTask();

        Consumer<ReceivedMessage<R>> callback = successCallback;

        if (callback == null) {
            return true;
        }

        ReceivedMessage<R> received = new ReceivedMessage<>(client, message, value);
        client.runCallback("Success callback for channel '" + channel + "'", () -> callback.accept(received));

        return true;
    }

    void expire() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }

        Runnable callback = timeoutCallback;

        if (callback == null) {
            return;
        }

        client.runCallback("Timeout callback for channel '" + channel + "'", callback);
    }

    void cancel() {
        finished.set(true);
        cancelTimeoutTask();
    }

    private void cancelTimeoutTask() {
        ScheduledFuture<?> task = timeoutTask;

        if (task == null) {
            return;
        }

        task.cancel(false);
    }

}
