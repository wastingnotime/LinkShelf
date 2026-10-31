package org.wastingnotime.myfeed.preview

import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/** Inert HTML parsing only. No scripts, frames, stylesheets, base tags, canonical URLs or oEmbed. */
data class Metadata(val title: String?, val description: String?, val siteName: String?, val imageUrl: String?) {
    val useful get() = title != null || description != null || siteName != null
}

object MetadataParser {
    fun parse(download: Download): Metadata {
        if (download.bytes.size > SafeHttp.HTML_BYTES) throw UnsafePreview("HTML too large")
        val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
            .find(download.contentType)?.groupValues?.get(1)?.let {
                runCatching { Charset.forName(it).name() }.getOrNull()
            }
        val doc = Jsoup.parse(ByteArrayInputStream(download.bytes), charset, download.url.toString())
        val tags = doc.select("meta[property], meta[name]")
        fun meta(key: String, max: Int): String? = tags.firstNotNullOfOrNull {
            if (it.attr("property").equals(key, true) || it.attr("name").equals(key, true)) clean(it.attr("content"), max) else null
        }
        // Ignore HTML <base>; resolve only against the last validated response URL.
        // Reject oversized references rather than fetching a truncated, different URL.
        val imageUrl = listOf("og:image", "twitter:image", "twitter:image:src").firstNotNullOfOrNull { key ->
            meta(key, 8193)?.takeIf { it.length <= 8192 }?.let {
                runCatching { SafeHttp.resolveChecked(download.url, it).toString() }.getOrNull()
            }
        }
        return Metadata(meta("og:title", 300) ?: meta("twitter:title", 300) ?: clean(doc.title(), 300),
            meta("og:description", 1000) ?: meta("twitter:description", 1000) ?: meta("description", 1000),
            meta("og:site_name", 150), imageUrl)
    }

    private fun clean(value: String, max: Int): String? = value
        .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim().take(max).ifBlank { null }
}
