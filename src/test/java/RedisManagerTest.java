import com.redis.testcontainers.RedisContainer;
import net.bitbylogic.rps.RedisManager;
import net.bitbylogic.rps.client.RedisClient;
import net.bitbylogic.rps.listener.ListenerComponent;
import net.bitbylogic.rps.listener.RedisMessageListener;
import net.bitbylogic.rps.timed.RedisTimedRequest;
import net.bitbylogic.rps.timed.RedisTimedResponse;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RedisManagerTest {

    private RedisContainer redisContainer;

    private RedisManager redisManager;
    private RedisManager otherManager;

    private RedisClient client;
    private RedisClient otherClient;

    @BeforeAll
    public void startRedis() {
        redisContainer = new RedisContainer(DockerImageName.parse("redis:6.2.6")).withExposedPorts(6379);
        redisContainer.start();

        String host = redisContainer.getHost();
        int port = redisContainer.getMappedPort(6379);

        redisManager = new RedisManager(host, port, null, "test-server");
        otherManager = new RedisManager(host, port, null, "other-server");

        client = redisManager.registerClient("test-client");
        otherClient = otherManager.registerClient("other-client");
    }

    @AfterAll
    public void stopRedis() {
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @Test
    public void testDuplicateClient() {
        var client1 = redisManager.registerClient("client1");
        var client2 = redisManager.registerClient("CLIENT1");
        Assertions.assertSame(client1, client2);
    }

    @Test
    public void testDuplicateClientIdsAcrossManagers() {
        RedisClient c1 = redisManager.registerClient("shared-id");
        RedisClient c2 = otherManager.registerClient("SHARED-ID");

        Assertions.assertNotSame(c1, c2, "Clients in different managers should be separate even with same ID");
    }

    @Test
    public void testerMessageAndReceive() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        otherClient.registerListener(new RedisMessageListener("test-channel") {
            @Override
            public void onReceive(@NotNull ListenerComponent component) {
                Assertions.assertEquals("hello", component.getData("msg", String.class));
                latch.countDown();
            }
        });

        ListenerComponent message = new ListenerComponent("other-server", "test-channel")
                .addData("msg", "hello");

        client.sendListenerMessage(message);

        boolean received = latch.await(5, TimeUnit.SECONDS);
        Assertions.assertTrue(received, "Message should have been received by listener");
    }

    @Test
    public void testSelfActivation() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        client.registerListener(new RedisMessageListener("self-test-channel") {
            {
                setAllowSelfActivation();
            }

            @Override
            public void onReceive(@NotNull ListenerComponent component) {
                Assertions.assertEquals("hello", component.getData("msg", String.class));
                latch.countDown();
            }
        });

        ListenerComponent message = new ListenerComponent("self-test-channel")
                .addData("msg", "hello").selfActivation(true);

        client.sendListenerMessage(message);

        boolean received = latch.await(5, TimeUnit.SECONDS);
        Assertions.assertTrue(received, "Message should have been received by listener");
    }

    @Test
    public void testMultipleListenersReceive() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        RedisMessageListener listener1 = new RedisMessageListener("multi-channel") {
            @Override
            public void onReceive(@NotNull ListenerComponent component) {
                latch.countDown();
            }
        };

        RedisMessageListener listener2 = new RedisMessageListener("multi-channel") {
            @Override
            public void onReceive(@NotNull ListenerComponent component) {
                latch.countDown();
            }
        };

        otherClient.registerListener(listener1);
        otherClient.registerListener(listener2);

        ListenerComponent message = new ListenerComponent("other-server", "multi-channel")
                .addData("msg", "ping");

        client.sendListenerMessage(message);

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Both listeners should have received the message");
    }

    @Test
    public void testIgnoreOtherServerMessages() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        client.registerListener(new RedisMessageListener("ignore-channel") {
            @Override
            public void onReceive(@NotNull ListenerComponent component) {
                latch.countDown();
            }
        });

        ListenerComponent message = new ListenerComponent("some-other-server", "ignore-channel")
                .addData("msg", "should be ignored");

        otherClient.sendListenerMessage(message);

        Assertions.assertFalse(latch.await(1, TimeUnit.SECONDS), "Listener should ignore messages from other servers");
    }

    @Test
    public void testTimedRequestTimeout() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        RedisTimedRequest request = new RedisTimedRequest("request1", component -> {}, unused -> latch.countDown());

        ListenerComponent message = new ListenerComponent("test-channel")
                .addTimedRequest(TimeUnit.MILLISECONDS, 100, request);

        client.sendListenerMessage(message);

        boolean triggered = latch.await(1, TimeUnit.SECONDS);
        Assertions.assertTrue(triggered, "Timed request timeout callback should trigger");
    }

    @Test
    public void testTimedRequestSuccess() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        RedisTimedRequest request = new RedisTimedRequest("request-success", component -> latch.countDown(), unused -> {});

        ListenerComponent message = new ListenerComponent("test-channel")
                .addTimedRequest(TimeUnit.MILLISECONDS, -1, request);

        client.sendListenerMessage(message);

        ListenerComponent response = new ListenerComponent("test-server", "test-channel")
                .addTimedResponse(new RedisTimedResponse(request));

        otherClient.sendTimedResponse(response);

        Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed request success callback should trigger");
    }

    @Test
    public void testMalformedJsonDoesNotCrash() {
        client.getRedisClient().getTopic("test-channel").publish("INVALID_JSON");
    }

}
