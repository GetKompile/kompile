package ai.kompile.testmilestone.service;

import ai.kompile.testmilestone.domain.TestCaseResultEntity;
import ai.kompile.utils.StringUtils;
import ai.kompile.testmilestone.domain.TestCaseStatus;
import lombok.extern.slf4j.Slf4j;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses Maven Surefire XML reports from target/surefire-reports/ directories.
 */
@Slf4j
public class SurefireReportParser {

    /**
     * Parse all surefire XML reports from a directory.
     */
    public static ParseResult parseDirectory(Path surefireReportsDir) {
        List<TestCaseResultEntity> results = new ArrayList<>();
        int totalTests = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int errors = 0;
        long totalDuration = 0;

        if (!Files.isDirectory(surefireReportsDir)) {
            log.warn("Surefire reports directory does not exist: {}", surefireReportsDir);
            return new ParseResult(results, 0, 0, 0, 0, 0, 0);
        }

        try (Stream<Path> files = Files.list(surefireReportsDir)) {
            List<Path> xmlFiles = files
                    .filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> p.getFileName().toString().startsWith("TEST-"))
                    .toList();

            for (Path xmlFile : xmlFiles) {
                try {
                    List<TestCaseResultEntity> parsed = parseFile(xmlFile.toFile());
                    results.addAll(parsed);
                } catch (Exception e) {
                    log.warn("Failed to parse surefire report: {}", xmlFile, e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to list surefire reports in: {}", surefireReportsDir, e);
        }

        for (TestCaseResultEntity r : results) {
            totalTests++;
            totalDuration += r.getDurationMs();
            switch (r.getStatus()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case ERROR -> errors++;
            }
        }

        return new ParseResult(results, totalTests, passed, failed, skipped, errors, totalDuration);
    }

    /**
     * Parse a single surefire XML report file.
     */
    static List<TestCaseResultEntity> parseFile(File xmlFile) throws Exception {
        List<TestCaseResultEntity> results = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlFile);

        Element root = doc.getDocumentElement();
        String suiteName = root.getAttribute("name");

        NodeList testCases = root.getElementsByTagName("testcase");
        for (int i = 0; i < testCases.getLength(); i++) {
            Element tc = (Element) testCases.item(i);
            String className = tc.getAttribute("classname");
            String methodName = tc.getAttribute("name");
            double timeSeconds = parseDouble(tc.getAttribute("time"), 0.0);
            long durationMs = (long) (timeSeconds * 1000);

            if (className == null || className.isEmpty()) {
                className = suiteName;
            }

            TestCaseStatus status = TestCaseStatus.PASSED;
            String errorMessage = null;
            String stackTrace = null;

            NodeList failures = tc.getElementsByTagName("failure");
            if (failures.getLength() > 0) {
                status = TestCaseStatus.FAILED;
                Element failure = (Element) failures.item(0);
                errorMessage = failure.getAttribute("message");
                stackTrace = failure.getTextContent();
            }

            NodeList errorsNodes = tc.getElementsByTagName("error");
            if (errorsNodes.getLength() > 0) {
                status = TestCaseStatus.ERROR;
                Element error = (Element) errorsNodes.item(0);
                errorMessage = error.getAttribute("message");
                stackTrace = error.getTextContent();
            }

            NodeList skippedNodes = tc.getElementsByTagName("skipped");
            if (skippedNodes.getLength() > 0) {
                status = TestCaseStatus.SKIPPED;
                Element skip = (Element) skippedNodes.item(0);
                errorMessage = skip.getAttribute("message");
            }

            results.add(TestCaseResultEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .className(className)
                    .methodName(methodName)
                    .status(status)
                    .errorMessage(StringUtils.truncate(errorMessage, 2048))
                    .errorStackTrace(stackTrace)
                    .durationMs(durationMs)
                    .build());
        }

        return results;
    }

    private static double parseDouble(String value, double defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public record ParseResult(
            List<TestCaseResultEntity> testCases,
            int totalTests,
            int passed,
            int failed,
            int skipped,
            int errors,
            long totalDurationMs
    ) {}
}
