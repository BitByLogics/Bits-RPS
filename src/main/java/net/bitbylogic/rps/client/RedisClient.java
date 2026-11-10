package net.bitbylogic.rps.client;

import lombok.Getter;
import net.bitbylogic.rps.RedisManager;
import net.bitbylogic.rps.listener.ReceivedMessage;
import net.bitbylogic.rps.listener.RedisMessageListener;
import net.bitbylogic.rps.model.RedisMessage;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RTopic;
import org.redisson.client.codec.StringCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;

public class RedisClient implements AutoCloseable {

    private static final Duration FALLBACK_TIMEOUT = Duration.ofSeconds(1);
    private static final String RESPONSE_TOPIC_FORMAT = "rps:response:%s:%s";
    private static final String INCOMING_DEBUG_FORMAT = "[INCOMING]: %s -> %s: %s";
    private static final String OUTGOING_DEBUG_FORMAT = "[OUTGOING]: %s -> %s: %s";

    private final CopyOnWriteArrayList<RedisMessageListener<?>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, RedisRequest<?>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, Integer> channelSubscriptions = new HashMap<>();
    private final ReentrantLock subscriptionLock = new ReentrantLock();
    private final ScheduledThreadPoolExecutor scheduler;

    @Getter
    private final RedisManager manager;

    @Getter
    private final String clientId;

    private Integer responseSubscription;

    @Getter
    private volatile boolean closed;

    public RedisClient(@NotNull RedisManager manager, @NotNull String clientId) {
        this.manager = manager;
        this.clientId = clientId;

        this.scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "bits-rps-" + clientId + "-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    public @NotNull String getServerId() {
        return manager.getServerId();
    }

    public boolean registerListener(@NotNull RedisMessageListener<?> listener) {
        if (closed) {
            manager.getLogger().warning("[REDIS]: Attempted to register a listener on closed client '" + clientId + "'.");
            return false;
        }

        if (manager.getRedissonClient().isShutdown()) {
            manager.getLogger().warning("[REDIS]: Attempted to register a listener after the Redis connection was shut down.");
            return false;
        }

        if (!listeners.addIfAbsent(listener)) {
            manager.getLogger().warning("[REDIS]: Attempted to register listener " + listener.getClass().getName() + " twice.");
            return false;
        }

        try {
            subscribeChannel(listener.getChannel());
        } catch (RuntimeException exception) {
            listeners.remove(listener);
            manager.getLogger().log(Level.SEVERE, "[REDIS]: Unable to subscribe to channel '" + listener.getChannel() + "'.", exception);
            return false;
        }

        return true;
    }

    public <T> @NotNull RedisMessageListener<T> registerListener(@NotNull String channel, @NotNull Class<T> payloadType, @NotNull Consumer<ReceivedMessage<T>> handler) {
        RedisMessageListener<T> listener = new RedisMessageListener<>(channel, payloadType) {
            @Override
            public void onReceive(@NotNull ReceivedMessage<T> message) {
                handler.accept(message);
            }
        };

        registerListener(listener);
        return listener;
    }

    public boolean unregisterListener(@NotNull RedisMessageListener<?> listener) {
        if (!listeners.remove(listener)) {
            return false;
        }

        String channel = listener.getChannel();

        subscriptionLock.lock();

        try {
            if (hasListeners(channel)) {
                return true;
            }

            Integer subscriptionId = channelSubscriptions.remove(channel);

            if (subscriptionId == null) {
                return true;
            }

            getTopic(channel).removeListener(subscriptionId);
        } catch (RuntimeException exception) {
            manager.getLogger().log(Level.WARNING, "[REDIS]: Unable to unsubscribe from channel '" + channel + "'.", exception);
        } finally {
            subscriptionLock.unlock();
        }

        return true;
    }

    public boolean send(@NotNull String channel, @NotNull Object payload) {
        return sendTo(null, channel, payload);
    }

    public boolean sendTo(String targetServerId, @NotNull String channel, @NotNull Object payload) {
        RedisMessage message = createMessage(channel, targetServerId, payload);

        if (message == null) {
            return false;
        }

        return publish(channel, message);
    }

    public @NotNull CompletableFuture<Boolean> sendAsync(@NotNull String channel, @NotNull Object payload) {
        return sendToAsync(null, channel, payload);
    }

    public @NotNull CompletableFuture<Boolean> sendToAsync(String targetServerId, @NotNull String channel, @NotNull Object payload) {
        RedisMessage message = createMessage(channel, targetServerId, payload);

        if (message == null) {
            return CompletableFuture.completedFuture(false);
        }

        return publishAsync(channel, message);
    }

    public <R> @NotNull RedisRequest<R> request(@NotNull String channel, @NotNull Object payload, @NotNull Class<R> responseType, @NotNull Duration timeout) {
        Duration effectiveTimeout = timeout;

        if (timeout.isNegative() || timeout.isZero()) {
            manager.getLogger().warning("[REDIS]: Request timeout on channel '" + channel + "' must be positive, using " + FALLBACK_TIMEOUT.toSeconds() + " second.");
            effectiveTimeout = FALLBACK_TIMEOUT;
        }

        return new RedisRequest<>(this, channel, payload, responseType, effectiveTimeout);
    }

    public boolean reply(@NotNull ReceivedMessage<?> received, @NotNull Object responsePayload) {
        RedisMessage message = createReply(received, responsePayload);

        if (message == null) {
            return false;
        }

        return publish(responseTopicName(received.getSourceServerId(), received.getSourceClientId()), message);
    }

    public @NotNull CompletableFuture<Boolean> replyAsync(@NotNull ReceivedMessage<?> received, @NotNull Object responsePayload) {
        RedisMessage message = createReply(received, responsePayload);

        if (message == null) {
            return CompletableFuture.completedFuture(false);
        }

        return publishAsync(responseTopicName(received.getSourceServerId(), received.getSourceClientId()), message);
    }

    @Override
    public void close() {
        subscriptionLock.lock();

        try {
            if (closed) {
                return;
            }

            closed = true;

            for (Map.Entry<String, Integer> entry : channelSubscriptions.entrySet()) {
                removeSubscription(entry.getKey(), entry.getValue());
            }

            channelSubscriptions.clear();

            if (responseSubscription != null) {
                removeSubscription(responseTopicName(getServerId(), clientId), responseSubscription);
                responseSubscription = null;
            }
        } finally {
            subscriptionLock.unlock();
        }

        listeners.clear();

        for (RedisRequest<?> request : pendingRequests.values()) {
            request.cancel();
        }

        pendingRequests.clear();
        scheduler.shutdownNow();
        manager.unregisterClient(this);
    }

    boolean dispatchRequest(@NotNull RedisRequest<?> request) {
        RedisMessage message = registerRequest(request);

        if (message == null) {
            return false;
        }

        if (publish(request.getChannel(), message)) {
            return true;
        }

        abandonRequest(message.id(), request);
        return false;
    }

    CompletableFuture<Boolean> dispatchRequestAsync(@NotNull RedisRequest<?> request) {
        RedisMessage message = registerRequest(request);

        if (message == null) {
            return CompletableFuture.completedFuture(false);
        }

        return publishAsync(request.getChannel(), message).thenApply(published -> {
            if (!published) {
                abandonRequest(message.id(), request);
            }

            return published;
        });
    }

    void runCallback(String description, Runnable task) {
        try {
            manager.getCallbackExecutor().execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    manager.getLogger().log(Level.SEVERE, "[REDIS]: " + description + " threw an exception.", exception);
                }
            });
        } catch (RuntimeException exception) {
            manager.getLogger().log(Level.SEVERE, "[REDIS]: The callback executor rejected: " + description + ".", exception);
        }
    }

    private RedisMessage registerRequest(RedisRequest<?> request) {
        String channel = request.getChannel();

        if (!canSend(channel)) {
            return null;
        }

        JsonNode tree = toTree(request.getPayload());

        if (tree == null) {
            return null;
        }

        try {
            subscribeResponses();
        } catch (RuntimeException exception) {
            manager.getLogger().log(Level.SEVERE, "[REDIS]: Unable to subscribe to the response topic of client '" + clientId + "'.", exception);
            return null;
        }

        UUID requestId = UUID.randomUUID();
        RedisMessage message = new RedisMessage(requestId, channel, getServerId(), clientId, request.getTargetServerId(), null, tree);

        pendingRequests.put(requestId, request);

        try {
            ScheduledFuture<?> task = scheduler.schedule(() -> expireRequest(requestId), request.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            request.setTimeoutTask(task);
        } catch (RuntimeException exception) {
            pendingRequests.remove(requestId);
            manager.getLogger().log(Level.WARNING, "[REDIS]: Unable to schedule the timeout of a request on channel '" + channel + "'.", exception);
            return null;
        }

        return message;
    }

    private void abandonRequest(UUID requestId, RedisRequest<?> request) {
        pendingRequests.remove(requestId);
        request.cancel();
    }

    private RedisMessage createMessage(String channel, String targetServerId, Object payload) {
        if (!canSend(channel)) {
            return null;
        }

        JsonNode tree = toTree(payload);

        if (tree == null) {
            return null;
        }

        return new RedisMessage(UUID.randomUUID(), channel, getServerId(), clientId, targetServerId, null, tree);
    }

    private RedisMessage createReply(ReceivedMessage<?> received, Object responsePayload) {
        RedisMessage original = received.getMessage();

        if (closed) {
            manager.getLogger().warning("[REDIS]: Attempted to reply on closed client '" + clientId + "'.");
            return null;
        }

        if (original.sourceClientId() == null) {
            manager.getLogger().warning("[REDIS]: Cannot reply to a message without a source client on channel '" + original.channel() + "'.");
            return null;
        }

        JsonNode tree = toTree(responsePayload);

        if (tree == null) {
            return null;
        }

        return new RedisMessage(UUID.randomUUID(), original.channel(), getServerId(), clientId, original.sourceServerId(), original.id(), tree);
    }

    private boolean canSend(String channel) {
        if (closed) {
            manager.getLogger().warning("[REDIS]: Attempted to send on closed client '" + clientId + "'.");
            return false;
        }

        if (manager.getRedissonClient().isShutdown()) {
            manager.getLogger().warning("[REDIS]: Attempted to send after the Redis connection was shut down.");
            return false;
        }

        if (channel.isBlank()) {
            manager.getLogger().warning("[REDIS]: Attempted to send to a blank channel.");
            return false;
        }

        return true;
    }

    private JsonNode toTree(Object payload) {
        try {
            return manager.getObjectMapper().valueToTree(payload);
        } catch (JacksonException exception) {
            manager.getLogger().log(Level.SEVERE, "[REDIS]: Unable to serialize payload of type " + payload.getClass().getName() + ".", exception);
            return null;
        }
    }

    private String serialize(String topicName, RedisMessage message) {
        String serialized;

        try {
            serialized = manager.getObjectMapper().writeValueAsString(message);
        } catch (JacksonException exception) {
            manager.getLogger().log(Level.SEVERE, "[REDIS]: Unable to serialize message for '" + topicName + "'.", exception);
            return null;
        }

        if (manager.isDebug()) {
            manager.getLogger().info(String.format(OUTGOING_DEBUG_FORMAT, clientId, message.targetServerId(), serialized));
        }

        return serialized;
    }

    private boolean publish(String topicName, RedisMessage message) {
        String serialized = serialize(topicName, message);

        if (serialized == null) {
            return false;
        }

        try {
            getTopic(topicName).publish(serialized);
        } catch (RuntimeException exception) {
            manager.getLogger().log(Level.SEVERE, "[REDIS]: Unable to publish to '" + topicName + "'.", exception);
            return false;
        }

        return true;
    }

    private CompletableFuture<Boolean> publishAsync(String topicName, RedisMessage message) {
        String serialized = serialize(topicName, message);

        if (serialized == null) {
            return CompletableFuture.completedFuture(false);
        }

        try {
            return getTopic(topicName).publishAsync(serialized).toCompletableFuture().handle((receivers, error) -> {
                if (error == null) {
                    return true;
                }

                manager.getLogger().log(Level.SEVERE, "[REDIS]: Unable to publish to '" + topicName + "'.", error);
                return false;
            });
        } catch (RuntimeException exception) {
            manager.getLogger().log(Level.SEVERE, "[REDIS]: Unable to publish to '" + topicName + "'.", exception);
            return CompletableFuture.completedFuture(false);
        }
    }

    private void subscribeChannel(String channel) {
        subscriptionLock.lock();

        try {
            if (channelSubscriptions.containsKey(channel)) {
                return;
            }

            int subscriptionId = getTopic(channel).addListener(String.class, (name, raw) -> receiveChannelMessage(channel, raw));
            channelSubscriptions.put(channel, subscriptionId);
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void subscribeResponses() {
        subscriptionLock.lock();

        try {
            if (responseSubscription != null) {
                return;
            }

            responseSubscription = getTopic(responseTopicName(getServerId(), clientId)).addListener(String.class, (name, raw) -> receiveResponse(raw));
        } finally {
            subscriptionLock.unlock();
        }
    }

    private void receiveChannelMessage(String channel, String raw) {
        RedisMessage message = parse(raw, channel);

        if (message == null) {
            return;
        }

        if (manager.isDebug()) {
            manager.getLogger().info(String.format(INCOMING_DEBUG_FORMAT, message.sourceClientId(), message.targetServerId(), raw));
        }

        String targetServerId = message.targetServerId();

        if (targetServerId != null && !targetServerId.isBlank() && !getServerId().equalsIgnoreCase(targetServerId)) {
            return;
        }

        boolean fromThisServer = getServerId().equalsIgnoreCase(message.sourceServerId());

        for (RedisMessageListener<?> listener : listeners) {
            if (!listener.getChannel().equals(channel)) {
                continue;
            }

            if (fromThisServer && !listener.isSelfActivation()) {
                continue;
            }

            deliver(listener, message);
        }
    }

    private <T> void deliver(RedisMessageListener<T> listener, RedisMessage message) {
        T payload;

        try {
            payload = manager.getObjectMapper().treeToValue(message.payload(), listener.getPayloadType());
        } catch (JacksonException exception) {
            manager.getLogger().log(Level.WARNING, "[REDIS]: Message on channel '" + listener.getChannel() + "' could not be read as " + listener.getPayloadType().getSimpleName() + " for " + listener.getClass().getName() + ".", exception);
            return;
        }

        if (payload == null) {
            manager.getLogger().warning("[REDIS]: Message on channel '" + listener.getChannel() + "' had no payload for " + listener.getClass().getName() + ".");
            return;
        }

        ReceivedMessage<T> received = new ReceivedMessage<>(this, message, payload);
        runCallback("Listener " + listener.getClass().getName() + " on channel '" + listener.getChannel() + "'", () -> listener.onReceive(received));
    }

    private void receiveResponse(String raw) {
        RedisMessage message = parse(raw, "response topic");

        if (message == null) {
            return;
        }

        if (manager.isDebug()) {
            manager.getLogger().info(String.format(INCOMING_DEBUG_FORMAT, message.sourceClientId(), message.targetServerId(), raw));
        }

        UUID replyTo = message.replyTo();

        if (replyTo == null) {
            manager.getLogger().warning("[REDIS]: Received a response without a reply id.");
            return;
        }

        RedisRequest<?> request = pendingRequests.get(replyTo);

        if (request == null) {
            return;
        }

        if (request.complete(message)) {
            pendingRequests.remove(replyTo);
        }
    }

    private void expireRequest(UUID requestId) {
        RedisRequest<?> request = pendingRequests.remove(requestId);

        if (request == null) {
            return;
        }

        request.expire();
    }

    private RedisMessage parse(String raw, String source) {
        RedisMessage message;

        try {
            message = manager.getObjectMapper().readValue(raw, RedisMessage.class);
        } catch (JacksonException exception) {
            manager.getLogger().log(Level.WARNING, "[REDIS]: Malformed message received on " + source + ".", exception);
            return null;
        }

        if (message == null || message.id() == null || message.sourceServerId() == null || message.payload() == null) {
            manager.getLogger().warning("[REDIS]: Incomplete message received on " + source + ".");
            return null;
        }

        return message;
    }

    private boolean hasListeners(String channel) {
        for (RedisMessageListener<?> listener : listeners) {
            if (listener.getChannel().equals(channel)) {
                return true;
            }
        }

        return false;
    }

    private void removeSubscription(String topicName, int subscriptionId) {
        if (manager.getRedissonClient().isShutdown()) {
            return;
        }

        try {
            getTopic(topicName).removeListener(subscriptionId);
        } catch (RuntimeException exception) {
            manager.getLogger().log(Level.WARNING, "[REDIS]: Unable to unsubscribe from '" + topicName + "'.", exception);
        }
    }

    private RTopic getTopic(String topicName) {
        return manager.getRedissonClient().getTopic(topicName, StringCodec.INSTANCE);
    }

    private static String responseTopicName(String serverId, String clientId) {
        return String.format(RESPONSE_TOPIC_FORMAT, serverId, clientId);
    }

    public @NotNull List<RedisMessageListener<?>> getListeners() {
        return List.copyOf(listeners);
    }

}
