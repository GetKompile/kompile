/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.auth.oauth.OAuthProviderRegistry;
import ai.kompile.cli.main.chat.config.ChatProvider;
import ai.kompile.cli.main.chat.config.ChatProviderRegistry;
import ai.kompile.core.agent.AgentProvider;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chat-style numbered wizards for managed provider credentials. */
final class AuthWizard implements AutoCloseable {
    private final Prompter prompter;

    private AuthWizard(Prompter prompter) {
        this.prompter = prompter;
    }

    static AuthWizard open() throws IOException {
        return new AuthWizard(new TerminalPrompter());
    }

    static AuthWizard using(Prompter prompter) {
        if (prompter == null) {
            throw new IllegalArgumentException("prompter must not be null");
        }
        return new AuthWizard(prompter);
    }

    LoginRequest promptForLogin(
            OAuthProviderRegistry registry,
            CredentialStore store) throws IOException {
        prompter.header("Kompile Auth Login", "Add and select a provider credential");

        LinkedHashMap<String, String> providers = loginProviders(registry);
        List<String> providerIds = new ArrayList<>(providers.keySet());
        List<String> providerLabels = new ArrayList<>(providers.values());

        int providerIndex = prompter.select("Select Provider:", providerLabels);
        if (providerIndex < 0) {
            return null;
        }
        String providerId = providerIds.get(providerIndex);
        String oauthProviderId = registry.oauthProviderForVendor(providerId).orElse(null);
        List<LoginKind> kinds = loginKinds(registry, providerId, oauthProviderId);
        if (kinds.isEmpty()) {
            prompter.message("No supported login method is available for " + providerId + ".");
            return null;
        }

        List<String> kindLabels = kinds.stream()
                .map(AuthWizard::loginKindLabel)
                .toList();
        int kindIndex = kinds.size() == 1 ? 0 : prompter.select("Select Login Method:", kindLabels);
        if (kindIndex < 0) {
            return null;
        }
        LoginKind kind = kinds.get(kindIndex);
        if (kind == LoginKind.NATIVE) {
            return new LoginRequest(providerId, null, kind, null, null, true);
        }
        String credentialProviderId = kind == LoginKind.OAUTH ? oauthProviderId : providerId;

        List<CredentialStore.CredentialInfo> existing = store.list(credentialProviderId);
        String suggestedName = existing.isEmpty()
                ? CredentialStore.DEFAULT_CREDENTIAL_NAME
                : "account-" + (existing.size() + 1);
        String credentialName;
        while (true) {
            credentialName = prompter.text("Credential name:", suggestedName);
            if (credentialName == null || credentialName.isBlank()) {
                return null;
            }
            String candidate = credentialName.trim().toLowerCase(Locale.ROOT);
            boolean alreadyExists = existing.stream()
                    .anyMatch(info -> info.credentialName().equals(candidate));
            if (!alreadyExists || prompter.confirm(
                    "Replace existing credential '" + candidate + "'?", false)) {
                credentialName = candidate;
                break;
            }
            suggestedName = "account-" + (existing.size() + 1);
        }

        String storedValue = null;
        String oauthMethod = null;

        if (kind == LoginKind.API_KEY) {
            storedValue = prompter.secret("API key for " + providerId + ":");
            if (storedValue == null || storedValue.isBlank()) {
                prompter.message("API key must not be blank.");
                return null;
            }
        } else if (kind == LoginKind.ENVIRONMENT) {
            String environmentName = defaultEnvironmentName(providerId);
            if (environmentName == null || environmentName.isBlank()) {
                prompter.message("No environment credential is registered for " + providerId + ".");
                return null;
            }
            storedValue = "$" + environmentName;
        } else {
            OAuthProviderFlow flow = registry.require(credentialProviderId);
            List<OAuthProviderFlow.LoginMethod> methods = flow.loginMethods();
            if (methods.size() <= 1) {
                oauthMethod = methods.isEmpty() ? flow.defaultLoginMethod() : methods.get(0).id();
            } else {
                List<String> methodLabels = methods.stream()
                        .map(method -> method.label() + " (" + method.id() + ")")
                        .toList();
                int methodIndex = prompter.select("Select OAuth Method:", methodLabels);
                if (methodIndex < 0) {
                    return null;
                }
                oauthMethod = methods.get(methodIndex).id();
            }
        }

        boolean activate = existing.isEmpty()
                || prompter.confirm("Use this credential now for " + providerLabel(providerId) + "?", true);
        return new LoginRequest(
                credentialProviderId,
                credentialName,
                kind,
                storedValue,
                oauthMethod,
                activate);
    }

    private static List<LoginKind> loginKinds(
            OAuthProviderRegistry registry,
            String providerId,
            String oauthProviderId) {
        if (NativeCliAuth.isSupported(providerId)) {
            return List.of(LoginKind.NATIVE);
        }
        List<LoginKind> kinds = new ArrayList<>();
        if (oauthProviderId != null) {
            kinds.add(LoginKind.OAUTH);
        }
        if (registry.supportsApiKey(providerId)) {
            kinds.add(LoginKind.API_KEY);
            if (ChatProviderRegistry.environmentVariable(providerId) != null) {
                kinds.add(LoginKind.ENVIRONMENT);
            }
        }
        return List.copyOf(kinds);
    }

    private static String loginKindLabel(LoginKind kind) {
        return switch (kind) {
            case OAUTH -> "OAuth / subscription sign-in";
            case API_KEY -> "Paste an API key";
            case ENVIRONMENT -> "Reference an environment variable";
            case NATIVE -> "Native agent login — choose OAuth or API in the agent";
        };
    }

    SwitchRequest promptForSwitch(CredentialStore store) throws IOException {
        prompter.header("Kompile Auth Switch", "Choose the active credential for a provider");
        List<CredentialStore.CredentialInfo> all = store.list();
        if (all.isEmpty()) {
            prompter.message("No stored credentials. Run 'kompile auth login' first.");
            return null;
        }

        List<String> providers = all.stream()
                .map(CredentialStore.CredentialInfo::providerId)
                .distinct()
                .toList();
        int providerIndex = providers.size() == 1
                ? 0
                : prompter.select("Select Provider:", providers);
        if (providerIndex < 0) {
            return null;
        }
        String providerId = providers.get(providerIndex);
        List<CredentialStore.CredentialInfo> credentials = store.list(providerId);
        List<String> labels = credentials.stream()
                .map(AuthWizard::credentialLabel)
                .toList();
        int credentialIndex = credentials.size() == 1
                ? 0
                : prompter.select("Select Credential:", labels);
        if (credentialIndex < 0) {
            return null;
        }
        return new SwitchRequest(providerId, credentials.get(credentialIndex).credentialName());
    }

    LogoutRequest promptForLogout(CredentialStore store) throws IOException {
        prompter.header("Kompile Auth Logout", "Remove credentials from one or more providers");
        List<CredentialStore.CredentialInfo> all = store.list();
        if (all.isEmpty()) {
            prompter.message("No stored credentials.");
            return null;
        }

        List<LogoutRequest> requests = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (CredentialStore.CredentialInfo info : all) {
            requests.add(new LogoutRequest(
                    LogoutScope.CREDENTIAL,
                    info.providerId(),
                    info.credentialName()));
            labels.add("Remove " + info.providerId() + " / " + credentialLabel(info));
        }
        List<String> providers = all.stream()
                .map(CredentialStore.CredentialInfo::providerId)
                .distinct()
                .toList();
        for (String providerId : providers) {
            int count = (int) all.stream()
                    .filter(info -> info.providerId().equals(providerId))
                    .count();
            if (count > 1) {
                requests.add(new LogoutRequest(LogoutScope.PROVIDER, providerId, null));
                labels.add("Remove all " + count + " credentials for " + providerId);
            }
        }
        if (all.size() > 1 || providers.size() > 1) {
            requests.add(new LogoutRequest(LogoutScope.ALL, null, null));
            labels.add("Remove every stored credential from every provider");
        }

        int selected = prompter.select("Select Logout Scope:", labels);
        if (selected < 0) {
            return null;
        }
        LogoutRequest request = requests.get(selected);
        if (!prompter.confirm("Remove the selected credential data?", false)) {
            return null;
        }
        return request;
    }

    OAuthProviderFlow.Interaction oauthInteraction() {
        return new OAuthProviderFlow.Interaction() {
            @Override
            public void info(String message) {
                prompter.message(message);
            }

            @Override
            public void authorizationUrl(URI url, String instructions) {
                prompter.message(instructions);
                prompter.message(url.toString());
            }

            @Override
            public void deviceCode(
                    String userCode,
                    URI verificationUri,
                    Integer intervalSeconds,
                    Integer expiresInSeconds) {
                prompter.message("Open " + verificationUri);
                prompter.message("Enter device code: " + userCode);
            }

            @Override
            public String prompt(String message) throws IOException {
                try {
                    return prompter.text(message, null);
                } catch (RuntimeException e) {
                    throw new IOException("Could not read OAuth input", e);
                }
            }
        };
    }

    private static LinkedHashMap<String, String> loginProviders(OAuthProviderRegistry registry) {
        LinkedHashMap<String, String> providers = new LinkedHashMap<>();
        for (ChatProvider provider : ChatProviderRegistry.directProviders()) {
            providers.putIfAbsent(provider.id(), provider.displayName());
        }
        registry.flows().stream()
                .sorted(java.util.Comparator.comparing(OAuthProviderFlow::userFacingProviderId))
                .forEach(flow -> providers.putIfAbsent(
                        flow.userFacingProviderId(),
                        ChatProviderRegistry.find(flow.userFacingProviderId()) == null
                                ? flow.displayName() : ChatProviderRegistry.label(flow.userFacingProviderId())));
        for (AgentProvider agent : NativeCliAuth.providers()) {
            providers.putIfAbsent(agent.getCommand(),
                    agent.getDisplayName() + " — native auth (OAuth/API)");
        }
        return providers;
    }

    private static String providerLabel(String providerId) {
        ChatProvider provider = ChatProviderRegistry.find(providerId);
        if (provider != null) {
            return provider.displayName();
        }
        return registryLabel(providerId);
    }

    private static String registryLabel(String providerId) {
        return ChatProviderRegistry.label(providerId);
    }

    private static String vendorForOAuthProvider(String providerId) {
        return new OAuthProviderRegistry().find(providerId)
                .map(OAuthProviderFlow::userFacingProviderId)
                .orElse(providerId);
    }

    private static String credentialLabel(CredentialStore.CredentialInfo info) {
        return info.displayLabel();
    }

    private static String defaultEnvironmentName(String providerId) {
        return ChatProviderRegistry.environmentVariable(providerId);
    }

    @Override
    public void close() {
        prompter.close();
    }

    enum LoginKind {
        API_KEY,
        ENVIRONMENT,
        OAUTH,
        NATIVE
    }

    enum LogoutScope {
        CREDENTIAL,
        PROVIDER,
        ALL
    }

    record LoginRequest(
            String providerId,
            String credentialName,
            LoginKind kind,
            String storedValue,
            String oauthMethod,
            boolean activate) {
    }

    record SwitchRequest(String providerId, String credentialName) {
    }

    record LogoutRequest(LogoutScope scope, String providerId, String credentialName) {
    }

    interface Prompter extends AutoCloseable {
        void header(String title, String subtitle);

        int select(String title, List<String> items);

        String text(String label, String defaultValue);

        String secret(String label);

        boolean confirm(String question, boolean defaultYes);

        void message(String message);

        @Override
        default void close() {
        }
    }

    static final class TerminalPrompter implements Prompter {
        private static final String RESET = "\033[0m";
        private static final String BOLD = "\033[1m";
        private static final String DIM = "\033[2m";
        private static final String CYAN = "\033[36m";
        private static final String GREEN = "\033[32m";
        private static final String YELLOW = "\033[33m";

        private final Terminal terminal;
        private final LineReader reader;

        private TerminalPrompter() throws IOException {
            terminal = TerminalBuilder.builder().system(true).build();
            reader = LineReaderBuilder.builder().terminal(terminal).build();
        }

        TerminalPrompter(Terminal terminal, LineReader reader) {
            this.terminal = terminal;
            this.reader = reader;
        }

        private static String terminalSafe(String value) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            StringBuilder safe = new StringBuilder(value.length());
            value.codePoints().forEach(codePoint -> {
                if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
                    safe.append(' ');
                } else if (!Character.isISOControl(codePoint)) {
                    safe.appendCodePoint(codePoint);
                }
            });
            return safe.toString();
        }

        private void printMenuLine(String text) {
            int width = terminal.getWidth() > 0 ? terminal.getWidth() : 80;
            var line = org.jline.utils.AttributedString.fromAnsi(
                    text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '));
            terminal.writer().println(line.columnSubSequence(0, Math.max(1, width - 1)).toAnsi());
        }

        private int menuCapacity() {
            int height = terminal.getHeight() > 0 ? terminal.getHeight() : 24;
            return Math.max(1, height - 6);
        }

        @Override
        public void header(String title, String subtitle) {
            System.out.println();
            System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
            System.out.printf(BOLD + CYAN + "  │  %-36s│%n" + RESET, terminalSafe(title));
            System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
            System.out.println("  " + DIM + terminalSafe(subtitle) + RESET);
            System.out.println();
        }

        @Override
        public int select(String title, List<String> items) {
            // Resume/chat can leave a scroll region, mouse reporting, or bracketed
            // paste active. Reset them even when there is nothing to select.
            terminal.writer().print("\033[r\033[?9l\033[?1000l\033[?1001l\033[?1002l"
                    + "\033[?1003l\033[?1004l\033[?1005l\033[?1006l\033[?1007l"
                    + "\033[?1015l\033[?1016l\033[?2004l");
            terminal.writer().flush();
            if (items.isEmpty()) {
                return -1;
            }
            int first = 0;
            int pageEndLimit = items.size();
            String status = "";
            while (true) {
                int height = terminal.getHeight() > 0 ? terminal.getHeight() : 24;
                int capacity = menuCapacity();
                first = Math.min(first, items.size() - 1);
                int end = Math.min(first + capacity, Math.min(pageEndLimit, items.size()));
                terminal.writer().print("\033[2J\033[H");
                if (height >= 5) {
                    printMenuLine(BOLD + terminalSafe(title) + RESET);
                }
                for (int i = first; i < end; i++) {
                    printMenuLine(CYAN + String.format("%2d", i + 1) + RESET
                            + "  " + terminalSafe(items.get(i)));
                }
                if (height >= 6) {
                    printMenuLine(DIM + "Showing " + (first + 1) + "-" + end + " of " + items.size() + RESET);
                }
                if (height >= 4) {
                    printMenuLine("number/name | n next | p prev | q cancel");
                }
                if (height >= 7) {
                    printMenuLine(YELLOW + status + RESET);
                }
                terminal.writer().flush();
                String input;
                try {
                    input = reader.readLine("> ");
                } catch (RuntimeException e) {
                    return -1;
                }
                if (input == null) {
                    return -1;
                }
                String trimmed = input.trim();
                if (trimmed.equalsIgnoreCase("q")
                        || trimmed.equalsIgnoreCase("quit")
                        || trimmed.equalsIgnoreCase("cancel")) {
                    return -1;
                }
                if (trimmed.equalsIgnoreCase("n") || trimmed.equalsIgnoreCase("next")) {
                    if (end < items.size()) {
                        first = end;
                        pageEndLimit = items.size();
                    }
                    status = "";
                    continue;
                }
                if (trimmed.equalsIgnoreCase("p") || trimmed.equalsIgnoreCase("prev")) {
                    int previousEnd = first;
                    if (previousEnd > 0) {
                        first = Math.max(0, previousEnd - menuCapacity());
                        pageEndLimit = previousEnd;
                    }
                    status = "";
                    continue;
                }
                if (!trimmed.isEmpty()) {
                    try {
                        int selected = Integer.parseInt(trimmed);
                        if (selected >= 1 && selected <= items.size()) {
                            return selected - 1;
                        }
                        status = "Choose a number from 1 to " + items.size() + ".";
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                    int match = -1;
                    int matches = 0;
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i).equalsIgnoreCase(trimmed)) {
                            return i;
                        }
                        if (items.get(i).toLowerCase(Locale.ROOT).contains(trimmed.toLowerCase(Locale.ROOT))) {
                            match = i;
                            matches++;
                        }
                    }
                    if (matches == 1) {
                        return match;
                    }
                    status = matches > 1 ? "Name matches several choices; enter a number or more of the name."
                            : "No matching choice. Enter a number or part of the name.";
                } else {
                    status = "Enter a choice, n for next, or q to cancel.";
                }
            }
        }

        @Override
        public String text(String label, String defaultValue) {
            String suffix = defaultValue == null || defaultValue.isBlank()
                    ? " "
                    : " [" + terminalSafe(defaultValue) + "] ";
            try {
                String value = reader.readLine("  " + terminalSafe(label) + suffix);
                if ((value == null || value.isBlank()) && defaultValue != null) {
                    return defaultValue;
                }
                return value == null ? null : value.trim();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public String secret(String label) {
            try {
                return reader.readLine("  " + terminalSafe(label) + " ", '*');
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean confirm(String question, boolean defaultYes) {
            String suffix = defaultYes ? " [Y/n] " : " [y/N] ";
            try {
                String answer = reader.readLine("  " + terminalSafe(question) + suffix);
                if (answer == null || answer.isBlank()) {
                    return defaultYes;
                }
                return answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public void message(String message) {
            System.out.println("  " + GREEN + terminalSafe(message) + RESET);
        }

        @Override
        public void close() {
            try {
                terminal.close();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
