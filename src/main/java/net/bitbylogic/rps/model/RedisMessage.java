package net.bitbylogic.rps.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedisMessage(
        UUID id,
        String channel,
        String sourceServerId,
        String sourceClientId,
        String targetServerId,
        UUID replyTo,
        JsonNode payload
) {

}
