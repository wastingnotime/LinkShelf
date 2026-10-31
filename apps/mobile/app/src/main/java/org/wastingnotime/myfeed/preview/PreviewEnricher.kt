package org.wastingnotime.myfeed.preview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import org.wastingnotime.myfeed.data.LinkDao
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
        try {
            metadata = runInterruptible(Dispatchers.IO) { MetadataParser.parse(download(link.originalUrl, false)) }
            path = null
            metadata.imageUrl?.let { image ->
                try { path = runInterruptible(Dispatchers.IO) { saveImage(id, download(image, true).bytes) } }
                catch (_: UnsafePreview) { /* text-only card */ }
                catch (_: IOException) { retryImage = attempt < MAX_ATTEMPTS - 1 }
            }
        } catch (_: UnsafePreview) { /* permanent fallback */ }
        catch (_: IOException) {
            if (attempt < MAX_ATTEMPTS - 1) return PreviewOutcome.Retry
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { /* malformed metadata/decoder failure: preserve useful text */ }
        val ready = metadata?.useful == true || path != null
        dao.finishPreview(id, metadata?.title, metadata?.description, metadata?.siteName,
            metadata?.imageUrl, path, if (retryImage) "pending" else if (ready) "ready" else "failed")
        return if (retryImage) PreviewOutcome.Retry else PreviewOutcome.Done
    }

    companion object { const val MAX_ATTEMPTS = 4 }
}
