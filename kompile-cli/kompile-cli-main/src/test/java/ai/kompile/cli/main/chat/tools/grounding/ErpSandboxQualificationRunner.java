package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.auth.source.ErpConnectionCredentials;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.KnowledgeSearchCliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.source.erp.ErpSourceConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Test-only runner. Receipts deliberately exclude configuration, business data and exception text. */
final class ErpSandboxQualificationRunner {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int FILE_BYTES = 16 * 1024 * 1024;
    private static final long ARTIFACT_BYTES = 256L * 1024 * 1024;

    static JsonNode parse(byte[] bytes) {
        try {
            require(bytes != null && bytes.length <= 65_536);
            JsonNode parsed = MAPPER.readTree(bytes);
            return parsed == null ? MAPPER.createObjectNode() : parsed;
        } catch (Exception ignored) {
            return MAPPER.createObjectNode();
        }
    }
    private static final Set<String> CONFIG_FIELDS = Set.of("sourceType", "productVersion", "properties", "expectedRecords");
    private static final Set<String> PROPERTY_FIELDS = Set.of("serviceRoot", "connectionName", "entitySet", "tenant",
            "select", "filter", "orderBy", "keyFields", "sapClient", "maxRecords", "pageSize", "maxPages",
            "timeoutMillis", "maxResponseBytes");
    record Snapshot(Map<String, String> documentIds, Map<String, String> markdown) {}

    static ObjectNode run(JsonNode input, Path workspace, Path receiptPath, boolean live) throws Exception {
        ObjectNode receipt = MAPPER.createObjectNode().put("schemaVersion", 1)
                .put("startedAt", Instant.now().toString()).put("mode", live ? "sandbox" : "fixture")
                .put("status", "FAIL").put("paginationRequestsObserved", false);
        ObjectNode checks = receipt.putObject("checks");
        String stage = "configuration";
        List<String> secrets = List.of();
        try {
            validate(input);
            String type = input.path("sourceType").asText();
            receipt.put("sourceType", type);
            Map<String, Object> properties = MAPPER.convertValue(input.path("properties"), new TypeReference<>() {});
            if (live) require("https".equals(ErpSourceConfiguration.serviceRoot(properties.get("serviceRoot").toString()).getScheme()));
            stage = "credentials";
            Map<String, Object> resolved = new LinkedHashMap<>(properties);
            ErpConnectionCredentials.resolve(CredentialStore.create(), type, resolved);
            ErpSourceConfiguration profile = ErpSourceConfiguration.from(DocumentSourceDescriptor.builder()
                    .type(DocumentSourceDescriptor.SourceType.valueOf(type)).metadata(resolved).build());
            secrets = secrets(resolved);
            require(!secrets.isEmpty());
            assertNoSecrets(input.path("productVersion").asText(), secrets);
            receipt.put("declaredProductVersion", input.path("productVersion").asText());
            checks.put("configurationAndNamedBinding", true);

            // A local binding rejection, not an invalid-password request to the vendor.
            Map<String, Object> mismatch = new LinkedHashMap<>(properties);
            mismatch.put("tenant", ErpSourceConfiguration.text(resolved, "tenant", "") + "-qualification-mismatch");
            boolean rejected = false;
            try { ErpConnectionCredentials.resolve(CredentialStore.create(), type, mismatch); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected);
            checks.put("localBindingRejection", true);

            Path baseline = workspace.resolve("baseline");
            ToolContext context = context(baseline);
            stage = "baselineCrawl";
            String kb = crawl(type, properties, context);
            stage = "baselineMaterialization";
            Snapshot first = snapshot(baseline, kb);
            require(!first.documentIds().isEmpty() && first.documentIds().size() <= profile.maxRecords());
            receipt.put("recordCount", first.documentIds().size());
            checks.put("boundedNonemptyRead", true);
            stage = "expectedKeysAndSearch";
            for (JsonNode expected : input.path("expectedRecords")) {
                String sourceId = identity(profile, expected.path("keys"));
                require(first.documentIds().containsKey(sourceId));
                String marker = expected.path("searchText").asText();
                require(first.markdown().get(sourceId).contains(marker));
                ToolResult search = new KnowledgeSearchCliTool((String) null, MAPPER).execute(
                        MAPPER.createObjectNode().put("query", marker).put("knowledgeBase", kb).put("limit", 50), context);
                require(!search.isError());
                JsonNode evidence = MAPPER.valueToTree(search.getMetadata().get("evidence"));
                boolean found = false;
                for (JsonNode hit : evidence) {
                    if (hit.path("documentId").asText().equals(first.documentIds().get(sourceId))
                            && matchingChunkContains(search.getOutput(), hit, marker)) found = true;
                }
                require(found && search.getOutput().contains(marker));
                String mode = Objects.toString(search.getMetadata().get("retrievalMode"), "unknown");
                require(Set.of("hybrid", "lexical-fallback").contains(mode));
                receipt.put("retrievalMode", mode);
                assertNoSecrets(search.getOutput(), secrets);
            }
            checks.put("expectedKeysMarkdownAndSearchEvidence", true);
            receipt.put("expectedRecordCount", input.path("expectedRecords").size());
            receipt.put("beyondConfiguredPageSize", !type.equals("JD_EDWARDS") && first.documentIds().size() > profile.pageSize());

            stage = "repeatCrawl";
            Snapshot repeated = snapshot(baseline, crawl(type, properties, context));
            require(stableIdentities(first, repeated));
            checks.put("repeatStableRecordIdentities", true);

            // Separate project prevents a smaller sample replacing the baseline materialization.
            stage = "cappedCrawl";
            Map<String, Object> capped = new LinkedHashMap<>(properties);
            capped.put("maxRecords", 1);
            Path capRoot = workspace.resolve("capped");
            Snapshot cap = snapshot(capRoot, crawl(type, capped, context(capRoot)));
            require(cap.documentIds().size() == 1 && first.documentIds().keySet().containsAll(cap.documentIds().keySet()));
            checks.put("oneRecordCap", true);
            stage = "artifactRedaction";
            assertArtifactRedaction(workspace, secrets);
            checks.put("textArtifactSecretRedaction", true);
            receipt.put("status", "PASS_BOUNDED_READ");
        } catch (Exception ignored) {
            // Do not attach the raw cause: it may contain tenant URLs, records or credentials.
            receipt.put("failedStage", stage);
        }
        try { assertNoSecrets(receipt.toString(), secrets); }
        catch (Exception ignored) {
            receipt.removeAll();
            receipt.put("schemaVersion", 1).put("mode", live ? "sandbox" : "fixture")
                    .put("status", "FAIL").put("failedStage", "receiptRedaction");
        }
        receipt.put("finishedAt", Instant.now().toString());
        Files.createDirectories(receiptPath.toAbsolutePath().getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(receiptPath.toFile(), receipt);
        return receipt;
    }

    private static void validate(JsonNode input) {
        allowed(input, CONFIG_FIELDS);
        require(input.path("sourceType").isTextual() && ErpSourceConfiguration.TYPES.contains(input.path("sourceType").asText()));
        require(input.path("productVersion").isTextual() && input.path("productVersion").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"));
        JsonNode p = input.path("properties");
        allowed(p, PROPERTY_FIELDS);
        p.fields().forEachRemaining(field -> {
            if (!Set.of("maxRecords", "pageSize", "maxPages", "timeoutMillis", "maxResponseBytes").contains(field.getKey()))
                require(field.getValue().isTextual());
        });
        for (String field : List.of("connectionName", "serviceRoot", "entitySet", "keyFields"))
            require(p.path(field).isTextual() && !p.path(field).asText().isBlank());
        require(p.path("keyFields").asText().matches("[A-Za-z_][A-Za-z0-9_]*(,[A-Za-z_][A-Za-z0-9_]*)*"));
        List<String> keys = Arrays.asList(p.path("keyFields").asText().split(","));
        require(new HashSet<>(keys).size() == keys.size());
        for (String field : List.of("maxRecords", "timeoutMillis", "maxResponseBytes")) positiveInteger(p.path(field));
        boolean paged = !input.path("sourceType").asText().equals("JD_EDWARDS");
        if (paged) {
            positiveInteger(p.path("pageSize"));
            positiveInteger(p.path("maxPages"));
        }
        require(p.path("maxRecords").asInt() <= 100 && p.path("timeoutMillis").asInt() <= 30_000
                && p.path("maxResponseBytes").asInt() <= 1_048_576);
        if (paged) require(p.path("pageSize").asInt() <= 100 && p.path("maxPages").asInt() <= 20);
        JsonNode expected = input.path("expectedRecords");
        require(expected.isArray() && expected.size() > 0 && expected.size() <= p.path("maxRecords").asInt());
        if (paged) require(expected.size() > p.path("pageSize").asInt());
        Set<String> identities = new HashSet<>();
        for (JsonNode record : expected) {
            allowed(record, Set.of("keys", "searchText"));
            allowed(record.path("keys"), new HashSet<>(keys));
            require(record.path("keys").size() == keys.size());
            for (String key : keys) {
                JsonNode value = record.path("keys").path(key);
                require((value.isTextual() || value.isNumber() || value.isBoolean()) && !value.asText().isBlank());
            }
            require(identities.add(sortedKeys(record.path("keys")).toString()));
            require(record.path("searchText").isTextual() && !record.path("searchText").asText().isBlank()
                    && record.path("searchText").asText().length() <= 512);
        }
    }

    private static void allowed(JsonNode node, Set<String> fields) {
        require(node.isObject());
        node.fieldNames().forEachRemaining(key -> require(fields.contains(key)));
    }
    private static void positiveInteger(JsonNode value) {
        require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0);
    }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("ERP qualification check failed");
    }
    private static ToolContext context(Path root) throws Exception {
        Files.createDirectories(root);
        PermissionService permissions = new PermissionService();
        for (String tool : Set.of("crawl_documents", "knowledge_search"))
            permissions.setUserOverride(tool, PermissionService.PermissionLevel.ALLOW);
        return new ToolContext("erp-qualification", AgentConfig.builder("erp-qualification").enabledTools(Set.of("crawl_documents", "knowledge_search")).build(),
                permissions, root, new ToolRegistry(MAPPER));
    }
    private static String crawl(String type, Map<String, Object> properties, ToolContext context) throws Exception {
        ObjectNode request = MAPPER.createObjectNode().put("async", false);
        request.putObject("knowledgeBase").put("name", "erp-qualification");
        request.putArray("documents").addObject().put("sourceType", type).set("properties", MAPPER.valueToTree(properties));
        ToolResult result = new CrawlDocumentsTool((String) null, MAPPER).execute(request, context);
        require(!result.isError());
        Object kb = result.getMetadata().get("knowledgeBase");
        require(kb instanceof String && kb.toString().matches("[A-Za-z0-9._-]+"));
        return kb.toString();
    }
    private static Snapshot snapshot(Path project, String kb) throws Exception {
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> markdown = new LinkedHashMap<>();
        for (String line : boundedText(project.resolve("data/crawls").resolve(kb).resolve("documents.jsonl")).split("\\n")) {
            if (line.isBlank()) continue;
            JsonNode doc = MAPPER.readTree(line);
            String sourceId = "";
            for (JsonNode output : doc.path("loaderOutputs")) {
                String candidate = output.path("metadata").path("source_path").asText();
                if (candidate.startsWith("erp:")) sourceId = candidate;
            }
            require(!sourceId.isBlank() && !doc.path("documentId").asText().isBlank());
            require(ids.put(sourceId, doc.path("documentId").asText()) == null);
            Path md = project.resolve(doc.path("markdownPath").asText()).toRealPath();
            require(md.startsWith(project.toRealPath()));
            require(ids.size() <= 100);
            markdown.put(sourceId, boundedText(md));
            require(markdown.values().stream().mapToLong(String::length).sum() <= 32L * 1024 * 1024);
        }
        return new Snapshot(ids, markdown);
    }
    static boolean stableIdentities(Snapshot first, Snapshot repeated) {
        return first.documentIds().equals(repeated.documentIds());
    }
    static boolean matchingChunkContains(String output, JsonNode hit, String marker) {
        String header = "Document: " + hit.path("documentId").asText() + " | Chunk: " + hit.path("chunkId").asText();
        for (String section : output.split("\\n### ")) {
            int start = section.indexOf(header);
            if (start < 0) continue;
            int end = section.indexOf('\n', start);
            if (end > start && section.substring(start, end).equals(header)
                    && section.substring(end + 1).contains(marker)) return true;
        }
        return false;
    }
    private static String boundedText(Path path) throws Exception {
        require(Files.size(path) <= FILE_BYTES);
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(FILE_BYTES + 1);
            require(bytes.length <= FILE_BYTES);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    private static ObjectNode sortedKeys(JsonNode keys) {
        ObjectNode result = MAPPER.createObjectNode();
        TreeSet<String> names = new TreeSet<>();
        keys.fieldNames().forEachRemaining(names::add);
        names.forEach(name -> result.set(name, keys.get(name)));
        return result;
    }
    private static String identity(ErpSourceConfiguration profile, JsonNode keys) throws Exception {
        String scope = profile.root() + "|" + ErpSourceConfiguration.text(profile.properties(), "tenant", "") + "|"
                + ErpSourceConfiguration.text(profile.properties(), "sapClient", "") + "|" + profile.entitySet();
        return "erp:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((scope + "|" + sortedKeys(keys)).getBytes(StandardCharsets.UTF_8)));
    }
    private static List<String> secrets(Map<String, Object> properties) {
        List<String> result = new ArrayList<>();
        for (String key : List.of("password", "accessToken")) {
            String value = ErpSourceConfiguration.text(properties, key, "");
            if (!value.isEmpty()) result.add(value);
        }
        if (properties.containsKey("password")) result.add(Base64.getEncoder().encodeToString(
                (properties.get("username") + ":" + properties.get("password")).getBytes(StandardCharsets.UTF_8)));
        return result;
    }
    private static void assertArtifactRedaction(Path root, List<String> secrets) throws Exception {
        try (var paths = Files.walk(root)) {
            long total = 0;
            int count = 0;
            var files = paths.filter(Files::isRegularFile).iterator();
            while (files.hasNext()) {
                Path path = files.next();
                require(++count <= 10_000);
                total += Files.size(path);
                require(total <= ARTIFACT_BYTES);
                if (path.getFileName().toString().matches(".*\\.(json|jsonl|md|txt|log)"))
                    assertNoSecrets(boundedText(path), secrets);
            }
        }
    }
    private static void assertNoSecrets(String text, List<String> secrets) throws Exception {
        for (String secret : secrets) {
            require(!text.contains(secret));
            String encoded = MAPPER.writeValueAsString(secret);
            require(!text.contains(encoded.substring(1, encoded.length() - 1)));
        }
    }
}
