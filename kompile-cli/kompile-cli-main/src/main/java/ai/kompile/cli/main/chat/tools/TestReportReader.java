package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, XXE-safe report import, restricted to reports changed during this execution. */
final class TestReportReader {
    private static final long MAX_FILE = 8 * 1024 * 1024;
    private static final int MAX_REPORTS = 128;
    private final Path directory;
    private final TestCaptureConfig config;
    private final List<Path> explicit = new ArrayList<>();
    private final Map<Path, String> before = new HashMap<>();
    private final Instant started;
    private final Consumer<String> warning;
    private boolean baselineComplete = true;
    private List<Path> used = List.of();

    TestReportReader(Path directory, TestCaptureConfig config, String command, Instant started, Consumer<String> warning) {
        this.directory = directory;
        this.config = config;
        this.started = started;
        this.warning = warning;
        for (String path : config.reportPaths) explicit.add(directory.resolve(path).normalize());
        for (List<String> segment : TestRunDetector.segments(command)) {
            for (int i = 0; i < segment.size(); i++) {
                String token = segment.get(i);
                for (String flag : List.of("--junitxml", "--junit-xml", "--output-junit", "--outputFile", "--output-file", "--json-output-path", "--junit-output-path")) {
                    if (token.startsWith(flag + "=")) explicit.add(directory.resolve(token.substring(flag.length() + 1)).normalize());
                    else if (token.equals(flag) && i + 1 < segment.size()) explicit.add(directory.resolve(segment.get(i + 1)).normalize());
                }
                if (token.startsWith("--gtest_output=")) {
                    int colon = token.indexOf(':');
                    if (colon > 0) explicit.add(directory.resolve(token.substring(colon + 1)).normalize());
                }
            }
        }
        try {
            for (Path path : files()) before.put(path, fingerprint(path));
        } catch (Exception e) {
            baselineComplete = false;
            warning.accept("Automatic test capture cannot establish report freshness: " + e.getMessage());
        }
    }

    List<String> references() { return used.stream().map(Path::toString).toList(); }

    TestRunDetector.Result fresh(String framework) {
        if (!baselineComplete) return null;
        try {
            List<TestRunDetector.Result> results = new ArrayList<>();
            List<Path> references = new ArrayList<>();
            for (Path path : files()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(started.minusMillis(1500))) continue;
                if (Objects.equals(before.get(path), fingerprint(path))) continue;
                TestRunDetector.Result result = parse(path, framework);
                if (result == null) throw new IOException("Unrecognized or incomplete test report: " + path);
                results.add(result);
                references.add(path);
            }
            if (results.isEmpty()) return null;
            used = List.copyOf(references);
            TestRunDetector.Result value = TestRunDetector.aggregate(results);
            return new TestRunDetector.Result(value.framework(), value.outcome(), value.total(), value.passed(), value.failed(),
                    value.errors(), value.skipped(), value.unit(), "fresh-structured-reports");
        } catch (Exception e) {
            warning.accept("Automatic test capture ignored ambiguous reports: " + e.getMessage());
            return null;
        }
    }

    private Set<Path> files() throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        List<Path> roots = new ArrayList<>(explicit);
        // Known report locations only. Do not walk source trees, node_modules, caches, or arbitrary logs.
        Deque<Path> modules = new ArrayDeque<>();
        modules.add(directory);
        int visited = 0;
        while (!modules.isEmpty() && visited++ < 256) {
            Path module = modules.removeFirst();
            for (String suffix : List.of("target/surefire-reports", "target/failsafe-reports", "build/test-results",
                    "TestResults", ".vitest/junit", ".vitest/json", "test-results.xml", "test-results.json", "junit.xml")) roots.add(module.resolve(suffix));
            if (directory.relativize(module).getNameCount() >= 4 || !Files.isDirectory(module)) continue;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(module)) {
                for (Path child : children) {
                    String name = child.getFileName().toString();
                    if (name.startsWith(".") || Set.of("src", "target", "build", "node_modules", "data", "docs", "lib", "dist", "vendor", "blasbuild").contains(name)) continue;
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(child.resolve("pom.xml"))) modules.add(child);
                }
            }
        }
        if (!modules.isEmpty()) throw new IOException("Too many modules for bounded report discovery; configure narrower reportPaths");
        for (Path root : roots) {
            if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) add(files, root);
            else if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                try (var walk = Files.walk(root, 3)) {
                    Iterator<Path> iterator = walk.iterator();
                    int seen = 0;
                    while (iterator.hasNext()) {
                        if (++seen > 2048) throw new IOException("Report directory exceeds capture limit: " + root);
                        Path file = iterator.next();
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) add(files, file);
                    }
                }
            }
        }
        long bytes = 0;
        for (Path file : files) {
            bytes += Files.size(file);
            if (bytes > 32L * 1024 * 1024) throw new IOException("Test report set exceeds 32 MiB capture limit");
        }
        return files;
    }
    private static void add(Set<Path> paths, Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml") || name.endsWith(".trx") || name.endsWith(".json")) {
            paths.add(file.toAbsolutePath().normalize());
            if (paths.size() > MAX_REPORTS) throw new IOException("Too many test reports (limit " + MAX_REPORTS + ")");
        }
    }
    private static byte[] bytes(Path file) throws IOException {
        if (Files.size(file) > MAX_FILE) throw new IOException("Test report exceeds 8 MiB: " + file);
        try (var input = Files.newInputStream(file)) {
            byte[] value = input.readNBytes((int) MAX_FILE + 1);
            if (value.length > MAX_FILE) throw new IOException("Growing test report exceeds 8 MiB: " + file);
            return value;
        }
    }
    private static String fingerprint(Path file) throws Exception {
        return Files.getLastModifiedTime(file) + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(file)));
    }

    static TestRunDetector.Result parse(Path file, String framework) throws Exception {
        byte[] value = bytes(file);
        if (file.toString().endsWith(".json")) {
            JsonNode json = JsonUtils.standardMapper().readTree(value);
            if (json.has("numTotalTests") && json.has("numFailedTests") && json.has("numPassedTests")) {
                long total = json.path("numTotalTests").asLong(-1), failed = json.path("numFailedTests").asLong(-1), passed = json.path("numPassedTests").asLong(-1);
                long skipped = json.path("numPendingTests").asLong() + json.path("numTodoTests").asLong();
                if (passed < 0 || total != passed + failed + skipped) return null;
                return TestRunDetector.counts(framework, total, failed, 0, skipped, "report-json");
            }
            JsonNode stats = json.path("stats");
            if (stats.has("expected") && stats.has("unexpected")) {
                long passed = stats.path("expected").asLong() + stats.path("flaky").asLong(), failed = stats.path("unexpected").asLong(), skipped = stats.path("skipped").asLong();
                return TestRunDetector.counts(framework, passed + failed + skipped, failed, 0, skipped, "report-json");
            }
            return null;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(value));
        Element root = document.getDocumentElement();
        String kind = root.getLocalName();
        if ("TestRun".equals(kind)) {
            NodeList counters = root.getElementsByTagNameNS("*", "Counters");
            if (counters.getLength() != 1) return null;
            Element c = (Element) counters.item(0);
            long total = attr(c, "total"), passed = attr(c, "passed"), failed = attr(c, "failed");
            long error = attr(c, "error") + attr(c, "timeout") + attr(c, "aborted");
            long skipped = attr(c, "notExecuted") + attr(c, "inconclusive");
            if (total != passed + failed + error + skipped) return null;
            return TestRunDetector.counts(framework, total, failed, error, skipped, "report-trx");
        }
        if (!Set.of("testsuite", "testsuites", "test-run", "test-results").contains(kind)) return null;
        NodeList cases = root.getElementsByTagNameNS("*", "testcase");
        boolean nunit = kind.startsWith("test-");
        if (nunit) cases = root.getElementsByTagNameNS("*", "test-case");
        if (cases.getLength() > 0) {
            long failed = 0, errors = 0, skipped = 0;
            for (int i = 0; i < cases.getLength(); i++) {
                Element test = (Element) cases.item(i);
                String status = test.getAttribute("result");
                if (test.getElementsByTagName("error").getLength() > 0 || status.equals("Error")) errors++;
                else if (test.getElementsByTagName("failure").getLength() > 0 || Set.of("Failed", "Failure").contains(status)) failed++;
                else if (test.getElementsByTagName("skipped").getLength() > 0 || Set.of("Skipped", "Ignored", "Inconclusive", "NotRunnable").contains(status)
                        || test.getAttribute("status").equals("notrun") || test.getAttribute("executed").equals("False")) skipped++;
                else if (nunit && !Set.of("Passed", "Success").contains(status)) return null;
            }
            return TestRunDetector.counts(framework, cases.getLength(), failed, errors, skipped, "report-xml");
        }
        // Summary-only JUnit reports are valid; nested suite totals must not be added twice.
        List<TestRunDetector.Result> results = new ArrayList<>();
        NodeList suites = document.getElementsByTagNameNS("*", "testsuite");
        for (int i = 0; i < suites.getLength(); i++) {
            Element suite = (Element) suites.item(i);
            if (suite.getElementsByTagNameNS("*", "testsuite").getLength() > 0 || !suite.hasAttribute("tests")) continue;
            var result = TestRunDetector.counts(framework, attr(suite, "tests"), attr(suite, "failures"), attr(suite, "errors"), attr(suite, "skipped"), "report-xml");
            if (result == null) return null;
            results.add(result);
        }
        return results.isEmpty() ? null : TestRunDetector.aggregate(results);
    }
    private static long attr(Element node, String name) { String text = node.getAttribute(name); return text.isEmpty() ? 0 : Long.parseLong(text); }
}
