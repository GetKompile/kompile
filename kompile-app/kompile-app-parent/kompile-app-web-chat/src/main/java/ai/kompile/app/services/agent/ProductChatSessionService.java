package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.cli.common.ProductChatPolicy;
import ai.kompile.cli.common.WebChatContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Immutable operator-selected product binding; no browser configuration or legacy fallback. */
@Service
public class ProductChatSessionService implements AutoCloseable {
    private final ObjectMapper mapper;
    private final ProductChatPolicy policy;
    private final String sessionId;
    private KompileCliSessionWorker worker;
    private final KompileCliSessionWorker.ReceiptLedger receipts = new KompileCliSessionWorker.ReceiptLedger();

    @Autowired
    public ProductChatSessionService(ObjectMapper mapper) throws IOException {
        this(mapper, configured());
    }

    ProductChatSessionService(ObjectMapper mapper, ProductChatPolicy policy) throws IOException {
        this.mapper = mapper; this.policy = policy;
        this.sessionId = policy == null ? null : persistentSessionId(policy.workspace());
    }

    private static ProductChatPolicy configured() throws IOException {
        if (ProductChatPolicy.configuredPath().isEmpty()) return null;
        if (WebChatContext.workspace() || WebChatContext.globalConfig() || WebChatContext.workflow() != null)
            throw new IOException("Product chat requires a fixed project workspace without a global/workflow handoff");
        Path root = WebChatContext.workingDirectory();
        if (root == null) root = Path.of("").toRealPath();
        // Invalid selection fails startup rather than re-enabling the generic browser agent.
        return ProductChatPolicy.configured(root).orElseThrow();
    }

    public boolean enabled() { return policy != null; }
    public ProductChatPolicy policy() { requireEnabled(); return policy; }
    public String sessionId() { requireEnabled(); return sessionId; }

    public synchronized Map<String, Object> metadata() {
        if (!enabled()) return Map.of("enabled", false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true); result.put("productId", policy.id()); result.put("title", policy.title());
        result.put("workingDirectory", policy.workspace().toString()); result.put("agentName", "role:" + policy.role());
        result.put("chat", Map.of("id", sessionId, "name", policy.title() + " play"));
        result.put("panelsUrl", "/api/agents/chat/product/assets/index.html");
        result.put("domainBaseUrl", "/api/agents/chat/product/domain");
        result.put("status", worker == null ? "closed" : worker.status());
        if (worker != null) result.put("generation", worker.generation());
        return result;
    }

    public synchronized Map<String, Object> openSession() {
        requireEnabled();
        if (worker != null && worker.quarantined() && !worker.status().equals("closed")) throw conflict("Product mutation outcome is unknown; explicitly close the session before further work");
        if (worker != null && !worker.exited()) {
            if (Set.of("idle", "busy", "settling", "opening").contains(worker.status())) return metadata();
            throw conflict("Previous product worker has not exited");
        }
        if (worker != null && !worker.status().equals("closed"))
            throw conflict("Close the failed product generation before opening another");
        worker = new KompileCliSessionWorker(mapper, policy, sessionId, receipts);
        try { worker.open(); return metadata(); }
        catch (IOException | IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Product worker unavailable; no legacy fallback");
        }
    }

    public synchronized Map<String, Object> closeSession() {
        requireEnabled(); if (worker != null) worker.close(); return metadata();
    }

    public synchronized SseEmitter stream(AgentChatRequest request) {
        validateRequest(request);
        SseEmitter emitter = new SseEmitter(0L);
        try { ownedWorker().submit(request.getTurnId(), request.getMessage(), emitter); return emitter; }
        catch (IllegalArgumentException invalid) { throw badRequest(invalid.getMessage()); }
        catch (IllegalStateException busy) { throw conflict(busy.getMessage()); }
        catch (IOException unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Product turn transport unavailable; outcome may be unknown"); }
    }

    public Map<String, Object> activeRun(String requestedSession, String directory) {
        validateIdentity(requestedSession, directory, null);
        synchronized (this) { return worker == null ? Map.of() : worker.activeRun(); }
    }

    public SseEmitter reconnect(String runId, long after) {
        SseEmitter emitter = new SseEmitter(0L);
        try { ownedWorker().reconnect(runId, after, emitter); return emitter; }
        catch (IllegalArgumentException invalid) { throw badRequest(invalid.getMessage()); }
        catch (IllegalStateException gone) { throw new ResponseStatusException(HttpStatus.GONE, "Product turn replay unavailable; never resubmit a mutation to reconnect"); }
    }

    public Map<String, Object> cancel(String runId) {
        try {
            boolean cancelled = ownedWorker().cancel(runId);
            return Map.of("processId", runId, "cancelled", cancelled, "rollback", false);
        } catch (IllegalArgumentException invalid) { throw badRequest(invalid.getMessage()); }
        catch (IllegalStateException full) { throw conflict(full.getMessage()); }
    }

    public Map<String, Object> control(String runId, JsonNode frame) {
        // Generic run control remains cancellation-only. Content decisions have a separate strict owner-routed endpoint.
        if (frame == null || !frame.isObject() || frame.size() != 1 || !frame.path("action").asText().equals("cancel"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Product browser controls are read-only except turn cancel");
        return Map.of("accepted", cancel(runId).get("cancelled"), "rollback", false);
    }

    public synchronized JsonNode readCapability(String capability) {
        try { return ownedWorker().readCapability(capability); }
        catch (IllegalStateException busy) { throw conflict("Product worker busy; retry the named read after the turn"); }
        catch (IllegalArgumentException refused) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Product read capability not enabled"); }
        catch (IOException unavailable) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Product capability read unavailable"); }
    }

    public JsonNode pendingApprovals() {
        try { return ownedWorker().pendingApprovals(); }
        catch (IllegalStateException busy) { throw conflict("Product approval metadata unavailable; refresh after the turn"); }
        catch (IOException unavailable) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Product approval metadata unavailable"); }
    }

    public JsonNode resolveApproval(String approvalId, JsonNode body) {
        validateApprovalBody(body);
        try {
            ai.kompile.cli.common.WebSessionProtocol.approvalId(approvalId);
            JsonNode result = ownedWorker().resolveApproval(approvalId, body.path("allow").booleanValue());
            if (!result.path("ok").isBoolean() || !result.path("ok").booleanValue())
                throw conflict("Approval is no longer pending in this session");
            return result;
        } catch (IllegalArgumentException invalid) { throw badRequest("Invalid approval identifier"); }
        catch (IllegalStateException busy) { throw conflict("Product approval unavailable; refresh pending approvals"); }
        catch (IOException unavailable) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Product approval unavailable; do not retry automatically"); }
    }

    public static void validateApprovalBody(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("allow").isBoolean())
            throw badRequest("Content approval requires exactly one boolean allow field");
    }

    @FunctionalInterface
    public interface DomainRelay<T> {
        T relay(JsonNode connection, KompileCliSessionWorker.ExternalOperation operation);
    }

    /** Capture the MCP connection and pin that same worker through the bounded HTTP relay.
     * stream() holds this SAME admission monitor through submit, not merely worker lookup.
     * The event reader never needs this monitor, including during the MCP read/idle barrier.
     */
    public synchronized <T> T withDomainConnection(DomainRelay<T> relay) {
        KompileCliSessionWorker owner = ownedWorker();
        JsonNode connection = readCapability("connection");
        try (KompileCliSessionWorker.ExternalOperation operation = owner.claimExternalOperation()) {
            return relay.relay(connection, operation);
        } catch (IllegalStateException busy) { throw conflict(busy.getMessage()); }
        catch (IOException unavailable) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Product domain admission unavailable"); }
    }

    private synchronized KompileCliSessionWorker ownedWorker() {
        requireEnabled();
        if (worker == null) throw conflict("Open the product session before submitting a turn");
        if (worker.quarantined()) throw conflict("Product mutation outcome is unknown; explicitly close the session before further work");
        return worker;
    }

    public void validateIdentity(String id, String directory, String agent) {
        requireEnabled();
        if (!sessionId.equals(id)) throw badRequest("Only the server-owned product conversation is allowed");
        if (directory != null && !directory.equals(policy.workspace().toString())) throw badRequest("Product workspace is fixed");
        if (agent != null && !agent.equals("role:" + policy.role())) throw badRequest("Product role is fixed");
    }

    /** Validate raw JSON before DTO defaults/unknown-field handling can hide a browser escape. */
    public void validateBody(JsonNode body) {
        if (body == null || !body.isObject()) throw badRequest("Product turn must be an object");
        Set<String> fields = Set.of("message", "sessionId", "turnId", "agentName", "workingDirectory");
        body.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw badRequest("Product turn contains a field that is not allowed"); });
        for (String field : List.of("message", "sessionId", "turnId"))
            if (!body.path(field).isTextual()) throw badRequest("Missing product turn field: " + field);
        for (String field : List.of("agentName", "workingDirectory"))
            if (body.has(field) && !body.get(field).isTextual()) throw badRequest("Invalid product identity field");
        validateIdentity(body.path("sessionId").asText(), body.has("workingDirectory") ? body.get("workingDirectory").asText() : null,
                body.has("agentName") ? body.get("agentName").asText() : null);
        try { KompileCliSessionWorker.requireId(body.path("turnId").asText()); }
        catch (IllegalArgumentException invalid) { throw badRequest(invalid.getMessage()); }
    }

    private void validateRequest(AgentChatRequest request) {
        if (request == null) throw badRequest("Product turn must be an object");
        validateIdentity(request.getSessionId(), request.getWorkingDirectory(), request.getAgentName());
        try { KompileCliSessionWorker.requireId(request.getTurnId()); }
        catch (IllegalArgumentException invalid) { throw badRequest(invalid.getMessage()); }
        if (request.isSkipPermissions() || request.getSystemPromptOverride() != null
                || request.getProvisionedAgentId() != null || request.getExternalConversationKey() != null
                || request.getRunId() != null || request.getFolderId() != null || request.getFactSheetId() != null
                || request.getAgentArgs() != null && !request.getAgentArgs().isEmpty()
                || request.getChatHistory() != null && !request.getChatHistory().isEmpty()
                || request.getAttachments() != null && !request.getAttachments().isEmpty()
                || request.isEnableRag() || request.isEnableGraphRag())
            throw badRequest("Product turn cannot override the operator-selected harness");
    }

    private void requireEnabled() { if (!enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product chat is not enabled"); }
    private static ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }
    @PreDestroy @Override public synchronized void close() { if (worker != null) worker.close(); }

    /** A private workspace-local identity, not a global folder registration or browser-selected path. */
    static String persistentSessionId(Path workspace) throws IOException {
        Path directory = KompileCliSessionWorker.privateDirectory(workspace);
        Path file = directory.resolve("product-chat.session");
        Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (FileChannel channel = FileChannel.open(file, options,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Product identity is locked");
            var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
                throw new IOException("Product identity file must be private to its owner");
            if (channel.size() == 0) {
                String id = UUID.randomUUID().toString(); ByteBuffer bytes = StandardCharsets.UTF_8.encode(id + "\n");
                while (bytes.hasRemaining()) channel.write(bytes); channel.force(true); return id;
            }
            if (channel.size() > 80) throw new IOException("Invalid product identity");
            ByteBuffer bytes = ByteBuffer.allocate(80);
            while (channel.read(bytes) > 0) { }
            bytes.flip(); String id = StandardCharsets.UTF_8.decode(bytes).toString().strip();
            try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException invalid) { throw new IOException("Invalid product identity"); }
            return id;
        } catch (OverlappingFileLockException busy) { throw new IOException("Product identity is locked"); }
    }
}
