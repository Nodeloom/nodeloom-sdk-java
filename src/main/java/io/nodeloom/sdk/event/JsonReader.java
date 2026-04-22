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
        // Parse numbers strictly following RFC 8259 grammar so malformed input
        // like "1e+5e+2" or "01" is rejected instead of silently corrupted.
        int start = pos;
        if (peek() == '-') pos++;

        // Integer part: '0' or ('1'-'9') digit*
        if (pos >= input.length()) throw new JsonParseException("Unterminated number at offset " + start);
        char first = input.charAt(pos);
        if (first == '0') {
            pos++;
        } else if (first >= '1' && first <= '9') {
            pos++;
            while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
        } else {
            throw new JsonParseException("Invalid number starting at offset " + start);
        }

        // Fractional part: '.' digit+
        boolean hasFraction = false;
        if (pos < input.length() && input.charAt(pos) == '.') {
            pos++;
            hasFraction = true;
            int fracStart = pos;
            while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
            if (pos == fracStart) {
                throw new JsonParseException("Fractional part requires at least one digit at offset " + start);
            }
        }

        // Exponent: ('e' | 'E') ('+' | '-')? digit+
        boolean hasExponent = false;
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            pos++;
            hasExponent = true;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
            int expStart = pos;
            while (pos < input.length() && isDigit(input.charAt(pos))) pos++;
            if (pos == expStart) {
                throw new JsonParseException("Exponent requires at least one digit at offset " + start);
            }
        }

        String num = input.substring(start, pos);
        if (hasFraction || hasExponent) {
            return Double.parseDouble(num);
        }
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            return Double.parseDouble(num);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
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
