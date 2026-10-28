package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.ChatProvider;
import ai.kompile.cli.main.chat.config.ChatProviderRegistry;
import ai.kompile.cli.main.chat.config.LiveModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscovery;
import ai.kompile.cli.main.chat.config.ModelDiscoveryHttp;
import ai.kompile.cli.main.chat.config.ModelRouting;
import ai.kompile.cli.main.chat.config.SubagentChatConfig;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.McpToolAnnotations;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Route discovery shared by direct chat, stdio MCP, and daemon MCP. Never runs a model turn. */
public class ResolveModelTool implements CliTool {
    private final ObjectMapper mapper;
    private final Supplier<ChatConfig> configuration;

    public ResolveModelTool(ObjectMapper mapper, Path workDir) {
        this(mapper, workDir, () -> ChatConfig.loadOrFromEnv(workDir));
    }

    public ResolveModelTool(ObjectMapper mapper, Path workDir, Supplier<ChatConfig> configuration) {
        this.mapper = mapper == null ? JsonUtils.standardMapper() : mapper;
        this.configuration = configuration;
    }

    public String id() { return "resolve_model"; }
    public String permissionKey() { return "read"; }
    public McpToolAnnotations mcpAnnotations() { return new McpToolAnnotations(true, false, true, true); }

    public String description() {
        return "Search Kompile providers, auth routes and models before dispatch. "
                + "action=providers lists registered providers (registration is not proof of valid credentials); "
                + "families shows model-family routing; models requires provider and accepts query. "
                + "For anthropic, authentication_method=oauth/native uses Claude Code login; api-key selects the separate HTTP API. "
                + "Copy taskArguments from a model result. MCP task also needs agent=kompile; direct chat uses role/agent_type. "
                + "Vendor Agent tools are not Kompile dispatch. No model invocation, login, or account fallback occurs here.";
    }

    public String compactHint() {
        return "Search routes: action=providers|families|models, provider, authentication_method=oauth|api-key, query. Copy taskArguments; MCP task needs agent=kompile (not vendor Agent).";
    }

    public JsonNode parameterSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("action").put("type", "string").putArray("enum")
                .add("providers").add("families").add("models");
        props.putObject("provider").put("type", "string").put("description", "Required for models; registered provider id.");
        props.putObject("authentication_method").put("type", "string").put("description",
                "Auth route: oauth/native uses Claude Code login for anthropic; api-key uses its HTTP API. Same-provider omission inherits the selected route.")
                .putArray("enum").add("oauth").add("native").add("api-key");
        props.putObject("query").put("type", "string").put("description", "Case-insensitive model-id substring.");
        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) {
        return execute(mapper.convertValue(params, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }

    public ToolResult execute(Map<String, Object> arguments) {
        for (String selector : List.of("action", "provider", "authentication_method", "query")) {
            if (arguments.containsKey(selector) && !(arguments.get(selector) instanceof String)) {
                return ToolResult.error(selector + " must be a string");
            }
        }
        try {
            return switch (text(arguments, "action").toLowerCase(Locale.ROOT)) {
                case "providers" -> providersResult();
                case "families" -> ToolResult.success(id(), mapper.createObjectNode()
                        .set("families", mapper.valueToTree(ModelRouting.familyCatalog())).toString());
                case "models" -> modelsResult(arguments);
                default -> ToolResult.error("action must be providers, families, or models");
            };
        } catch (Exception failure) {
            return ToolResult.error("Route discovery failed: " + failure.getMessage()
                    + ". Correct provider/authentication_method; no account fallback was attempted.");
        }
    }

    private ToolResult providersResult() {
        ChatConfig current = configuration.get();
        Map<String, ChatProvider> providers = new LinkedHashMap<>();
        ChatProviderRegistry.all().forEach(p -> providers.put(p.id(), p));
        for (String alias : List.of("openai-codex", "kompile-local")) {
            ChatProvider p = ChatProviderRegistry.find(alias);
            if (p != null) providers.putIfAbsent(p.id(), p);
        }
        ArrayNode rows = mapper.createArrayNode();
        for (ChatProvider provider : providers.values()) {
            ObjectNode row = rows.addObject();
            row.put("id", provider.id());
            row.put("label", provider.displayName());
            row.put("registered", true);
            row.put("configured", current != null && provider.id().equalsIgnoreCase(current.getProvider()));
            row.put("localOnly", provider.localOnly());
            ChatConfig route = route(current, provider.id(), "");
            row.put("authentication_method", auth(route));
            row.put("transport", route.isClaudeCliNative() ? "claude-code" : "provider");
            if (ChatConfig.isClaudeCliNativeProvider(provider.id())) {
                row.putArray("authentication_methods").add("oauth").add("native").add("api-key");
                row.put("guidance", "oauth/native: Claude Code login; api-key: separate Anthropic API. These are not interchangeable credentials.");
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("providers", rows);
        result.put("guidance", "configured marks the current chat provider, not a verified credential. Use models to check the chosen provider/auth catalog. MCP tasks use agent=kompile, not the vendor Agent tool.");
        return ToolResult.success(id(), result.toString());
    }

    private ToolResult modelsResult(Map<String, Object> arguments) {
        String providerId = text(arguments, "provider").toLowerCase(Locale.ROOT);
        if (providerId.isEmpty()) return ToolResult.error("provider is required for action=models; call action=providers to list ids");
        if (ChatProviderRegistry.find(providerId) == null) return ToolResult.error("Unknown provider '" + providerId + "'; call action=providers to list registered ids");
        ChatConfig route = route(configuration.get(), providerId, text(arguments, "authentication_method"));
        ModelDiscovery.Result discovery = discoverModels(route);
        if (discovery.status() != ModelDiscovery.Status.SUCCESS && discovery.status() != ModelDiscovery.Status.SUCCESS_EMPTY) {
            return ToolResult.error("Model discovery for provider=" + providerId + ", authentication_method=" + auth(route)
                    + " returned " + discovery.status() + ": " + discovery.message()
                    + ". Correct provider/authentication_method or the login; no API-key/subscription fallback was attempted.");
        }
        String query = text(arguments, "query").toLowerCase(Locale.ROOT);
        ArrayNode models = mapper.createArrayNode();
        for (LiveModelDiscovery.Model model : discovery.models()) {
            if (!model.id().toLowerCase(Locale.ROOT).contains(query)) continue;
            ObjectNode row = models.addObject();
            row.put("id", model.id());
            row.set("thinkingVariants", mapper.valueToTree(model.thinkingVariants()));
            if (model.contextWindow() > 0) row.put("contextWindow", model.contextWindow());
            if (model.maxOutputTokens() > 0) row.put("maxOutputTokens", model.maxOutputTokens());
            row.put("provider", providerId);
            row.put("routesTo", providerId); // catalog route, not model-family ownership
            ObjectNode task = row.putObject("taskArguments");
            task.put("provider", providerId);
            task.put("model", model.id());
            if (route.getAuthenticationMethod() != null) task.put("authentication_method", route.getAuthenticationMethod());
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("provider", providerId);
        result.put("authentication_method", auth(route));
        result.put("transport", route.isClaudeCliNative() ? "claude-code" : "provider");
        result.set("models", models);
        result.put("guidance", "Copy taskArguments into task; MCP also needs agent=kompile. Native chat uses role/agent_type. Do not replace Kompile dispatch with the vendor Agent tool.");
        return ToolResult.success(id(), result.toString(), Map.of("matched", models.size(), "catalogSize", discovery.models().size()));
    }

    // Test seam: use the exact same route as delegation, without real logins/network in unit tests.
    ModelDiscovery.Result discoverModels(ChatConfig route) {
        if (route.isClaudeCliNative()) return ModelDiscoveryHttp.discoverClaudeCliResult(route);
        return ModelDiscoveryHttp.discoverResultWithAuth(route.getProvider(), route.resolveRequestAuth(), route.getBaseUrl());
    }

    private static ChatConfig route(ChatConfig current, String provider, String auth) {
        return SubagentChatConfig.configure(current == null ? new ChatConfig() : current, provider, null, null, auth);
    }

    private static String auth(ChatConfig config) {
        return config.getAuthenticationMethod() == null ? "provider-default" : config.getAuthenticationMethod();
    }

    private static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String text ? text.trim() : "";
    }
}
