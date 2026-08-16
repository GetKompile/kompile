/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth;

import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.oauth.OAuthCredentialManager;
import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * Manage provider credentials used by the Kompile-managed CLI.
 */
@Command(name = "auth",
        mixinStandardHelpOptions = true,
        description = "Manage provider credentials for the Kompile CLI.",
        subcommands = {
                CommandLine.HelpCommand.class,
                AuthCommand.LoginCommand.class,
                AuthCommand.SwitchCommand.class,
                AuthCommand.LogoutCommand.class,
                AuthCommand.ListCommand.class
        })
public class AuthCommand implements Callable<Integer> {
    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @Command(name = "login", mixinStandardHelpOptions = true,
            description = "Store an API key or complete a provider OAuth login.")
    static class LoginCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER",
                description = "Provider id, for example openai or anthropic.")
        String providerId;

        @Option(names = {"--name", "--credential"}, paramLabel = "NAME",
                description = "Name for this credential (for example personal or work).")
        String credentialName;

        @Option(names = "--no-switch",
                description = "Store a named credential without making it active.")
        boolean noSwitch;

        @Option(names = "--stdin",
                description = "Read the API key from one line on standard input.")
        boolean stdin;

        @Option(names = "--from-env", paramLabel = "NAME",
                description = "Store a reference to an existing environment variable.")
        String environmentName;

        @Option(names = "--oauth",
                description = "Use the provider's subscription OAuth flow instead of an API key.")
        boolean oauth;

        @Option(names = "--method", paramLabel = "METHOD",
                description = "OAuth method: browser or device (provider-dependent).")
        String oauthMethod;

        @Option(names = "--manual",
                description = "Do not wait for a loopback callback; paste the final redirect URL/code.")
        boolean manual;

        @Option(names = "--enterprise", paramLabel = "DOMAIN",
                description = "GitHub Enterprise domain for the Copilot device flow.")
        String enterpriseDomain;

        @Option(names = "--gateway", paramLabel = "URL",
                description = "Radius gateway URL (defaults to the built-in Radius gateway).")
        String gateway;

        @Override
        public Integer call() throws Exception {
            if (noSwitch && (credentialName == null || credentialName.isBlank())) {
                System.err.println("--no-switch requires --name so an existing active credential is preserved.");
                return 2;
            }
            CredentialStore store = CredentialStore.create();
            OAuthProviderRegistry registry = new OAuthProviderRegistry();
            OAuthCredentialManager manager = new OAuthCredentialManager(store, registry);
            AuthWizard wizard = null;
            OAuthProviderFlow.Interaction interaction = new ConsoleOAuthInteraction();
            String storedValue = null;
            boolean activate = !noSwitch;
            boolean useOAuth;

            if (providerId == null) {
                if (stdin || environmentName != null || oauth || oauthMethod != null
                        || manual || enterpriseDomain != null || gateway != null
                        || credentialName != null || noSwitch) {
                    System.err.println("PROVIDER is required when login options are supplied.");
                    return 2;
                }
                try {
                    wizard = AuthWizard.open();
                    AuthWizard.LoginRequest request = wizard.promptForLogin(registry, store);
                    if (request == null) {
                        System.out.println("Login cancelled.");
                        return 0;
                    }
                    providerId = request.providerId();
                    credentialName = request.credentialName();
                    useOAuth = request.kind() == AuthWizard.LoginKind.OAUTH;
                    storedValue = request.storedValue();
                    oauthMethod = request.oauthMethod();
                    activate = request.activate();
                    interaction = wizard.oauthInteraction();
                } catch (IOException e) {
                    System.err.println("Could not start login wizard: " + e.getMessage());
                    return 1;
                }
            } else {
                useOAuth = oauth || registry.isOAuthOnly(providerId);
            }

            try {
                return useOAuth
                        ? loginOAuth(manager, store, interaction, activate)
                        : loginApiKey(store, storedValue, activate);
            } finally {
                if (wizard != null) {
                    wizard.close();
                }
            }
        }

        private Integer loginOAuth(
                OAuthCredentialManager manager,
                CredentialStore store,
                OAuthProviderFlow.Interaction interaction,
                boolean activate) {
            if (stdin || environmentName != null) {
                System.err.println("--stdin and --from-env apply only to API-key login.");
                return 2;
            }
            try {
                OAuthProviderFlow.LoginOptions options = new OAuthProviderFlow.LoginOptions(
                        oauthMethod,
                        manual,
                        enterpriseDomain,
                        gateway);
                ManagedCredential credential = manager.login(
                        providerId,
                        credentialName,
                        activate,
                        options,
                        interaction);
                String savedName = store.activeCredentialName(providerId);
                if (credentialName != null && !credentialName.isBlank()) {
                    savedName = credentialName.trim().toLowerCase(java.util.Locale.ROOT);
                }
                boolean active = savedName.equals(store.activeCredentialName(providerId));
                System.out.println("Saved " + credential.getType() + " credential for "
                        + providerId + " as '" + savedName + "'"
                        + (active ? " (active)" : "") + " to " + store.getAuthPath());
                return 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("OAuth login was interrupted.");
                return 130;
            } catch (Exception e) {
                System.err.println("OAuth login failed: " + e.getMessage());
                return 1;
            }
        }

        private Integer loginApiKey(
                CredentialStore store,
                String wizardStoredValue,
                boolean activate) throws Exception {
            if (oauthMethod != null || manual || enterpriseDomain != null || gateway != null) {
                System.err.println("OAuth options require --oauth for provider " + providerId + ".");
                return 2;
            }
            if (stdin && environmentName != null) {
                System.err.println("Use only one of --stdin or --from-env.");
                return 2;
            }

            String storedValue = wizardStoredValue;
            if (storedValue != null && storedValue.startsWith("$")
                    && storedValue.length() > 1) {
                String wizardEnvironmentName = storedValue.substring(1);
                String current = System.getenv(wizardEnvironmentName);
                if (current == null || current.isBlank()) {
                    System.err.println("Environment variable is not set: " + wizardEnvironmentName);
                    return 1;
                }
            } else if (storedValue != null) {
                // The wizard already collected a masked API-key entry.
            } else if (environmentName != null) {
                if (!environmentName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    System.err.println("Invalid environment variable name: " + environmentName);
                    return 2;
                }
                String current = System.getenv(environmentName);
                if (current == null || current.isBlank()) {
                    System.err.println("Environment variable is not set: " + environmentName);
                    return 1;
                }
                storedValue = "$" + environmentName;
            } else if (stdin) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(System.in, StandardCharsets.UTF_8));
                storedValue = reader.readLine();
            } else {
                Console console = System.console();
                if (console == null) {
                    System.err.println("No interactive console. Use --stdin or --from-env.");
                    return 1;
                }
                char[] secret = console.readPassword("API key for %s: ", providerId);
                storedValue = secret == null ? null : new String(secret);
                if (secret != null) {
                    java.util.Arrays.fill(secret, '\0');
                }
            }

            if (storedValue == null || storedValue.isBlank()) {
                System.err.println("API key must not be blank.");
                return 1;
            }

            if (credentialName == null || credentialName.isBlank()) {
                store.putApiKey(providerId, storedValue);
            } else {
                store.putApiKey(providerId, credentialName, storedValue, activate);
            }
            String savedName = credentialName == null || credentialName.isBlank()
                    ? store.activeCredentialName(providerId)
                    : credentialName.trim().toLowerCase(java.util.Locale.ROOT);
            boolean active = savedName.equals(store.activeCredentialName(providerId));
            System.out.println("Saved API key for " + providerId + " as '" + savedName + "'"
                    + (active ? " (active)" : "") + " to " + store.getAuthPath());
            return 0;
        }
    }

    private static final class ConsoleOAuthInteraction implements OAuthProviderFlow.Interaction {
        private final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        @Override
        public void info(String message) {
            System.out.println(message);
        }

        @Override
        public void authorizationUrl(URI url, String instructions) {
            System.out.println(instructions);
            System.out.println(url);
        }

        @Override
        public void deviceCode(
                String userCode,
                URI verificationUri,
                Integer intervalSeconds,
                Integer expiresInSeconds) {
            System.out.println("Open " + verificationUri);
            System.out.println("Enter device code: " + userCode);
        }

        @Override
        public String prompt(String message) throws java.io.IOException {
            System.out.print(message + " ");
            System.out.flush();
            return input.readLine();
        }
    }

    @Command(name = "switch", aliases = "use", mixinStandardHelpOptions = true,
            description = "Select the active named credential for a provider.")
    static class SwitchCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER")
        String providerId;

        @Parameters(index = "1", arity = "0..1", paramLabel = "CREDENTIAL")
        String credentialName;

        @Override
        public Integer call() throws Exception {
            CredentialStore store = CredentialStore.create();
            if ((providerId == null) != (credentialName == null)) {
                System.err.println("Provide both PROVIDER and CREDENTIAL, or neither to use the wizard.");
                return 2;
            }
            if (providerId == null) {
                try (AuthWizard wizard = AuthWizard.open()) {
                    AuthWizard.SwitchRequest request = wizard.promptForSwitch(store);
                    if (request == null) {
                        return 0;
                    }
                    providerId = request.providerId();
                    credentialName = request.credentialName();
                } catch (IOException e) {
                    System.err.println("Could not start credential switch wizard: " + e.getMessage());
                    return 1;
                }
            }
            if (!store.switchCredential(providerId, credentialName)) {
                System.err.println("No credential named '" + credentialName
                        + "' exists for provider " + providerId + ".");
                return 1;
            }
            System.out.println("Using credential '" + credentialName + "' for " + providerId + ".");
            return 0;
        }
    }

    @Command(name = "logout", mixinStandardHelpOptions = true,
            description = "Revoke and remove a named credential, provider, or all providers.")
    static class LogoutCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER",
                description = "Provider id to remove.")
        String providerId;

        @Parameters(index = "1", arity = "0..1", paramLabel = "CREDENTIAL",
                description = "Named credential to remove; omit to remove the provider.")
        String positionalCredentialName;

        @Option(names = {"--credential", "--name"}, paramLabel = "NAME",
                description = "Named credential to remove.")
        String credentialName;

        @Option(names = "--all",
                description = "Remove all providers, or all credentials for PROVIDER when supplied.")
        boolean all;

        @Override
        public Integer call() throws Exception {
            if (positionalCredentialName != null && credentialName != null) {
                System.err.println("Specify the credential either positionally or with --credential, not both.");
                return 2;
            }
            if (credentialName == null) {
                credentialName = positionalCredentialName;
            }
            if (all && credentialName != null) {
                System.err.println("--all cannot be combined with a named credential.");
                return 2;
            }
            if (providerId == null && credentialName != null) {
                System.err.println("PROVIDER is required for a named credential.");
                return 2;
            }

            CredentialStore store = CredentialStore.create();
            OAuthCredentialManager manager = new OAuthCredentialManager(
                    store,
                    new OAuthProviderRegistry());
            AuthWizard.LogoutRequest request;
            if (providerId == null && !all) {
                try (AuthWizard wizard = AuthWizard.open()) {
                    request = wizard.promptForLogout(store);
                } catch (IOException e) {
                    System.err.println("Could not start logout wizard: " + e.getMessage());
                    return 1;
                }
                if (request == null) {
                    return 0;
                }
            } else if (providerId == null) {
                request = new AuthWizard.LogoutRequest(AuthWizard.LogoutScope.ALL, null, null);
            } else if (credentialName != null) {
                request = new AuthWizard.LogoutRequest(
                        AuthWizard.LogoutScope.CREDENTIAL,
                        providerId,
                        credentialName);
            } else {
                request = new AuthWizard.LogoutRequest(
                        AuthWizard.LogoutScope.PROVIDER,
                        providerId,
                        null);
            }

            if (request.scope() == AuthWizard.LogoutScope.ALL) {
                int removed = manager.logoutAll();
                System.out.println(removed == 0
                        ? "No stored credentials."
                        : "Removed " + removed + " credential(s) from all providers.");
                return 0;
            }
            if (request.scope() == AuthWizard.LogoutScope.CREDENTIAL) {
                boolean removed = manager.logout(request.providerId(), request.credentialName());
                if (removed) {
                    System.out.println("Removed credential '" + request.credentialName()
                            + "' for " + request.providerId() + ".");
                } else {
                    System.out.println("No credential named '" + request.credentialName()
                            + "' for " + request.providerId() + ".");
                }
                return 0;
            }
            int count = store.list(request.providerId()).size();
            boolean removed = manager.logout(request.providerId());
            System.out.println(removed
                    ? "Removed " + count + " credential(s) for " + request.providerId() + "."
                    : "No stored credentials for " + request.providerId() + ".");
            return 0;
        }
    }

    @Command(name = "list", aliases = "status", mixinStandardHelpOptions = true,
            description = "List stored credential metadata without exposing secrets.")
    static class ListCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "PROVIDER",
                description = "Optional provider filter.")
        String providerId;

        @Option(names = "--available",
                description = "List built-in OAuth providers and their supported login methods.")
        boolean available;

        @Override
        public Integer call() throws Exception {
            if (available) {
                OAuthProviderRegistry registry = new OAuthProviderRegistry();
                registry.flows().stream()
                        .sorted(java.util.Comparator.comparing(OAuthProviderFlow::providerId))
                        .forEach(flow -> System.out.printf(
                                "%-24s %s%n",
                                flow.providerId(),
                                flow.loginMethods().stream()
                                        .map(OAuthProviderFlow.LoginMethod::id)
                                        .collect(java.util.stream.Collectors.joining(","))));
                return 0;
            }
            CredentialStore store = CredentialStore.create();
            var credentials = providerId == null ? store.list() : store.list(providerId);
            if (credentials.isEmpty()) {
                System.out.println(providerId == null
                        ? "No stored credentials."
                        : "No stored credentials for " + providerId + ".");
                return 0;
            }
            System.out.printf("%-22s %-20s %-10s %s%n",
                    "PROVIDER", "CREDENTIAL", "TYPE", "STATUS");
            for (CredentialStore.CredentialInfo credential : credentials) {
                System.out.printf("%-22s %-20s %-10s %s%n",
                        credential.providerId(),
                        credential.credentialName(),
                        credential.type(),
                        credential.active() ? "active" : "");
            }
            return 0;
        }
    }
}
