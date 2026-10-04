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

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Drives one sync session over a {@link SyncTransport}: hello handshake,
 * inventory exchange, plan computation, payload transfer, and optional baseline
 * update. Direction filters which actions apply; conflicts abort before any
 * write happens.
 */
public final class SyncSession implements AutoCloseable {

    /** Direction filter for applied actions. */
    public enum Direction { PUSH, PULL, BOTH }

    /** Supplies local file bytes for push operations. */
    public interface LocalSource {
        byte[] read(SyncEntry entry) throws IOException;
    }

    /** Applies pulled payloads to the local scope. */
    public interface LocalSink {
        void write(SyncEntry entry, byte[] bytes) throws IOException;

        void delete(SyncEntry entry) throws IOException;
    }

    private final SyncTransport transport;
    private final Map<String, List<SyncEntry>> localInventory;
    private final Map<String, String> baseline;
    private final LocalSource localSource;
    private final java.util.function.Consumer<String> log;

    private BufferedReader reader;
    private SyncProtocol.Writer writer;
    private Map<String, List<SyncEntry>> remoteInventory;
    private SyncPlan plan;
    private int nextRequestId = 10;
    private String remoteMountIdentity;

    String remoteMountIdentity() { return remoteMountIdentity; }

    public SyncSession(SyncTransport transport,
                       Map<String, List<SyncEntry>> localInventory,
                       Map<String, String> baseline,
                       LocalSource localSource,
                       java.util.function.Consumer<String> log) {
        this.transport = transport;
        this.localInventory = localInventory;
        this.baseline = baseline;
        this.localSource = localSource;
        this.log = log == null ? s -> { } : log;
    }

    private BufferedReader reader() throws IOException {
        if (reader == null) {
            reader = SyncProtocol.reader(transport.stdout());
        }
        return reader;
    }

    private SyncProtocol.Writer writer() throws IOException {
        if (writer == null) {
            OutputStream out = transport.stdin();
            writer = line -> {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            };
        }
        return writer;
    }

    /** Performs the hello handshake; throws when peer is incompatible. */
    public String handshake() throws IOException {
        ObjectNode hello = SyncProtocol.request(1, SyncProtocol.OP_HELLO);
        hello.put("protocol", SyncCatalog.PROTOCOL);
        hello.put("version", SyncCatalog.PROTOCOL_VERSION);
        var response = SyncProtocol.exchange(reader(), writer(), hello);
        String protocol = response.path("protocol").asText("");
        int version = response.path("version").asInt(-1);
        if (!SyncCatalog.PROTOCOL.equals(protocol) || version != SyncCatalog.PROTOCOL_VERSION) {
            throw new IOException("Incompatible sync peer: " + protocol + " v" + version
                    + " (expected " + SyncCatalog.PROTOCOL + " v" + SyncCatalog.PROTOCOL_VERSION + ")");
        }
        remoteMountIdentity = response.path("mountIdentity").asText("");
        return response.path("hostname").asText(transport.describe());
    }

    /** Requests the remote inventory for a scope and component set. */
    public Map<String, List<SyncEntry>> remoteInventory(String scope, List<String> components) throws IOException {
        ObjectNode request = SyncProtocol.request(2, SyncProtocol.OP_INVENTORY);
        request.put("scope", scope);
        var array = request.putArray("components");
        components.forEach(array::add);
        var response = SyncProtocol.exchange(reader(), writer(), request);
        var received = SyncProtocol.parseInventory(response.path("inventory"));
        for (var component : received.entrySet()) {
            if (!components.contains(component.getKey())) throw new IOException("Peer returned an unrequested component.");
            for (SyncEntry entry : component.getValue()) {
                if (!component.getKey().equals(entry.component())) throw new IOException("Peer inventory component mismatch.");
            }
        }
        this.remoteInventory = received;
        return remoteInventory;
    }

    /** Computes the transfer plan from both inventories and the baseline. */
    public SyncPlan computePlan(boolean deletionsOptIn) {
        if (remoteInventory == null) {
            throw new IllegalStateException("call remoteInventory() before computePlan()");
        }
        this.plan = new SyncEngine(log).plan(localInventory, remoteInventory, baseline, deletionsOptIn);
        return plan;
    }

    /**
     * Applies the plan. Push actions send payloads over the protocol; pull
     * actions fetch, hash-verify, and hand bytes to {@code localSink}.
     * Deletions run only when {@code allowDelete} is set.
     */
    public void apply(LocalSink localSink, Direction direction, boolean allowDelete) throws IOException {
        apply(plan, localSink, direction, allowDelete);
    }

    void apply(SyncPlan selectedPlan, LocalSink localSink, Direction direction, boolean allowDelete) throws IOException {
        if (selectedPlan == null) {
            throw new IllegalStateException("call computePlan() before apply()");
        }
        for (SyncPlan.Item item : selectedPlan.items()) {
            switch (item.action()) {
                case COPY_TO_REMOTE: {
                    if (direction == Direction.PULL) {
                        continue;
                    }
                    byte[] bytes = localSource.read(item.entry());
                    if (!sha256(bytes).equals(item.localHash())) {
                        throw new IOException("Sync source changed since inventory; retry: " + item.entry().packagePath());
                    }
                    ObjectNode apply = SyncProtocol.request(nextRequestId++, SyncProtocol.OP_APPLY);
                    apply.put("action", "write");
                    apply.set("entry", entryNode(item.entry()));
                    apply.put("dataBase64", Base64.getEncoder().encodeToString(bytes));
                    SyncProtocol.exchange(reader(), writer(), apply);
                    break;
                }
                case DELETE_REMOTE: {
                    if (direction == Direction.PULL || !allowDelete) {
                        continue;
                    }
                    ObjectNode apply = SyncProtocol.request(nextRequestId++, SyncProtocol.OP_APPLY);
                    apply.put("action", "delete");
                    apply.set("entry", entryNode(item.entry()));
                    SyncProtocol.exchange(reader(), writer(), apply);
                    break;
                }
                case COPY_TO_LOCAL: {
                    if (direction == Direction.PUSH) {
                        continue;
                    }
                    ObjectNode fetchOp = SyncProtocol.request(nextRequestId++, SyncProtocol.OP_FETCH);
                    fetchOp.set("entry", entryNode(item.entry()));
                    var response = SyncProtocol.exchange(reader(), writer(), fetchOp);
                    byte[] bytes = Base64.getDecoder().decode(response.path("dataBase64").asText());
                    String expected = item.remoteHash();
                    if (expected == null || expected.length() != 64) {
                        throw new IOException("peer returned no usable hash for " + item.entry().packagePath());
                    }
                    String actual = sha256(bytes);
                    if (!expected.equals(actual)) {
                        throw new IOException("hash mismatch on pull for " + item.entry().packagePath());
                    }
                    localSink.write(item.entry(), bytes);
                    break;
                }
                case DELETE_LOCAL: {
                    if (direction == Direction.PUSH || !allowDelete) {
                        continue;
                    }
                    localSink.delete(item.entry());
                    break;
                }
                default:
                    break;
            }
        }
    }

    /** The computed plan (after {@link #computePlan(boolean)}). */
    public SyncPlan plan() {
        return plan;
    }

    private static ObjectNode entryNode(SyncEntry entry) {
        ObjectNode node = SyncProtocol.mapper().createObjectNode();
        node.put("component", entry.component());
        node.put("path", entry.relativePath());
        if (entry.isDeletion()) {
            node.put("deleted", true);
        } else {
            node.put("sha256", entry.sha256());
            node.put("size", entry.size());
        }
        return node;
    }

    /** SHA-256 over in-memory bytes, hex-encoded. */
    public static String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(bytes)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (Exception e) {
            // Transport teardown is best-effort; never mask the sync outcome.
        }
    }
}
