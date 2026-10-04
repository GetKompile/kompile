/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.render.SyntaxHighlighter;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Objects;

/**
 * JSON form of a completed tool call for stream-json consumers such as the web chat. Built
 * from the {@link TerminalRenderer} row and detail model the terminal prints, so a browser
 * shows the same row, the same bounded body and the same highlight languages:
 *
 * <pre>{"displayName", "action"?, "title"?, "metadata"?, "preview"?, "error"?, "chart"?,
 *  "sections": [{"label", "diff"?, "note"?, "runs": [{"text", "file"?, "family"?}]}]}</pre>
 *
 * <p>{@code chart} is the tool's {@link ToolResult#CHART_METADATA} object, passed through as is
 * for the browser to draw; the terminal prints the same answer as text.</p>
 *
 * <p>Consecutive lines that share a highlight language form one run, so a browser highlighter
 * sees multi-line constructs (block comments, strings) whole; lines whose language was
 * inferred from the line itself stay one run each, as the terminal styles them, while their
 * unstyled neighbours still share one. {@code family}
 * is the terminal highlighter's family for {@code file}, a fallback for clients that do not
 * know the file's extension. Text is printable: no ANSI or control characters.</p>
 */
public final class ToolCallJson {

    private ToolCallJson() {}

    public static ObjectNode detail(ObjectMapper mapper, String toolName, String rawInput,
                                    ToolResult result) {
        ObjectNode detail = mapper.createObjectNode();
        if (result == null) return detail;
        // A stream-json client never saw the live output, so nothing counts as already shown.
        TerminalRenderer.ToolRow row = TerminalRenderer.toolRow(toolName, rawInput, result, false);
        detail.put("displayName", row.displayName());
        putText(detail, "action", row.action());
        putText(detail, "title", row.title());
        putText(detail, "metadata", row.metadata());
        putText(detail, "preview", row.preview());
        putText(detail, "error", row.errorPreview());
        putChart(mapper, detail, result);
        ArrayNode sections = detail.putArray("sections");
        for (TerminalRenderer.DetailSection section
                : TerminalRenderer.toolResultDetailSections(toolName, rawInput, result)) {
            ObjectNode node = sections.addObject().put("label", section.label());
            if (section.diff()) node.put("diff", true);
            if (section.truncated()) node.put("note", TerminalRenderer.DETAIL_TRUNCATED_NOTE);
            ArrayNode runs = node.putArray("runs");
            StringBuilder text = null;
            String runFile = null;
            for (TerminalRenderer.DetailLine line : section.lines()) {
                String file = styledFile(line.hint());
                if (text != null && Objects.equals(file, runFile)
                        && (file == null || !section.lineScoped())) {
                    text.append('\n').append(line.text());
                    continue;
                }
                if (text != null) addRun(runs, text.toString(), runFile);
                text = new StringBuilder(line.text());
                runFile = file;
            }
            if (text != null) addRun(runs, text.toString(), runFile);
        }
        return detail;
    }

    /**
     * The chart of a successful call. An in-process tool hands over a JSON node; a result rebuilt
     * from MCP structured content carries the same object as a map.
     */
    private static void putChart(ObjectMapper mapper, ObjectNode detail, ToolResult result) {
        Object chart = result.getMetadata().get(ToolResult.CHART_METADATA);
        if (chart == null || result.isError()) return;
        JsonNode node = mapper.valueToTree(chart);
        if (node.isObject()) detail.set("chart", node);
    }

    private static void addRun(ArrayNode runs, String text, String file) {
        ObjectNode run = runs.addObject().put("text", text);
        if (file != null) {
            run.put("file", file);
            run.put("family", family(file).name().toLowerCase(Locale.ROOT));
        }
    }

    /** The hint when the terminal would style with it, else null: unstyled lines carry no language. */
    private static String styledFile(String hint) {
        return family(hint) == SyntaxHighlighter.Family.NONE ? null : hint;
    }

    /** Same resolution order as {@link SyntaxHighlighter#highlight}: fence tag, then filename. */
    private static SyntaxHighlighter.Family family(String hint) {
        if (hint == null) return SyntaxHighlighter.Family.NONE;
        SyntaxHighlighter.Family family = SyntaxHighlighter.familyOf(hint);
        return family != SyntaxHighlighter.Family.NONE ? family : SyntaxHighlighter.familyForFilename(hint);
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) node.put(field, value);
    }
}
