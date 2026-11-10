package net.bitbylogic.rps;

import net.bitbylogic.rps.model.RedisMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

public class RedisMessageTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    public void roundTripKeepsAllFields() {
        JsonNode payload = mapper.valueToTree(new Sample("hello", 3));
        UUID id = UUID.randomUUID();
        UUID replyTo = UUID.randomUUID();

        RedisMessage original = new RedisMessage(id, "channel", "server-a", "client-a", "server-b", replyTo, payload);
        RedisMessage read = mapper.readValue(mapper.writeValueAsString(original), RedisMessage.class);

        Assertions.assertEquals(original, read);
        Assertions.assertEquals(new Sample("hello", 3), mapper.treeToValue(read.payload(), Sample.class));
    }

    @Test
    public void nullFieldsAreOmitted() {
        RedisMessage message = new RedisMessage(UUID.randomUUID(), "channel", "server-a", "client-a", null, null, mapper.valueToTree("text"));
        JsonNode tree = mapper.valueToTree(message);

        Assertions.assertFalse(tree.has("targetServerId"));
        Assertions.assertFalse(tree.has("replyTo"));
        Assertions.assertTrue(tree.has("payload"));
    }

    @Test
    public void unknownPropertiesAreIgnored() {
        String raw = "{\"id\":\"" + UUID.randomUUID() + "\",\"channel\":\"c\",\"sourceServerId\":\"s\",\"payload\":1,\"futureField\":true}";
        RedisMessage message = mapper.readValue(raw, RedisMessage.class);

        Assertions.assertEquals("s", message.sourceServerId());
        Assertions.assertEquals(1, message.payload().intValue());
    }

    public record Sample(String text, int count) {

    }

}
