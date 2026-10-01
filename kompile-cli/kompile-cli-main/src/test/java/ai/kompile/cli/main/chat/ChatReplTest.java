package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.enforcer.EnforcerDecision;
import ai.kompile.cli.main.chat.enforcer.EnforcerEvaluator;
import ai.kompile.cli.main.chat.enforcer.EnforcerPolicy;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for a defect where the judge chat transcript claimed
 * {@code [judge state] disabled} — twice — while the judge itself was active and
 * producing verdicts. Both problems traced to {@link AgenticChatLoop}'s inline-enforcer
 * lane (tool-call policy readiness, a concept unrelated to the judge's own
 * judgeControl/judgeGloballyEnabled on/off state): {@code setInlineEnforcer} and
 * {@code setInlineEnforcerEnabled} each independently emitted a "[state] ..." event for
 * one logical transition, and {@link ChatRepl#appendSupervisorActivity} blindly relabeled
 * any "[state] ..." event as "[judge state] ...".
 */
class ChatReplTest {

    @TempDir
    Path workingDirectory;

    @Test
    void enforcerStateEventIsNotRelabeledAsJudgeState() {
        AuxiliaryChatRepl judge = AuxiliaryChatRepl.observer(AuxiliaryChatRepl.Kind.JUDGE, "ready");

        ChatRepl.appendSupervisorActivity(judge, "[enforcer state] disabled");

        assertTrue(judge.transcript().contains("[enforcer state] disabled"),
                "the enforcer-lane readiness event must reach the judge transcript verbatim");
        assertFalse(judge.transcript().contains("[judge state] disabled"),
                "an inline-enforcer readiness event must never be relabeled as the judge's "
                        + "own state — the judge can be fully active while only the "
                        + "(optional, often unconfigured) enforcer policy is off");
    }

    @Test
    void genuineJudgeStateEventIsStillLabeledAsJudgeState() {
        AuxiliaryChatRepl judge = AuxiliaryChatRepl.observer(AuxiliaryChatRepl.Kind.JUDGE, "ready");

        ChatRepl.appendSupervisorActivity(judge, "[state] globally disabled");

        assertTrue(judge.transcript().contains("[judge state] globally disabled"),
                "the judge's own on/off toggle (judgeControl/judgeGloballyEnabled) must "
                        + "still be labeled as judge state — this fix must not disturb it");
    }

    @Test
    void clearingAnActiveEnforcerEmitsExactlyOneLabeledDisabledEvent() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, new ToolRegistry(mapper), new PermissionService(),
                new AgentRegistry(), workingDirectory);
        List<String> events = new ArrayList<>();
        loop.setInlineEnforcerActivityListener(events::add);

        EnforcerEvaluator available = new EnforcerEvaluator() {
            @Override
            public EnforcerDecision evaluate(String userPrompt, String agentOutput,
                                              EnforcerPolicy policy, int attempt) {
                return EnforcerDecision.pass("ok");
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String describe() {
                return "fixture";
            }
        };
        loop.setInlineEnforcer(available, new EnforcerPolicy("rules", 2, false), 2);
        loop.setInlineEnforcerEnabled(true);
        assertEquals(List.of("[enforcer state] ready"), events,
                "enabling a real enforcer is one transition, not two");

        events.clear();
        // Mirrors ChatRepl.clearInlineJudgePolicy(false) exactly: the normal path when
        // a project has no enforcer policy configured, regardless of whether the judge
        // itself (judgeControl/judgeGloballyEnabled) stays fully active.
        loop.setInlineEnforcer(null, null, 0);
        loop.setInlineEnforcerEnabled(false);

        assertEquals(List.of("[enforcer state] disabled"), events,
                "clearing the enforcer is one ready-to-disabled transition; "
                        + "setInlineEnforcer and setInlineEnforcerEnabled must not each "
                        + "independently emit a duplicate '[enforcer state] disabled', and "
                        + "must never emit the unlabeled '[state] disabled' that "
                        + "ChatRepl.appendSupervisorActivity relabels as "
                        + "'[judge state] disabled'");
    }

    @Test
    void unconfiguredEnforcerNeverHadAReadyTransitionSoClearingItEmitsNothing() throws Exception {
        ObjectMapper mapper = JsonUtils.standardMapper();
        AgenticChatLoop loop = new AgenticChatLoop(
                null, mapper, new ToolRegistry(mapper), new PermissionService(),
                new AgentRegistry(), workingDirectory);
        List<String> events = new ArrayList<>();
        loop.setInlineEnforcerActivityListener(events::add);

        // A fresh session whose project has no enforcer policy at all: clearInlineJudgePolicy
        // runs at startup even though the judge (judgeControl/judgeGloballyEnabled) may be
        // fully enabled. There is no ready-to-disabled transition to report.
        loop.setInlineEnforcer(null, null, 0);
        loop.setInlineEnforcerEnabled(false);

        assertEquals(List.of(), events,
                "the enforcer was never enabled in this session, so there is nothing to "
                        + "announce — must not spam '[enforcer state] disabled' on every "
                        + "startup of a project that simply has no enforcer policy");
    }
}
