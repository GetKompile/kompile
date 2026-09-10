package ai.kompile.chat.local.android.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class SdxHashingTest {

    @Test
    fun everyInputFormUsesTheSameLowercaseSha256Contract() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val path = Files.createTempFile("sdx-hashing-", ".txt")
        try {
            Files.write(path, bytes)
            assertEquals(expected, SdxHashing.sha256Hex(bytes))
            assertEquals(expected, SdxHashing.sha256Hex("abc"))
            assertEquals(expected, SdxHashing.sha256Hex(path))
            assertEquals(expected, SdxHashing.sha256Hex(path.toFile()))
            assertEquals(expected, SdxHashing.sha256Hex(ByteArrayInputStream(bytes)))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun emptyInputMatchesTheStandardDigest() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SdxHashing.sha256Hex(ByteArray(0)),
        )
    }
}
