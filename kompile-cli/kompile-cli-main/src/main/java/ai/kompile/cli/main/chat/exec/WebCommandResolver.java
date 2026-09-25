package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatCommandCatalog;
import ai.kompile.cli.main.chat.ContinueManager;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.ReminderManager;
import ai.kompile.cli.main.chat.ScheduledLoopManager;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ModelCatalogFallback;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.skill.SkillsMarkdownGenerator;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
        // Headless session-configuration read: one quiet outcome with every config
        // menu aggregated. Never reaches a model turn and never touches rawInput.
        if (input.configQuery()) {
            return configSnapshot(input, stateStore, directory, configOverride, modelCatalogStorePath);
        }
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
            if ("role".equals(name)) {
                return resolveRoleCommand(raw.substring(Math.min(end, raw.length())),
                        command, input, stateStore, directory);
            }
            if ("fast".equals(name)) {
                return resolveFastCommand(raw.substring(Math.min(end, raw.length())),
                        command, input, stateStore, directory, configOverride);
            }
            if ("reminder".equals(name) || "reminder-global".equals(name)
                    || "loop".equals(name) || "loop-global".equals(name)) {
                return resolvePersistenceCommand(name,
                        raw.substring(Math.min(end, raw.length())),
                        command, input, stateStore, directory);
            }
            if ("continue".equals(name)) {
                return resolveContinueCommand(
                        raw.substring(Math.min(end, raw.length())), command, directory);
            }
            if ("judge".equals(name) || "judge-global".equals(name)) {
                return resolveJudgeCommand(name,
                        raw.substring(Math.min(end, raw.length())),
                        command, input, stateStore, directory);
            }
            if (name.startsWith("queue")) {
                return resolveQueueCommand(name,
                        raw.substring(Math.min(end, raw.length())),
                        command, input, stateStore, directory);
            }
            if ("clear".equals(name)) {
                String clearArgs = stripQuotes(raw.substring(Math.min(end, raw.length())).strip());
                if (!clearArgs.isEmpty()) {
                    return new Resolution(Status.INVALID, command,
                            "Usage: /clear (no arguments). The previous transcript stays "
                                    + "resumable; nothing was changed.", null);
                }
                return resolveClearCommand(command, input, stateStore, directory);
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
        // "vendor:model" switches provider AND model (the web form of the
        // interactive picker's vendor page). A bare argument is a vendor name
        // when it matches a switchable vendor (its model menu is returned);
        // otherwise it is validated against the CURRENT provider as before.
        int vendorSeparator = rest.indexOf(':');
        if (vendorSeparator > 0) {
            return applyVendorModelSelection(rest, command, config, store, sessionId, directory);
        }
        if (WebModelCatalog.canonicalVendor(config, rest) != null) {
            return vendorModelMenu(command, config, WebModelCatalog.canonicalVendor(config, rest));
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
        // Switchable vendors — the web mirror of the interactive picker's
        // vendor page. Selection uses /model <vendor>:<model>.
        com.fasterxml.jackson.databind.node.ArrayNode vendorEntries = data.putArray("vendors");
        String configuredProvider = config == null ? null : config.getProvider();
        for (WebModelCatalog.VendorEntry vendor : WebModelCatalog.vendors(config)) {
            ObjectNode vendorEntry = vendorEntries.addObject();
            vendorEntry.put("vendor", vendor.vendor());
            if (vendor.display() != null && !vendor.display().equals(vendor.vendor())) {
                vendorEntry.put("display", vendor.display());
            }
            if (configuredProvider != null
                    && configuredProvider.equalsIgnoreCase(vendor.currentProvider())) {
                vendorEntry.put("current", true);
            }
        }
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
        text.append("\nSelect with: /model <id>; /model <vendor>:<model> switches provider; "
                + "/model <vendor> lists that vendor's models.");
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

    /**
     * Web {@code /model <vendor>} and {@code /model <vendor>:<model>} — the
     * vendor-switching mirror of the interactive picker's vendor page.
     *
     * <p>The bare vendor form lists that vendor's locally known models (the
     * recorded catalog for its wire provider) with the session's credential
     * posture surfaced honestly: vendors without a usable stored credential
     * are listed but marked unavailable instead of being hidden. The colon
     * form switches provider AND model in one step: the candidate mirrors the
     * interactive {@code buildModelProviderCandidate} (LLM settings carried,
     * cross-provider secrets/base URL discarded), fails closed on an unusable
     * credential, and persists provider+model durably for the session.</p>
     */
    private static Resolution applyVendorModelSelection(
            String requested, String command, ChatConfig config, ChatSessionStateStore store,
            String sessionId, Path directory) {
        int separator = requested.indexOf(':');
        String vendorPart = requested.substring(0, separator).trim();
        String modelPart = requested.substring(separator + 1).trim();
        String vendor = WebModelCatalog.canonicalVendor(config, vendorPart);
        if (vendor == null) {
            return new Resolution(Status.INVALID, command,
                    "Unknown vendor: '" + vendorPart + "' is not switchable here. "
                            + "Use /model to list vendors; no state was changed.", null);
        }
        if (modelPart.isEmpty()) {
            return vendorModelMenu(command, config, vendor);
        }
        String wireProvider = WebModelCatalog.providerForVendor(config, vendor);
        if (wireProvider == null) {
            return new Resolution(Status.INVALID, command,
                    "Vendor '" + vendor + "' has no configured provider route. "
                            + "Run /setup in the interactive CLI; no state was changed.", null);
        }
        // Build the candidate exactly like the interactive picker: carry LLM
        // settings, drop cross-provider secrets and base URL.
        ChatConfig candidate = new ChatConfig();
        candidate.applyLlmSettingsFrom(config);
        candidate.setProvider(wireProvider);
        candidate.setModel(modelPart);
        candidate.setThinking(null);
        candidate.setFastMode(wireProvider.equalsIgnoreCase(config.getProvider())
                && config.isFastMode() && candidate.supportsFastMode());
        if (!wireProvider.equalsIgnoreCase(config.getProvider())) {
            candidate.setApiKey(null);
            candidate.setBaseUrl(null);
            candidate.setAuthenticationMethod(null);
        }
        if (!candidate.isValid()) {
            return new Resolution(Status.INVALID, command,
                    "No usable credential for vendor '" + vendor + "' (provider " + wireProvider
                            + "). Configure it with /setup in the interactive CLI; "
                            + "the current provider/model is still active.", null);
        }
        ChatSessionStateStore.SaveResult saved =
                store.updateModel(sessionId, directory, wireProvider, modelPart);
        if (!saved.applied()) {
            return new Resolution(Status.INVALID, command,
                    "The vendor/model selection could not be persisted (missing session id or "
                            + "state directory); no durable change was made.", null);
        }
        // Answer with the NEW vendor's menu so the dialog reflects the switch.
        ObjectNode data = (ObjectNode) modelMenu(command, candidate, modelPart, null).data();
        ObjectNode state = data.putObject("state");
        state.put("sessionId", sessionId);
        state.put("workingDirectory", directory == null
                ? "" : directory.toAbsolutePath().normalize().toString());
        state.put("model", modelPart);
        state.put("provider", wireProvider);
        return new Resolution(Status.INTERACTION_REQUIRED, command,
                "Provider and model saved for this session: " + vendor + " / " + modelPart,
                null, data);
    }

    /** Local model listing for one vendor's wire provider (read-only). */
    private static Resolution vendorModelMenu(String command, ChatConfig config, String vendor) {
        String wireProvider = WebModelCatalog.providerForVendor(config, vendor);
        if (wireProvider == null) {
            return new Resolution(Status.INVALID, command,
                    "Vendor '" + vendor + "' has no configured provider route. "
                            + "Run /setup in the interactive CLI.", null);
        }
        ObjectNode data = vendorModelsData(config, vendor, wireProvider);
        ArrayNode models = (ArrayNode) data.get("models");
        String text = models.isEmpty()
                ? "No locally known models for vendor '" + vendor
                        + "'. Select explicitly with /model " + vendor + ":<model>."
                : "Models for vendor '" + vendor + "': select with /model " + vendor + ":<id>.";
        return new Resolution(Status.INTERACTION_REQUIRED, command, text, null, data);
    }

    /**
     * Model-section payload for the config snapshot: the current provider's
     * menu, or — when {@code vendor} is set — that vendor's locally known
     * models. Both shapes always carry the switchable vendor chips.
     */
    private static ObjectNode modelSection(ChatConfig config, String persistedModel,
                                           String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return (ObjectNode) modelMenu("/config", config, persistedModel, null).data();
        }
        String wireProvider = WebModelCatalog.providerForVendor(config, vendor);
        if (wireProvider == null) {
            // Unknown vendor: fall back to the default menu; the dialog shows
            // the chips so the user can pick a valid one.
            return (ObjectNode) modelMenu("/config", config, persistedModel, null).data();
        }
        ObjectNode data = vendorModelsData(config, vendor, wireProvider);
        data.put("currentVendor", vendor);
        return data;
    }

    /** Shared vendor-scoped model payload (menu:model with vendor marker). */
    private static ObjectNode vendorModelsData(ChatConfig config, String vendor,
                                               String wireProvider) {
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "model");
        data.put("provider", wireProvider);
        data.put("vendor", vendor);
        data.put("currentModel", config == null ? null : config.getModel());
        data.put("liveListingAvailable", false);
        ArrayNode models = data.putArray("models");
        for (String id : ModelCatalogFallback.lookup(wireProvider).map(r -> r.models()).orElse(List.of())) {
            ObjectNode model = models.addObject();
            model.put("id", id);
        }
        // Vendor chips ride along so the dialog can switch scope in place.
        ArrayNode vendorEntries = data.putArray("vendors");
        String configuredProvider = config == null ? null : config.getProvider();
        for (WebModelCatalog.VendorEntry entry : WebModelCatalog.vendors(config)) {
            ObjectNode vendorEntry = vendorEntries.addObject();
            vendorEntry.put("vendor", entry.vendor());
            if (entry.display() != null && !entry.display().equals(entry.vendor())) {
                vendorEntry.put("display", entry.display());
            }
            if (configuredProvider != null
                    && configuredProvider.equalsIgnoreCase(entry.currentProvider())) {
                vendorEntry.put("current", true);
            }
        }
        return data;
    }

    private static Resolution model(String prompt, WebChatInput input) {
        return new Resolution(Status.MODEL_INPUT, "", "", input.supplementalContext().isBlank() ? prompt
                : prompt + "\n\n<supplemental_context>\n" + input.supplementalContext() + "\n</supplemental_context>");
    }

    // ========================================================================
    // Web /continue: the auto-reply configuration is project-global file-backed
    // state (.kompile/chat-continue.json), so every edit persists through the
    // SAME ContinueManager the interactive CLI uses. The firing itself —
    // scanning a finished assistant turn and dispatching the auto-reply — is
    // a live-session behavior and stays there; web edits just configure it.
    // ========================================================================

    private static Resolution resolveContinueCommand(
            String argumentRegion, String command, Path directory) {
        Path workDir = directory == null ? Path.of(".") : directory;
        ContinueManager manager = new ContinueManager(JsonUtils.standardMapper(), workDir);
        String rest = stripQuotes(argumentRegion == null ? "" : argumentRegion.strip());
        String text = manager.handleCommand(rest);
        ObjectNode data = continueSnapshot(manager);
        return new Resolution(Status.INTERACTION_REQUIRED, command, text, null, data);
    }

    /** Structured /continue payload for the web dialog. */
    private static ObjectNode continueSnapshot(ContinueManager manager) {
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "continue");
        data.put("enabled", manager.isEnabled());
        data.put("reply", manager.reply());
        ArrayNode keywords = data.putArray("keywords");
        for (String keyword : manager.effectiveKeywords()) {
            keywords.addObject().put("keyword", keyword);
        }
        return data;
    }

    // ========================================================================
    // Web /judge and /judge-global: the durable, cross-process parts of judge
    // control are file-backed, so web edits land in the same state the live
    // CLI reads. The GLOBAL master switch persists in the harness config;
    // guidance persists per session under ~/.kompile/sessions (addressed by
    // the web session id, exactly what the live session resolves to).
    // Live-session-only controls (one-shot override, command approvals,
    // restart, agent switch, judgements) report honestly and change nothing.
    // ========================================================================

    private static Resolution resolveJudgeCommand(
            String name, String argumentRegion, String command, WebChatInput input,
            ChatSessionStateStore store, Path directory) {
        boolean globalForm = "judge-global".equals(name);
        Path workDir = directory == null ? Path.of(".") : directory;
        String rest = stripQuotes(argumentRegion == null ? "" : argumentRegion.strip());
        String[] parts = rest.split("\\s+", 2);
        String sub = rest.isBlank() ? "" : parts[0].toLowerCase(java.util.Locale.ROOT);
        String subRest = parts.length > 1 ? parts[1].strip() : "";

        if (globalForm) {
            return resolveJudgeGlobal(sub, subRest, command, workDir);
        }
        // /judge <sub> ...
        return switch (sub) {
            case "", "status", "show" -> judgeStatus(input, store, workDir);
            case "global" -> resolveJudgeGlobal(
                    subRest.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT),
                    subRest.contains(" ") ? subRest.split("\\s+", 2)[1].strip() : "",
                    command, workDir);
            case "feedback", "guidance" -> resolveJudgeFeedback(subRest, input, command);
            case "on", "enable", "resume", "off", "disable", "pause",
                 "override", "bypass", "allow-next", "approve",
                 "judgements", "history", "restart", "agent", "chat", "talk",
                 "init", "setup", "config", "rules", "reload", "delete", "remove",
                 "run", "start", "launch", "workflow", "direction", "policy" ->
                new Resolution(Status.LIVE_SESSION_REQUIRED, command,
                        "/judge " + sub + " drives live-session judge state and is not "
                                + "available over web input; no state was changed. Use /judge "
                                + "status, /judge global on|off, or /judge feedback <text>.",
                        null, judgeData(input, store, workDir));
            default -> new Resolution(Status.INVALID, command,
                    "Unknown /judge subcommand: " + sub + ". Use /judge status, "
                            + "/judge global on|off, or /judge feedback <text>.", null,
                            judgeData(input, store, workDir));
        };
    }

    /** Global judge master switch — file-backed harness config, safe headlessly. */
    private static Resolution resolveJudgeGlobal(
            String sub, String rest, String command, Path workDir) {
        ai.kompile.cli.main.chat.harness.HarnessConfig config =
                ai.kompile.cli.main.chat.harness.HarnessConfig.load();
        switch (sub) {
            case "", "status", "show" -> {
                ObjectNode data = judgeGlobalData(config);
                return new Resolution(Status.INTERACTION_REQUIRED, command,
                        "Judge global setting: " + (config.isJudgeGlobalEnabled() ? "enabled" : "disabled"),
                        null, data);
            }
            case "on", "enable" -> config.setJudgeGlobalEnabled(true);
            case "off", "disable" -> config.setJudgeGlobalEnabled(false);
            default -> {
                return new Resolution(Status.INVALID, command,
                        "Usage: /judge global [on|off|status]", null,
                        judgeGlobalData(config));
            }
        }
        config.save();
        return new Resolution(Status.INTERACTION_REQUIRED, command,
                "Judge " + (config.isJudgeGlobalEnabled() ? "enabled" : "disabled")
                        + " globally (persists for every session).",
                null, judgeGlobalData(config));
    }

    private static ObjectNode judgeGlobalData(
            ai.kompile.cli.main.chat.harness.HarnessConfig config) {
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "judge");
        data.put("judgeScope", "global");
        data.put("globalEnabled", config.isJudgeGlobalEnabled());
        return data;
    }

    /** Session judge posture: global switch + this session's durable guidance. */
    private static Resolution judgeStatus(WebChatInput input, ChatSessionStateStore store,
                                          Path workDir) {
        ai.kompile.cli.main.chat.harness.HarnessConfig config =
                ai.kompile.cli.main.chat.harness.HarnessConfig.load();
        return new Resolution(Status.INTERACTION_REQUIRED, "/judge",
                "Judge global: " + (config.isJudgeGlobalEnabled() ? "enabled" : "disabled"),
                null, judgeData(input, store, workDir));
    }

    private static ObjectNode judgeData(WebChatInput input, ChatSessionStateStore store,
                                        Path workDir) {
        ai.kompile.cli.main.chat.harness.HarnessConfig config =
                ai.kompile.cli.main.chat.harness.HarnessConfig.load();
        ObjectNode data = judgeGlobalData(config);
        String sessionId = input.sessionId() == null || input.sessionId().isBlank()
                ? null : input.sessionId();
        if (sessionId != null) {
            var control = ai.kompile.cli.main.chat.enforcer.JudgeControl.load(sessionId);
            data.put("sessionEnabled", control.isEnabled());
            data.put("guidance", control.getGuidance());
            data.put("overrideArmed", control.isOverrideNextSet());
        }
        return data;
    }

    /** Durable per-session guidance — the same file the live CLI reads. */
    private static Resolution resolveJudgeFeedback(
            String text, WebChatInput input, String command) {
        String sessionId = input.sessionId() == null || input.sessionId().isBlank()
                ? null : input.sessionId();
        if (sessionId == null) {
            return new Resolution(Status.INVALID, command,
                    "Judge feedback needs a session id; no state was changed.", null);
        }
        var control = ai.kompile.cli.main.chat.enforcer.JudgeControl.load(sessionId);
        if (text.isBlank()) {
            return new Resolution(Status.INTERACTION_REQUIRED, command,
                    control.hasGuidance()
                            ? "Current guidance: " + control.getGuidance()
                            : "No judge guidance set. Use /judge feedback <text> to correct the judge.",
                    null, judgeData(input, null, Path.of(".")));
        }
        if ("clear".equalsIgnoreCase(text) || "none".equalsIgnoreCase(text)
                || "off".equalsIgnoreCase(text)) {
            control.clearGuidance();
        } else {
            control.setGuidance(text);
        }
        return new Resolution(Status.INTERACTION_REQUIRED, command,
                control.hasGuidance()
                        ? "Judge guidance saved — injected into every future judge prompt."
                        : "Judge guidance cleared.",
                null, judgeData(input, null, Path.of(".")));
    }

    // ========================================================================
    // Headless session-configuration snapshot (configQuery=true): one outcome
    // carrying every config menu — model / role / fast / reminders (session +
    // project) / loops (session + project) / queue — so the web dialog populates
    // from a single quiet CLI invocation with zero chat messages. Read-only:
    // it never mutates state; the individual commands remain the write path.
    // ========================================================================

    private static Resolution configSnapshot(WebChatInput input, ChatSessionStateStore store,
                                             Path directory, Supplier<ChatConfig> configOverride,
                                             Path modelCatalogStorePath) {
        Path workDir = directory == null ? Path.of(".") : directory;
        String sessionId = input.sessionId() == null || input.sessionId().isBlank()
                ? null : input.sessionId();
        ChatConfig config = configOverride != null ? configOverride.get()
                : ChatConfig.loadOrFromEnv(workDir);
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "config");
        if (sessionId != null) data.put("sessionId", sessionId);
        // modelVendor scopes the model section to one vendor's models (quiet
        // vendor browsing in the web dialog); the vendor chips themselves are
        // always included so the dialog can switch scope without a dispatch.
        String modelVendor = input.modelVendor() == null || input.modelVendor().isBlank()
                ? null : input.modelVendor();
        data.set("model", modelSection(config, sessionId == null ? null
                : store.loadModel(sessionId, workDir), modelVendor));
        ObjectNode modelSectionData = (ObjectNode) data.get("model");
        if (modelVendor != null) modelSectionData.put("vendor", modelVendor);
        RoleManager roleManager = new RoleManager(workDir);
        data.set("role", roleMenu("/config", store, sessionId, workDir, roleManager).data());
        data.set("fast", fastSnapshot(config));
        ReminderManager sessionReminders = new ReminderManager(JsonUtils.standardMapper(),
                sessionId == null ? "unknown-session" : sessionId, workDir);
        data.set("reminders", reminderSnapshot(ReminderManager.Scope.SESSION, sessionReminders));
        data.set("continue", continueSnapshot(
                new ContinueManager(JsonUtils.standardMapper(), workDir)));
        ReminderManager projectReminders = new ReminderManager(JsonUtils.standardMapper(), null, workDir);
        data.set("remindersGlobal", reminderSnapshot(ReminderManager.Scope.PROJECT, projectReminders));
        java.nio.file.Path sessionLoopFile = ScheduledLoopManager.stateFileForSession(
                sessionId == null ? "unknown-session" : sessionId);
        data.set("loops", loopSnapshotJson(sessionLoopFile, "session"));
        data.set("loopsGlobal", loopSnapshotJson(ScheduledLoopManager.stateFileForProject(workDir), "project"));
        MessageQueue queue = new MessageQueue(sessionId == null ? "unknown-session" : sessionId);
        ObjectNode queueData = JsonUtils.standardMapper().createObjectNode();
        queueData.put("menu", "queue");
        queueData.put("scope", "session");
        queueData.set("queued", refreshedEntries(queue));
        data.set("queue", queueData);
        StringBuilder text = new StringBuilder("Session configuration snapshot loaded.");
        if (sessionId != null) text.append(" Session: ").append(sessionId);
        return new Resolution(Status.INTERACTION_REQUIRED, "/config", text.toString(), null, data);
    }

    /** Read-only reminders payload for the snapshot ({@code list} never mutates). */
    private static ObjectNode reminderSnapshot(ReminderManager.Scope scope, ReminderManager reminders) {
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "reminders");
        data.put("scope", scope == ReminderManager.Scope.PROJECT ? "project" : "session");
        ArrayNode entries = data.putArray("reminders");
        try {
            for (String text : reminders.list(scope)) {
                entries.addObject().put("text", text);
            }
        } catch (java.io.IOException error) {
            // A read failure yields an empty section rather than failing the
            // whole snapshot; the individual commands surface the real error.
        }
        return data;
    }

    /** Read-only loops payload for the snapshot (list-only manager, firing throws). */
    private static ObjectNode loopSnapshotJson(java.nio.file.Path stateFile, String scope) {
        ScheduledLoopManager loops = new ScheduledLoopManager(prompt -> {
            throw new IllegalStateException(
                    "Loop firing requires a live chat process; this manager only persists edits");
        }, stateFile);
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "loops");
        data.put("scope", scope);
        ArrayNode entries = data.putArray("loops");
        for (ScheduledLoopManager.ScheduledLoop loop : loops.list()) {
            entries.add(loopSnapshot(loop));
        }
        return data;
    }

    // ========================================================================
    // Web /role: the bare form returns the current selection and the role menu;
    // /role <name> validates against the same RoleManager roster the interactive
    // CLI uses, /role '' (or "none"/"default") clears the selection. The choice
    // is persisted durably per session id + working directory, mirroring /model.
    // ========================================================================

    /** Validation outcome for an explicit /role selection. */
    enum RoleSelection { KNOWN, CLEARED, UNKNOWN }

    static RoleSelection validateRole(RoleManager roleManager, String requested) {
        String candidate = requested == null ? "" : requested.trim();
        if (candidate.isEmpty() || "none".equalsIgnoreCase(candidate)
                || "default".equalsIgnoreCase(candidate)) {
            return RoleSelection.CLEARED;
        }
        return canonicalRoleName(roleManager, candidate) != null
                ? RoleSelection.KNOWN : RoleSelection.UNKNOWN;
    }

    private static Resolution resolveRoleCommand(
            String argumentRegion, String command, WebChatInput input,
            ChatSessionStateStore store, Path directory) {
        Path workDir = directory == null ? Path.of(".") : directory;
        // Quotes survive verbatim over web input (no shell strips them): /role ''
        // must clear the selection rather than validate a two-quote role name.
        // Only a genuinely absent argument opens the menu; an argument that is
        // empty after quote-stripping (or none/default) is an explicit clear.
        String raw = argumentRegion == null ? "" : argumentRegion.strip();
        String rest = stripQuotes(raw);
        String sessionId = input.sessionId() == null || input.sessionId().isBlank()
                ? null : input.sessionId();
        RoleManager roleManager = new RoleManager(workDir);
        if (raw.isEmpty()) {
            return roleMenu(command, store, sessionId, workDir, roleManager);
        }
        return applyRoleSelection(rest, command, store, sessionId, workDir, roleManager);
    }

    /** Strip one surrounding pair of single or double quotes, if present. */
    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static Resolution roleMenu(String command, ChatSessionStateStore store,
                                       String sessionId, Path directory, RoleManager roleManager) {
        String persisted = sessionId == null ? null : store.loadRole(sessionId, directory);
        List<RoleConfig> roles = new ArrayList<>(roleManager.getAllRoles());
        roles.sort(Comparator.comparing(RoleConfig::getName, String.CASE_INSENSITIVE_ORDER));
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "role");
        if (persisted != null) {
            data.put("currentRole", persisted);
        }
        ArrayNode entries = data.putArray("roles");
        for (RoleConfig role : roles) {
            ObjectNode entry = entries.addObject();
            entry.put("name", role.getName());
            if (role.getDisplayName() != null && !role.getDisplayName().isBlank()
                    && !role.getDisplayName().equals(role.getName())) {
                entry.put("display", role.getDisplayName());
            }
            if (role.getDescription() != null && !role.getDescription().isBlank()) {
                entry.put("description", role.getDescription());
            }
            if (role.getCategory() != null && !role.getCategory().isBlank()) {
                entry.put("category", role.getCategory());
            }
            if (persisted != null && persisted.equalsIgnoreCase(role.getName())) {
                entry.put("current", true);
            }
        }
        StringBuilder text = new StringBuilder(roles.isEmpty()
                ? "No roles are installed (built-in or custom)."
                : "Available roles:");
        for (RoleConfig role : roles) {
            text.append("\n  ")
                    .append(persisted != null && persisted.equalsIgnoreCase(role.getName()) ? "* " : "  ")
                    .append(role.getName());
            if (role.getDisplayName() != null && !role.getDisplayName().isBlank()
                    && !role.getDisplayName().equals(role.getName())) {
                text.append(" — ").append(role.getDisplayName());
            }
        }
        text.append("\nSelect with: /role <name>; /role '' clears the selection (default persona).");
        return new Resolution(Status.INTERACTION_REQUIRED, command, text.toString(), null, data);
    }

    private static Resolution applyRoleSelection(
            String requested, String command, ChatSessionStateStore store,
            String sessionId, Path directory, RoleManager roleManager) {
        RoleSelection selection = validateRole(roleManager, requested);
        if (selection == RoleSelection.UNKNOWN) {
            return new Resolution(Status.INVALID, command,
                    "Unknown role: '" + requested + "' is not in the role roster. "
                            + "Use /role to list available roles; no state was changed.", null);
        }
        boolean clearing = selection == RoleSelection.CLEARED;
        String canonical = clearing ? "" : canonicalRoleName(roleManager, requested.trim());
        ChatSessionStateStore.SaveResult saved = store.updateRole(sessionId, directory, canonical);
        if (!saved.applied()) {
            return new Resolution(Status.INVALID, command,
                    "The role selection could not be persisted (missing session id or state directory); "
                            + "no durable change was made.", null);
        }
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        ObjectNode state = data.putObject("state");
        state.put("sessionId", sessionId);
        state.put("workingDirectory", directory == null
                ? "" : directory.toAbsolutePath().normalize().toString());
        if (clearing) {
            data.put("cleared", true);
            state.put("role", "");
        } else {
            state.put("role", canonical);
        }
        return new Resolution(Status.INTERACTION_REQUIRED, command,
                clearing
                        ? "Role selection cleared for this session; the default persona applies."
                        : "Role selection saved for this session: " + canonical,
                null, data);
    }

    // ========================================================================
    // Web /fast: the bare form reports the current fast-mode preference and
    // eligibility; /fast on|off validates against the same provider capability
    // table the interactive /fast uses and persists the toggle into the chat
    // configuration (the same saveLoadedOrGlobal path as the CLI).
    // ========================================================================

    private static Resolution resolveFastCommand(
            String argumentRegion, String command, WebChatInput input,
            ChatSessionStateStore store, Path directory, Supplier<ChatConfig> configOverride) {
        Path workDir = directory == null ? Path.of(".") : directory;
        String rest = (argumentRegion == null ? "" : argumentRegion).strip().toLowerCase(Locale.ROOT);
        ChatConfig config = configOverride != null ? configOverride.get()
                : ChatConfig.loadOrFromEnv(workDir);
        if (!"on".equals(rest) && !"off".equals(rest) && !"status".equals(rest) && !rest.isEmpty()) {
            return new Resolution(Status.INVALID, command,
                    "Usage: /fast [on|off|status] (bare /fast reports the current state). "
                            + "No state was changed.", null);
        }
        if (!"off".equals(rest) && (config == null || !config.supportsFastMode())) {
            return new Resolution(Status.INVALID, command,
                    "Fast mode is not supported for the configured provider/model. Use /model first; "
                            + "no state was changed.", null);
        }
        boolean turningOn = "on".equals(rest) || (rest.isEmpty() && config.isFastMode());
        if (!"status".equals(rest)) {
            config.setFastMode(turningOn);
            try {
                config.saveLoadedOrGlobal();
            } catch (java.io.IOException error) {
                return new Resolution(Status.INVALID, command,
                        "Fast mode could not be persisted: " + error.getMessage()
                                + "; no durable change was made.", null);
            }
        }
        return new Resolution(Status.INTERACTION_REQUIRED, command,
                "Fast mode " + (config.isFastMode() ? "ON (requested)" : "OFF")
                        + " — applies to subsequent requests; reasoning effort is unchanged.",
                null, fastSnapshot(config));
    }

    /** Structured fast-mode payload shared by the /fast command and the config snapshot. */
    private static ObjectNode fastSnapshot(ChatConfig config) {
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "fast");
        data.put("fastMode", config != null && config.isFastMode());
        data.put("supported", config != null && config.supportsFastMode());
        data.put("provider", config == null || config.getProvider() == null ? "" : config.getProvider());
        data.put("model", config == null || config.getModel() == null ? "" : config.getModel());
        String notice = config == null ? null : config.fastModeCapabilities().notice();
        if (notice != null && !notice.isBlank()) {
            data.put("note", notice);
        }
        return data;
    }

    /** Exact roster name for a case-insensitively matching request, or null when absent. */
    private static String canonicalRoleName(RoleManager roleManager, String requested) {
        return roleManager.getAllRoles().stream()
                .map(RoleConfig::getName)
                .filter(name -> name.equalsIgnoreCase(requested))
                .findFirst()
                .orElse(null);
    }

    // ========================================================================
    // Web /reminder, /reminder-global, /loop, /loop-global: the bare form lists
    // current state; list/add/clear/pause/resume/remove delegate to the SAME
    // file-backed managers the interactive CLI uses, so web edits persist for
    // the project/session exactly like typed commands. Loop "run" dispatches
    // into a live chat process by design; over a headless command run there is
    // no process to fire into, so it reports that honestly and changes nothing.
    // ========================================================================

    private static Resolution resolvePersistenceCommand(
            String name, String argumentRegion, String command, WebChatInput input,
            ChatSessionStateStore store, Path directory) {
        boolean loop = name.startsWith("loop");
        boolean global = name.endsWith("-global");
        Path workDir = directory == null ? Path.of(".") : directory;
        String rest = stripQuotes(argumentRegion == null ? "" : argumentRegion.strip());
        String sessionId = input.sessionId() == null || input.sessionId().isBlank()
                ? null : input.sessionId();

        if (loop) {
            return resolveLoopCommand(global, rest, command, workDir);
        }
        ReminderManager.Scope scope = global
                ? ReminderManager.Scope.PROJECT : ReminderManager.Scope.SESSION;
        ReminderManager reminders = new ReminderManager(JsonUtils.standardMapper(),
                global ? null : (sessionId == null ? "unknown-session" : sessionId),
                workDir);
        return resolveReminderCommand(scope, reminders, rest, command);
    }

    private static Resolution resolveReminderCommand(
            ReminderManager.Scope scope, ReminderManager reminders,
            String rest, String command) {
        try {
            ObjectNode data = JsonUtils.standardMapper().createObjectNode();
            data.put("menu", "reminders");
            data.put("scope", scope == ReminderManager.Scope.PROJECT ? "project" : "session");
            ArrayNode entries = data.putArray("reminders");
            for (String text : reminders.list(scope)) {
                entries.addObject().put("text", text);
            }
            String message;
            if (rest.isEmpty() || rest.equalsIgnoreCase("list") || rest.equalsIgnoreCase("status")) {
                message = reminders.handleCommand(scope, rest.isEmpty() ? "list" : rest);
            } else if (rest.equalsIgnoreCase("clear")) {
                int cleared = reminders.clear(scope);
                data.putArray("reminders");
                String label = scope == ReminderManager.Scope.PROJECT ? "project" : "session";
                message = "Cleared " + cleared + " " + label + " reminder"
                        + (cleared == 1 ? "." : "s.");
            } else if (rest.equalsIgnoreCase("interval")) {
                message = reminders.handleCommand(scope, "interval");
            } else if (rest.toLowerCase(Locale.ROOT).startsWith("interval ")) {
                message = reminders.handleCommand(scope, rest);
            } else {
                String lowered = rest.toLowerCase(Locale.ROOT);
                String text = lowered.startsWith("add ")
                        ? rest.substring(4).strip() : rest;
                // "add" with no argument after whitespace stripping is usage, not reminder text.
                if (text.isEmpty() || "add".equalsIgnoreCase(rest)) {
                    return new Resolution(Status.INVALID, command,
                            "Reminder text cannot be blank.", null);
                }
                ReminderManager.AddResult result = reminders.add(scope, text);
                if (!result.added()) {
                    return new Resolution(Status.INVALID, command, result.message(), null);
                }
                entries.addObject().put("text", text);
                message = result.message();
            }
            return new Resolution(Status.INTERACTION_REQUIRED, command, message, null, data);
        } catch (java.io.IOException error) {
            String label = scope == ReminderManager.Scope.PROJECT ? "project" : "session";
            return new Resolution(Status.INVALID, command,
                    "Could not update " + label + " reminders: "
                            + error.getMessage(), null);
        }
    }

    private static Resolution resolveLoopCommand(
            boolean global, String rest, String command, Path workDir) {
        // Persistence paths mirror the interactive CLI (ScheduledLoopManager.stateFileFor*).
        java.nio.file.Path stateFile = global
                ? ScheduledLoopManager.stateFileForProject(workDir)
                : ScheduledLoopManager.stateFileForSession("unknown-session");
        ScheduledLoopManager loops = new ScheduledLoopManager(prompt -> {
            throw new IllegalStateException(
                    "Loop firing requires a live chat process; this manager only persists edits");
        }, stateFile);
        try {
            String operation = rest.isEmpty() ? "list" : rest.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            String argument = rest.contains(" ") ? rest.split("\\s+", 2)[1].strip() : "";
            ObjectNode data = JsonUtils.standardMapper().createObjectNode();
            data.put("menu", "loops");
            data.put("scope", global ? "project" : "session");
            ArrayNode entries = data.putArray("loops");
            String message;
            switch (operation) {
                case "list", "status", "" -> {
                    for (ScheduledLoopManager.ScheduledLoop loop : loops.list()) {
                        entries.add(loopSnapshot(loop));
                    }
                    message = loops.list().isEmpty()
                            ? "No " + (global ? "project-global" : "session") + " scheduled loops."
                            : "Scheduled " + (global ? "project-global" : "session") + " loops: "
                                    + loops.list().size();
                }
                case "add" -> {
                    ScheduledLoopManager.ScheduledLoop loop = null;
                    if (argument.startsWith("cron ")) {
                        String cronAndPrompt = argument.substring(5).strip();
                        int separator = cronAndPrompt.indexOf(" -- ");
                        if (separator >= 0) {
                            loop = loops.create(cronAndPrompt.substring(0, separator).strip(),
                                    cronAndPrompt.substring(separator + 4).strip());
                        }
                    } else {
                        loop = createIntervalLoop(loops, argument);
                    }
                    if (loop == null) {
                        return new Resolution(Status.INVALID, command,
                                "Usage: /loop add <5m|2h30m> <prompt> or "
                                        + "/loop add cron <min hour dom mon dow> -- <prompt> "
                                        + "(minimum interval 5s). No loop was created.", null);
                    }
                    entries.add(loopSnapshot(loop));
                    message = "Scheduled " + (global ? "project-global" : "session")
                            + " loop [" + loop.getId() + "] " + loop.getFormattedInterval()
                            + " — " + loop.getPrompt();
                }
                case "clear" -> {
                    int cleared = loops.clear();
                    message = "Cleared " + cleared + " " + (global ? "project-global" : "session")
                            + " scheduled loop" + (cleared == 1 ? "." : "s.");
                }
                case "pause", "resume", "remove", "delete", "stop" -> {
                    if (argument.isBlank()) {
                        return new Resolution(Status.INVALID, command,
                                "Usage: /loop " + operation + " <id>", null);
                    }
                    boolean changed = switch (operation) {
                        case "pause" -> loops.pause(argument);
                        case "resume" -> loops.resume(argument);
                        default -> loops.remove(argument);
                    };
                    if (!changed) {
                        return new Resolution(Status.INVALID, command,
                                (global ? "Project-global" : "Session") + " loop not found: "
                                        + argument, null);
                    }
                    for (ScheduledLoopManager.ScheduledLoop loop : loops.list()) {
                        entries.add(loopSnapshot(loop));
                    }
                    message = (global ? "Project-global" : "Session") + " loop "
                            + operation + "d: " + argument;
                }
                case "run", "now" -> {
                    return new Resolution(Status.LIVE_SESSION_REQUIRED, command,
                            "Running a loop now injects its prompt into a live chat process; "
                                    + "web command runs have no live process to fire into. "
                                    + "The loop stays scheduled and fires on its normal cadence "
                                    + "in the interactive CLI.", null);
                }
                default -> {
                    // Claude-style shorthand: /loop 5m prompt
                    ScheduledLoopManager.ScheduledLoop loop = createIntervalLoop(loops, rest);
                    if (loop == null) {
                        return new Resolution(Status.INVALID, command,
                                "Usage: /loop [add] <5m|2h30m> <prompt> | list | clear | "
                                        + "pause <id> | resume <id> | remove <id>.", null);
                    }
                    entries.add(loopSnapshot(loop));
                    message = "Scheduled " + (global ? "project-global" : "session")
                            + " loop [" + loop.getId() + "] " + loop.getFormattedInterval()
                            + " — " + loop.getPrompt();
                }
            }
            return new Resolution(Status.INTERACTION_REQUIRED, command, message, null, data);
        } finally {
            loops.shutdown();
        }
    }

    /** Interval shorthand "5m prompt" split; cron add is handled by the caller. */
    private static ScheduledLoopManager.ScheduledLoop createIntervalLoop(
            ScheduledLoopManager loops, String argument) {
        String[] parts = argument.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) return null;
        return loops.create(parts[0], parts[1].strip());
    }

    private static ObjectNode loopSnapshot(ScheduledLoopManager.ScheduledLoop loop) {
        ObjectNode node = JsonUtils.standardMapper().createObjectNode();
        node.put("id", loop.getId());
        node.put("schedule", loop.getSchedule());
        node.put("prompt", loop.getPrompt());
        node.put("status", loop.getStatus().name());
        node.put("interval", loop.getFormattedInterval());
        node.put("fireCount", loop.getFireCount());
        return node;
    }

    // ========================================================================
    // Web /queue family: the MessageQueue is file-backed per session
    // (~/.kompile/queues/queue-<sessionId>.json) and drained by the live CLI
    // process, so web edits land in the same queue the interactive chat
    // consumes. Enqueue/list/remove/edit/move/clear/status are fully supported;
    // /queue-send and /queue-send-all need a live REPL turn loop and report
    // that honestly instead of pretending to dispatch.
    // ========================================================================

    private static Resolution resolveQueueCommand(
            String name, String argumentRegion, String command, WebChatInput input,
            ChatSessionStateStore store, Path directory) {
        String sessionId = input.sessionId() == null || input.sessionId().isBlank()
                ? "unknown-session" : input.sessionId();
        String rest = stripQuotes(argumentRegion == null ? "" : argumentRegion.strip());
        // The queue file is keyed by session id only (not working directory),
        // mirroring MessageQueue.getQueueFilePath so the live CLI and web share it.
        MessageQueue queue = new MessageQueue(sessionId);
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "queue");
        data.put("scope", "session");
        data.set("queued", refreshedEntries(queue));
        switch (name) {
                case "queue" -> {
                    if (rest.isEmpty()) {
                        return new Resolution(Status.INTERACTION_REQUIRED, command,
                                queueSummary(queue), null, data);
                    }
                    MessageQueue.QueuedMessage added = queue.enqueue(rest);
                    if (added == null) {
                        return new Resolution(Status.INVALID, command,
                                "That message is already queued; no change was made.", null, data);
                    }
                    data.set("queued", refreshedEntries(queue));
                    return new Resolution(Status.INTERACTION_REQUIRED, command,
                            "Message queued [" + added.getId() + "]", null, data);
                }
                case "queues" -> {
                    return new Resolution(Status.INTERACTION_REQUIRED, command,
                            queueSummary(queue), null, data);
                }
                case "queue-status" -> {
                    return new Resolution(Status.INTERACTION_REQUIRED, command,
                            queueSummary(queue), null, data);
                }
                case "queue-remove" -> {
                    if (rest.isBlank()) {
                        return new Resolution(Status.INVALID, command,
                                "Usage: /queue-remove <id>", null, data);
                    }
                    if (!queue.remove(rest)) {
                        return new Resolution(Status.INVALID, command,
                                "Message not found: " + rest, null, data);
                    }
                    data.set("queued", refreshedEntries(queue));
                    return new Resolution(Status.INTERACTION_REQUIRED, command,
                            "Removed message [" + rest + "]", null, data);
                }
                case "queue-edit" -> {
                    String[] parts = rest.split("\\s+", 2);
                    if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                        return new Resolution(Status.INVALID, command,
                                "Usage: /queue-edit <id> <new message>", null, data);
                    }
                    if (!queue.update(parts[0], parts[1].strip())) {
                        return new Resolution(Status.INVALID, command,
                                "Message not found: " + parts[0], null, data);
                    }
                    data.set("queued", refreshedEntries(queue));
                    return new Resolution(Status.INTERACTION_REQUIRED, command,
                            "Updated message [" + parts[0] + "]", null, data);
                }
                case "queue-move" -> {
                    String[] parts = rest.split("\\s+", 2);
                    if (parts.length < 2) {
                        return new Resolution(Status.INVALID, command,
                                "Usage: /queue-move <id> <position>", null, data);
                    }
                    int position;
                    try {
                        position = Integer.parseInt(parts[1].strip());
                    } catch (NumberFormatException invalid) {
                        return new Resolution(Status.INVALID, command,
                                "Queue position must be a number: " + parts[1], null, data);
                    }
                    if (position < 1 || position > queue.size()) {
                        return new Resolution(Status.INVALID, command,
                                "Queue position must be between 1 and " + queue.size(), null, data);
                    }
                    if (!queue.move(parts[0], position - 1)) {
                        return new Resolution(Status.INVALID, command,
                                "Message not found: " + parts[0], null, data);
                    }
                    data.set("queued", refreshedEntries(queue));
                    return new Resolution(Status.INTERACTION_REQUIRED, command,
                            "Moved message [" + parts[0] + "] to position " + position,
                            null, data);
                }
                case "queue-clear" -> {
                    int cleared = queue.size();
                    queue.clear();
                    data.set("queued", refreshedEntries(queue));
                    return new Resolution(Status.INTERACTION_REQUIRED, command,
                            "Queue cleared (" + cleared + " message"
                                    + (cleared == 1 ? ")" : "s)") + ".",
                            null, data);
                }
                case "queue-send", "queue-send-all" -> {
                    return new Resolution(Status.LIVE_SESSION_REQUIRED, command,
                            "Sending queued messages injects them into a live chat turn loop; "
                                    + "web command runs have no live process to dispatch into. "
                                    + "The queue is unchanged and will be consumed by the "
                                    + "interactive CLI (or auto-dequeue at the next turn boundary).",
                            null, data);
                }
                default -> {
                    return new Resolution(Status.NOT_YET_SUPPORTED, command,
                            command + " is not supported by web input.", null, data);
                }
            }
    }

    private static ArrayNode refreshedEntries(MessageQueue queue) {
        ArrayNode entries = JsonUtils.standardMapper().createArrayNode();
        for (MessageQueue.QueuedMessage msg : queue.getAll()) {
            entries.add(queueSnapshot(msg));
        }
        return entries;
    }

    private static ObjectNode queueSnapshot(MessageQueue.QueuedMessage msg) {
        ObjectNode node = JsonUtils.standardMapper().createObjectNode();
        node.put("id", msg.getId());
        node.put("content", msg.getContent());
        node.put("status", msg.getStatus().name());
        node.put("createdAt", msg.getCreatedAt().toString());
        return node;
    }

    private static String queueSummary(MessageQueue queue) {
        if (queue.isEmpty()) return "Queue is empty.";
        StringBuilder text = new StringBuilder("Queued messages (")
                .append(queue.size()).append("):");
        java.util.List<MessageQueue.QueuedMessage> messages = queue.getAll();
        for (int i = 0; i < messages.size(); i++) {
            MessageQueue.QueuedMessage msg = messages.get(i);
            String content = msg.getContent();
            String truncated = content.length() > 70
                    ? content.substring(0, 70) + "…" : content;
            text.append("\n  ").append(i + 1).append(". [").append(msg.getId())
                    .append("] ").append(truncated);
        }
        return text.toString();
    }

    // ========================================================================
    // Web /clear: the interactive CLI starts a fresh transcript (the old one
    // stays resumable). Over web input the CLI cannot own the browser view, so
    // the resolution carries a structured instruction and any arguments are
    // rejected: the BROWSER performs the clear (new chat) on receiving it.
    // Durable session-scoped state (model, role, fast mode) intentionally
    // follows the session id; a brand-new browser chat gets a fresh id and
    // therefore starts clean, while /queue and reminders persist by design.
    // ========================================================================

    private static Resolution resolveClearCommand(
            String command, WebChatInput input,
            ChatSessionStateStore store, Path directory) {
        ObjectNode data = JsonUtils.standardMapper().createObjectNode();
        data.put("menu", "clear");
        String sessionId = input.sessionId() == null || input.sessionId().isBlank()
                ? "" : input.sessionId();
        data.put("sessionId", sessionId);
        return new Resolution(Status.INTERACTION_REQUIRED, command,
                "Starting a new conversation. The previous transcript stays resumable; "
                        + "queued messages and reminders are kept.", null, data);
    }
}
