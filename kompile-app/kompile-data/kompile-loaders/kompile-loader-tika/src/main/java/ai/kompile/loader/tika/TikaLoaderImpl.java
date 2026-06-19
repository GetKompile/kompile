/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.tika;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TikaLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(TikaLoaderImpl.class);
    private final Tika tika = new Tika();

    @Override
    public String getName() {
        return "Apache Tika Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        // Tika supports many file types, so it could be a fallback or check extensions it's good at.
        // For simplicity, let's say it supports if it's a file and not explicitly handled by others.
        // A more robust check might involve trying to detect type with Tika itself.
        return sourceDescriptor.getType() == DocumentSourceDescriptor.SourceType.FILE;
    }

    /**
     * Parse a single CSV line respecting RFC 4180 quoting rules.
     */
    public static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static final Pattern ATX_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /**
     * Extract ATX-style markdown headings from text content.
     */
    public static List<Map<String, String>> extractMarkdownHeadings(String markdown) {
        List<Map<String, String>> headings = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            Matcher m = ATX_HEADING.matcher(line.trim());
            if (m.matches()) {
                String text = m.group(2).replaceAll("\\s+#+\\s*$", "").trim();
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("text", text);
                entry.put("level", String.valueOf(m.group(1).length()));
                headings.add(entry);
            }
        }
        return headings;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            throw new IllegalArgumentException("TikaLoader currently only supports FILE sources.");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + sourceDescriptor.getPathOrUrl());
        }

        String content;
        try (InputStream stream = new FileInputStream(file)) {
            content = tika.parseToString(stream);
        } catch (TikaException | IOException e) {
            // Handle corrupted or invalid files gracefully
            String errorMessage = e.getMessage();
            logger.warn("Unable to parse file '{}' with Tika: {}. The file may be corrupted or in an unsupported format.",
                       file.getName(), errorMessage);

            // Return an error document so the caller knows what happened
            Document errorDoc = new Document("[Error: Unable to parse file. The file may be corrupted, truncated, or in an unsupported format.]");
            errorDoc.getMetadata().put("source", file.getAbsolutePath());
            errorDoc.getMetadata().put("fileName", file.getName());
            errorDoc.getMetadata().put("fileSize", file.length());
            errorDoc.getMetadata().put("lastModified", file.lastModified());
            errorDoc.getMetadata().put("loader", getName());
            errorDoc.getMetadata().put("parseError", true);
            errorDoc.getMetadata().put("errorMessage", errorMessage != null ? errorMessage : "Unknown error");
            return List.of(errorDoc);
        }

        Document springDoc = new Document(content);
        springDoc.getMetadata().put("source", file.getAbsolutePath());
        springDoc.getMetadata().put("fileName", file.getName());
        springDoc.getMetadata().put("loader", getName());

        // Structured-format enrichment: JSON/YAML/XML files are parsed so the graph
        // extractor can build a structural knowledge graph from them (Tika only yields
        // flat text). Best-effort — the document already carries its plain-text body.
        enrichStructuredFormat(file, springDoc.getMetadata());

        return List.of(springDoc);
    }

    // ── Structured-format (JSON / YAML / XML) metadata enrichment ───────────
    //
    // TikaGenericGraphExtractor keys off the "documentType" metadata plus json.*,
    // yaml.*, and xml.* keys to emit JSON_KEY / XML_ELEMENT / YAML_KEY entities and
    // their relations. Tika's plain-text extraction produces none of that, so we parse
    // the raw file here: JSON and YAML with Jackson, XML with the JDK DOM parser.

    private static final ObjectMapper JSON_MAPPER = JsonUtils.standardMapper();
    private static final YAMLFactory YAML_FACTORY = new YAMLFactory();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(YAML_FACTORY);

    /** Bounds so a pathologically large/deep document cannot explode the graph. */
    private static final int MAX_STRUCT_KEYS = 500;
    private static final int MAX_STRUCT_DEPTH = 12;

    /** Detects YAML anchor definitions (e.g. {@code &name}) for the hasAnchors flag. */
    private static final Pattern YAML_ANCHOR = Pattern.compile("(?m)(^|\\s)&[A-Za-z0-9_][\\w.\\-]*");

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private void enrichStructuredFormat(File file, Map<String, Object> meta) {
        String name = file.getName().toLowerCase();
        try {
            if (name.endsWith(".json")) {
                enrichJson(file, meta);
            } else if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                enrichYaml(file, meta);
            } else if (name.endsWith(".xml")) {
                enrichXml(file, meta);
            } else if (name.endsWith(".csv")) {
                enrichDelimited(file, meta, ",");
            } else if (name.endsWith(".tsv")) {
                enrichDelimited(file, meta, "\t");
            } else if (name.endsWith(".md") || name.endsWith(".markdown")) {
                enrichMarkdown(file, meta);
            }
        } catch (Exception e) {
            logger.warn("Could not extract structured metadata from '{}': {}. Indexing as plain text.",
                    file.getName(), e.toString());
        }
    }

    private void enrichJson(File file, Map<String, Object> meta) throws IOException {
        JsonNode root = JSON_MAPPER.readTree(file);
        if (root == null || root.isMissingNode()) {
            return;
        }
        meta.put(GraphConstants.META_DOCUMENT_TYPE, "json");
        boolean isArray = root.isArray();
        boolean isObject = root.isObject();
        meta.put("json.isArray", isArray);
        meta.put("json.isObject", isObject);

        // For a top-level array, derive structural keys from its first object element.
        JsonNode structRoot = isObject ? root : null;
        if (isArray) {
            meta.put("json.arraySize", root.size());
            JsonNode first = root.isEmpty() ? null : root.get(0);
            if (first != null && first.isObject()) {
                structRoot = first;
                meta.put("json.keysFromArrayElement", true);
            }
        }

        if (structRoot != null && structRoot.isObject()) {
            List<String> topKeys = new ArrayList<>();
            structRoot.fieldNames().forEachRemaining(topKeys::add);
            meta.put("json.topLevelKeys", topKeys);
            meta.put("json.keyCount", topKeys.size());

            JsonNode schema = structRoot.get("$schema");
            if (schema != null && schema.isTextual()) {
                meta.put("json.schema", schema.asText());
            }
            JsonNode id = structRoot.get("$id");
            if (id != null && id.isTextual()) {
                meta.put("json.id", id.asText());
            }

            List<Map<String, String>> nestedKeys = new ArrayList<>();
            collectJsonKeys(structRoot, "", 1, nestedKeys);
            meta.put("json.nestedKeys", nestedKeys);
        }
    }

    private void collectJsonKeys(JsonNode node, String parentPath, int depth,
                                 List<Map<String, String>> out) {
        if (node == null || !node.isObject() || depth > MAX_STRUCT_DEPTH) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            if (out.size() >= MAX_STRUCT_KEYS) {
                return;
            }
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode child = field.getValue();
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("path", path);
            entry.put("parentPath", parentPath);
            entry.put("depth", String.valueOf(depth));
            entry.put("valueType", jsonValueType(child));
            out.add(entry);

            if (child.isObject()) {
                collectJsonKeys(child, path, depth + 1, out);
            } else if (child.isArray() && !child.isEmpty() && child.get(0).isObject()) {
                collectJsonKeys(child.get(0), path, depth + 1, out);
            }
        }
    }

    private static String jsonValueType(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isBoolean()) return "boolean";
        if (node.isNumber()) return "number";
        return "unknown";
    }

    private void enrichYaml(File file, Map<String, Object> meta) throws IOException {
        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        List<JsonNode> documents = new ArrayList<>();
        try (var parser = YAML_FACTORY.createParser(raw)) {
            MappingIterator<JsonNode> it = YAML_MAPPER.readValues(parser, JsonNode.class);
            while (it.hasNextValue()) {
                documents.add(it.nextValue());
            }
        }
        meta.put(GraphConstants.META_DOCUMENT_TYPE, "yaml");
        meta.put("yaml.documentCount", documents.size());

        JsonNode first = documents.isEmpty() ? null : documents.get(0);
        if (first != null && first.isObject()) {
            List<String> topKeys = new ArrayList<>();
            first.fieldNames().forEachRemaining(topKeys::add);
            meta.put("yaml.topLevelKeys", topKeys);
            meta.put("yaml.keyCount", topKeys.size());
        }
        if (YAML_ANCHOR.matcher(raw).find()) {
            meta.put("yaml.hasAnchors", true);
        }
    }

    private void enrichXml(File file, Map<String, Object> meta)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        // Harden against XXE while still surfacing the DOCTYPE declaration.
        setXmlFeature(dbf, "http://xml.org/sax/features/external-general-entities", false);
        setXmlFeature(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
        setXmlFeature(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);

        DocumentBuilder builder = dbf.newDocumentBuilder();
        org.w3c.dom.Document dom;
        try (InputStream in = new FileInputStream(file)) {
            dom = builder.parse(in);
        }
        Element root = dom.getDocumentElement();
        if (root == null) {
            return;
        }
        meta.put(GraphConstants.META_DOCUMENT_TYPE, "xml");
        meta.put("xml.rootTag", localName(root));
        if (root.getNamespaceURI() != null) {
            meta.put("xml.rootNamespace", root.getNamespaceURI());
        }
        if (dom.getXmlVersion() != null) {
            meta.put("xml.version", dom.getXmlVersion());
        }
        if (dom.getXmlEncoding() != null) {
            meta.put("xml.encoding", dom.getXmlEncoding());
        }

        org.w3c.dom.DocumentType doctype = dom.getDoctype();
        if (doctype != null) {
            meta.put("xml.hasDtd", true);
            if (doctype.getSystemId() != null) {
                meta.put("xml.dtdSystemId", doctype.getSystemId());
            }
        }

        // Namespaces declared on the root element (xmlns / xmlns:prefix).
        List<String> namespaces = new ArrayList<>();
        Map<String, String> namespacePrefixes = new LinkedHashMap<>();
        NamedNodeMap rootAttrs = root.getAttributes();
        for (int i = 0; i < rootAttrs.getLength(); i++) {
            Attr attr = (Attr) rootAttrs.item(i);
            String attrName = attr.getName();
            if ("xmlns".equals(attrName) || attrName.startsWith("xmlns:")) {
                String uri = attr.getValue();
                if (uri != null && !uri.isBlank()) {
                    namespaces.add(uri);
                    namespacePrefixes.put(uri,
                            attrName.equals("xmlns") ? "" : attrName.substring("xmlns:".length()));
                }
            }
        }
        if (!namespaces.isEmpty()) {
            meta.put("xml.namespaces", namespaces);
            meta.put("xml.namespacePrefixes", namespacePrefixes);
            meta.put("xml.namespaceCount", namespaces.size());
        }

        String schemaLocation = root.getAttributeNS(XSI_NS, "schemaLocation");
        if (schemaLocation != null && !schemaLocation.isBlank()) {
            meta.put("xml.schemaLocation", schemaLocation.trim());
        }
        String noNsSchema = root.getAttributeNS(XSI_NS, "noNamespaceSchemaLocation");
        if (noNsSchema != null && !noNsSchema.isBlank()) {
            meta.put("xml.noNamespaceSchemaLocation", noNsSchema.trim());
        }

        // Child element hierarchy, in document order so parents precede children.
        List<Map<String, String>> childElements = new ArrayList<>();
        Set<String> uniqueTags = new LinkedHashSet<>();
        collectXmlChildren(root, "", 1, childElements, uniqueTags);
        if (!childElements.isEmpty()) {
            meta.put("xml.childElements", childElements);
            meta.put("xml.uniqueChildTags", new ArrayList<>(uniqueTags));
        }
        meta.put("xml.childElementCount", countChildElements(root));
    }

    private void collectXmlChildren(Element parent, String parentPath, int depth,
                                    List<Map<String, String>> out, Set<String> uniqueTags) {
        if (depth > MAX_STRUCT_DEPTH) {
            return;
        }
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node node = kids.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if (out.size() >= MAX_STRUCT_KEYS) {
                return;
            }
            Element el = (Element) node;
            String tag = localName(el);
            uniqueTags.add(tag);

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("tagName", tag);
            entry.put("parentPath", parentPath);
            entry.put("depth", String.valueOf(depth));
            if (el.getNamespaceURI() != null) {
                entry.put("namespace", el.getNamespaceURI());
            }
            NamedNodeMap attrs = el.getAttributes();
            int added = 0;
            for (int a = 0; a < attrs.getLength() && added < 10; a++) {
                Attr attr = (Attr) attrs.item(a);
                String attrName = attr.getName();
                if ("xmlns".equals(attrName) || attrName.startsWith("xmlns:")) {
                    continue;
                }
                entry.put("attr." + localName(attr), attr.getValue());
                added++;
            }
            out.add(entry);

            String childPath = parentPath.isEmpty() ? tag : parentPath + "/" + tag;
            collectXmlChildren(el, childPath, depth + 1, out, uniqueTags);
        }
    }

    private static int countChildElements(Element parent) {
        int count = 0;
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == Node.ELEMENT_NODE) {
                count++;
            }
        }
        return count;
    }

    private static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }

    private static void setXmlFeature(DocumentBuilderFactory dbf, String feature, boolean value) {
        try {
            dbf.setFeature(feature, value);
        } catch (ParserConfigurationException ignored) {
            // Feature not recognized by this parser; proceed with safe defaults.
        }
    }

    // ── Tabular (CSV / TSV) and Markdown enrichment ─────────────────────────
    //
    // CSV/TSV become content_type=table with a TableCellGraphBuilder cell graph
    // (same contract the Excel loader produces). Markdown is typed so the extractor
    // emits DOCUMENT_SECTION / TASK_ITEM entities from its headings and task lists,
    // and any pipe table is converted to a cell graph here.

    /** Markdown pipe-table separator row, e.g. {@code |---|:--:|---|} (mirrors the extractor). */
    private static final Pattern PIPE_TABLE_SEPARATOR =
            Pattern.compile("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$");

    private void enrichDelimited(File file, Map<String, Object> meta, String delimiter) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            rows.add("\t".equals(delimiter)
                    ? new ArrayList<>(List.of(line.split("\t", -1)))
                    : parseCsvLine(line));
        }
        if (rows.isEmpty()) {
            return;
        }
        List<String> headers = rows.get(0);
        // Set documentType so TikaGenericGraphExtractor.resolveEntityType() returns the correct
        // entity type (ENTITY_CSV_DOCUMENT / ENTITY_TSV_DOCUMENT) instead of falling through to
        // the generic ENTITY_DOCUMENT when documentType is absent.
        String fileExt = "\t".equals(delimiter) ? "tsv" : "csv";
        meta.put(GraphConstants.META_DOCUMENT_TYPE, fileExt);
        meta.put(GraphConstants.META_CONTENT_TYPE, "table");
        meta.put(GraphConstants.META_TABLE_ROW_COUNT, rows.size() - 1);
        meta.put(GraphConstants.META_TABLE_COLUMN_COUNT, headers.size());
        meta.put(GraphConstants.META_TABLE_HEADERS, String.join(",", headers));

        TableCellGraphBuilder builder = new TableCellGraphBuilder()
                .namespace("file:" + file.getName())
                .tableName(stripExtension(file.getName()))
                .headers(headers)
                .firstRowIsHeader(false);
        for (int i = 1; i < rows.size(); i++) {
            builder.addRow(rows.get(i));
        }
        var graph = builder.build();
        if (!graph.getEntities().isEmpty()) {
            meta.put(GraphConstants.META_TABLE_GRAPH, TableCellGraphBuilder.toJson(graph));
        }
    }

    private void enrichMarkdown(File file, Map<String, Object> meta) throws IOException {
        String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        meta.put(GraphConstants.META_DOCUMENT_TYPE, "markdown");

        // YAML frontmatter (--- ... ---) at the very start of the file.
        String body = raw;
        if (raw.startsWith("---")) {
            int end = raw.indexOf("\n---", 3);
            if (end > 0) {
                int bodyStart = raw.indexOf('\n', end + 1);
                body = bodyStart >= 0 ? raw.substring(bodyStart + 1) : "";
                parseFrontmatter(raw.substring(3, end), meta, file.getName());
            }
        }

        String tableGraph = buildPipeTableGraph(body, file.getName());
        if (tableGraph != null) {
            meta.put(GraphConstants.META_TABLE_GRAPH, tableGraph);
        }
    }

    private void parseFrontmatter(String frontmatter, Map<String, Object> meta, String fileName) {
        try {
            JsonNode fm = YAML_MAPPER.readTree(frontmatter);
            if (fm == null || !fm.isObject()) {
                return;
            }
            Map<String, String> fmMap = new LinkedHashMap<>();
            fm.fields().forEachRemaining(e -> fmMap.put(e.getKey(),
                    e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
            // Map form is consumed by TikaGenericGraphExtractor; flattened keys are convenient downstream.
            meta.put("markdown.frontmatter", fmMap);
            fmMap.forEach((k, v) -> meta.put("markdown.frontmatter." + k, v));
        } catch (Exception ex) {
            logger.debug("Markdown frontmatter parse failed for '{}': {}", fileName, ex.toString());
        }
    }

    private String buildPipeTableGraph(String markdown, String fileName) {
        String[] lines = markdown.split("\n");
        for (int i = 1; i < lines.length; i++) {
            if (!PIPE_TABLE_SEPARATOR.matcher(lines[i].trim()).matches()) {
                continue;
            }
            List<String> headers = splitPipeRow(lines[i - 1]);
            if (headers.isEmpty()) {
                continue;
            }
            TableCellGraphBuilder builder = new TableCellGraphBuilder()
                    .namespace("markdown:" + fileName)
                    .tableName(stripExtension(fileName))
                    .headers(headers)
                    .firstRowIsHeader(false);
            for (int j = i + 1; j < lines.length; j++) {
                String row = lines[j].trim();
                if (row.isEmpty() || !row.contains("|")) {
                    break;
                }
                builder.addRow(splitPipeRow(lines[j]));
            }
            var graph = builder.build();
            return graph.getEntities().isEmpty() ? null : TableCellGraphBuilder.toJson(graph);
        }
        return null;
    }

    private static List<String> splitPipeRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}