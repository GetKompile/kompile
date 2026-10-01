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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scans a scope root and produces a stable, path-sorted inventory of syncable
 * files for the requested component families.
 *
 * <p>Traversal rules: regular non-symlink files only, no package directories
 * or hidden entries beyond the first level, with {@code graph.jsonl.tmp} style
 * temporaries and any {@link SyncCatalog#NEVER_SYNCED_NAMES} entry excluded.</p>
 */
public final class SyncInventoryScanner {

    private SyncInventoryScanner() {
    }

    public static Map<String, List<SyncEntry>> scan(Path scopeRoot, List<String> components) throws IOException {
        Map<String, List<SyncEntry>> inventory = new TreeMap<>();
        for (String component : components) {
            Path root = scopeRoot.resolve(SyncCatalog.componentDir(scopeKind(scopeRoot), component));
            List<SyncEntry> entries = new ArrayList<>();
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                scanComponent(root, component, entries);
            }
            entries.sort(Comparator.comparing(SyncEntry::relativePath));
            inventory.put(component, entries);
        }
        return inventory;
    }

    private static String scopeKind(Path scopeRoot) {
        Path parent = scopeRoot.getParent();
        if (parent == null) {
            return SyncScope.GLOBAL;
        }
        return ".kompile".equals(parent.getFileName() == null ? "" : parent.getFileName().toString())
                ? SyncScope.PROJECT : SyncScope.GLOBAL;
    }

    private static void scanComponent(Path componentRoot, String component, List<SyncEntry> out) throws IOException {
        Files.walkFileTree(componentRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(componentRoot)) {
                    if (name.startsWith(".") || SyncCatalog.NEVER_SYNCED_NAMES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (name.endsWith(".tmp") || SyncCatalog.NEVER_SYNCED_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = componentRoot.relativize(file).toString().replace('\\', '/');
                String unitId = unitIdFor(component, relative);
                try {
                    String sha = sha256(file);
                    out.add(SyncEntry.file(component, relative, sha, Files.size(file), unitId));
                } catch (IOException e) {
                    // Unreadable files are skipped rather than failing the whole inventory.
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Logical unit identity. Skills and roles are packaged whole: every file
     * under {@code <name>/} or {@code <name>.<ext>} belongs to one unit.
     */
    static String unitIdFor(String component, String relativePath) {
        if (SyncCatalog.SKILLS.equals(component)) {
            int slash = relativePath.indexOf('/');
            if (slash > 0) {
                return component + ":" + relativePath.substring(0, slash);
            }
            int dot = relativePath.lastIndexOf('.');
            return component + ":" + (dot > 0 ? relativePath.substring(0, dot) : relativePath);
        }
        return null;
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder sb = new StringBuilder(digest.getDigestLength() * 2);
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
