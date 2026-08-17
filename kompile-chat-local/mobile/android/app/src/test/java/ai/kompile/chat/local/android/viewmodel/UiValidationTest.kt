package ai.kompile.chat.local.android.viewmodel

import ai.kompile.chat.local.android.ui.screens.copyableChatTranscript
import ai.kompile.chat.local.android.ui.screens.shouldShowHuggingFaceImportOnChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiValidationTest {

    @Test
    fun settingsTopAppBarAlwaysOffersLabeledChatNavigation() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/SettingsScreen.kt"
        ).readText()
        val contentStart = source.indexOf(") { padding ->")
        assertTrue("Missing Settings content", contentStart >= 0)
        val topBar = source.substring(0, contentStart)

        assertTrue(topBar.contains("TextButton("))
        assertTrue(topBar.contains("onClick = onOpenChat"))
        assertTrue(topBar.contains("testTag(\"open_chat_from_settings\")"))
        assertTrue(topBar.contains("Text(\"Chat\")"))
        assertTrue(topBar.contains("contentDescription = \"Save settings\""))
    }

    @Test
    fun modelStatusOccupiesTheTopContentSlotOnChatAndSettings() {
        val chat = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/ChatScreen.kt"
        ).readText()
        val settings = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/SettingsScreen.kt"
        ).readText()
        val chatContentStart = chat.indexOf(") { padding ->")
        val chatStatus = chat.indexOf("ModelStatusHeader(", chatContentStart)
        val chatThinking = chat.indexOf("// Thinking indicator.", chatContentStart)
        val settingsContentStart = settings.indexOf(") { padding ->")
        val settingsStatus = settings.indexOf("ModelStatusHeader(", settingsContentStart)
        val firstSettingsSection = settings.indexOf("// ── Canonical offline model", settingsContentStart)

        assertTrue(chatStatus in (chatContentStart + 1) until chatThinking)
        assertTrue(settingsStatus in (settingsContentStart + 1) until firstSettingsSection)
        assertTrue(chat.contains("testTag(\"top_model_status\")"))
        assertTrue(chat.contains("testTag(\"model_loading_indicator\")"))
        assertTrue(chat.contains("testTag(\"model_compiler_progress\")"))
        assertFalse(settings.substring(settingsContentStart, firstSettingsSection).contains("if (modelLoading)"))
    }

    @Test
    fun topModelStatusUsesOneSlotForLoadingReadyMissingAndFailure() {
        assertEquals(
            ModelStatusUi(
                "Preparing Hugging Face model…",
                "Importing and proving the local runtime",
                loading = true,
            ),
            modelStatusUi(ModelUiState.Missing, ImportOperationKind.HUGGING_FACE),
        )
        assertEquals(
            ModelStatusUi(
                "model.gguf",
                "Ready · google-edgetpu NNAPI islands + ARM64 replay"
            ),
            modelStatusUi(
                ModelUiState.Ready("/models/model.gguf", "LOCAL_TENSOR_G3_NNAPI"),
                ImportOperationKind.NONE,
            ),
        )
        assertEquals(
            ModelStatusUi(
                "Compiling or restoring Edge TPU plan…",
                "NNAPI driver cache candidate",
                loading = true,
            ),
            modelStatusUi(
                ModelUiState.Checking,
                ImportOperationKind.NONE,
                ModelLoadProgressUi(
                    "Compiling or restoring Edge TPU plan…",
                    "NNAPI driver cache candidate",
                ),
            ),
        )
        assertEquals(
            ModelStatusUi("No model loaded", "Open Settings to import or prepare one"),
            modelStatusUi(ModelUiState.Missing, ImportOperationKind.NONE),
        )
        assertEquals(
            ModelStatusUi("Model unavailable", "bad model", error = true),
            modelStatusUi(
                ModelUiState.Failed("/models/model.gguf", "bad model"),
                ImportOperationKind.NONE,
            ),
        )
    }

    @Test
    fun tensorG3GenerationBudgetIsAppliedAtOpenAndEveryChatRequest() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/viewmodel/ChatViewModel.kt"
        ).readText()

        assertEquals(3, Regex("effectiveMaxTokensForTarget\\(").findAll(source).count())
        assertTrue(source.contains("maxTokens = effectiveMaxTokensForTarget("))
        assertTrue(source.contains(".maxTokens(\n                effectiveMaxTokensForTarget("))
        assertTrue(source.contains("check(clearHuggingFaceImportCheckpoint())"))
        assertFalse(
            source.contains(
                "message = \"Finalizing the active model and removing obsolete cache files\",\n" +
                    "                        retryWillResumeOrReuse = true,\n" +
                    "                        storagePreflight = preflight.storage\n" +
                    "                    )\n" +
                    "                )\n" +
                    "                persistCurrentHuggingFaceObservation()"
            )
        )
    }

    @Test
    fun copiedTranscriptIncludesRawProtocolToolMetadataAndCurrentFailure() {
        val transcript = copyableChatTranscript(
            messages = listOf(
                UiMessage("user", "Search for café"),
                UiMessage(
                    "assistant",
                    "Found it",
                    toolRounds = listOf(
                        ToolRoundUi(
                            "graph_reasoning_query",
                            "{\"term\":\"café\"}",
                            "{\"hits\":1}"
                        )
                    ),
                    protocolExchanges = listOf(
                        ProtocolExchangeUi(
                            "{\"tool_choice\":\"auto\"}",
                            "<tool_call>café</tool_call>",
                            listOf("bad MCP envelope")
                        )
                    )
                )
            ),
            route = "LOCAL_TENSOR_G3_NNAPI",
            error = "Encoding failed",
            errorStackTrace = "stack-line"
        )

        assertTrue(transcript.contains("Search for café"))
        assertTrue(transcript.contains("graph_reasoning_query"))
        assertTrue(transcript.contains("\"tool_choice\":\"auto\""))
        assertTrue(transcript.contains("<tool_call>café</tool_call>"))
        assertTrue(transcript.contains("bad MCP envelope"))
        assertTrue(transcript.contains("Encoding failed"))
        assertTrue(transcript.contains("stack-line"))
    }

    @Test
    fun chatTopBarOffersRawTranscriptCopy() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/ChatScreen.kt"
        ).readText()

        assertTrue(source.contains("contentDescription = \"Copy transcript\""))
        assertTrue(source.contains("copyableChatTranscript(messages, route, error, errorStackTrace)"))
    }

    @Test
    fun tensorG3CarriesStructuredChatRequestsAcrossTheRuntimeBoundary() {
        val adapter = File(
            "src/main/java/ai/kompile/chat/local/android/model/AcceleratedChatModelAndroid.kt"
        ).readText()
        val process = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxRuntimeProcess.kt"
        ).readText()
        val owner = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxPlatformChatSession.kt"
        ).readText()

        assertTrue(adapter.contains("override fun generate(request: ChatRequest"))
        assertTrue(process.contains("putString(KEY_CHAT_REQUEST_JSON, request.toJson())"))
        assertTrue(process.contains("ChatResponse.fromStructuredJson(structuredJson)"))
        assertTrue(owner.contains("val canonicalRequestJson = JSONObject(requestJson).toString()"))
        assertTrue(owner.contains("canonicalRequestJson,\n                rawDecoded"))
        assertFalse(owner.contains("put(\"tool_choice\", \"none\")"))
    }

    @Test
    fun activeModelChatNavigationDoesNotRenderTheCompletedImportPipeline() {
        val active = HuggingFaceImportUiState.Active(
            artifactName = "model.gguf",
            route = "LOCAL_TENSOR_G3_NNAPI",
            storageLocation = "/models/model.gguf"
        )
        val ready = ModelUiState.Ready("/models/model.gguf", "LOCAL_TENSOR_G3_NNAPI")
        val working = HuggingFaceImportUiState.Working(
            HuggingFaceImportProgress(
                step = HuggingFaceImportStep.SDX_LOAD,
                message = "Loading model",
                attempt = 1,
                maxAttempts = 4,
                resumedBytes = 0L,
                completedBytes = 0L,
                totalBytes = null,
                smoothedBytesPerSecond = null,
                etaSeconds = null,
                retryWillResumeOrReuse = true
            )
        )

        assertFalse(shouldShowHuggingFaceImportOnChat(active, ready))
        assertFalse(shouldShowHuggingFaceImportOnChat(working, ready))
        assertTrue(shouldShowHuggingFaceImportOnChat(working, ModelUiState.Checking))
    }

    @Test
    fun chatRuntimeOwnerIsActivityScopedAndInjectedIntoEveryDestination() {
        val mainActivity = File(
            "src/main/java/ai/kompile/chat/local/android/MainActivity.kt"
        ).readText()
        val navigation = File(
            "src/main/java/ai/kompile/chat/local/android/ui/navigation/AppNavigation.kt"
        ).readText()
        val chatScreen = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/ChatScreen.kt"
        ).readText()
        val settingsScreen = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/SettingsScreen.kt"
        ).readText()

        assertTrue(mainActivity.contains("chatViewModel: ChatViewModel by viewModels()"))
        assertTrue(mainActivity.contains("AppNavigation(vm = chatViewModel)"))
        assertTrue(navigation.contains("fun AppNavigation(vm: ChatViewModel)"))
        assertTrue(navigation.contains("startDestination = ROUTE_CHAT"))
        val launchEffectStart = navigation.indexOf("LaunchedEffect(vm, navController)")
        val restoredLaunchChat = navigation.indexOf("openChat()", launchEffectStart)
        val navigationCollector = navigation.indexOf("vm.navigationEvents.collect", launchEffectStart)
        assertTrue(restoredLaunchChat in (launchEffectStart + 1) until navigationCollector)
        assertTrue(navigation.contains("ChatScreen("))
        assertTrue(navigation.contains("SettingsScreen("))
        assertTrue(navigation.contains("popBackStack(ROUTE_CHAT, inclusive = false)"))
        assertTrue(navigation.contains("navController.navigate(ROUTE_CHAT) { launchSingleTop = true }"))
        assertFalse(navigation.contains("popUpTo(ROUTE_CHAT)"))
        assertEquals(2, Regex("vm = vm").findAll(navigation).count())
        assertFalse(navigation.contains("ChatViewModel = viewModel()"))
        assertFalse(chatScreen.contains("ChatViewModel = viewModel()"))
        assertFalse(settingsScreen.contains("ChatViewModel = viewModel()"))
    }

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
    fun activeModelRequiresExplicitUnloadBeforeArtifactImport() {
        val reason = importBlockedReason(
            importBusy = false,
            generating = false,
            activeModelLoaded = true
        )

        assertNotNull(reason)
        assertTrue(reason!!.contains("Unload the active model"))
    }

    @Test
    fun onlyUnloadBypassesTheActiveModelOwnershipGate() {
        assertFalse(ImportOperationKind.NONE.requiresUnloadedModel)
        assertFalse(ImportOperationKind.MODEL_UNLOAD.requiresUnloadedModel)
        ImportOperationKind.entries
            .filterNot { it == ImportOperationKind.NONE || it == ImportOperationKind.MODEL_UNLOAD }
            .forEach { operation -> assertTrue(operation.requiresUnloadedModel) }
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
