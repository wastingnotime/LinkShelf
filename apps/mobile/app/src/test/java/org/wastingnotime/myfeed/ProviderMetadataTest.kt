package org.wastingnotime.myfeed

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.wastingnotime.myfeed.preview.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderMetadataTest {
    @Test fun youtubeUsesOnlyRecognizedHostsAndVideoIds() {
        val expected = YouTubeMetadata.endpoint("https://youtube.com/watch?v=abcdefghijk")
        for (url in listOf("https://youtu.be/abcdefghijk?si=test", "https://m.youtube.com/shorts/abcdefghijk", "https://www.youtube.com/live/abcdefghijk"))
            assertEquals(expected, YouTubeMetadata.endpoint(url))
        for (url in listOf("https://youtube.com.evil.com/watch?v=abcdefghijk", "https://youtube.com/watch?v=bad", "https://youtube.com@evil.com/watch?v=abcdefghijk", "https://youtube.com/playlist?list=abcdefghijk"))
            assertNull(YouTubeMetadata.endpoint(url))
    }
    @Test fun oembedOnlyExtractsTextAndThumbnailAndFallsBackToHtml() {
        val endpoint = requireNotNull(YouTubeMetadata.endpoint("https://youtu.be/abcdefghijk")).toHttpUrl()
        val metadata = YouTubeMetadata.parse(Download(endpoint,
            """{"version":"1.0","type":"video","title":"A video","thumbnail_url":"https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg","html":"<script>evil()</script>"}""".toByteArray(), "application/json"))
        assertEquals("A video", metadata.title)
        assertEquals("YouTube", metadata.siteName)
        val calls = mutableListOf<String>()
        val fallback = YouTubeMetadata.fetch("https://youtu.be/abcdefghijk") { url, _ ->
            calls += url
            if (url.contains("/oembed")) throw UnsafePreview("HTTP 403")
            Download("https://www.youtube.com/watch?v=abcdefghijk".toHttpUrl(), "<title>Fallback</title>".toByteArray(), "text/html")
        }
        assertEquals("Fallback", fallback.title); assertEquals(2, calls.size)
    }
    @Test fun metadataBeyondOldCutoffIsExtracted() {
        val html = "<html><head><style>" + " ".repeat(1100000) + "</style><meta property='og:title' content='App'><meta property='og:image' content='/app.png'></head>"
        val metadata = MetadataParser.parse(Download("https://play.google.com/store/apps/details?id=example".toHttpUrl(), html.toByteArray(), "text/html"))
        assertEquals("App", metadata.title); assertEquals("https://play.google.com/app.png", metadata.imageUrl)
    }
}
