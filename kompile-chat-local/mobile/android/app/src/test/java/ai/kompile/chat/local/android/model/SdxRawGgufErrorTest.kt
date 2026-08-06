package ai.kompile.chat.local.android.model

import ai.kompile.chat.local.ChatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SdxRawGgufErrorTest {

    @Test
    fun completeNativeStackTraceIsReadWithoutTruncation() {
        val expected = "java.lang.NoSuchFieldError: st_birthtime_nsec\n" +
            "\tat sun.nio.fs.UnixNativeDispatcher.init(Native Method)\n".repeat(80) +
            "Caused by: java.lang.IllegalStateException: complete cause\n"
        val utf8 = expected.toByteArray(StandardCharsets.UTF_8)
        var calls = 0

        val actual = readCompleteSdxLastError { buffer ->
            calls += 1
            val copied = minOf(utf8.size, buffer.size - 1)
            utf8.copyInto(buffer, endIndex = copied)
            buffer[copied] = 0
            utf8.size
        }

        assertEquals(expected, actual)
        assertEquals(2, calls)
        assertTrue(utf8.size > 2048)
    }

    @Test
    fun nativeErrorLengthChangeFailsExplicitly() {
        var calls = 0

        val failure = assertThrows(ChatException::class.java) {
            readCompleteSdxLastError { buffer ->
                calls += 1
                buffer[0] = 'x'.code.toByte()
                if (calls == 1) 3000 else 4000
            }
        }

        assertTrue(failure.message.orEmpty().contains("required=3000 confirmed=4000 capacity=3001"))
        assertEquals(2, calls)
    }
}
