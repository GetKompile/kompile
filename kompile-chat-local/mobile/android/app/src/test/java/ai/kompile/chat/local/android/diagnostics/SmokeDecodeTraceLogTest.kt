package ai.kompile.chat.local.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeDecodeTraceLogTest {
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