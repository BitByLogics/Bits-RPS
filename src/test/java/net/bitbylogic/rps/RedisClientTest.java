package net.bitbylogic.rps;

import com.redis.testcontainers.RedisContainer;
import net.bitbylogic.rps.client.RedisClient;
import net.bitbylogic.rps.listener.ReceivedMessage;
import net.bitbylogic.rps.listener.RedisMessageListener;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.client.codec.StringCodec;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RedisClientTest {

    private RedisContainer redisContainer;

    private RedisManager managerA;
    private RedisManager managerB;

    private RedisClient clientA;
    private RedisClient clientB;

    private String host;
    private int port;

    @BeforeAll
    public void startRedis() {
        redisContainer = new RedisContainer(DockerImageName.parse("redis:6.2.6")).withExposedPorts(6379);
        redisContainer.start();

        host = redisContainer.getHost();
        port = redisContainer.getMappedPort(6379);

        managerA = new RedisManager(host, port, null, "server-a");
        managerB = new RedisManager(host, port, null, "server-b");

        clientA = managerA.registerClient("client-a");
        clientB = managerB.registerClient("client-b");
    }

    @AfterAll
    public void stopRedis() {
        managerA.close();
        managerB.close();
        redisContainer.stop();
    }

    @Test
    public void typedMessageIsDeliveredToOtherServer() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Greeting> received = new AtomicReference<>();
        AtomicReference<String> source = new AtomicReference<>();

        clientB.registerListener("typed", Greeting.class, message -> {
            received.set(message.getPayload());
            source.set(message.getSourceServerId() + "/" + message.getSourceClientId());
            latch.countDown();
        });

        Assertions.assertTrue(clientA.send("typed", new Greeting("hello", 7)));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(new Greeting("hello", 7), received.get());
        Assertions.assertEquals("server-a/client-a", source.get());
    }

    @Test
    public void targetedMessageOnlyReachesTargetServer() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        clientB.registerListener("targeted", String.class, message -> latch.countDown());

        Assertions.assertTrue(clientA.sendTo("some-other-server", "targeted", "ignored"));
        Assertions.assertFalse(latch.await(1, TimeUnit.SECONDS));

        Assertions.assertTrue(clientA.sendTo("server-b", "targeted", "delivered"));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void ownServerIsIgnoredUnlessSelfActivationIsEnabled() throws InterruptedException {
        CountDownLatch ignored = new CountDownLatch(1);
        CountDownLatch accepted = new CountDownLatch(1);

        clientA.registerListener(new RedisMessageListener<>("self", String.class) {
            @Override
            public void onReceive(@NotNull ReceivedMessage<String> message) {
                ignored.countDown();
            }
        });

        clientA.registerListener(new RedisMessageListener<>("self", String.class, true) {
            @Override
            public void onReceive(@NotNull ReceivedMessage<String> message) {
                accepted.countDown();
            }
        });

        Assertions.assertTrue(clientA.send("self", "hello"));
        Assertions.assertTrue(accepted.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(ignored.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void everyListenerOnAChannelReceivesTheMessage() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        clientB.registerListener("multi", String.class, message -> latch.countDown());
        clientB.registerListener("multi", String.class, message -> latch.countDown());

        Assertions.assertTrue(clientA.send("multi", "ping"));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void listenerWithMismatchedPayloadTypeIsSkipped() throws InterruptedException {
        CountDownLatch matching = new CountDownLatch(1);
        AtomicInteger mismatched = new AtomicInteger();

        clientB.registerListener("mismatch", Greeting.class, message -> mismatched.incrementAndGet());
        clientB.registerListener("mismatch", String.class, message -> matching.countDown());

        Assertions.assertTrue(clientA.send("mismatch", "just text"));
        Assertions.assertTrue(matching.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(0, mismatched.get());
    }

    @Test
    public void throwingListenerDoesNotStopOthers() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        clientB.registerListener("throwing", String.class, message -> {
            throw new IllegalStateException("expected by test");
        });
        clientB.registerListener("throwing", String.class, message -> latch.countDown());

        Assertions.assertTrue(clientA.send("throwing", "ping"));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void malformedJsonIsIgnoredAndLaterMessagesStillArrive() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        clientB.registerListener("malformed", String.class, message -> latch.countDown());

        managerA.getRedissonClient().getTopic("malformed").publish("INVALID_JSON");
        managerA.getRedissonClient().getTopic("malformed", StringCodec.INSTANCE).publish("INVALID_JSON");
        managerA.getRedissonClient().getTopic("malformed", StringCodec.INSTANCE).publish("{\"unrelated\":true}");
        Assertions.assertFalse(latch.await(500, TimeUnit.MILLISECONDS));

        Assertions.assertTrue(clientA.send("malformed", "valid"));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void unregisteredListenerStopsReceiving() throws InterruptedException {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);

        RedisMessageListener<String> listener = clientB.registerListener("unregister", String.class, message -> {
            count.incrementAndGet();
            first.countDown();
        });

        Assertions.assertTrue(clientA.send("unregister", "one"));
        Assertions.assertTrue(first.await(5, TimeUnit.SECONDS));

        Assertions.assertTrue(clientB.unregisterListener(listener));
        Assertions.assertFalse(clientB.unregisterListener(listener));

        Assertions.assertTrue(clientA.send("unregister", "two"));
        Thread.sleep(500);
        Assertions.assertEquals(1, count.get());
    }

    @Test
    public void duplicateListenerRegistrationIsRejected() {
        RedisMessageListener<String> listener = new RedisMessageListener<>("duplicate", String.class) {
            @Override
            public void onReceive(@NotNull ReceivedMessage<String> message) {
            }
        };

        Assertions.assertTrue(clientB.registerListener(listener));
        Assertions.assertFalse(clientB.registerListener(listener));
    }

    @Test
    public void requestReceivesTypedReply() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Pong> pong = new AtomicReference<>();
        AtomicReference<String> responder = new AtomicReference<>();
        AtomicInteger timeouts = new AtomicInteger();

        clientB.registerListener("ping", Ping.class, message -> message.reply(new Pong(message.getPayload().value() + 1)));

        boolean sent = clientA.request("ping", new Ping(41), Pong.class, Duration.ofSeconds(5))
                .targetServer("server-b")
                .onSuccess(response -> {
                    pong.set(response.getPayload());
                    responder.set(response.getSourceServerId());
                    latch.countDown();
                })
                .onTimeout(timeouts::incrementAndGet)
                .send();

        Assertions.assertTrue(sent);
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(new Pong(42), pong.get());
        Assertions.assertEquals("server-b", responder.get());
        Assertions.assertEquals(0, timeouts.get());
    }

    @Test
    public void requestWithoutReplyTimesOut() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        boolean sent = clientA.request("nobody-listens", new Ping(1), Pong.class, Duration.ofMillis(200))
                .onSuccess(response -> successes.incrementAndGet())
                .onTimeout(latch::countDown)
                .send();

        Assertions.assertTrue(sent);
        Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS));
        Assertions.assertEquals(0, successes.get());
    }

    @Test
    public void lateReplyAfterTimeoutIsIgnored() throws InterruptedException {
        CountDownLatch timedOut = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();

        clientB.registerListener("slow", Ping.class, message -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }

            message.reply(new Pong(1));
        });

        boolean sent = clientA.request("slow", new Ping(1), Pong.class, Duration.ofMillis(200))
                .onSuccess(response -> successes.incrementAndGet())
                .onTimeout(timedOut::countDown)
                .send();

        Assertions.assertTrue(sent);
        Assertions.assertTrue(timedOut.await(3, TimeUnit.SECONDS));
        Thread.sleep(1200);
        Assertions.assertEquals(0, successes.get());
    }

    @Test
    public void requestCanOnlyBeSentOnce() {
        var request = clientA.request("once", new Ping(1), Pong.class, Duration.ofMillis(100));

        Assertions.assertTrue(request.send());
        Assertions.assertFalse(request.send());
    }

    @Test
    public void nonPositiveTimeoutFallsBackAndStillTimesOut() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        boolean sent = clientA.request("zero-timeout", new Ping(1), Pong.class, Duration.ZERO)
                .onTimeout(latch::countDown)
                .send();

        Assertions.assertTrue(sent);
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void invalidSendsAreRejected() {
        Assertions.assertFalse(clientA.send(" ", "payload"));
    }

    @Test
    public void duplicateClientIdsShareOneClientPerManager() {
        RedisClient first = managerA.registerClient("dup-client");
        RedisClient second = managerA.registerClient("DUP-CLIENT");
        RedisClient other = managerB.registerClient("dup-client");

        Assertions.assertSame(first, second);
        Assertions.assertNotSame(first, other);
        Assertions.assertSame(first, managerA.getClient("Dup-Client").orElseThrow());
    }

    @Test
    public void unregisteredClientCannotSendAndCanBeRegisteredAgain() {
        RedisClient temporary = managerA.registerClient("temporary");

        Assertions.assertTrue(managerA.unregisterClient("temporary"));
        Assertions.assertTrue(temporary.isClosed());
        Assertions.assertFalse(temporary.send("closed", "payload"));
        Assertions.assertFalse(managerA.unregisterClient("temporary"));

        RedisClient replacement = managerA.registerClient("temporary");
        Assertions.assertNotSame(temporary, replacement);
        Assertions.assertTrue(replacement.send("closed", "payload"));
    }

    @Test
    public void mapAndJsonNodePayloadsAreSupported() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Map<?, ?>> map = new AtomicReference<>();
        AtomicReference<JsonNode> node = new AtomicReference<>();

        clientB.registerListener("loose", Map.class, message -> {
            map.set(message.getPayload());
            latch.countDown();
        });
        clientB.registerListener("loose", JsonNode.class, message -> {
            node.set(message.getPayload());
            latch.countDown();
        });

        Assertions.assertTrue(clientA.send("loose", Map.of("id", 5, "name", "Steve")));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals("Steve", map.get().get("name"));
        Assertions.assertEquals(5, node.get().get("id").intValue());
    }

    @Test
    public void asyncSendDeliversAndReportsSuccess() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        clientB.registerListener("async", String.class, message -> latch.countDown());

        Assertions.assertTrue(clientA.sendAsync("async", "hello").get(5, TimeUnit.SECONDS));
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(clientA.sendAsync(" ", "hello").get(5, TimeUnit.SECONDS));
    }

    @Test
    public void asyncRequestAndReplyWork() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Pong> pong = new AtomicReference<>();

        clientB.registerListener("async-ping", Ping.class, message -> message.replyAsync(new Pong(message.getPayload().value() * 2)));

        boolean sent = clientA.request("async-ping", new Ping(21), Pong.class, Duration.ofSeconds(5))
                .onSuccess(response -> {
                    pong.set(response.getPayload());
                    latch.countDown();
                })
                .sendAsync()
                .get(5, TimeUnit.SECONDS);

        Assertions.assertTrue(sent);
        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals(new Pong(42), pong.get());
    }

    @Test
    public void callbacksRunOnTheCallbackExecutor() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "custom-callbacks"));
        RedisManager managerC = new RedisManager(host, port, null, "server-c");
        managerC.setCallbackExecutor(executor);

        try {
            RedisClient clientC = managerC.registerClient("client-c");
            CountDownLatch latch = new CountDownLatch(3);
            AtomicReference<String> listenerThread = new AtomicReference<>();
            AtomicReference<String> successThread = new AtomicReference<>();
            AtomicReference<String> timeoutThread = new AtomicReference<>();

            clientC.registerListener("executor-listener", String.class, message -> {
                listenerThread.set(Thread.currentThread().getName());
                latch.countDown();
            });
            clientB.registerListener("executor-ping", Ping.class, message -> message.reply(new Pong(1)));

            Assertions.assertTrue(clientA.send("executor-listener", "hello"));

            clientC.request("executor-ping", new Ping(1), Pong.class, Duration.ofSeconds(5))
                    .onSuccess(response -> {
                        successThread.set(Thread.currentThread().getName());
                        latch.countDown();
                    })
                    .send();

            clientC.request("executor-nobody", new Ping(1), Pong.class, Duration.ofMillis(200))
                    .onTimeout(() -> {
                        timeoutThread.set(Thread.currentThread().getName());
                        latch.countDown();
                    })
                    .send();

            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            Assertions.assertEquals("custom-callbacks", listenerThread.get());
            Assertions.assertEquals("custom-callbacks", successThread.get());
            Assertions.assertEquals("custom-callbacks", timeoutThread.get());
        } finally {
            managerC.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void debugModeLogsOutgoingAndIncomingMessages() throws InterruptedException {
        List<String> logged = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("rps-debug-test");
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        RedisManager managerD = new RedisManager(host, port, null, "server-d");
        managerD.setLogger(logger);
        managerD.setDebug(true);

        try {
            RedisClient clientD = managerD.registerClient("client-d");
            CountDownLatch latch = new CountDownLatch(1);

            clientD.registerListener(new RedisMessageListener<>("debug", String.class, true) {
                @Override
                public void onReceive(@NotNull ReceivedMessage<String> message) {
                    latch.countDown();
                }
            });

            Assertions.assertTrue(clientD.send("debug", "hello"));
            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            Assertions.assertTrue(logged.stream().anyMatch(line -> line.startsWith("[OUTGOING]")));
            Assertions.assertTrue(logged.stream().anyMatch(line -> line.startsWith("[INCOMING]")));
        } finally {
            managerD.close();
        }
    }

    @Test
    public void failedPublishReturnsFalse() throws Exception {
        RedisManager managerE = new RedisManager(host, port, null, "server-e");
        RedisClient clientE = managerE.registerClient("client-e");

        managerE.getRedissonClient().shutdown();

        Assertions.assertFalse(clientE.send("failing", "hello"));
        Assertions.assertFalse(clientE.sendAsync("failing", "hello").get(5, TimeUnit.SECONDS));
        Assertions.assertFalse(clientE.request("failing", new Ping(1), Pong.class, Duration.ofSeconds(1)).send());

        managerE.close();
    }

    @Test
    public void concurrentRegistrationCreatesOneClient() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Future<RedisClient>> futures = new ArrayList<>();

        for (int index = 0; index < 16; index++) {
            futures.add(executor.submit(() -> managerA.registerClient("concurrent")));
        }

        Set<RedisClient> unique = new HashSet<>();

        for (Future<RedisClient> future : futures) {
            unique.add(future.get(5, TimeUnit.SECONDS));
        }

        executor.shutdownNow();
        Assertions.assertEquals(1, unique.size());
    }

    @Test
    public void closingAClientRemovesItFromTheManager() {
        RedisClient client = managerA.registerClient("self-closing");

        client.close();

        Assertions.assertTrue(client.isClosed());
        Assertions.assertTrue(managerA.getClient("self-closing").isEmpty());
        Assertions.assertFalse(managerA.getClients().contains(client));
    }

    public record Greeting(String text, int count) {

    }

    public record Ping(int value) {

    }

    public record Pong(int value) {

    }

}
