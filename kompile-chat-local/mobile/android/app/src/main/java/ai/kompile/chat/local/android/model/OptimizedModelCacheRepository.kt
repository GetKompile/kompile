package ai.kompile.chat.local.android.model

import android.content.Context
import org.nd4j.dsp.model.SdxModelCache
import org.nd4j.dsp.model.SdxTargetProfile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class OptimizedModelCacheEntry(
    val compileKey: String,
    val canonicalSdzPath: String,
    val displayName: String,
    val profileLabel: String,
    val targetProfile: String,
    val compilerLabel: String,
    val canonicalBytes: Long,
    val compiledObjectBytes: Long,
    val referencedBytes: Long,
    val lastModifiedMillis: Long,
    val active: Boolean,
)

internal data class OptimizedModelStorageSnapshot(
    val entries: List<OptimizedModelCacheEntry>,
    val optimizedCacheBytes: Long,
    val retainedModelBytes: Long,
    val deviceCompilationBytes: Long,
    val totalModelBytes: Long,
    val invalidCacheEntries: Int,
) {
    companion object {
        val EMPTY = OptimizedModelStorageSnapshot(emptyList(), 0L, 0L, 0L, 0L, 0)
    }
}

/** App-facing projection of the authoritative shared SDX cache inventory. */
internal class OptimizedModelCacheRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val cacheDirectory = File(applicationContext.noBackupFilesDir, "sdx-model-cache")
    private val cache = SdxModelCache(cacheDirectory.toPath())
    private val labelDirectory = File(applicationContext.filesDir, "models/optimized-catalog")
    private val retainedModelsDirectory = File(applicationContext.filesDir, "models")
    private val deviceCompilationDirectory =
        File(applicationContext.codeCacheDir, "sdx-device-compilation")

    fun snapshot(targetProfile: String, activeModelPath: String): OptimizedModelStorageSnapshot {
        val target = SdxTargetProfile.fromId(targetProfile)
        val inventory = cache.inventory(target)
        val activePath = activeModelPath.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.canonicalFile
            ?.absolutePath
        val entries = inventory.entries().map { cached ->
            val label = readLabel(cached.compileKey())
            val canonicalPath = cached.sourceModel().toFile().canonicalPath
            OptimizedModelCacheEntry(
                compileKey = cached.compileKey(),
                canonicalSdzPath = canonicalPath,
                displayName = label?.displayName
                    ?: "Optimized model ${cached.sourceSha256().take(12)}",
                profileLabel = label?.profileLabel ?: "Existing optimized profile",
                targetProfile = cached.target().id(),
                compilerLabel = "${cached.compilerId()} ${cached.compilerVersion()}",
                canonicalBytes = cached.sourcePhysicalBytes(),
                compiledObjectBytes = cached.objectPhysicalBytes(),
                referencedBytes = cached.referencedPhysicalBytes(),
                lastModifiedMillis = maxOf(
                    label?.lastUsedMillis ?: 0L,
                    cached.lastModifiedMillis(),
                ),
                active = canonicalPath == activePath,
            )
        }.sortedWith(
            compareByDescending<OptimizedModelCacheEntry> { it.active }
                .thenByDescending { it.lastModifiedMillis }
                .thenBy { it.displayName.lowercase() }
        )
        val retainedBytes = regularFileBytes(retainedModelsDirectory)
        val deviceBytes = regularFileBytes(deviceCompilationDirectory)
        val totalBytes = addExact(
            inventory.totalPhysicalBytes(),
            retainedBytes,
            deviceBytes,
        )
        return OptimizedModelStorageSnapshot(
            entries = entries,
            optimizedCacheBytes = inventory.totalPhysicalBytes(),
            retainedModelBytes = retainedBytes,
            deviceCompilationBytes = deviceBytes,
            totalModelBytes = totalBytes,
            invalidCacheEntries = inventory.invalidReferenceCount(),
        )
    }

    fun remember(
        prepared: PreparedModelInfo,
        originalModelPath: String,
        options: ModelPreparationOptions,
    ) {
        require(prepared.compileKey.matches(Regex("[0-9a-f]{64}"))) {
            "Optimized model compile key is invalid"
        }
        val canonical = File(prepared.canonicalSdzPath).canonicalFile
        val ownedRoot = File(cacheDirectory, "v1").canonicalFile
        require(canonical.toPath().startsWith(ownedRoot.toPath()) && canonical.isFile) {
            "Optimized model catalog only accepts canonical cache-owned SDZ files"
        }
        require(labelDirectory.mkdirs() || labelDirectory.isDirectory) {
            "Could not create optimized model catalog directory"
        }
        val displayName = File(originalModelPath).name
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(200)
            .ifBlank { "Optimized model ${prepared.canonicalSdzLogicalSha256.take(12)}" }
        val values = Properties().apply {
            setProperty("format", LABEL_FORMAT)
            setProperty("compileKey", prepared.compileKey)
            setProperty("displayName", displayName)
            setProperty(
                "profileLabel",
                "${options.weightOptimization.label} · ${options.kvCacheOptimization.label}",
            )
            setProperty("lastUsedMillis", System.currentTimeMillis().toString())
        }
        val target = labelFile(prepared.compileKey)
        val temporary = File(labelDirectory, ".${prepared.compileKey}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                values.store(output, null)
                output.fd.sync()
            }
            publishLabel(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun markUsed(compileKey: String) {
        val current = readLabel(compileKey) ?: return
        val values = Properties().apply {
            setProperty("format", LABEL_FORMAT)
            setProperty("compileKey", compileKey)
            setProperty("displayName", current.displayName)
            setProperty("profileLabel", current.profileLabel)
            setProperty("lastUsedMillis", System.currentTimeMillis().toString())
        }
        val target = labelFile(compileKey)
        val temporary = File(labelDirectory, ".$compileKey.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                values.store(output, null)
                output.fd.sync()
            }
            publishLabel(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun readLabel(compileKey: String): CatalogLabel? {
        if (!compileKey.matches(Regex("[0-9a-f]{64}"))) return null
        val file = labelFile(compileKey)
        if (Files.isSymbolicLink(file.toPath()) ||
            !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) return null
        val labelBytes = file.length()
        if (labelBytes <= 0L || labelBytes > MAX_LABEL_FILE_BYTES) return null
        return runCatching {
            val properties = Properties()
            FileInputStream(file).use(properties::load)
            if (properties.getProperty("format") != LABEL_FORMAT ||
                properties.getProperty("compileKey") != compileKey
            ) return@runCatching null
            val displayName = properties.getProperty("displayName")
                ?.takeIf(String::isNotBlank)
                ?.takeIf { it.length <= MAX_LABEL_VALUE_CHARS }
                ?: return@runCatching null
            val profileLabel = properties.getProperty("profileLabel")
                ?.takeIf(String::isNotBlank)
                ?.takeIf { it.length <= MAX_LABEL_VALUE_CHARS }
                ?: "Optimized profile"
            val lastUsed = properties.getProperty("lastUsedMillis")?.toLongOrNull() ?: 0L
            CatalogLabel(displayName, profileLabel, lastUsed)
        }.getOrNull()
    }

    private fun labelFile(compileKey: String): File = File(labelDirectory, "$compileKey.properties")

    private fun publishLabel(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun regularFileBytes(root: File): Long {
        if (!root.exists()) return 0L
        require(!Files.isSymbolicLink(root.toPath())) {
            "Model storage root must not be a symlink: ${root.absolutePath}"
        }
        var total = 0L
        Files.walk(root.toPath()).use { paths ->
            paths.sorted().forEach { path ->
                require(!Files.isSymbolicLink(path)) {
                    "Model storage must not contain symlinks: $path"
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total = Math.addExact(total, Files.size(path))
                }
            }
        }
        return total
    }

    private data class CatalogLabel(
        val displayName: String,
        val profileLabel: String,
        val lastUsedMillis: Long,
    )

    private companion object {
        const val LABEL_FORMAT = "optimized-model-label-v1"
        const val MAX_LABEL_FILE_BYTES = 16L * 1024L
        const val MAX_LABEL_VALUE_CHARS = 512

        fun addExact(vararg values: Long): Long =
            values.fold(0L) { total, value -> Math.addExact(total, value) }
    }
}
