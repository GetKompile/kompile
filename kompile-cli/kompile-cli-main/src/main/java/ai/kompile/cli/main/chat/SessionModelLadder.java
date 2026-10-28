/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.DirectLlmClient.FailureKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-session model failover ladder: an ordered list of provider/model/credential
 * rungs this session may fall back to when the active one runs out of quota.
 *
 * <p>Two distinct concerns, deliberately kept apart:</p>
 * <ul>
 *   <li><b>User intent</b> — the ladder itself, loaded from layered config
 *       (global → project → session, the {@code ChatUiConfig} precedence) so a
 *       project default applies everywhere and one session can override it
 *       without rewriting the user's primary model.</li>
 *   <li><b>Runtime failover state</b> — the active rung, per-rung cooldowns and
 *       a bounded switch history, persisted beside it. A failover must never
 *       rewrite the user's chosen primary, and any explicit selection clears
 *       this state.</li>
 * </ul>
 *
 * <p>Decisions are driven by the typed {@link FailureKind} the provider
 * adapters already compute; never by matching failure prose. {@code QUOTA_EXHAUSTED}
 * climbs the ladder, {@code REFUSAL} never switches (evading a safety refusal is
 * circumvention, not resilience), and {@code CONTEXT_OVERFLOW} stays with
 * compaction because it owns that failure.</p>
 *
 * <p>Rungs are normalized into disruption tiers before selection so the
 * cheapest switch happens first: same provider/route keeps the provider's
 * native session and prompt cache (a model change), then a different credential
 * of the same provider (separate quota pool, same route), and only then a
 * different provider (new route, re-projected history, cold cache). The user's
 * configured order is preserved within a tier.</p>
 */
public final class SessionModelLadder {

    /** How a healthy rung is chosen among usable candidates. */
    public enum Ordering {
        /** First usable rung by tier, then configured order. Deterministic, cache friendly. */
        ORDERED,
        /** Among one tier, the rung whose last failure is oldest. */
        LEAST_RECENTLY_FAILED;

        static Ordering parse(String raw) {
            if (raw != null && "least-recently-failed".equalsIgnoreCase(raw.trim())) {
                return LEAST_RECENTLY_FAILED;
            }
            return ORDERED;
        }

        String wire() {
            return this == LEAST_RECENTLY_FAILED ? "least-recently-failed" : "ordered";
        }
    }

    /**
     * One rung. {@code provider} null means the provider active at failover
     * time; {@code credentialName} null means that session's active credential.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelRung(String provider, String model, String credentialName, String label,
                            String thinking, String authenticationMethod) {

        public ModelRung {
            model = model == null ? null : model.trim();
            provider = provider == null || provider.isBlank() ? null : provider.trim();
            credentialName = credentialName == null || credentialName.isBlank()
                    ? null : credentialName.trim();
            label = label == null || label.isBlank() ? null : label.trim();
            thinking = thinking == null || thinking.isBlank() ? null : thinking.trim();
            authenticationMethod = authenticationMethod == null || authenticationMethod.isBlank()
                    ? null : authenticationMethod.trim();
        }

        public ModelRung(String provider, String model, String credentialName, String label,
                         String thinking) {
            this(provider, model, credentialName, label, thinking, null);
        }

        public ModelRung(String provider, String model, String credentialName, String label) {
            this(provider, model, credentialName, label, null, null);
        }

        public ModelRung(String provider, String model) {
            this(provider, model, null, null);
        }

        public boolean hasModel() {
            return model != null && !model.isBlank();
        }

        String resolvedProvider(String activeProvider) {
            return provider == null ? activeProvider : provider;
        }

        String resolvedCredential(String activeCredential) {
            return credentialName == null && authenticationMethod == null ? activeCredential : credentialName;
        }

        /** Stable identity for cooldowns and de-duplication ({@code label} is cosmetic). */
        public String key(String activeProvider, String activeCredential) {
            String p = resolvedProvider(activeProvider);
            String c = resolvedCredential(activeCredential);
            return (p == null ? "" : p) + "|" + model + "|" + (c == null ? "" : c)
                    + (authenticationMethod == null ? "" : "|" + authenticationMethod);
        }

        /** Display form used by notices and {@code /model ladder}. */
        public String describe(String activeProvider) {
            String p = resolvedProvider(activeProvider);
            String base = (p == null || p.isBlank() ? "" : p + "/") + model;
            return label == null ? base : base + " (" + label + ")";
        }
    }

    /** A selected rung and why it was chosen. */
    public record Decision(ModelRung rung, String reason, Duration cooldown) { }

    /** One recorded switch, for {@code /model status} and diagnostics. */
    public record Swap(String from, String to, String reason, long atMs) { }

    private static final ObjectMapper JSON = JsonUtils.standardMapper();
    private static final String FILE = "model-ladder.json";
    private static final String GLOBAL_FILE = "model-ladder.json";

    /**
     * Default cooldown for an exhausted quota window with no parsable reset —
     * the same five-hour horizon {@link UsageLimitAutoContinue#DEFAULT_WINDOW}
     * assumes for subscription plans.
     */
    static final Duration DEFAULT_QUOTA_COOLDOWN = Duration.ofHours(5);
    /** Default cooldown for a per-minute throttle; see {@code HarnessConfig.rateLimitCooldownMs}. */
    static final Duration DEFAULT_RATE_LIMIT_COOLDOWN = Duration.ofMinutes(5);
    /** An account/org/region restriction rarely clears within a turn. */
    static final Duration DEFAULT_PERMISSION_COOLDOWN = Duration.ofHours(1);
    static final int MAX_HISTORY = 50;

    private final Clock clock;
    private final Path persistencePath;
    private final List<ModelRung> rungs;
    private boolean enabled;
    private boolean failoverOnRateLimit;
    private final int maxRungsPerTurn;
    private final Ordering ordering;
    private final Map<String, Long> cooldownUntil;
    private final List<Swap> history;
    private ModelRung activeRung;

    private SessionModelLadder(Clock clock, Path persistencePath, List<ModelRung> rungs,
                               boolean enabled, boolean failoverOnRateLimit, int maxRungsPerTurn,
                               Ordering ordering, Map<String, Long> cooldownUntil,
                               List<Swap> history, ModelRung activeRung) {
        this.clock = clock;
        this.persistencePath = persistencePath;
        this.rungs = new ArrayList<>(rungs);
        this.enabled = enabled;
        this.failoverOnRateLimit = failoverOnRateLimit;
        this.maxRungsPerTurn = Math.max(1, maxRungsPerTurn);
        this.ordering = ordering;
        this.cooldownUntil = new LinkedHashMap<>(cooldownUntil);
        this.history = new ArrayList<>(history);
        this.activeRung = activeRung;
    }

    // ------------------------------------------------------------------
    // Persistence (layered like ChatUiConfig: global, project, session)
    // ------------------------------------------------------------------

    public static Path globalPath() {
        return KompileHome.homeDirectory().toPath().resolve(GLOBAL_FILE);
    }

    public static Path projectPath(Path project) {
        return project.toAbsolutePath().normalize().resolve(".kompile").resolve(FILE);
    }

    public static Path sessionPath(String session) {
        if (session == null || !session.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid session id");
        }
        return KompileHome.homeDirectory().toPath().resolve("conversations").resolve(session + "." + FILE);
    }

    /**
     * Load the effective ladder for a session. Later layers override earlier
     * ones: a session file wins over the project ladder, which wins over the
     * global default. An unreadable layer is ignored rather than fatal.
     */
    public static SessionModelLadder load(String sessionId, Path projectRoot) {
        List<Path> layers = new ArrayList<>();
        layers.add(globalPath());
        if (projectRoot != null) layers.add(projectPath(projectRoot));
        Path sessionLayer = sessionId == null ? null : sessionPath(sessionId);
        if (sessionLayer != null) layers.add(sessionLayer);
        return load(layers, Clock.systemDefaultZone());
    }

    /** Child policy is independent of the main chat; runtime state belongs to its durable id. */
    public static SessionModelLadder forSubagent(String childId, Path projectRoot,
                                                List<ModelRung> rungs, boolean enabled) {
        SessionModelLadder state = load(List.of(sessionPath(childId)), Clock.systemDefaultZone());
        return new SessionModelLadder(state.clock, state.persistencePath, rungs, enabled, true,
                state.maxRungsPerTurn, state.ordering, state.cooldownUntil, state.history, state.activeRung);
    }

    /** Layered load with an explicit clock, for tests. */
    static SessionModelLadder load(List<Path> layers, Clock clock) {
        List<ModelRung> rungs = new ArrayList<>();
        boolean enabled = true;
        boolean failoverOnRateLimit = false;
        int maxRungsPerTurn = 3;
        Ordering ordering = Ordering.ORDERED;
        Map<String, Long> cooldowns = new LinkedHashMap<>();
        List<Swap> history = new ArrayList<>();
        ModelRung activeRung = null;
        // Edits always land in the most specific layer, so a ladder configured
        // inside one session is session-scoped: it overrides a project or global
        // default for that session without rewriting it for everyone else.
        Path persistencePath = layers.isEmpty() ? null : layers.get(layers.size() - 1);

        for (Path path : layers) {
            if (path == null || !Files.exists(path)) continue;
            try {
                JsonNode root = JSON.readTree(path.toFile());
                if (root == null || !root.isObject()) {
                    throw new IllegalArgumentException("Expected a JSON object");
                }
                if (root.has("rungs")) {
                    rungs = readRungs(root.get("rungs"));
                }
                if (root.has("enabled")) enabled = root.path("enabled").asBoolean(enabled);
                if (root.has("failoverOnRateLimit")) {
                    failoverOnRateLimit = root.path("failoverOnRateLimit").asBoolean(false);
                }
                if (root.has("maxRungsPerTurn")) {
                    maxRungsPerTurn = Math.max(1, root.path("maxRungsPerTurn").asInt(3));
                }
                if (root.has("selection")) ordering = Ordering.parse(root.path("selection").asText(""));
                // Cooldowns and the active rung are runtime state: only the session
                // layer they were written to is authoritative for them.
                if (root.has("cooldowns")) cooldowns = readCooldowns(root.get("cooldowns"));
                if (root.has("history")) history = readHistory(root.get("history"));
                if (root.has("activeRung")) activeRung = readRung(root.get("activeRung"));
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("Warning: ignoring invalid model ladder " + path + ": " + e.getMessage());
            }
        }
        return new SessionModelLadder(clock, persistencePath, rungs, enabled,
                failoverOnRateLimit, maxRungsPerTurn, ordering, cooldowns, history, activeRung);
    }

    public void save() throws IOException {
        if (persistencePath == null) return;
        ObjectNode root = JSON.createObjectNode();
        root.put("enabled", enabled);
        root.put("failoverOnRateLimit", failoverOnRateLimit);
        root.put("maxRungsPerTurn", maxRungsPerTurn);
        root.put("selection", ordering.wire());
        ArrayNode array = root.putArray("rungs");
        for (ModelRung rung : rungs) array.add(writeRung(rung));
        if (!cooldownUntil.isEmpty()) {
            ObjectNode cooldowns = root.putObject("cooldowns");
            cooldownUntil.forEach(cooldowns::put);
        }
        if (!history.isEmpty()) {
            ArrayNode recorded = root.putArray("history");
            for (Swap swap : history) {
                recorded.add(JSON.createObjectNode()
                        .put("from", swap.from()).put("to", swap.to())
                        .put("reason", swap.reason()).put("at", swap.atMs()));
            }
        }
        if (activeRung != null) root.set("activeRung", writeRung(activeRung));

        Files.createDirectories(persistencePath.getParent());
        Path temporary = Files.createTempFile(persistencePath.getParent(), ".model-ladder-", ".tmp");
        try {
            JSON.writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, persistencePath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, persistencePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<ModelRung> readRungs(JsonNode node) {
        List<ModelRung> parsed = new ArrayList<>();
        if (node == null || !node.isArray()) return parsed;
        for (JsonNode entry : node) {
            ModelRung rung = readRung(entry);
            if (rung != null) parsed.add(rung);
        }
        return parsed;
    }

    private static ModelRung readRung(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String model = node.path("model").asText("");
        if (model.isBlank()) return null;
        return new ModelRung(
                node.path("provider").asText(null),
                model,
                node.path("credentialName").asText(null),
                node.path("label").asText(null),
                node.path("thinking").asText(null),
                node.path("authenticationMethod").asText(null));
    }

    private static ObjectNode writeRung(ModelRung rung) {
        ObjectNode node = JSON.createObjectNode();
        if (rung.provider() != null) node.put("provider", rung.provider());
        node.put("model", rung.model());
        if (rung.credentialName() != null) node.put("credentialName", rung.credentialName());
        if (rung.label() != null) node.put("label", rung.label());
        if (rung.thinking() != null) node.put("thinking", rung.thinking());
        if (rung.authenticationMethod() != null) node.put("authenticationMethod", rung.authenticationMethod());
        return node;
    }

    private static Map<String, Long> readCooldowns(JsonNode node) {
        Map<String, Long> parsed = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return parsed;
        node.properties().forEach(entry -> {
            long until = entry.getValue().asLong(0L);
            if (until > 0L) parsed.put(entry.getKey(), until);
        });
        return parsed;
    }

    private static List<Swap> readHistory(JsonNode node) {
        List<Swap> parsed = new ArrayList<>();
        if (node == null || !node.isArray()) return parsed;
        for (JsonNode entry : node) {
            String from = entry.path("from").asText("");
            String to = entry.path("to").asText("");
            if (from.isBlank() && to.isBlank()) continue;
            parsed.add(new Swap(from, to, entry.path("reason").asText(""),
                    entry.path("at").asLong(0L)));
        }
        return parsed;
    }

    // ------------------------------------------------------------------
    // Configuration accessors and edits
    // ------------------------------------------------------------------

    public boolean enabled() { return enabled; }

    /** Master switch; a disabled ladder never switches, whatever its rungs. */
    public void setEnabled(boolean value) { this.enabled = value; }

    public boolean failoverOnRateLimit() { return failoverOnRateLimit; }

    public void setFailoverOnRateLimit(boolean value) { this.failoverOnRateLimit = value; }

    public int maxRungsPerTurn() { return maxRungsPerTurn; }

    public Ordering ordering() { return ordering; }

    public List<ModelRung> rungs() { return List.copyOf(rungs); }

    public boolean isEmpty() { return rungs.isEmpty(); }

    public Optional<ModelRung> activeRung() { return Optional.ofNullable(activeRung); }

    public List<Swap> history() { return List.copyOf(history); }

    /** Append a rung; returns false when the model is blank or already present. */
    public boolean addRung(ModelRung rung) {
        if (rung == null || !rung.hasModel()) return false;
        for (ModelRung existing : rungs) {
            if (existing.provider() == null ? rung.provider() == null
                    : existing.provider().equalsIgnoreCase(rung.provider())) {
                if (existing.model().equals(rung.model())
                        && java.util.Objects.equals(existing.credentialName(), rung.credentialName())
                        && java.util.Objects.equals(existing.authenticationMethod(), rung.authenticationMethod())) {
                    return false;
                }
            }
        }
        rungs.add(rung);
        return true;
    }

    /** Remove by 1-based index, as `/model ladder remove N` presents it. */
    public boolean removeRung(int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > rungs.size()) return false;
        rungs.remove(oneBasedIndex - 1);
        return true;
    }

    public void clearRungs() {
        rungs.clear();
        activeRung = null;
    }

    /** Apply an accepted wizard edit, dropping stale runtime selections. */
    public void replaceRungs(List<ModelRung> replacement) {
        clearRungs();
        if (replacement != null) replacement.forEach(this::addRung);
        noteUserSelection();
    }

    /** Move a rung, preserving the user's explicit ordering intent. */
    public boolean moveRung(int fromOneBased, int toOneBased) {
        if (fromOneBased < 1 || fromOneBased > rungs.size()) return false;
        if (toOneBased < 1 || toOneBased > rungs.size()) return false;
        ModelRung moved = rungs.remove(fromOneBased - 1);
        rungs.add(toOneBased - 1, moved);
        return true;
    }

    // ------------------------------------------------------------------
    // Failover decision
    // ------------------------------------------------------------------

    /**
     * Whether a failure of this kind may climb the ladder at all. Billing/quota
     * and identity restrictions are exactly what a second configured rung is
     * for; a policy refusal and a context overflow are not.
     */
    public boolean isSwitchEligible(FailureKind kind) {
        if (kind == null) return false;
        return switch (kind) {
            case QUOTA_EXHAUSTED -> true;
            case AUTHENTICATION, CREDENTIAL_UNAVAILABLE, PERMISSION_DENIED -> true;
            case RATE_LIMITED -> failoverOnRateLimit;
            // Refusing to evade a safety policy; compaction owns context overflow.
            case REFUSAL, CONTEXT_OVERFLOW -> false;
            // These are transient/protocol outcomes the in-band retry loop owns.
            case NONE, TEMPORARY, TRUNCATED, PROVIDER_ERROR -> false;
        };
    }

    /**
     * Pick the next rung for a switch-eligible failure.
     *
     * @param kind           typed provider failure
     * @param failureDetail  rendered failure, only used to read a reset horizon
     * @param activeProvider provider currently in use (for {@code null}-provider rungs)
     * @param activeModel    model currently in use; never re-selected
     * @param activeCredential credential currently in use
     * @param tried          rung keys already attempted this turn
     * @param attempt        how many rungs this turn has already switched to
     * @return the rung to switch to, or empty when the ladder cannot help
     */
    public Optional<Decision> next(FailureKind kind, String failureDetail, String activeProvider,
                                   String activeModel, String activeCredential,
                                   Set<String> tried, int attempt) {
        return next(kind, failureDetail, activeProvider, activeModel, activeCredential, tried, attempt, null);
    }

    public Optional<Decision> next(FailureKind kind, String failureDetail, String activeProvider,
                                   String activeModel, String activeCredential,
                                   Set<String> tried, int attempt, String activeAuthenticationMethod) {
        if (!enabled || !isSwitchEligible(kind)) return Optional.empty();
        if (attempt >= maxRungsPerTurn) return Optional.empty();

        Duration cooldown = cooldownFor(kind, failureDetail);
        String activeKey = keyOf(activeProvider, activeModel, activeCredential);
        Set<String> excluded = new LinkedHashSet<>(tried == null ? Set.of() : tried);
        excluded.add(activeKey);

        List<ModelRung> ordered = orderByTier(rungs, activeProvider, activeCredential);
        for (ModelRung rung : ordered) {
            String key = rung.key(activeProvider, activeCredential);
            if (excluded.contains(key) || isInCooldown(key)) continue;
            // Authentication and permission failures only clear on a different
            // identity: the same credential would fail identically again.
            if (requiresDifferentIdentity(kind)
                    && sameIdentity(rung, activeProvider, activeCredential)
                    && (rung.authenticationMethod() == null
                        || java.util.Objects.equals(rung.authenticationMethod(), activeAuthenticationMethod))) {
                continue;
            }
            return Optional.of(new Decision(rung, reasonFor(kind), cooldown));
        }
        return Optional.empty();
    }

    /** Tier of a rung relative to the currently active route: lower is cheaper. */
    int tierOf(ModelRung rung, String activeProvider, String activeCredential) {
        if (rung == null) return Integer.MAX_VALUE;
        if (!sameProvider(rung, activeProvider)) return 2;          // new route, cold cache
        if (!sameCredential(rung, activeCredential)) return 1;      // same route, other quota pool
        return 0;                                                   // same route+account: model only
    }

    private List<ModelRung> orderByTier(List<ModelRung> source, String activeProvider,
                                        String activeCredential) {
        List<ModelRung> ordered = new ArrayList<>(source);
        Map<ModelRung, Integer> configuredOrder = new LinkedHashMap<>();
        for (int i = 0; i < source.size(); i++) configuredOrder.put(source.get(i), i);
        ordered.sort((left, right) -> {
            int leftTier = tierOf(left, activeProvider, activeCredential);
            int rightTier = tierOf(right, activeProvider, activeCredential);
            if (leftTier != rightTier) return Integer.compare(leftTier, rightTier);
            if (ordering == Ordering.LEAST_RECENTLY_FAILED) {
                long leftAt = lastFailureAt(left, activeProvider, activeCredential);
                long rightAt = lastFailureAt(right, activeProvider, activeCredential);
                if (leftAt != rightAt) return Long.compare(leftAt, rightAt);
            }
            // Stable: the user's configured order wins inside a tier.
            return Integer.compare(configuredOrder.get(left), configuredOrder.get(right));
        });
        return ordered;
    }

    private long lastFailureAt(ModelRung rung, String activeProvider, String activeCredential) {
        Long until = cooldownUntil.get(rung.key(activeProvider, activeCredential));
        return until == null ? 0L : until;
    }

    /**
     * Cooldown to apply to the rung that just failed. A parsable reset horizon
     * is authoritative: it is the provider's own answer to "when does this
     * reopen", and sharing the parser with the auto-continue watchdog keeps the
     * two mechanisms from disagreeing.
     */
    public Duration cooldownFor(FailureKind kind, String failureDetail) {
        return switch (kind) {
            case QUOTA_EXHAUSTED -> {
                Duration hint = hintOf(failureDetail);
                yield hint == null ? DEFAULT_QUOTA_COOLDOWN : hint;
            }
            case RATE_LIMITED -> {
                Duration hint = hintOf(failureDetail);
                yield hint == null ? DEFAULT_RATE_LIMIT_COOLDOWN : hint;
            }
            case AUTHENTICATION, CREDENTIAL_UNAVAILABLE, PERMISSION_DENIED ->
                    DEFAULT_PERMISSION_COOLDOWN;
            default -> Duration.ZERO;
        };
    }

    private static Duration hintOf(String failureDetail) {
        if (failureDetail == null || failureDetail.isBlank()) return null;
        try {
            return UsageLimitAutoContinue.parseResetHint(failureDetail.toLowerCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            // A malformed hint is not evidence; the caller's default applies.
            return null;
        }
    }

    /** Record a failure: cooldown the rung and, on a switch, remember what happened. */
    public void markFailed(ModelRung rung, FailureKind kind, String failureDetail,
                           String activeProvider, String activeCredential) {
        if (rung == null) return;
        Duration cooldown = cooldownFor(kind, failureDetail);
        if (!cooldown.isZero()) {
            cooldownUntil.put(rung.key(activeProvider, activeCredential),
                    clock.millis() + cooldown.toMillis());
        }
    }

    /**
     * Record that this session is now running on {@code rung}. The user's
     * primary is untouched: {@link #noteUserSelection()} is the only thing that
     * clears it.
     */
    public void recordSwitch(String from, ModelRung rung, FailureKind kind, Duration cooldown,
                             String activeProvider, String activeCredential) {
        if (rung == null) return;
        activeRung = rung;
        // Only the failed source is cooled by markFailed; a successful target
        // must remain available on a later turn or after a durable resume.
        appendHistory(new Swap(from, rung.describe(activeProvider), kind.name(), clock.millis()));
    }

    /** The user picked a model/credential explicitly: manual intent wins. */
    public void noteUserSelection() {
        activeRung = null;
        cooldownUntil.clear();
    }

    public boolean isInCooldown(String key) {
        if (key == null) return false;
        Long until = cooldownUntil.get(key);
        if (until == null) return false;
        if (clock.millis() >= until) {
            cooldownUntil.remove(key);
            return false;
        }
        return true;
    }

    public long cooldownRemainingMs(String key) {
        Long until = cooldownUntil.get(key);
        if (until == null) return 0L;
        return Math.max(0L, until - clock.millis());
    }

    private void appendHistory(Swap swap) {
        history.add(swap);
        while (history.size() > MAX_HISTORY) history.remove(0);
    }

    // ------------------------------------------------------------------
    // Tier / identity helpers
    // ------------------------------------------------------------------

    static String keyOf(String provider, String model, String credential) {
        return (provider == null ? "" : provider) + "|" + (model == null ? "" : model) + "|"
                + (credential == null ? "" : credential);
    }

    private static String reasonFor(FailureKind kind) {
        return switch (kind) {
            case QUOTA_EXHAUSTED -> "usage quota exhausted";
            case RATE_LIMITED -> "rate limited";
            case AUTHENTICATION, CREDENTIAL_UNAVAILABLE -> "credential unusable";
            case PERMISSION_DENIED -> "provider access denied";
            default -> kind.name();
        };
    }

    private static boolean requiresDifferentIdentity(FailureKind kind) {
        return kind == FailureKind.AUTHENTICATION
                || kind == FailureKind.CREDENTIAL_UNAVAILABLE
                || kind == FailureKind.PERMISSION_DENIED;
    }

    private static boolean sameIdentity(ModelRung rung, String activeProvider, String activeCredential) {
        boolean providerSame = sameProvider(rung, activeProvider);
        String credential = rung.resolvedCredential(activeCredential);
        boolean credentialSame = java.util.Objects.equals(credential, activeCredential);
        return providerSame && credentialSame;
    }

    private static boolean sameProvider(ModelRung rung, String activeProvider) {
        String provider = rung.resolvedProvider(activeProvider);
        return provider != null && activeProvider != null
                && provider.equalsIgnoreCase(activeProvider);
    }

    /** True when the rung resolves to the credential the session is already using. */
    private static boolean sameCredential(ModelRung rung, String activeCredential) {
        return java.util.Objects.equals(rung.resolvedCredential(activeCredential), activeCredential);
    }
}
