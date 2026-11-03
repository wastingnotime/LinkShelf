package org.wastingnotime.linkshelf

import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.linkshelf.data.UrlCapture

class UrlCaptureTest {
    @Test fun extractsFirstValidLinkFromProseAndDropsWrappingPunctuation() {
        val url = requireNotNull(UrlCapture.extract("Watch this (https://YouTube.com/watch?v=AbC). Next https://example.com"))
        assertEquals("https://YouTube.com/watch?v=AbC", url.original)
        assertEquals("https://youtube.com/watch?v=AbC", url.canonical)
    }

    @Test fun balancedParenthesesInsidePathSurvive() {
        assertEquals("https://example.com/a_(b)", UrlCapture.extract("(https://example.com/a_(b)).")?.original)
    }

    @Test fun normalizesOnlyDocumentedIdentityComponents() {
        assertEquals("https://example.com/", UrlCapture.normalize("HTTPS://EXAMPLE.COM:443#section")?.canonical)
        assertEquals("http://example.com/", UrlCapture.normalize("HTTP://EXAMPLE.COM:80")?.canonical)
        assertEquals("https://example.com:8443/Path", UrlCapture.normalize("https://Example.com:8443/Path#part")?.canonical)
    }

    @Test fun queriesRemainByteForByteIncludingTrackingAndOrder() {
        val raw = "https://example.com/Path?b=2&a=%2f&a=&utm_source=shared"
        assertEquals(raw, UrlCapture.normalize(raw)?.canonical)
        assertNotEquals(UrlCapture.normalize("https://example.com/?a=1&b=2")?.canonical,
            UrlCapture.normalize("https://example.com/?b=2&a=1")?.canonical)
        assertNotEquals(UrlCapture.normalize("http://example.com/Path")?.canonical,
            UrlCapture.normalize("https://example.com/path")?.canonical)
    }

    @Test fun originalKeepsFragmentCaseAndDefaultPort() {
        val raw = "HTTPS://Example.com:443/Path?q=one#two"
        assertEquals(raw, UrlCapture.normalize(raw)?.original)
    }

    @Test fun supportsInternationalHostAndIpv6() {
        assertEquals("https://xn--bcher-kva.de/", UrlCapture.normalize("https://bücher.de")?.canonical)
        assertEquals("http://[2001:db8::1]/", UrlCapture.normalize("http://[2001:db8::1]:80")?.canonical)
    }

    @Test fun rejectsCredentialsUnsafeSchemesMalformedAuthoritiesAndEscapes() {
        listOf("javascript:alert(1)", "file:///tmp/a", "content://media/1", "ftp://example.com",
            "https://user:secret@example.com", "https://", "https://exa mple.com", "https://example.com/%xx",
            "https://example.com:0", "https://example.com:65536", "https://example.com:",
            "https://example.com:abc", "https://evil\\example.com", "https://example.com/\npath")
            .forEach { assertNull(it, UrlCapture.normalize(it)) }
    }

    @Test fun skipsInvalidCandidateAndBoundsInput() {
        assertEquals("https://example.com/", UrlCapture.extract("https://bad:abc next https://example.com")?.canonical)
        assertNull(UrlCapture.extract("Just some text"))
        assertNull(UrlCapture.extract("x".repeat(100_000) + " https://example.com"))
        assertNull(UrlCapture.normalize("https://example.com/" + "x".repeat(8192)))
        assertNull(UrlCapture.extract("https://example.com/" + ")".repeat(100_000)))
    }
}
