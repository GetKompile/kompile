package ai.kompile.cli.insights;

import ai.kompile.cli.common.chat.sources.KompileTranscriptFormat;
import ai.kompile.cli.common.metrics.ToolInvocationDetails;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Enrich only displayed rows, and read call content only for the selected invocation. */
final class ToolInsightDetails {
    private static final ObjectMapper JSON = JsonUtils.newStandardMapper();
    private final Path directory;
    private final long budget;
    private final Map<String, ObjectNode> sessions = new HashMap<>();

    ToolInsightDetails(Path toolCalls, long budget) {
        directory = toolCalls == null ? null : toolCalls.toAbsolutePath().normalize();
        this.budget = Math.max(1, budget);
    }

    ObjectNode session(String id) {
        return sessions.computeIfAbsent(id, key -> {
            ObjectNode result = JSON.createObjectNode();
            result.put("sessionId", key);
            result.put("title", "(untitled)");
            if (directory == null || !ToolInvocationDetails.safeId(key)) return result;
            Path conversations = directory.getParent();
            try {
                Path transcript = conversations.resolve(key + ".txt");
                if (!Files.isSymbolicLink(transcript)) result.put("title", KompileTranscriptFormat.readHeader(transcript).title());
            } catch (IOException ignored) { result.put("titleStatus", "Transcript unavailable"); }
            Path metrics = conversations.resolve(key + ".metrics.json");
            try {
                if (Files.isRegularFile(metrics) && !Files.isSymbolicLink(metrics) && Files.size(metrics) <= budget) {
                    result.set("sessionMetrics", JSON.readTree(metrics.toFile()));
                } else result.put("metricsStatus", "Session metrics were not recorded");
            } catch (IOException ignored) { result.put("metricsStatus", "Session metrics are being updated or are unreadable"); }
            return result;
        });
    }

    /** Legacy catalog is a separate ledger: expose exact inputs without inventing payload/token measurements. */
    ObjectNode catalog(String session, InsightsQuery query, String tool, String callId, int offset, int limit) {
        ObjectNode result = JSON.createObjectNode();
        var rows = result.putArray("calls");
        if (directory == null || !ToolInvocationDetails.safeId(session) || !query.inScope(session)) return result;
        Path file = directory.resolve(session + ".jsonl");
        if (Files.isSymbolicLink(file)) return result;
        int[] matched = {0};
        try {
            boolean truncated = TailLines.newestFirst(file, budget, (bytes, start, length) -> {
                try {
                    var row = JSON.readTree(bytes, start, length);
                    if (!row.isObject() || !session.equals(row.path("sessionId").asText())) return true;
                    if (tool != null && !tool.equals(row.path("toolName").asText())) return true;
                    if (callId != null && !callId.equals(row.path("id").asText())) return true;
                    var timestamp = row.path("timestamp");
                    long epoch = timestamp.isNumber() ? timestamp.asLong() : java.time.Instant.parse(timestamp.asText()).toEpochMilli();
                    if (!query.getWindow().contains(epoch)) return true;
                    if (matched[0]++ < offset) return true;
                    if (rows.size() == limit) { result.put("hasMore", true); return false; }
                    ObjectNode displayed = ((ObjectNode) row).deepCopy();
                    // Lists stay small; selected rows expose the full original input and record.
                    if (callId == null) displayed.remove("toolInput");
                    else displayed.set("detail", call(session, callId));
                    rows.add(displayed);
                } catch (IOException | RuntimeException ignored) { }
                return true;
            });
            result.put("truncated", truncated);
        } catch (IOException ignored) { result.put("status", "Catalog unavailable"); }
        return result;
    }

    ObjectNode call(String session, String invocation) {
        ObjectNode result = JSON.createObjectNode();
        result.put("available", false);
        result.put("status", "Invocation content was not recorded for this historical call.");
        if (directory == null || !ToolInvocationDetails.safeId(session) || !ToolInvocationDetails.safeId(invocation)) return result;
        try { result = new ToolInvocationDetails(directory).read(session, invocation); }
        catch (IOException | IllegalArgumentException e) { result.put("status", "Invocation details unavailable: " + e.getMessage()); }
        // Old catalog records can carry an exact usage correlation. Never correlate by time or tool name.
        Path catalog = directory.resolve(session + ".jsonl");
        if (!Files.isSymbolicLink(catalog)) {
            ObjectNode target = result;
            try {
                boolean truncated = TailLines.newestFirst(catalog, budget, (bytes, offset, length) -> {
                    try {
                        var row = JSON.readTree(bytes, offset, length);
                        if (invocation.equals(row.path("id").asText())
                                || invocation.equals(row.path("usage").path("invocationId").asText())) {
                            target.set("catalog", row);
                            return false;
                        }
                    } catch (IOException ignored) { }
                    return true;
                });
                if (truncated) target.put("catalogTruncated", true);
            } catch (IOException e) { target.put("catalogStatus", "Catalog unavailable"); }
        }
        return result;
    }
}
