package net.bitbylogic.rps.listener;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
public abstract class RedisMessageListener<T> {

    private final String channel;
    private final Class<T> payloadType;
    private final boolean selfActivation;

    protected RedisMessageListener(@NotNull String channel, @NotNull Class<T> payloadType) {
        this(channel, payloadType, false);
    }

    protected RedisMessageListener(@NotNull String channel, @NotNull Class<T> payloadType, boolean selfActivation) {
        this.channel = channel;
        this.payloadType = payloadType;
        this.selfActivation = selfActivation;
    }

    public abstract void onReceive(@NotNull ReceivedMessage<T> message);

}
