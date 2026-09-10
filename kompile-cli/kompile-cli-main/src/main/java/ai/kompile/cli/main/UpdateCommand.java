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
package ai.kompile.cli.main;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.util.OSResolver;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Updates a complete Kompile distribution without changing its release lane. */
@CommandLine.Command(
        name = "update",
        description = "Update this managed Kompile distribution while preserving its variant and backend.")
public final class UpdateCommand implements Callable<Integer> {

    static final String INSTALLER_RESOURCE = "installer/install.sh";
    private static final String GITHUB_API =
            "https://api.github.com/repos/GetKompile/kompile/releases";
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern SAFE_VERSION = Pattern.compile(
            "[0-9]+(?:\\.[0-9]+)*(?:-[A-Za-z0-9][A-Za-z0-9._-]*)?");
    private static final Pattern QUALIFIER_TOKEN = Pattern.compile("[0-9]+|[A-Za-z]+");
    private static final Set<String> SUPPORTED_VARIANTS = Set.of(
            "full", "local", "cli-only", "hosted", "cpu-intel", "cpu-arm", "cuda", "amd-zluda");
    private static final Set<String> UPDATE_CONTROL_ENV = Set.of(
            "KOMPILE_BASE_URL", "KOMPILE_VERSION", "KOMPILE_VARIANT",
            "KOMPILE_BACKEND_PROFILE", "KOMPILE_DEV", "KOMPILE_MODIFY_PATH",
            "KOMPILE_UPDATE_TEST_FAIL_AFTER");

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true,
            description = "Show this help message and exit.")
    private boolean help;

    @CommandLine.Option(names = "--check", description = "Check for a compatible update without changing files.")
    private boolean check;

    @CommandLine.Option(names = {"--version", "-v"}, paramLabel = "VERSION",
            description = "Install an exact version instead of the newest compatible release.")
    private String requestedVersion;

    @CommandLine.Option(names = {"--url", "-u"}, paramLabel = "BASE_URL",
            description = "Read versioned distribution archives from a custom HTTPS or file base URL.")
    private String requestedBaseUrl;

    @CommandLine.Option(names = "--force",
            description = "Reinstall the current version and replace locally modified managed payload files.")
    private boolean force;

    @CommandLine.Option(names = "--prerelease",
            description = "Include prerelease GitHub releases when selecting the newest compatible version.")
    private boolean prerelease;

    @CommandLine.Option(names = "--verbose", description = "Show installer download and extraction details.")
    private boolean verbose;

    @FunctionalInterface
    interface ReleaseResolver {
        ReleaseArtifact resolve(InstalledDistribution installed, String version, String baseUrl,
                                boolean includePrerelease) throws Exception;
    }

    @FunctionalInterface
    interface InstallerMaterializer {
        Path materialize() throws IOException;
    }

    @FunctionalInterface
    interface InstallerLauncher {
        int launch(List<String> command, Map<String, String> environment)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Transport {
        String get(URI uri) throws IOException, InterruptedException;

        default boolean exists(URI uri) throws IOException, InterruptedException {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Files.isRegularFile(Path.of(uri));
            }
            return false;
        }
    }

    record InstalledDistribution(
            Path root,
            String version,
            String variant,
            String platform,
            String backendProfile,
            String distributionClassifier) {
    }

    record ReleaseArtifact(String version, String archiveName, URI archiveUri, URI checksumUri) {
    }

    private final Supplier<Path> installRoot;
    private final Supplier<String> platform;
    private final Supplier<Boolean> managedRuntime;
    private final Map<String, String> environment;
    private final ReleaseResolver releaseResolver;
    private final InstallerMaterializer installerMaterializer;
    private final InstallerLauncher installerLauncher;
    private final ObjectMapper objectMapper;
    private final PrintWriter out;
    private final PrintWriter err;

    public UpdateCommand() {
        this(UpdateCommand::currentInstallRoot,
                OSResolver::javacppPlatform,
                UpdateCommand::currentRuntimeIsManaged,
                System.getenv(),
                new DefaultReleaseResolver(new JdkTransport(), JsonUtils.standardMapper()),
                UpdateCommand::materializeBundledInstaller,
                UpdateCommand::launchInstaller,
                JsonUtils.standardMapper(),
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
    }

    UpdateCommand(Supplier<Path> installRoot,
                  Supplier<String> platform,
                  Supplier<Boolean> managedRuntime,
                  Map<String, String> environment,
                  ReleaseResolver releaseResolver,
                  InstallerMaterializer installerMaterializer,
                  InstallerLauncher installerLauncher,
                  ObjectMapper objectMapper,
                  PrintWriter out,
                  PrintWriter err) {
        this.installRoot = installRoot;
        this.platform = platform;
        this.managedRuntime = managedRuntime;
        this.environment = Map.copyOf(environment);
        this.releaseResolver = releaseResolver;
        this.installerMaterializer = installerMaterializer;
        this.installerLauncher = installerLauncher;
        this.objectMapper = objectMapper;
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() {
        Path root = installRoot.get().toAbsolutePath().normalize();
        if (!managedRuntime.get()) {
            err.println("Cannot safely update Kompile: the running CLI does not belong to a managed distribution.");
            printUnmanagedRuntimeGuidance(root);
            return 3;
        }
        InstalledDistribution installed;
        try {
            installed = readInstalledDistribution(root, platform.get(), objectMapper);
        } catch (IOException | IllegalArgumentException e) {
            err.println("Cannot safely update Kompile: " + e.getMessage());
            printUnmanagedRuntimeGuidance(root);
            return 3;
        }

        String version = firstNonBlank(requestedVersion, environment.get("KOMPILE_VERSION"));
        String baseUrl = firstNonBlank(requestedBaseUrl, environment.get("KOMPILE_BASE_URL"));
        if (baseUrl != null && version == null) {
            err.println("A custom update URL requires --version (or KOMPILE_VERSION). ");
            return 2;
        }

        ReleaseArtifact artifact;
        try {
            artifact = releaseResolver.resolve(installed, version, baseUrl, prerelease);
        } catch (Exception e) {
            err.println("Could not resolve a compatible Kompile update: " + usefulMessage(e));
            err.println("No fallback variant or backend was selected; the installed lane was left unchanged.");
            return 1;
        }

        printResolution(installed, artifact);
        boolean sameVersion = installed.version().equals(normalizeVersion(artifact.version()));
        int versionComparison = compareVersions(artifact.version(), installed.version());
        if (version == null && (versionComparison < 0 || (versionComparison == 0 && !force))) {
            out.println(sameVersion
                    ? "Kompile is up to date."
                    : "No newer compatible release was found (newest: " + artifact.version() + ").");
            return 0;
        }
        if (check) {
            if (sameVersion) {
                out.println("Kompile is up to date.");
            } else if (versionComparison > 0) {
                out.println("Update available: " + installed.version() + " -> " + artifact.version());
            } else {
                out.println("Requested version " + artifact.version() + " is older than " + installed.version() + ".");
            }
            return 0;
        }
        if (sameVersion && !force) {
            out.println("Kompile is already at " + installed.version() + ". Use --force to reinstall it.");
            return 0;
        }
        if (installed.platform().startsWith("windows-")) {
            err.println("Windows can check for updates, but in-process replacement is not yet supported safely.");
            err.println("Close Kompile and run the installer for " + artifact.version()
                    + " with variant " + installed.variant() + " at " + installed.root() + ".");
            return 3;
        }

        Path installer = null;
        try {
            installer = installerMaterializer.materialize();
            List<String> command = installerCommand(installer, installed, artifact,
                    force, verbose, environment.get("KOMPILE_BASH"));
            Map<String, String> childEnvironment = installerEnvironment(environment, installed.root());
            int launchCode = installerLauncher.launch(command, childEnvironment);
            if (launchCode != 0) {
                err.println("Kompile updater failed (exit code " + launchCode + ").");
                return launchCode;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Kompile update launch was interrupted.");
            return 1;
        } catch (IOException | RuntimeException e) {
            err.println("Could not start the Kompile updater: " + usefulMessage(e));
            return 1;
        } finally {
            deleteQuietly(installer);
        }

        out.println("Kompile updated to " + artifact.version() + ". Restart running Kompile services to use it.");
        return 0;
    }

    private void printResolution(InstalledDistribution installed, ReleaseArtifact artifact) {
        out.println("Kompile update");
        out.println("  Install:    " + installed.root());
        out.println("  Current:    " + installed.version());
        out.println("  Target:     " + artifact.version());
        out.println("  Variant:    " + installed.variant());
        out.println("  Backend:    " + installed.backendProfile());
        out.println("  Classifier: " + installed.distributionClassifier());
        out.println("  Archive:    " + artifact.archiveUri());
    }

    private void printUnmanagedRuntimeGuidance(Path root) {
        err.println("Expected managed distribution metadata at " + root.resolve(".dist-info.json") + ".");
        err.println("JBang/stable-JAR runs must refresh their catalog/version; source or --dev installs must be rebuilt;");
        err.println("Docker installs must pull a new image; component-only installs use 'kompile install ... --force'.");
    }

    static boolean currentRuntimeIsManaged() {
        return samePath(currentInstallRoot(), runningDistributionRoot());
    }

    static Path selectInstallRoot(Path configuredRoot, boolean explicitlyConfigured, Path runtimeRoot) {
        return !explicitlyConfigured && runtimeRoot != null ? runtimeRoot : configuredRoot;
    }

    private static Path currentInstallRoot() {
        Path configured = KompileHome.installDirectory().toPath();
        return selectInstallRoot(configured, hasExplicitInstallRoot(), runningDistributionRoot());
    }

    private static boolean hasExplicitInstallRoot() {
        return hasText(System.getProperty("kompile.install.dir"))
                || hasText(System.getenv("KOMPILE_INSTALL_DIR"))
                || hasText(System.getProperty("kompile.dist.home"))
                || hasText(System.getenv("KOMPILE_DIST_HOME"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Path runningDistributionRoot() {
        Path nativeRoot = distributionRoot(NativeImageInfo.getExecutablePathAsPath());
        if (nativeRoot != null) return nativeRoot;
        try {
            URI location = UpdateCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return "file".equalsIgnoreCase(location.getScheme())
                    ? distributionRoot(Path.of(location))
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path distributionRoot(Path artifact) {
        java.io.File root = KompileHome.inferDistributionHome(artifact);
        return root == null ? null : root.toPath();
    }

    private static boolean samePath(Path first, Path second) {
        if (first == null || second == null) return false;
        try {
            return Files.isSameFile(first, second);
        } catch (IOException ignored) {
            return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
        }
    }

    static InstalledDistribution readInstalledDistribution(
            Path root, String currentPlatform, ObjectMapper objectMapper) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("installation directory does not exist: " + root);
        }
        Path resolvedRoot = root.toRealPath();
        Path metadata = resolvedRoot.resolve(".dist-info.json");
        if (!Files.isRegularFile(metadata)) {
            throw new IllegalArgumentException("this is not a managed distribution (missing .dist-info.json)");
        }

        JsonNode json = objectMapper.readTree(metadata.toFile());
        String version = requiredToken(json, "version");
        String variant = requiredToken(json, "variant");
        String installedPlatform = requiredToken(json, "platform");
        String classifier = requiredToken(json, "distributionClassifier");
        String backendProfile = optionalToken(json, "backendProfile", "none");

        if (!SUPPORTED_VARIANTS.contains(variant)) {
            throw new IllegalArgumentException("unsupported installed variant: " + variant);
        }
        if (!installedPlatform.equals(currentPlatform)) {
            throw new IllegalArgumentException("installed platform " + installedPlatform
                    + " does not match this machine (" + currentPlatform + ")");
        }
        if (!classifier.startsWith(variant + "-") || !classifier.contains(installedPlatform)) {
            throw new IllegalArgumentException("distribution classifier does not match its variant/platform: "
                    + classifier);
        }

        return new InstalledDistribution(resolvedRoot, normalizeVersion(version), variant, installedPlatform,
                backendProfile, classifier);
    }

    private static String requiredToken(JsonNode json, String field) {
        JsonNode value = json.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing " + field + " in .dist-info.json");
        }
        return validateToken(field, value.asText().trim());
    }

    private static String optionalToken(JsonNode json, String field, String defaultValue) {
        JsonNode value = json.path(field);
        String result = value.isTextual() && !value.asText().isBlank()
                ? value.asText().trim()
                : defaultValue;
        return validateToken(field, result);
    }

    private static String validateToken(String name, String value) {
        if (!SAFE_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("unsafe " + name + " in .dist-info.json: " + value);
        }
        return value;
    }

    static List<String> installerCommand(
            Path installer,
            InstalledDistribution installed,
            ReleaseArtifact artifact,
            boolean force,
            boolean verbose,
            String configuredBash) {
        String bash = configuredBash == null || configuredBash.isBlank() ? "bash" : configuredBash.trim();
        List<String> command = new ArrayList<>();
        command.add(bash);
        command.add(installer.toString());
        command.add("--update");
        command.add("--version");
        command.add(artifact.version());
        command.add("--variant");
        command.add(installed.variant());
        if (!"none".equals(installed.backendProfile())) {
            command.add("--backend-profile");
            command.add(installed.backendProfile());
        }
        command.add("--distribution-classifier");
        command.add(installed.distributionClassifier());
        command.add("--dir");
        command.add(installed.root().toString());
        command.add("--archive-url");
        command.add(artifact.archiveUri().toString());
        command.add("--checksum-url");
        command.add(artifact.checksumUri().toString());
        if (force) command.add("--force");
        if (verbose) command.add("--verbose");
        return List.copyOf(command);
    }

    static Map<String, String> installerEnvironment(Map<String, String> source, Path installRoot) {
        Map<String, String> result = new HashMap<>(source);
        UPDATE_CONTROL_ENV.forEach(result::remove);
        result.put("KOMPILE_INSTALL_DIR", installRoot.toString());
        result.put("KOMPILE_DEV", "0");
        result.put("KOMPILE_MODIFY_PATH", "0");
        return Map.copyOf(result);
    }

    private static Path materializeBundledInstaller() throws IOException {
        ClassLoader loader = UpdateCommand.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(INSTALLER_RESOURCE)) {
            if (input == null) {
                throw new IOException("bundled installer resource is missing: " + INSTALLER_RESOURCE);
            }
            Path installer = Files.createTempFile("kompile-update-installer-", ".sh");
            Files.copy(input, installer, StandardCopyOption.REPLACE_EXISTING);
            return installer;
        }
    }

    private static int launchInstaller(
            List<String> command, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command).inheritIO();
        processBuilder.environment().clear();
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        return process.waitFor();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }

    static String normalizeVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        if (normalized.startsWith("v")) normalized = normalized.substring(1);
        if (!SAFE_VERSION.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid version: " + version);
        }
        return normalized;
    }

    static int compareVersions(String first, String second) {
        VersionParts left = VersionParts.parse(normalizeVersion(first));
        VersionParts right = VersionParts.parse(normalizeVersion(second));
        int length = Math.max(left.core().size(), right.core().size());
        for (int i = 0; i < length; i++) {
            BigInteger leftPart = i < left.core().size() ? left.core().get(i) : BigInteger.ZERO;
            BigInteger rightPart = i < right.core().size() ? right.core().get(i) : BigInteger.ZERO;
            int comparison = leftPart.compareTo(rightPart);
            if (comparison != 0) return comparison;
        }
        if (left.qualifier().isEmpty() && !right.qualifier().isEmpty()) return 1;
        if (!left.qualifier().isEmpty() && right.qualifier().isEmpty()) return -1;
        return compareQualifier(left.qualifier(), right.qualifier());
    }

    private static int compareQualifier(String first, String second) {
        List<String> left = qualifierTokens(first);
        List<String> right = qualifierTokens(second);
        int length = Math.max(left.size(), right.size());
        for (int i = 0; i < length; i++) {
            if (i >= left.size()) return -1;
            if (i >= right.size()) return 1;
            String leftToken = left.get(i);
            String rightToken = right.get(i);
            boolean leftNumeric = Character.isDigit(leftToken.charAt(0));
            boolean rightNumeric = Character.isDigit(rightToken.charAt(0));
            int comparison;
            if (leftNumeric && rightNumeric) {
                comparison = new BigInteger(leftToken).compareTo(new BigInteger(rightToken));
            } else if (leftNumeric != rightNumeric) {
                comparison = leftNumeric ? -1 : 1;
            } else {
                comparison = leftToken.compareToIgnoreCase(rightToken);
            }
            if (comparison != 0) return comparison;
        }
        return first.compareToIgnoreCase(second);
    }

    private static List<String> qualifierTokens(String qualifier) {
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher matcher = QUALIFIER_TOKEN.matcher(qualifier);
        while (matcher.find()) tokens.add(matcher.group());
        return List.copyOf(tokens);
    }

    private record VersionParts(List<BigInteger> core, String qualifier) {
        static VersionParts parse(String version) {
            int qualifierIndex = version.indexOf('-');
            String coreValue = qualifierIndex < 0 ? version : version.substring(0, qualifierIndex);
            String qualifier = qualifierIndex < 0 ? "" : version.substring(qualifierIndex + 1);
            List<BigInteger> parts = new ArrayList<>();
            for (String part : coreValue.split("\\.")) {
                if (!part.matches("[0-9]+")) {
                    throw new IllegalArgumentException("version is not comparable: " + version);
                }
                parts.add(new BigInteger(part));
            }
            return new VersionParts(List.copyOf(parts), qualifier);
        }
    }

    private static String usefulMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; the system temporary directory remains the fallback.
        }
    }

    static final class DefaultReleaseResolver implements ReleaseResolver {
        private final Transport transport;
        private final ObjectMapper objectMapper;

        DefaultReleaseResolver(Transport transport, ObjectMapper objectMapper) {
            this.transport = transport;
            this.objectMapper = objectMapper;
        }

        @Override
        public ReleaseArtifact resolve(
                InstalledDistribution installed, String version, String baseUrl,
                boolean includePrerelease) throws Exception {
            String normalizedVersion = version == null ? null : normalizeVersion(version);
            if (baseUrl != null) {
                if (normalizedVersion == null) {
                    throw new IllegalArgumentException("custom update URLs require an exact version");
                }
                return resolveFromBase(installed, normalizedVersion, baseUrl);
            }

            URI endpoint = normalizedVersion == null
                    ? URI.create(GITHUB_API + "?per_page=100")
                    : URI.create(GITHUB_API + "/tags/v" + normalizedVersion);
            JsonNode response = objectMapper.readTree(transport.get(endpoint));
            List<JsonNode> releases = new ArrayList<>();
            if (response.isArray()) {
                response.forEach(releases::add);
            } else if (response.isObject()) {
                releases.add(response);
            } else {
                throw new IOException("GitHub returned an unexpected releases response");
            }

            ReleaseArtifact best = null;
            for (JsonNode release : releases) {
                if (release.path("draft").asBoolean(false)) continue;
                if (normalizedVersion == null && release.path("prerelease").asBoolean(false)
                        && !includePrerelease) continue;
                String tag = release.path("tag_name").asText("");
                String candidateVersion;
                try {
                    candidateVersion = normalizeVersion(tag);
                } catch (IllegalArgumentException ignored) {
                    continue; // Model/data releases such as the current `opennlp` tag are not product versions.
                }
                if (normalizedVersion != null && !normalizedVersion.equals(candidateVersion)) continue;
                ReleaseArtifact artifact = selectAsset(release, installed, candidateVersion);
                if (artifact != null && (normalizedVersion != null || best == null
                        || compareVersions(artifact.version(), best.version()) > 0)) {
                    best = artifact;
                }
            }
            if (best != null) return best;

            String requested = normalizedVersion == null ? "the release catalog" : "v" + normalizedVersion;
            throw new IOException("no checksummed " + installed.distributionClassifier()
                    + " archive was found in " + requested);
        }

        private ReleaseArtifact resolveFromBase(
                InstalledDistribution installed, String version, String baseUrl) throws Exception {
            URI base = validatedBaseUri(baseUrl);
            for (String extension : preferredExtensions(installed.platform())) {
                String name = archiveName(version, installed.distributionClassifier(), extension);
                URI archive = append(base, name);
                URI checksum = append(base, name + ".sha256");
                if (transport.exists(archive) && transport.exists(checksum)) {
                    return new ReleaseArtifact(version, name, archive, checksum);
                }
            }
            throw new IOException("no checksummed " + installed.distributionClassifier()
                    + " archive exists at " + base);
        }

        private ReleaseArtifact selectAsset(
                JsonNode release, InstalledDistribution installed, String version) {
            Map<String, URI> assets = new LinkedHashMap<>();
            for (JsonNode asset : release.path("assets")) {
                String name = asset.path("name").asText("");
                String url = asset.path("browser_download_url").asText("");
                if (!name.isBlank() && !url.isBlank()) assets.put(name, URI.create(url));
            }
            for (String extension : preferredExtensions(installed.platform())) {
                String name = archiveName(version, installed.distributionClassifier(), extension);
                URI archive = assets.get(name);
                URI checksum = assets.get(name + ".sha256");
                if (archive != null && checksum != null) {
                    return new ReleaseArtifact(version, name, archive, checksum);
                }
            }
            return null;
        }

        private static URI validatedBaseUri(String raw) {
            URI base = URI.create(raw.endsWith("/") ? raw : raw + "/");
            String scheme = base.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https")
                    || scheme.equalsIgnoreCase("file"))) {
                throw new IllegalArgumentException("update URL must use https or file");
            }
            return base;
        }

        private static URI append(URI base, String name) {
            return base.resolve(name);
        }

        private static String archiveName(String version, String classifier, String extension) {
            return "kompile-dist-" + version + "-" + classifier + "." + extension;
        }

        private static List<String> preferredExtensions(String platform) {
            return List.of("zip");
        }
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        @Override
        public String get(URI uri) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "kompile-update")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("GET " + uri + " returned HTTP " + response.statusCode());
            }
            return response.body();
        }

        @Override
        public boolean exists(URI uri) throws IOException, InterruptedException {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Transport.super.exists(uri);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "kompile-update")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        }
    }
}
