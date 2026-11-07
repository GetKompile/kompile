/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.McpToolResultSerializer;
import ai.kompile.cli.main.chat.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, display-only child conversation. Typed provider events, not parsed log
 * labels, identify prose, reasoning and tools. The browser uses the same renderers
 * and ChatDisplayBlock projection as the main transcript; the raw log stays intact.
 */
public final class SubagentTranscript {
    private static final int MAX_PARTS = 128;
    private static final int MAX_CHARS = ChatDisplayBlock.MAX_SECTION_CHARS;
    private static final int MAX_PART_CHARS = ChatDisplayBlock.MAX_SECTION_CHARS;
    private final List<Part> parts = new ArrayList<>();
    private long sequence;
    private boolean omitted;
    private boolean finished;

    public synchronized void text(String kind, String text) {
        if (finished || text == null || text.isEmpty()) return;
        Part last = parts.isEmpty() ? null : parts.get(parts.size() - 1);
        if (last == null || !last.kind.equals(kind) || !last.running) {
            endText();
            last = new Part("child:" + ++sequence, kind, "", "");
            last.error = kind.equals("error");
            parts.add(last);
        }
        last.content = bounded(last.content + TerminalRenderer.printableToolOutput(text));
        last.cached = null;
        trim();
    }

    public synchronized void message(String kind, String text) {
        endText();
        text(kind, text);
        endText();
    }

    public synchronized void toolStart(String id, String name, String input) {
        if (finished || tool(id) != null) return;
        endText();
        Part part = new Part("child:" + ++sequence, "tool", id, name);
        part.input = bounded(input);
        parts.add(part);
        trim();
    }

    public synchronized void toolInput(String id, String input) {
        Part part = tool(id);
        if (finished || part == null) return;
        part.input = bounded(input);
        part.cached = null;
        trim();
    }

    public synchronized void toolOutput(String id, String output) {
        Part part = tool(id);
        if (finished || part == null || output == null) return;
        part.content = bounded(part.content + output);
        part.cached = null;
        trim();
    }

    public synchronized void toolComplete(String id, String output, boolean error) {
        Part part = tool(id);
        if (finished || part == null) return;
        part.result = bounded(output);
        part.error = error;
        part.running = false;
        part.cached = null;
        trim();
    }

    public synchronized void finish() {
        finished = true;
        for (Part part : parts) {
            part.running = false;
            part.cached = null;
        }
    }

    /**
     * Accept writes again for an interactive follow-up turn on a retained child.
     * Mirrors the status bar's {@code reactivate()} semantics: the settled parts
     * stay settled, and the next event starts a fresh running part.
     */
    public synchronized void reactivate() {
        finished = false;
    }

    /** Immutable snapshots, cached per changed part and ANSI mode, never parent cards. */
    public synchronized List<ChatDisplayBlock> blocks(TerminalRenderer renderer) {
        List<ChatDisplayBlock> result = new ArrayList<>();
        if (omitted) result.add(ChatDisplayBlock.text("child:omitted", "metadata", "… (older child transcript omitted)"));
        for (Part part : parts) {
            if (part.cached == null || part.cachedAnsi != renderer.isAnsiEnabled()) {
                part.cached = render(part, renderer);
                part.cachedAnsi = renderer.isAnsiEnabled();
            }
            result.add(part.cached);
        }
        return List.copyOf(result);
    }

    /** Raw typed presentation data, never pre-rendered ANSI or parent conversation state. */
    public synchronized void writeSnapshot(Path file) throws IOException {
        var mapper = JsonUtils.standardMapper();
        var root = mapper.createObjectNode().put("version", 1).put("omitted", omitted)
                .put("finished", finished).put("sequence", sequence);
        var entries = root.putArray("parts");
        for (Part part : parts) {
            var entry = entries.addObject().put("key", part.key).put("kind", part.kind)
                    .put("id", part.id).put("name", part.name).put("content", part.content)
                    .put("input", part.input).put("running", part.running).put("error", part.error);
            if (part.result != null) entry.put("result", part.result);
        }
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            mapper.writeValue(pending.toFile(), root);
            try {
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    /** Bounded sidecar import; all content still passes the ordinary safe renderers. */
    public static SubagentTranscript readSnapshot(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(4_000_001);
        }
        if (bytes.length > 4_000_000) throw new IOException("Child transcript snapshot exceeds display limit");
        var root = JsonUtils.standardMapper().readTree(bytes);
        if (root == null || root.path("version").asInt() != 1 || !root.path("parts").isArray()
                || root.path("parts").size() > MAX_PARTS) throw new IOException("Invalid child transcript snapshot");
        var transcript = new SubagentTranscript();
        transcript.omitted = root.path("omitted").asBoolean();
        transcript.finished = root.path("finished").asBoolean();
        transcript.sequence = root.path("sequence").asLong();
        for (var entry : root.path("parts")) {
            String kind = entry.path("kind").asText();
            if (!List.of("assistant", "thinking", "user", "tool", "error", "metadata").contains(kind)) {
                throw new IOException("Invalid child transcript part kind");
            }
            var part = new Part(ChatDisplayBlock.boundedPlainText(entry.path("key").asText(), 1_000),
                    kind, entry.path("id").asText(), entry.path("name").asText());
            part.content = bounded(entry.path("content").asText());
            part.input = bounded(entry.path("input").asText());
            if (entry.hasNonNull("result")) part.result = bounded(entry.path("result").asText());
            part.running = !transcript.finished && entry.path("running").asBoolean();
            part.error = entry.path("error").asBoolean();
            transcript.parts.add(part);
            transcript.trim();
        }
        return transcript;
    }

    private void endText() {
        if (parts.isEmpty()) return;
        Part last = parts.get(parts.size() - 1);
        if (!last.kind.equals("tool")) {
            last.running = false;
            last.cached = null;
        }
    }

    private Part tool(String id) {
        for (Part part : parts) if (part.kind.equals("tool") && part.id.equals(id)) return part;
        return null;
    }

    private void trim() {
        int size = parts.stream().mapToInt(Part::size).sum();
        while (parts.size() > 1 && (parts.size() > MAX_PARTS || size > MAX_CHARS)) {
            size -= parts.remove(0).size();
            omitted = true;
        }
    }

    private static String bounded(String value) {
        return ChatDisplayBlock.boundedPlainText(value, MAX_PART_CHARS);
    }

    private static ChatDisplayBlock render(Part part, TerminalRenderer renderer) {
        if (part.kind.equals("tool")) return renderTool(part, renderer);
        List<String> lines = new ArrayList<>();
        if (part.kind.equals("assistant")) {
            var markdown = new MarkdownPreview(new AsciiRenderer(renderer), lines);
            markdown.accept(part.content);
            if (part.running) {
                String pending = markdown.preview();
                if (!pending.isEmpty()) lines.add(pending);
            } else markdown.flush();
        } else if (part.kind.equals("thinking")) {
            var thinking = new ThinkingStreamRenderer(lines::add, renderer);
            thinking.accept(part.content);
            if (part.running) {
                String pending = thinking.pendingLinePreview();
                if (!pending.isEmpty()) lines.add(pending);
            } else thinking.flush();
        } else if (part.kind.equals("user")) {
            lines.add(renderer.cyan("> ") + part.content);
        } else {
            lines.add(part.error ? renderer.red(part.content) : renderer.dim(part.content));
        }
        String display = String.join("\n", lines);
        return new ChatDisplayBlock(part.key, part.kind, "", "", display,
                List.of(new ChatDisplayBlock.Section("body", part.kind, "", part.content, display)),
                part.error, part.running);
    }

    private static ChatDisplayBlock renderTool(Part part, TerminalRenderer renderer) {
        ToolResult result = null;
        if (part.result != null) {
            if (part.name.startsWith("mcp__")) result = McpToolResultSerializer.fromStructuredContentText(
                    JsonUtils.standardMapper(), part.result, part.error);
            if (result == null) result = part.error ? ToolResult.error(part.result) : ToolResult.success(part.result);
        }
        String header = result == null ? renderer.renderToolCallStart(part.name, part.input)
                : renderer.renderToolCallSummary(part.name, part.input, result);
        StringBuilder classic = new StringBuilder(header);
        if (!part.content.isBlank()) classic.append('\n').append(renderer.renderToolOutput(part.content));
        if (result != null) {
            String detail = renderer.renderToolResultDetail(part.name, part.input, result);
            if (!detail.isBlank()) classic.append('\n').append(detail);
        }
        return new ChatDisplayBlock(part.key, TerminalRenderer.isTodoTool(part.name) ? "todos" : "tool",
                part.name, header, classic.toString(), renderer.renderToolDisplaySections(
                        part.name, part.input, result, part.content, ChatDisplayBlock.MAX_SECTION_CHARS),
                part.error, part.running);
    }

    private static final class MarkdownPreview extends StreamingMarkdownRenderer {
        MarkdownPreview(AsciiRenderer renderer, List<String> lines) { super(renderer, lines::add); }
        String preview() { return pendingLinePreview(); }
    }

    private static final class Part {
        final String key;
        final String kind;
        final String id;
        final String name;
        String content = "";
        String input = "";
        String result;
        boolean running = true;
        boolean error;
        ChatDisplayBlock cached;
        boolean cachedAnsi;

        Part(String key, String kind, String id, String name) {
            this.key = key;
            this.kind = kind;
            this.id = ChatDisplayBlock.boundedPlainText(id, 1_000);
            this.name = ChatDisplayBlock.boundedPlainText(name, 1_000);
        }

        int size() { return content.length() + input.length() + (result == null ? 0 : result.length()) + id.length() + name.length(); }
    }
}
