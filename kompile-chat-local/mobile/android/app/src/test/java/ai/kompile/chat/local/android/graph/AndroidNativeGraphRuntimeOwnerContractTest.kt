package ai.kompile.chat.local.android.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidNativeGraphRuntimeOwnerContractTest {

    @Test
    fun graphSessionsReuseOneProcessLifetimeIsolate() {
        val source = File(
            "src/main/java/ai/kompile/chat/local/android/graph/AndroidNativeGraphBackend.java"
        ).readText()

        assertTrue(source.contains("private static final GraphRuntimeOwner RUNTIME"))
        assertTrue(source.contains("private static final class GraphRuntimeOwner"))
        assertTrue(source.contains("RUNTIME.open(kgraphPath)"))
        assertTrue(source.contains("RUNTIME.close(closingSession)"))
        assertTrue(source.contains("KompileGraphNative.kgr_close(requireIsolateThread(), session)"))
        assertTrue(source.contains("KompileGraphNative.kgr_last_error(thread)"))
        assertTrue(source.contains("Native graph session could not open: \" + detail"))
        assertTrue(source.contains("private IOException isolateInitializationFailure"))
        assertTrue(source.contains("isolateThread = created;"))
        assertTrue(
            source.indexOf("isolateThread = created;") <
                source.indexOf("KompileGraphNative.kgr_abi_version(created)")
        )
        assertTrue(source.contains("while (true)"))
        assertTrue(source.contains("Thread.currentThread().interrupt()"))
        assertEquals(
            "the Android image permits exactly one isolate bootstrap site",
            1,
            Regex("KompileGraphNative\\.kgr_create_isolate\\(\\)").findAll(source).count()
        )
        assertFalse(
            "closing a graph session must not tear down the process-lifetime Graal isolate",
            source.contains("KompileGraphNative.kgr_tear_down_isolate")
        )
        assertFalse(
            "model or graph churn must not stop the process-lifetime owner thread",
            source.contains("executor.shutdownNow()")
        )
    }
}
