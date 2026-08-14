import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Computes independent semantic Native Image and side-loaded native payload
 * fingerprints for a Maven runtime classpath.
 *
 * <p>The AOT fingerprint hashes archive member names and uncompressed contents
 * while deliberately excluding binary payloads that Kompile excludes from
 * Native Image resources and loads from the distribution at runtime. The
 * runtime fingerprint hashes only those excluded payloads. ZIP timestamps,
 * compression choices, absolute Maven repository paths, and native-only
 * rebuilds therefore cannot invalidate Graal analysis.</p>
 */
public final class NativeImageDependencyFingerprint {

    private static final Pattern NATIVE_PAYLOAD = Pattern.compile(
            "(^|/)[^/]+\\.(?:so(?:\\..*)?|dylib|dll|a|lib|o|obj|vsa|vso)$");
    private static final Set<String> ARCHIVE_EXTENSIONS =
            Set.of(".jar", ".zip", ".aar");

    private NativeImageDependencyFingerprint() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "Usage: NativeImageDependencyFingerprint.java <classpath-file> [manifest-cache-dir]");
        }

        Path classpathFile = Path.of(args[0]).toAbsolutePath().normalize();
        Path manifestCache = args.length == 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : null;
        if (manifestCache != null) {
            Files.createDirectories(manifestCache);
            if (!Files.isDirectory(manifestCache, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(manifestCache)) {
                throw new IOException("Unsafe native dependency manifest cache: " + manifestCache);
            }
            manifestCache = manifestCache.toRealPath(LinkOption.NOFOLLOW_LINKS);
        }
        String classpath = Files.readString(classpathFile, StandardCharsets.UTF_8).trim();
        if (classpath.isEmpty()) {
            throw new IllegalArgumentException("Runtime classpath is empty: " + classpathFile);
        }

        LinkedHashSet<Path> dependencyPaths = new LinkedHashSet<>();
        for (String rawPath : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (!rawPath.isBlank()) {
                dependencyPaths.add(Path.of(rawPath).toAbsolutePath().normalize());
            }
        }
        if (dependencyPaths.isEmpty()) {
            throw new IllegalArgumentException("Runtime classpath has no entries: " + classpathFile);
        }

        FingerprintWriter aot = new FingerprintWriter("kompile-native-aot-dependencies-v1");
        FingerprintWriter runtime = new FingerprintWriter("kompile-native-runtime-payload-v1");
        int aotDependencyIndex = 0;
        int runtimeDependencyIndex = 0;
        int nativeMemberCount = 0;

        for (Path dependency : dependencyPaths) {
            validateDependency(dependency);
            DependencyMembers members = Files.isDirectory(dependency, LinkOption.NOFOLLOW_LINKS)
                    ? directoryMembers(dependency)
                    : fileMembers(dependency, manifestCache);
            String label = dependency.getFileName().toString();

            if (members.aot().memberCount() > 0) {
                writeDependency(aot, ++aotDependencyIndex, label, members.aot());
            }
            if (members.runtime().memberCount() > 0) {
                writeDependency(runtime, ++runtimeDependencyIndex, label, members.runtime());
                nativeMemberCount += members.runtime().memberCount();
            }
        }

        aot.field("dependency-count", Integer.toString(aotDependencyIndex));
        runtime.field("dependency-count", Integer.toString(runtimeDependencyIndex));
        runtime.field("native-member-count", Integer.toString(nativeMemberCount));

        System.out.println("schema=kompile-native-dependency-fingerprints-v1");
        System.out.println("aot=" + aot.finish());
        System.out.println("runtime=" + runtime.finish());
        System.out.println("aot_dependencies=" + aotDependencyIndex);
        System.out.println("runtime_dependencies=" + runtimeDependencyIndex);
        System.out.println("native_members=" + nativeMemberCount);
    }

    private static void validateDependency(Path dependency) throws IOException {
        if (Files.isSymbolicLink(dependency)) {
            throw new IOException("Runtime classpath dependency is a symbolic link: " + dependency);
        }
        if (!Files.isRegularFile(dependency, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(dependency, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime classpath dependency is missing or unsafe: " + dependency);
        }
    }

    private static DependencyMembers fileMembers(Path file, Path manifestCache) throws IOException {
        String lowerName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean archive = ARCHIVE_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
        if (archive) {
            return cachedArchiveMembers(file, manifestCache);
        }

        Member member = new Member(file.getFileName().toString(), sha256(file), Files.size(file));
        if (isNativePayload(member.name())) {
            return dependencyMembers(List.of(), List.of(member));
        }
        return dependencyMembers(List.of(member), List.of());
    }

    private static DependencyMembers cachedArchiveMembers(Path archive, Path manifestCache)
            throws IOException {
        String identity = manifestCache == null ? null : archiveSnapshotIdentity(archive);
        Path receipt = identity == null ? null : manifestCache.resolve(identity + ".receipt");
        if (receipt != null) {
            DependencyMembers cached = readArchiveReceipt(receipt, identity);
            if (cached != null) {
                return cached;
            }
        }

        DependencyMembers scanned = archiveMembers(archive);
        if (identity != null) {
            String identityAfterScan = archiveSnapshotIdentity(archive);
            if (!identity.equals(identityAfterScan)) {
                throw new IOException("Runtime dependency changed while it was fingerprinted: " + archive);
            }
            writeArchiveReceipt(manifestCache, receipt, identity, scanned);
        }
        return scanned;
    }

    private static String archiveSnapshotIdentity(Path archive) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                archive, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        FileTime changeTime;
        try {
            Object value = Files.getAttribute(archive, "unix:ctime", LinkOption.NOFOLLOW_LINKS);
            if (!(value instanceof FileTime fileTime)) {
                return null;
            }
            changeTime = fileTime;
        } catch (UnsupportedOperationException unsupported) {
            return null;
        }

        String snapshot = String.join("\n",
                "schema=kompile-native-archive-snapshot-v1",
                "path=" + archive.toRealPath(LinkOption.NOFOLLOW_LINKS),
                "size=" + attributes.size(),
                "modified=" + attributes.lastModifiedTime().toInstant(),
                "changed=" + changeTime.toInstant(),
                "file-key=" + String.valueOf(attributes.fileKey()));
        return sha256Text(snapshot);
    }

    private static DependencyMembers readArchiveReceipt(Path receipt, String expectedIdentity)
            throws IOException {
        if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(receipt)) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : Files.readAllLines(receipt, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator <= 0 || fields.put(line.substring(0, separator),
                    line.substring(separator + 1)) != null) {
                return null;
            }
        }
        if (fields.size() != 6
                || !"kompile-native-archive-manifest-v1".equals(fields.get("schema"))
                || !expectedIdentity.equals(fields.get("snapshot"))
                || !isSha256(fields.get("aot_sha256"))
                || !isSha256(fields.get("runtime_sha256"))) {
            return null;
        }
        try {
            int aotMembers = Integer.parseInt(fields.get("aot_members"));
            int runtimeMembers = Integer.parseInt(fields.get("runtime_members"));
            if (aotMembers < 0 || runtimeMembers < 0) {
                return null;
            }
            return new DependencyMembers(
                    new LayerFingerprint(fields.get("aot_sha256"), aotMembers),
                    new LayerFingerprint(fields.get("runtime_sha256"), runtimeMembers));
        } catch (NumberFormatException invalidReceipt) {
            return null;
        }
    }

    private static void writeArchiveReceipt(
            Path manifestCache,
            Path receipt,
            String identity,
            DependencyMembers members) throws IOException {
        Files.createDirectories(manifestCache);
        if (!Files.isDirectory(manifestCache, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifestCache)) {
            throw new IOException("Unsafe native dependency manifest cache: " + manifestCache);
        }
        Path temporary = Files.createTempFile(manifestCache, "." + identity + ".", ".tmp");
        try {
            Files.write(temporary, List.of(
                    "schema=kompile-native-archive-manifest-v1",
                    "snapshot=" + identity,
                    "aot_sha256=" + members.aot().sha256(),
                    "aot_members=" + members.aot().memberCount(),
                    "runtime_sha256=" + members.runtime().sha256(),
                    "runtime_members=" + members.runtime().memberCount()),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, receipt,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, receipt, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static DependencyMembers archiveMembers(Path archive) throws IOException {
        List<Member> aotMembers = new ArrayList<>();
        List<Member> runtimeMembers = new ArrayList<>();
        Set<String> memberNames = new HashSet<>();

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries;
            try (Stream<? extends ZipEntry> stream = zip.stream()) {
                entries = stream
                        .filter(entry -> !entry.isDirectory())
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .toList();
            }
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                validateMemberName(archive, name);
                if (!memberNames.add(name)) {
                    throw new IOException("Duplicate archive member is not cache-safe: "
                            + archive + "!/" + name);
                }
                Member member;
                try (InputStream input = zip.getInputStream(entry)) {
                    member = new Member(name, sha256(input), entry.getSize());
                }
                if (isNativePayload(name)) {
                    runtimeMembers.add(member);
                } else {
                    aotMembers.add(member);
                }
            }
        }
        return dependencyMembers(aotMembers, runtimeMembers);
    }

    private static DependencyMembers directoryMembers(Path root) throws IOException {
        List<Member> aotMembers = new ArrayList<>();
        List<Member> runtimeMembers = new ArrayList<>();
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream
                    .sorted(Comparator.comparing(path -> normalizeRelative(root, path)))
                    .toList();
        }

        for (Path path : paths) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("Runtime classpath directory contains a symbolic link: " + path);
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String relative = normalizeRelative(root, path);
            Member member = new Member(relative, sha256(path), Files.size(path));
            if (isNativePayload(relative)) {
                runtimeMembers.add(member);
            } else {
                aotMembers.add(member);
            }
        }
        return dependencyMembers(aotMembers, runtimeMembers);
    }

    private static DependencyMembers dependencyMembers(
            List<Member> aotMembers, List<Member> runtimeMembers) {
        return new DependencyMembers(
                fingerprintMembers(aotMembers), fingerprintMembers(runtimeMembers));
    }

    private static LayerFingerprint fingerprintMembers(List<Member> members) {
        FingerprintWriter writer = new FingerprintWriter("kompile-native-archive-members-v1");
        writer.field("member-count", Integer.toString(members.size()));
        for (Member member : members) {
            writer.field("member-name", member.name());
            writer.field("member-size", Long.toString(member.size()));
            writer.field("member-sha256", member.sha256());
        }
        return new LayerFingerprint(writer.finish(), members.size());
    }

    private static String normalizeRelative(Path root, Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static void validateMemberName(Path archive, String name) throws IOException {
        if (name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\n') >= 0
                || name.indexOf('\r') >= 0) {
            throw new IOException("Unsafe archive member name: " + archive);
        }
    }

    private static boolean isNativePayload(String name) {
        return NATIVE_PAYLOAD.matcher(name).find();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void writeDependency(
            FingerprintWriter writer, int index, String label, LayerFingerprint members) {
        writer.field("dependency-index", Integer.toString(index));
        writer.field("dependency-label", label);
        writer.field("member-count", Integer.toString(members.memberCount()));
        writer.field("members-sha256", members.sha256());
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        }
    }

    private static String sha256Text(String value) {
        MessageDigest digest = newDigest();
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return hex(digest.digest());
    }

    private static String sha256(InputStream input) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[1024 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private record Member(String name, String sha256, long size) {
    }

    private record LayerFingerprint(String sha256, int memberCount) {
    }

    private record DependencyMembers(LayerFingerprint aot, LayerFingerprint runtime) {
    }

    private static final class FingerprintWriter {
        private final MessageDigest digest = newDigest();

        private FingerprintWriter(String schema) {
            field("schema", schema);
        }

        private void field(String name, String value) {
            update(name);
            update(value);
        }

        private void update(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }

        private String finish() {
            return hex(digest.digest());
        }
    }
}
