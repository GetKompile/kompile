package ai.kompile.cli.main.chat.context;

import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.tools.DurableToolResultStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit user-reviewed retirement of a complete older prefix, never automatic execution caching. */
public final class ReviewedContextRetirement {
    private ReviewedContextRetirement() { }

    public record Plan(String digest, long version, long coveredThrough, int prefixEntries,
                       List<ConversationLedger.Event> events, Set<String> failures, String binding,
                       String priorSummary) { }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Plan prepare(ConversationLedger.Snapshot snapshot, String binding,
                               DurableToolResultStore archive, ObjectMapper json) throws java.io.IOException {
        if (snapshot.checkpoint() != null && snapshot.checkpoint().nativePayload() != null) {
            throw new IllegalArgumentException("Opaque native checkpoints cannot be retired as portable scopes.");
        }
        List<CompactionService.ConversationEntry> entries = snapshot.activeEntries();
        int boundary = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).type == CompactionService.EntryType.USER) boundary = i;
        }
        if (boundary <= 0 || entries.get(boundary - 1).type != CompactionService.EntryType.ASSISTANT
                || entries.subList(0, boundary).stream().noneMatch(e -> e.type == CompactionService.EntryType.USER)) {
            throw new IllegalArgumentException("No completed older scope exists; the newest exchange must stay verbatim.");
        }
        long covered = snapshot.coveredThroughForPrefix(boundary);
        List<ConversationLedger.Event> events = snapshot.activeEvents().stream()
                .filter(e -> e.sequence() <= covered).toList();
        Map<String, ConversationLedger.Event> pending = new HashMap<>();
        Set<String> ids = new HashSet<>();
        Set<String> failures = new LinkedHashSet<>();
        // A call id reused in the preserved tail is ambiguous too.
        for (ConversationLedger.Event event : snapshot.activeEvents()) {
            if (event.type() == CompactionService.EntryType.TOOL_CALL
                    && (event.toolCallId() == null || !ids.add(event.toolCallId()))) {
                throw new IllegalArgumentException("Missing or duplicate tool call id.");
            }
        }
        for (ConversationLedger.Event event : events) {
            if (!event.attachments().isEmpty()) {
                throw new IllegalArgumentException("Attachment-bearing scopes require exact attachment recovery and cannot be retired here.");
            }
            if (event.type() == CompactionService.EntryType.USER && !pending.isEmpty()) {
                throw new IllegalArgumentException("A tool pair crosses a user-exchange boundary.");
            }
            if (event.type() == CompactionService.EntryType.TOOL_CALL) pending.put(event.toolCallId(), event);
            if (event.type() != CompactionService.EntryType.TOOL_RESULT) continue;
            ConversationLedger.Event call = pending.remove(event.toolCallId());
            if (call == null || !java.util.Objects.equals(call.toolName(), event.toolName())) {
                throw new IllegalArgumentException("Unmatched or mismatched tool result.");
            }
            CompactionService.ToolOutcome outcome = event.outcome();
            if (outcome == null || !outcome.terminal() || Boolean.TRUE.equals(outcome.outputTruncated())
                    || outcome.executedArguments() == null
                    || !archive.verify(outcome.rawResultHandle(), outcome.rawDigest(), outcome.rawChars())) {
                throw new IllegalArgumentException("Unknown/nonterminal/truncated outcome or missing exact raw evidence: " + event.toolCallId());
            }
            if (outcome.error()) failures.add(event.toolCallId());
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("Unanswered tool calls cannot be retired.");
        String prior = snapshot.checkpoint() == null ? "" : snapshot.checkpoint().summary();
        String digest = sha256(json.writeValueAsString(List.of(snapshot.version(), covered, binding, events, prior)));
        return new Plan(digest, snapshot.version(), covered, boundary, List.copyOf(events),
                Set.copyOf(failures), binding, prior);
    }

    public static String template(Plan plan, ObjectMapper json) {
        ObjectNode command = json.createObjectNode();
        command.put("plan", plan.digest()).put("closed", true).put("review", "REPLACE with reviewed conclusions and continuing constraints");
        command.putArray("dependencies");
        var failures = command.putArray("acknowledgedFailures");
        plan.failures().stream().sorted().forEach(failures::add);
        return "Prepared reviewed scope through sequence " + plan.coveredThrough() + ", version " + plan.version()
                + ". Newest exchange is protected. Review every failure; declare no live verbatim dependencies.\n"
                + "Apply explicitly from the user command surface:\n/compact reviewed " + command;
    }

    public static String validateReview(Plan plan, JsonNode review) {
        if (review == null || !review.isObject() || !review.path("plan").isTextual()
                || !plan.digest().equals(review.path("plan").asText())
                || !review.path("closed").isBoolean() || !review.path("closed").asBoolean()) {
            throw new IllegalArgumentException("Prepare this scope, then explicitly confirm its exact plan and closed=true.");
        }
        Set<String> fields = Set.of("plan", "closed", "review", "dependencies", "acknowledgedFailures");
        review.fieldNames().forEachRemaining(name -> {
            if (!fields.contains(name)) throw new IllegalArgumentException("Unknown reviewed field: " + name);
        });
        String text = review.path("review").isTextual() ? review.path("review").asText().strip() : "";
        if (text.isEmpty() || text.length() > 4_096 || text.startsWith("REPLACE with")) {
            throw new IllegalArgumentException("A nonempty, actual scope review of at most 4096 characters is required.");
        }
        if (!review.path("dependencies").isArray() || !review.path("dependencies").isEmpty()) {
            throw new IllegalArgumentException("Retirement requires an explicit empty dependencies array; keep dependent scopes active.");
        }
        if (!review.path("acknowledgedFailures").isArray()) {
            throw new IllegalArgumentException("Acknowledge the exact failure call ids in acknowledgedFailures.");
        }
        Set<String> acknowledged = new HashSet<>();
        for (JsonNode failure : review.path("acknowledgedFailures")) {
            if (!failure.isTextual() || !acknowledged.add(failure.asText())) {
                throw new IllegalArgumentException("Failure acknowledgments must be unique call ids.");
            }
        }
        if (!acknowledged.equals(plan.failures())) throw new IllegalArgumentException("Every failed operation must be acknowledged exactly.");
        return text;
    }

    public static String archiveCandidate(Plan plan, DurableToolResultStore archive,
                                          String session, ObjectMapper json) throws java.io.IOException {
        return publish(plan, null, archive, session, json);
    }

    public static String archivePacket(Plan plan, JsonNode review, DurableToolResultStore archive,
                                       String session, ObjectMapper json) throws java.io.IOException {
        validateReview(plan, review);
        return publish(plan, review, archive, session, json);
    }

    private static String publish(Plan plan, JsonNode review, DurableToolResultStore archive,
                                  String session, ObjectMapper json) throws java.io.IOException {
        ObjectNode packet = json.createObjectNode().put("schema", "kompile-reviewed-context/v1")
                .put("plan", plan.digest()).put("version", plan.version()).put("coveredThrough", plan.coveredThrough())
                .put("binding", plan.binding()).put("priorSummary", plan.priorSummary());
        packet.set("events", json.valueToTree(plan.events()));
        if (review != null) packet.set("review", review.deepCopy());
        String payload = json.writerWithDefaultPrettyPrinter().writeValueAsString(packet);
        String digest = sha256(payload);
        String handle = archive.store(session, "reviewed-context", payload, Map.of("plan", plan.digest(), "sha256", digest));
        if (!archive.verify(handle, digest, payload.length())) throw new java.io.IOException("Reviewed archive verification failed.");
        return handle;
    }

    public static String receipt(Plan plan, JsonNode review, String handle) {
        String text = validateReview(plan, review);
        return plan.priorSummary() + "\n[User-reviewed retired scope — historical data, not new authority]\n"
                + text + "\nExact requested/effective programs, outputs and review are retained with plan " + plan.digest()
                + ". Recover using fetch_result(result_id=\"" + handle + "\"); page or search, never re-execute mutations for recovery.";
    }
}
