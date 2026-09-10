/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import org.jline.reader.LineReader;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Project judge profiles and legacy vendor defaults, shared by the chat setup wizard. */
public final class JudgeDefaultsWizard {
    private JudgeDefaultsWizard() {}

    public static void configure(LineReader reader, ChatConfig.Scope scope, Path projectRoot,
                                 ChatConfig currentConfig) throws IOException {
        int configure = SetupWizard.selectNumbered(reader, "Manage project judge profiles and vendor defaults?", List.of(
                "No — keep saved judge choices (default)", "Yes — manage judges"));
        if (configure != 1) return;
        System.out.println("  Judge profiles share the project profile store, but never change main-chat choices.");
        System.out.println("  Explicit judge models win. Profiles do not enable judges or change vendor/authentication.");
        System.out.println("  Project profiles: " + ChatProfiles.path(projectRoot));
        System.out.println("  Fallback defaults (" + scope + "): " + JudgeDefaults.configPath(scope, projectRoot));

        while (true) {
            List<ChatProfiles.Profile> profiles = ChatProfiles.list(projectRoot, "judge");
            List<String> vendors = new ArrayList<>(vendors());
            for (var profile : profiles) if (!vendors.contains(profile.vendor())) vendors.add(profile.vendor());
            List<String> labels = new ArrayList<>();
            labels.add("Done — keep these choices");
            for (String vendor : vendors) {
                var active = ChatProfiles.activeJudge(projectRoot, vendor);
                var saved = JudgeDefaults.vendorDefault(vendor, projectRoot);
                labels.add(SetupWizard.vendorLabel(vendor) + (active != null ? " — profile: " + label(active)
                        : saved == null ? " — not configured" : " — vendor default: " + saved.model()));
            }
            int selected = SetupWizard.selectNumbered(reader, "Select judge vendor:", labels);
            if (selected <= 0) return;
            String vendor = vendors.get(selected - 1);
            List<ChatProfiles.Profile> choices = profiles.stream().filter(p -> p.vendor().equals(vendor)).toList();
            int action = SetupWizard.selectNumbered(reader, "Manage " + SetupWizard.vendorLabel(vendor) + " judges:", List.of(
                    "Back — keep choices (default)", "Use a saved project judge profile",
                    "Create or replace a named project judge profile", "Delete a project judge profile",
                    "Clear active profile — use vendor defaults", "Edit fallback vendor default (" + scope + ")"));
            if (action < 0) return;
            switch (action) {
                case 1, 3 -> {
                    if (choices.isEmpty()) {
                        System.out.println("  No judge profiles saved for this vendor.");
                        continue;
                    }
                    int choice = SetupWizard.selectNumbered(reader, "Select project judge profile:",
                            choices.stream().map(JudgeDefaultsWizard::label).toList());
                    if (choice < 0) return;
                    String name = choices.get(choice).name();
                    if (action == 1) {
                        ChatProfiles.activateJudge(projectRoot, vendor, name);
                        System.out.println("  Active judge profile: " + vendor + " / " + name);
                    } else if (confirm(reader, "Delete " + name + "? An active selection will return to vendor defaults.")) {
                        ChatProfiles.deleteJudge(projectRoot, vendor, name);
                    }
                }
                case 2, 5 -> {
                    if (!configureModel(reader, scope, projectRoot, currentConfig, vendor, action == 2)) return;
                }
                case 4 -> {
                    ChatProfiles.activateJudge(projectRoot, vendor, null);
                    System.out.println("  Using vendor defaults for " + vendor);
                }
                default -> { }
            }
        }
    }

    private static boolean configureModel(LineReader reader, ChatConfig.Scope scope, Path projectRoot,
                                          ChatConfig currentConfig, String vendor, boolean named) throws IOException {
        boolean passthrough = currentConfig != null && "passthrough".equals(currentConfig.getChatMode());
        String currentProvider = currentConfig == null ? null : passthrough
                ? JudgeDefaults.capabilityProvider(currentConfig.getPassthroughAgent()) : currentConfig.getProvider();
        boolean sameVendor = vendor.equals(JudgeDefaults.vendor(currentProvider));
        String provider = sameVendor ? currentProvider : vendor;
        // Native chats use their agent's capability route, not stale direct-chat credentials/endpoints.
        ChatConfig discoveryConfig = sameVendor && !passthrough
                ? currentConfig : new ChatConfig(provider, null, null, null);
        var auth = discoveryConfig.resolveRequestAuth();
        String transientApiKey = auth != null && !auth.oauth() ? auth.token() : null;
        var model = SetupWizard.selectModel(reader, provider, transientApiKey, discoveryConfig);
        if (model == null || model.model() == null) return false;
        List<SetupWizard.ThinkingOption> choices = thinkingChoices(provider, model.model(), model.discovery());
        String thinking = choices.get(0).value();
        if (choices.size() > 1) {
            int choice = SetupWizard.selectNumbered(reader, "Judge thinking (Enter selects lowest supported):",
                    choices.stream().map(SetupWizard.ThinkingOption::label).toList());
            if (choice < 0) return false;
            thinking = choices.get(choice).value();
        } else {
            System.out.println("  " + choices.get(0).label());
        }
        if (named) {
            String name;
            try {
                name = reader.readLine("  Judge profile name (blank to skip): ");
            } catch (EndOfFileException | UserInterruptException e) {
                return false;
            }
            if (name == null) return false;
            if (name.isBlank()) return true;
            var profile = ChatProfiles.captureJudge(name, provider, model.model(), thinking);
            if (!ChatProfiles.save(projectRoot, profile, false)) {
                if (!confirm(reader, "Replace existing judge profile " + vendor + " / " + profile.name() + "?")) return true;
                ChatProfiles.save(projectRoot, profile, true);
            }
            System.out.println("  Saved project judge profile: " + label(profile));
            if (confirm(reader, "Use " + profile.name() + " as this project's active judge profile for " + vendor + "?")) {
                ChatProfiles.activateJudge(projectRoot, vendor, profile.name());
            }
        } else {
            JudgeDefaults.save(scope, projectRoot, provider, new JudgeDefaults.Selection(model.model(), thinking));
            System.out.println("  Saved fallback judge default for " + SetupWizard.vendorLabel(vendor) + ": " + model.model());
            if (ChatProfiles.activeJudge(projectRoot, vendor) != null) {
                System.out.println("  The active project profile still takes precedence; clear it to use this default.");
            }
        }
        return true;
    }

    private static boolean confirm(LineReader reader, String question) {
        return SetupWizard.selectNumbered(reader, question, List.of("No (default)", "Yes")) == 1;
    }

    private static String label(ChatProfiles.Profile profile) {
        return profile.name() + " / " + profile.model() + " / thinking: "
                + (profile.thinking() == null ? "lowest supported" : profile.thinking());
    }

    static List<String> vendors() {
        return ChatProviderRegistry.all().stream().map(ChatProvider::id).map(JudgeDefaults::vendor)
                .filter(vendor -> !vendor.isBlank()).distinct().toList();
    }

    static List<SetupWizard.ThinkingOption> thinkingChoices(String provider, String model,
                                                           ModelDiscovery.Result discovery) {
        List<SetupWizard.ThinkingOption> supported = SetupWizard.thinkingOptions(
                provider, model, null, null, discovery);
        String lowest = JudgeDefaults.lowestThinking(supported);
        LinkedHashMap<String, SetupWizard.ThinkingOption> choices = new LinkedHashMap<>();
        if (lowest != null) {
            choices.put(lowest, new SetupWizard.ThinkingOption(lowest, lowest + " — lowest supported (default)"));
        }
        for (var option : supported) {
            // Once the minimum is known, there is no ambiguous 'provider default' to save in its place.
            if (!option.value().isBlank()) choices.putIfAbsent(option.value(), option);
        }
        if (lowest == null) {
            List<SetupWizard.ThinkingOption> result = new ArrayList<>();
            result.add(new SetupWizard.ThinkingOption("", "Provider default — no known lowest thinking control"));
            result.addAll(choices.values());
            return List.copyOf(result);
        }
        return List.copyOf(choices.values());
    }
}
