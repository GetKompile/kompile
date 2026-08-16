package ai.kompile.chat.local.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidJavaCppMemoryPolicyTest {

    @Test
    fun pixelClassDeviceUsesPhysicalRamInsteadOfTheSmallArtHeap() {
        val limits = calculateAndroidJavaCppMemoryLimits(
            systemTotalBytes = 8L * GIB,
            systemLowMemoryThresholdBytes = 256L * MIB,
            javaHeapMaxBytes = 256L * MIB
        )

        assertEquals(5_153_960_755L, limits.maxTrackedBytes)
        assertEquals(7_730_941_133L, limits.maxPhysicalBytes)
        assertTrue(limits.maxPhysicalBytes > 2_077L * MIB)
        assertTrue(limits.maxPhysicalBytes > 4L * limits.javaHeapMaxBytes)
    }

    @Test
    fun androidLowMemoryThresholdCanReserveMoreThanTenPercent() {
        val limits = calculateAndroidJavaCppMemoryLimits(
            systemTotalBytes = 4L * GIB,
            systemLowMemoryThresholdBytes = 768L * MIB,
            javaHeapMaxBytes = 256L * MIB
        )

        assertEquals(2_576_980_377L, limits.maxTrackedBytes)
        assertEquals(2_684_354_560L, limits.maxPhysicalBytes)
        assertEquals(1_610_612_736L, limits.systemTotalBytes - limits.maxPhysicalBytes)
    }

    @Test
    fun loadedJavaCppValuesMustExactlyMatchTheEarlyPolicy() {
        val limits = calculateAndroidJavaCppMemoryLimits(
            systemTotalBytes = 8L * GIB,
            systemLowMemoryThresholdBytes = 256L * MIB,
            javaHeapMaxBytes = 256L * MIB
        )
        limits.requireLoadedValues(limits.maxTrackedBytes, limits.maxPhysicalBytes)

        val failure = runCatching {
            limits.requireLoadedValues(
                actualMaxTrackedBytes = 256L * MIB,
                actualMaxPhysicalBytes = 1L * GIB
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("initialized before"))
    }

    @Test
    fun applicationInstallsPolicyBeforeAnyAndroidOrJavaCppBootstrap() {
        val application = File(
            "src/main/java/ai/kompile/chat/local/android/KompileChatApplication.kt"
        ).readText()
        val install = application.indexOf("AndroidJavaCppMemoryPolicy.install(base)")
        val attach = application.indexOf("super.attachBaseContext(base)")
        assertTrue(install >= 0)
        assertTrue(attach > install)

        val policy = File(
            "src/main/java/ai/kompile/chat/local/android/AndroidJavaCppMemoryPolicy.kt"
        ).readText()
        assertFalse(policy.contains("import org.bytedeco.javacpp.Pointer"))
        assertFalse(policy.contains("Pointer.max"))

        val runtime = File(
            "src/sdx/java/ai/kompile/chat/local/android/model/SdxPlatformChatSession.kt"
        ).readText()
        val configureLibrary = runtime.indexOf("SdxAndroidLlmLibrary.configure(")
        val loadTransport = runtime.indexOf("SdxAndroidLlmLibrary.bind(")
        val createRuntime = runtime.indexOf("abi.sdxLlmCreateRuntime()")
        assertTrue(configureLibrary >= 0)
        assertTrue(loadTransport > configureLibrary)
        assertTrue(createRuntime > loadTransport)
        assertFalse(runtime.contains("import org.bytedeco.javacpp.Pointer"))
        assertTrue(runtime.contains("SdxPointerByReference()"))
        assertFalse(runtime.contains("Pointer.maxBytes()"))
        assertFalse(runtime.contains("Pointer.maxPhysicalBytes()"))
    }

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}
