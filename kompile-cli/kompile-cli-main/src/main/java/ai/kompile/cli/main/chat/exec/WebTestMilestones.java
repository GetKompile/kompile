package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.TestMilestonesCommand;

import java.nio.file.Path;

/** Browser adapter for the shared, read-only workspace milestone report. */
public final class WebTestMilestones {
    private WebTestMilestones() { }

    public static WebCommandResolver.Resolution resolve(String arguments, String sessionId, Path directory) {
        TestMilestonesCommand.Report report = TestMilestonesCommand.scopedReport(directory, arguments, sessionId);
        return new WebCommandResolver.Resolution(
                report.valid() ? WebCommandResolver.Status.COMPLETED : WebCommandResolver.Status.INVALID,
                "/test-milestones", report.text(), null);
    }
}
