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
        @Parameters(index = "0", paramLabel = "PROVIDER",
                description = "Provider id, for example openai or anthropic.")
        String providerId;

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
            OAuthProviderRegistry registry = new OAuthProviderRegistry();
            boolean useOAuth = oauth || registry.isOAuthOnly(providerId);
            if (useOAuth) {
                if (stdin || environmentName != null) {
                    System.err.println("--stdin and --from-env apply only to API-key login.");
                    return 2;
                }
                try {
                    OAuthCredentialManager manager = OAuthCredentialManager.create();
                    OAuthProviderFlow.LoginOptions options = new OAuthProviderFlow.LoginOptions(
                            oauthMethod,
                            manual,
                            enterpriseDomain,
                            gateway);
                    ManagedCredential credential = manager.login(
                            providerId,
                            options,
                            new ConsoleOAuthInteraction());
                    System.out.println("Saved " + credential.getType() + " credential for "
                            + providerId + " to " + CredentialStore.create().getAuthPath());
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

            if (oauthMethod != null || manual || enterpriseDomain != null || gateway != null) {
                System.err.println("OAuth options require --oauth for provider " + providerId + ".");
                return 2;
            }
            if (stdin && environmentName != null) {
                System.err.println("Use only one of --stdin or --from-env.");
                return 2;
            }

            String storedValue;
            if (environmentName != null) {
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

            CredentialStore store = CredentialStore.create();
            store.putApiKey(providerId, storedValue);
            System.out.println("Saved API key for " + providerId + " to " + store.getAuthPath());
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

    @Command(name = "logout", mixinStandardHelpOptions = true,
            description = "Revoke and remove a stored provider credential.")
    static class LogoutCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "PROVIDER",
                description = "Provider id to remove.")
        String providerId;

        @Override
        public Integer call() throws Exception {
            boolean removed = OAuthCredentialManager.create().logout(providerId);
            if (removed) {
                System.out.println("Removed stored credential for " + providerId + ".");
            } else {
                System.out.println("No stored credential for " + providerId + ".");
            }
            return 0;
        }
    }

    @Command(name = "list", aliases = "status", mixinStandardHelpOptions = true,
            description = "List stored credential metadata without exposing secrets.")
    static class ListCommand implements Callable<Integer> {
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
            var credentials = store.list();
            if (credentials.isEmpty()) {
                System.out.println("No stored credentials.");
                return 0;
            }
            for (CredentialStore.CredentialInfo credential : credentials) {
                System.out.printf("%-24s %s%n", credential.providerId(), credential.type());
            }
            return 0;
        }
    }
}
