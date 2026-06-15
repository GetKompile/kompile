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
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Interactive Cypher REPL. Supports history, scripted sourcing and a small set
 * of meta-commands (lines starting with {@code :}).
 */
@CommandLine.Command(
        name = "shell",
        description = "Interactive Cypher REPL against the running graph backend",
        mixinStandardHelpOptions = true
)
public class GraphShellCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = "--read-only",
            description = "Run all queries in read-only mode")
    private boolean readOnly;

    private InputStream input = System.in;
    private PrintStream out = System.out;
    private PrintStream err = System.err;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        return runRepl(client, new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
    }

    int runRepl(KompileHttpClient client, BufferedReader reader) {
        List<String> history = new ArrayList<>();
        Map<String, Object> sessionParams = new LinkedHashMap<>();
        out.println("kompile graph shell — :help for commands, :exit to quit");
        boolean interactive = (input == System.in);
        try {
            while (true) {
                if (interactive) out.print("kg> ");
                String line = reader.readLine();
                if (line == null) break;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.startsWith(":")) {
                    boolean shouldExit = handleMeta(trimmed, history, sessionParams, client, reader);
                    if (shouldExit) return 0;
                    continue;
                }

                history.add(trimmed);
                executeQuery(client, trimmed, sessionParams);
            }
        } catch (Exception e) {
            err.println("Shell error: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private boolean handleMeta(String line, List<String> history, Map<String, Object> sessionParams,
                               KompileHttpClient client, BufferedReader reader) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";
        switch (cmd) {
            case ":help" -> printHelp();
            case ":history" -> {
                for (int i = 0; i < history.size(); i++) out.println("  " + (i + 1) + "  " + history.get(i));
            }
            case ":params" -> handleParams(arg, sessionParams);
            case ":source" -> sourceFile(client, arg, sessionParams, history);
            case ":sysinfo" -> printSysInfo(client);
            case ":exit", ":quit" -> { return true; }
            default -> err.println("Unknown meta-command: " + cmd + " (try :help)");
        }
        return false;
    }

    private void handleParams(String arg, Map<String, Object> sessionParams) {
        if (arg.isBlank()) {
            if (sessionParams.isEmpty()) out.println("  (no session params)");
            else sessionParams.forEach((k, v) -> OutputFormatter.printKv(k, v));
            return;
        }
        if ("clear".equalsIgnoreCase(arg)) {
            sessionParams.clear();
            out.println("  Cleared session params.");
            return;
        }
        for (String token : arg.split(",")) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                err.println("  Invalid param assignment: " + token);
                continue;
            }
            String k = token.substring(0, eq).trim();
            String v = token.substring(eq + 1).trim();
            sessionParams.put(k, parseScalar(v));
        }
    }

    private void sourceFile(KompileHttpClient client, String arg, Map<String, Object> sessionParams,
                            List<String> history) {
        if (arg.isBlank()) {
            err.println("  Usage: :source <path>");
            return;
        }
        try {
            String contents = Files.readString(Path.of(arg), StandardCharsets.UTF_8);
            for (String stmt : contents.split(";")) {
                String s = stmt.trim();
                if (s.isEmpty() || s.startsWith("//")) continue;
                history.add(s);
                executeQuery(client, s, sessionParams);
            }
        } catch (Exception e) {
            err.println("  Error reading " + arg + ": " + e.getMessage());
        }
    }

    private void printSysInfo(KompileHttpClient client) {
        try {
            String body = client.getString("/api/graph/cypher/info");
            JsonNode n = client.getObjectMapper().readTree(body);
            n.fields().forEachRemaining(e -> OutputFormatter.printKv(e.getKey(), e.getValue()));
        } catch (Exception e) {
            err.println("  Error: " + e.getMessage());
        }
    }

    private void executeQuery(KompileHttpClient client, String cypher, Map<String, Object> sessionParams) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cypher", cypher);
            body.put("readOnly", readOnly);
            if (!sessionParams.isEmpty()) body.put("params", new LinkedHashMap<>(sessionParams));
            String response = client.postString("/api/graph/cypher/query", body);
            JsonNode result = client.getObjectMapper().readTree(response);
            renderResult(result);
        } catch (Exception e) {
            err.println("  Error: " + e.getMessage());
        }
    }

    private void renderResult(JsonNode result) {
        JsonNode columns = result.path("columns");
        JsonNode rows = result.path("rows");
        long elapsed = result.path("elapsedMs").asLong(0);
        if (!columns.isArray() || columns.isEmpty()) {
            out.println("  (empty result, " + elapsed + " ms)");
            return;
        }
        StringBuilder header = new StringBuilder("  ");
        for (JsonNode c : columns) header.append(c.asText()).append("  ");
        out.println(header.toString().trim());
        int shown = 0;
        for (JsonNode row : rows) {
            StringBuilder line = new StringBuilder("  ");
            for (JsonNode cell : row) line.append(cellToString(cell)).append("  ");
            out.println(line.toString().trim());
            if (++shown >= 50) {
                out.println("  ... (output truncated at 50 rows; use 'graph query' for full results)");
                break;
            }
        }
        out.println("  " + rows.size() + " row(s) in " + elapsed + " ms");
    }

    private static String cellToString(JsonNode cell) {
        if (cell == null || cell.isNull()) return "-";
        if (cell.isTextual()) return cell.asText();
        return cell.toString();
    }

    private static Object parseScalar(String v) {
        if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
        try { return Long.parseLong(v); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(v); } catch (NumberFormatException ignored) {}
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private void printHelp() {
        out.println("Meta-commands:");
        out.println("  :help              show this help");
        out.println("  :history           list previously-run statements");
        out.println("  :params            show session params");
        out.println("  :params k=v[,k=v]  set session params (applied to every query)");
        out.println("  :params clear      reset session params");
        out.println("  :source <file>     run a file of ;-separated Cypher");
        out.println("  :sysinfo           show server / database info");
        out.println("  :exit | :quit      leave the shell");
        out.println("Anything else is sent as a Cypher statement.");
    }

    // Test hooks
    void setStreams(InputStream in, PrintStream out, PrintStream err) {
        this.input = in;
        this.out = out;
        this.err = err;
    }
}
