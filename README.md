# Bits-RPS

A Redis Pub/Sub wrapper on top of Redisson for sending typed messages between servers, with request and reply support.

## Concepts

* **RedisManager** owns the Redis connection, the JSON mapper and the clients of one server. It has a server id that identifies this server to the others.
* **RedisClient** is a named messaging endpoint of that server. It sends messages, registers listeners and sends requests.
* **Message** is one payload object of your own type (a record works well) on a channel. It can be broadcast or addressed to one server id.
* **RedisMessageListener&lt;T&gt;** receives messages of one channel and one payload class. Messages whose payload does not fit the class are skipped for that listener.
* **RedisRequest&lt;R&gt;** sends a message and waits for a typed reply for a limited time.

## Requirements

* Java 21
* Redis 6.2 or newer (tested with 6.2.6)

## Installation

```xml
<repository>
    <id>minebit</id>
    <url>https://repo.minebit.dev/releases</url>
</repository>

<dependency>
    <groupId>net.bitbylogic</groupId>
    <artifactId>bits-rps</artifactId>
    <version>2.0.0</version>
</dependency>
```

## Quick start

```java
record PlayerJoined(UUID uuid, String name) {
}

RedisManager manager = new RedisManager("127.0.0.1", 6379, "secret", "lobby-1");
RedisClient client = manager.registerClient("main");

client.registerListener("player-joined", PlayerJoined.class, message -> {
    PlayerJoined joined = message.getPayload();
    String from = message.getSourceServerId();
});

client.send("player-joined", new PlayerJoined(uuid, name));
client.sendTo("proxy-1", "player-joined", new PlayerJoined(uuid, name));
```

`manager.close()` closes every client and the Redis connection. `manager.unregisterClient("main")` or `client.close()` closes one client.

## Requests and replies

```java
record Ping(int value) {
}

record Pong(int value) {
}

otherClient.registerListener("ping", Ping.class, message -> {
    message.reply(new Pong(message.getPayload().value() + 1));
});

client.request("ping", new Ping(41), Pong.class, Duration.ofSeconds(5))
        .targetServer("lobby-2")
        .onSuccess(response -> System.out.println(response.getPayload().value()))
        .onTimeout(() -> System.out.println("No reply"))
        .send();
```

Only the first reply is used. A reply that arrives after the timeout is ignored.

## Routing

* A message without a target is received by every server that has a listener on the channel.
* A listener ignores messages sent by its own server unless it is created with the self activation flag: `new RedisMessageListener<>(channel, Payload.class, true)`.
* Channel names are case sensitive.

## Threading

`send`, `sendTo`, `reply` and `RedisRequest#send` block until Redis acknowledges the publish. Each has an async variant (`sendAsync`, `sendToAsync`, `replyAsync`, `RedisRequest#sendAsync`) that returns a `CompletableFuture<Boolean>`. The first request of a client, and every `registerListener`, still subscribe synchronously.

Listeners and success callbacks run on a Redisson thread, and timeout callbacks on a daemon thread of the client. Set a callback executor to run all of them somewhere else, for example on your server scheduler:

```java
manager.setCallbackExecutor(task -> Bukkit.getGlobalRegionScheduler().execute(plugin, task));
```

## Delivery and limits

* Redis Pub/Sub is at-most-once. A server that is disconnected, restarting or not yet subscribed misses the message, and nothing is retried or stored. Use a request with a timeout when you need to know that someone received it.
* Every server needs a unique server id. Two servers with the same id treat each other's messages as their own and drop them unless the listener has self activation enabled.
* Debug mode (`manager.setDebug(true)`) logs the full JSON of every message. Do not leave it on in production when payloads hold private data.
* Adding a field to a payload class is safe for older receivers, since unknown properties are ignored. Renaming or removing one is not.

## Payloads

Payloads are serialized with Jackson 3 and sent as plain JSON strings. Redis connection options such as TLS, Sentinel or Cluster come from the Redisson `Config` you pass to the manager. Records and plain classes with getters work out of the box. To use extra modules, pass your own mapper: `new RedisManager(config, serverId, objectMapper)`.

## Building

```
mvn clean verify
```

This compiles, runs the tests against a Redis container (Docker has to be running), writes a coverage report to `target/site/jacoco` and runs SpotBugs.

## Releasing

Set the version in `pom.xml` and push a tag named exactly the version, for example `2.0.0`. The release workflow checks that the tag matches the pom, deploys to the Minebit repository and creates the GitHub release.

## Upgrading from 1.x

See the [changelog](CHANGELOG.md). Redis payloads are not compatible between 1.x and 2.x, so every server has to be updated together.

## Documentation

API documentation: https://bitbylogics.github.io/Bits-RPS/
