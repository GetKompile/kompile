package ai.kompile.chat.local.android.model

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The one SHA-256 implementation for the app. Every previous private copy
 * (ModelPreparationOptions string hash, ChatViewModel file hash,
 * NativeRuntimeLoadDiagnostics byte hash, HuggingFaceGgmlAcquisition path hash)
 * delegated here so hex formatting, buffering, and charset handling cannot drift.
 *
 * All hex output is lowercase, matching the desktop `SdxSourceIdentity` and the
 * qualification manifest format.
 */
internal object SdxHashing {

    private const val STREAM_BUFFER_BYTES = 64 * 1024

    fun sha256Hex(bytes: ByteArray): String =
        digest("SHA-256").digest(bytes).toLowercaseHex()

    fun sha256Hex(value: String): String =
        sha256Hex(value.toByteArray(Charsets.UTF_8))

    fun sha256Hex(file: File): String = file.inputStream().buffered().use { sha256Hex(it) }

    fun sha256Hex(path: Path): String = Files.newInputStream(path).use { sha256Hex(it) }

    fun sha256Hex(input: InputStream): String {
        val digest = digest("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toLowercaseHex()
    }

    private fun digest(algorithm: String): MessageDigest =
        MessageDigest.getInstance(algorithm)

    private fun ByteArray.toLowercaseHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
