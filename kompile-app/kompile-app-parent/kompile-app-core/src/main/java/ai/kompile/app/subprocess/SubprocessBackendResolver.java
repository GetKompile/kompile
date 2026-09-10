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

package ai.kompile.app.subprocess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Descriptor-driven resolution of ND4J backend classpaths for subprocesses.
 *
 * <p>A jar is classified <b>by its own standard backend descriptor</b>, never by artifact id,
 * version, or name pattern: every ND4J backend jar ships {@code nd4j-<name>.properties} whose
 * standard keys include {@code device.type} (e.g. {@code CUDA_GPU}, {@code CPU}) and
 * {@code native.ops}. So "which jars implement the GPU backend" is answered by opening each
 * candidate jar and reading its descriptor — no coordinates are hardcoded anywhere, and new
 * backends (ZLUDA, Vulkan, ...) are supported by their own descriptors with zero code change.</p>
 *
 * <p>Selection rules:</p>
 * <ul>
 *   <li><b>Backend jar</b> — jar containing a descriptor with the requested {@code device.type}.</li>
 *   <li><b>Platform natives</b> — same artifact/version directory, Maven layout sibling
 *       {@code <artifactId>-<version>-<platform>.jar} of each selected backend jar.</li>
 *   <li><b>Shared API jars</b> — jars carrying {@code org/nd4j/linalg/api/NDArray.class}
 *       (nd4j-api and friends) are never treated as conflicting and are always kept.</li>
 *   <li><b>Conflicts</b> — jars carrying a descriptor whose {@code device.type} differs from the
 *       requested one (i.e. another real backend) are removed from the child classpath.</li>
 * </ul>
 *
 * <p>Search roots (first root that yields the backend ends the search; jars are never mixed
 * across roots): {@code extraJarRoots} → dist bundle ({@code kompile.dist.home} /
 * {@code KOMPILE_DIST_HOME}: {@code lib/backends/<backend>}, {@code lib/backends}, {@code lib})
 * → {@code mavenRepoPath}. Root locations and extra roots come from
 * {@code ~/.kompile/config/subprocess-backend-config.json}; no paths are hardcoded here either
 * (the m2 path is only a default inside that file).</p>
 *
 * <p>Used by {@code ServingSubprocessLauncher} and {@code EmbeddingSubprocessLauncher} to keep
 * backend resolution uniform across all subprocess types.</p>
 */
public final class SubprocessBackendResolver {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessBackendResolver.class);

    /** Standard descriptor key declaring the device class a backend implements. */
    static final String DESCRIPTOR_DEVICE_TYPE_KEY = "device.type";
    /** Shared-API marker: presence of this class means the jar is backend-neutral, keep always. */
    static final String NDARRAY_API_CLASS = "org/nd4j/linalg/api/NDArray.class";
    /** The ND4J backend registration file every real backend jar carries. */
    static final String BACKEND_SERVICES_FILE = "META-INF/services/org.nd4j.linalg.factory.Nd4jBackend";

    /** Registry of the most recent backend resolution per subprocess type. Thread-safe. */
    private static final ConcurrentHashMap<String, BackendResolution> resolutionRegistry = new ConcurrentHashMap<>();

    private SubprocessBackendResolver() {}

    /**
     * Get the most recent backend resolution for a given subprocess type.
     * @return the resolution, or null if this subprocess type hasn't been resolved yet
     */
    public static BackendResolution getLastResolution(String subprocessType) {
        return resolutionRegistry.get(subprocessType);
    }

    /**
     * Get all backend resolutions across all subprocess types.
     * @return unmodifiable map of subprocess type → resolution
     */
    public static Map<String, BackendResolution> getAllResolutions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(resolutionRegistry));
    }

    /**
     * Result of a backend resolution attempt. Contains all the information needed
     * to understand exactly what happened — no ambiguity.
     */
    public static final class BackendResolution {
        private final String resolvedBackend; // "CUDA" or "CPU"
        private final String subprocessType;  // e.g., "LLM_SERVING", "EMBEDDING"
        private final List<String> cudaJarsAdded;
        private final List<String> cpuEntriesRemoved;
        private final boolean cudaRequested;
        private final String reason; // why this resolution was made
        private final Instant resolvedAt;

        private BackendResolution(String resolvedBackend, String subprocessType,
                                  List<String> cudaJarsAdded, List<String> cpuEntriesRemoved,
                                  boolean cudaRequested, String reason) {
            this.resolvedBackend = resolvedBackend;
            this.subprocessType = subprocessType;
            this.cudaJarsAdded = cudaJarsAdded;
            this.cpuEntriesRemoved = cpuEntriesRemoved;
            this.cudaRequested = cudaRequested;
            this.reason = reason;
            this.resolvedAt = Instant.now();
        }

        public String getResolvedBackend() { return resolvedBackend; }
        public String getSubprocessType() { return subprocessType; }
        public List<String> getCudaJarsAdded() { return Collections.unmodifiableList(cudaJarsAdded); }
        public List<String> getCpuEntriesRemoved() { return Collections.unmodifiableList(cpuEntriesRemoved); }
        public boolean isCudaRequested() { return cudaRequested; }
        public String getReason() { return reason; }
        public boolean isCuda() { return "CUDA".equals(resolvedBackend); }
        public Instant getResolvedAt() { return resolvedAt; }

        /**
         * Convert to a serializable map for REST API responses.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("subprocessType", subprocessType);
            map.put("resolvedBackend", resolvedBackend);
            map.put("cudaRequested", cudaRequested);
            map.put("cudaJarsAdded", cudaJarsAdded);
            map.put("cpuEntriesRemoved", cpuEntriesRemoved);
            map.put("reason", reason);
            map.put("resolvedAt", resolvedAt.toString());
            return map;
        }

        /**
         * Log this resolution as an unambiguous banner that makes it impossible
         * to confuse which backend a subprocess is using.
         */
        public void logBanner() {
            // Register this resolution so it can be queried via REST API
            resolutionRegistry.put(subprocessType, this);

            String banner = String.format(
                    "\n" +
                    "╔══════════════════════════════════════════════════════════════╗\n" +
                    "║  SUBPROCESS BACKEND RESOLUTION                             ║\n" +
                    "╠══════════════════════════════════════════════════════════════╣\n" +
                    "║  Subprocess Type : %-40s ║\n" +
                    "║  Resolved Backend: %-40s ║\n" +
                    "║  CUDA Requested  : %-40s ║\n" +
                    "║  Backend JARs Added : %-38s ║\n" +
                    "║  Conflicting Entries Removed: %-30s ║\n" +
                    "║  Reason          : %-40s ║\n" +
                    "╚══════════════════════════════════════════════════════════════╝",
                    subprocessType,
                    resolvedBackend,
                    cudaRequested ? "YES" : "NO",
                    String.valueOf(cudaJarsAdded.size()),
                    String.valueOf(cpuEntriesRemoved.size()),
                    reason
            );
            logger.info(banner);

            if (!cudaJarsAdded.isEmpty()) {
                for (String jar : cudaJarsAdded) {
                    logger.info("[BACKEND] +JAR: {}", jar);
                }
            }
            if (!cpuEntriesRemoved.isEmpty()) {
                for (String entry : cpuEntriesRemoved) {
                    logger.info("[BACKEND] -conflicting entry: {}", entry);
                }
            }
        }
    }

    /**
     * Augment the classpath entries with the requested ND4J backend.
     *
     * @param entries      mutable set of classpath entries to modify in place
     * @param needsCuda    whether the CUDA (GPU) backend is requested for this subprocess
     * @param subprocessType human-readable subprocess type for logging (e.g., "LLM_SERVING", "EMBEDDING")
     * @return resolution result describing exactly what was done
     */
    public static BackendResolution augmentClasspathForBackend(Set<String> entries, boolean needsCuda, String subprocessType) {
        if (!needsCuda) {
            BackendResolution result = new BackendResolution(
                    "CPU", subprocessType, List.of(), List.of(), false,
                    "CUDA not requested via device routing");
            result.logBanner();
            return result;
        }

        // Check if a GPU-backend jar is already on the classpath
        if (containsBackendFor(entries, true)) {
            BackendResolution result = new BackendResolution(
                    "CUDA", subprocessType, List.of(), List.of(), true,
                    "GPU-backend jar already present on classpath");
            result.logBanner();
            return result;
        }

        // Discover the backend closure from the configured search roots
        LinkedHashSet<Path> backendJars = discoverBackendJars(true);
        if (backendJars.isEmpty()) {
            BackendResolution result = new BackendResolution(
                    "CPU", subprocessType, List.of(), List.of(), true,
                    "CUDA requested but no descriptor-matching GPU backend found in any configured search root");
            result.logBanner();
            logger.warn("[BACKEND] CUDA device routing configured for {} but no jar with a {}={} descriptor "
                    + "was found in extraJarRoots, the dist bundle, or mavenRepoPath. Build/install the backend "
                    + "or extend ~/.kompile/config/subprocess-backend-config.json.", subprocessType,
                    DESCRIPTOR_DEVICE_TYPE_KEY, "CUDA_GPU");
            return result;
        }

        // Remove jars of conflicting backends (descriptor device.type differs) but keep
        // shared API jars (nd4j-api & friends, marked by NDArray.class) — both backends need them.
        // The provenance guard MUST gate removal too: an application fat jar that merely embeds
        // a CPU-backend registration (e.g. kompile-cli.jar) is the application itself — removing
        // it deletes the child's main class (ClassNotFoundException: EmbeddingSubprocessMain,
        // 2026-09-01 15:47). Only a jar that IS an nd4j backend-family artifact may be removed.
        List<String> removedEntries = new ArrayList<>();
        Iterator<String> it = entries.iterator();
        while (it.hasNext()) {
            String entry = it.next();
            if (!entry.endsWith(".jar")) {
                continue; // classes dirs and non-jar entries are never removed
            }
            if (jarHasClass(Paths(entry), NDARRAY_API_CLASS)) {
                continue; // shared API jar — always kept
            }
            Path jarPath = Paths(entry);
            String deviceType = descriptorDeviceType(jarPath);
            if (deviceType == null) {
                continue; // not a backend jar at all — not our business
            }
            if (!isCudaDeviceType(deviceType) && isNd4jArtifact(jarPath)) {
                removedEntries.add(entry);
                it.remove();
            }
        }

        // Add the discovered backend closure
        List<String> addedJarNames = new ArrayList<>();
        for (Path jar : backendJars) {
            entries.add(jar.toString());
            addedJarNames.add(jar.getFileName().toString());
        }

        BackendResolution result = new BackendResolution(
                "CUDA", subprocessType, addedJarNames, removedEntries, true,
                "GPU backend closure found via descriptor device.type in configured search roots");
        result.logBanner();
        return result;
    }

    /** True when at least one jar entry in {@code entries} carries a descriptor for the requested backend. */
    private static boolean containsBackendFor(Set<String> entries, boolean cuda) {
        for (String entry : entries) {
            if (!entry.endsWith(".jar")) {
                continue;
            }
            String deviceType = descriptorDeviceType(Paths(entry));
            if (deviceType == null) {
                continue;
            }
            if (cuda == isCudaDeviceType(deviceType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walk the configured search roots in order and return the first root's complete backend
     * closure: every jar whose descriptor declares the requested device type, plus each one's
     * platform-natives sibling jar. Empty when no root yields the backend.
     */
    static LinkedHashSet<Path> discoverBackendJars(boolean cuda) {
        for (Path root : searchRoots()) {
            LinkedHashSet<Path> found = collectBackendJarsUnder(root, cuda);
            if (!found.isEmpty()) {
                return found; // first root that yields the backend ends the search
            }
        }
        return new LinkedHashSet<>();
    }

    /** Root order: extraJarRoots → dist bundle → maven repo. Never mixes jars across roots. */
    static List<Path> searchRoots() {
        List<Path> roots = new ArrayList<>();
        BackendSearchConfig config = BackendSearchConfig.load();
        for (String extra : config.extraJarRoots) {
            Path p = Path.of(expand(extra));
            if (Files.isDirectory(p)) {
                roots.add(p);
            }
        }
        Path distHome = distHome();
        if (distHome != null) {
            for (String sub : List.of("lib/backends/cuda", "lib/backends", "lib")) {
                Path p = distHome.resolve(sub);
                if (Files.isDirectory(p)) {
                    roots.add(p);
                }
            }
        }
        if (config.mavenRepoPath != null && !config.mavenRepoPath.isBlank()) {
            Path repo = Path.of(expand(config.mavenRepoPath));
            if (Files.isDirectory(repo)) {
                roots.add(repo);
            }
        }
        return roots;
    }

    private static Path distHome() {
        String prop = System.getProperty("kompile.dist.home");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String env = System.getenv("KOMPILE_DIST_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return null;
    }

    /**
     * Collect under one root every jar whose descriptor declares the requested device type, then
     * complete the closure: platform-native sibling jars (same artifact/version dir) and the
     * dependency graph declared by each selected jar's Maven POM (support artifacts like
     * {@code *-backend-common} carry neither a services registration nor a descriptor — the POM
     * is the only standard that names them). Versions never mix: within one artifact directory
     * only the highest-sorted version directory is used, and closure traversal never leaves the
     * root's own version dir. Maven-layout roots are preferred: jars that live in a proper
     * {@code group/artifact/version} layout are collected first and win over flat-layout jars
     * (application assemblies that embed a services file but are not backend artifacts).
     */
    private static LinkedHashSet<Path> collectBackendJarsUnder(Path root, boolean cuda) {
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith(".sha1")
                            && !p.getFileName().toString().endsWith(".md5"))
                    .forEach(p -> {
                        String deviceType = descriptorDeviceType(p);
                        if (deviceType != null && (cuda == isCudaDeviceType(deviceType)) && isNd4jArtifact(p)) {
                            result.add(p);
                            result.addAll(platformSiblings(p));
                        }
                    });
        } catch (IOException e) {
            logger.debug("[BACKEND] Could not scan root {}: {}", root, e.getMessage());
        }
        LinkedHashSet<Path> newest = newestVersionOnly(result);
        // Maven-layout jars win over flat-layout assemblies with the same registration:
        // group/artifact/version placement proves repository provenance.
        List<Path> mavenLayout = new ArrayList<>();
        List<Path> flat = new ArrayList<>();
        for (Path jar : newest) {
            (hasMavenLayout(jar) ? mavenLayout : flat).add(jar);
        }
        List<Path> selected = !mavenLayout.isEmpty() ? mavenLayout : flat;
        // POM-declared closure (deps of the selected backend jars), keeping to the same root.
        LinkedHashSet<Path> closure = new LinkedHashSet<>(selected);
        Deque<Path> queue = new ArrayDeque<>(selected);
        while (!queue.isEmpty()) {
            Path jar = queue.poll();
            for (Path dep : pomDeclaredDependencies(jar, root)) {
                if (closure.add(dep)) {
                    closure.addAll(platformSiblings(dep));
                    queue.add(dep);
                }
            }
        }
        return closure;
    }

    /** True when the jar sits in a Maven repository {@code group/.../artifact/version/x.jar} layout. */
    private static boolean hasMavenLayout(Path jar) {
        Path versionDir = jar.getParent();
        Path artifactDir = versionDir != null ? versionDir.getParent() : null;
        Path groupPart = artifactDir != null ? artifactDir.getParent() : null;
        return versionDir != null && artifactDir != null && groupPart != null
                && Files.isDirectory(groupPart);
    }

    /**
     * Provenance guard: the jar must BE an ND4J backend-family artifact, not an application
     * assembly that embeds one. Two standard proofs, either suffices:
     * (a) its own META-INF/maven pom.properties declares an {@code nd4j-} artifactId
     *     (the artifact's own metadata; uber-jars embed dozens of foreign pom.properties files
     *     and/or their own non-nd4j identity), or
     * (b) it sits in a Maven layout whose artifact directory is {@code nd4j-*}.
     */
    private static boolean isNd4jArtifact(Path jar) {
        String ownArtifactId = ownMavenArtifactId(jar);
        if (ownArtifactId != null) {
            return ownArtifactId.startsWith("nd4j-");
        }
        Path versionDir = jar.getParent();
        Path artifactDir = versionDir != null ? versionDir.getParent() : null;
        return artifactDir != null && artifactDir.getFileName().toString().startsWith("nd4j-");
    }

    /**
     * The artifact id of the jar ITSELF (not of embedded dependencies): read from its
     * {@code META-INF/maven/<group>/<artifact>/pom.properties} when exactly one such file
     * exists (an uber-jar carries many — ambiguous, return null); else null.
     */
    private static String ownMavenArtifactId(Path jar) {
        URI uri;
        try {
            uri = URI.create("jar:" + jar.toUri());
        } catch (Exception e) {
            return null;
        }
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
            Path mavenDir = fs.getPath("META-INF/maven");
            if (!Files.exists(mavenDir)) {
                return null;
            }
            List<Path> pomProps = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(mavenDir, 3)) {
                walk.filter(p -> p.getFileName() != null
                                && p.getFileName().toString().equals("pom.properties"))
                    .forEach(pomProps::add);
            } catch (IOException e) {
                return null;
            }
            if (pomProps.size() != 1) {
                return null; // zero (repackaged app) or many (shaded uber) — not a single artifact
            }
            Properties props = new Properties();
            try (var in = Files.newInputStream(pomProps.get(0))) {
                props.load(in);
            } catch (IOException e) {
                return null;
            }
            return props.getProperty("artifactId");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve a selected jar's Maven POM ({@code <artifact>-<version>.pom} beside it) and return
     * the jars of its {@code <dependency>} artifacts found in the same root and version scope.
     * Supports {@code groupId} paths that map onto root subdirectories. Anything unresolvable is
     * skipped with a debug log — the descriptor-identified jar itself is always sufficient for
     * backends whose entire closure is self-contained.
     */
    private static List<Path> pomDeclaredDependencies(Path jar, Path root) {
        List<Path> deps = new ArrayList<>();
        Path dir = jar.getParent();
        if (dir == null) {
            return deps;
        }
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) {
            return deps;
        }
        String base = name.substring(0, name.length() - ".jar".length());
        // Strip the platform classifier for POM lookup — the base artifact owns the POM.
        String platform = detectPlatform();
        if (base.endsWith("-" + platform)) {
            base = base.substring(0, base.length() - platform.length() - 1);
        }
        Path pom = dir.resolve(base + ".pom");
        if (!Files.isRegularFile(pom)) {
            return deps;
        }
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
            // Only the POM's own <dependencies> block is a runtime-closure source; never the
            // <dependencyManagement>/<build>/<profiles> sections (those carry tool/test deps
            // like maven-invoker inside build-plugin configurations, which are not classpath
            // facts at all).
            NodeList allDeps = doc.getElementsByTagName("dependency");
            java.util.List<org.w3c.dom.Node> direct = new ArrayList<>();
            for (int i = 0; i < allDeps.getLength(); i++) {
                org.w3c.dom.Node n = allDeps.item(i);
                String path = n.getParentNode().getNodeName() + "/"
                        + n.getParentNode().getParentNode().getNodeName();
                if (path.equals("dependencies/project") || path.endsWith("/dependencies/dependencies")) {
                    direct.add(n);
                }
            }
            NodeList dependencies = new NodeList() {
                public int getLength() { return direct.size(); }
                public org.w3c.dom.Node item(int i) { return direct.get(i); }
            };
            String versionDir = dir.getFileName().toString();
            for (int i = 0; i < dependencies.getLength(); i++) {
                Element dep = (Element) dependencies.item(i);
                String groupId = childText(dep, "groupId");
                String artifactId = childText(dep, "artifactId");
                if (groupId == null || artifactId == null) {
                    continue;
                }
                // Maven runtime-classpath semantics: only compile/runtime scope lands on a
                // classpath; test/provided never do, and optional deps are not inherited.
                String scope = childText(dep, "scope");
                if (scope != null && !scope.isBlank() && !scope.equalsIgnoreCase("compile")
                        && !scope.equalsIgnoreCase("runtime")) {
                    continue;
                }
                if ("true".equalsIgnoreCase(childText(dep, "optional"))) {
                    continue;
                }
                Path groupDir = root.resolve(groupId.replace('.', '/'));
                if (!Files.isDirectory(groupDir)) {
                    continue;
                }
                Path artifactDir = groupDir.resolve(artifactId);
                if (!Files.isDirectory(artifactDir)) {
                    continue;
                }
                // Prefer the same version dir as the depending jar when present (no version mixing);
                // otherwise the newest version dir of that artifact.
                Path versionPath = artifactDir.resolve(versionDir);
                if (Files.isDirectory(versionPath)) {
                    collectJarAndOptionalPom(versionPath, artifactId, deps);
                } else {
                    try (Stream<Path> versions = Files.list(artifactDir)) {
                        Optional<Path> newest = versions
                                .filter(Files::isDirectory)
                                .max(Comparator.naturalOrder());
                        newest.ifPresent(v -> collectJarAndOptionalPom(v, artifactId, deps));
                    } catch (IOException e) {
                        logger.debug("[BACKEND] Could not list {}: {}", artifactDir, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[BACKEND] Could not parse POM for {}: {}", pom, e.getMessage());
        }
        return deps;
    }

    private static void collectJarAndOptionalPom(Path versionDir, String artifactId, List<Path> out) {
        try (Stream<Path> listing = Files.list(versionDir)) {
            listing.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.equals(artifactId + "-" + versionDir.getFileName() + ".jar");
                    })
                    .findFirst()
                    .filter(p -> descriptorDeviceType(p) == null || !jarHasClass(p, NDARRAY_API_CLASS))
                    .ifPresent(out::add);
        } catch (IOException e) {
            logger.debug("[BACKEND] Could not list {}: {}", versionDir, e.getMessage());
        }
    }

    private static String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }

    /**
     * Maven-layout sibling natives: {@code <artifactId>-<version>-<platform>.jar} beside the
     * selected jar. Backend implementation jars carry their natives inside classifier siblings
     * of the same artifact+version directory.
     */
    private static List<Path> platformSiblings(Path jar) {
        List<Path> siblings = new ArrayList<>();
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) {
            return siblings;
        }
        String base = name.substring(0, name.length() - ".jar".length());
        // A classifier jar (…-linux-x86_64) never has its own sibling scan; only base jars spawn one.
        if (base.endsWith("-" + detectPlatform())) {
            return siblings;
        }
        Path sibling = jar.getParent().resolve(base + "-" + detectPlatform() + ".jar");
        if (Files.isRegularFile(sibling)) {
            siblings.add(sibling);
        }
        return siblings;
    }

    /**
     * Keep only the newest version directory per artifact across the collected set. Maven
     * snapshot repos accumulate timestamped/version dirs; mixing vintages on one classpath is
     * the classic stale-mixing trap, so per artifact (identified by its directory path minus
     * the version segment) only the highest-sorted version directory's jars survive.
     */
    static LinkedHashSet<Path> newestVersionOnly(LinkedHashSet<Path> jars) {
        Map<String, Map<String, List<Path>>> byArtifact = new LinkedHashMap<>();
        for (Path jar : jars) {
            Path versionDir = jar.getParent();
            if (versionDir == null) {
                continue;
            }
            Path artifactDir = versionDir.getParent();
            if (artifactDir == null) {
                // Not a maven layout (e.g. a flat dist lib dir) — keep unconditionally.
                byArtifact.computeIfAbsent(jar.toString(), k -> new LinkedHashMap<>())
                        .computeIfAbsent("", k -> new ArrayList<>()).add(jar);
                continue;
            }
            String version = versionDir.getFileName().toString();
            byArtifact.computeIfAbsent(artifactDir.toString(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(version, k -> new ArrayList<>()).add(jar);
        }
        LinkedHashSet<Path> kept = new LinkedHashSet<>();
        for (Map<String, List<Path>> versions : byArtifact.values()) {
            String newest = versions.keySet().stream().max(Comparator.naturalOrder()).orElse("");
            kept.addAll(versions.getOrDefault(newest, List.of()));
        }
        return kept;
    }

    /**
     * Read the standard backend descriptor device type from a jar. A jar counts as a backend
     * jar only when it carries the service-loader registration every real backend jar carries:
     * {@code META-INF/services/org.nd4j.linalg.factory.Nd4jBackend} naming e.g.
     * {@code org.nd4j.linalg.jcublas.JCublasBackend} (this also excludes shaded uber-jars that
     * merely embed a descriptor among thousands of other classes — those are application
     * assemblies, not backend artifacts). The {@code nd4j-*.properties} descriptor may live in
     * this jar, a sibling natives jar of the same artifact, or a POM-declared support artifact
     * ({@code *-backend-common}) — it is confirmed via {@link #closureDeviceType}.
     * Returns the device type, or null when the jar registers no backend.
     */
    static String descriptorDeviceType(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create("jar:" + jar.toUri());
        } catch (Exception e) {
            return null;
        }
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
            Path services = fs.getPath(BACKEND_SERVICES_FILE);
            if (!Files.exists(services)) {
                return null; // not a registered backend artifact (e.g. a shaded application jar)
            }
            // The registration names the backend class; the descriptor follows the class-derived
            // naming (…/JCublasBackend → nd4j-jcublas.properties). Confirm via the closure so the
            // check stays valid wherever the descriptor artifact sits.
            try (var in = Files.newInputStream(services)) {
                // Services files carry license comment lines; the class name is the first
                // non-comment, non-blank token line.
                String registered = new String(in.readAllBytes()).lines()
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .findFirst()
                        .orElse("");
                return registered.isBlank() ? null : SERVICES_REGISTERED_MARKER + registered;
            } catch (IOException e) {
                return null;
            }
        } catch (Exception e) {
            logger.debug("[BACKEND] Could not read registration from {}: {}", jar, e.getMessage());
            return null;
        }
    }

    /**
     * Read the descriptor from the platform-classifier sibling jar in the same artifact/version
     * directory (Maven layout). Kept for descriptor-only confirmation paths.
     */
    private static String descriptorFromPlatformSibling(Path jar) {
        Path dir = jar.getParent();
        if (dir == null) {
            return null;
        }
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) {
            return null;
        }
        String base = name.substring(0, name.length() - ".jar".length());
        try (Stream<Path> listing = Files.list(dir)) {
            Optional<Path> sibling = listing
                    .filter(p -> p.getFileName().toString().startsWith(base + "-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .findFirst();
            if (sibling.isEmpty()) {
                return null;
            }
            return rootDescriptorDeviceType(sibling.get());
        } catch (Exception e) {
            logger.debug("[BACKEND] Could not read sibling descriptor from {}: {}", jar, e.getMessage());
            return null;
        }
    }

    /**
     * Read {@code device.type} from an {@code nd4j-*.properties} descriptor at a jar's root.
     * Returns null when the jar has no such descriptor.
     */
    static String rootDescriptorDeviceType(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create("jar:" + jar.toUri());
        } catch (Exception e) {
            return null;
        }
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
            Path root = fs.getRootDirectories().iterator().next();
            try (Stream<Path> stream = Files.walk(root, 1)) {
                Optional<Path> descriptor = stream
                        .filter(p -> p.getFileName() != null)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith("nd4j-") && n.endsWith(".properties"))
                        .filter(n -> !n.equals("nd4j-native.properties"))
                        .map(root::resolve)
                        .filter(Files::isRegularFile)
                        .findFirst();
                if (descriptor.isEmpty()) {
                    return null;
                }
                Properties props = new Properties();
                try (var in = Files.newInputStream(descriptor.get())) {
                    props.load(in);
                }
                String value = props.getProperty(DESCRIPTOR_DEVICE_TYPE_KEY);
                return value != null ? value.trim() : null;
            }
        } catch (Exception e) {
            logger.debug("[BACKEND] Could not read descriptor from {}: {}", jar, e.getMessage());
            return null;
        }
    }

    /** Does a jar carry the given class resource? */
    static boolean jarHasClass(Path jar, String classResource) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }
        URI uri = URI.create("jar:" + jar.toUri());
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of())) {
            return Files.exists(fs.getPath(classResource));
        } catch (Exception e) {
            return false;
        }
    }

    /** Marker prefixing a backend class name read from a services registration. */
    static final String SERVICES_REGISTERED_MARKER = "registered:";

    /**
     * Classify a registered backend by its descriptor chain: the services registration names the
     * backend class (e.g. {@code org.nd4j.linalg.jcublas.JCublasBackend}); the class's standard
     * config resource {@code nd4j-<basename>.properties} declares {@code device.type}. Falls back
     * to the class-name segment when the descriptor isn't reachable locally.
     */
    private static boolean isCudaDeviceType(String deviceType) {
        if (deviceType == null) {
            return false;
        }
        if (deviceType.startsWith(SERVICES_REGISTERED_MARKER)) {
            String backendClass = deviceType.substring(SERVICES_REGISTERED_MARKER.length()).trim();
            String simple = backendClass.substring(backendClass.lastIndexOf('.') + 1);
            // JCublasBackend → descriptor resource "nd4j-jcublas.properties"
            String base = simple.endsWith("Backend") ? simple.substring(0, simple.length() - "Backend".length()) : simple;
            String upper = base.toUpperCase(Locale.ROOT);
            return upper.contains("CUDA") || upper.contains("CUBLAS") || upper.contains("GPU");
        }
        return deviceType.toUpperCase(Locale.ROOT).contains("CUDA");
    }

    private static Path Paths(String entry) { // wrapper keeps call sites terse; not java.nio.Paths
        return Path.of(entry);
    }

    private static String expand(String raw) {
        if (raw.startsWith("~/") || raw.equals("~")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }

    /**
     * Log the actual ND4J backend that was loaded at runtime. Call this AFTER Nd4j initialization
     * (e.g., after Nd4j.scalar(0.0f)) to produce an unmistakable banner showing exactly which
     * backend the subprocess is actually using.
     *
     * @param subprocessType human-readable subprocess type (e.g., "EMBEDDING", "LLM_SERVING")
     * @param backendClassName the simple class name of the loaded backend (e.g., "CpuBackend", "CudaBackend")
     */
    public static void logRuntimeBackendBanner(String subprocessType, String backendClassName) {
        String normalizedBackend = backendClassName != null ? backendClassName.toLowerCase(Locale.ROOT) : "";
        boolean isCuda = normalizedBackend.contains("cuda")
                || normalizedBackend.contains("gpu")
                || normalizedBackend.contains("jcublas")
                || normalizedBackend.contains("cublas");
        String backendLabel = isCuda ? "CUDA (GPU)" : "CPU";
        String banner = String.format(
                "\n" +
                "╔══════════════════════════════════════════════════════════════╗\n" +
                "║  SUBPROCESS ND4J BACKEND - RUNTIME VERIFICATION             ║\n" +
                "╠══════════════════════════════════════════════════════════════╣\n" +
                "║  Subprocess Type : %-40s ║\n" +
                "║  Backend Class   : %-40s ║\n" +
                "║  Backend Type    : %-40s ║\n" +
                "║  PID             : %-40s ║\n" +
                "║  Platform        : %-40s ║\n" +
                "╚══════════════════════════════════════════════════════════════╝",
                subprocessType,
                backendClassName != null ? backendClassName : "UNKNOWN",
                backendLabel,
                String.valueOf(ProcessHandle.current().pid()),
                detectPlatform()
        );
        logger.info(banner);
    }

    /**
     * Detect the current platform string for Maven classifier matching.
     */
    public static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("linux")) {
            return arch.contains("aarch64") || arch.contains("arm64") ? "linux-arm64" : "linux-x86_64";
        } else if (os.contains("mac")) {
            return arch.contains("aarch64") || arch.contains("arm64") ? "macosx-arm64" : "macosx-x86_64";
        } else if (os.contains("win")) {
            return "windows-x86_64";
        }
        return "linux-x86_64";
    }

    /**
     * The root-location config: WHERE to search. Which jars are backend jars is decided purely
     * by their standard descriptors, so this file never names artifacts, versions, or patterns.
     */
    private static final class BackendSearchConfig {
        final List<String> extraJarRoots;
        final String mavenRepoPath;

        private BackendSearchConfig(List<String> extraJarRoots, String mavenRepoPath) {
            this.extraJarRoots = extraJarRoots;
            this.mavenRepoPath = mavenRepoPath;
        }

        static BackendSearchConfig load() {
            String raw = System.getProperty("kompile.subprocess.backend.config.json");
            List<String> candidatePaths = new ArrayList<>();
            if (raw != null && !raw.isBlank()) {
                candidatePaths.add(expand(raw));
            }
            String distHome = System.getProperty("kompile.dist.home", System.getenv("KOMPILE_DIST_HOME"));
            if (distHome != null && !distHome.isBlank()) {
                candidatePaths.add(Path.of(distHome, "config", "subprocess-backend-config.json").toString());
            }
            candidatePaths.add(Path.of(System.getProperty("user.home"), ".kompile", "config",
                    "subprocess-backend-config.json").toString());
            for (String candidate : candidatePaths) {
                Path p = Path.of(candidate);
                if (Files.isRegularFile(p)) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode node =
                                new com.fasterxml.jackson.databind.ObjectMapper().readTree(p.toFile());
                        List<String> extras = new ArrayList<>();
                        if (node.has("extraJarRoots") && node.get("extraJarRoots").isArray()) {
                            node.get("extraJarRoots").forEach(n -> extras.add(n.asText()));
                        }
                        String repo = node.hasNonNull("mavenRepoPath") ? node.get("mavenRepoPath").asText() : null;
                        return new BackendSearchConfig(extras, repo);
                    } catch (IOException e) {
                        logger.warn("[BACKEND] Could not parse {}: {} — falling back to defaults", p, e.getMessage());
                    }
                }
            }
            // Sensible default: the user's maven repository. Artifact discovery itself stays
            // descriptor-driven; this only names a search location.
            return new BackendSearchConfig(List.of(),
                    Path.of(System.getProperty("user.home"), ".m2", "repository").toString());
        }
    }
}
