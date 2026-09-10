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

package ai.kompile.crawler.remote;

import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.core.crawl.graph.SourceCredentialRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * SFTP remote folder client using OpenSSH batch-mode subprocess execution.
 *
 * <p>All remote operations are sent to {@code sftp -b -}; paths never become a remote shell
 * command. Passwords use {@code sshpass -e} and therefore never appear in process arguments.</p>
 *
 * <p>Properties:</p>
 * <ul>
 *   <li>{@code host} — SFTP server hostname (required)</li>
 *   <li>{@code port} — SFTP server port (default: 22)</li>
 *   <li>{@code username} — login username (required)</li>
 *   <li>{@code password} — login password (optional, uses key auth if absent)</li>
 *   <li>{@code privateKeyPath} — path to SSH private key (optional)</li>
 *   <li>{@code knownHostsPath} — path to known_hosts file (optional)</li>
 *   <li>{@code strictHostKeyChecking} — "yes"/"no" (default: "yes")</li>
 * </ul>
 */
public class SftpFolderClient implements RemoteFolderClient {

    private static final Logger log = LoggerFactory.getLogger(SftpFolderClient.class);

    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKeyPath;
    private String remotePath;
    private String knownHostsPath;
    private String strictHostKeyChecking;

    @Override
    public SourceType sourceType() {
        return SourceType.SFTP;
    }

    @Override
    public void connect(String pathOrUrl, Map<String, Object> properties) throws IOException {
        this.host = requireProp(properties, "host");
        this.port = intProp(properties, "port", 22);
        this.username = requireProp(properties, "username");
        this.password = stringProp(properties, "password", null);
        this.privateKeyPath = stringProp(properties, "privateKeyPath", null);
        this.knownHostsPath = stringProp(properties, "knownHostsPath", null);
        this.strictHostKeyChecking = stringProp(properties, "strictHostKeyChecking", "yes");
        if (!"yes".equalsIgnoreCase(strictHostKeyChecking)
                && !"no".equalsIgnoreCase(strictHostKeyChecking)) {
            throw new IOException("strictHostKeyChecking must be 'yes' or 'no'");
        }
        this.host = safeHost(host);
        this.username = safeUsername(username);

        // Parse remote path
        String path = pathOrUrl;
        if (path.startsWith("sftp://")) {
            // sftp://user@host:port/path
            path = path.substring(7);
            int at = path.indexOf('@');
            if (at >= 0) path = path.substring(at + 1);
            int colon = path.indexOf(':');
            int slash = path.indexOf('/');
            if (slash >= 0) {
                path = path.substring(slash);
            } else {
                path = "/";
            }
        }
        this.remotePath = safeBatchPath(path.isEmpty() ? "/" : path, "remote path");

        // Verify sftp command is available
        try {
            Process proc = new ProcessBuilder("sftp", "-h")
                    .redirectErrorStream(true).start();
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("sftp command check timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted checking for sftp command", e);
        }

        log.info("SFTP client configured: {}@{}:{}{}", username, host, port, remotePath);
    }

    @Override
    public List<RemoteFileEntry> listFiles(int maxDepth) throws IOException {
        List<RemoteFileEntry> entries = new ArrayList<>();
        listRecursive(remotePath, 0, maxDepth, entries);
        log.info("SFTP listing complete: {} files in {}:{}", entries.size(), host, remotePath);
        return entries;
    }

    private void listRecursive(String dir, int currentDepth, int maxDepth,
                                List<RemoteFileEntry> results) throws IOException {
        if (maxDepth > 0 && currentDepth >= maxDepth) return;

        List<String> lines = executeBatch(
                "ls -l " + quoteBatchPath(dir), 120, "listing " + dir,
                BoundedProcessRunner.MAX_LISTING_OUTPUT_BYTES);

        List<String> subdirs = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("total")) continue;
            // Parse ls -l output: permissions links owner group size month day time/year name
            String[] parts = line.split("\\s+", 9);
            if (parts.length < 9) continue;

            String perms = parts[0];
            String name = parts[8];
            if (name.equals(".") || name.equals("..")) continue;

            String fullPath = dir.endsWith("/") ? dir + name : dir + "/" + name;

            if (perms.startsWith("d")) {
                subdirs.add(fullPath);
            } else if (perms.startsWith("-")) {
                long size = -1;
                try { size = Long.parseLong(parts[4]); } catch (NumberFormatException e) {
                    log.debug("Could not parse SFTP file size from '{}': {}", parts[4], e.getMessage());
                }

                results.add(new RemoteFileEntry(
                        fullPath, name, size, 0L, null, null));
            }
        }

        for (String subdir : subdirs) {
            listRecursive(subdir, currentDepth + 1, maxDepth, results);
        }
    }

    @Override
    public void download(String remoteKey, Path localDest) throws IOException {
        Files.createDirectories(localDest.getParent());
        String safeRemote = safeBatchPath(remoteKey, "remote key");
        String safeLocal = safeBatchPath(localDest.toAbsolutePath().toString(), "local destination");
        executeBatch("get " + quoteBatchPath(safeRemote) + " " + quoteBatchPath(safeLocal),
                300, "download " + remoteKey);
        if (!Files.isRegularFile(localDest)) {
            throw new IOException("SFTP download did not create " + localDest);
        }
    }

    @Override
    public void close() {
        // No persistent connection to close
    }

    private List<String> buildSftpCommand() {
        List<String> cmd = new ArrayList<>();
        if (password != null) {
            cmd.addAll(List.of("sshpass", "-e"));
        }
        cmd.add("sftp");
        cmd.addAll(List.of("-q", "-b", "-", "-P", String.valueOf(port)));
        if (privateKeyPath != null) {
            cmd.addAll(List.of("-i", privateKeyPath));
        }
        if ("no".equalsIgnoreCase(strictHostKeyChecking)) {
            cmd.addAll(List.of("-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null"));
        } else if (knownHostsPath != null) {
            cmd.addAll(List.of("-o", "UserKnownHostsFile=" + knownHostsPath));
        }
        cmd.add(username + "@" + host);
        return cmd;
    }

    private List<String> executeBatch(
            String command, long timeoutSeconds, String operation) throws IOException {
        return executeBatch(command, timeoutSeconds, operation,
                BoundedProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);
    }

    private List<String> executeBatch(
            String command, long timeoutSeconds, String operation, int maxOutputBytes)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(buildSftpCommand()).redirectErrorStream(true);
        if (password != null) {
            builder.environment().put("SSHPASS", password);
        }
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                builder, command + "\nbye\n", java.time.Duration.ofSeconds(timeoutSeconds),
                "SFTP " + operation, maxOutputBytes);
        if (result.truncated()) {
            throw new IOException("SFTP " + operation
                    + " produced more than " + maxOutputBytes + " bytes of connector output");
        }
        if (result.exitCode() != 0) {
            throw new IOException("SFTP " + operation + " failed (exit "
                    + result.exitCode() + "): "
                    + SourceCredentialRedactor.redact(result.output()));
        }
        return result.lines();
    }

    static String quoteBatchPath(String value) throws IOException {
        return "\"" + safeBatchPath(value, "batch path") + "\"";
    }

    private static String safeBatchPath(String value, String label) throws IOException {
        if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('"') >= 0
                || value.indexOf(';') >= 0 || value.startsWith("!")) {
            throw new IOException("SFTP " + label + " contains unsupported command characters");
        }
        return value;
    }

    private static String safeHost(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._:\\[\\]-]+")) {
            throw new IOException("SFTP host contains unsupported characters");
        }
        return value;
    }

    private static String safeUsername(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._+-]+")) {
            throw new IOException("SFTP username contains unsupported characters");
        }
        return value;
    }

    private static String requireProp(Map<String, Object> props, String key) throws IOException {
        Object v = props.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IOException("Required SFTP property '" + key + "' is missing");
        }
        return v.toString();
    }

    private static String stringProp(Map<String, Object> props, String key, String defaultValue) {
        Object v = props.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : defaultValue;
    }

    private static int intProp(Map<String, Object> props, String key, int defaultValue) {
        Object v = props.get(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }
}
