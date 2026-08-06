package ai.kompile.chat.local.android.model

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SdxModelPreparationWireInstrumentedTest {

    @Test
    fun prepareRequestRoundTripsWithBootClassLoaderUsingFrameworkScalarsOnly() {
        val request = buildSdxModelPreparationRequest(
            modelPath = "/data/user/0/example/files/model.gguf",
            verifiedSourceSha256 = "a".repeat(64),
            verifiedSourceBytes = 1_516_744_736L,
            operationAttemptId = "attempt-id"
        )

        val decoded = parcelRoundTripWithBootClassLoader(request)
        requireFrameworkOnlySdxWireBundle(decoded, "round-tripped request")

        assertEquals("/data/user/0/example/files/model.gguf", decoded.getString("model_path"))
        assertEquals("a".repeat(64), decoded.getString("verified_sha256"))
        assertEquals(1_516_744_736L, decoded.getLong("verified_bytes"))
        assertEquals("attempt-id", decoded.getString("operation_attempt_id"))
        assertTrue(
            decoded.keySet().all { key ->
                @Suppress("DEPRECATION")
                val value = decoded.get(key)
                value == null || isFrameworkOnlySdxWireValueClass(value.javaClass)
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun parcelRoundTripWithBootClassLoader(source: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(source)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(null)).also { it.keySet() }
        } finally {
            parcel.recycle()
        }
    }
}
