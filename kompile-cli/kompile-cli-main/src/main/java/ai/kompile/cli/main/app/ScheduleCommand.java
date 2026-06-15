/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "schedule",
        description = "Scheduled job management",
        subcommands = {
                ScheduleCommand.CreateCmd.class,
                ScheduleCommand.ListCmd.class,
                ScheduleCommand.DeleteCmd.class
        },
        mixinStandardHelpOptions = true
)
public class ScheduleCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @CommandLine.Command(
            name = "create",
            description = "Create a scheduled job",
            subcommands = {
                    CreateCmd.StalenessCheckCmd.class,
                    CreateCmd.ReIngestionCmd.class,
                    CreateCmd.EvalSuiteCmd.class
            },
            mixinStandardHelpOptions = true
    )
    static class CreateCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            new CommandLine(this).usage(System.out);
            return 0;
        }

        @CommandLine.Command(name = "staleness-check", description = "Schedule a staleness check", mixinStandardHelpOptions = true)
        static class StalenessCheckCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @CommandLine.Option(names = "--cron", required = true, description = "Cron expression (e.g. '0 0 * * *')")
            private String cron;

            @CommandLine.Option(names = "--factSheetId", required = true, description = "Fact sheet ID")
            private String factSheetId;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("cronExpression", cron);
                    body.put("factSheetId", factSheetId);
                    String response = client.postString("/api/schedules/staleness-check", body);
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        System.out.println("Staleness check scheduled.");
                        JsonNode node = client.getObjectMapper().readTree(response);
                        if (node.has("id")) {
                            OutputFormatter.printKv("Schedule ID", node.get("id").asText());
                        }
                        OutputFormatter.printKv("Cron", cron);
                    }
                    return 0;
                } catch (IOException e) {
                    System.err.println("Error: " + e.getMessage());
                    return 1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 1;
                }
            }
        }

        @CommandLine.Command(name = "re-ingestion", description = "Schedule a re-ingestion job", mixinStandardHelpOptions = true)
        static class ReIngestionCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @CommandLine.Option(names = "--cron", required = true, description = "Cron expression")
            private String cron;

            @CommandLine.Option(names = "--factSheetId", required = true, description = "Fact sheet ID")
            private String factSheetId;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("cronExpression", cron);
                    body.put("factSheetId", factSheetId);
                    String response = client.postString("/api/schedules/re-ingestion", body);
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        System.out.println("Re-ingestion scheduled.");
                        JsonNode node = client.getObjectMapper().readTree(response);
                        if (node.has("id")) {
                            OutputFormatter.printKv("Schedule ID", node.get("id").asText());
                        }
                        OutputFormatter.printKv("Cron", cron);
                    }
                    return 0;
                } catch (IOException e) {
                    System.err.println("Error: " + e.getMessage());
                    return 1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 1;
                }
            }
        }

        @CommandLine.Command(name = "eval-suite", description = "Schedule an evaluation suite run", mixinStandardHelpOptions = true)
        static class EvalSuiteCmd implements Callable<Integer> {
            @CommandLine.Mixin
            private AppClientMixin app;

            @CommandLine.Option(names = "--cron", required = true, description = "Cron expression")
            private String cron;

            @CommandLine.Option(names = "--suiteId", required = true, description = "Evaluation suite ID")
            private String suiteId;

            @Override
            public Integer call() {
                KompileHttpClient client = app.requireClient();
                if (client == null) return 1;
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("cronExpression", cron);
                    body.put("suiteId", suiteId);
                    String response = client.postString("/api/schedules/eval-suite", body);
                    if (app.isJsonOutput()) {
                        OutputFormatter.printJson(response);
                    } else {
                        System.out.println("Eval suite scheduled.");
                        JsonNode node = client.getObjectMapper().readTree(response);
                        if (node.has("id")) {
                            OutputFormatter.printKv("Schedule ID", node.get("id").asText());
                        }
                        OutputFormatter.printKv("Cron", cron);
                    }
                    return 0;
                } catch (IOException e) {
                    System.err.println("Error: " + e.getMessage());
                    return 1;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 1;
                }
            }
        }
    }

    @CommandLine.Command(name = "list", description = "List all scheduled jobs", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/schedules");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode array = client.getObjectMapper().readTree(response);
                    System.out.println("Scheduled Jobs:");
                    OutputFormatter.printTable(array, "id", "type", "cronExpression", "enabled", "nextRun");
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "delete", description = "Delete a scheduled job", mixinStandardHelpOptions = true)
    static class DeleteCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Schedule ID to delete")
        private String scheduleId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.delete("/api/schedules/" + scheduleId);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Deleted schedule: " + scheduleId);
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }
}
