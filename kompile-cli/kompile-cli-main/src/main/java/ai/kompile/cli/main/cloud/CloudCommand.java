/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.cloud;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.Console;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Top-level {@code kompile cloud} command for authenticated interaction with
 * kompile-saas. Provides account management, cloud instance lifecycle, and
 * bridging between local running instances and the cloud backend.
 */
@Command(name = "cloud",
        mixinStandardHelpOptions = true,
        description = "Manage your kompile cloud account, instances, and applications.",
        subcommands = {
                CommandLine.HelpCommand.class,
                CloudCommand.LoginCommand.class,
                CloudCommand.RegisterCommand.class,
                CloudCommand.LogoutCommand.class,
                CloudCommand.StatusCommand.class,
                CloudCommand.InstancesCommand.class,
                CloudCommand.AppsCommand.class,
                CloudCommand.JobsCommand.class,
                CloudCommand.LocalCommand.class
        })
public class CloudCommand implements Callable<Integer> {

    private static final ObjectMapper PRETTY = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    static SaasClient buildClient() throws IOException {
        CloudConfig config = CloudConfig.loadOrFromEnv();
        String baseUrl = config != null ? config.getBaseUrl() : "https://api.getkompile.com";
        String token = config != null ? config.getToken() : null;
        return new SaasClient(baseUrl, token);
    }

    static SaasClient requireAuth() throws IOException {
        SaasClient client = buildClient();
        if (!client.isAuthenticated()) {
            throw new IOException("Not authenticated. Run 'kompile cloud login' first, "
                    + "or set KOMPILE_TOKEN environment variable.");
        }
        return client;
    }

    static void printJson(JsonNode node) throws IOException {
        System.out.println(PRETTY.writeValueAsString(node));
    }

    // =====================================================================
    // kompile cloud login
    // =====================================================================
    @Command(name = "login", mixinStandardHelpOptions = true,
            description = "Log in to your kompile cloud account.")
    static class LoginCommand implements Callable<Integer> {

        @Option(names = {"--user", "--userName"}, description = "Username")
        String username;

        @Option(names = {"--password"}, description = "Password")
        String password;

        @Option(names = {"--url"}, description = "kompile-saas base URL")
        String url;

        @Option(names = {"--no-save"}, description = "Don't save credentials to disk")
        boolean noSave;

        @Override
        public Integer call() throws Exception {
            Console console = System.console();

            if (username == null) {
                if (console != null) {
                    username = console.readLine("Username: ");
                } else {
                    System.err.println("--user is required in non-interactive mode");
                    return 1;
                }
            }
            if (password == null) {
                if (console != null) {
                    char[] pw = console.readPassword("Password: ");
                    password = new String(pw);
                } else {
                    System.err.println("--password is required in non-interactive mode");
                    return 1;
                }
            }

            CloudConfig config = CloudConfig.loadOrFromEnv();
            String baseUrl = url != null ? url : (config != null ? config.getBaseUrl() : "https://api.getkompile.com");

            SaasClient client = new SaasClient(baseUrl, null);
            JsonNode response = client.login(username, password);

            if (!client.isAuthenticated()) {
                System.err.println("Login failed.");
                return 1;
            }

            if (!noSave) {
                CloudConfig newConfig = CloudConfig.builder()
                        .token(client.getToken()).baseUrl(baseUrl).username(username).build();
                newConfig.save();
                System.out.println("Logged in as " + username);
                System.out.println("Credentials saved to " + CloudConfig.configPath());
            } else {
                System.out.println("Logged in as " + username);
            }

            if (response.has("user")) {
                JsonNode user = response.get("user");
                if (user.has("email")) System.out.println("  Email: " + user.get("email").asText());
                if (user.has("buildCredits")) System.out.println("  Credits: " + user.get("buildCredits").asLong());
            }

            return 0;
        }
    }

    // =====================================================================
    // kompile cloud register
    // =====================================================================
    @Command(name = "register", mixinStandardHelpOptions = true,
            description = "Register a new kompile cloud account.")
    static class RegisterCommand implements Callable<Integer> {

        @Option(names = {"--user", "--userName"}, description = "Username")
        String username;

        @Option(names = {"--email"}, description = "Email address")
        String email;

        @Option(names = {"--password"}, description = "Password")
        String password;

        @Option(names = {"--confirm-password", "--confirmPassword"}, description = "Confirm password")
        String confirmPassword;

        @Option(names = {"--url"}, description = "kompile-saas base URL")
        String url;

        @Option(names = {"--login"}, description = "Auto-login after registration (requires email confirmation)")
        boolean autoLogin;

        @Override
        public Integer call() throws Exception {
            Console console = System.console();

            if (username == null) {
                if (console != null) username = console.readLine("Username: ");
                else { System.err.println("--user is required in non-interactive mode"); return 1; }
            }
            if (email == null) {
                if (console != null) email = console.readLine("Email: ");
                else { System.err.println("--email is required in non-interactive mode"); return 1; }
            }
            if (password == null) {
                if (console != null) password = new String(console.readPassword("Password: "));
                else { System.err.println("--password is required in non-interactive mode"); return 1; }
            }
            if (confirmPassword == null) {
                if (console != null) confirmPassword = new String(console.readPassword("Confirm password: "));
                else confirmPassword = password;
            }

            if (!password.equals(confirmPassword)) {
                System.err.println("Passwords do not match.");
                return 1;
            }

            CloudConfig config = CloudConfig.loadOrFromEnv();
            String baseUrl = url != null ? url : (config != null ? config.getBaseUrl() : "https://api.getkompile.com");
            SaasClient client = new SaasClient(baseUrl, null);

            JsonNode response = client.register(username, email, password, confirmPassword);
            if (response.has("status")) {
                System.out.println(response.get("status").asText());
            } else {
                printJson(response);
            }

            System.out.println();
            System.out.println("Check your email to confirm your account.");
            System.out.println("After confirmation, log in with: kompile cloud login");

            if (autoLogin) {
                System.out.println();
                System.out.println("Attempting auto-login (will fail if email not yet confirmed)...");
                try {
                    client.login(username, password);
                    if (client.isAuthenticated()) {
                        CloudConfig.builder().token(client.getToken()).baseUrl(baseUrl).username(username).build().save();
                        System.out.println("Logged in as " + username);
                        System.out.println("Credentials saved to " + CloudConfig.configPath());
                    }
                } catch (IOException e) {
                    System.err.println("Login failed: " + e.getMessage());
                    System.err.println("Confirm your email first, then run: kompile cloud login");
                }
            }

            return 0;
        }
    }

    // =====================================================================
    // kompile cloud logout
    // =====================================================================
    @Command(name = "logout", mixinStandardHelpOptions = true,
            description = "Clear stored cloud credentials.")
    static class LogoutCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            if (CloudConfig.exists()) {
                CloudConfig config = CloudConfig.load();
                if (config != null) {
                    config.clearCredentials();
                }
            }
            System.out.println("Logged out. Credentials cleared.");
            return 0;
        }
    }

    // =====================================================================
    // kompile cloud status
    // =====================================================================
    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Show authentication status and account info.")
    static class StatusCommand implements Callable<Integer> {

        @Option(names = "--json", description = "Output raw JSON")
        boolean json;

        @Override
        public Integer call() throws Exception {
            SaasClient client = requireAuth();
            JsonNode credits = client.get("/api/upload/buildcredits");

            if (json) {
                printJson(credits);
            } else {
                CloudConfig config = CloudConfig.load();
                System.out.println("Authenticated to " + (config != null ? config.getBaseUrl() : "kompile-saas"));
                if (config != null && config.getUsername() != null) {
                    System.out.println("  User: " + config.getUsername());
                }
                if (credits.has("buildCredits")) {
                    System.out.println("  Build credits: " + credits.get("buildCredits").asLong());
                }
            }
            return 0;
        }
    }

    // =====================================================================
    // kompile cloud instances
    // =====================================================================
    @Command(name = "instances", mixinStandardHelpOptions = true,
            description = "Manage cloud instances.",
            subcommands = {CommandLine.HelpCommand.class})
    static class InstancesCommand implements Callable<Integer> {

        @Option(names = "--json", description = "Output raw JSON")
        boolean json;

        @Override
        public Integer call() throws Exception {
            // Default: list
            SaasClient client = requireAuth();
            JsonNode instances = client.get("/api/instances");
            if (json) {
                printJson(instances);
            } else if (!instances.isArray() || instances.isEmpty()) {
                System.out.println("No cloud instances.");
            } else {
                System.out.printf("  %-6s %-25s %-15s %-12s %-20s%n", "ID", "Name", "Type", "Status", "Endpoint");
                for (JsonNode inst : instances) {
                    System.out.printf("  %-6s %-25s %-15s %-12s %-20s%n",
                            inst.path("id").asText("-"),
                            inst.path("instanceName").asText("-"),
                            inst.path("appType").asText("-"),
                            inst.path("status").asText("-"),
                            inst.path("endpointUrl").asText("-"));
                }
            }
            return 0;
        }
    }

    // =====================================================================
    // kompile cloud apps
    // =====================================================================
    @Command(name = "apps", mixinStandardHelpOptions = true,
            description = "Manage cloud applications.",
            subcommands = {
                    CommandLine.HelpCommand.class,
                    AppsCommand.ListApps.class,
                    AppsCommand.ShowApp.class,
                    AppsCommand.CreateApp.class,
                    AppsCommand.DeployApp.class,
                    AppsCommand.DeployModelStaging.class,
                    AppsCommand.DeleteApp.class,
                    AppsCommand.TypesApp.class
            })
    static class AppsCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            return new ListApps().call();
        }

        @Command(name = "list", description = "List applications")
        static class ListApps implements Callable<Integer> {
            @Option(names = "--json") boolean json;
            @Override
            public Integer call() throws Exception {
                SaasClient client = requireAuth();
                JsonNode apps = client.get("/api/applications");
                if (json) { printJson(apps); return 0; }
                if (!apps.isArray() || apps.isEmpty()) { System.out.println("No applications."); return 0; }
                System.out.printf("  %-6s %-25s %-15s %-10s %-8s%n", "ID", "Name", "Type", "Status", "Instances");
                for (JsonNode a : apps) {
                    System.out.printf("  %-6s %-25s %-15s %-10s %-8s%n",
                            a.path("id").asText("-"),
                            a.path("appName").asText("-"),
                            a.path("appType").asText("-"),
                            a.path("status").asText("-"),
                            a.path("runningInstanceCount").asText("0") + "/" + a.path("instanceCount").asText("0"));
                }
                return 0;
            }
        }

        @Command(name = "show", description = "Show application details")
        static class ShowApp implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", description = "Application ID") long id;
            @Override
            public Integer call() throws Exception {
                printJson(requireAuth().get("/api/applications/" + id));
                return 0;
            }
        }

        @Command(name = "types", description = "List available application types")
        static class TypesApp implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                SaasClient client = requireAuth();
                JsonNode types = client.get("/api/applications/types");
                if (!types.isArray()) { printJson(types); return 0; }
                System.out.printf("  %-20s %-15s %-8s %-8s%n", "ID", "Name", "Port", "Service");
                for (JsonNode t : types) {
                    System.out.printf("  %-20s %-15s %-8s %-8s%n",
                            t.path("id").asText(), t.path("name").asText(),
                            t.path("defaultPort").asText(), t.path("isService").asText());
                }
                return 0;
            }
        }

        @Command(name = "create", description = "Create an application")
        static class CreateApp implements Callable<Integer> {
            @Option(names = "--name", required = true) String name;
            @Option(names = "--type", required = true, description = "App type: rag-app, hosted-llm-rag, model-staging, kompile-app, etc.") String type;
            @Option(names = "--description") String description;
            @Option(names = "--llm-provider") String llmProvider;
            @Option(names = "--llm-model") String llmModel;
            @Option(names = "--embedding-provider") String embeddingProvider;
            @Option(names = "--embedding-model") String embeddingModel;
            @Option(names = "--vectorstore-provider") String vectorstoreProvider;
            @Option(names = "--cpu") Integer cpuUnits;
            @Option(names = "--memory") Integer memoryMb;

            @Override
            public Integer call() throws Exception {
                SaasClient client = requireAuth();
                var body = PRETTY.createObjectNode();
                body.put("appName", name);
                body.put("appType", type);
                if (description != null) body.put("description", description);
                if (llmProvider != null) body.put("llmProvider", llmProvider);
                if (llmModel != null) body.put("llmModel", llmModel);
                if (embeddingProvider != null) body.put("embeddingProvider", embeddingProvider);
                if (embeddingModel != null) body.put("embeddingModel", embeddingModel);
                if (vectorstoreProvider != null) body.put("vectorstoreProvider", vectorstoreProvider);
                if (cpuUnits != null) body.put("cpuUnits", cpuUnits);
                if (memoryMb != null) body.put("memoryMb", memoryMb);
                JsonNode result = client.post("/api/applications", body.toString());
                System.out.println("Application created:");
                printJson(result);
                return 0;
            }
        }

        @Command(name = "deploy", description = "Deploy an application to cloud instances")
        static class DeployApp implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", description = "Application ID") long id;
            @Option(names = "--count", defaultValue = "1") int count;
            @Override
            public Integer call() throws Exception {
                SaasClient client = requireAuth();
                JsonNode result = client.post("/api/applications/" + id + "/deploy",
                        "{\"instanceCount\":" + count + "}");
                System.out.println("Deployed:");
                printJson(result);
                return 0;
            }
        }

        @Command(name = "deploy-model-staging", description = "Quick-deploy a model staging service")
        static class DeployModelStaging implements Callable<Integer> {
            @Option(names = "--name", required = true) String instanceName;
            @Option(names = "--description") String description;
            @Option(names = "--model-dir") String modelDirectory;
            @Option(names = "--staging-dir") String stagingDirectory;
            @Option(names = "--huggingface-token") String hfToken;
            @Option(names = "--archive-token") String archiveToken;
            @Option(names = "--cpu") Integer cpuUnits;
            @Option(names = "--memory") Integer memoryMb;
            @Option(names = "--storage") Integer storageGb;

            @Override
            public Integer call() throws Exception {
                SaasClient client = requireAuth();
                var body = PRETTY.createObjectNode();
                body.put("instanceName", instanceName);
                if (description != null) body.put("description", description);
                if (modelDirectory != null) body.put("modelDirectory", modelDirectory);
                if (stagingDirectory != null) body.put("stagingDirectory", stagingDirectory);
                if (hfToken != null) body.put("huggingfaceToken", hfToken);
                if (archiveToken != null) body.put("archiveToken", archiveToken);
                if (cpuUnits != null) body.put("cpuUnits", cpuUnits);
                if (memoryMb != null) body.put("memoryMb", memoryMb);
                if (storageGb != null) body.put("storageGb", storageGb);
                JsonNode result = client.post("/api/applications/deploy/model-staging", body.toString());
                System.out.println("Model staging service deployed:");
                printJson(result);
                return 0;
            }
        }

        @Command(name = "delete", description = "Delete an application")
        static class DeleteApp implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", description = "Application ID") long id;
            @Override
            public Integer call() throws Exception {
                requireAuth().delete("/api/applications/" + id);
                System.out.println("Application deleted.");
                return 0;
            }
        }
    }

    // =====================================================================
    // kompile cloud jobs
    // =====================================================================
    @Command(name = "jobs", mixinStandardHelpOptions = true,
            description = "Manage cloud build jobs.",
            subcommands = {
                    CommandLine.HelpCommand.class,
                    JobsCommand.ListJobs.class,
                    JobsCommand.CreditsCommand.class,
                    JobsCommand.LogsCommand.class,
                    JobsCommand.CancelJob.class
            })
    static class JobsCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception { return new ListJobs().call(); }

        @Command(name = "list", description = "List all jobs")
        static class ListJobs implements Callable<Integer> {
            @Option(names = "--json") boolean json;
            @Override
            public Integer call() throws Exception {
                printJson(requireAuth().get("/api/upload/joboverview"));
                return 0;
            }
        }

        @Command(name = "credits", description = "Show build credits")
        static class CreditsCommand implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                printJson(requireAuth().get("/api/upload/buildcredits"));
                return 0;
            }
        }

        @Command(name = "logs", description = "View logs for a job")
        static class LogsCommand implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", description = "Job name") String jobName;
            @Option(names = "--lines", defaultValue = "50") int lines;
            @Override
            public Integer call() throws Exception {
                SaasClient client = requireAuth();
                String body = String.format(
                        "{\"jobName\":\"%s\",\"ascending\":false,\"numLogs\":%d,\"startFromHead\":false}",
                        jobName, lines);
                JsonNode logs = client.post("/api/upload/logsforjob", body);
                if (logs.isArray()) {
                    for (JsonNode entry : logs) {
                        String ts = entry.path("timeStamp").asText("");
                        String msg = entry.path("logMessage").asText("");
                        System.out.println(ts + " " + msg);
                    }
                } else {
                    printJson(logs);
                }
                return 0;
            }
        }

        @Command(name = "cancel", description = "Cancel a running job")
        static class CancelJob implements Callable<Integer> {
            @CommandLine.Parameters(index = "0", description = "Job name") String jobName;
            @Override
            public Integer call() throws Exception {
                printJson(requireAuth().post("/api/upload/canceljob",
                        String.format("{\"jobNames\":[\"%s\"]}", jobName)));
                return 0;
            }
        }
    }

    // =====================================================================
    // kompile cloud local — bridge between local and cloud instances
    // =====================================================================
    @Command(name = "local", mixinStandardHelpOptions = true,
            description = "Show locally running kompile instances and their cloud sync status.",
            subcommands = {
                    CommandLine.HelpCommand.class,
                    LocalCommand.ListLocal.class,
                    LocalCommand.SyncLocal.class
            })
    static class LocalCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception { return new ListLocal().call(); }

        @Command(name = "list", description = "List locally running kompile instances")
        static class ListLocal implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                List<InstanceInfo> locals = InstanceRegistry.listAll();
                if (locals.isEmpty()) {
                    System.out.println("No local kompile instances running.");
                    System.out.println("Start one with: kompile web");
                    return 0;
                }

                System.out.printf("  %-25s %-20s %-8s %-8s %-30s%n", "Name", "Type", "Port", "PID", "Project");
                for (InstanceInfo info : locals) {
                    boolean alive = isProcessAlive(info.getPid());
                    String status = alive ? "running" : "stale";
                    System.out.printf("  %-25s %-20s %-8d %-8s %-30s%n",
                            info.getName(),
                            info.getType() != null ? info.getType() : "-",
                            info.getPort(),
                            status,
                            info.getProjectDir() != null ? info.getProjectDir() : "-");
                }
                return 0;
            }
        }

        @Command(name = "sync", description = "Sync local instances with cloud account")
        static class SyncLocal implements Callable<Integer> {
            @Override
            public Integer call() throws Exception {
                SaasClient client = requireAuth();
                List<InstanceInfo> locals = InstanceRegistry.listAll();

                if (locals.isEmpty()) {
                    System.out.println("No local instances to sync.");
                    return 0;
                }

                System.out.println("Local instances:");
                for (InstanceInfo info : locals) {
                    boolean alive = isProcessAlive(info.getPid());
                    System.out.printf("  %s [%s] port=%d %s%n",
                            info.getName(),
                            info.getType() != null ? info.getType() : "unknown",
                            info.getPort(),
                            alive ? "running" : "stale");
                }

                System.out.println();
                System.out.println("Cloud instances:");
                JsonNode cloudInstances = client.get("/api/instances");
                if (!cloudInstances.isArray() || cloudInstances.isEmpty()) {
                    System.out.println("  (none)");
                } else {
                    for (JsonNode inst : cloudInstances) {
                        System.out.printf("  %s [%s] %s %s%n",
                                inst.path("instanceName").asText("-"),
                                inst.path("appType").asText("-"),
                                inst.path("status").asText("-"),
                                inst.path("endpointUrl").asText(""));
                    }
                }

                return 0;
            }
        }

        private static boolean isProcessAlive(long pid) {
            if (pid <= 0) return false;
            try {
                return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
