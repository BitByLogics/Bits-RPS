package net.bitbylogic.rps.listener;

import com.google.gson.Gson;
import lombok.Getter;
import lombok.Setter;
import net.bitbylogic.rps.client.RedisClient;
import net.bitbylogic.rps.timed.RedisTimedRequest;
import net.bitbylogic.rps.timed.RedisTimedResponse;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Getter
public class ListenerComponent {

    @Setter
    private RedisClient source;

    private final HashMap<String, String> data = new HashMap<>();
    private final HashMap<RedisTimedRequest, Long> timedRequests = new HashMap<>();
    private final List<RedisTimedResponse> timedResponses = new ArrayList<>();

    private final String targetServerId;
    private final String channel;

    private boolean allowRequestSelfActivation;

    public ListenerComponent() {
        this(null, null);
    }

    /**
     * Constructs a new {@code ListenerComponent} instance with the specified channel name.
     *
     * @param channel the name of the Redis channel associated with this ListenerComponent.
     *                Can be {@code null} if no channel is specified.
     */
    public ListenerComponent(@Nullable String channel) {
        this(null, channel);
    }

    /**
     * Constructs a new {@code ListenerComponent} instance with the specified target server ID and channel.
     *
     * @param targetServerId the ID of the target server associated with this ListenerComponent. Can be {@code null}.
     * @param channel the name of the Redis channel associated with this ListenerComponent. Can be {@code null}.
     */
    private ListenerComponent(@Nullable String targetServerId, @Nullable String channel) {
        this(targetServerId, channel, new HashMap<>());
    }

    /**
     * Constructs a new {@code ListenerComponent} instance with the specified target server ID, channel, and data.
     *
     * @param targetServerId the ID of the target server associated with this ListenerComponent. Can be {@code null}.
     * @param channel the name of the Redis channel associated with this ListenerComponent. Can be {@code null}.
     * @param data a {@link HashMap} containing initial key-value pairs to populate the component's data. Cannot be {@code null}.
     */
    public ListenerComponent(@Nullable String targetServerId, @Nullable String channel, @NotNull HashMap<String, String> data) {
        this.targetServerId = targetServerId;
        this.channel = channel;

        this.data.putAll(data);
    }

    /**
     * Adds a key-value pair to the internal data map. The key is a string, and
     * the value is serialized into JSON format using Gson before being stored.
     *
     * @param key the key associated with the data to add; must not be null.
     * @param object the object to serialize and store as the value; must not be null.
     * @return the current instance of {@code ListenerComponent}, allowing method chaining.
     */
    @Contract("_, _ -> this")
    public ListenerComponent addData(@NotNull String key, @NotNull Object object) {
        data.put(key, new Gson().toJson(object));
        return this;
    }

    /**
     * Adds a raw key-value pair to the internal data storage of the {@code ListenerComponent}.
     *
     * @param key   the key to associate with the value. It cannot be null.
     * @param value the value to store, associated with the provided key. It cannot be null.
     * @return the current instance of {@code ListenerComponent} to allow for method chaining.
     */
    @Contract("_, _ -> this")
    public ListenerComponent addDataRaw(@NotNull String key, @NotNull String value) {
        data.put(key, value);
        return this;
    }

    /**
     * Adds a timed request to the listener component. The request will be tracked for the specified
     * duration and can execute associated callbacks for success or timeout scenarios.
     *
     * @param unit    the time unit for the duration, cannot be {@code null}.
     * @param time    the duration for which the request is tracked, in the specified time unit.
     * @param request the {@link RedisTimedRequest} object to be tracked, cannot be {@code null}.
     * @return the current instance of {@link ListenerComponent}, allowing for method chaining.
     */
    @Contract("_, _, _ -> this")
    public ListenerComponent addTimedRequest(@NotNull TimeUnit unit, int time, @NotNull RedisTimedRequest request) {
        timedRequests.put(request, unit.toMillis(time));
        return this;
    }

    /**
     * Adds a timed response to the list of timed responses managed by the {@code ListenerComponent}.
     *
     * @param response the {@link RedisTimedResponse} to be added. Must not be {@code null}.
     * @return the {@code ListenerComponent} instance for method-chaining.
     */
    @Contract("_ -> this")
    public ListenerComponent addTimedResponse(@NotNull RedisTimedResponse response) {
        timedResponses.add(response);
        return this;
    }

    /**
     * Configures whether self-activation is allowed for this {@code ListenerComponent}.
     *
     * @param selfActivation a boolean value indicating whether self-activation is enabled.
     *                        If {@code true}, self-activation is allowed;
     *                        otherwise, it is disabled.
     * @return the current instance of {@code ListenerComponent}, allowing for method chaining.
     */
    @Contract("_ -> this")
    public ListenerComponent selfActivation(boolean selfActivation) {
        this.allowRequestSelfActivation = selfActivation;
        return this;
    }

    /**
     * Retrieves data stored in the component using the specified key and deserializes it
     * into an object of the specified type.
     *
     * @param <T>  the type of the object to be returned
     * @param key  the key identifying the stored data; must not be {@code null}
     * @param type the {@code Class} object representing the type to deserialize to; must not be {@code null}
     * @return the deserialized object of type {@code T}, or {@code null} if the key is not found in the data
     */
    @CheckReturnValue
    public <T> T getData(@NotNull String key, @NotNull Class<T> type) {
        if (source.getRedisManager() == null || !data.containsKey(key)) {
            return null;
        }

        return source.getRedisManager().getGson().fromJson(data.get(key), type);
    }

    /**
     * Retrieves data associated with a specific key and attempts to deserialize it into the specified type.
     * If the key is not present in the data, the provided fallback value is returned.
     *
     * @param <T>      the type of the object to be returned
     * @param key      the key identifying the data to retrieve; must not be null
     * @param type     the {@code Class} object representing the type to deserialize to; must not be null
     * @param fallback the fallback value to return if the key is not found in the data; must not be null
     * @return the deserialized object of type {@code T} if the key is found, or the provided fallback value otherwise
     */
    @CheckReturnValue
    public <T> T getDataOrElse(@NotNull String key, @NotNull Class<T> type, @NotNull T fallback) {
        return data.containsKey(key) ? getData(key, type) : fallback;
    }

    /**
     * Retrieves a {@link RedisTimedRequest} from the internal collection of timed requests
     * based on the provided unique identifier.
     *
     * @param id the unique identifier of the request to retrieve; must not be {@code null}.
     * @return the {@link RedisTimedRequest} associated with the given ID, or {@code null} if no matching request is found.
     */
    @CheckReturnValue
    public RedisTimedRequest getRequestByID(@NotNull String id) {
        return timedRequests.keySet().stream().filter(request -> request.getId().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

}
