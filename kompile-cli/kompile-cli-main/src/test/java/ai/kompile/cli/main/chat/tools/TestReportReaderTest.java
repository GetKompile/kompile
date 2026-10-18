package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestReportReaderTest {
    @TempDir Path root;
    @Test void nestedJUnitSuiteTotalsAreNotDoubleCounted() throws Exception {
        var result = xml("<testsuites tests='99'><testsuite tests='99'><testsuite tests='3'>"
                + "<testcase name='pass'/><testcase name='failure'><failure/></testcase>"
                + "<testcase name='skip'><skipped/></testcase></testsuite></testsuite></testsuites>");
        assertEquals(3L, result.total());
        assertEquals(1L, result.passed());
        assertEquals(1L, result.failed());
        assertEquals(1L, result.skipped());
    }
    @Test void summaryOnlySuitesUseLeafCounts() throws Exception {
        var result = xml("<testsuite tests='6'><testsuite tests='2' failures='1'/>"
                + "<testsuite tests='4' errors='1' skipped='2'/></testsuite>");
        assertEquals(6L, result.total());
        assertEquals(1L, result.errors());
        assertEquals(2L, result.skipped());
    }
    @Test void xxeAndDtdAreRejectedRatherThanReadingLocalFiles() throws Exception {
        Path secret = Files.writeString(root.resolve("secret"), "must-not-be-read");
        assertThrows(Exception.class, () -> xml("<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM '"
                + secret.toUri() + "'>]><testsuite tests='1'><testcase name='&xxe;'/></testsuite>"));
    }
    @Test void malformedAndImpossibleReportsCannotBeGreen() throws Exception {
        assertThrows(Exception.class, () -> xml("<testsuite>"));
        assertNull(xml("<testsuite tests='2' failures='3'/>"));
        assertNull(xml("<configuration><testcase name='a'/></configuration>"));
        assertEquals("no_tests", xml("<testsuite tests='0'/>").outcome());
        assertEquals("skipped", xml("<testsuite tests='2' skipped='2'/>").outcome());
    }
    @Test void trxCountersUseTheirOwnNamespacedFormat() throws Exception {
        var result = xml("<TestRun xmlns='http://microsoft.com/schemas/VisualStudio/TeamTest/2010'>"
                + "<ResultSummary><Counters total='5' passed='2' failed='1' error='1' notExecuted='1'/>"
                + "</ResultSummary></TestRun>");
        assertEquals(5L, result.total());
        assertEquals(1L, result.errors());
        assertEquals(1L, result.skipped());
        assertEquals("failed", result.outcome());
    }
    @Test void pesterNunitCountsOnlyTestCases() throws Exception {
        var result = xml("<test-results><test-suite result='Failure'><results>"
                + "<test-case result='Success'/><test-case result='Failure'><failure/></test-case>"
                + "<test-case result='Ignored'/></results></test-suite></test-results>");
        assertEquals(3L, result.total());
        assertEquals(1L, result.passed());
        assertEquals(1L, result.skipped());
    }
    @Test void jestAndVitestJsonValidateCountConsistency() throws Exception {
        var result = json("{\"numTotalTests\":4,\"numPassedTests\":2,\"numFailedTests\":1,\"numPendingTests\":1}");
        assertEquals(4L, result.total());
        assertEquals("failed", result.outcome());
        assertNull(json("{\"numTotalTests\":1,\"numPassedTests\":0,\"numFailedTests\":0}"));
    }
    @Test void playwrightJsonKeepsRetriesFromInflatingTotal() throws Exception {
        var result = json("{\"stats\":{\"expected\":2,\"unexpected\":1,\"flaky\":1,\"skipped\":1}}");
        assertEquals(5L, result.total());
        assertEquals(3L, result.passed());
        assertEquals(1L, result.failed());
    }
    private TestRunDetector.Result xml(String xml) throws Exception {
        return TestReportReader.parse(Files.writeString(root.resolve("report.xml"), xml), "fixture");
    }
    private TestRunDetector.Result json(String json) throws Exception {
        return TestReportReader.parse(Files.writeString(root.resolve("report.json"), json), "fixture");
    }
}
