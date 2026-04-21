package io.nodeloom.sdk.event;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonReaderTest {

    @Test
    void parsesEmptyObject() {
        assertEquals(Map.of(), JsonReader.parseObject("{}"));
    }

    @Test
    void parsesPrimitivesAndNesting() {
        Map<String, Object> result = JsonReader.parseObject(
                "{\"name\":\"alice\",\"age\":42,\"active\":true,\"score\":3.14,\"tags\":[\"a\",\"b\"]," +
                "\"meta\":{\"key\":null}}");
        assertEquals("alice", result.get("name"));
        assertEquals(42L, result.get("age"));
        assertEquals(Boolean.TRUE, result.get("active"));
        assertEquals(3.14, result.get("score"));
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) result.get("tags");
        assertEquals(List.of("a", "b"), tags);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.get("meta");
        assertNull(meta.get("key"));
    }

    @Test
    void handlesEscapedStrings() {
        Map<String, Object> result = JsonReader.parseObject("{\"text\":\"line1\\nline2\\t\\\"quoted\\\"\"}");
        assertEquals("line1\nline2\t\"quoted\"", result.get("text"));
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(JsonReader.JsonParseException.class,
                () -> JsonReader.parseObject("{\"k\":1} extra"));
    }

    @Test
    void returnsEmptyMapForBlankInput() {
        assertTrue(JsonReader.parseObject(null).isEmpty());
        assertTrue(JsonReader.parseObject("").isEmpty());
    }
}
