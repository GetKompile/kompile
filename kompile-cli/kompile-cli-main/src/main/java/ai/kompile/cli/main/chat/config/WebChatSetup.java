package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.exec.ChatSessionStateStore;
import ai.kompile.cli.main.chat.exec.HeadlessPassthroughRunner;
import ai.kompile.cli.main.chat.exec.WebCommandResolver;
import ai.kompile.cli.main.chat.exec.WebModelCatalog;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Quiet browser adapter to the terminal wizard's authoritative catalogs and stores. */
public final class WebChatSetup {
    private WebChatSetup() { }
    private static final List<String> FIELDS = List.of("mode", "runtime", "leadMode", "profile", "vendor",
            "authMethod", "authenticationScope", "credentialName", "apiKey", "baseUrl", "model", "thinking",
            "fastMode", "ultracode", "passthroughAgent", "passthroughManaged", "workflow", "saveProfile",
            "replaceProfile", "saveScope", "judges");
    private static final List<String> TERMINAL_ACTIONS = List.of(
            "New OAuth sign-in and native CLI re-login: run kompile chat --setup in a terminal.",
            "Unmanaged passthrough and native workflow leads require terminal chat.",
            "Create/edit workflow teams (designer/templates) and judge backends (CRUD/defaults) in the terminal wizard; saved profiles are selectable here.",
            "Acquire/import Hugging Face or local models and select authentication gateways/enterprise OAuth methods in terminal setup.",
            "Resume existing/bulk conversations is a terminal action or the existing web conversation picker.");

    public static ObjectNode handle(Path directory, JsonNode request) throws IOException {
        if (request == null || !request.isObject()) throw new IllegalArgumentException("Expected setup object");
        String action = text(request, "action", "catalog");
        JsonNode selection = request.path("selection");
        if (selection.isMissingNode() || selection.isNull()) selection = JsonUtils.standardMapper().createObjectNode();
        validateFields(selection);
        // An existing chat's wizard starts from the route its next turn runs, not the folder defaults.
        String sessionId = text(request, "sessionId", null);
        if ("catalog".equals(action))
            return catalog(directory, selection, sessionId == null ? null : sessionRoute(directory, sessionId));
        if ("judge".equals(action)) {
            applyJudges(directory, request.path("judges"));
            return success();
        }
        if ("update".equals(action)) return update(directory, sessionId, selection);
        if (!List.of("create", "profile").contains(action)) throw new IllegalArgumentException("Unknown setup action");
        ChatConfig config = selection(directory, selection, true);
        WorkflowTeamSnapshot workflow = workflow(directory, selection, config);
        validateSaves(directory, selection, config, "profile".equals(action));
        if ("create".equals(action)) {
            Path target = ChatConfig.sessionConfigPath(sessionId);
            if (Files.exists(target)) throw new IllegalArgumentException("Session already configured");
            // This is the sole secret-persistence boundary. ChatConfig writes only credential references.
            config.bindSession(sessionId);
            if (workflow != null) WorkflowSessionContext.start(sessionId, workflow);
        }
        return applySaves(directory, selection, config);
    }

    /**
     * The session wizard: the same mode, native framework, vendor, authentication, credential,
     * endpoint, model and effort choices as a new chat, decided the same way, rewriting this
     * chat's route. A native framework is switched like a vendor; it starts its own native
     * session on the next turn. Only the workflow team is fixed at creation.
     */
    private static ObjectNode update(Path directory, String sessionId, JsonNode input) throws IOException {
        if (sessionId == null) throw new IllegalArgumentException("Choose a chat to configure");
        ChatConfig current = sessionRoute(directory, sessionId);
        if (input.hasNonNull("workflow") || input.hasNonNull("leadMode") || "workflow".equals(text(input, "mode", null)))
            throw new IllegalArgumentException("A chat keeps its workflow team; start a new chat to change it");
        ChatConfig config = selection(directory, input, true, current);
        validateSaves(directory, input, config, false);
        if ("passthrough".equals(config.getChatMode()) && (!"passthrough".equals(current.getChatMode())
                || !config.getPassthroughAgent().equals(current.getPassthroughAgent())))
            HeadlessPassthroughRunner.startFreshNativeSession(sessionId, directory, config.getPassthroughAgent());
        config.bindSession(sessionId);
        // Earlier web /model and /thinking overrides would otherwise win over the rewritten route.
        if (!new ChatSessionStateStore().clearRoute(sessionId, directory).applied())
            throw new IllegalArgumentException("The route was saved, but the earlier web model override could not be "
                    + "cleared; it still applies until the session state lock is free. Apply again.");
        ObjectNode saved = applySaves(directory, input, config);
        WebCommandResolver.publishRoute(sessionId, directory);
        return saved;
    }

    private static ChatConfig sessionRoute(Path directory, String sessionId) {
        ChatConfig current = WebCommandResolver.effectiveSessionConfig(sessionId, directory);
        if (current == null) throw new IllegalArgumentException("This chat has no configuration; run kompile chat --setup");
        return current;
    }

    private static void validateSaves(Path directory, JsonNode selection, ChatConfig config, boolean profileRequired)
            throws IOException {
        String saveScope = text(selection, "saveScope", "session");
        if (!List.of("session", "project", "global").contains(saveScope)) throw new IllegalArgumentException("Invalid save scope");
        String saveProfile = text(selection, "saveProfile", null);
        if (profileRequired && saveProfile == null) throw new IllegalArgumentException("Profile name required");
        if (saveProfile != null && !selection.path("replaceProfile").asBoolean(false)
                && ChatProfiles.list(directory, config.getChatMode()).stream().anyMatch(p -> p.name().equals(saveProfile)))
            throw new IllegalArgumentException("Profile already exists; confirm replacement");
        validateJudges(directory, selection.path("judges"));
    }

    private static ObjectNode applySaves(Path directory, JsonNode selection, ChatConfig config) throws IOException {
        String saveScope = text(selection, "saveScope", "session");
        String saveProfile = text(selection, "saveProfile", null);
        if (!"session".equals(saveScope)) config.copy().save("global".equals(saveScope)
                ? ChatConfig.Scope.GLOBAL : ChatConfig.Scope.PROJECT, directory);
        if (saveProfile != null && !ChatProfiles.save(directory, ChatProfiles.capture(saveProfile, config),
                selection.path("replaceProfile").asBoolean(false))) throw new IllegalArgumentException("Profile already exists");
        applyJudges(directory, selection.path("judges"));
        ObjectNode out = success();
        out.put("framework", "passthrough".equals(config.getChatMode()) ? config.getPassthroughAgent() : "standard");
        if (config.getModel() != null) out.put("model", config.getModel());
        return out;
    }

    static ChatConfig selection(Path directory, JsonNode input, boolean creating) throws IOException {
        return selection(directory, input, creating, null);
    }

    private static ChatConfig selection(Path directory, JsonNode input, boolean creating, ChatConfig base)
            throws IOException {
        String profile = text(input, "profile", null);
        ChatConfig config = base != null ? base : ChatConfig.loadProject(directory);
        config = config == null ? new ChatConfig() : config.copy();
        if (profile != null) {
            List<ChatProfiles.Profile> profiles = new ArrayList<>(ChatProfiles.list(directory, "standard"));
            profiles.addAll(ChatProfiles.list(directory, "passthrough"));
            config = profiles.stream().filter(p -> p.name().equals(profile)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown chat profile")).toConfig();
        }
        String mode = text(input, "mode", config.getChatMode() == null ? "standard" : config.getChatMode());
        if (!List.of("standard", "passthrough", "workflow").contains(mode)) throw new IllegalArgumentException("Invalid chat mode");
        if ("workflow".equals(mode)) mode = text(input, "leadMode", "standard");
        if (!List.of("standard", "passthrough").contains(mode)) throw new IllegalArgumentException("Invalid workflow lead mode");
        // Like a vendor change: another mode or native framework lists other models and effort levels.
        String priorMode = config.getChatMode() == null ? "standard" : config.getChatMode();
        String priorAgent = config.getPassthroughAgent();
        if (!mode.equals(priorMode)) clearModelChoice(config);
        config.setChatMode(mode);
        if ("passthrough".equals(mode)) {
            config.setPassthroughAgent(text(input, "passthroughAgent", config.getPassthroughAgent()));
            if (mode.equals(priorMode) && !java.util.Objects.equals(priorAgent, config.getPassthroughAgent()))
                clearModelChoice(config);
            config.setPassthroughManaged(input.path("passthroughManaged").asBoolean(config.isPassthroughManaged()));
            if (creating && !SetupWizard.supportsWeb(config)) throw new IllegalArgumentException("This native launch requires terminal chat");
            String nativeAgent = config.getPassthroughAgent();
            // Same agent keys the terminal wizard offers and persists (registry commands, e.g. "claude").
            if ((creating && nativeAgent == null) || (nativeAgent != null && !ChatConfig.getPassthroughAgents().containsKey(nativeAgent)))
                throw new IllegalArgumentException("Unknown native framework");
            if (text(input, "apiKey", null) != null || text(input, "credentialName", null) != null)
                throw new IllegalArgumentException("Native framework owns authentication; use its terminal login");
        } else {
            String runtime = text(input, "runtime", runtime(config));
            String vendor = text(input, "vendor", SetupWizard.vendorForProvider(config.getProvider()));
            List<String> allowed = vendors(runtime);
            if (vendor == null || !allowed.contains(vendor)) vendor = allowed.get(0);
            if (input.hasNonNull("vendor") && !allowed.contains(text(input, "vendor", null)))
                throw new IllegalArgumentException("Vendor is not available for this runtime");
            boolean sameVendor = vendor.equals(SetupWizard.vendorForProvider(config.getProvider()));
            SetupWizard.AuthMethod method = auth(text(input, "authMethod", sameVendor ? config.getAuthenticationMethod() : null), vendor);
            if (!methods(vendor).contains(method)) throw new IllegalArgumentException("Authentication method not supported by vendor");
            config.setProvider(SetupWizard.resolveProviderForAuth(vendor, method));
            config.setAuthenticationMethod(method.configValue());
            config.setBaseUrl(text(input, "baseUrl", sameVendor ? config.getBaseUrl() : SetupWizard.baseUrlForAuth(vendor, method)));
            validateUrl(config.getBaseUrl());
            config.setAuthenticationScope(text(input, "authenticationScope", config.getAuthenticationScope()));
            config.setCredentialName(text(input, "credentialName", sameVendor ? config.getCredentialName() : null));
            if (!sameVendor) clearModelChoice(config);
            String inputKey = text(input, "apiKey", null);
            config.setApiKey(inputKey);
            if (inputKey != null && method != SetupWizard.AuthMethod.API_KEY && method != SetupWizard.AuthMethod.API_KEY_CREDITS)
                throw new IllegalArgumentException("API key requires API-key authentication");
            String credential = config.getCredentialName();
            if (credential != null && CredentialStore.create().list(config.getProvider()).stream()
                    .noneMatch(c -> c.credentialName().equals(credential) && compatible(c, method)))
                throw new IllegalArgumentException("Selected credential is not available for this vendor and authentication route");
        }
        String savedModel = config.getModel();
        config.setModel(text(input, "model", config.getModel()));
        config.setThinking(text(input, "thinking", config.getThinking()));
        config.setFastMode(input.path("fastMode").asBoolean(config.isFastMode()));
        config.setUltracode(input.path("ultracode").asBoolean(config.isUltracode()));
        if (creating) {
            if (!SetupWizard.supportsWeb(config)) throw new IllegalArgumentException("This launch requires the complete CLI wizard");
            if (!config.isValid()) throw new IllegalArgumentException("Choose a model and an existing compatible credential; new sign-in requires terminal setup");
            if (config.isFastMode() && !config.supportsFastMode()) throw new IllegalArgumentException("Fast mode is unsupported on this route");
            if (config.isUltracode() && !config.supportsUltracode()) throw new IllegalArgumentException("Ultracode is unsupported on this route");
            if ("passthrough".equals(mode) && config.getThinking() != null
                    && !SetupWizard.supportsPassthroughThinking(config.getPassthroughAgent(), config.isPassthroughManaged()))
                throw new IllegalArgumentException("Native framework does not accept thinking in this launch mode");
            // A newly chosen model is decided like the terminal /model command: the route's live
            // list (or, when the provider is unreachable, its last known good catalog).
            boolean newModel = config.getModel() != null && !config.getModel().equalsIgnoreCase(savedModel);
            boolean standardThinking = !"passthrough".equals(mode) && config.getThinking() != null;
            ModelDiscovery.Result discovery = newModel || standardThinking
                    ? WebModelCatalog.discover(config, text(input, "apiKey", null)) : null;
            if (newModel && ModelCatalogSelection.decisionFor(discovery,
                    "passthrough".equals(mode) ? config.getPassthroughAgent() : config.getProvider(), config.getModel())
                    == ModelCatalogSelection.SelectionDecision.UNKNOWN)
                throw new IllegalArgumentException("Model '" + config.getModel() + "' is not in this route's live model list "
                        + "or its last known good catalog");
            if (standardThinking && !config.getThinking().equals(SetupWizard.compatibleThinking(
                    config.getProvider(), config.getModel(), config.getThinking(), discovery)))
                throw new IllegalArgumentException("Thinking option is unsupported for this model");
        }
        return config;
    }

    private static ObjectNode catalog(Path directory, JsonNode input, ChatConfig session) throws IOException {
        ObjectNode out = JsonUtils.standardMapper().createObjectNode();
        out.put("available", true);
        out.put("session", session != null);
        out.set("terminalActions", JsonUtils.standardMapper().valueToTree(TERMINAL_ACTIONS));
        List<ChatProfiles.Profile> profiles = new ArrayList<>(ChatProfiles.list(directory, "standard"));
        profiles.addAll(ChatProfiles.list(directory, "passthrough"));
        var entries = out.putArray("profiles");
        for (var p : profiles) entries.addObject().put("name", p.name()).put("label", p.name()).put("mode", p.mode());
        var judges = out.putArray("judgeProfiles");
        for (var p : ChatProfiles.list(directory, "judge")) judges.addObject().put("name", p.name())
                .put("label", p.name()).put("vendor", p.vendor()).put("provider", p.provider()).put("model", p.model())
                .put("thinking", p.thinking());
        var workflows = out.putArray("workflows");
        for (var w : WorkflowTeamStore.list(directory)) workflows.addObject().put("name", w.name()).put("label", w.name());
        // Modes, runtimes and native agents come from the terminal wizard's own menus, not a browser copy.
        var modes = out.putArray("modes");
        for (int i = 0; i < SetupWizard.chatModeValues().size(); i++)
            modes.addObject().put("id", SetupWizard.chatModeValues().get(i)).put("label", SetupWizard.chatModeOptions().get(i));
        var leadModes = out.putArray("leadModes");
        for (int i = 0; i < SetupWizard.workflowLeadValues().size(); i++)
            leadModes.addObject().put("id", SetupWizard.workflowLeadValues().get(i)).put("label", SetupWizard.workflowLeadOptions().get(i));
        var styles = out.putArray("passthroughStyles");
        for (int i = 0; i < SetupWizard.passthroughStyleOptions().size(); i++)
            styles.addObject().put("managed", i == 0).put("label", SetupWizard.passthroughStyleOptions().get(i));
        var runtimes = out.putArray("runtimes");
        for (var r : SetupWizard.StandardRuntime.values())
            runtimes.addObject().put("id", runtimeId(r)).put("label", SetupWizard.standardRuntimeOptions().get(r.ordinal()));
        var frameworks = out.putArray("frameworks");
        for (var agent : ChatConfig.getPassthroughAgents().entrySet()) frameworks.addObject().put("id", agent.getKey())
                .put("label", agent.getValue()).put("available", SubprocessAgentRunner.resolveAgentBinary(agent.getKey()) != null)
                .put("webSupported", SubprocessAgentRunner.supportsHeadless(agent.getKey()));
        ChatConfig config = selection(directory, input, false, session);
        boolean nativeLead = "passthrough".equals(config.getChatMode());
        // Mirrors SetupWizard.supportsWeb minus completeness: whether this route can run in a browser at all.
        out.put("webSupported", nativeLead
                ? !"workflow".equals(text(input, "mode", null)) && config.isPassthroughManaged()
                        && SubprocessAgentRunner.supportsHeadless(config.getPassthroughAgent())
                : !config.isKompileServer());
        ObjectNode defaults = out.putObject("defaults");
        defaults.put("mode", text(input, "mode", config.getChatMode()));
        defaults.put("runtime", text(input, "runtime", runtime(config)));
        defaults.put("leadMode", config.getChatMode());
        defaults.put("authenticationScope", config.getAuthenticationScope());
        defaults.put("saveScope", "session");
        defaults.put("fastMode", config.isFastMode()); defaults.put("ultracode", config.isUltracode());
        defaults.put("passthroughManaged", config.isPassthroughManaged());
        if (config.getPassthroughAgent() != null) defaults.put("passthroughAgent", config.getPassthroughAgent());
        if (config.getProvider() != null) defaults.put("vendor", SetupWizard.vendorForProvider(config.getProvider()));
        if (config.getAuthenticationMethod() != null) defaults.put("authMethod", config.getAuthenticationMethod());
        if (config.getModel() != null) defaults.put("model", config.getModel());
        if (config.getThinking() != null) defaults.put("thinking", config.getThinking());
        if (config.getBaseUrl() != null) defaults.put("baseUrl", config.getBaseUrl());
        if (config.getCredentialName() != null) defaults.put("credentialName", config.getCredentialName());
        for (String field : List.of("profile", "workflow", "credentialName")) if (input.hasNonNull(field)) defaults.put(field, text(input, field, null));
        var vendors = out.putArray("vendors");
        for (String vendor : vendors(text(input, "runtime", runtime(config)))) {
            var v = vendors.addObject().put("id", vendor).put("label", SetupWizard.vendorLabel(vendor))
                    .put("endpointRequired", SetupWizard.requiresBaseUrl(vendor));
            var options = v.putArray("authMethods");
            for (var method : methods(vendor)) options.addObject().put("id", method.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                    .put("label", SetupWizard.authMethodLabel(vendor, method))
                    .put("acceptsKey", method == SetupWizard.AuthMethod.API_KEY || method == SetupWizard.AuthMethod.API_KEY_CREDITS)
                    .put("signIn", method == SetupWizard.AuthMethod.OAUTH || method == SetupWizard.AuthMethod.NATIVE);
        }
        var credentials = out.putArray("credentials");
        if (config.getProvider() != null) {
            var method = auth(config.getAuthenticationMethod(), SetupWizard.vendorForProvider(config.getProvider()));
            for (var c : CredentialStore.create().list(config.getProvider())) if (compatible(c, method))
                credentials.addObject().put("name", c.credentialName()).put("label", c.credentialName() + " — " + c.type()
                        + (c.identity() == null ? "" : " — " + c.identity())
                        + (c.status() == null || c.status().isBlank() ? "" : " (" + c.status() + ")"));
        }
        var models = out.putArray("models");
        var errors = out.putArray("errors");
        ModelDiscovery.Result discovery = null;
        List<LiveModelDiscovery.Model> discovered = List.of();
        // The terminal /model picker's flow: live discovery for the route (one retry on a
        // transient failure), then listForPicker, which records a verified list and falls
        // back to the last known good catalog only when the provider could not be reached.
        String catalogKey = nativeLead ? config.getPassthroughAgent() : config.getProvider();
        try {
            if (!nativeLead || catalogKey != null) {
                discovery = WebModelCatalog.discover(config, text(input, "apiKey", null));
                discovered = discovery.models();
                var catalog = ModelCatalogSelection.listForPicker(discovery, catalogKey);
                // Models are only ever chosen from this list; the browser offers no free-text model entry.
                for (String id : catalog.models()) models.addObject().put("id", id).put("label", id);
                if (!catalog.banner().isBlank()) errors.add(catalog.banner());
                else if (catalog.models().isEmpty()) errors.add(discovery.message() == null || discovery.message().isBlank()
                        ? "The provider returned no models; check this route's account or endpoint, then refresh." : discovery.message());
            }
        } catch (RuntimeException unavailable) {
            errors.add("Model discovery failed: " + (unavailable.getMessage() == null
                    ? unavailable.getClass().getSimpleName() : unavailable.getMessage()) + ".");
        }
        if (nativeLead) {
            var thinking = out.putArray("thinkingOptions");
            if (SetupWizard.supportsPassthroughThinking(config.getPassthroughAgent(), config.isPassthroughManaged()))
                discovered.stream().filter(m -> m.id().equals(config.getModel())).findFirst().ifPresent(m ->
                        m.thinkingVariants().forEach(v -> thinking.addObject().put("value", v.value()).put("label", v.label())));
            out.put("fastModeSupported", false);
            out.put("ultracodeSupported", false);
        } else {
            out.set("thinkingOptions", JsonUtils.standardMapper().valueToTree(SetupWizard.thinkingOptions(
                    config.getProvider(), config.getModel(), null, config, discovery)));
            out.put("fastModeSupported", config.supportsFastMode());
            out.put("ultracodeSupported", config.supportsUltracode());
        }
        return out;
    }

    private static void clearModelChoice(ChatConfig config) {
        config.setModel(null); config.setThinking(null);
        config.setFastMode(false); config.setUltracode(false);
    }

    private static WorkflowTeamSnapshot workflow(Path directory, JsonNode input, ChatConfig config) throws IOException {
        String name = text(input, "workflow", null);
        if ("workflow".equals(text(input, "mode", null)) && name == null) throw new IllegalArgumentException("Select a saved workflow");
        if (name == null) return null;
        if (!SetupWizard.canLeadWorkflow(config) || "passthrough".equals(config.getChatMode()))
            throw new IllegalArgumentException("This workflow lead requires terminal chat; use a standard direct lead on the web");
        var team = WorkflowTeamStore.get(directory, name);
        if (team == null) throw new IllegalArgumentException("Unknown saved workflow");
        return WorkflowTeamSnapshot.resolve(team, new RoleManager(directory));
    }

    private static void validateJudges(Path directory, JsonNode judges) throws IOException {
        if (judges.isMissingNode() || judges.isNull()) return;
        if (!judges.isArray()) throw new IllegalArgumentException("Judges must be profile actions");
        for (JsonNode judge : judges) {
            String action = text(judge, "action", "activate");
            if (!"activate".equals(action)) throw new IllegalArgumentException("Judge editing requires terminal wizard; web supports saved profile activation");
            String provider = text(judge, "provider", null), profile = text(judge, "profile", null);
            if (ChatProfiles.list(directory, "judge").stream().noneMatch(p -> p.provider().equals(provider) && p.name().equals(profile)))
                throw new IllegalArgumentException("Unknown judge profile for provider");
        }
    }
    private static void applyJudges(Path directory, JsonNode judges) throws IOException {
        validateJudges(directory, judges);
        if (judges.isArray()) for (JsonNode judge : judges)
            ChatProfiles.activateJudge(directory, text(judge, "provider", null), text(judge, "profile", null));
    }
    private static List<String> vendors(String runtime) {
        return switch (runtime) {
            case "kompile", "kompile-local" -> List.of(runtime);
            case "external-local" -> java.util.stream.Stream.concat(ChatProviderRegistry.localProviders().stream().map(ChatProvider::id),
                    java.util.stream.Stream.of("custom")).distinct().toList();
            case "direct" -> SetupWizard.directVendorOrder();
            default -> throw new IllegalArgumentException("Invalid runtime");
        };
    }
    private static List<SetupWizard.AuthMethod> methods(String vendor) {
        return List.of("kompile", "kompile-local").contains(vendor) || ChatProviderRegistry.localProviders().stream().anyMatch(p -> p.id().equals(vendor))
                ? List.of(SetupWizard.AuthMethod.NONE) : SetupWizard.authMethodsForPicker(vendor);
    }
    private static SetupWizard.AuthMethod auth(String value, String vendor) {
        if (value == null) return methods(vendor).get(0);
        try { return SetupWizard.AuthMethod.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid authentication method"); }
    }
    private static boolean compatible(CredentialStore.CredentialInfo c, SetupWizard.AuthMethod method) {
        return method == SetupWizard.AuthMethod.OAUTH ? "oauth".equals(c.type()) && (c.expiresAt() > System.currentTimeMillis() || c.refreshable())
                : (method == SetupWizard.AuthMethod.API_KEY || method == SetupWizard.AuthMethod.API_KEY_CREDITS) && ai.kompile.cli.common.auth.ManagedCredential.API_KEY.equals(c.type());
    }
    private static String runtime(ChatConfig c) {
        if (c.isKompileServer()) return "kompile";
        if (c.isKompileLocalServing()) return "kompile-local";
        return c.getProvider() != null && vendors("external-local").contains(c.getProvider()) ? "external-local" : "direct";
    }
    private static String runtimeId(SetupWizard.StandardRuntime runtime) {
        return runtime.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
    private static void validateFields(JsonNode input) {
        if (!input.isObject()) throw new IllegalArgumentException("Selection must be an object");
        input.fieldNames().forEachRemaining(field -> { if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported setup field"); });
        input.fields().forEachRemaining(e -> {
            if (List.of("fastMode", "ultracode", "passthroughManaged", "replaceProfile").contains(e.getKey())) {
                if (!e.getValue().isBoolean() && !e.getValue().isNull()) throw new IllegalArgumentException("Expected boolean setup option");
            } else if (!"judges".equals(e.getKey()) && !e.getValue().isTextual() && !e.getValue().isNull())
                throw new IllegalArgumentException("Expected text setup option");
        });
    }
    private static void validateUrl(String value) {
        if (value == null) return;
        try {
            var uri = java.net.URI.create(value);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Endpoint must be an HTTP URL without credentials, query, or fragment"); }
    }
    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException("Expected text option");
        String result = value.asText().trim();
        if (result.length() > ("apiKey".equals(field) ? 16384 : 4096) || result.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid setup option");
        return result.isBlank() ? null : result;
    }
    private static ObjectNode success() { return JsonUtils.standardMapper().createObjectNode().put("ok", true); }
}
