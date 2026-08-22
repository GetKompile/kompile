package ai.kompile.chat.local.android.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeDecodeTraceLogTest {
    @Test
    fun dspDiagnosticsExportStreamsActiveAndRotatedCapturesIntoOneFile() {
        val directory = Files.createTempDirectory("dsp-diagnostics-export").toFile()
        try {
            File(directory, DspDiagnosticsExportPolicy.ACTIVE_FILE)
                .writeText("active-event\n")
            File(directory, "${DspDiagnosticsExportPolicy.ACTIVE_FILE}.1")
                .writeText("backup-event\n")

            val exported = DspDiagnosticsExportPolicy.writeShareSnapshot(directory)
            val contents = exported.readText()

            assertTrue(exported.name.startsWith(DspDiagnosticsExportPolicy.SHARE_FILE_PREFIX))
            assertTrue(exported.name.endsWith(DspDiagnosticsExportPolicy.SHARE_FILE_SUFFIX))
            assertTrue(contents.contains("=== dsp-diagnostics.json (13 bytes) ==="))
            assertTrue(contents.contains("active-event"))
            assertTrue(contents.contains("=== dsp-diagnostics.json.1 (13 bytes) ==="))
            assertTrue(contents.contains("backup-event"))
            assertTrue(contents.indexOf("active-event") < contents.indexOf("backup-event"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dspDiagnosticsResetStartsAnEmptyChatCaptureWithoutOldBackups() {
        val directory = Files.createTempDirectory("dsp-diagnostics-reset").toFile()
        try {
            val active = File(directory, DspDiagnosticsExportPolicy.ACTIVE_FILE)
                .apply { writeText("previous-chat\n") }
            File(directory, "${DspDiagnosticsExportPolicy.ACTIVE_FILE}.1")
                .writeText("previous-runtime\n")
            File(directory, "${DspDiagnosticsExportPolicy.ACTIVE_FILE}.2")
                .writeText("previous-import\n")
            val retainedExport = DspDiagnosticsExportPolicy.writeShareSnapshot(directory)

            DspDiagnosticsExportPolicy.resetForChat(directory)

            assertTrue(active.isFile)
            assertEquals(0L, active.length())
            assertFalse(File(directory, "${DspDiagnosticsExportPolicy.ACTIVE_FILE}.1").exists())
            assertFalse(File(directory, "${DspDiagnosticsExportPolicy.ACTIVE_FILE}.2").exists())
            assertTrue(retainedExport.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dspDiagnosticsExportImplementationDoesNotMaterializeTraceText() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/diagnostics/DspDiagnosticsTraceLog.kt"
        ).readText()

        assertTrue(source.contains("fun writeShareSnapshot("))
        assertTrue(source.contains("private fun copyPrefix("))
        assertTrue(source.contains("var remaining = source.length"))
        assertTrue(source.contains("COPY_BUFFER_BYTES = 64 * 1024"))
        assertFalse(source.contains("fun readContents(): String"))
        assertFalse(source.contains("RandomAccessFile"))
    }

    @Test
    fun dspDiagnosticsExportCopiesTheLengthCapturedBeforeNativeAppend() {
        val directory = Files.createTempDirectory("dsp-diagnostics-prefix").toFile()
        try {
            val active = File(directory, DspDiagnosticsExportPolicy.ACTIVE_FILE)
            active.writeText("stable-event\n")
            val appended = AtomicBoolean()

            val exported = DspDiagnosticsExportPolicy.writeShareSnapshot(directory) {
                if (appended.compareAndSet(false, true)) {
                    active.appendText("late-native-event\n")
                }
            }
            val contents = exported.readText()

            assertTrue(contents.contains("stable-event"))
            assertFalse(contents.contains("late-native-event"))
            assertTrue(active.readText().contains("late-native-event"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dspDiagnosticsExportsUseUniqueNamesAndBoundedRetention() {
        val directory = Files.createTempDirectory("dsp-diagnostics-retention").toFile()
        try {
            File(directory, DspDiagnosticsExportPolicy.ACTIVE_FILE).writeText("event\n")
            val exports = (1..3).map {
                DspDiagnosticsExportPolicy.writeShareSnapshot(directory)
            }
            val retained = directory.listFiles { file ->
                file.name.startsWith(DspDiagnosticsExportPolicy.SHARE_FILE_PREFIX) &&
                    file.name.endsWith(DspDiagnosticsExportPolicy.SHARE_FILE_SUFFIX)
            }.orEmpty()

            assertEquals(3, exports.map { it.name }.distinct().size)
            assertEquals(DspDiagnosticsExportPolicy.MAX_SHARE_FILES, retained.size)
            assertTrue(exports.last().isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentDspExportsAreSerializedWithoutOverlappingFileLocks() {
        val directory = Files.createTempDirectory("dsp-diagnostics-concurrent").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            File(directory, DspDiagnosticsExportPolicy.ACTIVE_FILE).writeText("event\n")
            val first = executor.submit<File> {
                DspDiagnosticsExportPolicy.writeShareSnapshot(directory)
            }
            val second = executor.submit<File> {
                DspDiagnosticsExportPolicy.writeShareSnapshot(directory)
            }

            val firstFile = first.get(10, TimeUnit.SECONDS)
            val secondFile = second.get(10, TimeUnit.SECONDS)
            assertFalse(firstFile.name == secondFile.name)
            assertTrue(firstFile.isFile)
            assertTrue(secondFile.isFile)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun rotationIsBoundedAtTheActiveFileLimit() {
        assertFalse(SmokeDecodeTracePolicy.shouldRotate(0L, 1L))
        assertFalse(
            SmokeDecodeTracePolicy.shouldRotate(
                SmokeDecodeTracePolicy.MAX_ACTIVE_BYTES - 1L,
                1L
            )
        )
        assertTrue(
            SmokeDecodeTracePolicy.shouldRotate(
                SmokeDecodeTracePolicy.MAX_ACTIVE_BYTES.toLong(),
                1L
            )
        )
    }

    @Test
    fun eventNamesAndFieldsStaySingleLineAndRedacted() {
        assertEquals("native_generate_enter", SmokeDecodeTracePolicy.eventName("native generate/enter"))
        assertEquals("unknown", SmokeDecodeTracePolicy.eventName("///"))
        val safe = SmokeDecodeTracePolicy.field(
            "failed https://huggingface.co/acme/model?token=hf_secret " +
                "/data/user/0/app/private.gguf\nAuthorization: Bearer hf_1234567890"
        )

        assertFalse(safe.contains("huggingface.co"))
        assertFalse(safe.contains("hf_secret"))
        assertFalse(safe.contains("hf_1234567890"))
        assertFalse(safe.contains("/data/user"))
        assertFalse(safe.contains('\n'))
        assertTrue(safe.length <= 2_048)
    }

    @Test
    fun retentionAndLineBoundsAreExplicit() {
        assertEquals(512 * 1024, SmokeDecodeTracePolicy.MAX_ACTIVE_BYTES)
        assertEquals(3, SmokeDecodeTracePolicy.MAX_BACKUP_FILES)
        assertEquals(16 * 1024, SmokeDecodeTracePolicy.MAX_LINE_BYTES)
        assertTrue(SmokeDecodeTracePolicy.MAX_LINE_CHARS < SmokeDecodeTracePolicy.MAX_LINE_BYTES)
    }

    @Test
    fun detailedFailuresAreRedactedBeforeLosslessChunking() {
        val raw = buildString {
            appendLine("remote failure https://huggingface.co/acme/model?token=hf_secret")
            appendLine("Authorization: Bearer hf_1234567890")
            repeat(400) { index ->
                appendLine("at ai.kompile.runtime.ModelOpen.frame$index(ModelOpen.kt:$index)")
            }
        }
        val expected = ImportDiagnosticPolicy.sanitizeDetails(raw)
        val chunks = SmokeDecodeTracePolicy.detailChunks(raw)

        assertEquals(expected, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= SmokeDecodeTracePolicy.MAX_DETAIL_CHUNK_CHARS })
        assertFalse(chunks.joinToString("").contains("huggingface.co"))
        assertFalse(chunks.joinToString("").contains("hf_secret"))
        assertFalse(chunks.joinToString("").contains("hf_1234567890"))
    }
}