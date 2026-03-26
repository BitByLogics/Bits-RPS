package net.bitbylogic.rps.timed;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.bitbylogic.rps.listener.ListenerComponent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Consumer;

@Getter
@AllArgsConstructor
@RequiredArgsConstructor
public class RedisTimedRequest {

    private final UUID uniqueId;

    private final String id;

    private final transient Consumer<ListenerComponent> successCallback;
    private final transient Consumer<Void> timeoutCallback;

    @Setter
    private String channel;

    /**
     * Constructs a new RedisTimedRequest with a unique identifier, request ID,
     * and callbacks for success and timeout events.
     *
     * @param id the unique identifier of the request. Must not be null.
     * @param successCallback the callback executed on a successful request.
     *                        Must not be null.
     * @param timeoutCallback the callback executed on a timeout event.
     *                        Must not be null.
     */
    public RedisTimedRequest(@NotNull String id, @NotNull Consumer<ListenerComponent> successCallback, @NotNull Consumer<Void> timeoutCallback) {
        this.uniqueId = UUID.randomUUID();
        this.id = id;
        this.successCallback = successCallback;
        this.timeoutCallback = timeoutCallback;
    }

    /**
     * Constructs a new instance of the RedisTimedRequest class.
     *
     * @param uniqueId The unique identifier associated with this request.
     * @param id The request identifier.
     * @param channel The channel associated with the request.
     */
    public RedisTimedRequest(@NotNull UUID uniqueId, @NotNull String id, @NotNull String channel) {
        this.uniqueId = uniqueId;
        this.id = id;
        this.successCallback = null;
        this.timeoutCallback = null;

        this.channel = channel;
    }

}
