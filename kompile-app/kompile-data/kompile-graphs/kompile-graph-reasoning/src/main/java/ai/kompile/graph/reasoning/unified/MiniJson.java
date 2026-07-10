/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.unified;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small, self-contained, dependency-free JSON reader/writer used by {@link UnifiedGraph}'s
 * persistence for its structural (non-vector) sections.
 *
 * <p>The reasoning library deliberately excludes {@code jackson-databind} (see the module POM); the
 * existing hand-rolled serializers ({@link ai.kompile.graph.reasoning.embedding.learn.EmbeddingTableIO},
 * {@code PslWeightLearningService}, {@code MebnWeightSerializer}) each parse their own fixed schema
 * with ad-hoc substring scanning. A unified graph needs to round-trip <em>arbitrary</em>
 * {@code attributes} maps (nested objects/arrays/strings/numbers/booleans/nulls), so it needs a real
 * parser. This class provides exactly that in pure JDK — no Spring, no JPA, no jackson-databind.</p>
 *
 * <h2>Java value mapping</h2>
 * <table>
 *   <caption>JSON &harr; Java</caption>
 *   <tr><th>JSON</th><th>Java (parse result)</th><th>Java (accepted for write)</th></tr>
 *   <tr><td>object</td><td>{@link LinkedHashMap}&lt;String,Object&gt;</td><td>{@link Map}</td></tr>
 *   <tr><td>array</td><td>{@link ArrayList}&lt;Object&gt;</td><td>{@link Iterable}, {@code Object[]}, primitive arrays</td></tr>
 *   <tr><td>string</td><td>{@link String}</td><td>{@link String}, {@link CharSequence}, {@link Enum}</td></tr>
 *   <tr><td>integral number</td><td>{@link Long}</td><td>{@link Byte}/{@link Short}/{@link Integer}/{@link Long}</td></tr>
 *   <tr><td>real number</td><td>{@link Double}</td><td>{@link Float}/{@link Double}</td></tr>
 *   <tr><td>true/false</td><td>{@link Boolean}</td><td>{@link Boolean}</td></tr>
 *   <tr><td>null</td><td>{@code null}</td><td>{@code null}</td></tr>
 * </table>
 *
 * <p>Non-finite doubles ({@code NaN}, {@code +/-Infinity}) are emitted as JSON <em>strings</em>
 * ({@code "NaN"}, {@code "Infinity"}, {@code "-Infinity"}) because the JSON grammar has no literal
 * for them; the typed decoders tolerate that on read. Values that are none of the accepted write
 * types are emitted as their {@link String#valueOf(Object) string form} — the contract is that
 * {@code attributes} hold JSON-representable values (this mirrors how the infra layer already stores
 * node/edge metadata as JSON).</p>
 */
public final class MiniJson {

    private MiniJson() { }

    // ═════════════════════════════════════════════════════════════════════════
    // Writing
    // ═════════════════════════════════════════════════════════════════════════

    /** Serialize a Java value to a compact (no whitespace) JSON string. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(64);
        writeValue(sb, value);
        return sb.toString();
    }

    /** Append the JSON form of {@code v} to {@code sb}. */
    public static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue() ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            writeDouble(sb, ((Number) v).doubleValue());
        } else if (v instanceof Number n) {
            sb.append(n.toString()); // integral: Byte/Short/Integer/Long/BigInteger
        } else if (v instanceof Map<?, ?> m) {
            writeObject(sb, m);
        } else if (v instanceof Iterable<?> it) {
            writeArray(sb, it);
        } else if (v instanceof Object[] arr) {
            writeArray(sb, Arrays.asList(arr));
        } else if (v instanceof double[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); writeDouble(sb, arr[i]); }
            sb.append(']');
        } else if (v instanceof float[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); writeDouble(sb, arr[i]); }
            sb.append(']');
        } else if (v instanceof long[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
            sb.append(']');
        } else if (v instanceof int[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
            sb.append(']');
        } else if (v instanceof boolean[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i] ? "true" : "false"); }
            sb.append(']');
        } else {
            // Enums, Instant, UUID, and any other value type: fall back to its string form.
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() == null) continue; // JSON keys must be strings; skip null keys
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        boolean first = true;
        for (Object o : it) {
            if (!first) sb.append(',');
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    /**
     * Emit a double. Finite values use {@link Double#toString(double)} (shortest round-trippable
     * decimal); non-finite values are emitted as JSON strings since JSON has no literal for them.
     */
    private static void writeDouble(StringBuilder sb, double d) {
        if (Double.isFinite(d)) {
            sb.append(Double.toString(d));
        } else if (Double.isNaN(d)) {
            sb.append("\"NaN\"");
        } else if (d > 0) {
            sb.append("\"Infinity\"");
        } else {
            sb.append("\"-Infinity\"");
        }
    }

    /** Append {@code s} as a fully-escaped JSON string literal (including surrounding quotes). */
    public static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format(Locale.ROOT, "%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Parsing
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Parse a JSON document into Java values (see the class-level mapping table).
     *
     * @throws IllegalArgumentException if the input is not well-formed JSON
     */
    public static Object parse(String json) {
        Parser p = new Parser(json);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("MiniJson: trailing content at index " + p.pos);
        }
        return v;
    }

    /** Parse a JSON document expected to be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("MiniJson: expected a JSON object, got "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object readValue() {
            skipWs();
            if (atEnd()) throw err("unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return out; }
            while (true) {
                skipWs();
                if (peek() != '"') throw err("expected string key");
                String key = readString();
                skipWs();
                expect(':');
                Object val = readValue();
                out.put(key, val);
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw err("expected ',' or '}'");
            }
            return out;
        }

        List<Object> readArray() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return out; }
            while (true) {
                out.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw err("expected ',' or ']'");
            }
            return out;
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw err("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (atEnd()) throw err("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            if (pos + 4 > s.length()) throw err("bad \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw err("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw err("invalid literal");
        }

        Object readNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw err("invalid literal");
        }

        Object readNumber() {
            int start = pos;
            boolean real = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    real = true;
                    pos++;
                } else {
                    break;
                }
            }
            String tok = s.substring(start, pos);
            if (tok.isEmpty()) throw err("invalid number");
            if (real) {
                return Double.parseDouble(tok);
            }
            try {
                return Long.parseLong(tok);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(tok);
            }
        }

        char peek() { skipWs(); return atEnd() ? '\0' : s.charAt(pos); }
        char next() { if (atEnd()) throw err("unexpected end of input"); return s.charAt(pos++); }
        void expect(char c) { char g = next(); if (g != c) throw err("expected '" + c + "' but got '" + g + "'"); }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("MiniJson parse error at index " + pos + ": " + msg);
        }
    }
}
