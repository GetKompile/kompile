package ai.kompile.chat.local.android.model

import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.isMainApplicationProcess
import ai.kompile.chat.local.android.diagnostics.NativeOperationDiagnosticPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SdxModelPreparationProcessTest {

    @Test
    fun importerUsesASeparateNamedAndroidProcess() {
        val packageName = "ai.kompile.chat.local.android.tensorg3.debug"

        assertEquals(
            "$packageName:sdx_model_import",
            sdxImporterProcessName(packageName)
        )
        assertTrue(isMainApplicationProcess(packageName, packageName))
        assertFalse(
            isMainApplicationProcess(
                packageName,
                sdxImporterProcessName(packageName)
            )
        )
    }

    @Test
    fun acceleratorRuntimeUsesASeparateSupervisedAndroidProcess() {
        val packageName = "ai.kompile.chat.local.android.tensorg3.debug"
        val runtimeProcess = sdxRuntimeProcessName(packageName)

        assertEquals("$packageName:sdx_model_runtime", runtimeProcess)
        assertFalse(isMainApplicationProcess(packageName, runtimeProcess))

        val source = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxRuntimeProcess.kt"
        ).readText()
        assertTrue(source.contains("class SdxRuntimeService : Service()"))
        assertTrue(source.contains("context.startService(runtimeIntent)"))
        assertTrue(source.contains("context.bindService("))
        assertTrue(source.indexOf("context.startService(runtimeIntent)") < source.indexOf("context.bindService("))
        assertTrue(source.contains("override fun onStartCommand"))
        assertTrue(source.contains("START_NOT_STICKY"))
        assertTrue(source.contains("service.linkToDeath(this, 0)"))
        assertTrue(source.contains("NativeOperationCrashRecovery.recoverAttemptAndPersist("))
        assertTrue(source.contains("awaitExitEvidence("))
        assertTrue(source.contains("SdxPlatformRuntimeOwner.open("))
        assertTrue(source.contains("ownerExecutor = Executors.newSingleThreadExecutor"))
        assertTrue(source.contains("EVENT_CHUNK"))
        assertTrue(source.contains("\"ipc_remote_failure\""))
        assertTrue(source.contains("\"runtime_open_failed\""))
        assertTrue(source.contains("\"remote_failure_class\""))
        assertFalse(source.contains("putSerializable"))

        val runtimeOwner = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxPlatformChatSession.kt"
        ).readText()
        assertTrue(runtimeOwner.contains("\"runtime_load_checkpoint\""))
        assertTrue(runtimeOwner.contains("\"sdxLlmResolveModelBundle\""))
        assertTrue(runtimeOwner.contains("\"sdxLlmLoadCompiledModel\""))

        val manifest = File("src/tensorG3/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".model.SdxRuntimeService\""))
        assertTrue(manifest.contains("android:process=\":sdx_model_runtime\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("android:stopWithTask=\"false\""))
    }

    @Test
    fun runtimeRecyclesOrphanedOrRetiringWorkerBeforeOpeningAnotherSession() {
        assertFalse(
            sdxRuntimeWorkerMustRestartBeforeOpen(
                SdxRuntimeWorkerState(
                    pid = 101,
                    startTimeTicks = 1_001L,
                    ownsModelSession = false,
                    retiring = false
                )
            )
        )
        assertTrue(
            sdxRuntimeWorkerMustRestartBeforeOpen(
                SdxRuntimeWorkerState(
                    pid = 102,
                    startTimeTicks = 1_002L,
                    ownsModelSession = true,
                    retiring = false
                )
            )
        )
        assertTrue(
            "A cleanly closed worker remains unsafe once its queued retirement starts",
            sdxRuntimeWorkerMustRestartBeforeOpen(
                SdxRuntimeWorkerState(
                    pid = 103,
                    startTimeTicks = 1_003L,
                    ownsModelSession = false,
                    retiring = true
                )
            )
        )

        val source = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxRuntimeProcess.kt"
        ).readText()
        assertTrue(source.contains("SdxRuntimeConnection.bindForNewSession(applicationContext)"))
        assertTrue(source.contains("putBoolean(KEY_HAS_ACTIVE_SESSION, activeSession != null)"))
        assertTrue(source.contains("putBoolean(KEY_WORKER_RETIRING, sdxRuntimeWorkerRetiring.get())"))
        assertTrue(source.contains("check(!sdxRuntimeWorkerRetiring.get())"))
        assertTrue(source.contains("connection.retireAndAwait(state.pid, state.startTimeTicks)"))
        assertTrue(source.contains("if (sdxRuntimeProcessMatches(pid, startTimeTicks)) Process.killProcess(pid)"))
        assertTrue(source.contains("check(state.pid != Process.myPid())"))
        val unbindStart = source.indexOf("override fun onUnbind(intent: Intent?): Boolean")
        val unbindEnd = source.indexOf("override fun onDestroy()", unbindStart)
        assertTrue("Missing isolated runtime unbind lifecycle", unbindStart >= 0)
        assertTrue("Missing isolated runtime destroy lifecycle", unbindEnd > unbindStart)
        val unbind = source.substring(unbindStart, unbindEnd)
        val retire = unbind.indexOf("sdxRuntimeWorkerRetiring.set(true)")
        val kill = unbind.indexOf("post { Process.killProcess(pid) }")
        assertTrue("Worker retirement must be visible before queued process death", retire >= 0)
        assertTrue("Queued process death must follow the retirement marker", kill > retire)
        assertFalse(unbind.contains("postDelayed"))
    }

    @Test
    fun runtimeCloseWaitsUntilTheRetiredWorkerPidIsGone() {
        var checks = 0
        var waits = 0

        val exited = awaitSdxRuntimeWorkerExit(
            pid = 1234,
            startTimeTicks = 99L,
            maxChecks = 4,
            processMatches = { _, _ ->
                checks++
                checks < 3
            },
            waitBetweenChecks = { waits++ },
        )

        assertTrue(exited)
        assertEquals(3, checks)
        assertEquals(2, waits)

        val source = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxRuntimeProcess.kt"
        ).readText()
        assertTrue(source.contains("connection.retireAndAwait(processId, processStartTimeTicks)"))
        assertTrue(source.contains("sdxRuntimeWorkerRetiring.set(true)"))
        assertTrue(source.contains("Process.killProcess(pid)"))
    }

    @Test
    fun runtimeCloseRejectsAWorkerThatNeverExits() {
        var waits = 0

        val exited = awaitSdxRuntimeWorkerExit(
            pid = 4321,
            startTimeTicks = 100L,
            maxChecks = 3,
            processMatches = { _, _ -> true },
            waitBetweenChecks = { waits++ },
        )

        assertFalse(exited)
        assertEquals(2, waits)
    }

    @Test
    fun runtimePidIdentityIncludesProcStartTimeToRejectPidReuse() {
        val stat = "1234 (sdx worker thread) " +
            (3..22).joinToString(" ") { field -> if (field == 22) "987654" else field.toString() }

        assertEquals(
            987654L,
            sdxRuntimeProcessStartTimeTicks(1234) { stat },
        )
        assertEquals(null, sdxRuntimeProcessStartTimeTicks(1234) { "invalid" })
    }

    @Test
    fun importerPidIdentityIncludesProcStartTimeBeforeTimeoutKill() {
        val stat = "5678 (sdx importer thread) " +
            (3..22).joinToString(" ") { field -> if (field == 22) "123456" else field.toString() }

        assertEquals(
            123456L,
            sdxImporterProcessStartTimeTicks(5678) { stat },
        )
        assertEquals(null, sdxImporterProcessStartTimeTicks(5678) { "invalid" })

        val source = File(
            "src/main/java/ai/kompile/chat/local/android/model/SdxModelPreparationProcess.kt"
        ).readText()
        assertTrue(source.contains("sdxImporterProcessMatches(watchedPid, remoteStartTimeTicks)"))
        assertTrue(source.contains("KEY_PROCESS_START_TIME_TICKS"))
    }

    @Test
    fun sourceBuildRefreshesGraphAndChatCoreBeforeSkippingPackagerMaven() {
        val wrapper = File("../build-tensor-g3-offline-apk.sh").readText()
        val graphStart = wrapper.indexOf("ensure_graph_aot() {")
        val graphEnd = wrapper.indexOf("\nWORK_ROOT=", graphStart)
        assertTrue("Missing graph producer lifecycle", graphStart >= 0 && graphEnd > graphStart)
        val graph = wrapper.substring(graphStart, graphEnd)
        val resumeGuard = graph.indexOf("if (( RESUME_PUBLISH == 1 )) &&")
        val reuseReturn = graph.indexOf("return")
        assertTrue("Only explicit resume may skip the graph source build", resumeGuard >= 0)
        assertTrue(reuseReturn > resumeGuard)
        assertTrue(graph.contains("-pl :kompile-graph-reasoning-local,:kompile-chat-local-core"))
        assertTrue(graph.contains("-am install"))
        assertTrue(graph.contains("-Dnd4j.backend=nd4j-native"))
        assertFalse(graph.contains("clean install"))
        val refreshCall = wrapper.indexOf("\nensure_graph_aot\n")
        val packaging = wrapper.indexOf("exec \"\$APK_BUILDER\"")
        assertTrue("Kompile inputs must be refreshed before packaging", refreshCall >= 0)
        assertTrue(packaging > refreshCall)
    }

    @Test
    fun resumePublishPreservesAndVerifiesHistoricalProducerClosure() {
        val wrapper = File("../build-tensor-g3-offline-apk.sh").readText()
        val cleanupStart = wrapper.indexOf("printf 'Running mandatory pre-build cleanup.")
        val cleanup = wrapper.substring(
            cleanupStart,
            wrapper.indexOf("ensure_graph_aot", cleanupStart)
        )
        val resumeGuard = cleanup.indexOf("if (( RESUME_PUBLISH == 0 ))")
        val producerPrune = cleanup.indexOf("\"\$CLEANUP_BUILDER\"")
        val resumeBranch = cleanup.indexOf("else", producerPrune)
        val apkCleanup = cleanup.indexOf("\"\$APK_BUILDER\" --cleanup-only")

        assertTrue("Producer pruning is not guarded from resume-publish", resumeGuard >= 0)
        assertTrue(producerPrune > resumeGuard)
        assertTrue(resumeBranch > producerPrune)
        assertTrue("Disposable APK cleanup must still run in resume mode", apkCleanup > resumeBranch)
        assertTrue(cleanup.contains("preserving immutable producer generations"))

        val packager = File("../tools/build-offline-accelerators.sh").readText()
        assertTrue(packager.contains("REUSE_RECEIPTED_PRODUCERS == 1"))
        assertTrue(packager.contains("Historical SDX AOT base generation was pruned"))
        assertTrue(packager.contains(
            "base_sdk_actual_sha=\"\${SDX_AOT_RECEIPT_VALUES[base_sdk_sha256]}\""
        ))
        assertTrue(packager.contains("SDX_AOT_RECEIPT_VALUES[base_sdk_native_sha256]"))
        assertTrue(packager.contains("sha256_file \"\$base_native_bytes\""))
    }

    @Test
    fun runtimeServiceSnapshotsRecyclableMessageBeforeOwnerThreadDispatch() {
        val source = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxRuntimeProcess.kt"
        ).readText()
        val handlerStart = source.indexOf("private fun handleRequest(request: AndroidMessage)")
        val handlerEnd = source.indexOf("private fun executeOwnedRequest(", handlerStart)
        assertTrue("Missing runtime request handler", handlerStart >= 0)
        assertTrue("Missing detached owner-thread dispatch", handlerEnd > handlerStart)
        val handler = source.substring(handlerStart, handlerEnd)

        assertTrue(handler.contains("val method = request.what"))
        assertTrue(handler.contains("val sendingUid = request.sendingUid"))
        assertTrue(handler.contains("val replyTo = request.replyTo ?: return"))
        assertTrue(handler.contains("val wireData = Bundle(request.data)"))
        assertEquals(1, Regex("""request\.what""").findAll(handler).count())
        assertEquals(1, Regex("""request\.sendingUid""").findAll(handler).count())
        assertEquals(1, Regex("""request\.replyTo""").findAll(handler).count())
        assertEquals(1, Regex("""request\.data""").findAll(handler).count())
        val dispatch = handler.substring(handler.indexOf("when (method)"))
        assertFalse(Regex("""\brequest\b""").containsMatchIn(dispatch))
        assertFalse(handler.contains("error(\"unreachable\")"))
        assertTrue(source.contains("ownerExecutor.execute {\n            val response = try {\n                operation()"))
    }

    @Test
    fun importerUsesTheSameBoundImportantServiceLifecycleAsTheRuntime() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/model/SdxModelPreparationProcess.kt"
        ).readText()

        assertTrue(source.contains("class SdxModelPreparationService : Service()"))
        assertTrue(source.contains("appContext.bindService("))
        assertTrue(source.contains("Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT"))
        assertTrue(source.contains("service.linkToDeath(this, 0)"))
        assertTrue(source.contains("ownerExecutor = Executors.newSingleThreadExecutor"))
        assertTrue(source.contains("val method = request.what"))
        assertTrue(source.contains("val sendingUid = request.sendingUid"))
        assertTrue(source.contains("val replyTo = request.replyTo ?: return"))
        assertTrue(source.contains("val wireData = Bundle(request.data)"))
        assertTrue(source.contains("EVENT_PROGRESS"))
        assertTrue(source.contains("NativeOperationJournal(applicationContext).begin"))
        assertTrue(source.contains("KEY_OPERATION_ATTEMPT_ID"))
        assertTrue(source.contains("connection.prepare(request, pid)"))
        assertTrue(source.contains("requireFrameworkOnlySdxWireBundle("))
        assertTrue(
            source.contains("NativeOperationJournal(context).resumeOrNull(operation.snapshot().attemptId)")
        )
        assertTrue(source.contains("KEY_OPERATION_TERMINAL"))
        assertTrue(source.contains("failureBundleAfterPersistingTerminalState(operation, failure)"))
        assertTrue(source.contains("catch (failure: OutOfMemoryError)"))
        assertFalse(source.contains("ContentProvider"))
        assertFalse(source.contains("acquireUnstableContentProviderClient"))
        assertFalse(source.contains("putParcelable(KEY_PROGRESS_MESSENGER"))
        assertFalse(source.contains("ResultReceiver"))
        assertFalse(source.contains("putSerializable"))
        assertFalse(source.contains("failureBundlePreservingCheckpoint"))
        assertFalse(
            source.contains("operation.checkpoint(NativeOperationCheckpoint.STOP_IMPORTER_PROCESS)")
        )

        val service = source.substring(source.indexOf("class SdxModelPreparationService"))
        assertTrue(
            service.contains(
                "val response = successBundle(prepared)\n" +
                    "            operation.complete()\n" +
                    "            response"
            )
        )
        assertTrue(service.contains("operation.failAndPersist(failure)"))
        assertTrue(service.contains("failureBundle(failure, operationTerminal = true)"))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".model.SdxModelPreparationService\""))
        assertTrue(manifest.contains("android:process=\":sdx_model_import\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertFalse(manifest.contains("SdxModelPreparationProvider"))
    }

    @Test
    fun importerWireGuardAllowsOnlyFrameworkScalars() {
        assertTrue(isFrameworkOnlySdxWireValueClass(String::class.java))
        assertTrue(isFrameworkOnlySdxWireValueClass(Boolean::class.javaObjectType))
        assertTrue(isFrameworkOnlySdxWireValueClass(Long::class.javaObjectType))
        assertTrue(isFrameworkOnlySdxWireValueClass(Int::class.javaObjectType))

        assertFalse(isFrameworkOnlySdxWireValueClass(android.os.Messenger::class.java))
        assertFalse(isFrameworkOnlySdxWireValueClass(PreparedModelPayload::class.java))
        assertFalse(isFrameworkOnlySdxWireValueClass(Any::class.java))
    }

    @Test
    fun smokeDecodeAndNativeTombstonesHaveVisibleFatalFailureBoundaries() {
        val viewModel = File(
            "src/main/java/ai/kompile/chat/local/android/viewmodel/ChatViewModel.kt"
        ).readText()
        val smokeStart = viewModel.indexOf("fun runModelSmokeTest()")
        val smokeEnd = viewModel.indexOf("fun onSettingsChanged()", smokeStart)
        assertTrue(smokeStart >= 0)
        assertTrue(smokeEnd > smokeStart)
        val smoke = viewModel.substring(smokeStart, smokeEnd)
        assertTrue(smoke.contains("catch (failure: Throwable)"))
        assertTrue(smoke.contains("catch (persistenceFailure: Throwable)"))
        assertTrue(
            smoke.indexOf("_modelSmokeState.value = failed") <
                smoke.indexOf("recordImportDiagnostic(")
        )
        assertTrue(viewModel.contains("NativeOperationKind.SDX_MODEL_LOAD"))
        assertTrue(viewModel.contains("NativeOperationKind.SDX_MODEL_EXECUTION"))

        val application = File(
            "src/main/java/ai/kompile/chat/local/android/KompileChatApplication.kt"
        ).readText()
        assertTrue(application.contains("catch (failure: Throwable)"))
        assertTrue(application.contains("startupDiagnosticFallback = ImportDiagnosticPolicy.create("))

        val recovery = File(
            "src/main/java/ai/kompile/chat/local/android/diagnostics/NativeRuntimeLoadDiagnostics.kt"
        ).readText()
        assertTrue(recovery.contains("AndroidNativeTombstone.render(capturedBytes)"))
        assertTrue(recovery.contains("no_backup/\$NATIVE_TOMBSTONE_DIRECTORY/\$fileName"))
        assertTrue(recovery.contains("Android returned no exit trace stream"))
        assertTrue(recovery.contains("failure.stackTraceToString()"))

        val runtimeSupervisor = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxRuntimeProcess.kt"
        ).readText()
        val importerSupervisor = File(
            "src/main/java/ai/kompile/chat/local/android/model/SdxModelPreparationProcess.kt"
        ).readText()
        assertTrue(runtimeSupervisor.contains("EXIT_EVIDENCE_WAIT_MILLIS = 5_000L"))
        assertTrue(importerSupervisor.contains("EXIT_EVIDENCE_WAIT_MILLIS = 5_000L"))
    }

    @Test
    fun allNd4jModelLifecycleBoundariesStayInsideTheDurableJournal() {
        val importer = File(
            "src/main/java/ai/kompile/chat/local/android/model/SdxRawGgufChatSession.kt"
        ).readText()
        assertCheckpointBeforeCall(
            importer,
            "SdxAndroidLlmLibrary.bind(library)",
            "LOAD_IMPORTER_TRANSPORT"
        )
        assertCheckpointBeforeCall(importer, "native.sdxLlmCreateRuntime()", "CREATE_IMPORTER_RUNTIME")
        assertCheckpointBeforeCall(importer, "native.sdxLlmAbiVersion(runtime)", "QUERY_IMPORTER_ABI")
        assertCheckpointBeforeCall(importer, "native.sdxLlmPrepareGguf(", "CONVERT_OPTIMIZE_SDZ")
        assertCheckpointBeforeCall(importer, "readAndFree(\n                            operation", "READ_PREPARED_MODEL")
        assertCheckpointBeforeCall(importer, "native.sdxLlmGetLastError(", "QUERY_IMPORTER_LAST_ERROR")
        assertCheckpointBeforeCall(importer, "native.sdxLlmFree(", "FREE_IMPORTER_RESULT")
        assertCheckpointBeforeCall(importer, "native.sdxLlmDestroyRuntime(runtime)", "DESTROY_IMPORTER_RUNTIME")
        assertEquals(7, Regex("native\\.sdxLlm[A-Za-z0-9]+\\(").findAll(importer).count())
        assertTrue(importer.contains("internal object SdxGgufModelImporter"))
        assertTrue(importer.contains("model.absolutePath,\n                    tokenizerPath,"))
        assertFalse(importer.contains(": PlatformLocalChatSession"))
        assertFalse(importer.contains("PlatformLocalChatModelFactory.open("))

        val modelSeam = File(
            "src/main/java/ai/kompile/chat/local/android/model/AcceleratedChatModelAndroid.kt"
        ).readText()
        assertTrue(modelSeam.contains("SdxGgufModelImporter.prepare("))
        assertTrue(modelSeam.contains("tokenizerPath = tokenizerPath"))
        assertTrue(modelSeam.contains("preparationInfo?.canonicalSdzPath ?: modelPath"))
        assertEquals(
            1,
            Regex("PlatformLocalChatModelFactory\\.open\\(").findAll(modelSeam).count()
        )

        val resolver = File(
            "src/main/java/ai/kompile/chat/local/android/model/MobileModelArtifactResolver.kt"
        ).readText()
        assertCheckpointBeforeCall(resolver, "SdxModelCache(cacheRoot.toPath()).resolveVerified(", "RESOLVE_MODEL_ASSETS")
        assertTrue(resolver.contains("NativeOperationJournal(applicationContext).begin"))
        assertTrue(resolver.contains("operation.failAndPersist(failure)"))

        val viewModel = File(
            "src/main/java/ai/kompile/chat/local/android/viewmodel/ChatViewModel.kt"
        ).readText()
        assertEquals(
            2,
            Regex("MobileModelArtifactResolver\\.resolveWithOwnJournal\\(")
                .findAll(viewModel)
                .count()
        )
        assertTrue(viewModel.contains("val activeModelPath = preparedModel?.canonicalSdzPath ?: exactModelPath"))
        assertTrue(viewModel.contains("modelPath = activeModelPath"))
        assertTrue(
            viewModel.contains(
                "tokenizerPath = tokenizerAssets.paths[\"tokenizer.json\"]?.toString()"
            )
        )
        val startupOpen = viewModel.substring(
            viewModel.indexOf("private fun rebuildEngineLocked("),
            viewModel.indexOf("val route = newLocal.routeName")
        )
        assertTrue(startupOpen.contains("preparationOptions = prefs.modelPreparationOptions"))
        assertFalse(viewModel.contains("SdxRawGgufChatSession"))

        val sdx = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxPlatformChatSession.kt"
        ).readText()
        assertTrue(sdx.contains("Application.getProcessName() == sdxRuntimeProcessName"))
        assertEquals(0, Regex("\\.failAndPersist\\(failure\\)").findAll(sdx).count())
        assertFalse(sdx.contains("ai.kompile.chat.local.sdx.SdxLlmAbi"))
        assertTrue(sdx.contains("SdxAndroidLlmAbi"))
        assertFalse(sdx.contains("MobileModelArtifactResolver"))
        assertFalse(sdx.contains("org.bytedeco.javacpp"))
        assertTrue(sdx.contains("SdxNativeHandle"))
        assertFalse(sdx.contains("import org.nd4j.dsp.runtime.SdxRuntime"))
        assertFalse(sdx.contains("SdxRuntime.create()"))
        assertFalse(sdx.contains("NativeTokenizer"))
        assertFalse(sdx.contains("SdxTextSession"))
        assertFalse(sdx.contains("SameDiff"))
        assertFalse(sdx.contains("fromFlatGraph"))
        assertCheckpointBeforeCall(
            sdx,
            "SdxAndroidLlmLibrary.bind(library)",
            "LOAD_NATIVE_TRANSPORT"
        )
        assertCheckpointBeforeCall(
            sdx,
            "abi.sdxLlmCreateRuntime()",
            "CREATE_NATIVE_RUNTIME"
        )
        assertCheckpointBeforeCall(
            sdx,
            "abi.sdxLlmAbiVersion(runtimeHandle)",
            "QUERY_RUNTIME_ABI"
        )
        assertCheckpointBeforeCall(
            sdx,
            "abi.sdxLlmResolveModelBundle(",
            "RESOLVE_MODEL_ASSETS"
        )
        assertCheckpointBeforeCall(
            sdx,
            "abi.sdxLlmLoadCompiledModel(",
            "LOAD_MODEL_BUNDLE"
        )
        assertCheckpointBeforeCall(
            sdx,
            "native.sdxLlmRenderChatPrompt(",
            "RENDER_CHAT_TEMPLATE"
        )
        assertCheckpointBeforeCall(
            sdx,
            "native.sdxLlmGenerateStreaming(",
            "GENERATE_TOKENS"
        )
        assertCheckpointBeforeCall(
            sdx,
            "native.sdxLlmTokenCount(",
            "GENERATE_TOKENS"
        )
        assertFalse(sdx.contains("TENSOR_G3_MAX_PROMPT_TOKENS"))
        assertTrue(sdx.contains("native_fixed_plan_rolling_window"))
        val cancellation = sdx.substring(
            sdx.indexOf("override fun cancel("),
            sdx.indexOf("override fun close(")
        )
        assertCheckpointBeforeCall(
            cancellation,
            "cancelRequested.set(true)",
            "CANCEL_GENERATION"
        )
        assertFalse(cancellation.contains("native.sdxLlm"))
        assertTrue(sdx.contains("native.sdxLlmUnloadModel(runtime, model)"))
        assertTrue(sdx.contains("native.sdxLlmDestroyRuntime(runtime)"))
        assertTrue(sdx.contains("\"native_generation_report\""))
        assertTrue(sdx.contains("\"decode_tokens_per_second\""))
        assertTrue(sdx.contains("\"native_empty_output\""))
        assertTrue(sdx.contains("SDX returned no assistant text after compiled generation"))
        assertTrue(sdx.contains("\"generated_token_ids\" to generatedTokenIds"))
        assertTrue(sdx.contains("generatedTokenIds.take(TRACE_TEXT_PREVIEW_CHARS)"))
        assertTrue(sdx.contains("generated_token_ids_preview="))
        assertTrue(sdx.contains("raw_decoded_utf8_hex_prefix="))
        assertTrue(sdx.contains("\"native_op_sanity\" to effectiveDiagnosticMode.nativeOpSanity"))

        val runtimeEnvironment = File(
            "src/main/java/ai/kompile/chat/local/android/model/SdxRawGgufChatSession.kt"
        ).readText()
        assertTrue(runtimeEnvironment.contains("ND4J_DSP_NATIVE_DUMP_OUTPUTS"))
        assertTrue(runtimeEnvironment.contains("ND4J_DSP_DIAG_EXEC_LIMIT"))
        assertTrue(
            runtimeEnvironment.contains(
                "Os.setenv(\"ND4J_DSP_DIAG_EXEC_LIMIT\", \"64\", true)"
            )
        )
        assertTrue(runtimeEnvironment.contains("effectiveDiagnosticMode.capturesDspTrace"))

        val androidAbi = File(
            "src/main/java/ai/kompile/chat/local/android/model/SdxAndroidLlmAbi.kt"
        ).readText()
        assertTrue(
            androidAbi.contains(
                "const val ABI_VERSION = SdxAndroidLlmNative.SDX_LLM_ABI_VERSION"
            )
        )
        assertTrue(androidAbi.contains("SdxAndroidLlmNative.nativePrepareGguf("))
        assertTrue(androidAbi.contains("SdxAndroidLlmNative.nativeResolveModelBundle("))
        assertTrue(androidAbi.contains("SdxAndroidLlmNative.nativeLoadCompiledModel("))
        assertTrue(androidAbi.contains("SdxAndroidLlmNative.nativeRenderChatPrompt("))
        assertTrue(androidAbi.contains("SdxAndroidLlmNative.nativeTokenCount("))
        assertTrue(androidAbi.contains("SdxAndroidLlmNative.nativeLastResultJson("))
        assertTrue(androidAbi.contains("SdxAndroidLlmNative.nativeGenerateStreaming("))
        assertTrue(androidAbi.contains("SdxAndroidLlmNative.nativeReadUtf8("))
        assertFalse(androidAbi.contains("org.bytedeco.javacpp"))
        assertFalse(androidAbi.contains("SdxLlmNative"))
        assertFalse(androidAbi.contains("com.sun.jna"))
        assertFalse(androidAbi.contains("Native.load"))

        val androidJni = File("src/main/cpp/sdx_llm_android_jni.cpp").readText()
        assertTrue(androidJni.contains("#include \"sdx_llm_c.h\""))
        assertTrue(
            androidJni.contains(
                "Java_ai_kompile_chat_local_android_model_SdxAndroidLlmNative_nativeCreateRuntime"
            )
        )
        assertTrue(androidJni.contains("sdxLlmGenerateStreaming("))
        assertFalse(androidJni.contains("kompile_reasoning"))

        val androidPackager = File("../tools/build-offline-accelerators.sh").readText()
        assertTrue(androidPackager.contains("build_sdx_android_jni_bridge()"))
        assertTrue(androidPackager.contains("Kompile-owned Android SDX JNI source"))
        assertTrue(androidPackager.contains("SDX SDK still contains the Kompile-owned JNI transport"))
        assertTrue(androidPackager.contains("-Wl,-soname,libjnisdx_llm.so"))
        assertTrue(androidPackager.contains("    nd4j/nd4j-ggml\n"))
        assertTrue(androidPackager.contains("[nd4j-ggml]=\"nd4j/nd4j-ggml\""))

        val shrinkerRules = File("proguard-rules.pro").readText()
        assertTrue(shrinkerRules.contains("-keep class org.nd4j.dsp.model.SdxLlmNative { *; }"))
        assertTrue(shrinkerRules.contains("-keep class kotlin.** { *; }"))
        assertTrue(shrinkerRules.contains("-checkdiscard class ai.kompile.chat.local.sdx.SdxLlmAbi"))
        assertFalse(shrinkerRules.contains("-keep class com.sun.jna"))
        assertFalse(shrinkerRules.contains("-keep interface ai.kompile.chat.local.sdx.SdxLlmAbi"))

        val apkVerifier = File("../tools/verify-offline-apk.sh").readText()
        assertTrue(apkVerifier.contains("--class ai.kompile.chat.local.android.model.SdxAndroidLlmAbi"))
        assertTrue(apkVerifier.contains("org/nd4j/dsp/model/SdxLlmNative.class"))
        assertFalse(apkVerifier.contains("--class ai.kompile.chat.local.sdx.SdxLlmAbi"))
        assertTrue(apkVerifier.contains("causal-lm-in-graph-state-v2"))
        assertTrue(apkVerifier.contains("io.recurrentStates"))
        assertTrue(apkVerifier.contains("duplicate recurrent state input"))
        listOf(
            "ANeuralNetworks_getDeviceCount",
            "ANeuralNetworks_getDevice",
            "ANeuralNetworksDevice_getName",
            "ANeuralNetworksDevice_getType",
            "ANeuralNetworksDevice_getFeatureLevel",
            "ANeuralNetworksModel_getSupportedOperationsForDevices",
            "ANeuralNetworksCompilation_createForDevices",
        ).forEach { symbol -> assertTrue(apkVerifier.contains(symbol)) }
        assertTrue(apkVerifier.contains("forbidden generic NNAPI compilation"))
        assertTrue(apkVerifier.contains("google-edgetpu device fingerprint"))

        val runtimeProcess = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxRuntimeProcess.kt"
        ).readText()
        assertTrue(runtimeProcess.contains("NativeOperationJournal(applicationContext).begin"))
        assertTrue(runtimeProcess.contains("NativeOperationJournal(applicationContext).resume"))
        assertTrue(runtimeProcess.contains("recoverRuntimeFailure("))
        assertTrue(runtimeProcess.contains("NativeOperationCrashRecovery.recoverAttemptAndPersist("))
        assertTrue(runtimeProcess.contains("SdxPlatformRuntimeOwner.open("))
        assertFalse(runtimeProcess.contains("SdxRuntime.create()"))
        assertFalse(runtimeProcess.contains("SdxRuntime.ModelOptions"))
        assertFalse(runtimeProcess.contains("KEY_BACKEND"))
        assertFalse(runtimeProcess.contains("KEY_STRICT_BACKEND"))

        listOf("tensorG3", "vulkan", "hexagon").forEach { sourceSet ->
            val factory = File(
                "src/$sourceSet/java/ai/kompile/chat/local/android/model/" +
                    "PlatformLocalChatModelFactory.kt"
            ).readText()
            assertFalse(factory.contains("SdxRuntime"))
            assertFalse(factory.contains("ModelOptions"))
        }
        assertFalse(
            File(
                "src/vulkan/java/ai/kompile/chat/local/android/model/" +
                    "VulkanModelOptionsCompatibility.kt"
            ).exists()
        )

        val liteRt = File(
            "src/tensorG5/java/ai/kompile/chat/local/android/model/PlatformLocalChatModelFactory.kt"
        ).readText()
        assertTrue(
            liteRt.contains(
                "MobileModelArtifactResolver.resolve(\n" +
                    "                applicationContext,\n" +
                    "                modelPath,\n" +
                    "                operation"
            )
        )
        assertEquals(2, Regex("\\.failAndPersist\\(failure\\)").findAll(liteRt).count())
        assertCheckpointBeforeCall(liteRt, "SdxLiteRtLmChatSession.builder(", "LOAD_LITERT_RUNTIME")
        assertCheckpointBeforeCall(liteRt, "builder.build()", "CREATE_LITERT_SESSION")
        assertCheckpointBeforeCall(liteRt, "session.sendMessageStreaming(", "EXECUTE_LITERT_GENERATION")
        assertCheckpointBeforeCall(liteRt, "session.cancel()", "CANCEL_LITERT_GENERATION")
        assertCheckpointBeforeCall(liteRt, "session.close()", "CLOSE_LITERT_SESSION")
    }

    @Test
    fun startupRecoveryNeverTreatsAnOrphanedLiveWorkerAsResumable() {
        val diagnostics = File(
            "src/main/java/ai/kompile/chat/local/android/diagnostics/NativeRuntimeLoadDiagnostics.kt"
        ).readText()
        val recovery = diagnostics.substring(
            diagnostics.indexOf("fun recoverAndPersist(context: Context)"),
            diagnostics.indexOf("fun recoverAttemptAndPersist(")
        )
        val workerEvidence = recovery.indexOf(
            "val workerExitEvidence = if (isProcessAlive(activeAttempt.processId))"
        )
        val exactWorkerEvidence = recovery.indexOf(
            "awaitExitEvidence(context, activeAttempt)",
            workerEvidence
        )
        val mainProcessEvidence = recovery.indexOf(
            "?: awaitMainProcessExitEvidence(context, activeAttempt)"
        )
        val terminalReread = recovery.indexOf("journal.loadPending(activeAttempt.attemptId)")
        val append = recovery.indexOf("ImportDiagnosticStore(context).appendDurably(diagnostic)")

        assertTrue("Recovery must inspect the exact pending worker pid", workerEvidence >= 0)
        assertTrue("Recovery must query worker exit evidence when the worker is dead", exactWorkerEvidence > workerEvidence)
        assertTrue("Recovery must query historical main-process exit evidence", mainProcessEvidence > exactWorkerEvidence)
        assertFalse(
            "A live worker left by the previous main process is not resumable",
            recovery.substring(workerEvidence, mainProcessEvidence).contains("return@mapNotNull null")
        )
        assertTrue("Recovery must re-read provider-owned terminal state", terminalReread > mainProcessEvidence)
        assertTrue("Recovery must only persist after the terminal recheck", append > terminalReread)
    }

    @Test
    fun managedNativeFailuresArePersistedBeforeTheirJournalEntryIsCleared() {
        val diagnostics = File(
            "src/main/java/ai/kompile/chat/local/android/diagnostics/NativeRuntimeLoadDiagnostics.kt"
        ).readText()
        val persistMethod = diagnostics.indexOf("internal fun persistManagedFailure(")
        val append = diagnostics.indexOf(
            "ImportDiagnosticStore(applicationContext).appendDurably(diagnostic)",
            persistMethod
        )
        val clear = diagnostics.indexOf("clear(attemptId)", append)

        assertTrue("Missing managed-failure persistence API", persistMethod >= 0)
        assertTrue("Managed failure is not durably appended", append > persistMethod)
        assertTrue("Managed failure journal is cleared before the diagnostic is durable", clear > append)
        assertTrue(diagnostics.contains("fun failAndPersist(failure: Throwable)"))
        assertFalse(diagnostics.contains("fun failed()"))

        val importerProcess = File(
            "src/main/java/ai/kompile/chat/local/android/model/SdxModelPreparationProcess.kt"
        ).readText()
        assertTrue(importerProcess.contains("operation.failAndPersist(failure)"))
    }

    @Test
    fun preparedModelPayloadRoundTripsEveryExecutionField() {
        val expected = PreparedModelInfo(
            cacheHit = true,
            sourceSha256 = "a".repeat(64),
            sourceBytes = 987_654_321L,
            canonicalSdzLogicalSha256 = "b".repeat(64),
            canonicalSdzLogicalBytes = 112_233_445L,
            canonicalSdzPath = "/data/user/0/app/no_backup/sdx-model-cache/v1/model.sdz",
            canonicalSdzBytes = 123_456_789L,
            modelPath = "/data/user/0/app/no_backup/sdx-model-cache/v1/target/model.sdz",
            tokenizerPath = "/data/user/0/app/no_backup/sdx-model-cache/v1/tokenizer.json",
            compileKey = "compile-key",
            targetProfile = "android-arm64-nnapi-accelerator",
            targetSoc = "google-tensor-g3",
            contextLength = 4096,
            maxPrefillLength = 1024,
            conversionProfileSha256 = "c".repeat(64),
            diagnosticMode = ModelDiagnosticMode.DSP_DIAGNOSTICS.wireValue,
            optimizedSourcePath = "/data/user/0/app/no_backup/sdx-model-cache/v1/source-q4_k.gguf",
            optimizedSourceBytes = 456_789_123L,
        )

        assertEquals(
            expected,
            PreparedModelPayload.from(expected).toPreparedModelInfo()
        )
    }

    @Test
    fun runtimeWireRoundTripsConversationAndEveryGenerationOption() {
        val messages = listOf(
            Message.system("Be precise"),
            Message.user("Hello \"runtime\"\nline two"),
            Message.assistant("Hi"),
            Message.toolResult("search", "{\"ok\":true}")
        )
        assertEquals(messages, decodeSdxRuntimeMessages(encodeSdxRuntimeMessages(messages)))

        val expected = GenOptions.builder()
            .temperature(0.25)
            .maxTokens(321)
            .topP(0.82)
            .topK(17)
            .seed(123456789L)
            .build()
        val decoded = decodeSdxRuntimeGenerationOptions(expected.toOptionsJson())
        assertEquals(expected.temperature(), decoded.temperature(), 0.0)
        assertEquals(expected.maxTokens(), decoded.maxTokens())
        assertEquals(expected.topP(), decoded.topP(), 0.0)
        assertEquals(expected.topK(), decoded.topK())
        assertEquals(expected.seed(), decoded.seed())
        assertEquals(
            15L * 60L * 1_000L,
            sdxRuntimeGenerationTimeoutMillis(16, coldCompilation = true)
        )
        assertEquals(
            5L * 60L * 1_000L,
            sdxRuntimeGenerationTimeoutMillis(16, coldCompilation = false)
        )
        assertTrue(
            sdxRuntimeGenerationTimeoutMillis(Int.MAX_VALUE, coldCompilation = true) <=
                2L * 60L * 60L * 1_000L
        )
    }

    @Test
    fun exitEvidenceCorrelationDistinguishesImporterFromMainProcessAndPid() {
        val packageName = "ai.kompile.chat.local.android.tensorg3.debug"
        val importer = sdxImporterProcessName(packageName)
        val started = 10_000L
        val importerPid = 1234

        assertTrue(
            NativeOperationDiagnosticPolicy.exitMatchesProcess(
                expectedProcessName = importer,
                expectedProcessId = importerPid,
                startedEpochMillis = started,
                processName = importer,
                processId = importerPid,
                exitTimestampEpochMillis = started + 1
            )
        )
        assertFalse(
            NativeOperationDiagnosticPolicy.exitMatchesProcess(
                expectedProcessName = importer,
                expectedProcessId = importerPid,
                startedEpochMillis = started,
                processName = packageName,
                processId = importerPid,
                exitTimestampEpochMillis = started + 1
            )
        )
        assertFalse(
            NativeOperationDiagnosticPolicy.exitMatchesProcess(
                expectedProcessName = importer,
                expectedProcessId = importerPid,
                startedEpochMillis = started,
                processName = importer,
                processId = importerPid + 1,
                exitTimestampEpochMillis = started + 1
            )
        )
        assertFalse(
            NativeOperationDiagnosticPolicy.exitMatchesProcess(
                expectedProcessName = importer,
                expectedProcessId = importerPid,
                startedEpochMillis = started,
                processName = importer,
                processId = importerPid,
                exitTimestampEpochMillis = started - 2_001
            )
        )
    }

    private fun assertCheckpointBeforeCall(source: String, call: String, checkpoint: String) {
        val callIndex = source.indexOf(call)
        assertTrue("Missing native boundary '$call'", callIndex >= 0)
        assertCallUsesCheckpoint(source, call, callIndex, checkpoint)
    }

    private fun assertEveryCallUsesCheckpoint(source: String, call: String, checkpoint: String) {
        var callIndex = source.indexOf(call)
        assertTrue("Missing native boundary '$call'", callIndex >= 0)
        while (callIndex >= 0) {
            assertCallUsesCheckpoint(source, call, callIndex, checkpoint)
            callIndex = source.indexOf(call, callIndex + call.length)
        }
    }

    private fun assertCallUsesCheckpoint(
        source: String,
        call: String,
        callIndex: Int,
        checkpoint: String
    ) {
        val closestCheckpoint = source.lastIndexOf("NativeOperationCheckpoint.", callIndex)
        val expectedCheckpoint = source.lastIndexOf(
            "NativeOperationCheckpoint.$checkpoint",
            callIndex
        )
        assertTrue("No durable checkpoint precedes '$call'", closestCheckpoint >= 0)
        assertEquals(
            "The closest durable checkpoint before '$call' must be $checkpoint",
            expectedCheckpoint,
            closestCheckpoint
        )
    }
}
