# Bits-RPS

A developer-focused Redis Pub/Sub (RPS) wrapper built on top of Redisson. This library provides a structured, asynchronous messaging layer for Java applications, specifically designed for distributed systems that require reliable cross-service communication.

---

## Technical Overview

Bits-RPS abstracts the complexity of Redisson's `RTopic` into a managed system of clients and listeners. It features a built-in request-response pattern with timeout handling and automated JSON serialization via Gson.



### Key Components

* **RedisManager**: The central controller for the Redisson connection pool and GSON configuration.
* **RedisClient**: An isolated messaging node used to register listeners and publish components.
* **ListenerComponent**: The universal data carrier. It supports raw strings, serialized objects, and metadata for routing.
* **RedisTimedRequest**: A specialized mechanism for awaiting a response from a remote source with a defined `TimeUnit` expiry.

---

## Implementation

### 1. Initialization
The `RedisManager` handles connection parameters and sets up the internal GSON hierarchy for request/response tracking.

```java
// Parameters: host, port, password, sourceId
RedisManager redisManager = new RedisManager("127.0.0.1", 6379, "secret", "server-01");
```

### 2. Registering a Client and Listener
Listeners are registered to specific channels. The system supports "Self Activation" (deciding whether a client should process its own published messages).

```java
RedisClient client = redisManager.registerClient("main-client");

client.registerListener(new RedisMessageListener("game_updates") {
    @Override
    public void onReceive(ListenerComponent message) {
        String action = message.getData("action", String.class);
        // Process logic
    }
});
```

### 3. Executing a Timed Request
One of the core features is the ability to send a message and expect a callback from another service within a specific timeframe.

```java
ListenerComponent component = new ListenerComponent("target-id", "auth-channel");
component.addData("session_id", "abc-123");

// Define the request and callbacks
RedisTimedRequest request = new RedisTimedRequest("auth-verification");

request.onSuccess(response -> {
    System.out.println("Verification received!");
});

request.onTimeout(() -> {
    System.out.println("No response from target within 5 seconds.");
});

// Attach and send
component.addTimedRequest(TimeUnit.SECONDS, 5, request);
client.sendListenerMessage(component);
```

---

## Messaging Logic

The library utilizes a standardized routing header in every `ListenerComponent`:

| Field | Purpose |
| :--- | :--- |
| **source** | Automatically populated with the sending `RedisClient` metadata. |
| **target** | If null, the message is broadcast. If set, only the matching `sourceId` processes it. |
| **channel** | The Redis topic identifier. |
| **data** | A Map of serialized objects stored as JSON strings. |

---

## Dependencies

The project relies on the following stack:
* **Redisson**: High-level Java Redis client.
* **Lombok**: For boilerplate reduction.
* **Gson**: For object serialization.
