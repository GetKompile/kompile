package ai.kompile.chat.local.android.viewmodel

import ai.kompile.chat.local.android.ui.screens.copyableChatDebugTranscript
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
            errorStackTrace = "stack-line",
            streaming = StreamingUiState(
                phase = "decoding",
                content = "live café",
                protocolExchanges = listOf(
                    ProtocolExchangeUi(
                        "{\"stream\":true}",
                        "live raw café",
                        listOf("live protocol warning")
                    )
                )
            )
        )

        assertTrue(transcript.contains("Search for café"))
        assertTrue(transcript.contains("graph_reasoning_query"))
        assertTrue(transcript.contains("\"tool_choice\":\"auto\""))
        assertTrue(transcript.contains("<tool_call>café</tool_call>"))
        assertTrue(transcript.contains("bad MCP envelope"))
        assertTrue(transcript.contains("Encoding failed"))
        assertTrue(transcript.contains("stack-line"))
        assertTrue(transcript.contains("streaming.protocol[0].request_json"))
        assertTrue(transcript.contains("live raw café"))
        assertTrue(transcript.contains("live protocol warning"))
    }

    @Test
    fun copiedDebugTranscriptIncludesLifecycleAndNativeEvidence() {
        val debug = copyableChatDebugTranscript(
            messages = listOf(UiMessage("user", "Hello")),
            route = "LOCAL_TENSOR_G3_NNAPI",
            modelState = ModelUiState.Ready("/models/model.sdz", "LOCAL_TENSOR_G3_NNAPI"),
            graphState = GraphUiState.Ready("/graphs/default.kgraph"),
            importOperation = ImportOperationKind.NONE,
            modelLoadProgress = ModelLoadProgressUi("Ready", "Restored device cache"),
            diagnostics = emptyList(),
            error = "native failure",
            errorStackTrace = "stack line",
            streaming = null,
            smokeDecodeTrace = "event=native_chunk chunk_utf8_sha256=abc" + "x".repeat(300_000),
            dspDiagnosticsTrace = "dsp_event=execute_segment",
            capturedAtEpochMillis = 1234L
        )

        assertTrue(debug.startsWith("Kompile Chat debug transcript"))
        assertTrue(debug.contains("captured_at_epoch_ms=1234"))
        assertTrue(debug.contains("model_state=Ready"))
        assertTrue(debug.contains("graph_state=Ready"))
        assertTrue(debug.contains("model_load_progress.detail=Restored device cache"))
        assertTrue(debug.contains("execution_log_path=files/diagnostics/execution.log"))
        assertTrue(debug.contains("persisted DSP execution trace"))
        assertTrue(debug.contains("dsp_event=execute_segment"))
        assertTrue(debug.contains("native_chunk chunk_utf8_sha256=abc"))
        assertTrue(debug.contains("debug log truncated for clipboard safety"))
        assertTrue(debug.toByteArray(Charsets.UTF_8).size <= 200_000)
        assertTrue(debug.contains("native failure"))
    }

    @Test
    fun chatTopBarOffersRawTranscriptCopy() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/ChatScreen.kt"
        ).readText()

        assertTrue(source.contains("contentDescription = \"Copy transcript\""))
        assertTrue(source.contains("contentDescription = \"Copy debug log\""))
        assertTrue(source.contains("testTag(\"copy_debug_log_button\")"))
        assertTrue(source.contains("contentDescription = \"Share debug log\""))
        assertTrue(source.contains("testTag(\"share_debug_log_button\")"))
        assertTrue(source.contains("FileProvider.getUriForFile("))
        assertTrue(source.contains("Intent.ACTION_SEND"))
        assertTrue(source.contains("Intent.EXTRA_STREAM"))
        assertTrue(source.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(source.contains("fullChatDebugTranscript("))
        assertTrue(source.contains("ExecutionDiagnosticsTraceLog(traceContext).writeSnapshot(fullDebugText)"))
        assertTrue(source.contains("copyableChatTranscript(messages, route, error, errorStackTrace, streaming)"))
    }

    @Test
    fun settingsSharesDspDiagnosticsAsAFileWithoutClipboardMaterialization() {
        val settings = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/SettingsScreen.kt"
        ).readText()
        val chat = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/ChatScreen.kt"
        ).readText()

        assertTrue(settings.contains("DspDiagnosticsTraceLog(context).writeShareSnapshot {"))
        assertTrue(settings.contains("withContext(Dispatchers.IO)"))
        assertTrue(settings.contains("exportContext.ensureActive()"))
        assertTrue(settings.contains("FileProvider.getUriForFile("))
        assertTrue(settings.contains("Intent.ACTION_SEND"))
        assertTrue(settings.contains("Intent.EXTRA_STREAM"))
        assertTrue(settings.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(settings.contains("ClipData.newUri("))
        assertTrue(settings.contains("catch (cancelled: CancellationException)"))
        assertTrue(settings.contains("testTag(\"share_dsp_diagnostics_trace\")"))
        assertTrue(settings.contains("DspDiagnosticsTraceLog(context).clearAll()"))
        assertTrue(settings.contains("testTag(\"clear_dsp_diagnostics_trace\")"))
        assertTrue(settings.contains("modelSmokeState !is ModelSmokeUiState.Running"))
        assertFalse(
            settings.contains(
                "AnnotatedString(DspDiagnosticsTraceLog(context).readContents())"
            )
        )
        assertTrue(
            chat.contains("DspDiagnosticsTraceLog(traceContext).contentsDescription()")
        )
        assertFalse(chat.contains("DspDiagnosticsTraceLog(traceContext).readContents()"))
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

    @Test
    fun settingsExposeOptimizedModelsAndTheirStorageSeparatelyFromRawSources() {
        val settings = File(
            "src/main/java/ai/kompile/chat/local/android/ui/screens/SettingsScreen.kt"
        ).readText()

        assertTrue(settings.contains("Models stored on this phone"))
        assertTrue(settings.contains("optimizedModelStorage.optimizedCacheBytes"))
        assertTrue(settings.contains("optimizedModelStorage.retainedModelBytes"))
        assertTrue(settings.contains("optimizedModelStorage.deviceCompilationBytes"))
        assertTrue(settings.contains("Reuse optimized model"))
        assertTrue(settings.indexOf("Models stored on this phone") < settings.indexOf("Optimize local model"))
    }
}
