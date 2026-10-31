package org.wastingnotime.myfeed.data

import java.net.IDN
import java.net.URI
import java.util.Locale

data class SharedUrl(val original: String, val canonical: String, val host: String)

/** First valid HTTP(S) URL in bounded shared text. Identity never follows redirects. */
object UrlCapture {
    private val urls = Regex("https?://[^\\s<>\\\"'\\u0000-\\u001f]+", RegexOption.IGNORE_CASE)

    fun extract(text: String): SharedUrl? = urls.findAll(text.take(100_000)).mapNotNull {
        if (it.value.length > 8192) return@mapNotNull null
        var candidate = it.value.trimEnd('.', ',', ';')
        for ((close, open) in listOf(')' to '(', ']' to '[', '}' to '{')) {
            val excess = candidate.count { c -> c == close } - candidate.count { c -> c == open }
            if (excess > 0) candidate = candidate.dropLast(minOf(excess, candidate.takeLastWhile { c -> c == close }.length))
        }
        normalize(candidate)
    }.firstOrNull()

    fun normalize(url: String): SharedUrl? = runCatching {
        require(url.length <= 8192 && url.none { it.isWhitespace() || it.isISOControl() })
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https")
        val authority = requireNotNull(uri.rawAuthority)
        require(!authority.contains('@') && !authority.contains('\\'))
        // URI.host validates ASCII/IPv6 hosts; IDN handles international domain names.
        val asciiAuthority = if (authority.startsWith("[")) authority else {
            val parts = authority.split(':')
            require(parts.size <= 2 && parts[0].isNotEmpty())
            IDN.toASCII(parts[0], IDN.USE_STD3_ASCII_RULES) +
                if (parts.size == 2) ":${parts[1]}" else ""
        }
        val parsed = URI("$scheme://$asciiAuthority")
        val host = requireNotNull(parsed.host).lowercase(Locale.ROOT)
        val port = parsed.port
        require(port == -1 || port in 1..65535)
        require(!authority.endsWith(':'))
        val portSuffix = if (port == -1 || (scheme == "http" && port == 80) ||
            (scheme == "https" && port == 443)) "" else ":$port"
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val canonical = URI("$scheme://$host$portSuffix$path$query").toASCIIString()
        SharedUrl(url, canonical, host)
    }.getOrNull()
}
