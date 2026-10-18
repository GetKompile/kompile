package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Soft text-ingress pressure, not a token count or a hard provider-context quota. */
final class ToolOutputBudget {
    record Settings(boolean enabled, int inlineChars, int fetchChars, int floorChars,
                    int windowChars, long windowMillis) {
        static Settings defaults() { return new Settings(true, 8000, 6000, 2048, 64000, 300_000); }
        static Settings legacy() { return new Settings(false, 16000, 16000, 2048, 64000, 300_000); }
    }

    private final Settings settings;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new LinkedHashMap<>(16, .75f, true);

    ToolOutputBudget(Settings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
    }

    static ToolOutputBudget load(Path workDir, LongSupplier clock) {
        Settings value = overlay(Settings.defaults(), KompileHome.homeDirectory().toPath()
                .resolve("config/tool-output-budget.json"));
        if (workDir != null) value = overlay(value, workDir.resolve("config/tool-output-budget.json"));
        return new ToolOutputBudget(value, clock);
    }

    private static Settings overlay(Settings base, Path file) {
        if (!Files.exists(file)) return base;
        try {
            if (Files.size(file) > 65536) throw new IOException("configuration exceeds 64 KiB");
            JsonNode node = JsonUtils.standardMapper().readTree(Files.readString(file));
            if (node == null || !node.isObject()) throw new IOException("expected an object");
            boolean enabled = base.enabled();
            if (node.has("enabled")) {
                if (!node.path("enabled").isBoolean()) throw new IOException("enabled must be boolean");
                enabled = node.path("enabled").asBoolean();
            }
            int inline = number(node, "inlineChars", base.inlineChars(), 2048, 16000);
            int fetch = number(node, "fetchChars", base.fetchChars(), 2048, 16000);
            int floor = Math.min(base.floorChars(), Math.min(inline, fetch));
            floor = number(node, "floorChars", floor, 2048, Math.min(inline, fetch));
            return new Settings(enabled, inline, fetch, floor,
                    number(node, "windowChars", base.windowChars(), 16384, 4_000_000),
                    1000L * number(node, "windowSeconds", (int) (base.windowMillis() / 1000), 1, 86400));
        } catch (IOException | RuntimeException failure) {
            // Configuration diagnostics must not echo an arbitrarily large JSON source.
            String reason = failure instanceof JsonProcessingException ? "malformed JSON" : failure.getMessage();
            if (reason == null) reason = failure.getClass().getSimpleName();
            reason = reason.replace('\n', ' ').replace('\r', ' ');
            System.err.println("[kompile] Ignoring invalid tool-output budget at " + file + ": "
                    + reason.substring(0, Math.min(160, reason.length())));
            return base;
        }
    }

    private static int number(JsonNode node, String key, int fallback, int min, int max) throws IOException {
        if (!node.has(key)) return fallback;
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < min || value.intValue() > max) {
            throw new IOException(key + " must be an integer in [" + min + ", " + max + "]");
        }
        return value.intValue();
    }

    synchronized Reservation reserve(String owner, boolean fetch) {
        if (!settings.enabled()) return new Reservation(null, 16000);
        String key = owner == null ? "mcp" : owner;
        long now = clock.getAsLong();
        Window window = windows.get(key);
        if (window == null || now < window.started || now - window.started >= settings.windowMillis()) {
            window = new Window(now);
            windows.put(key, window);
            if (windows.size() > 128) windows.remove(windows.keySet().iterator().next());
        }
        int base = fetch ? settings.fetchChars() : settings.inlineChars();
        int allowance = (int) Math.max(settings.floorChars(), Math.min(base, settings.windowChars() - window.chars));
        window.chars += allowance; // Reserve before work so concurrent calls share pressure.
        return new Reservation(window, allowance);
    }

    final class Reservation implements AutoCloseable {
        private final Window window;
        private final int limit;
        private boolean completed;
        Reservation(Window window, int limit) { this.window = window; this.limit = limit; }
        int limit() { return limit; }
        void complete(int actualChars) {
            synchronized (ToolOutputBudget.this) {
                if (completed) return;
                completed = true;
                if (window != null) window.chars = Math.max(0, window.chars - limit + Math.max(0, actualChars));
            }
        }
        @Override public void close() { complete(0); }
    }

    private static final class Window {
        final long started;
        long chars;
        Window(long started) { this.started = started; }
    }
}
