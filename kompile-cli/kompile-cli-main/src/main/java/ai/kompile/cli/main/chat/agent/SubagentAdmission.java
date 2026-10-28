package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.SubagentConfig;

import java.io.IOException;

/** One non-blocking admission counter for all chat runners in this JVM, not one per session. */
public final class SubagentAdmission {
    private static int active;
    private SubagentAdmission() { }

    public static Lease acquire(String provider, String model) throws IOException {
        return acquire(SubagentConfig.load(), provider, model);
    }

    static synchronized Lease acquire(SubagentConfig config, String provider, String model) {
        validateRoute(config, provider, model);
        if (config.maxConcurrent() >= 0 && active >= config.maxConcurrent()) {
            throw new IllegalStateException("Subagent concurrency limit reached (" + active + "/" + config.maxConcurrent()
                    + "). Wait for a running child to finish or change /subagent-config max. No subagent was launched.");
        }
        active++;
        return new Lease();
    }

    /** A running child keeps its existing lease when switching route. */
    public static void validateRoute(String provider, String model) throws IOException {
        validateRoute(SubagentConfig.load(), provider, model);
    }

    static void validateRoute(SubagentConfig config, String provider, String model) {
        if (!config.bannedModels().isEmpty() && (model == null || model.isBlank())) {
            throw new IllegalStateException("Subagent model cannot be verified against model bans on this route; select an explicit model or use direct-model chat. No subagent was launched.");
        }
        String ban = config.matchingBan(provider, model);
        if (ban != null) throw new IllegalStateException("Subagent model '" + model + "' is banned by pattern '" + ban + "' (/subagent-config). No subagent was launched.");
    }

    public static synchronized int activeCount() { return active; }

    /** Idempotent so failure cleanup and normal completion can safely share ownership. */
    public static final class Lease implements AutoCloseable {
        private boolean closed;
        private Lease() { }
        @Override public void close() {
            synchronized (SubagentAdmission.class) {
                if (!closed) { closed = true; active--; }
            }
        }
    }
}
