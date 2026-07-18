package ai.kompile.chat.local.android.model

import android.content.Context
import ai.kompile.chat.local.android.BuildConfig
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

    fun resolve(context: Context, modelPath: String): SdxCompiledModel {
        val source = File(modelPath)
        require(source.isFile) { "SDX source model does not exist: $modelPath" }
        require(source.name.endsWith(".sdz", ignoreCase = true)) {
            "Local models use the canonical SameDiff .sdz format"
        }

        val target = SdxTargetProfile.fromId(BuildConfig.SDX_TARGET_PROFILE)
        val cacheRoot = File(context.noBackupFilesDir, "sdx-model-cache")
        return SdxModelCache(cacheRoot.toPath()).resolve(source.toPath(), target)
    }
}
