/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.cloud;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.config.ImportMode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.Callable;

/** Manage named Kompile settings profiles in the authenticated SaaS account. */
@Command(name = "settings",
        aliases = "profiles",
        mixinStandardHelpOptions = true,
        description = "Store, retrieve, and manage non-secret Kompile settings profiles.",
        subcommands = {
                CommandLine.HelpCommand.class,
                CloudSettingsCommand.ListCommand.class,
                CloudSettingsCommand.GetCommand.class,
                CloudSettingsCommand.PushCommand.class,
                CloudSettingsCommand.PullCommand.class,
                CloudSettingsCommand.DeleteCommand.class
        })
public class CloudSettingsCommand implements Callable<Integer> {

    private static final String API = "/api/cli-settings";
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    @Override
    public Integer call() throws Exception {
        return new ListCommand().call();
    }

    @Command(name = "list", aliases = "ls", description = "List cloud settings profiles.")
    static class ListCommand implements Callable<Integer> {
        @Option(names = "--json", description = "Print the raw JSON response.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            JsonNode profiles = CloudCommand.requireAuth().get(API);
            if (json) {
                CloudCommand.printJson(profiles);
                return 0;
            }
            if (profiles == null || !profiles.isArray() || profiles.isEmpty()) {
                System.out.println("No cloud settings profiles.");
                System.out.println("Create one with: kompile cloud settings push [profile]");
                return 0;
            }
            System.out.printf("%-24s %-9s %-7s %-25s %s%n",
                    "Profile", "Revision", "Files", "Updated", "Description");
            for (JsonNode profile : profiles) {
                System.out.printf("%-24s %-9d %-7d %-25s %s%n",
                        profile.path("profileKey").asText("-"),
                        profile.path("version").asLong(),
                        profile.path("fileCount").asInt(),
                        profile.path("updatedAt").asText("-"),
                        safeDisplay(profile.path("description").asText("")));
            }
            return 0;
        }
    }

    @Command(name = "get", aliases = "show", description = "Retrieve a cloud settings profile.")
    static class GetCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", defaultValue = "default",
                description = "Profile name (default: ${DEFAULT-VALUE}).")
        String profileKey;

        @Option(names = {"-o", "--output"}, description = "Write JSON to this file instead of stdout.")
        Path output;

        @Option(names = "--bundle-only", description = "Return only the portable settings bundle.")
        boolean bundleOnly;

        @Override
        public Integer call() throws Exception {
            JsonNode profile = CloudCommand.requireAuth().get(profilePath(profileKey));
            JsonNode value = bundleOnly ? profile.path("settings") : profile;
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            if (output == null) {
                System.out.println(json);
            } else {
                Path absolute = output.toAbsolutePath().normalize();
                if (absolute.getParent() != null) {
                    Files.createDirectories(absolute.getParent());
                }
                Files.writeString(absolute, json + System.lineSeparator(), StandardCharsets.UTF_8);
                System.out.println("Saved " + profileKey + " to " + absolute);
            }
            return 0;
        }
    }

    @Command(name = "push", description = "Capture local settings and store them as a cloud profile.")
    static class PushCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", defaultValue = "default",
                description = "Profile name (default: ${DEFAULT-VALUE}).")
        String profileKey;

        @Option(names = {"-d", "--description"}, description = "Profile description.")
        String description;

        @Option(names = "--scope", defaultValue = "user",
                description = "Settings scope: user, project, or all (default: ${DEFAULT-VALUE}).")
        String scope;

        @Option(names = "--project-dir",
                description = "Project root for project/all scope (default: resolved current project).")
        Path projectDir;

        @Option(names = "--expected-version",
                description = "Require this cloud revision (-1 creates only if absent).")
        Long expectedVersion;

        @Option(names = "--dry-run", description = "Capture and validate without uploading.")
        boolean dryRun;

        @Option(names = "--json", description = "Print the captured bundle for a dry run.")
        boolean json;

        @Override
        public Integer call() throws Exception {
            CloudSettingsBundleService.validateProfileKey(profileKey);
            CloudSettingsBundleService.validateDescription(description);
            CloudSettingsBundleService.Scope selectedScope =
                    CloudSettingsBundleService.Scope.parse(scope);
            Path resolvedProject = selectedScope == CloudSettingsBundleService.Scope.USER
                    ? projectDir : resolveProject(projectDir);

            CloudSettingsBundleService bundles = new CloudSettingsBundleService();
            CloudSettingsBundleService.CaptureResult captured =
                    bundles.capture(selectedScope, resolvedProject);
            if (dryRun && json) {
                printRedactionWarnings(captured);
            } else {
                printCaptureSummary(captured);
            }
            if (captured.getFiles().isEmpty()) {
                System.err.println("No settings files were found for scope " + scope + ".");
                return 1;
            }
            if (dryRun) {
                if (json) {
                    System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(captured.getBundle()));
                    System.err.println("Dry run only; no cloud profile was changed.");
                } else {
                    System.out.println("Dry run only; no cloud profile was changed.");
                }
                return 0;
            }

            SaasClient client = CloudCommand.requireAuth();
            if (expectedVersion != null && expectedVersion < -1) {
                System.err.println("--expected-version must be -1 for create or >= 0 for update.");
                return 2;
            }
            long expected = expectedVersion != null
                    ? expectedVersion : currentVersionForPush(client, profileKey);
            ObjectNode request = MAPPER.createObjectNode();
            request.put("schemaVersion", CloudSettingsBundleService.FORMAT_VERSION);
            request.put("expectedVersion", expected);
            if (description != null) {
                request.put("description", description);
            }
            request.set("settings", captured.getBundle());

            JsonNode response;
            try {
                response = client.put(profilePath(profileKey), request.toString());
            } catch (SaasClient.HttpException e) {
                return reportCloudFailure("store", profileKey, expected, e);
            }
            System.out.printf("Stored cloud settings profile '%s' at revision %d (%d files).%n",
                    profileKey, response.path("version").asLong(),
                    response.path("fileCount").asInt());
            return 0;
        }
    }

    @Command(name = "pull", description = "Retrieve and apply a cloud settings profile locally.")
    static class PullCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", defaultValue = "default",
                description = "Profile name (default: ${DEFAULT-VALUE}).")
        String profileKey;

        @Option(names = {"-m", "--mode"}, defaultValue = "append",
                description = "Apply mode: append or override (default: ${DEFAULT-VALUE}).")
        String mode;

        @Option(names = "--project-dir",
                description = "Project root for project settings (default: resolved current project).")
        Path projectDir;

        @Option(names = "--preview", description = "Show files without changing local settings.")
        boolean preview;

        @Override
        public Integer call() throws Exception {
            JsonNode profile = CloudCommand.requireAuth().get(profilePath(profileKey));
            JsonNode settings = profile.get("settings");
            if (settings == null || !settings.isObject()) {
                throw new IllegalArgumentException("Cloud profile does not contain a settings bundle");
            }

            CloudSettingsBundleService bundles = new CloudSettingsBundleService();
            bundles.validateBundle(settings);
            System.out.printf("Profile: %s  revision: %d  files: %d%n",
                    profile.path("profileKey").asText(profileKey),
                    profile.path("version").asLong(),
                    settings.path("files").size());
            Iterator<String> names = settings.path("files").fieldNames();
            while (names.hasNext()) {
                System.out.println("  - " + names.next());
            }
            if (preview) {
                System.out.println("Preview only; no local settings were changed.");
                return 0;
            }

            ImportMode importMode;
            try {
                importMode = ImportMode.valueOf(mode.trim().toUpperCase());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("mode must be append or override");
            }
            CloudSettingsBundleService.ApplyResult result = bundles.apply(
                    settings, importMode, resolveProject(projectDir));
            for (String path : result.getCreated()) {
                System.out.println("  + " + path);
            }
            for (String path : result.getUpdated()) {
                System.out.println("  ~ " + path);
            }
            System.out.printf("Applied %d settings files. Local secret values were preserved.%n",
                    result.totalProcessed());
            return 0;
        }
    }

    @Command(name = "delete", aliases = "rm", description = "Delete a cloud settings profile.")
    static class DeleteCommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", defaultValue = "default",
                description = "Profile name (default: ${DEFAULT-VALUE}).")
        String profileKey;

        @Option(names = "--expected-version",
                description = "Require this revision instead of fetching the current revision.")
        Long expectedVersion;

        @Option(names = {"-y", "--yes"}, required = true,
                description = "Confirm permanent profile deletion.")
        boolean confirmed;

        @Override
        public Integer call() throws Exception {
            if (!confirmed) {
                System.err.println("Profile deletion requires --yes.");
                return 2;
            }
            SaasClient client = CloudCommand.requireAuth();
            if (expectedVersion != null && expectedVersion < 0) {
                System.err.println("--expected-version must be >= 0 for delete.");
                return 2;
            }
            long expected = expectedVersion != null ? expectedVersion : -1L;
            try {
                if (expectedVersion == null) {
                    expected = currentVersionRequired(client, profileKey);
                }
                client.delete(profilePath(profileKey) + "?expectedVersion=" + expected);
            } catch (SaasClient.HttpException e) {
                return reportCloudFailure("delete", profileKey, expected, e);
            }
            System.out.println("Deleted cloud settings profile '" + profileKey + "'.");
            return 0;
        }
    }

    private static String profilePath(String profileKey) {
        CloudSettingsBundleService.validateProfileKey(profileKey);
        return API + "/" + profileKey;
    }

    private static long currentVersionForPush(SaasClient client, String profileKey) throws Exception {
        try {
            return client.get(profilePath(profileKey)).path("version").asLong();
        } catch (SaasClient.HttpException e) {
            if (e.getStatusCode() == 404) {
                return -1L;
            }
            throw e;
        }
    }

    private static long currentVersionRequired(SaasClient client, String profileKey)
            throws Exception {
        return client.get(profilePath(profileKey)).path("version").asLong();
    }

    static int reportCloudFailure(String action, String profileKey, long expected,
                                  SaasClient.HttpException error) {
        if (error.getStatusCode() == 409) {
            Long current = null;
            try {
                JsonNode response = MAPPER.readTree(error.getResponseBody());
                if (response != null && response.hasNonNull("currentVersion")) {
                    current = response.get("currentVersion").asLong();
                }
            } catch (Exception ignored) {
                // Fall through to the status-only conflict message.
            }
            System.err.printf("Could not %s cloud settings profile '%s': revision conflict "
                            + "(expected %d%s).%n",
                    action, profileKey, expected,
                    current == null ? "" : ", current " + current);
            System.err.println("Run 'kompile cloud settings get " + profileKey
                    + "' and retry with --expected-version <revision>.");
            return 1;
        }
        if (error.getStatusCode() == 404) {
            System.err.println("Cloud settings profile not found: " + profileKey);
            return 1;
        }
        System.err.println("Could not " + action + " cloud settings profile '"
                + profileKey + "': " + error.getMessage());
        return 1;
    }

    private static Path resolveProject(Path projectDir) {
        return (projectDir != null ? projectDir : KompileHome.resolvedProjectDirectory().toPath())
                .toAbsolutePath().normalize();
    }

    private static void printCaptureSummary(CloudSettingsBundleService.CaptureResult captured) {
        System.out.println("Captured settings files: " + captured.getFiles().size());
        for (String path : captured.getFiles()) {
            System.out.println("  - " + path);
        }
        if (!captured.getRedactedPaths().isEmpty()) {
            System.out.println("Excluded plaintext secret fields: "
                    + captured.getRedactedPaths().size());
            for (String path : captured.getRedactedPaths()) {
                System.out.println("  ! " + path);
            }
        }
    }

    private static void printRedactionWarnings(
            CloudSettingsBundleService.CaptureResult captured) {
        for (String path : captured.getRedactedPaths()) {
            System.err.println("Excluded plaintext secret field: " + path);
        }
    }

    static String safeDisplay(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            safe.append(Character.isISOControl(character) ? '?' : character);
        }
        return safe.toString();
    }
}
