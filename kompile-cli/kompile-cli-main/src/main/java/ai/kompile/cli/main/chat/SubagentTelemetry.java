package ai.kompile.cli.main.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Display-only child metrics. Provider usage is authoritative; streamed text is explicitly estimated. */
public final class SubagentTelemetry {
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private long input, output, cacheRead, cacheCreation, streamChars, tools;
    private boolean measured;

    public synchronized void put(String key, String value) {
        if (value != null && !value.isBlank()) metadata.put(key, value);
    }

    /** Settled deltas, not cumulative request snapshots. */
    public synchronized void usage(long in, long out, long read, long creation) {
        measured = true;
        input += Math.max(0, in);
        output += Math.max(0, out);
        cacheRead += Math.max(0, read);
        cacheCreation += Math.max(0, creation);
        if (out > 0) streamChars = 0;
    }

    public synchronized void stream(String text) {
        if (text != null) streamChars += text.length();
    }

    public synchronized void beginRequest() { streamChars = 0; }
    public synchronized void tool() { tools++; }

    public synchronized Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>(metadata);
        if (measured) {
            result.put("input_tokens", Long.toString(input));
            result.put("output_tokens", Long.toString(output));
            result.put("cache_read_tokens", Long.toString(cacheRead));
            result.put("cache_creation_tokens", Long.toString(cacheCreation));
        }
        // Keep a provider's aggregate total distinct from partially reported categories.
        if (tools > 0) result.putIfAbsent("tool_uses", Long.toString(tools));
        result.put("stream_output_tokens", Long.toString((streamChars + 3) / 4));
        return Map.copyOf(result);
    }
}
