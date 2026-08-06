package ai.kompile.chat.local.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SdxRawGgufContractTest {

    @Test
    fun verifiedPreparationOptionsCarryExactDownloaderAttestation() {
        val sha256 = "a".repeat(64)

        assertEquals(
            "{\"verifiedSourceSha256\":\"$sha256\",\"verifiedSourceBytes\":987654321}",
            SdxRawGgufContract.preparationOptionsJson(sha256.uppercase(), 987_654_321L)
        )
        assertEquals("{}", SdxRawGgufContract.preparationOptionsJson(null, null))
    }

    @Test
    fun preparationOptionsRejectPartialOrInvalidAttestation() {
        assertThrows(IllegalArgumentException::class.java) {
            SdxRawGgufContract.preparationOptionsJson("bad-sha", 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SdxRawGgufContract.preparationOptionsJson("a".repeat(64), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SdxRawGgufContract.preparationOptionsJson(null, 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SdxRawGgufContract.preparationOptionsJson("a".repeat(64), 0L)
        }
    }
}
