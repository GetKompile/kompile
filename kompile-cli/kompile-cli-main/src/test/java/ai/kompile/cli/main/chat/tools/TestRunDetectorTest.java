package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class TestRunDetectorTest {
    @TempDir Path root;
    record Fixture(String command, String output, String framework, String outcome, Long total) {}
    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("/opt/mvn/bin/mvn test", "[INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 1, Time elapsed: 0.1 s -- in X", "jvm", "failed", 3L),
                new Fixture("./gradlew test", "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0", "jvm", "passed", 2L),
                new Fixture("sbt test", "[info] Tests: succeeded 2, failed 1, canceled 0, ignored 1, pending 0", "scalatest", "failed", 4L),
                new Fixture("pio test", "2 Tests 0 Failures 0 Ignored", "unity", "passed", 2L),
                new Fixture("node --test", "ℹ tests 3\nℹ pass 2\nℹ fail 0\nℹ cancelled 0\nℹ skipped 1\nℹ todo 0\nℹ duration_ms 0.123", "node", "passed", 3L),
                new Fixture("playwright test", "1 failed\n2 passed (1.2s)", "playwright", "failed", 3L),
                new Fixture("prove t", "All tests successful.\nFiles=1, Tests=3, 0 wallclock secs\nResult: PASS", "perl", "passed", 3L),
                new Fixture("python3 -m pytest -q", "2 passed, 1 skipped in 0.12s", "pytest", "passed", 3L),
                new Fixture("pytest", "=== 1 failed, 2 passed in 1.00s ===", "pytest", "failed", 3L),
                new Fixture("python -m unittest", "Ran 3 tests in 0.123s\n\nOK (skipped=1)", "unittest", "passed", 3L),
                new Fixture("python -m doctest -v docs.py", "2 passed and 0 failed.", "unittest", "passed", 2L),
                new Fixture("shunit2 checks.sh", "Ran 2 tests.\nOK", "unittest", "passed", 2L),
                new Fixture("java -jar junit-platform-console-standalone.jar execute", "[ 2 tests successful ]\n[ 0 tests failed ]", "jvm", "passed", 2L),
                new Fixture("java org.testng.TestNG testng.xml", "Total tests run: 3, Passes: 2, Failures: 1, Skips: 0", "jvm", "failed", 3L),
                new Fixture("cargo test", "test result: ok. 2 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.0s", "rust", "passed", 3L),
                new Fixture("ctest", "100% tests passed, 0 tests failed out of 4", "ctest", "passed", 4L),
                new Fixture("./native-tests", "[ RUN      ] Foo.Bar\n[==========] 2 tests from 1 test suite ran. (0 ms total)\n[  PASSED  ] 1 test.\n[  FAILED  ] 1 test.", "gtest", "failed", 2L),
                new Fixture("./catch-tests", "All tests passed (12 assertions in 2 test cases)", "catch2", "passed", 2L),
                new Fixture("dotnet test", "Passed!  - Failed: 0, Passed: 2, Skipped: 1, Total: 3, Duration: 1 ms", "dotnet", "passed", 3L),
                new Fixture("swift test", "Executed 2 tests, with 0 failures (0 unexpected) in 0.1 seconds", "xctest", "passed", 2L),
                new Fixture("swift test", "◇ Test run with 2 tests in 0 suites passed after 0.001 seconds.", "swift", "passed", 2L),
                new Fixture("flutter test", "00:01 +2: All tests passed!", "dart", "passed", 2L),
                new Fixture("npx jest", "Tests: 1 failed, 2 passed, 3 total", "jest", "failed", 3L),
                new Fixture("pnpm vitest run", "Tests  2 passed | 1 skipped (3)", "vitest", "passed", 3L),
                new Fixture("npx mocha", "  2 passing (10ms)\n  1 failing", "mocha", "failed", 3L),
                new Fixture("bundle exec rspec", "3 examples, 1 failure, 1 pending", "rspec", "failed", 3L),
                new Fixture("ruby test/example_test.rb", "3 runs, 12 assertions, 0 failures, 0 errors, 1 skips", "minitest", "passed", 3L),
                new Fixture("prove -v t", "1..3\nok 1 - pass\nnot ok 2 - fail\nok 3 # SKIP unsupported", "perl", "failed", 3L),
                new Fixture("bats tests", "1..2\nok 1 - works\nok 2 - skipped # SKIP missing", "bats", "passed", 2L),
                new Fixture("pg_prove tests", "1..1\nok 1 - SQL assertion", "pgtap", "passed", 1L),
                new Fixture("node --test", "TAP version 13\n1..1\nok 1 - test", "node", "passed", 1L),
                new Fixture("busted", "2 successes / 1 failure / 0 errors / 1 pending : 0.001 seconds", "lua", "failed", 4L),
                new Fixture("pwsh -Command Invoke-Pester", "Tests Passed: 2, Failed: 0, Skipped: 1, NotRun: 0", "pester", "passed", 3L),
                new Fixture("vim '+Vader!*'", "Success/Total: 2/3 (assertions: 4/5)", "vader", "failed", 3L),
                new Fixture("sage -t file.py", "[3 tests, 0.1 s]\nAll tests passed!", "sage", "passed", 3L),
                new Fixture("./custom-tests", "3 tests executed: 2 passed, 1 failed", "generic", "failed", 3L),
                new Fixture("./custom-tests", "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0", "jvm", "passed", 2L));
    }
    @ParameterizedTest @MethodSource("fixtures")
    void commonFrameworkCompletionEvidence(Fixture f) {
        var result = detect(f.command(), f.output(), 0);
        assertNotNull(result, f.toString());
        assertEquals(f.framework(), result.framework());
        assertEquals(f.outcome(), result.outcome());
        assertEquals(f.total(), result.total());
    }
    @ParameterizedTest @ValueSource(strings = {
            "mvn compile", "/opt/bin/mvn test-compile", "mvn install -DskipTests", "mvn test -DskipTests=true",
            "mvn verify -Dmaven.test.skip=true", "cargo build --tests", "cargo test --no-run", "cargo test -- --list",
            "ctest -N", "ctest --show-only=json-v1", "go test -c", "go test -list=TestX", "swift test list",
            "go test -run=^$ -bench=BenchmarkX", "gradle build --dry-run", "gradle test --test-dry-run",
            "pytest --collect-only", "python -m pytest --co", "dotnet build", "dotnet test --list-tests",
            "jest --listTests", "jest --showConfig", "bats --count", "pio test --without-testing",
            "gcc test.c -o test", "cmake --build build", "echo 'pytest tests'", "/usr/bin/cat old-test.log",
            "bash -c 'cat old-test.log'", "test -f tests.log", "git show HEAD:tests.log", "npm run build",
            "pytest ; cat old-test.log", "mvn install && echo 'Tests run: 3, Failures: 0, Errors: 0, Skipped: 0'",
            "cd $PROJECT && pytest"})
    void buildsAndDiscoveryCannotBecomeMilestones(String command) {
        assertNull(TestRunDetector.plan(command, root, config()));
    }
    @Test void buildSuccessAndExitZeroAreNotExecutionEvidence() {
        assertNull(detect("./custom-tests", "BUILD SUCCESS\nPASS\nSUCCESS", 0));
        assertEquals("unknown", detect("mvn test", "BUILD SUCCESS", 0).outcome());
        assertEquals("execution_error", detect("cargo test", "error: could not compile", 1).outcome());
        assertEquals("setup_error", detect("pytest", "ERROR collecting test_x.py\n1 error in 0.1s", 2).outcome());
        assertEquals("unknown", detect("pytest", "1 passed in 0.1s", 2).outcome());
    }
    @Test void noTestsAndCachedAreNeutral() {
        assertEquals("no_tests", detect("pytest", "no tests ran in 0.00s", 5).outcome());
        assertNull(detect("pytest", "no tests ran in 0.00s", 5).passing());
        assertEquals("cached", detect("go test ./...", "ok example/package (cached)", 0).outcome());
        assertEquals("skipped", detect("pytest", "2 skipped in 0.01s", 0).outcome());
    }
    @Test void skippedCtestAndCachedGoJsonNeverCertifyFreshPassingTests() {
        assertEquals("skipped", detect("ctest", "1/1 Test #1: sample ...***Skipped 0.00 sec\n100% tests passed, 0 tests failed out of 1", 0).outcome());
        assertEquals("cached", detect("go test -json", "{\"Action\":\"run\",\"Package\":\"p\",\"Test\":\"TestX\"}\n"
                + "{\"Action\":\"pass\",\"Package\":\"p\",\"Test\":\"TestX\"}\n"
                + "{\"Action\":\"output\",\"Package\":\"p\",\"Output\":\"ok p (cached)\\n\"}\n"
                + "{\"Action\":\"pass\",\"Package\":\"p\"}", 0).outcome());
    }
    @Test void outputLimitsNeverTurnPartialResultsIntoAGreenMilestone() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4097; i++) text.append("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in C").append(i).append('\n');
        var result = detect("mvn test", text.toString(), 0);
        assertEquals("unknown", result.outcome());
        assertEquals("output-count-limit", result.basis());
        assertNull(result.total());
    }
    @Test void compoundBuildThenTestAndBooleanFalseFlags() {
        assertEquals("passed", detect("mvn install -DskipTests && python -m pytest -q", "2 passed in 0.1s", 0).outcome());
        assertEquals("passed", detect("mvn verify -DskipITs", "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0", 0).outcome());
        assertEquals("passed", detect("mvn test -DskipTests=false -N", "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0", 0).outcome());
        assertEquals("passed", detect("bash -c 'pytest -q'", "2 passed in 0.1s", 0).outcome());
    }
    @Test void suitesAndAggregateLinesAreNotAddedTwice() {
        var result = detect("mvn test", "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in A\n"
                + "Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 -- in B\n"
                + "Tests run: 5, Failures: 1, Errors: 0, Skipped: 0", 1);
        assertEquals(5L, result.total());
        assertEquals(1L, result.failed());
    }
    @Test void mixedRunnerSummariesAndVerboseParentRunnersAreNotLostOrDoubleCounted() {
        var mixed = detect("mvn test && pytest", "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in A\n3 passed in 0.1s", 0);
        assertEquals("mixed", mixed.framework());
        assertEquals(5L, mixed.total());
        var nested = detect("ctest -V", "[==========] 2 tests from 1 test suite ran. (0 ms)\n[ PASSED ] 2 tests.\n100% tests passed, 0 tests failed out of 1", 0);
        assertEquals("ctest", nested.framework());
        assertEquals(1L, nested.total());
    }
    @Test void tapNeedsACompletePlanAndHonorsTodoAndSubtests() {
        assertEquals("unknown", detect("prove -v", "1..2\nok 1", 0).outcome());
        assertEquals("unknown", detect("prove -v", "1..1\nBail out! crash", 0).outcome());
        var result = detect("node --test", "    1..1\n    ok 1 - nested\n1..2\nok 1 - parent\nnot ok 2 # TODO future", 0);
        assertEquals(2L, result.total());
        assertEquals("passed", result.outcome());
        assertEquals(1L, result.skipped());
    }
    @Test void expectationCountsAreNotMisrepresentedAsTestCounts() {
        var result = detect("Rscript -e 'testthat::test_local()'", "[ FAIL 0 | WARN 1 | SKIP 1 | PASS 4 ]", 0);
        assertEquals("expectations", result.unit());
        assertEquals(5L, result.total());
    }
    @Test void goJsonPreservesTestResultsAndFailedBuild() {
        var result = detect("go test -json", "{\"Action\":\"run\",\"Package\":\"p\",\"Test\":\"TestX\"}\n"
                + "{\"Action\":\"pass\",\"Package\":\"p\",\"Test\":\"TestX\"}\n{\"Action\":\"pass\",\"Package\":\"p\"}", 0);
        assertEquals(1L, result.total());
        assertEquals("setup_error", detect("go test -json", "{\"Action\":\"fail\",\"FailedBuild\":\"p\"}", 1).outcome());
    }
    private TestCaptureConfig config() { return TestCaptureConfig.load(root, text -> fail(text)); }
    private TestRunDetector.Result detect(String command, String output, int exit) {
        var plan = TestRunDetector.plan(command, root, config());
        assertNotNull(plan, command);
        var detector = new TestRunDetector(config(), plan, command);
        detector.accept(output);
        return detector.result(exit, false);
    }
}
