package org.wastingnotime.myfeed

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.myfeed.preview.*

class MetadataTest {
    private fun parse(html: String, type: String = "text/html; charset=UTF-8") = MetadataParser.parse(
        Download("https://example.com/posts/story".toHttpUrl(), html.toByteArray(), type))

    @Test fun prefersOgThenTwitterThenHtmlAndDecodesEntitiesAsPlainText() {
        val meta = parse("""<title>HTML</title><meta name=twitter:title content=Twitter>
            <meta property=OG:TITLE content="Article &amp; story"><meta property=og:description content="A &lt;b&gt;description&lt;/b&gt;">
            <meta property=og:site_name content=" Site "><meta name=twitter:image content="/card.png">""")
        assertEquals("Article & story", meta.title)
        assertEquals("A <b>description</b>", meta.description)
        assertEquals("Site", meta.siteName)
        assertEquals("https://example.com/card.png", meta.imageUrl)
        assertEquals("Twitter", parse("<meta property=og:title content=' '><meta name=twitter:title content=Twitter><title>HTML</title>").title)
        assertEquals("HTML", parse("<title>HTML</title>").title)
        assertEquals("Description", parse("<meta name=description content=Description>").description)
    }

    @Test fun malformedHtmlEmptyTagsAndHugeValuesAreBoundedAndInert() {
        val meta = parse("<meta property=og:title content='${"x".repeat(400)}'><meta property=og:description content='${"d".repeat(1200)}'><script>location='http://127.0.0.1/'</script><iframe src='http://localhost/'><title>unfinished")
        assertEquals(300, meta.title?.length); assertEquals(1000, meta.description?.length)
        assertFalse(parse("<script>alert('execute')</script><meta property=og:title content=' '>").useful)
        assertEquals("Title", parse("<title>Title</title>", "text/html; charset=does-not-exist").title)
    }

    @Test fun invalidOgImageFallsBackToTwitterAndHugeUrlIsNotTruncated() {
        val meta = parse("<meta property=og:image content='http://127.0.0.1/x'><meta name=twitter:image content='/fallback.png'>")
        assertEquals("https://example.com/fallback.png", meta.imageUrl)
        assertNull(parse("<meta property=og:image content='https://example.com/${"x".repeat(8200)}'>").imageUrl)
    }

    @Test fun ignoresBaseCanonicalRefreshAndUnsafeImageReferences() {
        val meta = parse("<base href='http://127.0.0.1/'><link rel=canonical href='http://localhost/'><meta http-equiv=refresh content='0;url=http://localhost/'><meta property=og:image content='../safe.png'>")
        assertEquals("https://example.com/safe.png", meta.imageUrl)
        listOf("http://127.0.0.1/x", "http://user:pass@example.com/x", "file:///x", "data:image/png,123", "javascript:evil()", "http://@example.com/x")
            .forEach { assertNull(it, parse("<meta property=og:image content='$it'>").imageUrl) }
    }
}
