/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.source.erp;

import ai.kompile.core.crawl.graph.SourceCredentialRedactor;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Spring-free bounded ERP reads over Camel HTTP. No route DSL or vendor mutations. */
public final class CamelErpDocumentLoader implements DocumentLoader {
    private final ObjectMapper mapper = new ObjectMapper();
    @Override public String getName() { return "camel-erp"; }
    @Override public boolean supports(DocumentSourceDescriptor source) {
        return source != null && source.getType() != null && ErpSourceConfiguration.isErp(source.getType().name());
    }
    @Override public List<Document> load(DocumentSourceDescriptor source) throws Exception { return load(source, null); }
    @Override public List<Document> load(DocumentSourceDescriptor source, Consumer<LoaderProgress> progress) throws Exception {
        if (!supports(source)) throw new IllegalArgumentException("Unsupported ERP descriptor");
        ErpSourceConfiguration config = ErpSourceConfiguration.from(source);
        interrupted();
        // Credential-free endpoint URI; auth exists only in a fresh per-request header map.
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            HttpComponent http = new HttpComponent();
            http.setRedirectHandlingDisabled(true);
            http.setAutomaticRetriesDisabled(true);
            http.setCookieManagementDisabled(true);
            http.setResponsePayloadStreamingThreshold(config.maxResponseBytes());
            http.setHttpClientConfigurer(builder -> {
                builder.disableContentCompression();
                builder.addResponseInterceptorLast((response, details, httpContext) -> {
                    var encoding = response.getFirstHeader("Content-Encoding");
                    if (encoding != null && !"identity".equalsIgnoreCase(encoding.getValue()))
                        throw new org.apache.hc.core5.http.HttpException("ERP compressed responses are not supported");
                    if (response instanceof org.apache.hc.core5.http.ClassicHttpResponse classic && classic.getEntity() != null) {
                        classic.setEntity(new org.apache.hc.core5.http.io.entity.HttpEntityWrapper(classic.getEntity()) {
                            @Override public InputStream getContent() throws IOException {
                                return new java.io.FilterInputStream(super.getContent()) {
                                    private long remaining = config.maxResponseBytes();
                                    @Override public int read() throws IOException {
                                        int value = super.read();
                                        if (value >= 0 && --remaining < 0) throw new IOException("ERP response exceeded maxResponseBytes");
                                        return value;
                                    }
                                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                                        int read = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
                                        if (read > 0 && (remaining -= read) < 0) throw new IOException("ERP response exceeded maxResponseBytes");
                                        return read;
                                    }
                                };
                            }
                        });
                    }
                });
            });
            Timeout timeout = Timeout.ofMilliseconds(config.timeoutMillis());
            http.setConnectTimeout(timeout);
            http.setConnectionRequestTimeout(timeout);
            http.setSoTimeout(timeout);
            http.setResponseTimeout(timeout);
            context.addComponent(config.root().getScheme(), http);
            context.setStreamCaching(false);
            context.start();
            try (ProducerTemplate template = context.createProducerTemplate()) {
                String endpoint = config.root() + "?throwExceptionOnFailure=false&disableStreamCache=false&copyHeaders=false";
                URI page = config.firstPage();
                Set<URI> visited = new HashSet<>();
                Map<String, Document> records = new LinkedHashMap<>();
                int fetched = 0;
                for (int n = 1; n <= config.maxPages(); n++) {
                    interrupted();
                    if (!config.type().equals("ODOO") && !visited.add(page)) throw new IOException("ERP pagination repeated a page");
                    JsonNode json = request(template, endpoint, page, config, config.type().equals("ODOO") ? odooBody(config, fetched) : null);
                    JsonNode wrapper = config.type().equals("SAP_NETWEAVER") ? json.path("d") : json;
                    JsonNode rows = switch (config.type()) {
                        case "SAP_NETWEAVER" -> wrapper.path("results");
                        case "NETSUITE", "ORACLE_FUSION" -> wrapper.path("items");
                        case "ORACLE_EBS" -> ebsRows(wrapper, config);
                        case "JD_EDWARDS" -> wrapper.path("fs_DATABROWSE_" + config.entitySet()).path("data").path("gridData").path("rowset");
                        case "INFOR_MONGOOSE" -> wrapper.path("Items");
                        case "ODOO", "ACUMATICA" -> wrapper;
                        case "SALESFORCE" -> wrapper.path("records");
                        default -> wrapper.path("value");
                    };
                    if (!rows.isArray()) throw new IOException("ERP response is not an entity collection");
                    if (config.type().equals("NETSUITE")) validateNetSuitePage(wrapper, rows, config, fetched);
                    if (config.type().equals("ORACLE_FUSION")) validateFusionPage(wrapper, rows, config, fetched);
                    if (config.type().equals("ORACLE_EBS")) validateEbsPage(wrapper, rows, config, fetched);
                    if (config.type().equals("JD_EDWARDS")) validateJdePage(wrapper, rows, config);
                    if (config.type().equals("INFOR_MONGOOSE")) validateMongoosePage(wrapper, rows, config, fetched);
                    if (config.type().equals("ACUMATICA")) {
                        if (rows.size() > Math.min(config.pageSize(), config.maxRecords() - fetched))
                            throw new IOException("Acumatica exceeded the requested page size");
                        validateAcumaticaErrors(rows);
                    }
                    // Validate the envelope even when this page reaches the intentional sample cap.
                    if (config.type().equals("SALESFORCE")) {
                        if (!wrapper.path("done").isBoolean()) throw new IOException("Salesforce response must include done");
                        if (!wrapper.path("done").booleanValue()) {
                            JsonNode next = wrapper.path("nextRecordsUrl");
                            if (!next.isTextual() || next.asText().isBlank()) throw new IOException("Salesforce query requires a continuation");
                            config.continuation(page, next.asText());
                        }
                    }
                    if (config.type().equals("ODOO") && rows.size() > config.pageSize()) throw new IOException("Odoo exceeded the requested page size");
                    for (JsonNode row : rows) {
                        interrupted();
                        if (!row.isObject()) throw new IOException("ERP entity must be an object");
                        if (config.type().equals("NETSUITE")) {
                            String id = row.path("id").asText();
                            if (!id.matches("[A-Za-z0-9_-]+")) throw new IOException("NetSuite record has an invalid id");
                            // Collection responses contain only ids/links. Never follow server-supplied links.
                            row = request(template, endpoint, config.root().resolve(config.entitySet() + "/" + id), config, null);
                            if (!row.isObject() || !id.equals(row.path("id").asText())) throw new IOException("NetSuite detail identity mismatch");
                        }
                        Document document = document(row, config);
                        Document previous = records.put(document.getMetadata().get("source_path").toString(), document);
                        if (previous != null && Set.of("ORACLE_FUSION", "ORACLE_EBS", "JD_EDWARDS", "INFOR_MONGOOSE", "ACUMATICA").contains(config.type()))
                            throw new IOException("ERP read repeated a record identity; verify keyFields and ordering");
                        if (++fetched >= config.maxRecords()) break;
                    }
                    if (progress != null) progress.accept(new LoaderProgress("LOADING", Math.min(99, fetched * 100 / config.maxRecords()),
                            "erp-page", "ERP entity page loaded", Map.of("pages", n, "records", records.size())));
                    if (fetched >= config.maxRecords()) return new ArrayList<>(records.values());
                    if (config.type().equals("JD_EDWARDS")) return new ArrayList<>(records.values());
                    if (config.type().equals("ORACLE_FUSION") || config.type().equals("ORACLE_EBS")) {
                        boolean more = config.type().equals("ORACLE_FUSION") ? wrapper.path("hasMore").booleanValue()
                                : ebsNumber(wrapper.path("OutputParameters").path("Summary").path("TotalCount")) > fetched;
                        if (!more) return new ArrayList<>(records.values());
                        page = config.recordPage(fetched);
                    } else if (config.type().equals("INFOR_MONGOOSE")) {
                        if (!wrapper.path("MoreRowsExist").booleanValue()) return new ArrayList<>(records.values());
                        page = config.mongoosePage(wrapper.path("Bookmark").textValue(), config.maxRecords() - fetched);
                    } else if (config.type().equals("ACUMATICA")) {
                        // A short response may be server-clamped; only an empty page proves exhaustion.
                        if (rows.isEmpty()) return new ArrayList<>(records.values());
                        page = config.acumaticaPage(fetched);
                    } else if (config.type().equals("ODOO")) {
                        if (rows.size() < config.pageSize()) return new ArrayList<>(records.values());
                    } else if (config.type().equals("NETSUITE")) {
                        if (!wrapper.path("hasMore").booleanValue()) return new ArrayList<>(records.values());
                        page = config.recordPage(fetched);
                    } else {
                        JsonNode nextNode = wrapper.path(config.type().equals("SAP_NETWEAVER") ? "__next"
                                : config.type().equals("SALESFORCE") ? "nextRecordsUrl" : "@odata.nextLink");
                        if (config.type().equals("SALESFORCE")) {
                            if (wrapper.path("done").booleanValue()) return new ArrayList<>(records.values());
                        } else if (nextNode.isMissingNode() || nextNode.isNull() || nextNode.isTextual() && nextNode.asText().isBlank())
                            return new ArrayList<>(records.values());
                        if (!nextNode.isTextual()) throw new IOException("ERP continuation must be a URL");
                        page = config.continuation(page, nextNode.asText());
                    }
                    if (n == config.maxPages()) throw new IOException("ERP page budget exhausted before the record limit; increase maxPages");
                }
                throw new IOException("ERP page budget exhausted");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (IllegalArgumentException | IOException e) {
            throw e; // Local validation errors contain no remote payloads or credentials.
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("ERP crawl cancelled");
            // Do not retain a Camel exception cause: it can contain request URLs, headers or response bodies.
            throw new IOException("ERP transport failed; check the service, credentials and timeout");
        }
    }
    private static void validateMongoosePage(JsonNode wrapper, JsonNode rows, ErpSourceConfiguration config, int fetched) throws IOException {
        if (!wrapper.path("Success").isBoolean() || !wrapper.path("Success").booleanValue()
                || !wrapper.path("MoreRowsExist").isBoolean() || rows.size() > Math.min(config.pageSize(), config.maxRecords() - fetched))
            throw new IOException("Invalid or failed Infor Mongoose collection read");
        if (wrapper.path("MoreRowsExist").booleanValue()) {
            JsonNode bookmark = wrapper.path("Bookmark");
            if (rows.isEmpty() || !bookmark.isTextual() || bookmark.textValue().isBlank() || bookmark.textValue().length() > 16384)
                throw new IOException("Infor Mongoose requires a bounded progressing bookmark");
        }
    }
    private static void validateFusionPage(JsonNode wrapper, JsonNode rows, ErpSourceConfiguration config, int offset) throws IOException {
        JsonNode limit = wrapper.path("limit");
        if (!wrapper.path("hasMore").isBoolean() || !wrapper.path("offset").isIntegralNumber()
                || !wrapper.path("offset").canConvertToInt() || wrapper.path("offset").intValue() != offset || !limit.isIntegralNumber()
                || !limit.canConvertToInt() || limit.intValue() < 1 || limit.intValue() > config.pageSize()
                || rows.size() > limit.intValue() || wrapper.path("hasMore").booleanValue() && rows.isEmpty()
                || wrapper.has("count") && (!wrapper.path("count").isIntegralNumber() || !wrapper.path("count").canConvertToInt() || wrapper.path("count").intValue() != rows.size()))
            throw new IOException("Invalid Oracle Fusion collection pagination");
    }
    private JsonNode ebsRows(JsonNode wrapper, ErpSourceConfiguration config) throws IOException {
        var rows = mapper.createArrayNode();
        JsonNode summary = wrapper.path("OutputParameters").path("Summary");
        JsonNode output = wrapper.path("OutputParameters").path("Result").path("Output");
        if ((output.isMissingNode() || output.isNull()) && ebsNumber(summary.path("GetCount")) == 0) return rows;
        if (output.isArray()) {
            for (JsonNode entry : output) addEbsRecords(rows, entry, config);
        } else addEbsRecords(rows, output, config);
        return rows;
    }
    private static void addEbsRecords(com.fasterxml.jackson.databind.node.ArrayNode rows, JsonNode output,
                                      ErpSourceConfiguration config) throws IOException {
        if (!output.isObject()) throw new IOException("Invalid Oracle EBS interface result");
        JsonNode record = output.path(config.entitySet() + "_REC");
        if (record.isArray()) {
            for (JsonNode row : record) {
                if (!row.isObject()) throw new IOException("Invalid Oracle EBS interface record");
                rows.add(row);
            }
        } else if (record.isObject()) rows.add(record);
        else throw new IOException("Oracle EBS response is not an open interface GET result");
    }
    private static long ebsNumber(JsonNode value) throws IOException {
        if (!(value.isIntegralNumber() || value.isTextual()) || !value.asText().matches("[0-9]+"))
            throw new IOException("Invalid Oracle EBS interface summary");
        try { return Long.parseLong(value.asText()); }
        catch (NumberFormatException e) { throw new IOException("Invalid Oracle EBS interface summary"); }
    }
    private static void validateEbsPage(JsonNode wrapper, JsonNode rows, ErpSourceConfiguration config, int offset) throws IOException {
        JsonNode summary = wrapper.path("OutputParameters").path("Summary");
        long limit = ebsNumber(summary.path("Limit"));
        long total = ebsNumber(summary.path("TotalCount"));
        if (ebsNumber(summary.path("Offset")) != offset || ebsNumber(summary.path("GetCount")) != rows.size()
                || limit < 1 || limit > config.pageSize() || rows.size() > limit || total < (long) offset + rows.size()
                || total > offset && rows.isEmpty())
            throw new IOException("Invalid Oracle EBS interface pagination");
    }
    private static void validateJdePage(JsonNode wrapper, JsonNode rows, ErpSourceConfiguration config) throws IOException {
        JsonNode browse = wrapper.path("fs_DATABROWSE_" + config.entitySet());
        JsonNode errors = browse.path("errors");
        JsonNode sysErrors = wrapper.path("sysErrors");
        JsonNode summary = browse.path("data").path("gridData").path("summary");
        if (!errors.isArray() || !errors.isEmpty() || !sysErrors.isArray() || !sysErrors.isEmpty()
                || !summary.path("records").isIntegralNumber() || !summary.path("records").canConvertToInt() || summary.path("records").intValue() != rows.size()
                || !summary.path("moreRecords").isBoolean() || rows.size() > config.maxRecords()
                || summary.path("moreRecords").booleanValue() && rows.size() < config.maxRecords())
            throw new IOException("Invalid or incomplete JD Edwards bounded table read");
    }
    private String odooBody(ErpSourceConfiguration config, int offset) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("domain");
        var fields = body.putArray("fields");
        config.selectedFields("id").forEach(fields::add);
        body.put("limit", config.pageSize());
        body.put("offset", offset);
        body.put("order", "id asc");
        return mapper.writeValueAsString(body);
    }
    private static void validateNetSuitePage(JsonNode wrapper, JsonNode rows, ErpSourceConfiguration config, int offset) throws IOException {
        if (!wrapper.path("hasMore").isBoolean() || !wrapper.path("offset").isIntegralNumber()
                || wrapper.path("offset").asLong() != offset || !wrapper.path("count").isIntegralNumber()
                || wrapper.path("count").asLong() != rows.size() || rows.size() > config.pageSize()
                || wrapper.path("hasMore").booleanValue() && rows.size() != config.pageSize())
            throw new IOException("Invalid NetSuite record pagination");
    }
    private JsonNode request(ProducerTemplate template, String endpoint, URI page, ErpSourceConfiguration config, String requestBody) throws Exception {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(Exchange.HTTP_METHOD, requestBody == null ? "GET" : "POST");
        if (requestBody != null) {
            headers.put("Content-Type", "application/json");
            String database = ErpSourceConfiguration.text(config.properties(), "tenant", "");
            if (!database.isEmpty()) headers.put("X-Odoo-Database", database);
        }
        if (config.type().equals("SALESFORCE")) headers.put("Sforce-Query-Options", "batchSize=" + Math.max(200, Math.min(2000, config.pageSize())));
        if (config.type().equals("INFOR_MONGOOSE")) headers.put("X-Infor-MongooseConfig", ErpSourceConfiguration.text(config.properties(), "tenant", ""));
        headers.put(Exchange.HTTP_URI, page.getScheme() + "://" + page.getRawAuthority() + page.getRawPath());
        headers.put(Exchange.HTTP_RAW_QUERY, page.getRawQuery());
        headers.put("Accept", "application/json");
        headers.put("Accept-Encoding", "identity");
        String token = ErpSourceConfiguration.text(config.properties(), "accessToken", "");
        String password = ErpSourceConfiguration.text(config.properties(), "password", "");
        if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
        else if (!password.isEmpty()) {
            String user = ErpSourceConfiguration.text(config.properties(), "username", "");
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        Exchange exchange = template.request(endpoint, e -> {
            e.getIn().setHeaders(headers);
            e.getIn().setBody(requestBody);
        });
        try {
            if (exchange.isFailed()) throw new IOException("ERP HTTP request failed");
            Integer status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
            try (InputStream body = exchange.getMessage().getBody(InputStream.class)) {
                if (status == null || status < 200 || status >= 300) throw new IOException("ERP service returned HTTP " + status);
                if (body == null) throw new IOException("ERP service returned an empty response");
                byte[] bytes = body.readNBytes(config.maxResponseBytes() + 1);
                if (bytes.length > config.maxResponseBytes()) throw new IOException("ERP response exceeded maxResponseBytes");
                try {
                    JsonNode json = mapper.readTree(bytes);
                    if (json == null || json.isNull()) throw new IOException();
                    return json;
                }
                catch (Exception e) { throw new IOException("ERP service returned invalid JSON"); }
            }
        } finally {
            exchange.getIn().removeHeader("Authorization");
            exchange.getMessage().removeHeader("Authorization");
        }
    }
    private static void validateAcumaticaErrors(JsonNode node) throws IOException {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode value = field.getValue();
                if (field.getKey().equalsIgnoreCase("error") && !value.isNull()
                        && (!value.isTextual() || !value.asText().isBlank()))
                    throw new IOException("Acumatica returned a record or field error");
                validateAcumaticaErrors(value);
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) validateAcumaticaErrors(value);
        }
    }
    private Document document(JsonNode row, ErpSourceConfiguration config) throws Exception {
        String keys = ErpSourceConfiguration.text(config.properties(), "keyFields", "");
        String identity;
        if (!keys.isBlank()) {
            ObjectNode values = mapper.createObjectNode();
            for (String key : keys.split(",")) {
                key = key.trim();
                JsonNode value = config.type().equals("ACUMATICA") ? row.path(key).path("value") : row.path(key);
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || sensitive(key) || value.isMissingNode() || value.isNull()
                        || !value.isValueNode() || value.asText().isBlank())
                    throw new IOException("ERP keyFields must identify non-secret scalar fields present in every record");
                values.set(key, value);
            }
            identity = sorted(values).toString();
        } else if (row.hasNonNull("@odata.id")) identity = row.path("@odata.id").asText();
        else if (row.path("__metadata").hasNonNull("uri")) identity = row.path("__metadata").path("uri").asText();
        else if (row.hasNonNull("ID")) identity = scalarIdentity(row.get("ID"));
        else if (row.hasNonNull("Id")) identity = scalarIdentity(row.get("Id"));
        else if (row.hasNonNull("id")) identity = scalarIdentity(row.get("id"));
        else throw new IOException("ERP entity has no stable identity; configure keyFields");
        if (identity.isBlank()) throw new IOException("ERP entity has no stable identity; configure keyFields");
        String scope = config.root() + "|" + ErpSourceConfiguration.text(config.properties(), "tenant", "") + "|"
                + ErpSourceConfiguration.text(config.properties(), "sapClient", "") + "|" + config.entitySet();
        String stableId = "erp:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((scope + "|" + identity).getBytes(StandardCharsets.UTF_8)));
        StringBuilder text = new StringBuilder("# ").append(config.entitySet()).append("\n\n");
        ObjectNode record = ((ObjectNode) row).deepCopy();
        if (Set.of("NETSUITE", "ORACLE_FUSION").contains(config.type())) record.remove("links");
        if (config.type().equals("SALESFORCE")) record.remove("attributes");
        if (config.type().equals("ACUMATICA")) record.remove(java.util.List.of("id", "rowNumber", "_links", "files"));
        JsonNode safe = sorted(record);
        var fields = safe.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            text.append("- **").append(field.getKey()).append("**: ")
                    .append(field.getValue().isTextual() ? field.getValue().asText().replace("\n", " ") : field.getValue()).append('\n');
        }
        String content = SourceCredentialRedactor.redact(text.toString());
        for (String key : new String[]{"password", "accessToken"}) {
            String secret = ErpSourceConfiguration.text(config.properties(), key, "");
            if (!secret.isEmpty()) content = content.replace(secret, "<redacted>");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_path", stableId);
        metadata.put("source_type", config.type());
        metadata.put("source_url", config.root().resolve((config.type().equals("INFOR_MONGOOSE") ? "load/" : "") + config.entitySet()).toString());
        metadata.put("title", config.entitySet());
        metadata.put("erp.entitySet", config.entitySet());
        return new Document(content, metadata);
    }
    private static String scalarIdentity(JsonNode value) throws IOException {
        if (!value.isValueNode() || value.asText().isBlank()) throw new IOException("ERP entity identity must be a nonblank scalar");
        return value.toString(); // Preserve existing scalar ID hashing.
    }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(e -> { if (!e.getKey().startsWith("@") && !e.getKey().equals("__metadata") && !sensitive(e.getKey())) fields.put(e.getKey(), e.getValue()); });
            fields.forEach((key, value) -> copy.set(key, sorted(value)));
            return copy;
        }
        if (node.isArray()) {
            var copy = mapper.createArrayNode();
            node.forEach(value -> copy.add(sorted(value)));
            return copy;
        }
        return node;
    }
    private static boolean sensitive(String key) {
        String name = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return name.endsWith("password") || name.endsWith("token") || name.endsWith("secret") || name.endsWith("authorization")
                || name.endsWith("apikey") || name.endsWith("privatekey") || name.endsWith("credentials");
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("ERP crawl cancelled");
    }
}
