package net.bitbylogic.rps.listener;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import net.bitbylogic.rps.client.RedisClient;
import org.jetbrains.annotations.NotNull;

@Getter
@Setter
public abstract class RedisMessageListener {

    private final @NotNull String channelName;

    @Setter(AccessLevel.NONE)
    private boolean selfActivation;

    private RedisClient client;

    public RedisMessageListener(@NotNull String channelName) {
        this.channelName = channelName;
    }

    /**
     * Handles the reception of a {@code ListenerComponent} message dispatched within the Redis channel.
     * Implementations of this method should define the logic to process the received message.
     *
     * @param message the {@link ListenerComponent} object containing the message data, channel information,
     *                and any associated metadata. It cannot be {@code null}.
     */
    public abstract void onReceive(@NotNull ListenerComponent message);

    /**
     * Enables self-activation by setting the {@code selfActivation} flag to {@code true}.
     * This method allows the listener to be activated by components sent from the same server.
     */
    protected void setAllowSelfActivation() {
        selfActivation = true;
    }

}
