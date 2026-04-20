package com.mingeek.studiopop.data.youtube

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import java.io.InputStream

/**
 * InputStream 을 요청 본문으로 쓰면서 업로드 진행률을 콜백으로 내보내는 RequestBody.
 * YouTube resumable 업로드의 PUT 단계에서 사용.
 */
class ProgressRequestBody(
    private val contentType: MediaType?,
    private val contentLength: Long,
    private val openStream: () -> InputStream,
    private val onProgress: (bytesWritten: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        openStream().use { input ->
            val counting = object : ForwardingSink(sink) {
                var written = 0L
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    onProgress(written, contentLength)
                }
            }
            val bufferedSink = (counting as Sink).buffer()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                bufferedSink.write(buffer, 0, read)
            }
            bufferedSink.flush()
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
