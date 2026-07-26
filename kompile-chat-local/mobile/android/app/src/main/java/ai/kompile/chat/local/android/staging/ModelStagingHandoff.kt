package ai.kompile.chat.local.android.staging

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Opens the prepared-artifact download surface of a Kompile staging service.
 *
 * This handoff deliberately carries no Hugging Face repository, GGML/GGUF URL, or
 * conversion inputs. Staging is a source of already target-prepared SDZ/KProject
 * packages; raw Hugging Face acquisition is downloaded and executed by the app through SDX.
 */
object ModelStagingHandoff {
    enum class Artifact(val queryValue: String, val fileExtension: String) {
        MODEL("model", ".sdz"),
        PROJECT("kproject", ".kproject")
    }

    /**
     * Validates the persisted server base using the same policy as [build]. Blank is
     * optionally allowed so Settings can represent an unconfigured optional service.
     */
    fun baseUrlProblem(raw: String, allowBlank: Boolean): String? {
        val value = raw.trim()
        if (value.isEmpty()) {
            return if (allowBlank) null
            else "Configure a Kompile prepared-artifact server URL in Settings first."
        }
        if (value.any(Char::isWhitespace)) {
            return "The prepared-artifact URL must not contain spaces."
        }
        val uri = try {
            URI.create(value)
        } catch (_: IllegalArgumentException) {
            return "The Kompile artifact server URL is invalid."
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme.isNullOrBlank()) {
            return "The prepared-artifact URL must start with http:// or https://."
        }
        if (scheme != "http" && scheme != "https") {
            return "The prepared-artifact URL must use http or https, not $scheme."
        }
        if (uri.host.isNullOrBlank()) {
            return "The prepared-artifact URL needs a host, for example http://workstation:8090."
        }
        if (uri.rawUserInfo != null) {
            return "The prepared-artifact URL must not include credentials."
        }
        if (uri.rawQuery != null) {
            return "The prepared-artifact URL must not include query parameters."
        }
        if (uri.rawFragment != null) {
            return "The prepared-artifact URL must not include a fragment."
        }
        if (!isCanonicalPath(uri.rawPath)) {
            return "The prepared-artifact URL must not contain encoded path segments or path traversal."
        }
        return null
    }

    fun build(
        baseUrl: String,
        targetProfile: String,
        artifact: Artifact
    ): URI {
        val base = parseStagingBase(baseUrl)
        require(targetProfile.isNotBlank()) {
            "A target profile is required for prepared artifact downloads."
        }
        return URI.create(
            buildString {
                append(base.toASCIIString())
                append('?')
                append("target=")
                append(encodeComponent(targetProfile.trim()))
                append("&artifact=")
                append(artifact.queryValue)
            }
        )
    }

    private fun parseStagingBase(raw: String): URI {
        val value = raw.trim()
        val problem = baseUrlProblem(value, allowBlank = false)
        require(problem == null) { problem.orEmpty() }
        return canonicalDownloadRoute(URI.create(value))
    }

    private fun canonicalDownloadRoute(base: URI): URI {
        val root = base.path.orEmpty().trimEnd('/')
        val route = when {
            root.isEmpty() -> "/download"
            root.endsWith("/download") -> root
            else -> "$root/download"
        }
        return URI(
            base.scheme.lowercase(Locale.ROOT),
            null,
            base.host.lowercase(Locale.ROOT),
            base.port,
            route,
            null,
            null
        )
    }

    private fun isCanonicalPath(rawPath: String?): Boolean {
        if (rawPath == null || '%' in rawPath || '\\' in rawPath || "//" in rawPath) {
            return false
        }
        return rawPath.split('/').none { it == "." || it == ".." }
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
