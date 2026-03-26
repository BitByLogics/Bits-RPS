package net.bitbylogic.rps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.Setter;
import net.bitbylogic.rps.client.RedisClient;
import net.bitbylogic.rps.gson.TimedRequestSerializer;
import net.bitbylogic.rps.timed.RedisTimedRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Getter
public class RedisManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().enableComplexMapKeySerialization()
            .registerTypeHierarchyAdapter(RedisTimedRequest.class, new TimedRequestSerializer()).create();

    private final ConcurrentHashMap<String, RedisClient> clients = new ConcurrentHashMap<>();
    private final String serverId;

    private RedissonClient redissonClient;

    @Setter
    private boolean debug;

    /**
     * Constructs a new RedisManager instance with the specified host, port, password, and server ID.
     * Initializes the RedissonClient with a single server configuration.
     *
     * @param host      the hostname or IP address of the Redis server; must not be null
     * @param port      the port number for the Redis server
     * @param password  the password for the Redis server; can be null or empty if no authentication is required
     * @param serverId  the unique identifier for the server; must not be null
     */
    public RedisManager(@NotNull String host, int port, @Nullable String password, @NotNull String serverId) {
        this.serverId = serverId;

        Config config = new Config();

        config.setPassword(password == null ? null : password.isEmpty() ? null : password)
                .useSingleServer()
                .setAddress(String.format("redis://%s:%s", host, port))
                .setPingConnectionInterval(30_000)
                .setConnectTimeout(20_000)
                .setTimeout(10_000)
                .setRetryDelay(i -> Duration.ofSeconds(5))
                .setConnectionMinimumIdleSize(4)
                .setConnectionPoolSize(32);

        try {
            redissonClient = Redisson.create(config);
        } catch (Exception exception) {
            Logger.getGlobal().log(Level.SEVERE, "[REDIS]: Unable to connect to redis, contact developer with error below.", exception);
        }
    }

    /**
     * Creates a new instance of the RedisManager class, initializing the
     * RedissonClient instance with the given configuration.
     *
     * @param redisConfig the configuration object for the Redis connection; must not be null
     * @param serverId    the unique identifier for the server; must not be null
     */
    public RedisManager(@NotNull Config redisConfig, @NotNull String serverId) {
        this.serverId = serverId;

        try {
            redissonClient = Redisson.create(redisConfig);
        } catch (Exception exception) {
            Logger.getGlobal().log(Level.SEVERE, "[REDIS]: Unable to connect to redis, contact developer with error below.", exception);
        }
    }

    /**
     * Registers a new RedisClient with the specified ID. If a client with the same ID
     * already exists, it returns the existing client instead of creating a new one.
     *
     * @param id the unique identifier for the RedisClient to register; must not be null
     * @return the newly registered RedisClient instance, or the existing RedisClient
     *         instance if a client with the same ID is already registered
     */
    public @NotNull RedisClient registerClient(@NotNull String id) {
        String key = id.toLowerCase();

        if (clients.containsKey(key)) {
            Logger.getGlobal().warning("[REDIS]: Attempted to register RedisClient with duplicate ID '" + id + "'");
            return clients.get(key);
        }

        RedisClient client = new RedisClient(this, id);
        clients.put(key, client);

        return client;
    }

    /**
     * Retrieves the instance of {@code Gson} used by the {@code RedisManager}.
     *
     * @return the {@code Gson} instance used for JSON serialization and deserialization.
     */
    public Gson getGson() {
        return GSON;
    }

}
