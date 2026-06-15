/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.graph;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;
import ai.kompile.cli.main.app.OutputFormatter;
import picocli.CommandLine;

import java.util.Scanner;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph delete-graph} — delete a named graph and all its children.
 * Prompts for confirmation unless {@code --force} is given.
 */
@CommandLine.Command(
        name = "delete-graph",
        description = "Delete a named graph and its children",
        mixinStandardHelpOptions = true
)
public class GraphDeleteCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--graph-id", required = true,
            description = "Graph ID to delete")
    private String graphId;

    @CommandLine.Option(names = "--force",
            description = "Skip confirmation prompt")
    private boolean force;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            if (!force) {
                System.out.printf("Are you sure you want to delete graph '%s' and all its children? [y/N] ", graphId);
                System.out.flush();
                try (Scanner scanner = new Scanner(System.in)) {
                    String answer = scanner.nextLine().trim();
                    if (!answer.equalsIgnoreCase("y") && !answer.equalsIgnoreCase("yes")) {
                        System.out.println("Aborted.");
                        return 0;
                    }
                }
            }

            client.delete("/api/graphs/" + graphId);
            System.out.println("Deleted graph: " + graphId);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
