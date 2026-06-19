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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * SFTP remote folder client using JSch-compatible subprocess execution.
 *
 * <p>This implementation uses the system's {@code sftp} command to avoid
 * adding a heavy SSH library dependency. For production deployments with
 * high volume, a JSch or Apache Mina SSHD dependency can be substituted.</p>
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
        this.remotePath = path.isEmpty() ? "/" : path;

        // Verify sftp command is available
        try {
            Process proc = new ProcessBuilder("which", "sftp")
                    .redirectErrorStream(true).start();
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("sftp command check timed out");
            }
            if (proc.exitValue() != 0) {
                throw new IOException("sftp command not found on PATH");
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

        List<String> command = buildSshCommand("ls", "-l", dir);
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            if (password != null) {
                pb.environment().put("SSHPASS", password);
            }
            Process proc = pb.start();
            List<String> lines;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                lines = reader.lines().toList();
            }
            if (!proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("SFTP listing timed out for " + dir);
            }

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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SFTP listing interrupted", e);
        }
    }

    @Override
    public void download(String remoteKey, Path localDest) throws IOException {
        Files.createDirectories(localDest.getParent());
        List<String> command = buildScpCommand(remoteKey, localDest.toString());
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            if (password != null) {
                pb.environment().put("SSHPASS", password);
            }
            Process proc = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                output = String.join("\n", reader.lines().toList());
            }
            if (!proc.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("SCP download timed out for " + remoteKey);
            }
            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                throw new IOException("SCP download failed for " + remoteKey
                        + " (exit " + exitCode + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SFTP download interrupted for " + remoteKey, e);
        }
    }

    @Override
    public void close() {
        // No persistent connection to close
    }

    private List<String> buildSshCommand(String... sshArgs) {
        List<String> cmd = new ArrayList<>();
        if (password != null) {
            cmd.addAll(List.of("sshpass", "-e"));
        }
        cmd.add("ssh");
        cmd.addAll(List.of("-p", String.valueOf(port)));
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
        cmd.addAll(Arrays.asList(sshArgs));
        return cmd;
    }

    private List<String> buildScpCommand(String remoteSrc, String localDest) {
        List<String> cmd = new ArrayList<>();
        if (password != null) {
            cmd.addAll(List.of("sshpass", "-e"));
        }
        cmd.add("scp");
        cmd.addAll(List.of("-P", String.valueOf(port)));
        if (privateKeyPath != null) {
            cmd.addAll(List.of("-i", privateKeyPath));
        }
        if ("no".equalsIgnoreCase(strictHostKeyChecking)) {
            cmd.addAll(List.of("-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null"));
        } else if (knownHostsPath != null) {
            cmd.addAll(List.of("-o", "UserKnownHostsFile=" + knownHostsPath));
        }
        cmd.add(username + "@" + host + ":" + remoteSrc);
        cmd.add(localDest);
        return cmd;
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
