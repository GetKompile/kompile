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
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * SMB/CIFS remote folder client using the system's {@code smbclient} command.
 *
 * <p>Credentials are supplied through an owner-only authentication file rather than process
 * arguments. Remote paths are quoted and command-language metacharacters are rejected before
 * they reach {@code smbclient -c}.</p>
 *
 * <p>Properties:</p>
 * <ul>
 *   <li>{@code host} — SMB server hostname (required)</li>
 *   <li>{@code port} — SMB server port (default: 445)</li>
 *   <li>{@code username} — login username (required)</li>
 *   <li>{@code password} — login password (required)</li>
 *   <li>{@code domain} — Windows domain (optional)</li>
 * </ul>
 *
 * <p>The {@code pathOrUrl} is parsed as {@code //host/share/path} or just {@code share/path}.</p>
 */
public class SmbFolderClient implements RemoteFolderClient {

    private static final Logger log = LoggerFactory.getLogger(SmbFolderClient.class);

    private String host;
    private int port;
    private String username;
    private String domain;
    private String share;
    private String remotePath;
    private Path authenticationFile;

    @Override
    public SourceType sourceType() {
        return SourceType.SMB;
    }

    @Override
    public void connect(String pathOrUrl, Map<String, Object> properties) throws IOException {
        this.host = requireProp(properties, "host");
        this.port = intProp(properties, "port", 445);
        this.username = requireProp(properties, "username");
        String password = requireProp(properties, "password");
        this.domain = stringProp(properties, "domain", null);
        rejectCredentialLineBreaks(username, "username");
        rejectCredentialLineBreaks(password, "password");
        rejectCredentialLineBreaks(domain, "domain");

        // Parse pathOrUrl: "//host/share/path" or "smb://host/share/path" or "share/path"
        String path = pathOrUrl;
        if (path.startsWith("smb://")) {
            path = path.substring(4); // keep //
        }
        if (path.startsWith("//")) {
            // //host/share/path — strip host part (host already in properties)
            path = path.substring(2);
            int slash = path.indexOf('/');
            if (slash >= 0) {
                path = path.substring(slash + 1);
            }
        }

        // Split into share and path
        int slash = path.indexOf('/');
        if (slash > 0) {
            this.share = path.substring(0, slash);
            this.remotePath = path.substring(slash + 1);
        } else {
            this.share = path;
            this.remotePath = "";
        }

        this.share = safeCommandValue(this.share, "SMB share");
        this.remotePath = safeCommandValue(this.remotePath, "SMB remote path");

        // Verify smbclient is available without relying on a Unix-only `which` command.
        try {
            Process proc = new ProcessBuilder("smbclient", "--version")
                    .redirectErrorStream(true).start();
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("smbclient command check timed out");
            }
            if (proc.exitValue() != 0) {
                throw new IOException("smbclient command not found on PATH");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted checking for smbclient", e);
        }

        authenticationFile = createAuthenticationFile(username, password, domain);

        log.info("SMB client configured: {}@{}:{}/{}/{}", username, host, port, share, remotePath);
    }

    @Override
    public List<RemoteFileEntry> listFiles(int maxDepth) throws IOException {
        List<RemoteFileEntry> entries = new ArrayList<>();
        String startDir = remotePath;
        listRecursive(startDir, 0, maxDepth, entries);
        log.info("SMB listing complete: {} files in //{}/{}", entries.size(), host, share);
        return entries;
    }

    private void listRecursive(String dir, int currentDepth, int maxDepth,
                                List<RemoteFileEntry> results) throws IOException {
        if (maxDepth > 0 && currentDepth >= maxDepth) return;

        String pattern = dir.isEmpty() ? "*" : dir + "/*";
        String smbCommand = "ls " + quoteCommandArgument(pattern);
        List<String> cmd = buildSmbClientCommand(smbCommand);
        BoundedProcessRunner.Result process = BoundedProcessRunner.run(
                new ProcessBuilder(cmd), null, java.time.Duration.ofSeconds(120),
                "SMB listing " + dir, BoundedProcessRunner.MAX_LISTING_OUTPUT_BYTES);
        if (process.truncated()) {
            throw new IOException("SMB listing " + dir + " produced more than "
                    + BoundedProcessRunner.MAX_LISTING_OUTPUT_BYTES + " bytes of output");
        }
        if (process.exitCode() != 0) {
            throw new IOException("SMB listing failed for " + dir + " (exit "
                    + process.exitCode() + "): "
                    + SourceCredentialRedactor.redact(process.output()));
        }

        List<String> subdirs = new ArrayList<>();
        for (String line : process.lines()) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("Domain=") || line.startsWith("OS=")
                    || line.contains("blocks of size") || line.contains("blocks available")) {
                continue;
            }

            // smbclient ls output: "  filename       A     12345  Mon Jan  1 00:00:00 2024"
            // Attributes: D=dir, A=archive, H=hidden, S=system, R=readonly, N=normal
            if (line.contains("  D  ") || line.matches(".*\\bD\\b.*")) {
                String name = extractSmbName(line);
                if (name != null && !name.equals(".") && !name.equals("..")) {
                    String subPath = dir.isEmpty() ? name : dir + "/" + name;
                    subdirs.add(subPath);
                }
            } else {
                String name = extractSmbName(line);
                if (name == null) continue;

                long size = extractSmbSize(line);
                String normalizedPath = dir.isEmpty() ? name : dir + "/" + name;

                results.add(new RemoteFileEntry(
                        normalizedPath, name, size, 0L, null, null));
            }
        }

        for (String subdir : subdirs) {
            listRecursive(subdir, currentDepth + 1, maxDepth, results);
        }
    }

    @Override
    public void download(String remoteKey, Path localDest) throws IOException {
        Files.createDirectories(localDest.getParent());
        String smbPath = safeCommandValue(remoteKey.replace('\\', '/'), "SMB remote key");
        String localPath = safeCommandValue(
                localDest.toAbsolutePath().toString(), "SMB local destination");
        String smbCommand = "get " + quoteCommandArgument(smbPath) + " "
                + quoteCommandArgument(localPath);
        List<String> cmd = buildSmbClientCommand(smbCommand);
        BoundedProcessRunner.Result process = BoundedProcessRunner.run(
                new ProcessBuilder(cmd), null, java.time.Duration.ofSeconds(300),
                "SMB download " + remoteKey);
        if (process.exitCode() != 0 || !Files.exists(localDest)) {
            throw new IOException("SMB download failed for " + remoteKey
                    + " (exit " + process.exitCode() + "): "
                    + SourceCredentialRedactor.redact(process.output()));
        }
    }

    @Override
    public void close() {
        if (authenticationFile != null) {
            try {
                Files.deleteIfExists(authenticationFile);
            } catch (IOException e) {
                log.warn("Could not remove temporary SMB authentication file {}", authenticationFile, e);
            } finally {
                authenticationFile = null;
            }
        }
    }

    private List<String> buildSmbClientCommand(String smbCommand) throws IOException {
        if (authenticationFile == null || !Files.isRegularFile(authenticationFile)) {
            throw new IOException("SMB authentication file is unavailable");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("smbclient");
        cmd.add("//" + host + "/" + share);
        cmd.addAll(List.of("-p", String.valueOf(port)));
        cmd.addAll(List.of("-A", authenticationFile.toString()));
        cmd.addAll(List.of("-c", smbCommand));
        return cmd;
    }

    private static Path createAuthenticationFile(
            String username, String password, String domain) throws IOException {
        Path file = Files.createTempFile("kompile-smb-auth-", ".conf");
        StringBuilder contents = new StringBuilder()
                .append("username = ").append(username).append('\n')
                .append("password = ").append(password).append('\n');
        if (domain != null && !domain.isBlank()) {
            contents.append("domain = ").append(domain).append('\n');
        }
        Files.writeString(file, contents, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows inherits the current user's temporary-directory ACL.
        }
        file.toFile().deleteOnExit();
        return file;
    }

    static String quoteCommandArgument(String value) throws IOException {
        return "\"" + safeCommandValue(value, "SMB command argument") + "\"";
    }

    private static String safeCommandValue(String value, String label) throws IOException {
        if (value == null) return "";
        if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf(';') >= 0 || value.indexOf('!') >= 0 || value.indexOf('"') >= 0) {
            throw new IOException(label + " contains unsupported command characters");
        }
        return value;
    }

    private static void rejectCredentialLineBreaks(String value, String label) throws IOException {
        if (value != null && (value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new IOException("SMB " + label + " contains an invalid line break");
        }
    }

    private static String extractSmbName(String line) {
        // Name is the first non-whitespace token before the attributes
        String trimmed = line.trim();
        int firstSpace = trimmed.indexOf("  ");
        if (firstSpace < 0) return null;
        return trimmed.substring(0, firstSpace).trim();
    }

    private static long extractSmbSize(String line) {
        // Look for a number that's likely the file size (after attributes, before date)
        String[] parts = line.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) {
            try {
                return Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                // Expected: iterating tokens until we find a numeric file size
                log.trace("Token '{}' in SMB line is not a numeric size, trying next", parts[i]);
            }
        }
        return -1;
    }

    private static String requireProp(Map<String, Object> props, String key) throws IOException {
        Object v = props.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IOException("Required SMB property '" + key + "' is missing");
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
