package ai.kompile.chat.local.android.model

import android.content.Context
import ai.kompile.chat.local.android.BuildConfig
import ai.kompile.chat.local.android.diagnostics.NativeOperationCheckpoint
import ai.kompile.chat.local.android.diagnostics.NativeOperationJournal
import ai.kompile.chat.local.android.diagnostics.NativeOperationKind
import ai.kompile.chat.local.android.diagnostics.NativeOperationTransaction
import org.nd4j.dsp.model.SdxCompiledModel
import org.nd4j.dsp.model.SdxModelCache
import org.nd4j.dsp.model.SdxTargetProfile
import java.io.File

/**
 * Resolves one canonical SameDiff .sdz to this APK flavor's immutable AOT cache object.
 *
 * Provider-specific files are compiler implementation details embedded under
 * META-INF/sdx-cache in the SDZ. Android never compiles or falls back at runtime:
 * a missing or invalid target object is a hard model-load failure.
 */
internal object MobileModelArtifactResolver {

    /**
     * Resolve outside an already-journaled runtime load while retaining the exact failing boundary
     * across managed exceptions, native aborts, and whole-process death.
     */
    fun resolveWithOwnJournal(context: Context, modelPath: String): SdxCompiledModel {
        val applicationContext = context.applicationContext
        val operation = NativeOperationJournal(applicationContext).begin(
            modelPath = modelPath,
            operation = NativeOperationKind.SDX_MODEL_LOAD,
            checkpoint = NativeOperationCheckpoint.RESOLVE_MODEL_ASSETS
        )
        return try {
            val compiled = resolve(applicationContext, modelPath, operation)
            operation.complete()
            compiled
        } catch (failure: Throwable) {
            try {
                operation.failAndPersist(failure)
            } catch (reportingFailure: Throwable) {
                failure.addSuppressed(reportingFailure)
            }
            throw failure
        }
    }

    fun resolve(
        context: Context,
        modelPath: String,
        operation: NativeOperationTransaction
    ): SdxCompiledModel {
        val source = File(modelPath)
        require(source.isFile) { "SDX source model does not exist: $modelPath" }
        require(source.name.endsWith(".sdz", ignoreCase = true)) {
            "Local models use the canonical SameDiff .sdz format"
        }

        operation.checkpoint(NativeOperationCheckpoint.RESOLVE_MODEL_ASSETS)
        val target = SdxTargetProfile.fromId(BuildConfig.SDX_TARGET_PROFILE)
        val cacheRoot = File(context.noBackupFilesDir, "sdx-model-cache")
        val compiled = SdxModelCache(cacheRoot.toPath()).resolve(source.toPath(), target)
        // A target object alone is not a runnable chat model. Fail at import with
        // the staging guidance from SDX instead of opening a partial native session.
        compiled.requireTextModelAssets()
        return compiled
    }
}
