package ai.kompile.chat.local.android.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiValidationTest {

    @Test
    fun everyExecutableRouteHasAnActiveBadgeIncludingRawGguf() {
        assertEquals(RouteBadgeUi("VULKAN", true), routeBadgeUi("LOCAL_VULKAN"))
        assertEquals(RouteBadgeUi("HEXAGON", true), routeBadgeUi("LOCAL_HEXAGON"))
        assertEquals(RouteBadgeUi("TENSOR G3", true), routeBadgeUi("LOCAL_TENSOR_G3_NNAPI"))
        assertEquals(RouteBadgeUi("TENSOR G5", true), routeBadgeUi("LOCAL_TENSOR_G5"))
        assertEquals(RouteBadgeUi("SDX GGUF", true), routeBadgeUi("SDX_GGUF_AOT"))
        assertEquals(RouteBadgeUi("NO MODEL", false), routeBadgeUi("NONE"))
    }

    @Test
    fun rawGgufDoesNotOfferAButtonForUnsupportedCancellation() {
        assertFalse(routeCanCancelGeneration("SDX_GGUF_AOT"))
        assertFalse(routeCanCancelGeneration("NONE"))
        assertTrue(routeCanCancelGeneration("LOCAL_VULKAN"))
        assertTrue(routeCanCancelGeneration("LOCAL_HEXAGON"))
        assertTrue(routeCanCancelGeneration("LOCAL_TENSOR_G3_NNAPI"))
        assertTrue(routeCanCancelGeneration("LOCAL_TENSOR_G5"))
    }

    // ── engineNotice: the input bar is never silently disabled ────────────────

    @Test
    fun readyEngineNeedsNoNotice() {
        assertNull(
            engineNotice(
                ModelUiState.Ready("/models/m.sdz", "LOCAL_VULKAN"),
                GraphUiState.Ready("/graphs/g.kgraph")
            )
        )
    }

    @Test
    fun graphFailureSurfacesItsMessageAsActionable() {
        val notice = engineNotice(
            ModelUiState.Ready("/models/m.sdz", "LOCAL_VULKAN"),
            GraphUiState.Failed("/graphs/g.kgraph", "Native graph runtime unavailable")
        )

        assertNotNull(notice)
        assertTrue(notice!!.actionable)
        assertEquals("Native graph runtime unavailable", notice.message)
    }

    @Test
    fun graphStartupIsTransientNotActionable() {
        val notice = engineNotice(
            ModelUiState.Ready("/models/m.sdz", "LOCAL_VULKAN"),
            GraphUiState.Checking
        )

        assertNotNull(notice)
        assertFalse(notice!!.actionable)
    }

    @Test
    fun missingModelAsksForAnImport() {
        val notice = engineNotice(ModelUiState.Missing, GraphUiState.WaitingForModel)

        assertNotNull(notice)
        assertTrue(notice!!.actionable)
        assertTrue(notice.message.contains("Import"))
    }

    @Test
    fun modelFailureSurfacesItsMessageAsActionable() {
        val notice = engineNotice(
            ModelUiState.Failed("/models/m.sdz", "No embedded cache for this target"),
            GraphUiState.WaitingForModel
        )

        assertNotNull(notice)
        assertTrue(notice!!.actionable)
        assertEquals("No embedded cache for this target", notice.message)
    }

    @Test
    fun modelStartupIsTransientNotActionable() {
        val notice = engineNotice(ModelUiState.Checking, GraphUiState.WaitingForModel)

        assertNotNull(notice)
        assertFalse(notice!!.actionable)
    }

    // ── stagingUrlProblem: optional prepared-artifact URL validation ──────────

    @Test
    fun blankStagingUrlIsAcceptableAsUnset() {
        assertNull(stagingUrlProblem(""))
        assertNull(stagingUrlProblem("   "))
    }

    @Test
    fun wellFormedHttpAndHttpsPass() {
        assertNull(stagingUrlProblem("http://workstation:8090"))
        assertNull(stagingUrlProblem("https://staging.example.com/console"))
        assertNull(stagingUrlProblem("  http://10.0.0.5:8090  "))
    }

    @Test
    fun missingSchemeIsRejected() {
        assertNotNull(stagingUrlProblem("workstation:8090"))
        assertNotNull(stagingUrlProblem("://host"))
    }

    @Test
    fun nonHttpSchemeIsRejectedByName() {
        val problem = stagingUrlProblem("ftp://host:21")

        assertNotNull(problem)
        assertTrue(problem!!.contains("ftp"))
    }

    @Test
    fun embeddedWhitespaceIsRejected() {
        assertNotNull(stagingUrlProblem("http://my host:8090"))
    }

    @Test
    fun schemeWithoutHostIsRejected() {
        assertNotNull(stagingUrlProblem("http://"))
        assertNotNull(stagingUrlProblem("https:///path"))
    }

    @Test
    fun settingsRejectsAnythingThePreparedArtifactHandoffWouldNotStore() {
        listOf(
            "https://user:password@staging.example",
            "https://staging.example/import?theme=dark",
            "https://staging.example/import?access_token=secret",
            "https://staging.example/import#fragment",
            "https://staging.example/staging%2Fdownload"
        ).forEach { value ->
            assertNotNull("Expected rejection for $value", stagingUrlProblem(value))
        }
    }

    @Test
    fun launchAdditionallyRequiresAValue() {
        assertNotNull(stagingUrlLaunchProblem(""))
        assertTrue(stagingUrlLaunchProblem("")!!.contains("Settings"))
        assertTrue(stagingUrlLaunchProblem("")!!.contains("prepared-artifact"))
        assertNotNull(stagingUrlLaunchProblem("ftp://host"))
        assertNull(stagingUrlLaunchProblem("http://workstation:8090"))
    }

    // ── targetProfileProblem: cross-flavor selections fail closed ─────────────

    @Test
    fun standaloneSelectionWithoutProjectTargetIsAllowed() {
        assertNull(targetProfileProblem("", "android-arm64-vulkan"))
    }

    @Test
    fun matchingTargetIsAllowed() {
        assertNull(targetProfileProblem("android-arm64-vulkan", "android-arm64-vulkan"))
    }

    @Test
    fun mismatchedTargetNamesBothProfiles() {
        val problem = targetProfileProblem(
            "android-arm64-hexagon-htp",
            "android-arm64-vulkan"
        )

        assertNotNull(problem)
        assertTrue(problem!!.contains("android-arm64-hexagon-htp"))
        assertTrue(problem.contains("android-arm64-vulkan"))
        assertTrue(problem.contains(".kproject"))
    }

    // ── importBlockedReason: single-flight import policy ──────────────────────

    @Test
    fun idleEngineAllowsImports() {
        assertNull(importBlockedReason(importBusy = false, generating = false))
    }

    @Test
    fun runningImportBlocksASecondImport() {
        val reason = importBlockedReason(importBusy = true, generating = false)

        assertNotNull(reason)
        assertTrue(reason!!.contains("already running"))
    }

    @Test
    fun activeGenerationBlocksImports() {
        val reason = importBlockedReason(importBusy = false, generating = true)

        assertNotNull(reason)
        assertTrue(reason!!.contains("response"))
    }

    @Test
    fun busyImportTakesPrecedenceOverGeneration() {
        val reason = importBlockedReason(importBusy = true, generating = true)

        assertTrue(reason!!.contains("already running"))
    }

    @Test
    fun blockedProjectImportKeepsActionablePhaseLabel() {
        val failure = ProjectImportOutcome.Failed(
            ProjectImportPhase.BLOCKED,
            "Another import is already running. Wait for it to finish."
        )

        assertEquals(
            "Import blocked: Another import is already running. Wait for it to finish.",
            failure.displayMessage
        )
    }
}
