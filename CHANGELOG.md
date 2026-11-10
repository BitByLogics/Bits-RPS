# Changelog

## 2.0.0

### Breaking
- Messages carry one typed payload instead of a `HashMap<String, String>`. `ListenerComponent#addData`, `addDataRaw`, `getData`, `getDataOrElse` are replaced by `RedisClient#send(channel, payload)` and `ReceivedMessage#getPayload()`. The payload is any class Jackson can serialize, such as a record.
- `ListenerComponent` is removed. The wire format is the immutable `RedisMessage`, and nothing on it is mutated by the client. `sendListenerMessage(component)` becomes `send(channel, payload)` or `sendTo(serverId, channel, payload)`.
- `RedisMessageListener` is now `RedisMessageListener<T>` and takes the payload class: `new RedisMessageListener<>(channel, Payload.class)`. `onReceive` gets a `ReceivedMessage<T>`. `getChannelName` is now `getChannel`. `setAllowSelfActivation()` is replaced by the third constructor argument. `getClient` and `setClient` are removed.
- `RedisTimedRequest`, `RedisTimedResponse`, `ListenerComponent#addTimedRequest`, `addTimedResponse`, `selfActivation(boolean)`, `getRequestByID` and `RedisClient#sendTimedResponse` are replaced by `RedisClient#request(channel, payload, ResponseType.class, timeout)` and `ReceivedMessage#reply(payload)`. A request always has a timeout, so the `-1` "never expires" value is gone.
- The timeout callback is a `Runnable` instead of `Consumer<Void>`.
- Responses use a per-client topic (`rps:response:<serverId>:<clientId>`) instead of the shared `INTERNAL_REDIS_REQUESTS` topic, and a response is matched by id. The reply flag `allowRequestSelfActivation` is removed: a reply from the same server is always accepted.
- Redis payloads changed. 2.x and 1.x servers cannot talk to each other. Upgrade every server together.
- `RedisManager#getObjectMapper()` is no longer static. Each manager owns its mapper, and a custom `ObjectMapper` can be passed to the constructor.
- The `RedisManager` constructors throw if Redis cannot be reached instead of logging and leaving a `null` client.
- `RedisManager#getClients()` returns a copy. `RedisClient#getListeners()` returns a copy, and `getTimedRequests`, `getRequestTimers`, `getRegisteredChannels` and `getRedisClient()` are removed. Use `RedisManager#getRedissonClient()`.
- Channel names are matched exactly. They used to be matched ignoring case, although Redis topics are case sensitive.
- The `serialization` and `timed` packages are removed.
- The jar is no longer shaded. Redisson and Jackson are normal transitive dependencies, so they can be relocated or shared by the consuming plugin.
- `RedisClient#close()` now also unregisters the client from its manager, and a closed client is replaced when its id is registered again.

### Added
- `RedisClient#registerListener(channel, PayloadType.class, handler)` registers a lambda listener, and `unregisterListener` removes one and drops the subscription when it was the last on its channel.
- `RedisRequest` with `targetServer`, `onSuccess`, `onTimeout` and `send`.
- `RedisManager#unregisterClient`, `getClient`, `close()`, `setLogger` and an `ObjectMapper` constructor. `RedisClient#close()` cancels subscriptions, pending requests and its timeout thread.
- `send`, `sendTo`, `reply` and `RedisRequest#send` return whether the message was published and log the cause when it was not.
- `RedisManager#setCallbackExecutor` runs listener, success and timeout callbacks on an executor of your choice, for example a Paper or Folia scheduler. The default runs them directly on the calling Redisson or timeout thread.
- `sendAsync`, `sendToAsync`, `replyAsync` and `RedisRequest#sendAsync` return a `CompletableFuture<Boolean>` instead of blocking on the publish.
- Unit and Testcontainers tests, JaCoCo coverage, SpotBugs analysis, source and Javadoc jars, Dependabot, a GitHub Actions build with a dependency review on pull requests, and a tag-driven release workflow.
- MIT license, security policy and contributing guide.

### Fixed
- Messages were encoded with the Redisson default codec, so a subscriber would deserialize whatever binary data was published to its topic. Topics now use plain strings and only JSON is parsed.
- A request with a zero or negative timeout is clamped to one second with a warning instead of expiring immediately.
- Channel listeners were never subscribed after the Jackson migration (`registerChannel` returned early for every new channel).
- Every timed request created its own `Timer` thread, and the timer and request were left behind when a send failed or a late response arrived. One daemon thread per client now handles timeouts, and a finished request can complete only once.
- Timed responses were found by scanning a list. They are looked up by id.
- Registering the same client id from two threads could create two clients.
- Sending, replying or registering after the Redis connection was shut down returned only after hanging in Redisson. It now logs a warning and returns `false`.
- Listener and callback exceptions are caught and logged instead of escaping into Redisson.
- Malformed or incomplete messages are logged and dropped.
