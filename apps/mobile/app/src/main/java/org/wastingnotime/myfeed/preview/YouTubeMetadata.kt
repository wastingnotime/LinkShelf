package org.wastingnotime.myfeed.preview

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.IOException

/** Fixed first-party endpoint; ignore executable oEmbed HTML and never discover arbitrary endpoints. */
object YouTubeMetadata {
    fun endpoint(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in listOf(80, 443)) return null
        val parts = url.pathSegments
        val id = when (url.host) {
            "youtu.be" -> parts.singleOrNull()
            "youtube.com", "www.youtube.com", "m.youtube.com" -> when {
                url.encodedPath == "/watch" -> url.queryParameter("v")
                parts.size == 2 && parts[0] in listOf("shorts", "live", "embed") -> parts[1]
                else -> null
            }
            else -> null
        } ?: return null
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        return "https://www.youtube.com/oembed".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("url", "https://www.youtube.com/watch?v=$id")
            .addQueryParameter("format", "json").build().toString()
    }

    fun isEndpoint(url: HttpUrl) = url.isHttps && url.host == "www.youtube.com" && url.port == 443 && url.encodedPath == "/oembed"

    fun parse(download: Download): Metadata {
        if (!isEndpoint(download.url) || download.bytes.size > SafeHttp.JSON_BYTES) throw UnsafePreview("Invalid oEmbed response")
        val json = JSONObject(download.bytes.toString(Charsets.UTF_8))
        require(json.optString("type") == "video" && json.optString("version") == "1.0")
        fun text(key: String, limit: Int) = (json.opt(key) as? String)?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")?.trim()?.take(limit)?.takeIf { it.isNotBlank() }
        val title = text("title", 300) ?: throw UnsafePreview("No video title available")
        val image = (json.opt("thumbnail_url") as? String)?.takeIf { it.length <= 8192 }?.let {
            runCatching { SafeHttp.resolveChecked(download.url, it).toString() }.getOrNull()
        }
        return Metadata(title, null, "YouTube", image)
    }

    fun fetch(raw: String, download: (String, Boolean) -> Download): Metadata {
        endpoint(raw)?.let { endpoint ->
            try { return parse(download(endpoint, false)) }
            catch (_: IOException) { /* Public HTML is the bounded fallback. */ }
            catch (_: org.json.JSONException) { }
            catch (_: IllegalArgumentException) { }
        }
        return MetadataParser.parse(download(raw, false))
    }
}
