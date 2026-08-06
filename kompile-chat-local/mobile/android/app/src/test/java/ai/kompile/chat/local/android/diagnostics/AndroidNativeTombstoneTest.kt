package ai.kompile.chat.local.android.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class AndroidNativeTombstoneTest {

    @Test
    fun rendersSignalMemoryErrorNativeFramesBuildIdsAndLogsFromAndroidSchema() {
        val allocationFrame = frame(
            relativePc = 0x110L,
            function = "allocateShapeBuffer",
            file = "/data/app/libnd4j.so",
            buildId = "alloc-build-id"
        )
        val deallocationFrame = frame(
            relativePc = 0x220L,
            function = "releaseShapeBuffer",
            file = "/data/app/libnd4j.so",
            buildId = "free-build-id"
        )
        val crashingFrame = frame(
            relativePc = 0x330L,
            pc = 0x7f00330L,
            sp = 0x7fff000L,
            function = "sdx_decode_tokens",
            functionOffset = 0x24L,
            file = "/data/app/libsdx_llm.so",
            fileMapOffset = 0x1000L,
            buildId = "sdx-build-id"
        )
        val heap = message(
            varintField(1, 0xfeedbeefL),
            varintField(2, 256L),
            varintField(3, 40L),
            messageField(4, allocationFrame),
            varintField(5, 41L),
            messageField(6, deallocationFrame)
        )
        val memoryError = message(
            varintField(1, 1L),
            varintField(2, 1L),
            messageField(3, heap)
        )
        val cause = message(
            stringField(1, "Shape buffer was accessed after release"),
            messageField(2, memoryError)
        )
        val signal = message(
            varintField(1, 11L),
            stringField(2, "SIGSEGV"),
            varintField(3, 1L),
            stringField(4, "SEGV_MAPERR"),
            varintField(8, 1L),
            varintField(9, 0xfeedbeefL)
        )
        val register = message(stringField(1, "x0"), varintField(2, 0xfeedbeefL))
        val thread = message(
            varintField(1, 43L),
            stringField(2, "sdx-model-owner"),
            messageField(3, register),
            messageField(4, crashingFrame),
            stringField(7, "tagged address control was enabled")
        )
        val threadEntry = message(varintField(1, 43L), messageField(2, thread))
        val logMessage = message(
            stringField(1, "08-04 10:11:12.345"),
            varintField(2, 42L),
            varintField(3, 43L),
            varintField(4, 6L),
            stringField(5, "SDX"),
            stringField(6, "decode entered native generation")
        )
        val logBuffer = message(stringField(1, "crash"), messageField(2, logMessage))
        val crashDetail = message(
            bytesField(1, "allocator".toByteArray(StandardCharsets.UTF_8)),
            bytesField(2, byteArrayOf(0x01, 0x02, 0x03))
        )
        val tombstone = message(
            varintField(1, 1L),
            stringField(2, "google/tensor/pixel"),
            stringField(4, "2026-08-04 10:11:12+0900"),
            varintField(5, 42L),
            varintField(6, 43L),
            varintField(7, 10001L),
            stringField(9, "ai.kompile.chat.local:sdx_model_runtime"),
            messageField(10, signal),
            stringField(14, "Scudo ERROR: invalid chunk state when deallocating address"),
            messageField(15, cause),
            messageField(16, threadEntry),
            messageField(18, logBuffer),
            varintField(20, 17L),
            messageField(21, crashDetail),
            varintField(22, 16_384L),
            varintField(23, 1L)
        )

        val rendered = AndroidNativeTombstone.render(tombstone)

        assertTrue(rendered.contains("Architecture: ARM64"))
        assertTrue(rendered.contains("SIGSEGV (11)"))
        assertTrue(rendered.contains("SEGV_MAPERR (1)"))
        assertTrue(rendered.contains("fault address 0xfeedbeef"))
        assertTrue(rendered.contains("Scudo ERROR: invalid chunk state"))
        assertTrue(rendered.contains("Shape buffer was accessed after release"))
        assertTrue(rendered.contains("[Scudo USE_AFTER_FREE]"))
        assertTrue(rendered.contains("allocateShapeBuffer"))
        assertTrue(rendered.contains("releaseShapeBuffer"))
        assertTrue(rendered.contains("Crashing thread 43 (sdx-model-owner)"))
        assertTrue(rendered.contains("x0=0xfeedbeef"))
        assertTrue(rendered.contains("sdx_decode_tokens+0x24"))
        assertTrue(rendered.contains("libsdx_llm.so+0x1000"))
        assertTrue(rendered.contains("BuildId: sdx-build-id"))
        assertTrue(rendered.contains("decode entered native generation"))
        assertTrue(rendered.contains("allocator: 3 bytes; prefix=010203"))
    }

    @Test
    fun malformedLengthIsAnExplicitParserFailureWithByteOffset() {
        val malformed = byteArrayOf(
            ((10 shl 3) or 2).toByte(),
            127,
            1
        )

        try {
            AndroidNativeTombstone.render(malformed)
            fail("Expected malformed tombstone to fail")
        } catch (failure: AndroidNativeTombstoneParseException) {
            assertTrue(failure.message.orEmpty().contains("requires 127 bytes"))
            assertTrue(failure.message.orEmpty().contains("protobuf byte offset"))
        }
    }

    private fun frame(
        relativePc: Long,
        pc: Long = 0L,
        sp: Long = 0L,
        function: String,
        functionOffset: Long = 0L,
        file: String,
        fileMapOffset: Long = 0L,
        buildId: String
    ): ByteArray = message(
        varintField(1, relativePc),
        varintField(2, pc),
        varintField(3, sp),
        stringField(4, function),
        varintField(5, functionOffset),
        stringField(6, file),
        varintField(7, fileMapOffset),
        stringField(8, buildId)
    )

    private fun varintField(fieldNumber: Int, value: Long): ByteArray = message(
        varint((fieldNumber.toLong() shl 3) or 0L),
        varint(value)
    )

    private fun stringField(fieldNumber: Int, value: String): ByteArray =
        bytesField(fieldNumber, value.toByteArray(StandardCharsets.UTF_8))

    private fun messageField(fieldNumber: Int, value: ByteArray): ByteArray =
        bytesField(fieldNumber, value)

    private fun bytesField(fieldNumber: Int, value: ByteArray): ByteArray = message(
        varint((fieldNumber.toLong() shl 3) or 2L),
        varint(value.size.toLong()),
        value
    )

    private fun varint(input: Long): ByteArray {
        var value = input
        val output = ByteArrayOutputStream()
        while (value and -128L != 0L) {
            output.write(((value and 0x7fL) or 0x80L).toInt())
            value = value ushr 7
        }
        output.write(value.toInt())
        return output.toByteArray()
    }

    private fun message(vararg fields: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        fields.forEach(output::write)
        return output.toByteArray()
    }
}
