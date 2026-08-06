package ai.kompile.chat.local.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bytedeco.javacpp.Pointer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidJavaCppMemoryPolicyInstrumentedTest {

    @Test
    fun applicationPolicyIsFrozenIntoJavaCppBeforeNativeRuntimeUse() {
        val limits = AndroidJavaCppMemoryPolicy.requireInstalled()
        val loadedMaxTrackedBytes = Pointer.maxBytes()
        val loadedMaxPhysicalBytes = Pointer.maxPhysicalBytes()

        limits.requireLoadedValues(loadedMaxTrackedBytes, loadedMaxPhysicalBytes)
        assertTrue(loadedMaxPhysicalBytes > loadedMaxTrackedBytes)
        assertTrue(loadedMaxPhysicalBytes > 4L * Runtime.getRuntime().maxMemory())
    }
}
