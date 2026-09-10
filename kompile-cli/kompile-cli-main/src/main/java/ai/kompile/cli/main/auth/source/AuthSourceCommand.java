/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.source;

import ai.kompile.channel.api.ChannelCredentialView;
import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.app.AppClientMixin;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import ai.kompile.cli.main.auth.channel.ChannelControlPlaneClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectCrawlBackend;
import ai.kompile.cli.main.project.LocalExternalSourceLoaderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** OAuth and bilateral source connection management for Notion, Obsidian, Git, and peers. */
@Command(name = "source", aliases = "sources", mixinStandardHelpOptions = true,
        description = "Manage authenticated source and note-sync integrations.",
        subcommands = {
                CommandLine.HelpCommand.class,
                AuthSourceCommand.Providers.class,
                AuthSourceCommand.WebLogin.class,
                AuthSourceCommand.OAuthStatus.class,
                AuthSourceCommand.OAuthSettings.class,
                AuthSourceCommand.OAuthSetup.class,
                AuthSourceCommand.ConfigureOAuth.class,
                AuthSourceCommand.ValidateOAuth.class,
                AuthSourceCommand.Login.class,
                AuthSourceCommand.OAuthHealth.class,
                AuthSourceCommand.Refresh.class,
                AuthSourceCommand.DisconnectOAuth.class,
                AuthSourceCommand.ResetOAuth.class,
                AuthSourceCommand.Ingest.class,
                AuthSourceCommand.Connect.class,
                AuthSourceCommand.ListConnections.class,
                AuthSourceCommand.Status.class,
                AuthSourceCommand.Test.class,
                AuthSourceCommand.Enable.class,
                AuthSourceCommand.Disable.class,
                AuthSourceCommand.Pull.class,
                AuthSourceCommand.Sync.class,
                AuthSourceCommand.Delete.class
        })
public final class AuthSourceCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    abstract static class Base implements Callable<Integer> {
        @CommandLine.Mixin AppClientMixin app = new AppClientMixin();

        final Integer withClient(Action action) {
            KompileHttpClient http = app.requireClient();
            if (http == null) return 1;
            try {
                return action.run(new SourceControlPlaneClient(http));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 130;
            } catch (Exception error) {
                System.err.println("Source integration command failed: "
                        + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                return 1;
            }
        }

        final void printJson(JsonNode value) throws Exception {
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        }

        @FunctionalInterface interface Action {
            Integer run(SourceControlPlaneClient client) throws Exception;
        }
    }

    @Command(name = "providers", description = "List OAuth and bilateral sync providers.",
            mixinStandardHelpOptions = true)
    static final class Providers extends Base {
        @Override public Integer call() {
            return withClient(client -> {
                JsonNode oauth = client.oauthProviders();
                JsonNode ingest = client.sourceTypes();
                if (app.isJsonOutput()) {
                    var root = MAPPER.createObjectNode();
                    root.set("oauth", oauth);
                    root.set("ingest", ingest);
                    root.putArray("sync").add("NOTION").add("OBSIDIAN")
                            .add("LOCAL_FOLDER").add("GIT_REPOSITORY");
                    printJson(root);
                } else {
                    System.out.println("OAuth providers:");
                    for (JsonNode provider : oauth) {
                        System.out.printf("  %-14s %-24s %s%n",
                                provider.path("providerId").asText(),
                                provider.path("displayName").asText(),
                                provider.path("configured").asBoolean() ? "configured" : "setup required");
                    }
                    System.out.println("Installed ingestion sources:");
                    for (JsonNode source : ingest) {
                        System.out.printf("  %-18s %-24s %s%n",
                                source.path("type").asText().toLowerCase(Locale.ROOT),
                                source.path("displayName").asText(),
                                source.path("available").asBoolean() ? "available" : "not installed");
                    }
                    System.out.println("Bilateral sync: notion, obsidian, local-folder, git-repository");
                }
                return 0;
            });
        }
    }

    @Command(name = "web-login", description = "Mint a one-time source-management browser code.",
            mixinStandardHelpOptions = true)
    static final class WebLogin extends Base {
        @Override public Integer call() {
            return withClient(client -> {
                JsonNode login = client.browserLogin();
                if (app.isJsonOutput()) printJson(login);
                else {
                    System.out.println("One-time source web login code (expires "
                            + login.path("expiresAt").asText() + "):");
                    System.out.println(login.path("code").asText());
                }
                return 0;
            });
        }
    }

    @Command(name = "oauth-status", description = "Show OAuth connection status.",
            mixinStandardHelpOptions = true)
    static final class OAuthStatus extends Base {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER") String provider;
        @Override public Integer call() {
            return withClient(client -> {
                JsonNode result = provider == null
                        ? client.oauthConnections() : client.oauthStatus(provider);
                printJson(result);
                return 0;
            });
        }
    }

    @Command(name = "oauth-settings", description = "Show masked OAuth application settings.",
            mixinStandardHelpOptions = true)
    static final class OAuthSettings extends Base {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER") String provider;
        @Override public Integer call() {
            return withClient(client -> {
                printJson(client.oauthSettings(provider));
                return 0;
            });
        }
    }

    @Command(name = "oauth-setup", description = "Show provider OAuth application setup guidance.",
            mixinStandardHelpOptions = true)
    static final class OAuthSetup extends Base {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER") String provider;
        @Override public Integer call() {
            return withClient(client -> {
                JsonNode setup = client.oauthSetupInfo();
                if (provider == null) {
                    printJson(setup);
                    return 0;
                }
                for (JsonNode item : setup) {
                    if (provider.equalsIgnoreCase(item.path("providerId").asText())) {
                        printJson(item);
                        return 0;
                    }
                }
                throw new IllegalArgumentException(
                        "No OAuth setup guidance is available for provider: " + provider);
            });
        }
    }

    @Command(name = "configure-oauth", description = "Save encrypted OAuth application settings.",
            mixinStandardHelpOptions = true)
    static final class ConfigureOAuth extends ProviderAction {
        @Option(names = "--client-id", required = true) String clientId;
        @Option(names = "--client-secret-from-env", paramLabel = "ENV") String secretEnvironment;
        @Option(names = "--client-secret-file", paramLabel = "PATH") Path secretFile;
        @Option(names = "--client-secret-stdin") boolean secretStdin;
        @Option(names = "--scopes") String scopes;
        @Option(names = "--tenant-id") String tenantId;

        @Override public Integer call() {
            return withClient(client -> {
                String secret = readSecret(secretEnvironment, secretFile, secretStdin);
                if (secret == null) {
                    throw new IllegalArgumentException("A client secret input source is required");
                }
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put("providerId", provider.toLowerCase(Locale.ROOT));
                settings.put("clientId", clientId.trim());
                settings.put("clientSecret", secret);
                if (scopes != null && !scopes.isBlank()) settings.put("scopes", scopes.trim());
                if (tenantId != null && !tenantId.isBlank()) settings.put("tenantId", tenantId.trim());
                printJson(client.saveOAuthSettings(provider, settings));
                return 0;
            });
        }
    }

    @Command(name = "validate-oauth", description = "Validate OAuth application configuration.",
            mixinStandardHelpOptions = true)
    static final class ValidateOAuth extends ProviderAction {
        @Override public Integer call() {
            return withClient(client -> {
                JsonNode result = client.validateOAuthSettings(provider);
                printJson(result);
                return result.path("configured").asBoolean() ? 0 : 1;
            });
        }
    }

    @Command(name = "login", description = "Start a source-provider OAuth login.",
            mixinStandardHelpOptions = true)
    static final class Login extends Base {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER",
                description = "Optional provider id; omit to pick from installed providers.")
        String provider;
        @Override public Integer call() {
            return withClient(client -> {
                String selected = provider == null || provider.isBlank()
                        ? chooseProvider(client)
                        : provider;
                if (selected == null) {
                    return 1;
                }
                JsonNode response = client.authorize(selected);
                String url = response.path("authorizationUrl").asText();
                if (app.isJsonOutput()) printJson(response);
                else {
                    System.out.println("Open this authorization URL:");
                    System.out.println(url);
                    System.out.println("After approval, check with `kompile auth source oauth-status "
                            + selected + "`.");
                }
                return url.isBlank() ? 1 : 0;
            });
        }
    }

    /** Numbered picker over installed OAuth providers; mirrors the channel login UX. */
    private static String chooseProvider(SourceControlPlaneClient client) throws Exception {
        JsonNode providers = client.oauthProviders();
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode node : providers) {
            usable.add(node);
        }
        if (usable.isEmpty()) {
            System.out.println("No source providers are installed on the server.");
            return null;
        }
        System.out.println();
        System.out.println("Select a source provider:");
        System.out.println();
        for (int i = 0; i < usable.size(); i++) {
            JsonNode node = usable.get(i);
            System.out.printf("  %2d  %s%n", i + 1,
                    node.path("displayName").asText(node.path("id").asText("?")));
        }
        System.out.println();
        Console console = System.console();
        if (console == null) {
            System.err.println("PROVIDER is required in non-interactive mode.");
            return null;
        }
        while (true) {
            String input = console.readLine("  Choice (1-" + usable.size() + "): ");
            if (input == null) {
                return null;
            }
            String trimmed = input.trim();
            try {
                int n = Integer.parseInt(trimmed);
                if (n >= 1 && n <= usable.size()) {
                    return usable.get(n - 1).path("id").asText();
                }
            } catch (NumberFormatException ignored) {
                // fall through to name matching
            }
            for (JsonNode node : usable) {
                String id = node.path("id").asText();
                String display = node.path("displayName").asText(id);
                if (id.equalsIgnoreCase(trimmed) || display.toLowerCase(Locale.ROOT)
                        .contains(trimmed.toLowerCase(Locale.ROOT))) {
                    return id;
                }
            }
            System.out.println("  Enter 1-" + usable.size() + " or part of a provider name.");
        }
    }

    abstract static class ProviderAction extends Base {
        @Parameters(index = "0", paramLabel = "PROVIDER") String provider;
    }

    @Command(name = "oauth-health", description = "Check the live OAuth connection health.",
            mixinStandardHelpOptions = true)
    static final class OAuthHealth extends ProviderAction {
        @Override public Integer call() {
            return withClient(client -> {
                JsonNode result = client.oauthHealth(provider);
                printJson(result);
                return result.path("healthy").asBoolean() ? 0 : 1;
            });
        }
    }

    @Command(name = "refresh", description = "Refresh a source OAuth token.", mixinStandardHelpOptions = true)
    static final class Refresh extends ProviderAction {
        @Override public Integer call() { return withClient(c -> { printJson(c.refresh(provider)); return 0; }); }
    }

    @Command(name = "disconnect-oauth", description = "Revoke a source OAuth connection.",
            mixinStandardHelpOptions = true)
    static final class DisconnectOAuth extends ProviderAction {
        @Override public Integer call() { return withClient(c -> { printJson(c.disconnectOAuth(provider)); return 0; }); }
    }

    @Command(name = "reset-oauth", description = "Delete OAuth application settings and encrypted secret.",
            mixinStandardHelpOptions = true)
    static final class ResetOAuth extends ProviderAction {
        @Option(names = "--yes", description = "Confirm destructive OAuth settings reset.") boolean yes;

        @Override public Integer call() {
            if (!yes) {
                System.err.println("Refusing to reset OAuth settings without --yes.");
                return 2;
            }
            return withClient(client -> {
                printJson(client.deleteOAuthSettings(provider));
                return 0;
            });
        }
    }

    @Command(name = "ingest",
            description = "Start an authenticated one-off crawl for any installed source type.",
            mixinStandardHelpOptions = true)
    static final class Ingest extends Base {
        @Parameters(index = "0", paramLabel = "SOURCE_TYPE",
                description = "For example jira, reddit, notion, discord, slack, confluence, gmail, gdrive, onedrive, or obsidian.")
        String sourceType;
        @Option(names = {"--path", "--source-url"}, paramLabel = "PATH_OR_URL",
                description = "Source locator. Required when the source type needs one for its "
                        + "identity (folder/file/drive types, reddit subreddit, jira site, ...); "
                        + "omitted for account-wide types (gmail/workspace/imap ingest the mailbox "
                        + "directly) or when identity comes from --set metadata (discord guildId, "
                        + "drive fileIds, notion pageIds); slack itself still needs the channel.")
        String pathOrUrl;
        @Option(names = "--dry-run",
                description = "Validate and preview the local crawl without persisting anything.")
        boolean dryRun;
        @Option(names = "--fact-sheet-id",
                description = "Target Fact Sheet ID. Required for crawl-manager ingestion; omit "
                        + "with --local to use the folder's own knowledge base (<project>-knowledge).")
        Long factSheetId;
        @Option(names = "--label") String label;
        @Option(names = "--name") String name;
        @Option(names = "--max-depth", defaultValue = "0") int maxDepth;
        @Option(names = "--max-documents", defaultValue = "0") int maxDocuments;
        @Option(names = "--local",
                description = "Force the folder-local crawl runtime even when --url/--port pins a "
                        + "server. Without a pinned server this is already the default.")
        boolean local;
        @Option(names = "--from-channel-connection", paramLabel = "NAME",
                description = "Resolve missing source credentials from a named channel connection "
                        + "(slack, discord, or email provider; the admin control plane must be reachable).")
        String fromChannelConnection;
        @Option(names = {"--skip-final-learning", "--skip-reasoning-learning"},
                description = "Skip the optional final KGE and FOL/PSL/MEBN learning pass for this crawl.")
        boolean skipFinalLearning;
        @Option(names = "--set", paramLabel = "KEY=VALUE",
                description = "Non-secret source property; repeatable.")
        Map<String, String> properties = new LinkedHashMap<>();
        @Option(names = "--secret-from-env", paramLabel = "FIELD=ENV",
                description = "Read a source secret from an environment variable; repeatable.")
        Map<String, String> secretEnvironment = new LinkedHashMap<>();
        @Option(names = "--secret-file", paramLabel = "FIELD=PATH",
                description = "Read a source secret from a restricted file; repeatable.")
        Map<String, Path> secretFiles = new LinkedHashMap<>();
        @Option(names = "--secret-stdin", paramLabel = "FIELD",
                description = "Read one source-secret line from stdin; repeatable.")
        List<String> secretStdin = new ArrayList<>();

        /**
         * Runtime credential bridge: resolves secrets plus non-secret connection settings from a
         * named channel connection (routed control plane) and maps them onto the loader contract
         * of the requested source type. Explicit --set / --secret-* values win because they are
         * applied afterwards.
         */
        private Map<String, Object> bridgeChannelCredentials(String type) {
            if (fromChannelConnection == null || fromChannelConnection.isBlank()) {
                return Map.of();
            }
            String name = fromChannelConnection.trim();
            try {
                ChannelControlPlaneClient channels = new ChannelControlPlaneClient("");
                return mappedChannelCredentials(channels.credential(name), type);
            } catch (Exception error) {
                throw new IllegalArgumentException("Could not resolve credentials from channel connection '"
                        + name + "': "
                        + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                        error);
            }
        }

        /**
         * OAuth fill-in for loaders whose credential contract is metadata.accessToken (GMAIL,
         * GDOCS, GDRIVE, GOOGLE_WORKSPACE, ONEDRIVE, plus EMAIL/IMAP/POP3 XOAUTH2). Prefers the
         * local CLI credential store (~/.kompile/auth.json; refreshes when near expiry, no app
         * needed) and falls back to the app control plane for tokens connected there; explicit
         * --secret-* input still wins because it is applied afterwards.
         */
        private void fillOAuthAccessToken(String type, Map<String, Object> bridged) {
            String provider = oauthProviderFor(type);
            if (provider == null) {
                return;
            }
            // EMAIL/IMAP/POP3 use the token only with an explicit XOAUTH2 authMode.
            if (type.equals("EMAIL") || type.equals("IMAP") || type.equals("POP3")) {
                Object authMode = properties.get("authMode");
                if (authMode == null || !authMode.toString().startsWith("OAUTH2")) {
                    return;
                }
            }
            try {
                OAuthCredentialManager manager = new OAuthCredentialManager(
                        CredentialStore.create(), new OAuthProviderRegistry());
                OAuthProviderFlow.RequestAuth auth = manager.resolve(provider);
                if (auth != null) {
                    bridged.put("accessToken", auth.token());
                    ai.kompile.cli.common.auth.ManagedCredential credential =
                            CredentialStore.create().read(provider);
                    for (String key : oauthMetadataKeysFor(type)) {
                        Object value = credential == null ? null : credential.getMetadata(key);
                        if (value instanceof String text && !text.isBlank()) {
                            bridged.putIfAbsent(key, text);
                        }
                    }
                    return;
                }
            } catch (Exception ignoredLocal) {
                // No locally stored credential (or refresh failed); fall through to the app.
            }
            try {
                SourceControlPlaneClient sources = new SourceControlPlaneClient(
                        KompileHttpClient.routed());
                JsonNode credential = sources.oauthCredential(provider);
                if (credential.path("hasToken").asBoolean(false)) {
                    bridged.put("accessToken", credential.path("accessToken").asText());
                }
                // hasToken=false: leave credentials empty so the loader's own
                // "requires a connected Google OAuth account" error surfaces verbatim.
            } catch (Exception ignored) {
                // Control plane unreachable or not authenticated: local crawls still work when
                // the user supplies explicit secrets; connectivity errors must not turn into
                // spurious bridge failures for types that may not need OAuth at all.
            }
        }

        @Override public Integer call() {
            String type = sourceType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            String resolvedLocator = pathOrUrl == null ? "" : pathOrUrl.trim();
            // Locator identity is the loader's own contract, not a CLI table: the local
            // registry mirrors each loader's validation (account-wide types, or identity
            // metadata such as guildId/fileIds/pageIds), and the loader's own precise error
            // surfaces for anything genuinely locator-required (reddit subreddit, drive
            // folder, jira site, ...). No per-provider defaults are invented here.
            if (resolvedLocator.isEmpty()
                    && !LocalExternalSourceLoaderRegistry.identityWithoutLocator(
                            type, new LinkedHashMap<>(properties))) {
                System.err.println("--path is required for source type " + type + " "
                        + "(a folder, file, or item locator is part of its source identity). "
                        + "Identity can also come from --set metadata (e.g. guildId, fileIds, pageIds).");
                return 2;
            }
            final String locator = resolvedLocator;
            // Routing mirrors the crawl tooling contract: no pinned kompile URL means the
            // folder-local runtime and its directory-derived knowledge base. --local only
            // forces local when a server IS pinned; a fact sheet is required only in real
            // server mode.
            boolean serverMode = !local && app.hasPinnedTarget();
            if (serverMode && (factSheetId == null || factSheetId <= 0)) {
                System.err.println("--fact-sheet-id is required for crawl-manager ingestion "
                        + "(omit --url/--port or pass --local to write to the folder's own "
                        + "knowledge base).");
                return 2;
            }
            Map<String, Object> bridged = bridgeChannelCredentials(type);
            if (!bridged.containsKey("accessToken")) {
                fillOAuthAccessToken(type, bridged);
            }
            if (!serverMode) {
                try {
                    return ingestLocally(type, locator, bridged);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return 130;
                } catch (Exception error) {
                    System.err.println("Local source ingestion failed: "
                            + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                    return 1;
                }
            }
            return withClient(client -> {
                if (factSheetId == null || factSheetId <= 0) {
                    throw new IllegalArgumentException("--fact-sheet-id must be a positive ID");
                }
                JsonNode targetSheet = client.factSheet(factSheetId);
                if (targetSheet.path("id").asLong(-1L) != factSheetId) {
                    throw new IllegalArgumentException(
                            "Fact Sheet " + factSheetId + " does not exist on the selected Kompile project");
                }
                JsonNode descriptor = findSourceDescriptor(client.sourceTypes(), type);
                if (!descriptor.path("available").asBoolean(false)) {
                    throw new IllegalStateException("Source runtime is not installed: " + type);
                }
                Map<String, Object> runtimeProperties = new LinkedHashMap<>(bridged);
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    String key = entry.getKey();
                    if (isSensitiveProperty(key)) {
                        throw new IllegalArgumentException("Secret property " + key
                                + " must use --secret-from-env, --secret-file, or --secret-stdin");
                    }
                    runtimeProperties.put(key, parsePropertyValue(entry.getValue()));
                }
                runtimeProperties.putAll(readSourceSecrets(
                        type, secretEnvironment, secretFiles, secretStdin));
                validateRequiredProperties(descriptor, runtimeProperties);

                Map<String, Object> source = new LinkedHashMap<>();
                source.put("label", label == null || label.isBlank()
                        ? type.toLowerCase(Locale.ROOT) : label.trim());
                source.put("sourceType", type);
                source.put("pathOrUrl", locator);
                source.put("maxDepth", maxDepth);
                source.put("maxDocuments", maxDocuments);
                source.put("properties", runtimeProperties);
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("name", name == null || name.isBlank()
                        ? "Ingest " + source.get("label") : name.trim());
                request.put("factSheetId", factSheetId);
                request.put("sources", List.of(source));
                if (skipFinalLearning) {
                    request.put("runtimeConfig", Map.of(
                            "runReasoningLearning", false,
                            "trainEmbeddingsAfterEnrichment", false));
                }
                printJson(client.startSourceCrawl(request));
                return 0;
            });
        }

        private Integer ingestLocally(String type, String locator, Map<String, Object> bridged) throws Exception {
            if (factSheetId != null && factSheetId <= 0) {
                throw new IllegalArgumentException("--fact-sheet-id must be a positive ID");
            }
            if (!LocalExternalSourceLoaderRegistry.supports(type) && !"OBSIDIAN".equals(type)) {
                throw new IllegalArgumentException("Source type " + type
                        + " is not available in the folder-local source runtime");
            }
            Map<String, Object> runtimeProperties = new LinkedHashMap<>(bridged);
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (isSensitiveProperty(entry.getKey())) {
                    throw new IllegalArgumentException("Secret property " + entry.getKey()
                            + " must use --secret-from-env, --secret-file, or --secret-stdin");
                }
                runtimeProperties.put(entry.getKey(), parsePropertyValue(entry.getValue()));
            }
            runtimeProperties.putAll(readSourceSecrets(
                    type, secretEnvironment, secretFiles, secretStdin));

            ObjectNode request = MAPPER.createObjectNode()
                    .put("async", false)
                    .put("dryRun", dryRun);
            if (skipFinalLearning) {
                request.putObject("runtimeConfig")
                        .put("runReasoningLearning", false)
                        .put("trainEmbeddingsAfterEnrichment", false);
            }
            request.put("name", name == null || name.isBlank()
                    ? "Ingest " + (label == null || label.isBlank() ? type.toLowerCase(Locale.ROOT) : label.trim())
                    : name.trim());
            if (factSheetId != null) {
                // Explicit target wins; omission lets the folder-local backend use its
                // directory-derived default knowledge base (<project-slug>-knowledge).
                request.putObject("knowledgeBase").put("id", factSheetId);
            }
            ObjectNode source = request.putArray("documents").addObject();
            source.put("path", locator);
            source.put("label", label == null || label.isBlank() ? type.toLowerCase(Locale.ROOT) : label.trim());
            source.put("sourceType", type);
            source.put("maxDepth", maxDepth);
            source.put("maxDocuments", maxDocuments);
            source.set("properties", MAPPER.valueToTree(runtimeProperties));

            ToolContext context = new ToolContext(
                    "source-ingest-local", null, new PermissionService(),
                    Path.of(System.getProperty("user.dir")), null);
            ToolResult result = new LocalProjectCrawlBackend(MAPPER).crawlDocuments(request, context);
            if (app.isJsonOutput()) {
                var response = MAPPER.createObjectNode();
                response.put("title", result.getTitle());
                response.put("output", result.getOutput());
                response.put("error", result.isError());
                response.set("metadata", MAPPER.valueToTree(result.getMetadata()));
                printJson(response);
            } else {
                System.out.println(result.getOutput());
            }
            return result.isError() ? 1 : 0;
        }
    }

    @Command(name = "connect", description = "Create a Notion, Obsidian, folder, or Git sync connection.",
            mixinStandardHelpOptions = true)
    static final class Connect extends Base {
        @Parameters(index = "0", paramLabel = "PROVIDER",
                description = "notion, obsidian, local-folder, or git-repository") String provider;
        @Option(names = "--fact-sheet-id", required = true) long factSheetId;
        @Option(names = "--scope", required = true,
                description = "Notion page/database id or local working directory/vault path.") String scope;
        @Option(names = "--direction", defaultValue = "BIDIRECTIONAL") String direction;
        @Option(names = "--poll-cron") String pollCron;
        @Option(names = "--api-url", description = "Obsidian Local REST API base URL.") String apiUrl;
        @Option(names = "--repository-url", description = "Git remote URL.") String repositoryUrl;
        @Option(names = "--branch", defaultValue = "main") String branch;
        @Option(names = "--git-username", defaultValue = "x-access-token") String gitUsername;
        @Option(names = "--no-auto-commit") boolean noAutoCommit;
        @Option(names = "--no-remote-sync") boolean noRemoteSync;
        @Option(names = "--token-from-env", paramLabel = "ENV") String tokenEnvironment;
        @Option(names = "--token-file", paramLabel = "PATH") Path tokenFile;
        @Option(names = "--token-stdin") boolean tokenStdin;
        @Option(names = "--enable",
                description = "Test authentication and enable the connection only when the test succeeds.")
        boolean enable;

        @Override public Integer call() {
            return withClient(client -> {
                String syncProvider = syncProvider(provider);
                String token = readSecret(tokenEnvironment, tokenFile, tokenStdin);
                Map<String, Object> request = new LinkedHashMap<>();
                request.put("factSheetId", factSheetId);
                request.put("provider", syncProvider);
                request.put("externalScope", scope.trim());
                request.put("direction", direction.toUpperCase(Locale.ROOT));
                if (pollCron != null && !pollCron.isBlank()) request.put("pollCron", pollCron.trim());
                if ("OBSIDIAN".equals(syncProvider) && apiUrl != null && !apiUrl.isBlank()) {
                    request.put("obsidianApiUrl", apiUrl.trim());
                    request.put("authMode", "OBSIDIAN_REST_TOKEN");
                    if (token != null) request.put("obsidianToken", token);
                }
                if ("GIT_REPOSITORY".equals(syncProvider)) {
                    if (repositoryUrl != null && !repositoryUrl.isBlank()) {
                        request.put("repositoryUrl", repositoryUrl.trim());
                        request.put("gitBranch", branch);
                        request.put("remoteSyncEnabled", !noRemoteSync);
                        if (token != null) {
                            request.put("authMode", "HTTPS_TOKEN");
                            request.put("gitUsername", gitUsername);
                            request.put("gitToken", token);
                        } else {
                            request.put("authMode", "SYSTEM_GIT");
                        }
                    }
                    request.put("autoCommit", !noAutoCommit);
                }
                JsonNode created = client.createSync(request);
                if (enable) {
                    long id = created.path("id").asLong();
                    JsonNode test = client.testSync(id);
                    if (!test.path("success").asBoolean(false)) {
                        throw new IllegalStateException("Source authentication test failed; connection "
                                + id + " remains disabled: "
                                + test.path("message").asText("no provider diagnostic"));
                    }
                    created = client.enableSync(id);
                }
                printJson(created);
                return 0;
            });
        }
    }

    @Command(name = "list", description = "List source sync connections for a Fact Sheet.",
            mixinStandardHelpOptions = true)
    static final class ListConnections extends Base {
        @Option(names = "--fact-sheet-id", required = true) long factSheetId;
        @Override public Integer call() {
            return withClient(c -> { printJson(c.syncConnections(factSheetId)); return 0; });
        }
    }

    abstract static class ConnectionAction extends Base {
        @Parameters(index = "0", paramLabel = "CONNECTION_ID") long id;
    }

    @Command(name = "status", description = "Show a source sync connection.", mixinStandardHelpOptions = true)
    static final class Status extends ConnectionAction {
        @Override public Integer call() { return withClient(c -> { printJson(c.syncConnection(id)); return 0; }); }
    }
    @Command(name = "test", description = "Test a source sync connection.", mixinStandardHelpOptions = true)
    static final class Test extends ConnectionAction {
        @Override public Integer call() { return withClient(c -> { printJson(c.testSync(id)); return 0; }); }
    }
    @Command(name = "enable", description = "Enable a source sync connection.", mixinStandardHelpOptions = true)
    static final class Enable extends ConnectionAction {
        @Override public Integer call() { return withClient(c -> { printJson(c.enableSync(id)); return 0; }); }
    }
    @Command(name = "disable", description = "Disable a source sync connection.", mixinStandardHelpOptions = true)
    static final class Disable extends ConnectionAction {
        @Override public Integer call() { return withClient(c -> { printJson(c.disableSync(id)); return 0; }); }
    }
    @Command(name = "pull", description = "Pull external changes into Kompile.", mixinStandardHelpOptions = true)
    static final class Pull extends ConnectionAction {
        @Override public Integer call() { return withClient(c -> { printJson(c.pullSync(id)); return 0; }); }
    }
    @Command(name = "sync", description = "Run the configured full/bidirectional sync.", mixinStandardHelpOptions = true)
    static final class Sync extends ConnectionAction {
        @Override public Integer call() { return withClient(c -> { printJson(c.triggerSync(id)); return 0; }); }
    }

    @Command(name = "delete", description = "Delete a source sync connection.", mixinStandardHelpOptions = true)
    static final class Delete extends ConnectionAction {
        @Option(names = {"--yes", "-y"}) boolean yes;
        @Override public Integer call() {
            if (!yes) {
                Console console = System.console();
                if (console == null || !"yes".equalsIgnoreCase(
                        console.readLine("Delete source connection %d? Type yes: ", id))) return 2;
            }
            return withClient(c -> { c.deleteSync(id); System.out.println("Deleted source connection " + id); return 0; });
        }
    }

    /**
     * Runtime credential bridge: resolves secrets plus non-secret connection settings from a named
     * channel connection and maps them onto the loader contract of the requested source type.
     * Explicit --set / --secret-* values always win because they are applied afterwards.
     */
    static Map<String, Object> mappedChannelCredentials(ChannelCredentialView credential, String type) {
        String provider = credential.providerId();
        Map<String, String> secrets = credential.secrets();
        Map<String, Object> settings = credential.properties();
        Map<String, Object> mapped = new LinkedHashMap<>();
        switch (provider) {
            case "slack" -> {
                requireSupported(type, "SLACK", "SLACK_HISTORY");
                copyIfPresent(secrets, "botToken", mapped, "slackToken");
            }
            case "discord" -> {
                requireSupported(type, "DISCORD", "DISCORD_HISTORY");
                copyIfPresent(secrets, "botToken", mapped, "botToken");
            }
            case "email" -> {
                requireSupported(type, "EMAIL", "IMAP", "POP3");
                copyIfPresent(secrets, "password", mapped, "password");
                copyIfPresent(settings, "username", mapped, "username");
                copyIfPresent(settings, "imapHost", mapped, "host");
                copyIfPresent(settings, "imapPort", mapped, "port");
                if (settings.containsKey("smtpHost") && !mapped.containsKey("host")) {
                    copyIfPresent(settings, "smtpHost", mapped, "host");
                }
            }
            default -> throw new IllegalArgumentException(
                    "Channel provider '" + provider
                            + "' has no crawl credential mapping (supported: slack, discord, email)");
        }
        if (mapped.isEmpty()) {
            throw new IllegalArgumentException("Channel connection has no runtime credential for " + type);
        }
        return Map.copyOf(mapped);
    }

    private static void requireSupported(String type, String... supported) {
        for (String candidate : supported) {
            if (candidate.equals(type)) return;
        }
        throw new IllegalArgumentException("Source type " + type
                + " does not match the channel connection provider (expected one of "
                + String.join(", ", supported) + ")");
    }

    private static void copyIfPresent(
            Map<String, ?> source, String from, Map<String, Object> target, String to) {
        Object value = source.get(from);
        if (value instanceof String text) {
            if (!text.isBlank()) target.put(to, text.trim());
        } else if (value != null) {
            target.put(to, value);
        }
    }

    /**
     * Source types whose loaders resolve credentials from metadata.accessToken and fall back to
     * the connected OAuth account server-side. Maps each to its OAuth provider id so local
     * crawls can bridge the locally connected token. Gmail/M365 mail presets are also honored
     * by the IMAP loader via the same accessToken key. Jira/Confluence additionally copy the
     * Atlassian cloudId from credential metadata when present.
     */
    static String oauthProviderFor(String sourceType) {
        return switch (sourceType) {
            case "GMAIL", "GDOCS", "GDRIVE", "GOOGLE_WORKSPACE" -> "google";
            case "ONEDRIVE" -> "microsoft";
            case "EMAIL", "IMAP", "POP3" -> "google"; // XOAUTH2 for Gmail-hosted mailboxes
            case "NOTION" -> "notion";
            case "REDDIT" -> "reddit";
            case "JIRA", "CONFLUENCE" -> "atlassian";
            default -> null;
        };
    }

    /** Credential metadata keys copied into crawl properties alongside accessToken. */
    static java.util.List<String> oauthMetadataKeysFor(String sourceType) {
        return switch (sourceType) {
            case "JIRA", "CONFLUENCE" -> java.util.List.of("cloudId");
            default -> java.util.List.of();
        };
    }

    private static String syncProvider(String provider) {
        if (provider == null) throw new IllegalArgumentException("Source provider is required");
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "notion" -> "NOTION";
            case "obsidian" -> "OBSIDIAN";
            case "local-folder", "folder" -> "LOCAL_FOLDER";
            case "git", "git-repository" -> "GIT_REPOSITORY";
            default -> throw new IllegalArgumentException("Unsupported sync provider: " + provider);
        };
    }

    private static JsonNode findSourceDescriptor(JsonNode sourceTypes, String type) {
        if (sourceTypes != null) {
            for (JsonNode candidate : sourceTypes) {
                if (type.equalsIgnoreCase(candidate.path("type").asText())) return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown source type: " + type);
    }

    private static void validateRequiredProperties(
            JsonNode descriptor, Map<String, Object> properties) {
        for (JsonNode required : descriptor.path("requiredProperties")) {
            String name = required.asText();
            if ("pathOrUrl".equals(name)) continue;
            Object value = properties.get(name);
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException("Missing required source property: " + name);
            }
        }
    }

    private static Map<String, String> readSourceSecrets(
            String sourceType,
            Map<String, String> environment,
            Map<String, Path> files,
            List<String> stdinFields) throws Exception {
        Set<String> allowed = sourceSecretFields(sourceType);
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            requireAllowedSecret(allowed, entry.getKey());
            putSecret(result, entry.getKey(), System.getenv(entry.getValue()));
        }
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            requireAllowedSecret(allowed, entry.getKey());
            putSecret(result, entry.getKey(), Files.readString(entry.getValue(), StandardCharsets.UTF_8));
        }
        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String field : stdinFields) {
            requireAllowedSecret(allowed, field);
            putSecret(result, field, stdin.readLine());
        }
        return Map.copyOf(result);
    }

    static Set<String> sourceSecretFields(String sourceType) {
        return switch (sourceType) {
            case "DISCORD", "DISCORD_HISTORY" -> Set.of("botToken");
            case "SLACK", "SLACK_HISTORY" -> Set.of("slackToken");
            case "CONFLUENCE" -> Set.of("apiToken", "accessToken");
            case "JIRA" -> Set.of("apiToken", "accessToken");
            case "REDDIT" -> Set.of("accessToken");
            case "NOTION" -> Set.of("apiToken", "accessToken");
            case "EMAIL", "IMAP", "POP3" -> Set.of("password", "accessToken");
            case "SFTP", "SMB", "SQL" -> Set.of("password");
            case "GMAIL", "GDOCS", "GDRIVE", "GOOGLE_WORKSPACE", "ONEDRIVE" -> Set.of("accessToken");
            case "S3" -> Set.of("accessKey", "secretKey");
            default -> Set.of();
        };
    }

    private static void requireAllowedSecret(Set<String> allowed, String name) {
        if (!allowed.contains(name)) {
            throw new IllegalArgumentException("Unknown secret field for source type: " + name);
        }
    }

    private static void putSecret(Map<String, String> result, String name, String rawValue) {
        String value = rawValue == null ? null : rawValue.trim();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Source secret is blank or unavailable: " + name);
        }
        if (result.putIfAbsent(name, value) != null) {
            throw new IllegalArgumentException("Source secret supplied more than once: " + name);
        }
    }

    private static boolean isSensitiveProperty(String name) {
        String normalized = name == null ? ""
                : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.endsWith("password") || normalized.endsWith("token")
                || normalized.endsWith("secret") || normalized.equals("accesskey")
                || normalized.equals("secretkey") || normalized.equals("apikey");
    }

    static Object parsePropertyValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        boolean jsonScalar = value.equals("null")
                || value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
                || value.startsWith("[") || value.startsWith("{");
        if (!jsonScalar) return value;
        try {
            return MAPPER.convertValue(MAPPER.readTree(value), Object.class);
        } catch (Exception invalidJson) {
            throw new IllegalArgumentException("Invalid JSON property value: " + raw, invalidJson);
        }
    }

    private static String readSecret(String environment, Path file, boolean stdin) throws Exception {
        int sources = (environment == null ? 0 : 1) + (file == null ? 0 : 1) + (stdin ? 1 : 0);
        if (sources > 1) throw new IllegalArgumentException("Use only one token input source");
        String value = null;
        if (environment != null) value = System.getenv(environment);
        else if (file != null) value = Files.readString(file, StandardCharsets.UTF_8);
        else if (stdin) value = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (sources == 1 && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("Source integration token is blank or unavailable");
        }
        return value == null ? null : value.trim();
    }
}
