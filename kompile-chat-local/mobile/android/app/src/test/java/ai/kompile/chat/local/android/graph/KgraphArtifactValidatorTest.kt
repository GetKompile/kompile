package ai.kompile.chat.local.android.graph

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KgraphArtifactValidatorTest {

    @Test
    fun acceptsCanonicalContainerWithoutLoadingNativeRuntime() {
        val graph = writeGraph(
            mapOf(
                "manifest.json" to """{"format":"kompile-graph","formatVersion":1}""",
                "entities.jsonl" to "",
                "relations.jsonl" to ""
            )
        )

        try {
            KgraphArtifactValidator.validate(graph)
        } finally {
            Files.deleteIfExists(graph)
        }
    }

    @Test
    fun rejectsContainerWithoutManifest() {
        val graph = writeGraph(
            mapOf(
                "entities.jsonl" to "",
                "relations.jsonl" to ""
            )
        )

        try {
            val failure = expectIOException { KgraphArtifactValidator.validate(graph) }
            assertTrue(failure.message.orEmpty().contains("manifest.json"))
        } finally {
            Files.deleteIfExists(graph)
        }
    }

    @Test
    fun rejectsUnsupportedManifestBeforeNativeActivation() {
        val graph = writeGraph(
            mapOf(
                "manifest.json" to """{"format":"other","formatVersion":7}""",
                "entities.jsonl" to "",
                "relations.jsonl" to ""
            )
        )

        try {
            val failure = expectIOException { KgraphArtifactValidator.validate(graph) }
            assertTrue(failure.message.orEmpty().contains("manifest format"))
        } finally {
            Files.deleteIfExists(graph)
        }
    }

    @Test
    fun rejectsMalformedManifestInsteadOfSubstringMatching() {
        val graph = writeGraph(
            mapOf(
                "manifest.json" to """{"format":"kompile-graph","formatVersion":1,}""",
                "entities.jsonl" to "",
                "relations.jsonl" to ""
            )
        )

        try {
            val failure = expectIOException { KgraphArtifactValidator.validate(graph) }
            assertTrue(failure.message.orEmpty().contains("malformed manifest"))
        } finally {
            Files.deleteIfExists(graph)
        }
    }

    @Test
    fun rejectsManifestFieldsNestedOutsideTheRoot() {
        val graph = writeGraph(
            mapOf(
                "manifest.json" to """{"nested":{"format":"kompile-graph","formatVersion":1}}""",
                "entities.jsonl" to "",
                "relations.jsonl" to ""
            )
        )

        try {
            val failure = expectIOException { KgraphArtifactValidator.validate(graph) }
            assertTrue(failure.message.orEmpty().contains("manifest format"))
        } finally {
            Files.deleteIfExists(graph)
        }
    }

    @Test
    fun rejectsUnsafeEntryNames() {
        val graph = writeGraph(
            mapOf(
                "manifest.json" to """{"format":"kompile-graph","formatVersion":1}""",
                "entities.jsonl" to "",
                "relations.jsonl" to "",
                "../payload" to "unsafe"
            )
        )

        try {
            val failure = expectIOException { KgraphArtifactValidator.validate(graph) }
            assertTrue(failure.message.orEmpty().contains("unsafe ZIP entry"))
        } finally {
            Files.deleteIfExists(graph)
        }
    }

    @Test
    fun rejectsExcessiveEntryCounts() {
        val entries = linkedMapOf(
            "manifest.json" to """{"format":"kompile-graph","formatVersion":1}""",
            "entities.jsonl" to "",
            "relations.jsonl" to ""
        )
        repeat(KgraphArtifactValidator.MAX_ENTRIES) { index ->
            entries["vectors/layer-$index.kvec"] = ""
        }
        val graph = writeGraph(entries)

        try {
            val failure = expectIOException { KgraphArtifactValidator.validate(graph) }
            assertTrue(failure.message.orEmpty().contains("more than"))
        } finally {
            Files.deleteIfExists(graph)
        }
    }

    private fun writeGraph(entries: Map<String, String>): Path {
        val path = Files.createTempFile("kgraph-validator-", ".kgraph")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return path
    }

    private fun expectIOException(block: () -> Unit): IOException {
        try {
            block()
            fail("Expected IOException")
        } catch (failure: IOException) {
            return failure
        }
        throw AssertionError("unreachable")
    }
}
