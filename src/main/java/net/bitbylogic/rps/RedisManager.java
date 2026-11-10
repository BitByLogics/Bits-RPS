package net.bitbylogic.rps;

import lombok.Getter;
import lombok.Setter;
import net.bitbylogic.rps.client.RedisClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class RedisManager implements AutoCloseable {

    private final ConcurrentHashMap<String, RedisClient> clients = new ConcurrentHashMap<>();
    private final ReentrantLock clientLock = new ReentrantLock();

    @Getter
    private final String serverId;

    @Getter
    private final RedissonClient redissonClient;

    @Getter
    private final ObjectMapper objectMapper;

    @Getter
    @Setter
    private volatile boolean debug;

    @Getter
    private volatile @NotNull Logger logger = Logger.getLogger("Bits-RPS");

    @Getter
    private volatile @NotNull Executor callbackExecutor = Runnable::run;

    public RedisManager(@NotNull String host, int port, @Nullable String password, @NotNull String serverId) {
        this(createConfig(host, port, password), serverId);
    }

    public RedisManager(@NotNull Config redisConfig, @NotNull String serverId) {
        this(redisConfig, serverId, JsonMapper.builder().build());
    }

    public RedisManager(@NotNull Config redisConfig, @NotNull String serverId, @NotNull ObjectMapper objectMapper) {
        this.serverId = serverId;
        this.objectMapper = objectMapper;
        this.redissonClient = Redisson.create(redisConfig);
    }

    public void setLogger(@NotNull Logger logger) {
        this.logger = logger;
    }

    public void setCallbackExecutor(@NotNull Executor callbackExecutor) {
        this.callbackExecutor = callbackExecutor;
    }

    public @NotNull RedisClient registerClient(@NotNull String id) {
        String key = id.toLowerCase();

        clientLock.lock();

        try {
            RedisClient existing = clients.get(key);

            if (existing != null && !existing.isClosed()) {
                logger.warning("[REDIS]: Attempted to register RedisClient with duplicate ID '" + id + "'");
                return existing;
            }

            RedisClient client = new RedisClient(this, id);
            clients.put(key, client);

            return client;
        } finally {
            clientLock.unlock();
        }
    }

    public boolean unregisterClient(@NotNull String id) {
        RedisClient client;

        clientLock.lock();

        try {
            client = clients.remove(id.toLowerCase());
        } finally {
            clientLock.unlock();
        }

        if (client == null) {
            return false;
        }

        client.close();
        return true;
    }

    public boolean unregisterClient(@NotNull RedisClient client) {
        boolean removed;

        clientLock.lock();

        try {
            removed = clients.remove(client.getClientId().toLowerCase(), client);
        } finally {
            clientLock.unlock();
        }

        client.close();
        return removed;
    }

    public @NotNull Optional<RedisClient> getClient(@NotNull String id) {
        return Optional.ofNullable(clients.get(id.toLowerCase()));
    }

    public @NotNull List<RedisClient> getClients() {
        return List.copyOf(clients.values());
    }

    @Override
    public void close() {
        List<RedisClient> registered;

        clientLock.lock();

        try {
            registered = List.copyOf(clients.values());
            clients.clear();
        } finally {
            clientLock.unlock();
        }

        for (RedisClient client : registered) {
            client.close();
        }

        if (redissonClient.isShutdown()) {
            return;
        }

        redissonClient.shutdown();
    }

    private static Config createConfig(String host, int port, String password) {
        Config config = new Config();

        config
                .useSingleServer()
                .setAddress(String.format("redis://%s:%s", host, port))
                .setPingConnectionInterval(30_000)
                .setConnectTimeout(20_000)
                .setTimeout(10_000)
                .setRetryDelay(i -> Duration.ofSeconds(5))
                .setConnectionMinimumIdleSize(4)
                .setConnectionPoolSize(32);

        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }

        return config;
    }

}
