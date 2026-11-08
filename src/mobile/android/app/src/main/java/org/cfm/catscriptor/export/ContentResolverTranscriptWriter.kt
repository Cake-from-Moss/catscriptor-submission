package org.cfm.catscriptor.export

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.pipeline.Transcript
import org.cfm.catscriptor.domain.pipeline.TranscriptExporter
import org.cfm.catscriptor.domain.pipeline.TranscriptFormat
import java.io.IOException

/** Владеет открытым SAF-потоком; сериализатор получает поток только на время записи. */
class ContentResolverTranscriptWriter(
    private val resolver: ContentResolver,
    private val exporter: TranscriptExporter,
) {
    suspend fun write(uri: Uri, transcript: Transcript, format: TranscriptFormat) {
        withContext(Dispatchers.IO) {
            val stream = resolver.openOutputStream(uri, "wt")
                ?: throw IOException("Провайдер не открыл поток для $uri")
            stream.use { exporter.export(transcript, format, it) }
        }
    }
}
