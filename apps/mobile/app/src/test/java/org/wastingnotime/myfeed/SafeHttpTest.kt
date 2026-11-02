package org.wastingnotime.myfeed

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.myfeed.preview.*
import java.net.*
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class SafeHttpTest {
    private val server = MockWebServer()
    private val publicIp = InetAddress.getByName("93.184.216.34")
    private var connections = 0
    private var receivedBytes = 0
    // Test-only socket routing: production DNS policy still sees/validates public addresses.
    // No unsafe-network switch exists in the application.
    private val sockets = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun getInputStream() = object : java.io.FilterInputStream(super.getInputStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, length).also { if (it > 0) receivedBytes += it }
            }
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                connections++
                assertEquals(publicIp, (endpoint as InetSocketAddress).address)
                super.connect(InetSocketAddress("127.0.0.1", server.port), timeout)
            }
        }
        override fun createSocket(h: String, p: Int): Socket = error("unused")
        override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("unused")
        override fun createSocket(h: InetAddress, p: Int): Socket = error("unused")
        override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("unused")
    }
    private fun dns(block: () -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String) = block()
    }
    private fun http(dns: Dns = dns { listOf(publicIp) }, timeout: Long = 15_000) = SafeHttp(dns, sockets, timeout)
    private fun url(path: String = "/") = "http://public.example.org:${server.port}$path"
    private fun html(body: String = "<title>Article</title>") = MockResponse().setHeader("Content-Type", "text/html").setBody(body)
    private fun blocked(block: () -> Unit) {
        try { block(); fail("Expected unsafe preview") } catch (_: UnsafePreview) { }
    }
    @After fun close() { server.shutdown() }

    @Test fun rejectsSpecialPurposeIpv4AndIpv6IncludingMappedAndTransitionAddresses() {
        val denied = listOf("0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "192.0.0.9", "192.0.2.1", "192.88.99.1", "192.168.1.1", "198.18.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fc00::1",
            "fe80::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2001::1", "2001:db8::1",
            "2002:7f00:1::1", "3fff::1", "2620:4f:8000::1")
        denied.forEach { assertFalse(it, PublicAddress.allowed(InetAddress.getByName(it))) }
        listOf("1.1.1.1", "8.8.8.8", "93.184.216.34", "2606:4700:4700::1111", "2001:4860:4860::8888")
            .forEach { assertTrue(it, PublicAddress.allowed(InetAddress.getByName(it))) }
    }

    @Test fun blocksCredentialsLocalNamesLiteralsOtherSchemesAndMalformedUrlsBeforeConnecting() {
        listOf("http://localhost/", "http://foo.local/", "http://printer/", "http://foo.internal/",
            "http://127.0.0.1/", "http://127.1/", "http://2130706433/", "http://999.999.999.999/", "http://0177.0.0.1/", "http://[::1]/",
            "http://[::ffff:127.0.0.1]/", "http://user:pass@example.com/", "http://@example.com/",
            "file:///etc/passwd", "javascript:alert(1)", "data:image/png,abc", "ftp://example.com/",
            "https://example.com:0/", "http://example.com/\\evil", "http://[fe80::1%25eth0]/")
            .forEach { raw -> blocked { http().get(raw) } }
        assertEquals(0, connections)
    }

    @Test fun mixedDnsAnswerRejectedBeforeSocketAndDnsTimeoutIsBounded() {
        blocked { http(dns { listOf(publicIp, InetAddress.getByName("10.0.0.2")) }).get(url()) }
        assertEquals(0, connections)
        val start = System.nanoTime()
        try { PublicDns(dns { Thread.sleep(200); listOf(publicIp) }, 25).lookup("example.com"); fail() }
        catch (_: RetryablePreview) { }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500)
    }

    @Test fun followsBoundedRelativeRedirectsWithFreshDnsAndNoAmbientHeaders() {
        var lookups = 0
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        server.enqueue(html())
        val result = http(dns { lookups++; listOf(publicIp) }).get(url())
        assertEquals("/next", result.url.encodedPath)
        assertEquals(2, lookups)
        assertEquals(2, connections)
        repeat(2) {
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Referer")); assertEquals("identity", request.getHeader("Accept-Encoding"))
        }
    }

    @Test fun redirectToPrivateOrCredentialTargetNeverConnectsThere() {
        for (target in listOf("http://127.0.0.1/secret", "http://user:password@example.com/", "file:///secret", "http://@example.com/")) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
            blocked { http().get(url()) }
        }
        assertEquals(4, connections)
    }

    @Test fun rebindingOnSameHostRedirectIsRejected() {
        var lookups = 0
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/again"))
        blocked { http(dns { if (lookups++ == 0) listOf(publicIp) else listOf(InetAddress.getByName("127.0.0.1")) }).get(url()) }
        assertEquals(2, lookups); assertEquals(1, connections)
    }

    @Test fun httpsRedirectCannotDowngradeOrNormalizeAwayCredentials() {
        val base = "https://example.com/story".toHttpUrl()
        blocked { SafeHttp.redirectUrl(base, "http://other.example.com/") }
        blocked { SafeHttp.redirectUrl(base, "//@example.com/") }
        assertEquals("https://example.com/next", SafeHttp.redirectUrl(base, "/next").toString())
    }

    @Test fun redirectLoopStopsAtFourRequests() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
        blocked { http().get(url()) }; assertEquals(4, server.requestCount)
    }

    @Test fun boundsHtmlPrefixesRejectsOversizeImagesAndCompressedOrWrongTypes() {
        for (image in listOf(false, true)) {
            val type = if (image) "image/png" else "text/html"
            val limit = if (image) SafeHttp.IMAGE_BYTES else SafeHttp.HTML_BYTES
            server.enqueue(MockResponse().setHeader("Content-Type", type).setBody("x".repeat(limit + 1)))
            if (image) blocked { http().get(url(), true) } else assertEquals(limit, http().get(url()).bytes.size)
            server.enqueue(MockResponse().setHeader("Content-Type", type).setChunkedBody("x".repeat(limit + 1), 4096))
            if (image) blocked { http().get(url(), true) } else assertEquals(limit, http().get(url()).bytes.size)
            server.enqueue(MockResponse().setHeader("Content-Type", type).setBody("x".repeat(limit)))
            assertEquals(limit, http().get(url(), image).bytes.size)
        }
        server.enqueue(html().setHeader("Content-Encoding", "gzip")); blocked { http().get(url()) }
        server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml").setBody("<svg/>"))
        blocked { http().get(url(), true) }
        server.enqueue(MockResponse().setBody("<html/>")); blocked { http().get(url()) }
    }

    @Test fun largePageKeepsEarlyMetadataWithoutDownloadingItsEntireBody() {
        val prefix = "<meta property='og:title' content='Large page'><meta property='og:image' content='/card.jpg'>"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
            .setBody(prefix + "x".repeat(4 * SafeHttp.HTML_BYTES)))
        val download = http().get(url())
        assertEquals(SafeHttp.HTML_BYTES, download.bytes.size)
        assertEquals("Large page", MetadataParser.parse(download).title)
        assertTrue(receivedBytes < 2 * SafeHttp.HTML_BYTES)
    }

    @Test fun rejectedAndRedirectBodiesAreCancelledWithoutDrainingDownloads() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("x".repeat(4 * 1024 * 1024)))
        blocked { http().get(url()) }
        assertTrue("Rejected body must not be drained: $receivedBytes", receivedBytes <= 16 * 1024)
        receivedBytes = 0
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next").setBody("x".repeat(4 * 1024 * 1024)))
        server.enqueue(html())
        http().get(url())
        assertTrue("Redirect body must not be drained: $receivedBytes", receivedBytes <= 32 * 1024)
    }

    @Test fun imageRedirectUsesSameSafetyAndTransientStatusAndTimeoutAreRetryable() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.0.1/image.png"))
        blocked { http().get(url(), true) }; assertEquals(1, connections)
        for (code in listOf(408, 429, 500, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try { http().get(url()); fail() } catch (_: RetryablePreview) { }
        }
        server.enqueue(html().setBodyDelay(1, TimeUnit.SECONDS))
        val start = System.nanoTime()
        try { http(timeout = 100).get(url()); fail() } catch (_: java.io.IOException) { }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 900)
    }
}
