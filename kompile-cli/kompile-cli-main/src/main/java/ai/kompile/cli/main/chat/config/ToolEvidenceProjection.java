package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.tools.ToolResultStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request-only evidence projection. Without an archive, deduplication always
 * leaves the first complete identical result in the SAME request. With an explicitly
 * supplied session archive, every replacement requires fresh exact file verification.
 * A remembered decision is never treated as retention proof.
 * Calls, arguments, results and their metadata remain in their original positions.
 *
 * Inspired by HELIX's tool-result-aging and cache-stable-aging policies; batching
 * avoids invalidating the provider's cached prefix for every small new duplicate.
 */
final class ToolEvidenceProjection {
    private static final int MIN_CHARS = 1_024;
    private static final int MAX_DECISIONS = 4_096;
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "(?i)\\b(error|exception|failed|failure|fatal|denied|cancelled|canceled|vulnerability|exploit)\\b"
                    + "|\\bCVE-[0-9]{4}-|-----BEGIN [^\\r\\n]*PRIVATE KEY-----");

    private final int frontier;
    private final int batchResults;
    private final long batchChars;
    // Hashes only, bounded and owned by a single DirectLlmClient. Every hit is revalidated.
    private final Map<String, Boolean> decisions = new LinkedHashMap<>();
    // OpenAI does not carry isError on the wire. Preserve known errors out of band.
    private final Set<String> errorCalls = new HashSet<>();
    private boolean errorCapacityExceeded;

    ToolEvidenceProjection() { this(8, 8, 16_384); }

    ToolEvidenceProjection(int frontier, int batchResults, long batchChars) {
        if (frontier < 1 || batchResults < 1 || batchChars < 1) {
            throw new IllegalArgumentException("Invalid tool evidence projection limits");
        }
        this.frontier = frontier;
        this.batchResults = batchResults;
        this.batchChars = batchChars;
    }

    synchronized void clear() {
        decisions.clear();
        errorCalls.clear();
        errorCapacityExceeded = false;
    }

    synchronized void protectError(String callId) {
        if (callId == null) return;
        String key = sha256(callId.trim());
        if (errorCalls.contains(key)) return;
        if (errorCalls.size() >= MAX_DECISIONS) errorCapacityExceeded = true;
        else errorCalls.add(key);
    }

    synchronized ArrayNode project(ArrayNode input) { return project(input, null); }

    synchronized ArrayNode project(ArrayNode input, ToolResultStore archive) {
        // Never evict error knowledge and then mistake an error for a success.
        if (errorCapacityExceeded) return input;
        Map<String, Call> calls = new HashMap<>();
        Set<String> ambiguousCalls = new HashSet<>();
        List<Slot> results = new ArrayList<>();
        int lastModelAction = -1;
        int checkpoint = -1;
        for (int i = 0; i < input.size(); i++) {
            JsonNode item = input.get(i);
            String role = item.path("role").asText();
            String type = item.path("type").asText();
            if ("compaction".equals(type)) checkpoint = i;
            if ("assistant".equals(role) || "function_call".equals(type)) lastModelAction = i;
            if ("function_call".equals(type)) {
                addCall(calls, ambiguousCalls, item.path("call_id"), item.path("name"),
                        item.path("arguments"), i, false);
            } else if ("assistant".equals(role)) {
                for (JsonNode call : item.path("tool_calls")) {
                    if (!"function".equals(call.path("type").asText())) continue;
                    addCall(calls, ambiguousCalls, call.path("id"), call.path("function").path("name"),
                            call.path("function").path("arguments"), i, false);
                }
                for (JsonNode part : item.path("content")) {
                    String kind = part.path("type").asText();
                    if ("compaction".equals(kind)) checkpoint = i;
                    if ("tool_use".equals(kind) || "toolCall".equals(kind)) {
                        addCall(calls, ambiguousCalls, part.path("id"), part.path("name"),
                                part.path("tool_use".equals(kind) ? "input" : "arguments"), i, true);
                    }
                }
            }
            if ("tool".equals(role)) {
                results.add(slot(item, i, -1, "content", "tool_call_id"));
            } else if ("function_call_output".equals(type) || "custom_tool_call_output".equals(type)) {
                // Custom calls are unsupported for replacement, but still occupy the frontier.
                results.add(slot(item, i, -1, "output", "call_id"));
            } else if ("toolResult".equals(role)) {
                JsonNode parts = item.path("content");
                // Mixed or multimodal outputs are never flattened.
                if (parts.isArray() && parts.size() == 1
                        && "text".equals(parts.get(0).path("type").asText())) {
                    results.add(new Slot(i, 0, "text", item.path("toolCallId").asText(),
                            text(parts.get(0).path("text")), isError(item)));
                } else {
                    results.add(new Slot(i, -1, "content", "", null, true));
                }
            } else if ("user".equals(role) && item.path("content").isArray()) {
                for (int j = 0; j < item.path("content").size(); j++) {
                    JsonNode part = item.path("content").get(j);
                    if ("tool_result".equals(part.path("type").asText())) {
                        results.add(slot(part, i, j, "content", "tool_use_id"));
                    }
                }
            }
        }

        Map<String, Integer> resultCounts = new HashMap<>();
        for (Slot result : results) resultCounts.merge(result.callId(), 1, Integer::sum);
        Map<Evidence, Slot> originals = new HashMap<>();
        List<Replacement> candidates = new ArrayList<>();
        Set<String> eligible = new HashSet<>();
        int fresh = 0;
        long savings = 0;
        for (int i = 0; i < results.size(); i++) {
            Slot result = results.get(i);
            Call call = calls.get(result.callId());
            if (call == null || ambiguousCalls.contains(result.callId())
                    || call.index() <= checkpoint || result.index() <= checkpoint
                    || resultCounts.get(result.callId()) != 1 || call.index() >= result.index()
                    || result.error() || (!errorCalls.isEmpty() && errorCalls.contains(sha256(result.callId())))
                    || result.value() == null || result.value().length() < MIN_CHARS
                    || DIAGNOSTIC.matcher(result.value()).find()) continue;
            Evidence evidence = new Evidence(call.name(), call.arguments(), result.value());
            Slot original = originals.putIfAbsent(evidence, result);
            if ((archive == null && original == null)
                    || i >= results.size() - frontier || result.index() >= lastModelAction) continue;
            String digest = sha256(result.value());
            String receipt = archive == null
                    ? "[Exact duplicate tool output: " + result.value().length()
                        + " characters; sha256:" + digest + "]\n"
                        + "Byte-identical output for the same tool and arguments remains in earlier call \""
                        + original.callId() + "\" in this request. This call still executed; only its repeated body is omitted."
                    : archive.projectHistoricalResult(result.callId(), call.name(), result.value());
            if (receipt == null || receipt.length() >= result.value().length()) continue;
            // Archive-backed mode never emits pointers to inline originals it may retire.
            String source = archive == null ? "inline:" + original.callId() : "archive:" + sha256(receipt);
            String key = sha256(call.name() + "\0" + call.arguments() + "\0" + result.callId()
                    + "\0" + source + "\0" + digest);
            boolean committed = decisions.containsKey(key);
            eligible.add(key);
            candidates.add(new Replacement(result, receipt, key, committed));
            if (!committed) {
                fresh++;
                savings += result.value().length() - receipt.length();
            }
        }
        // Reclaim stale decisions, never an active commitment. At capacity, leave
        // new candidates raw so count and stream cannot disagree through eviction.
        decisions.keySet().retainAll(eligible);
        boolean commitBatch = fresh >= batchResults || savings >= batchChars;
        ArrayNode projected = input;
        for (Replacement candidate : candidates) {
            if (!candidate.committed() && (!commitBatch || decisions.size() >= MAX_DECISIONS)) continue;
            if (projected == input) projected = input.deepCopy();
            Slot slot = candidate.slot();
            ObjectNode target = (ObjectNode) projected.get(slot.index());
            if (slot.part() >= 0) target = (ObjectNode) target.path("content").get(slot.part());
            target.put(slot.field(), candidate.receipt());
            decisions.put(candidate.key(), true);
        }
        return projected;
    }

    private static Slot slot(JsonNode item, int index, int part, String field, String id) {
        return new Slot(index, part, field, item.path(id).asText(), text(item.path(field)), isError(item));
    }

    private static boolean isError(JsonNode item) {
        return item.path("is_error").asBoolean() || item.path("isError").asBoolean();
    }

    private static String text(JsonNode value) { return value.isTextual() ? value.textValue() : null; }

    private static void addCall(Map<String, Call> calls, Set<String> ambiguous, JsonNode id,
                                JsonNode name, JsonNode arguments, int index, boolean objectArguments) {
        if (!id.isTextual() || !id.asText().matches("[A-Za-z0-9_.:-]{1,256}")) return;
        if (calls.containsKey(id.asText())) {
            ambiguous.add(id.asText());
            return;
        }
        // Even an invalid declaration occupies its id: a later duplicate cannot repair it.
        calls.put(id.asText(), null);
        if (!name.isTextual() || name.asText().isBlank()
                || (objectArguments ? !arguments.isObject() : !arguments.isTextual())) return;
        // JSON encoding retains the representation type as well as the exact value.
        calls.put(id.asText(), new Call(name.asText(), arguments.toString(), index));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Call(String name, String arguments, int index) { }
    private record Evidence(String name, String arguments, String value) { }
    private record Slot(int index, int part, String field, String callId, String value, boolean error) { }
    private record Replacement(Slot slot, String receipt, String key, boolean committed) { }
}
