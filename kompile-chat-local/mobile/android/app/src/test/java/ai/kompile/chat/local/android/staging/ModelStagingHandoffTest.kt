package ai.kompile.chat.local.android.staging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStagingHandoffTest {

    @Test
    fun opensPreparedModelDownloadWithoutSourceFragment() {
        val uri = ModelStagingHandoff.build(
            "https://staging.example/import",
            "android-arm64-vulkan",
            ModelStagingHandoff.Artifact.MODEL
        )

        assertEquals("https", uri.scheme)
        assertEquals("staging.example", uri.host)
        assertEquals("/import/download", uri.path)
        assertEquals("target=android-arm64-vulkan&artifact=model", uri.rawQuery)
        assertNull(uri.fragment)
        assertEquals(".sdz", ModelStagingHandoff.Artifact.MODEL.fileExtension)
    }

    @Test
    fun opensPreparedProjectDownload() {
        val uri = ModelStagingHandoff.build(
            "http://workstation.local:8090/staging",
            "android-arm64-hexagon-htp",
            ModelStagingHandoff.Artifact.PROJECT
        )

        assertEquals("/staging/download", uri.path)
        assertTrue(uri.query.orEmpty().contains("artifact=kproject"))
        assertEquals(".kproject", ModelStagingHandoff.Artifact.PROJECT.fileExtension)
    }

    @Test
    fun canonicalizesDownloadRouteOnce() {
        mapOf(
            "https://staging.example" to "/download",
            "https://staging.example/" to "/download",
            "https://staging.example/staging" to "/staging/download",
            "https://staging.example/staging/" to "/staging/download",
            "https://staging.example/staging/download/" to "/staging/download"
        ).forEach { (base, expectedPath) ->
            assertEquals(
                expectedPath,
                ModelStagingHandoff.build(
                    base,
                    "android-arm64-vulkan",
                    ModelStagingHandoff.Artifact.MODEL
                ).path
            )
        }
    }

    @Test
    fun blankServerReportsPreparedArtifactConfiguration() {
        val failure = runCatching {
            ModelStagingHandoff.build(
                " ",
                "android-arm64-vulkan",
                ModelStagingHandoff.Artifact.MODEL
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("prepared-artifact server"))
    }

    @Test
    fun rejectsUnsafeServerUrlsAndBlankTargets() {
        listOf(
            "https://user:secret@staging.example/import",
            "https://staging.example/import#unexpected",
            "https://staging.example/import?token=secret",
            "https://staging.example/import?theme=dark%20mode",
            "https://staging.example/import?session_key=secret",
            "https://staging.example/import?access_token=secret",
            "https://staging.example/staging%2Fdownload",
            "file:///tmp/staging"
        ).forEach { value ->
            val failure = runCatching {
                ModelStagingHandoff.build(
                    value,
                    "android-arm64-vulkan",
                    ModelStagingHandoff.Artifact.MODEL
                )
            }.exceptionOrNull()
            assertTrue("Expected rejection for $value", failure != null)
        }

        val blankTarget = runCatching {
            ModelStagingHandoff.build(
                "https://staging.example",
                " ",
                ModelStagingHandoff.Artifact.MODEL
            )
        }.exceptionOrNull()
        assertTrue(blankTarget?.message.orEmpty().contains("target profile"))
    }
}
