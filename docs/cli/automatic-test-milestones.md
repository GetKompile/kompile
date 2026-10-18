# Automatic CLI test milestones

The CLI harness observes authorized commands and records test outcomes without an
agent calling `test_milestone`. It is deterministic, enabled by default, and
independent of the performance judge, an LLM, or an app/server. Commands are never
rewritten, given extra reporter flags, or run a second time.

## Capture boundaries

- Foreground/detached `bash`, including MCP/headless: observes raw output before
  the canonical output limit.
- `process launch`: the owning real process records after output drain and exit.
  Polling, virtual processes and shared mirrors do not duplicate records.
- Native Claude, Codex, Pi, Gemini/Qwen and OpenCode commands: correlated command
  start/result events use the same service where their streams expose them. MCP
  execution tools capture themselves and are excluded from provider recapture.
- Imported transcripts, assistant prose, and opaque PTY-only streams are not test
  evidence. Capture finalizes at command exit, not per cycle of an ongoing watch.

## Configuration

Global defaults: `~/.kompile/test-milestones/config.json`.
Project overrides: `<project>/.kompile/test-milestones/config.json`.
Supplied project keys override global keys; arrays replace arrays. Absent settings
mean the built-in defaults. A global configuration does not claim ownership of
all directories under the user's home.

No manual initialization is required:

```json
{
  "action": "config",
  "auto_capture": {
    "enabled": true,
    "fallbackEnabled": true,
    "disabledFrameworks": ["pytest"],
    "includeCommands": [],
    "excludeCommands": ["expensive-integration"],
    "reportPaths": ["reports/junit.xml"],
    "customRules": [{
      "commandRegex": "validate-special",
      "summaryRegex": "^DONE (?<total>[0-9]+) (?<passed>[0-9]+) (?<failed>[0-9]+)$"
    }]
  }
}
```

The object is stored as `autoCapture`. `config` and `status` display effective
settings. `enabled=false` or `disabledFrameworks=["*"]` disables capture.
Exclusions win; empty includes allow all eligible commands. Malformed settings
fail closed with a warning, without preventing the command. `reportPaths` accepts
files/directories, not globs. Custom summaries require named `total`, `passed`,
`failed` groups; `skipped` and `errors` are optional. Counts must be consistent.
Regexes and their inputs are bounded; backreferences and repeated quantified or
alternating groups are rejected.

`fallbackEnabled=false` disables the generic count-summary regex, not known
framework output from a script. Default fallback examples:

```text
3 tests executed: 2 passed, 1 failed
3 tests run: 2 passed, 0 failed, 1 skipped
```

Runner/group ids include `jvm`, `pytest`, `unittest`, `sage`, `ctest`, `gtest`,
`catch2`, `platformio`, `unity`, `dotnet`, `swift`, `xctest`, `go`, `rust`, `dart`,
`javascript`, `node`, `jest`, `vitest`, `mocha`, `playwright`, `ruby`, `rspec`,
`minitest`, `perl`, `pgtap`, `bats`, `shellspec`, `shunit2`, `pester`, `testthat`,
`lua`, `vader`, `generic`, and `custom`. Disable command groups such as `jvm` or
`swift` to exclude the whole stack, including report imports; child ids disable
specific output detectors. JVM covers Maven/Gradle/JUnit/TestNG/Spock/ScalaTest.

These cover executable syntax-highlighter languages through their common runners,
including SQL/pgTAP, R/testthat, Lua/busted, Sage and Vim/Vader. Markup, configuration,
CSS, protobuf, Dockerfiles and Makefiles have no intrinsic test runner: observe
the invoked framework or configure a custom executable's output.

## Honest outcomes

Records remain in `.kompile/test-milestones/milestones/*.json`, using existing run
ids. Automatic records add `source=harness`, execution identity, framework,
start/end timing, module, exit code, evidence and available report/log references.
Revision metadata is observed at start; it does **not** prove the working tree
equals that commit. Commands and environment values are not copied into metadata.

Only `passed` and `failed` populate legacy `passing`. Outcomes `no_tests`,
`skipped`, `cached`, `execution_error`, `setup_error`, `unknown`, `interrupted`, and
`timed_out` remain visible but do not affect pass/fail trends, regressions or
last-known-good calculations. Unknown counts remain missing; expectation counts
(e.g. testthat) are separate observations, not fabricated test-case counts.
A passing summary with an unsuccessful process retains evidence but is not green.
A failed summary remains failed even when a pipe masks the runner's exit code.

Evidence: fresh JUnit/Surefire XML, TRX, NUnit/Pester XML, Jest-compatible or
Playwright JSON, Go/Dart events, framework summaries and complete TAP streams.
Known report directories and explicit reporter CLI paths are discovered. Reports
must change during the invocation, checked against a timestamp/content baseline.
Nested suite totals and verbose parent-runner summaries are not added twice.
Do not supply both an aggregate report and its source reports.

`mvn compile`, `mvn test-compile`, `mvn install -DskipTests`, `cargo test --no-run`,
`ctest -N`, Gradle test dry-runs, pytest collection, and test-listing modes do not
certify execution. Conversely, normal `mvn install` and `gradle build` may run
tests: actual evidence decides. Bare PASS/SUCCESS, BUILD SUCCESS and exit zero
are never sufficient. Complex shell commands with log readers/printers or
unresolvable directory changes fail closed; invoke the test command directly or
use a test script rather than replaying logs alongside it.

Overlapping invocations sharing a report directory in one CLI JVM ignore report
imports and use their own output. Independent processes should use separate report
destinations: freshness alone cannot prove cross-process ownership. XML DTDs and
external entities are disabled. Limits: 128 reports, 8 MiB/file, 32 MiB/report set,
bounded directory discovery and output parsing. Warnings go to stderr or the
interactive channel, never MCP protocol stdout.

Research: [Maven](https://maven.apache.org/surefire/maven-surefire-plugin/),
[Gradle](https://docs.gradle.org/current/userguide/java_testing.html),
[pytest](https://docs.pytest.org/en/stable/reference/exit-codes.html),
[Cargo](https://doc.rust-lang.org/cargo/commands/cargo-test.html),
[CTest](https://cmake.org/cmake/help/latest/manual/ctest.1.html),
[Go events](https://pkg.go.dev/cmd/test2json),
[Node reporters](https://nodejs.org/api/test.html),
[TAP](https://testanything.org/tap-specification.html),
[.NET](https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-test-vstest).
