package ai.kompile.chat.local.android.graph

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidNativeGraphBackendDeviceTest {

    @Test
    fun bundledFixtureOpensInAndroidGraphAotRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "graphs/fixture.kgraph").canonicalFile

        assertTrue("Bundled fixture is missing: $fixture", fixture.isFile)
        AndroidNativeGraphBackend.open(fixture.toPath()).use { graph ->
            val catalog = graph.catalogJson()
            assertTrue("Native graph tool catalog is empty", catalog.isNotBlank())
            assertTrue("Native graph tool catalog omitted graph_reasoning_query", catalog.contains("graph_reasoning_query"))
        }
    }
}
