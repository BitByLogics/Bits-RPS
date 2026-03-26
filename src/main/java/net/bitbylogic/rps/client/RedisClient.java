package net.bitbylogic.rps.client;

import com.google.gson.JsonSyntaxException;
import lombok.Getter;
import net.bitbylogic.rps.RedisManager;
import net.bitbylogic.rps.listener.ListenerComponent;
import net.bitbylogic.rps.listener.RedisMessageListener;
import net.bitbylogic.rps.timed.RedisTimedRequest;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

@Getter
public class RedisClient {

    private static final String INCOMING_DEBUG_FORMAT = "[INCOMING]: %s -> %s: %s";
    private static final String OUTGOING_DEBUG_FORMAT = "[OUTGOING]: %s -> %s: %s";
    private static final String TIMED_RESPONSE_DEBUG_FORMAT = "[TIMED RESPONSE]: %s -> %s: %s";

    private static final String REQUEST_TOPIC_ID = "INTERNAL_REDIS_REQUESTS";

    private final transient CopyOnWriteArrayList<RedisMessageListener> listeners = new CopyOnWriteArrayList<>();
    private final transient CopyOnWriteArrayList<RedisTimedRequest> timedRequests = new CopyOnWriteArrayList<>();
    private final transient ConcurrentHashMap<RedisTimedRequest, Timer> requestTimers = new ConcurrentHashMap<>();

    private final String serverId;
    private final String clientId;

    private final transient RedisManager redisManager;

    /**
     * Constructs a new RedisClient instance with the specified RedisManager and client ID.
     * Initializes the client with the server ID retrieved from the RedisManager and registers
     * the request topic for message handling.
     *
     * @param redisManager The RedisManager instance responsible for managing Redis operations. Must not be null.
     * @param clientId The unique identifier for this RedisClient instance. Must not be null.
     */
    public RedisClient(@NotNull RedisManager redisManager, @NotNull String clientId) {
        this.redisManager = redisManager;
        this.serverId = redisManager.getServerId();
        this.clientId = clientId;

        registerRequestTopic();
    }

    /**
     * Registers a Redis message listener with the client. This method ensures that the provided
     * listener is not already registered. If the listener is being registered for the first time,
     * it is added to the internal list of listeners, associated with the current client instance,
     * and the listener's subscription channel is registered for incoming messages.
     *
     * @param listener The Redis message listener to register. Must not be null.
     */
    public void registerListener(@NotNull RedisMessageListener listener) {
        if (listeners.contains(listener)) {
            Logger.getGlobal().warning(String.format("Attempted to register listener %s twice!", listener.getClass().getName()));
            return;
        }

        listeners.add(listener);
        listener.setClient(this);

        registerChannel(listener.getChannelName());
    }

    /**
     * Sends a listener message to a Redis topic and handles timed requests associated with the message.
     * This method binds the provided {@link ListenerComponent} to the current {@code RedisClient} instance as its source,
     * processes any timed requests defined in the component, and publishes the message to a Redis topic.
     * Debug information can also be logged if enabled in the RedisManager.
     *
     * @param component The {@link ListenerComponent} to be sent. Must not be null.
     *                  The component encapsulates channel, target server, data, and potentially timed requests.
     */
    public void sendListenerMessage(@NotNull ListenerComponent component) {
        component.setSource(this);

        component.getTimedRequests().forEach((request, expireTime) -> {
            if (expireTime == -1) {
                timedRequests.add(request);
                return;
            }

            final Timer requestTimer = new Timer();

            requestTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    timedRequests.remove(request);
                    request.getTimeoutCallback().accept(null);
                    requestTimer.cancel();
                }
            }, expireTime);

            timedRequests.add(request);
            requestTimers.put(request, requestTimer);
        });

        if (redisManager.isDebug()) {
            Logger.getGlobal().info(String.format(OUTGOING_DEBUG_FORMAT, component.getSource().getClientId(), component.getTargetServerId(), redisManager.getGson().toJson(component)));
        }

        getTopic(component.getChannel()).publish(redisManager.getGson().toJson(component));
    }

    /**
     * Sends a timed response message using the specified {@code ListenerComponent}.
     * This method binds the provided {@link ListenerComponent} to the current {@link RedisClient} instance as its source,
     * verifies that at least one timed response is defined in the component, and then publishes the component as a
     * message to a Redis topic. If debug mode is enabled in the {@link RedisManager}, debug information about the
     * operation is logged.
     *
     * @param component The {@link ListenerComponent} containing the timed responses to be sent. Must not be null*/
    public void sendTimedResponse(@NotNull ListenerComponent component) {
        component.setSource(this);

        if (component.getTimedResponses().isEmpty()) {
            Logger.getGlobal().warning("Attempted to send response with no responses!");
            return;
        }

        if (redisManager.isDebug()) {
            Logger.getGlobal().info(String.format(TIMED_RESPONSE_DEBUG_FORMAT, component.getSource().getClientId(), component.getTargetServerId(), redisManager.getGson().toJson(component)));
        }

        getTopic(REQUEST_TOPIC_ID).publish(redisManager.getGson().toJson(component));
    }

    private List<RedisMessageListener> getListeners(@NotNull String channel) {
        return listeners.stream().filter(l -> l.getChannelName().equalsIgnoreCase(channel)).toList();
    }

    private RTopic getTopic(String topicName) {
        return redisManager.getRedissonClient().getTopic(topicName);
    }

    /**
     * Retrieves the Redisson client instance used to interact with Redis.
     * <p>
     * This method provides access to the underlying {@link RedissonClient}
     * which enables operations such as data storage, messaging, and synchronization
     * across Redis.
     *
     * @return the {@link RedissonClient} instance managed by the {@code redisManager}.
     */
    public RedissonClient getRedisClient() {
        return redisManager.getRedissonClient();
    }

    private void registerChannel(@NotNull String channelName) {
        if (redisManager.getRedissonClient().getTopic(channelName).countListeners() >= 1) {
            return;
        }

        RTopic topic = getTopic(channelName);

        topic.addListener(String.class, (channel, msg) -> {
            try {
                ListenerComponent component = redisManager.getGson().fromJson(msg, ListenerComponent.class);

                if (redisManager.isDebug()) {
                    Logger.getGlobal().info(String.format(INCOMING_DEBUG_FORMAT, component.getSource().getClientId(), component.getTargetServerId(), msg));
                }

                if (component.getTargetServerId() != null && !component.getTargetServerId().isBlank() && !redisManager.getServerId().equalsIgnoreCase(component.getTargetServerId())) {
                    return;
                }

                getListeners(channelName).forEach(messageListener -> {
                    if (component.getSource().getServerId().equalsIgnoreCase(messageListener.getClient().getServerId()) && !messageListener.isSelfActivation()) {
                        return;
                    }

                    messageListener.onReceive(component);
                });
            } catch (Exception e) {
                Logger.getGlobal().log(Level.SEVERE, "Error while processing incoming message!", e);
            }
        });
    }

    private void registerRequestTopic() {
        RTopic topic = getTopic(REQUEST_TOPIC_ID);

        topic.addListener(String.class, (channel, msg) -> {
            try {
                ListenerComponent component = redisManager.getGson().fromJson(msg, ListenerComponent.class);

                if (redisManager.isDebug()) {
                    Logger.getGlobal().info(String.format(INCOMING_DEBUG_FORMAT, component.getSource().getClientId(), component.getTargetServerId(), msg));
                }

                component.getTimedResponses().forEach(response -> {
                    if (component.getSource().getServerId().equalsIgnoreCase(redisManager.getServerId()) && !component.isAllowRequestSelfActivation()) {
                        return;
                    }

                    RedisTimedRequest timedRequest = timedRequests.stream().filter(request -> request.getUniqueId().equals(response.getUniqueId()))
                            .findFirst().orElse(null);

                    if (timedRequest == null) {
                        return;
                    }

                    if (requestTimers.containsKey(timedRequest)) {
                        requestTimers.get(timedRequest).cancel();
                    }

                    timedRequests.remove(timedRequest);

                    timedRequest.getSuccessCallback().accept(component);
                });
            } catch (JsonSyntaxException e) {
                Logger.getGlobal().log(Level.WARNING, "Malformed JSON published to request topic!", e);
            }
        });
    }

}
