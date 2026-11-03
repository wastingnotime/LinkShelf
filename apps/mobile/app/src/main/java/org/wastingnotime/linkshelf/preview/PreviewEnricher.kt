package org.wastingnotime.linkshelf.preview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import org.wastingnotime.linkshelf.data.LinkDao
import java.io.IOException

enum class PreviewOutcome { Done, Retry }

/** Finite retry policy shared by the real worker and focused recovery tests. */
class PreviewEnricher(
    private val dao: LinkDao,
    private val download: (String, Boolean) -> Download,
    private val saveImage: (Long, ByteArray) -> String?,
) {
    suspend fun enrich(id: Long, attempt: Int): PreviewOutcome {
        val link = dao.find(id) ?: return PreviewOutcome.Done
        if (link.previewStatus != "pending" || link.deletedAt != null) return PreviewOutcome.Done
        var metadata: Metadata? = Metadata(link.title, link.description, link.siteName, link.imageUrl)
        var path: String? = link.imagePath
        var retryImage = false
        var failure: String? = null
        var stage = "Page"
        try {
            val fetched = runInterruptible(Dispatchers.IO) { YouTubeMetadata.fetch(link.originalUrl, download) }
            // An empty/partial response must not erase imported or previously saved metadata.
            metadata = Metadata(fetched.title ?: link.title, fetched.description ?: link.description,
                fetched.siteName ?: link.siteName, fetched.imageUrl ?: link.imageUrl)
            metadata.imageUrl?.let { image ->
                stage = "Image"
                try {
                    val savedImage = runInterruptible(Dispatchers.IO) { saveImage(id, download(image, true).bytes) }
                    if (savedImage == null) failure = "Image format or dimensions unsupported" else path = savedImage
                }
                catch (e: UnsafePreview) { failure = "Image: ${e.message}" }
                catch (_: IOException) { failure = "Image network request failed"; retryImage = attempt < MAX_ATTEMPTS - 1 }
            }
        } catch (e: UnsafePreview) { failure = "$stage: ${e.message}" }
        catch (_: IOException) {
            failure = "Page network request failed"
            if (attempt < MAX_ATTEMPTS - 1) return PreviewOutcome.Retry
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { failure = "$stage could not be processed" }
        if (failure == null && metadata?.useful != true && path == null) failure = "No preview metadata found"
        val ready = metadata?.useful == true || path != null
        dao.finishPreview(id, metadata?.title, metadata?.description, metadata?.siteName,
            metadata?.imageUrl, path, if (retryImage) "pending" else if (failure != null) "${if (ready) "partial" else "failed"}:$failure" else if (ready) "ready" else "failed")
        return if (retryImage) PreviewOutcome.Retry else PreviewOutcome.Done
    }

    companion object { const val MAX_ATTEMPTS = 4 }
}
