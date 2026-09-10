/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.McpServerAuthManager;
import ai.kompile.cli.main.chat.mcp.McpConfigStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/** Configure custom MCP servers consumed by Kompile chat and managed passthrough. */
@CommandLine.Command(
        name = "mcp",
        description = "Install and manage custom MCP servers for Kompile chat.",
        mixinStandardHelpOptions = true,
        subcommands = {
                CommandLine.HelpCommand.class,
                McpCommand.Add.class,
                McpCommand.ListServers.class,
                McpCommand.Get.class,
                McpCommand.Remove.class,
                McpCommand.Enable.class,
                McpCommand.Disable.class,
                McpCommand.Auth.class
        })
public final class McpCommand implements Callable<Integer> {
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    abstract static class WorkspaceCommand {
        @CommandLine.Option(names = {"--work-dir", "-d"}, defaultValue = ".",
                description = "Project directory (default: current directory).")
        Path workDir;

        McpConfigStore store() {
            return new McpConfigStore(workDir);
        }
    }

    abstract static class ScopedMutation extends WorkspaceCommand {
        @CommandLine.Option(names = {"--scope", "-s"}, defaultValue = "project",
                description = "Configuration scope: project or user (default: project).")
        String scope;

        McpConfigStore.Scope scope() {
            return McpConfigStore.Scope.parse(scope);
        }
    }

    @CommandLine.Command(name = "add", aliases = {"install"},
            description = "Add or update a custom stdio, Streamable HTTP, or legacy SSE server.",
            mixinStandardHelpOptions = true)
    public static final class Add extends ScopedMutation implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", paramLabel = "NAME",
                description = "Unique server name.")
        String name;

        @CommandLine.Parameters(index = "1..*", arity = "1..*",
                paramLabel = "TARGET [ARG...]",
                description = "Stdio command and arguments, or one HTTP/SSE URL. Use -- before command flags.")
        List<String> targetAndArgs = new ArrayList<>();

        @CommandLine.Option(names = {"--transport", "-t"}, defaultValue = "stdio",
                description = "Transport: stdio, http, or sse (default: stdio).")
        String transport;

        @CommandLine.Option(names = {"--env", "-e"}, paramLabel = "KEY=VALUE",
                description = "Environment entry for a stdio server; repeatable. Prefer VALUE=$ENV_VAR for secrets.")
        List<String> environment = new ArrayList<>();

        @CommandLine.Option(names = {"--header", "-H"}, paramLabel = "NAME=VALUE",
                description = "HTTP header; repeatable. Prefer --header-env for secrets.")
        List<String> headers = new ArrayList<>();

        @CommandLine.Option(names = "--header-env", paramLabel = "NAME=ENV_VAR",
                description = "HTTP header populated from an environment variable; repeatable.")
        List<String> environmentHeaders = new ArrayList<>();

        @CommandLine.Option(names = "--bearer-token-env-var", paramLabel = "ENV_VAR",
                description = "Environment variable containing a bearer token for HTTP.")
        String bearerTokenEnvironmentVariable;

        @CommandLine.Option(names = "--cwd", paramLabel = "PATH",
                description = "Stdio server working directory; relative paths resolve from the project.")
        String cwd;

        @CommandLine.Option(names = "--timeout-seconds", defaultValue = "300",
                description = "Initialization and tool-call timeout in seconds (default: 300).")
        long timeoutSeconds;

        @CommandLine.Option(names = "--required",
                description = "Fail chat startup when this enabled server cannot initialize.")
        boolean required;

        @CommandLine.Option(names = "--disabled",
                description = "Install the server disabled; enable it later with `kompile mcp enable`.")
        boolean disabled;

        @CommandLine.Option(names = "--description",
                description = "Human-readable purpose of this server.")
        String description;

        @CommandLine.Option(names = "--include-tool", split = ",", paramLabel = "TOOL",
                description = "Only expose these tools; repeat or comma-separate names.")
        List<String> includeTools = new ArrayList<>();

        @CommandLine.Option(names = "--exclude-tool", split = ",", paramLabel = "TOOL",
                description = "Hide these tools; repeat or comma-separate names.")
        List<String> excludeTools = new ArrayList<>();

        @CommandLine.Option(names = "--replace",
                description = "Replace an existing entry in the selected scope.")
        boolean replace;

        @Override
        public Integer call() {
            try {
                McpConfigStore.Transport selectedTransport = parseTransport(transport);
                ObjectNode config = buildConfig(selectedTransport);
                store().put(name, config, scope(), replace);
                System.out.println((replace ? "Configured" : "Installed") + " MCP server '"
                        + name + "' in " + scope().name().toLowerCase(Locale.ROOT) + " scope.");
                System.out.println("Config: " + store().path(scope()));
                System.out.println("Restart active chat/passthrough sessions to load the server.");
                if (selectedTransport == McpConfigStore.Transport.STDIO) {
                    System.out.println("The configured command is launched on first use; package managers such as npx/uvx perform their normal installation then.");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Could not add MCP server: " + e.getMessage());
                return 1;
            }
        }

        private ObjectNode buildConfig(McpConfigStore.Transport selectedTransport) {
            if (targetAndArgs == null || targetAndArgs.isEmpty()) {
                throw new IllegalArgumentException("A command or URL is required.");
            }
            ObjectNode config = MAPPER.createObjectNode();
            config.put("type", switch (selectedTransport) {
                case STDIO -> "stdio";
                case HTTP -> "http";
                case SSE -> "sse";
            });
            config.put("enabled", !disabled);
            config.put("required", required);
            config.put("timeoutSeconds", Math.max(1L, timeoutSeconds));
            if (description != null && !description.isBlank()) config.put("description", description.trim());
            putStringArray(config, "includeTools", includeTools);
            putStringArray(config, "excludeTools", excludeTools);

            if (selectedTransport == McpConfigStore.Transport.STDIO) {
                rejectRemoteOptions();
                config.put("command", targetAndArgs.get(0));
                ArrayNode args = config.putArray("args");
                targetAndArgs.stream().skip(1).forEach(args::add);
                if (cwd != null && !cwd.isBlank()) config.put("cwd", cwd.trim());
                putMap(config, "env", parsePairs(environment, "environment"));
                warnLiteralSecrets(environment);
            } else {
                rejectStdioOptions();
                if (targetAndArgs.size() != 1) {
                    throw new IllegalArgumentException("HTTP/SSE servers accept one URL and no command arguments.");
                }
                validateUrl(targetAndArgs.get(0));
                config.put("url", targetAndArgs.get(0));
                putMap(config, "headers", parsePairs(headers, "header"));
                putMap(config, "envHeaders", parsePairs(environmentHeaders, "header environment"));
                if (bearerTokenEnvironmentVariable != null
                        && !bearerTokenEnvironmentVariable.isBlank()) {
                    config.put("bearerTokenEnvVar", bearerTokenEnvironmentVariable.trim());
                }
                warnLiteralSecrets(headers);
            }
            return config;
        }

        private void rejectRemoteOptions() {
            if (!headers.isEmpty() || !environmentHeaders.isEmpty()
                    || (bearerTokenEnvironmentVariable != null
                    && !bearerTokenEnvironmentVariable.isBlank())) {
                throw new IllegalArgumentException("Headers and bearer tokens require http or sse transport.");
            }
        }

        private void rejectStdioOptions() {
            if (!environment.isEmpty() || (cwd != null && !cwd.isBlank())) {
                throw new IllegalArgumentException("--env and --cwd require stdio transport.");
            }
        }
    }

    @CommandLine.Command(name = "list",
            description = "List configured custom MCP servers.",
            mixinStandardHelpOptions = true)
    public static final class ListServers extends WorkspaceCommand implements Callable<Integer> {
        @CommandLine.Option(names = {"--scope", "-s"}, defaultValue = "effective",
                description = "View: effective, project, or user (default: effective).")
        String view;

        @CommandLine.Option(names = "--json", description = "Print redacted JSON.")
        boolean json;

        @Override
        public Integer call() {
            try {
                List<McpConfigStore.ConfiguredServer> servers = store().list(
                        McpConfigStore.View.parse(view));
                if (json) {
                    ArrayNode output = MAPPER.createArrayNode();
                    for (McpConfigStore.ConfiguredServer server : servers) {
                        ObjectNode item = output.addObject();
                        item.put("name", server.name());
                        item.put("scope", server.scope().name().toLowerCase(Locale.ROOT));
                        item.set("config", McpConfigStore.redacted(server.config()));
                    }
                    System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
                    return 0;
                }
                if (servers.isEmpty()) {
                    System.out.println("No custom MCP servers configured.");
                    System.out.println("Install one with: kompile mcp add <name> -- <command> [args...]");
                    return 0;
                }
                System.out.printf("%-24s %-9s %-8s %-9s %s%n",
                        "NAME", "SCOPE", "TYPE", "STATUS", "TARGET");
                for (McpConfigStore.ConfiguredServer server : servers) {
                    JsonNode config = server.config();
                    McpConfigStore.Transport transport = McpConfigStore.transportOf(config);
                    String target = transport == McpConfigStore.Transport.STDIO
                            ? config.path("command").asText("")
                            : McpConfigStore.redactedEndpoint(McpConfigStore.endpointOf(config));
                    String status = config.path("enabled").asBoolean(true)
                            ? (config.path("required").asBoolean(false) ? "required" : "enabled")
                            : "disabled";
                    System.out.printf("%-24s %-9s %-8s %-9s %s%n",
                            server.name(), server.scope().name().toLowerCase(Locale.ROOT),
                            transport.name().toLowerCase(Locale.ROOT), status, target);
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Could not list MCP servers: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "get",
            description = "Show one configured MCP server with secret values redacted.",
            mixinStandardHelpOptions = true)
    public static final class Get extends WorkspaceCommand implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", paramLabel = "NAME")
        String name;

        @CommandLine.Option(names = {"--scope", "-s"}, defaultValue = "effective",
                description = "View: effective, project, or user (default: effective).")
        String view;

        @Override
        public Integer call() {
            try {
                var server = store().find(name, McpConfigStore.View.parse(view));
                if (server.isEmpty()) {
                    System.err.println("No MCP server named '" + name + "' in " + view + " view.");
                    return 1;
                }
                ObjectNode output = MAPPER.createObjectNode();
                output.put("name", server.get().name());
                output.put("scope", server.get().scope().name().toLowerCase(Locale.ROOT));
                output.set("config", McpConfigStore.redacted(server.get().config()));
                System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
                return 0;
            } catch (Exception e) {
                System.err.println("Could not get MCP server: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "remove", aliases = {"uninstall"},
            description = "Remove a custom MCP server from one scope.",
            mixinStandardHelpOptions = true)
    public static final class Remove extends ScopedMutation implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", paramLabel = "NAME")
        String name;

        @Override
        public Integer call() {
            try {
                if (!store().remove(name, scope())) {
                    System.err.println("No MCP server named '" + name + "' in "
                            + scope().name().toLowerCase(Locale.ROOT) + " scope.");
                    return 1;
                }
                System.out.println("Removed MCP server '" + name + "' from "
                        + scope().name().toLowerCase(Locale.ROOT) + " scope.");
                return 0;
            } catch (Exception e) {
                System.err.println("Could not remove MCP server: " + e.getMessage());
                return 1;
            }
        }
    }

    abstract static class SetEnabled extends ScopedMutation implements Callable<Integer> {
        @CommandLine.Parameters(index = "0", paramLabel = "NAME")
        String name;

        abstract boolean enabled();

        @Override
        public Integer call() {
            try {
                if (!store().setEnabled(name, scope(), enabled())) {
                    System.err.println("No MCP server named '" + name + "' in "
                            + scope().name().toLowerCase(Locale.ROOT) + " scope.");
                    return 1;
                }
                System.out.println((enabled() ? "Enabled" : "Disabled") + " MCP server '"
                        + name + "' in " + scope().name().toLowerCase(Locale.ROOT) + " scope.");
                System.out.println("Restart active chat/passthrough sessions to apply the change.");
                return 0;
            } catch (Exception e) {
                System.err.println("Could not update MCP server: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "enable", description = "Enable a configured MCP server.",
            mixinStandardHelpOptions = true)
    public static final class Enable extends SetEnabled {
        @Override boolean enabled() { return true; }
    }

    @CommandLine.Command(name = "disable", description = "Disable a configured MCP server without removing it.",
            mixinStandardHelpOptions = true)
    public static final class Disable extends SetEnabled {
        @Override boolean enabled() { return false; }
    }

    @CommandLine.Command(name = "auth", mixinStandardHelpOptions = true,
            description = "OAuth login, status, and logout for a custom HTTP MCP server.",
            subcommands = {CommandLine.HelpCommand.class,
                    McpCommand.Auth.Login.class, McpCommand.Auth.Status.class,
                    McpCommand.Auth.Logout.class})
    public static final class Auth implements Callable<Integer> {
        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        private static McpServerAuthManager manager() {
            return new McpServerAuthManager(CredentialStore.create());
        }

        private static McpConfigStore.ConfiguredServer requireHttpServer(
                McpConfigStore store, String name) throws Exception {
            var server = store.find(name, McpConfigStore.View.EFFECTIVE);
            if (server.isEmpty()) {
                throw new IllegalArgumentException(
                        "No MCP server named '" + name + "'. Install it with `kompile mcp add` first.");
            }
            McpConfigStore.Transport transport = McpConfigStore.transportOf(server.get().config());
            if (transport == McpConfigStore.Transport.STDIO) {
                throw new IllegalArgumentException(
                        "OAuth applies to http/sse servers only; '" + name + "' is stdio.");
            }
            return server.get();
        }

        @CommandLine.Command(name = "login", mixinStandardHelpOptions = true,
                description = "Run browser OAuth (Authorization Code + PKCE) for a custom MCP server.")
        public static final class Login extends WorkspaceCommand implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", paramLabel = "NAME")
            String name;

            @CommandLine.Option(names = {"--scope"}, paramLabel = "SCOPE",
                    description = "Override the OAuth scope string; default comes from server metadata.")
            String scope;

            @CommandLine.Option(names = "--manual",
                    description = "Print the authorization URL and paste back the code instead of starting a local callback server.")
            boolean manual;

            @Override
            public Integer call() {
                try {
                    McpConfigStore store = store();
                    var server = requireHttpServer(store, name);
                    if (Boolean.parseBoolean(System.getenv().getOrDefault(
                            "KOMPILE_MCP_AUTH_TEST", "false"))) {
                        // Test hook: metadata exchange only; no interactive flow.
                        System.out.println("OAuth metadata discovery OK for '" + name + "'.");
                        return 0;
                    }
                    new McpServerAuthManager(CredentialStore.create()).login(
                            name, McpConfigStore.endpointOf(server.config()), scope, manual,
                            System.out);
                    System.out.println("OAuth login stored for MCP server '" + name + "'.");
                    System.out.println("Restart active chat sessions to pick up the token.");
                    return 0;
                } catch (Exception e) {
                    System.err.println("MCP OAuth login failed: " + e.getMessage());
                    return 1;
                }
            }
        }

        @CommandLine.Command(name = "status", mixinStandardHelpOptions = true,
                description = "Show whether a custom MCP server has a stored OAuth token.")
        public static final class Status extends WorkspaceCommand implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", paramLabel = "NAME")
            String name;

            @Override
            public Integer call() {
                try {
                    requireHttpServer(store(), name);
                    McpServerAuthManager manager = manager();
                    boolean present = manager.hasCredential(name);
                    boolean valid = manager.hasValidCredential(name);
                    System.out.printf("MCP server '%s': %s%n", name,
                            !present ? "not logged in"
                                    : valid ? "token stored and valid"
                                    : "token stored (expired or refresh pending)");
                    return present ? 0 : 1;
                } catch (Exception e) {
                    System.err.println("MCP OAuth status failed: " + e.getMessage());
                    return 1;
                }
            }
        }

        @CommandLine.Command(name = "logout", mixinStandardHelpOptions = true,
                description = "Remove the stored OAuth token for a custom MCP server.")
        public static final class Logout extends WorkspaceCommand implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", paramLabel = "NAME")
            String name;

            @Override
            public Integer call() {
                try {
                    requireHttpServer(store(), name);
                    boolean removed = manager().logout(name);
                    if (!removed) {
                        System.err.println("No stored OAuth token for MCP server '" + name + "'.");
                        return 1;
                    }
                    System.out.println("Removed stored OAuth token for MCP server '" + name + "'.");
                    return 0;
                } catch (Exception e) {
                    System.err.println("MCP OAuth logout failed: " + e.getMessage());
                    return 1;
                }
            }
        }
    }

    private static McpConfigStore.Transport parseTransport(String value) {
        return switch (value == null ? "stdio" : value.trim().toLowerCase(Locale.ROOT)) {
            case "stdio", "local" -> McpConfigStore.Transport.STDIO;
            case "http", "streamable-http", "streamable_http", "remote" -> McpConfigStore.Transport.HTTP;
            case "sse" -> McpConfigStore.Transport.SSE;
            default -> throw new IllegalArgumentException(
                    "Unsupported MCP transport '" + value + "'. Use stdio, http, or sse.");
        };
    }

    private static Map<String, String> parsePairs(List<String> entries, String label) {
        Map<String, String> result = new LinkedHashMap<>();
        if (entries == null) return result;
        for (String entry : entries) {
            int separator = entry == null ? -1 : entry.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException(label + " entries must use NAME=VALUE: " + entry);
            }
            String name = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1);
            if (name.isBlank()) {
                throw new IllegalArgumentException(label + " name cannot be blank");
            }
            result.put(name, value);
        }
        return result;
    }

    private static void putMap(ObjectNode root, String name, Map<String, String> values) {
        if (values.isEmpty()) return;
        ObjectNode target = root.putObject(name);
        values.forEach(target::put);
    }

    private static void putStringArray(ObjectNode root, String name, List<String> values) {
        if (values == null || values.isEmpty()) return;
        ArrayNode target = root.putArray(name);
        values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().forEach(target::add);
    }

    private static void validateUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid MCP URL: " + value, e);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("MCP URL must use http or https: " + value);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    "MCP URL must not embed credentials; use --header-env, --bearer-token-env-var, or `kompile mcp auth login`");
        }
    }

    private static void warnLiteralSecrets(List<String> entries) {
        if (entries == null) return;
        for (String entry : entries) {
            if (entry == null) continue;
            int separator = entry.indexOf('=');
            if (separator <= 0) continue;
            String name = entry.substring(0, separator).toUpperCase(Locale.ROOT);
            String value = entry.substring(separator + 1);
            boolean sensitive = name.contains("TOKEN") || name.contains("SECRET")
                    || name.contains("PASSWORD") || name.contains("KEY")
                    || name.contains("AUTH") || name.contains("CREDENTIAL");
            boolean reference = value.startsWith("$")
                    || (value.startsWith("%") && value.endsWith("%"));
            if (sensitive && !reference) {
                System.err.println("Warning: " + name
                        + " appears to contain a literal secret. Prefer an environment reference.");
            }
        }
    }
}
