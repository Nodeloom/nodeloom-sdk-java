package io.nodeloom.sdk.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser for SDK responses.
 *
 * <p>Supports: object, array, string, number, boolean, null. Returns the
 * parsed structure as Java {@link Map}/{@link List}/primitives so callers can
 * read fields without an external dependency.</p>
 *
 * <p>This is intentionally a small parser tailored to the well-formed JSON
 * the NodeLoom backend returns. It does not attempt to be a fully spec-
 * compliant parser (no surrogate-pair edge cases, no leniency flags). For
 * unknown input shapes it throws {@link JsonParseException}.</p>
 */
public final class JsonReader {

    private final String input;
    private int pos;

    private JsonReader(String input) {
        this.input = input;
        this.pos = 0;
    }

    /** Parse a JSON document. Returns null when the input is null/blank. */
    public static Object parse(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;
        JsonReader reader = new JsonReader(trimmed);
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.pos != reader.input.length()) {
            throw new JsonParseException("Unexpected trailing content at offset " + reader.pos);
        }
        return value;
    }

    /** Convenience: parse and cast to {@link Map}. Returns empty map on null/non-object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String input) {
        Object value = parse(input);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= input.length()) {
            throw new JsonParseException("Unexpected end of input");
        }
        char c = input.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
            case 'f':
                return readBoolean();
            case 'n':
                return readNull();
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw new JsonParseException("Unexpected character '" + c + "' at offset " + pos);
        }
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') { pos++; return map; }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            Object value = readValue();
            map.put(key, value);
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            }
            if (next == '}') {
                pos++;
                return map;
            }
            throw new JsonParseException("Expected ',' or '}' at offset " + pos);
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') { pos++; return list; }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                pos++;
                continue;
            }
            if (next == ']') {
                pos++;
                return list;
            }
            throw new JsonParseException("Expected ',' or ']' at offset " + pos);
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= input.length()) throw new JsonParseException("Unterminated escape");
                char esc = input.charAt(pos++);
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > input.length()) throw new JsonParseException("Invalid unicode escape");
                        int cp = Integer.parseInt(input.substring(pos, pos + 4), 16);
                        sb.append((char) cp);
                        pos += 4;
                        break;
                    default:
                        throw new JsonParseException("Invalid escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new JsonParseException("Unterminated string");
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String num = input.substring(start, pos);
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return Double.parseDouble(num);
        }
    }

    private Boolean readBoolean() {
        if (input.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (input.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new JsonParseException("Invalid boolean literal at offset " + pos);
    }

    private Object readNull() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new JsonParseException("Invalid null literal at offset " + pos);
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= input.length()) {
            throw new JsonParseException("Unexpected end of input at offset " + pos);
        }
        return input.charAt(pos);
    }

    private void expect(char c) {
        if (pos >= input.length() || input.charAt(pos) != c) {
            throw new JsonParseException("Expected '" + c + "' at offset " + pos);
        }
        pos++;
    }

    public static final class JsonParseException extends RuntimeException {
        public JsonParseException(String message) {
            super(message);
        }
    }
}
