package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import ai.kompile.core.agent.CliAgentRegistry;
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
        if ("catalog".equals(action)) return catalog(directory, selection);
        if ("judge".equals(action)) {
            applyJudges(directory, request.path("judges"));
            return success();
        }
        if (!List.of("create", "profile").contains(action)) throw new IllegalArgumentException("Unknown setup action");
        ChatConfig config = selection(directory, selection, true);
        WorkflowTeamSnapshot workflow = workflow(directory, selection, config);
        String saveScope = text(selection, "saveScope", "session");
        if (!List.of("session", "project", "global").contains(saveScope)) throw new IllegalArgumentException("Invalid save scope");
        String saveProfile = text(selection, "saveProfile", null);
        if ("profile".equals(action) && saveProfile == null) throw new IllegalArgumentException("Profile name required");
        if (saveProfile != null && !selection.path("replaceProfile").asBoolean(false)
                && ChatProfiles.list(directory, config.getChatMode()).stream().anyMatch(p -> p.name().equals(saveProfile)))
            throw new IllegalArgumentException("Profile already exists; confirm replacement");
        validateJudges(directory, selection.path("judges"));
        if ("create".equals(action)) {
            String id = text(request, "sessionId", null);
            Path target = ChatConfig.sessionConfigPath(id);
            if (Files.exists(target)) throw new IllegalArgumentException("Session already configured");
            // This is the sole secret-persistence boundary. ChatConfig writes only credential references.
            config.bindSession(id);
            if (workflow != null) WorkflowSessionContext.start(id, workflow);
        }
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
        String profile = text(input, "profile", null);
        ChatConfig config = ChatConfig.loadProject(directory);
        if (config == null) config = new ChatConfig();
        else config = config.copy();
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
        config.setChatMode(mode);
        if ("passthrough".equals(mode)) {
            config.setPassthroughAgent(text(input, "passthroughAgent", config.getPassthroughAgent()));
            config.setPassthroughManaged(input.path("passthroughManaged").asBoolean(config.isPassthroughManaged()));
            if (creating && !SetupWizard.supportsWeb(config)) throw new IllegalArgumentException("This native launch requires terminal chat");
            String nativeAgent = config.getPassthroughAgent();
            if ((creating && nativeAgent == null) || (nativeAgent != null && CliAgentRegistry.loadAll().stream()
                    .noneMatch(a -> a.getName().equals(nativeAgent))))
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
            if (!sameVendor) {
                config.setModel(null); config.setThinking(null);
                config.setFastMode(false); config.setUltracode(false);
            }
            String inputKey = text(input, "apiKey", null);
            config.setApiKey(inputKey);
            if (inputKey != null && method != SetupWizard.AuthMethod.API_KEY && method != SetupWizard.AuthMethod.API_KEY_CREDITS)
                throw new IllegalArgumentException("API key requires API-key authentication");
            String credential = config.getCredentialName();
            if (credential != null && CredentialStore.create().list(config.getProvider()).stream()
                    .noneMatch(c -> c.credentialName().equals(credential) && compatible(c, method)))
                throw new IllegalArgumentException("Selected credential is not available for this vendor and authentication route");
        }
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
            if (!"passthrough".equals(mode) && config.getThinking() != null
                    && !config.getThinking().equals(SetupWizard.compatibleThinking(config.getProvider(), config.getModel(), config.getThinking(), null)))
                throw new IllegalArgumentException("Thinking option is unsupported for this model");
        }
        return config;
    }

    private static ObjectNode catalog(Path directory, JsonNode input) throws IOException {
        ObjectNode out = JsonUtils.standardMapper().createObjectNode();
        out.put("available", true);
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
        var runtimes = out.putArray("runtimes");
        for (String r : List.of("direct", "kompile-local", "external-local", "kompile")) runtimes.addObject().put("id", r).put("label", r);
        var frameworks = out.putArray("frameworks");
        for (var a : CliAgentRegistry.loadAll()) frameworks.addObject().put("id", a.getName()).put("label", a.getDisplayName())
                .put("available", installed(a.getCommand()));
        ChatConfig config = selection(directory, input, false);
        ObjectNode defaults = out.putObject("defaults");
        defaults.put("mode", text(input, "mode", config.getChatMode()));
        defaults.put("runtime", runtime(config));
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
            var v = vendors.addObject().put("id", vendor).put("label", SetupWizard.vendorLabel(vendor));
            var options = v.putArray("authMethods");
            for (var method : methods(vendor)) options.addObject().put("id", method.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                    .put("label", method.name().replace('_', ' '));
        }
        var credentials = out.putArray("credentials");
        if (config.getProvider() != null) {
            var method = auth(config.getAuthenticationMethod(), SetupWizard.vendorForProvider(config.getProvider()));
            for (var c : CredentialStore.create().list(config.getProvider())) if (compatible(c, method))
                credentials.addObject().put("name", c.credentialName()).put("label", c.credentialName() + " — " + c.type());
        }
        var models = out.putArray("models");
        String modelProvider = "passthrough".equals(config.getChatMode()) ? nativeProvider(config.getPassthroughAgent()) : config.getProvider();
        ModelDiscovery.Result discovery = null;
        try {
            // Catalog reads never refresh OAuth or mutate credential state. Existing API keys may be used transiently.
            var requestAuth = catalogAuth(config, text(input, "apiKey", null));
            discovery = config.isClaudeCliNative() || ("passthrough".equals(config.getChatMode()) && "claude".equals(config.getPassthroughAgent()))
                    ? ModelDiscoveryHttp.discoverClaudeCliResult()
                    : ModelDiscoveryHttp.refreshResultWithAuth(modelProvider, requestAuth, config.getBaseUrl());
            for (var model : discovery.models()) models.addObject().put("id", model.id()).put("label", model.id());
            if (!discovery.hasModels()) out.putArray("errors").add("Model catalog unavailable; enter a model ID manually. New sign-in is terminal-only.");
        } catch (RuntimeException unavailable) {
            out.putArray("errors").add("Model catalog unavailable; enter a model ID manually.");
        }
        out.put("manualModelAllowed", true);
        out.set("thinkingOptions", JsonUtils.standardMapper().valueToTree(SetupWizard.thinkingOptions(
                modelProvider, config.getModel(), null, config, discovery)));
        out.put("fastModeSupported", config.supportsFastMode());
        out.put("ultracodeSupported", config.supportsUltracode());
        return out;
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
    /** Request headers from a stored credential only: no login, refresh, recordUsed, or default-account mutation. */
    private static ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth catalogAuth(ChatConfig config, String inputKey) throws IOException {
        var empty = new ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth(null, null, java.util.Map.of(), false);
        if (inputKey != null) return ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth.apiKey(inputKey);
        if (config.getProvider() == null || "none".equals(config.getAuthenticationMethod()) || "native".equals(config.getAuthenticationMethod())
                || "passthrough".equals(config.getChatMode()) || config.isClaudeCliNative()) return empty;
        var store = CredentialStore.create();
        String name = config.getCredentialName();
        if (name == null) name = store.defaultCredentialName(config.getProvider());
        var credential = name == null ? null : store.read(config.getProvider(), name);
        if (credential == null) return empty;
        if (credential.isApiKey() && "api-key".equals(config.getAuthenticationMethod()))
            return ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth.apiKey(credential.getKey());
        if (credential.isOAuth() && "oauth".equals(config.getAuthenticationMethod()) && !credential.expiresWithin(0, System.currentTimeMillis())) {
            var flow = new ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry().find(config.getProvider()).orElse(null);
            if (flow != null) return flow.toRequestAuth(credential);
        }
        return empty;
    }

    private static String runtime(ChatConfig c) {
        if (c.isKompileServer()) return "kompile";
        if (c.isKompileLocalServing()) return "kompile-local";
        return ChatProviderRegistry.localProviders().stream().anyMatch(p -> p.id().equals(c.getProvider())) ? "external-local" : "direct";
    }
    private static String nativeProvider(String agent) {
        return agent == null ? "custom" : switch (agent) { case "codex" -> "openai-codex"; case "claude" -> "anthropic"; default -> agent; };
    }
    private static boolean installed(String command) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String part : path.split(java.io.File.pathSeparator)) if (Files.isExecutable(Path.of(part).resolve(command))) return true;
        return false;
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
