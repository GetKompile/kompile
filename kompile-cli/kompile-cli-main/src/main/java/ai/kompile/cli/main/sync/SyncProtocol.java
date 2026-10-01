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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-lines request/response protocol shared by the sync client and the
 * {@code kompile sync serve} endpoint. One JSON object per line; every request
 * carries a monotonically increasing {@code id} echoed by the response.
 *
 * <p>Operations:</p>
 * <ul>
 *   <li>{@code hello} — negotiate protocol + capabilities (first message)</li>
 *   <li>{@code inventory} — list entries for a scope and component set</li>
 *   <li>{@code fetch} — base64 file payload for one entry</li>
 *   <li>{@code apply} — writes/deletes with expected-hash safety rechecks</li>
 * </ul>
 */
public final class SyncProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    /** Wire mapper: the protocol is one JSON object per line — never pretty-printed. */
    private static final ObjectMapper WIRE = new ObjectMapper();

    public static final String OP_HELLO = "hello";
    public static final String OP_INVENTORY = "inventory";
    public static final String OP_FETCH = "fetch";
    public static final String OP_APPLY = "apply";
    public static final String OP_ERROR = "error";

    private SyncProtocol() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Serializes a node as a single compact JSON line for the wire. */
    public static String toLine(JsonNode node) throws IOException {
        return WIRE.writeValueAsString(node);
    }

    public static ObjectNode request(int id, String op) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("op", op);
        return node;
    }

    public static ObjectNode ok(int id) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("ok", true);
        return node;
    }

    public static ObjectNode error(int id, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("ok", false);
        node.put("error", message);
        return node;
    }

    /** Writes one JSON line. */
    public interface Writer {
        void write(String line) throws IOException;
    }

    /** Sends a request and reads the matching response line. */
    public static JsonNode exchange(BufferedReader reader, Writer writer, ObjectNode request) throws IOException {
        writer.write(toLine(request));
        String line = reader.readLine();
        if (line == null) {
            throw new EOFException("sync peer closed the connection before responding (op="
                    + request.path("op").asText() + ")");
        }
        JsonNode response = MAPPER.readTree(line);
        if (response.path("ok").asBoolean(true) == false) {
            throw new IOException("sync peer rejected "
                    + request.path("op").asText() + ": " + response.path("error").asText("unknown"));
        }
        return response;
    }

    /** Parses inventory payloads: {component: [{path, sha256, size, unitId}...]}. */
    public static Map<String, java.util.List<SyncEntry>> parseInventory(JsonNode payload) {
        Map<String, java.util.List<SyncEntry>> inventory = new LinkedHashMap<>();
        payload.properties().forEach(componentEntry -> {
            String component = componentEntry.getKey();
            java.util.List<SyncEntry> entries = new java.util.ArrayList<>();
            for (JsonNode e : componentEntry.getValue()) {
                String path = e.path("path").asText();
                if (e.hasNonNull("deleted") && e.path("deleted").asBoolean()) {
                    entries.add(SyncEntry.deletion(component, path, unitId(component, path)));
                } else {
                    entries.add(SyncEntry.file(component, path,
                            e.path("sha256").asText(), e.path("size").asLong(), unitId(component, path)));
                }
            }
            inventory.put(component, entries);
        });
        return inventory;
    }

    private static String unitId(String component, String path) {
        int slash = path.indexOf('/');
        if (slash > 0 && SyncCatalog.SKILLS.equals(component)) {
            return component + ":" + path.substring(0, slash);
        }
        return null;
    }

    /** Serializes an inventory to the wire form. */
    public static ObjectNode writeInventory(Map<String, java.util.List<SyncEntry>> inventory) {
        ObjectNode root = MAPPER.createObjectNode();
        inventory.forEach((component, entries) -> {
            var array = root.putArray(component);
            for (SyncEntry e : entries) {
                var node = array.addObject();
                node.put("path", e.relativePath());
                if (e.isDeletion()) {
                    node.put("deleted", true);
                } else {
                    node.put("sha256", e.sha256());
                    node.put("size", e.size());
                }
                if (e.unitId() != null) {
                    node.put("unitId", e.unitId());
                }
            }
        });
        return root;
    }

    /** Reads lines as UTF-8 JSON, tolerant of blank lines. */
    public static BufferedReader reader(java.io.InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
