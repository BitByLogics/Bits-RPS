package net.bitbylogic.rps.listener;

import lombok.Getter;
import lombok.Setter;
import net.bitbylogic.rps.client.RedisClient;

@Getter
@Setter
public abstract class RedisMessageListener {

    private final String channelName;
    private boolean selfActivation;
    private RedisClient client;

    public RedisMessageListener(String channelName) {
        this.channelName = channelName;
    }

    /**
     * Called when an incoming Redis message matches
     * the specified channel name.
     *
     * @param message The message associated with the incoming channel message.
     */
    public abstract void onReceive(ListenerComponent message);

}
