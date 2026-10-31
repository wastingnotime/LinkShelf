package org.wastingnotime.myfeed.preview

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.wastingnotime.myfeed.data.UrlCapture
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.*
import javax.net.SocketFactory

class UnsafePreview(message: String) : IOException(message)
class RetryablePreview(message: String) : IOException(message)

data class Download(val url: HttpUrl, val bytes: ByteArray, val contentType: String)

/** Deny special-purpose IPv4; allow only IPv6 global unicast outside special registries.
 * Conservative: even globally reachable exceptions inside special blocks are denied. */
object PublicAddress {
    private val deniedV4 = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24",
        "192.31.196.0/24", "192.52.193.0/24", "192.175.48.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3")
    private val deniedV6 = listOf("2001::/23", "2001:db8::/32", "2002::/16", "2620:4f:8000::/48", "3fff::/20")
    private fun inRange(bytes: ByteArray, cidr: String): Boolean {
        val (ip, bits) = cidr.split('/')
        val network = InetAddress.getByName(ip).address // constants only, never hostnames
        if (bytes.size != network.size) return false
        val prefix = bits.toInt()
        return (0 until prefix).all { bit ->
            (bytes[bit / 8].toInt() xor network[bit / 8].toInt()) and (1 shl (7 - bit % 8)) == 0
        }
    }
    fun allowed(address: InetAddress): Boolean {
        val bytes = address.address
        return when (bytes.size) {
            4 -> deniedV4.none { inRange(bytes, it) }
            16 -> inRange(bytes, "2000::/3") && deniedV6.none { inRange(bytes, it) }
            else -> false
        }
    }
}

/** The exact addresses returned here are used for socket connection by OkHttp (no second lookup).
 * Reject the entire answer if any address is unsafe, including mixed A/AAAA responses. */
internal class PublicDns(private val resolver: Dns = Dns.SYSTEM, private val timeoutMs: Long = 3_000) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val future = try { executor.submit<List<InetAddress>> { resolver.lookup(hostname) } }
        catch (_: RejectedExecutionException) { throw RetryablePreview("DNS busy") }
        val addresses = try { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { throw RetryablePreview("DNS timeout") }
        catch (e: ExecutionException) { throw IOException("DNS failed", e.cause) }
        finally { future.cancel(true) }
        if (addresses.isEmpty() || addresses.any { !PublicAddress.allowed(it) }) throw UnsafePreview("Non-public address")
        return addresses
    }
    companion object {
        // A stalled platform resolver cannot create an unbounded number of threads or queued lookups.
        private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(8), { task -> Thread(task, "preview-dns").apply { isDaemon = true } })
    }
}

/** No proxy, cookies, auth, auto redirects, connection reuse/coalescing, or remote execution.
 * Metadata and image downloads share this transport and all of its limits. */
class SafeHttp internal constructor(
    resolver: Dns = Dns.SYSTEM,
    socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val callTimeoutMs: Long = 15_000,
) {
    private val client = OkHttpClient.Builder()
        .dns(PublicDns(resolver)).socketFactory(socketFactory).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .protocols(listOf(Protocol.HTTP_1_1)).connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()

    fun get(raw: String, image: Boolean = false): Download {
        var url = checkedUrl(raw)
        val limit = if (image) IMAGE_BYTES else HTML_BYTES
        repeat(MAX_REDIRECTS + 1) { hop ->
            val request = Request.Builder().url(url)
                .header("User-Agent", "my-feed/0.1 (link preview)")
                // Refuse compressed responses: cap wire bytes and decoded bytes identically.
                .header("Accept-Encoding", "identity")
                .header("Accept", if (image) "image/jpeg,image/png,image/webp" else "text/html,application/xhtml+xml")
                .build()
            val call = client.newCall(request)
            val response = call.execute()
            try {
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    if (hop == MAX_REDIRECTS) throw UnsafePreview("Too many redirects")
                    val location = response.header("Location") ?: throw UnsafePreview("Missing redirect target")
                    // Check raw credentials before URL resolution can normalize them away.
                    url = redirectUrl(url, location)
                } else {
                    if (response.code == 408 || response.code == 429 || response.code in 500..599)
                        throw RetryablePreview("Transient HTTP ${response.code}")
                    if (!response.isSuccessful) throw UnsafePreview("HTTP ${response.code}")
                    if (response.header("Content-Encoding")?.lowercase()?.let { it != "identity" } == true)
                        throw UnsafePreview("Compressed response")
                    val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    if (if (image) type !in setOf("image/jpeg", "image/png", "image/webp")
                        else type !in setOf("text/html", "application/xhtml+xml")) throw UnsafePreview("Unsupported content type")
                    val body = response.body ?: throw UnsafePreview("Empty response")
                    if (body.contentLength() > limit) throw UnsafePreview("Response too large")
                    val bytes = ByteArrayOutputStream()
                    val input = body.byteStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - bytes.size()))
                        if (count == -1) break
                        bytes.write(buffer, 0, count)
                        if (bytes.size() > limit) throw UnsafePreview("Response too large")
                    }
                    return Download(url, bytes.toByteArray(), response.header("Content-Type").orEmpty())
                }
            } finally {
                // Closing an unread OkHttp body can drain it for connection reuse. Cancel first
                // so rejected/redirect responses cannot bypass the byte cap during close.
                call.cancel()
                response.close()
            }
        }
        throw UnsafePreview("Redirect limit")
    }

    companion object {
        const val HTML_BYTES = 512 * 1024
        const val IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        fun redirectUrl(base: HttpUrl, reference: String): HttpUrl {
            val target = resolveChecked(base, reference)
            if (base.isHttps && !target.isHttps) throw UnsafePreview("HTTPS downgrade")
            return target
        }
        fun resolveChecked(base: HttpUrl, reference: String): HttpUrl {
            if (reference.length > 8192 || reference.any { it.isWhitespace() || it.isISOControl() || it == '\\' })
                throw UnsafePreview("Invalid reference")
            val uri = try { java.net.URI(reference) } catch (_: Exception) { throw UnsafePreview("Invalid reference") }
            if (uri.rawAuthority?.contains('@') == true) throw UnsafePreview("Credentials")
            return checkedUrl((base.resolve(reference) ?: throw UnsafePreview("Invalid reference")).toString())
        }
        fun checkedUrl(raw: String): HttpUrl {
            val normalized = UrlCapture.normalize(raw) ?: throw UnsafePreview("Invalid HTTP(S) URL")
            val url = normalized.canonical.toHttpUrlOrNull() ?: throw UnsafePreview("Invalid URL")
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw UnsafePreview("Credentials")
            val host = url.host.trimEnd('.').lowercase()
            if (!host.contains('.') && !host.contains(':') || listOf("localhost", "local", "internal", "lan", "home", "onion", "test", "invalid")
                    .any { host == it || host.endsWith(".$it") } || '%' in host)
                throw UnsafePreview("Local hostname")
            // Numeric addresses must also be checked here: OkHttp may bypass DNS for literals.
            if (':' in host || host.all { it.isDigit() || it == '.' }) {
                val address = try {
                    if (':' in host) InetAddress.getByName(host) // colon syntax is parsed as IPv6, never DNS
                    else {
                        val parts = host.split('.')
                        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
                                (it.length > 1 && it.startsWith('0')) || it.toInt() !in 0..255 })
                            throw UnsafePreview("Invalid IPv4 literal")
                        InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
                    }
                } catch (_: Exception) { throw UnsafePreview("Invalid IP") }
                if (!PublicAddress.allowed(address)) throw UnsafePreview("Non-public IP")
            }
            return url
        }
    }
}
