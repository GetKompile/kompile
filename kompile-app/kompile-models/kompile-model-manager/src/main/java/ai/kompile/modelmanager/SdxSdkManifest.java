/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.modelmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict schema-v1 model and resolver for {@code sdx-sdk-manifest.json}. */
public final class SdxSdkManifest {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "sdx-sdk-manifest.json";

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "releaseVersion", "releaseTag", "artifacts");
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "component", "packageRole", "platform", "variant", "classifier",
            "fileName", "packaging", "sha256", "size");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Comparator<Artifact> CANONICAL_ORDER = Comparator
            .comparing(Artifact::component)
            .thenComparing(Artifact::packageRole)
            .thenComparing(Artifact::platform)
            .thenComparing(Artifact::variant)
            .thenComparing(Artifact::classifier)
            .thenComparing(Artifact::fileName);

    private final String releaseVersion;
    private final String releaseTag;
    private final List<Artifact> artifacts;

    private SdxSdkManifest(String releaseVersion, String releaseTag, List<Artifact> artifacts) {
        this.releaseVersion = releaseVersion;
        this.releaseTag = releaseTag;
        this.artifacts = List.copyOf(artifacts);
    }

    public String releaseVersion() { return releaseVersion; }
    public String releaseTag() { return releaseTag; }
    public List<Artifact> artifacts() { return artifacts; }

    public static SdxSdkManifest parse(ObjectMapper mapper, InputStream input, String expectedVersion)
            throws IOException {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(input, "input");
        JsonNode root = mapper.readTree(input);
        requireObject(root, "manifest");
        rejectUnknown(root, TOP_LEVEL_FIELDS, "manifest");
        int schemaVersion = required(root, "schemaVersion").asInt(Integer.MIN_VALUE);
        if (schemaVersion != SCHEMA_VERSION || !root.get("schemaVersion").isIntegralNumber()) {
            throw invalid("schemaVersion must be integer 1");
        }
        String releaseVersion = requiredText(root, "releaseVersion");
        requireToken(releaseVersion, "releaseVersion");
        if (expectedVersion != null && !expectedVersion.equals(releaseVersion)) {
            throw invalid("releaseVersion '" + releaseVersion + "' does not match requested version '" + expectedVersion + "'");
        }
        String releaseTag = requiredText(root, "releaseTag");
        String expectedTag = "sdk-v" + releaseVersion;
        if (!expectedTag.equals(releaseTag)) {
            throw invalid("releaseTag must be '" + expectedTag + "'");
        }
        JsonNode artifactsNode = required(root, "artifacts");
        if (!artifactsNode.isArray() || artifactsNode.isEmpty()) {
            throw invalid("artifacts must be a non-empty array");
        }

        List<Artifact> artifacts = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        Set<String> fileNames = new HashSet<>();
        for (int i = 0; i < artifactsNode.size(); i++) {
            JsonNode node = artifactsNode.get(i);
            requireObject(node, "artifacts[" + i + "]");
            rejectUnknown(node, ARTIFACT_FIELDS, "artifacts[" + i + "]");
            Artifact artifact = new Artifact(
                    Component.parse(requiredText(node, "component")).value,
                    PackageRole.parse(requiredText(node, "packageRole")).value,
                    requiredString(node, "platform"),
                    requiredString(node, "variant"),
                    requiredString(node, "classifier"),
                    requiredText(node, "fileName"),
                    Packaging.parse(requiredText(node, "packaging")).value,
                    requiredText(node, "sha256").toLowerCase(Locale.ROOT),
                    requiredLong(node, "size"));
            artifact.validate(i);
            if (artifact.isSelectable() && !identities.add(artifact.selectionIdentity())) {
                throw invalid("duplicate artifact selection identity: " + artifact.selectionIdentity());
            }
            if (!fileNames.add(artifact.fileName)) {
                throw invalid("duplicate artifact fileName: " + artifact.fileName);
            }
            artifacts.add(artifact);
        }
        List<Artifact> sorted = new ArrayList<>(artifacts);
        sorted.sort(CANONICAL_ORDER);
        if (!sorted.equals(artifacts)) {
            throw invalid("artifacts must be sorted by component, packageRole, platform, variant, classifier, fileName");
        }
        return new SdxSdkManifest(releaseVersion, releaseTag, artifacts);
    }

    public Artifact select(String component, String packageRole, String platform, String variant) throws IOException {
        Component parsedComponent = Component.parse(component);
        PackageRole parsedRole = PackageRole.parse(packageRole);
        String canonicalPlatform = canonicalPlatform(platform);
        List<Artifact> matches = artifacts.stream()
                .filter(a -> a.component.equals(parsedComponent.value))
                .filter(a -> a.packageRole.equals(parsedRole.value))
                .filter(a -> a.platform.equals(canonicalPlatform))
                .filter(a -> a.variant.equals(variant))
                .toList();
        if (matches.size() != 1) {
            throw new IOException("Expected exactly one SDK artifact for " +
                    String.join("/", component, packageRole, platform, variant) + ", found " + matches.size());
        }
        return matches.get(0);
    }

    public Artifact selectClassifier(String classifier) throws IOException {
        String canonicalClassifier = canonicalClassifier(classifier);
        List<Artifact> matches = artifacts.stream().filter(a -> a.classifier.equals(canonicalClassifier)).toList();
        if (matches.size() != 1) {
            throw new IOException("Classifier '" + classifier + "' is not unambiguous in the SDK manifest; " +
                    "specify component, packageRole, platform, and variant (matches=" + matches.size() + ")");
        }
        return matches.get(0);
    }

    public Artifact selectClassifier(String component, String packageRole, String classifier) throws IOException {
        String parsedComponent = Component.parse(component).value;
        String parsedRole = PackageRole.parse(packageRole).value;
        String canonicalClassifier = canonicalClassifier(classifier);
        List<Artifact> matches = artifacts.stream()
                .filter(a -> a.component.equals(parsedComponent))
                .filter(a -> a.packageRole.equals(parsedRole))
                .filter(a -> a.classifier.equals(canonicalClassifier))
                .toList();
        if (matches.size() != 1) {
            throw new IOException("Expected exactly one SDK artifact for " + parsedComponent + "/" +
                    parsedRole + "/" + classifier + ", found " + matches.size());
        }
        return matches.get(0);
    }

    public static String defaultVariant(SdxSdkManifest manifest, String component, String packageRole,
                                        String platform) throws IOException {
        String parsedComponent = Component.parse(component).value;
        String parsedRole = PackageRole.parse(packageRole).value;
        String canonicalPlatform = canonicalPlatform(platform);
        List<String> variants = manifest.artifacts.stream()
                .filter(a -> a.component.equals(parsedComponent))
                .filter(a -> a.packageRole.equals(parsedRole))
                .filter(a -> a.platform.equals(canonicalPlatform))
                .map(Artifact::variant).distinct().toList();
        if (variants.size() == 1 && "cpu".equals(variants.get(0))) {
            return "cpu";
        }
        throw new IOException("variant is required for " + component + "/" + packageRole + "/" + platform +
                "; CPU is defaulted only when it is the sole available variant (available=" + variants + ")");
    }

    public record Artifact(String component, String packageRole, String platform, String variant,
                           String classifier, String fileName, String packaging, String sha256, long size) {
        private void validate(int index) throws IOException {
            if ("java".equals(component)) {
                if (!"java".equals(packageRole) || !"jar".equals(packaging) ||
                        !platform.isEmpty() || !variant.isEmpty() || !classifier.isEmpty()) {
                    throw invalid("artifacts[" + index + "] Java artifacts require packageRole=java, " +
                            "packaging=jar, and empty platform/variant/classifier");
                }
            } else {
                requireToken(platform, "artifacts[" + index + "].platform");
                requireToken(variant, "artifacts[" + index + "].variant");
                requireToken(classifier, "artifacts[" + index + "].classifier");
                String expectedClassifier = platform + "-" + variant;
                if (!expectedClassifier.equals(classifier)) {
                    throw invalid("artifacts[" + index + "].classifier must be '" + expectedClassifier + "'");
                }
                validateNativeRole(index);
            }
            if (!isSafeBaseName(fileName) || !SAFE_FILE_NAME.matcher(fileName).matches()) {
                throw invalid("artifacts[" + index + "].fileName must be a safe basename");
            }
            if (!SHA256.matcher(sha256).matches()) {
                throw invalid("artifacts[" + index + "].sha256 must contain 64 hexadecimal characters");
            }
            if (size <= 0) {
                throw invalid("artifacts[" + index + "].size must be positive");
            }
        }

        public String selectionIdentity() {
            return String.join("\u0000", component, packageRole, platform, variant);
        }

        public boolean isSelectable() {
            return !"java".equals(component);
        }

        private void validateNativeRole(int index) throws IOException {
            boolean valid = switch (component) {
                case "runtime" -> switch (packageRole) {
                    case "platform-sdk", "runtime-bindings" -> "zip".equals(packaging);
                    case "android-aar" -> "aar".equals(packaging);
                    case "apple-xcframework" ->
                            "xcframework".equals(packaging) || "xcframework.zip".equals(packaging);
                    default -> false;
                };
                case "aot" -> "aot-sdk".equals(packageRole) && "zip".equals(packaging);
                default -> false;
            };
            if (!valid) {
                throw invalid("artifacts[" + index + "] has incompatible component/packageRole/packaging");
            }
        }
    }

    public enum Component {
        RUNTIME("runtime"), AOT("aot"), JAVA("java");
        private final String value;
        Component(String value) { this.value = value; }
        static Component parse(String value) throws IOException {
            for (Component item : values()) if (item.value.equals(value)) return item;
            throw invalid("unsupported component: " + value);
        }
    }

    public enum PackageRole {
        PLATFORM_SDK("platform-sdk"), RUNTIME_BINDINGS("runtime-bindings"), ANDROID_AAR("android-aar"),
        APPLE_XCFRAMEWORK("apple-xcframework"), AOT_SDK("aot-sdk"), JAVA("java");
        private final String value;
        PackageRole(String value) { this.value = value; }
        static PackageRole parse(String value) throws IOException {
            for (PackageRole item : values()) if (item.value.equals(value)) return item;
            throw invalid("unsupported packageRole: " + value);
        }
    }

    public enum Packaging {
        ZIP("zip"), TAR_GZ("tar.gz"), AAR("aar"), XCFRAMEWORK("xcframework"),
        XCFRAMEWORK_ZIP("xcframework.zip"), JAR("jar");
        private final String value;
        Packaging(String value) { this.value = value; }
        static Packaging parse(String value) throws IOException {
            for (Packaging item : values()) if (item.value.equals(value)) return item;
            throw invalid("unsupported packaging: " + value);
        }
    }

    private static JsonNode required(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw invalid("missing required field: " + field);
        return value;
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = requiredString(node, field);
        if (value.isBlank()) throw invalid(field + " must be a non-empty string");
        return value;
    }

    private static String requiredString(JsonNode node, String field) throws IOException {
        JsonNode value = required(node, field);
        if (!value.isTextual()) throw invalid(field + " must be a string");
        return value.textValue();
    }

    private static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid(field + " must be an integer");
        return value.longValue();
    }

    private static void requireObject(JsonNode node, String location) throws IOException {
        if (node == null || !node.isObject()) throw invalid(location + " must be an object");
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String location) throws IOException {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) throw invalid("unknown field " + location + "." + field);
        }
    }

    private static void requireToken(String value, String field) throws IOException {
        if (!TOKEN.matcher(value).matches()) throw invalid(field + " contains unsafe characters");
    }

    private static boolean isSafeBaseName(String value) {
        if (value.isBlank() || value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 ||
                value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) return false;
        Path path = Path.of(value);
        return path.getNameCount() == 1 && path.getFileName().toString().equals(value);
    }

    private static String canonicalPlatform(String platform) {
        if (platform != null && platform.startsWith("macosx-")) {
            return "macos-" + platform.substring("macosx-".length());
        }
        return platform;
    }

    private static String canonicalClassifier(String classifier) {
        if (classifier != null && classifier.startsWith("macosx-")) {
            return "macos-" + classifier.substring("macosx-".length());
        }
        return classifier;
    }

    private static IOException invalid(String message) {
        return new IOException("Invalid " + FILE_NAME + ": " + message);
    }
}
