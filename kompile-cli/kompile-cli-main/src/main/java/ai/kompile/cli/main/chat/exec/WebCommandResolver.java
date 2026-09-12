package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatCommandCatalog;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.skill.SkillsMarkdownGenerator;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;

/** Resolve raw user intent before provider initialization, memory, or supplemental context. */
public final class WebCommandResolver {
    private WebCommandResolver() { }
    public enum Status {
        MODEL_INPUT, COMPLETED, UNKNOWN_COMMAND, TERMINAL_REQUIRED,
        LIVE_SESSION_REQUIRED, NOT_YET_SUPPORTED, INTERACTION_REQUIRED, INVALID
    }
    /**
     * A resolved user intent. {@code data} carries structured command payload
     * (e.g. the {@code /model} menu or applied session state) for machine
     * consumers; it is {@code null} for plain-text-only outcomes.
     */
    public record Resolution(Status status, String command, String text, String modelPrompt, JsonNode data) {
        /** Compatibility constructor for callers compiled against the pre-data shape. */
        public Resolution(Status status, String command, String text, String modelPrompt) {
            this(status, command, text, modelPrompt, null);
        }
        public boolean isCommandOutcome() { return status != Status.MODEL_INPUT; }
        public int exitCode() {
            return switch (status) {
                case COMPLETED, INTERACTION_REQUIRED, MODEL_INPUT -> 0;
                case INVALID, UNKNOWN_COMMAND, TERMINAL_REQUIRED,
                        LIVE_SESSION_REQUIRED, NOT_YET_SUPPORTED -> 2;
            };
        }
    }

    public static Resolution resolve(WebChatInput input, Path directory) {
        return resolve(input, directory, new ChatSessionStateStore(), null);
    }

    /** Headless entry: explicit store seam for tests and embedders. */
    public static Resolution resolve(WebChatInput input, Path directory, ChatSessionStateStore stateStore) {
        return resolve(input, directory, stateStore, null);
    }

    /**
     * Resolve with an explicit configuration override. {@code configOverride}
     * (typically the run's injected {@code Options.chatConfig()}) replaces the
     * on-disk config lookup when present, keeping command resolution free of
     * environment/network probing in embedded runs.
     */
    public static Resolution resolve(WebChatInput input, Path directory,
                                     ChatSessionStateStore stateStore, ChatConfig configOverride) {
        return resolve(input, directory, stateStore, configOverride, null);
    }

    /** Full seam: config override plus an explicit model-catalog store path (tests/embedders). */
    public static Resolution resolve(WebChatInput input, Path directory,
                                     ChatSessionStateStore stateStore, ChatConfig configOverride,
                                     Path modelCatalogStorePath) {
        ChatSessionStateStore store = stateStore == null ? new ChatSessionStateStore() : stateStore;
        return resolveInternal(input, () -> loadSkills(directory), store, directory,
                configOverride != null ? () -> configOverride : null, modelCatalogStorePath);
    }

    public static SkillRegistry loadSkills(Path directory) {
        SkillRegistry registry = new SkillRegistry();
        new CustomSkillLoader(directory).loadAll().values().forEach(registry::register);
        return registry;
    }

    /** Original seam, preserved for existing callers/tests. */
    static Resolution resolve(WebChatInput input, Supplier<SkillRegistry> skills) {
        return resolveInternal(input, skills, new ChatSessionStateStore(), null, () -> null, null);
    }

    static Resolution resolveInternal(WebChatInput input, Supplier<SkillRegistry> skills,
                                      ChatSessionStateStore stateStore, Path directory,
                                      Supplier<ChatConfig> configOverride, Path modelCatalogStorePath) {
        String raw = input.rawInput().stripLeading();
        if (!raw.startsWith("/")) return model(input.rawInput(), input);
        int end = 1;
        while (end < raw.length() && !Character.isWhitespace(raw.charAt(end))) end++;
        String name = raw.substring(1, end).toLowerCase(Locale.ROOT);
        String command = "/" + name;
        if (ChatCommandCatalog.isBuiltin(name)) {
            if ("help".equals(name)) return new Resolution(Status.COMPLETED, command, ChatCommandCatalog.webHelp(), null);
            if ("skills".equals(name)) return new Resolution(Status.COMPLETED, command,
                    SkillsMarkdownGenerator.generateCompact(skills.get().all()), null);
            if ("model".equals(name)) {
                return resolveModelCommand(raw.substring(Math.min(end, raw.length())),
                        command, input, stateStore, directory, configOverride,
                        modelCatalogStorePath);
            }
            Status status = Status.valueOf(ChatCommandCatalog.webSupport(name).name());
            String message = switch (status) {
                case TERMINAL_REQUIRED -> command + " requires the interactive terminal; no action was performed.";
                case LIVE_SESSION_REQUIRED -> command + " requires a durable live-session runtime, not yet supported by web input; no state was changed.";
                default -> command + " is not yet supported by web input; no action was performed.";
            };
            return new Resolution(status, command, message, null);
        }
        // Only raw arguments are expanded. Supplemental context cannot become skill arguments.
        var skill = skills.get().get(name);
        if (skill == null) return new Resolution(Status.UNKNOWN_COMMAND, command,
                "Unknown command: " + command + ". Use /help or /skills.", null);
        int argsStart = end;
        while (argsStart < raw.length() && Character.isWhitespace(raw.charAt(argsStart))) argsStart++;
        String arguments = raw.substring(argsStart);
        String expanded = "<skill name=\"" + skill.getName() + "\">\n"
                + skill.expandTemplate(arguments) + "\n</skill>";
        return model(expanded, input);
    }

    /**
     * Web {@code /model}: the bare form returns the local catalog menu (with any
     * persisted session selection marked current); the explicit form validates
     * the id and persists the selection durably for this session. Always a
     * command outcome — never reaches a model turn.
     */
    private static Resolution resolveModelCommand(
            String argumentRegion, String command, WebChatInput input,
            ChatSessionStateStore store, Path directory, Supplier<ChatConfig> configOverride,
            Path modelCatalogStorePath) {
        String rest = argumentRegion == null ? "" : argumentRegion.strip();
        ChatConfig config = configOverride != null ? configOverride.get()
                : ChatConfig.loadOrFromEnv(directory == null ? Path.of(".") : directory);
        String sessionId = input.sessionId() == null || input.sessionId().isBlank()
                ? null : input.sessionId();
        if (rest.isEmpty()) {
            String persisted = sessionId == null ? null : store.loadModel(sessionId, directory);
            return modelMenu(command, config, persisted, modelCatalogStorePath);
        }
        return applyModelSelection(rest, command, config, store, sessionId, directory,
                modelCatalogStorePath);
    }

    private static Resolution modelMenu(String command, ChatConfig config, String persistedModel,
                                        Path modelCatalogStorePath) {
        WebModelCatalog.Listing listing = WebModelCatalog.listing(config, modelCatalogStorePath);
        // A persisted explicit selection is the effective current model for this
        // session; otherwise the configured model is.
        String current = persistedModel != null ? persistedModel
                : listing.currentModel();
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "model");
        data.put("currentModel", current);
        data.put("provider", listing.provider());
        if (persistedModel != null && !persistedModel.equals(listing.currentModel())) {
            data.put("persistedForSession", true);
        }
        data.put("liveListingAvailable", listing.liveListingAvailable());
        if (listing.note() != null && !listing.note().isBlank()) {
            data.put("note", listing.note());
        }
        ArrayNode models = data.putArray("models");
        for (WebModelCatalog.Entry entry : listing.entries()) {
            ObjectNode model = models.addObject();
            model.put("id", entry.id());
            if (entry.display() != null && !entry.display().equals(entry.id())) {
                model.put("display", entry.display());
            }
            if (entry.contextLimit() != null && entry.contextLimit() > 0) {
                model.put("contextLimit", entry.contextLimit());
            }
            if (current != null && current.equalsIgnoreCase(entry.id())) {
                model.put("current", true);
            }
        }
        StringBuilder text = new StringBuilder("Available models for provider '")
                .append(listing.provider() == null || listing.provider().isBlank()
                        ? "(unconfigured)" : listing.provider())
                .append("':");
        if (listing.entries().isEmpty()) {
            text.append("\n  (no locally known models; live listing unavailable)");
        } else {
            for (WebModelCatalog.Entry entry : listing.entries()) {
                text.append("\n  ")
                        .append(current != null && current.equalsIgnoreCase(entry.id()) ? "* " : "  ")
                        .append(entry.id());
                if (entry.contextLimit() != null && entry.contextLimit() > 0) {
                    text.append("  (context ").append(entry.contextLimit()).append(")");
                }
            }
        }
        text.append("\nSelect with: /model <id>.");
        if (!listing.liveListingAvailable()) {
            text.append(" Live provider listing was not fetched; the menu reflects locally known models only.");
        }
        return new Resolution(Status.INTERACTION_REQUIRED, command, text.toString(), null, data);
    }

    private static Resolution applyModelSelection(
            String requested, String command, ChatConfig config, ChatSessionStateStore store,
            String sessionId, Path directory, Path modelCatalogStorePath) {
        WebModelCatalog.Selection selection =
                WebModelCatalog.validate(config, requested, modelCatalogStorePath);
        if (selection == WebModelCatalog.Selection.UNKNOWN) {
            return new Resolution(Status.INVALID, command,
                    "Unknown model: '" + requested + "' is not in the provider's known catalog. "
                            + "Use /model to list locally known models; no state was changed.", null);
        }
        String canonical = WebModelCatalog.canonicalId(config, requested, modelCatalogStorePath);
        ChatSessionStateStore.SaveResult saved =
                store.updateModel(sessionId, directory, canonical);
        if (!saved.applied()) {
            return new Resolution(Status.INVALID, command,
                    "The model selection could not be persisted (missing session id or state directory); "
                            + "no durable change was made.", null);
        }
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        ObjectNode state = data.putObject("state");
        state.put("sessionId", sessionId);
        state.put("workingDirectory", directory == null
                ? "" : directory.toAbsolutePath().normalize().toString());
        state.put("model", canonical);
        return new Resolution(Status.INTERACTION_REQUIRED, command,
                "Model selection saved for this session: " + canonical, null, data);
    }

    private static Resolution model(String prompt, WebChatInput input) {
        return new Resolution(Status.MODEL_INPUT, "", "", input.supplementalContext().isBlank() ? prompt
                : prompt + "\n\n<supplemental_context>\n" + input.supplementalContext() + "\n</supplemental_context>");
    }
}
