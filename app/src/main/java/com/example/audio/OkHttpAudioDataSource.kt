package com.example.audio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlin.math.min

/**
 * OkHttp-backed Media3 [HttpDataSource] for continuous, resilient audio streaming.
 *
 * Uses OkHttp's HTTP/2 + HTTP/1.1 connection pool, TLS session reuse, and automatic
 * connection-failure retries while leaving disk byte caching strictly to Media3 SimpleCache.
 */
@OptIn(UnstableApi::class)
class OkHttpAudioDataSource(
    private val callFactory: Call.Factory,
    private val userAgent: String? = null,
    private val cacheControl: CacheControl? = null,
    private val defaultRequestProperties: HttpDataSource.RequestProperties? = null
) : BaseDataSource(/* isNetwork = */ true), HttpDataSource {

    private val requestProperties = HttpDataSource.RequestProperties()

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseByteStream: InputStream? = null
    private var opened: Boolean = false
    private var bytesToRead: Long = 0L
    private var bytesRead: Long = 0L
    private var lastDocumentSizeBytes: Long = C.LENGTH_UNSET.toLong()

    /**
     * Total document size (full file bytes) reported by the latest open response via
     * Content-Range or Content-Length, or [C.LENGTH_UNSET] if unknown.
     */
    fun getDocumentSizeBytes(): Long = lastDocumentSizeBytes

    class Factory(
        private val callFactory: Call.Factory
    ) : HttpDataSource.Factory {
        private val defaultRequestProperties = HttpDataSource.RequestProperties()
        private var userAgent: String? = null
        private var cacheControl: CacheControl? = null

        fun setUserAgent(userAgent: String?): Factory {
            this.userAgent = userAgent
            return this
        }

        fun setCacheControl(cacheControl: CacheControl?): Factory {
            this.cacheControl = cacheControl
            return this
        }

        override fun setDefaultRequestProperties(properties: Map<String, String>): Factory {
            defaultRequestProperties.clearAndSet(properties)
            return this
        }

        override fun createDataSource(): HttpDataSource {
            return OkHttpAudioDataSource(
                callFactory = callFactory,
                userAgent = userAgent,
                cacheControl = cacheControl,
                defaultRequestProperties = defaultRequestProperties
            )
        }
    }

    override fun setRequestProperty(name: String, value: String) {
        requestProperties.set(name, value)
    }

    override fun clearRequestProperty(name: String) {
        requestProperties.remove(name)
    }

    override fun clearAllRequestProperties() {
        requestProperties.clear()
    }

    override fun getResponseCode(): Int {
        val res = response
        return if (res == null || res.code <= 0) -1 else res.code
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return response?.headers?.toMultimap() ?: emptyMap()
    }

    override fun getUri(): Uri? {
        return response?.request?.url?.toString()?.let(Uri::parse) ?: dataSpec?.uri
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        this.bytesRead = 0L
        this.bytesToRead = 0L
        this.lastDocumentSizeBytes = C.LENGTH_UNSET.toLong()
        transferInitializing(dataSpec)

        val request = buildRequest(dataSpec)
        val executedResponse: Response = try {
            callFactory.newCall(request).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        this.response = executedResponse
        val body: ResponseBody? = executedResponse.body
        this.responseByteStream = body?.byteStream()
        val responseCode = executedResponse.code

        val contentRangeHeader = executedResponse.header("Content-Range")
        val contentLengthHeader = executedResponse.header("Content-Length")
        val parsedDocSize = parseTotalLengthFromContentRange(contentRangeHeader)
        if (parsedDocSize > 0L) {
            lastDocumentSizeBytes = parsedDocSize
        } else {
            val headerLen = contentLengthHeader?.toLongOrNull() ?: -1L
            if (responseCode == 200 && headerLen > 0L) {
                lastDocumentSizeBytes = headerLen
            }
        }

        if (!executedResponse.isSuccessful) {
            if (responseCode == 416) {
                val documentSize = HttpUtil.getDocumentSize(contentRangeHeader)
                if (dataSpec.position == documentSize && documentSize >= 0L) {
                    opened = true
                    transferStarted(dataSpec)
                    return 0L
                }
            }

            val errorBodyBytes = try {
                responseByteStream?.readBytes() ?: ByteArray(0)
            } catch (_: IOException) {
                ByteArray(0)
            }
            val headersMap = executedResponse.headers.toMultimap()
            closeConnectionQuietly()
            val cause = if (responseCode == 416) {
                androidx.media3.datasource.DataSourceException(
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                )
            } else {
                null
            }
            throw HttpDataSource.InvalidResponseCodeException(
                responseCode,
                executedResponse.message,
                cause,
                headersMap,
                dataSpec,
                errorBodyBytes
            )
        }

        // If server returned 200 OK instead of 206 Partial Content for a non-zero offset request,
        // skip the leading bytes cleanly in read() rather than corrupting stream offsets.
        val bytesToSkip = if (responseCode == 200 && dataSpec.position != 0L) dataSpec.position else 0L
        if (bytesToSkip > 0L && responseByteStream != null) {
            try {
                skipBytes(bytesToSkip, dataSpec)
            } catch (e: IOException) {
                closeConnectionQuietly()
                if (e is HttpDataSource.HttpDataSourceException) throw e
                throw HttpDataSource.HttpDataSourceException(
                    e,
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
        }

        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            val bodyLen = body?.contentLength() ?: -1L
            if (bodyLen >= 0L) {
                if (bytesToSkip > 0L) (bodyLen - bytesToSkip).coerceAtLeast(0L) else bodyLen
            } else if (parsedDocSize > 0L && dataSpec.position >= 0L) {
                (parsedDocSize - dataSpec.position).coerceAtLeast(0L)
            } else {
                C.LENGTH_UNSET.toLong()
            }
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val currentSpec = dataSpec ?: return C.RESULT_END_OF_INPUT
        val stream = responseByteStream ?: return C.RESULT_END_OF_INPUT

        val maxReadLength = if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0L) {
                return C.RESULT_END_OF_INPUT
            }
            min(length.toLong(), remaining).toInt()
        } else {
            length
        }

        val read = try {
            stream.read(buffer, offset, maxReadLength)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                currentSpec,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }

        if (read == -1) {
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += read
        bytesTransferred(read)
        return read
    }

    @Throws(HttpDataSource.HttpDataSourceException::class)
    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        closeConnectionQuietly()
        dataSpec = null
    }

    private fun buildRequest(dataSpec: DataSpec): Request {
        val builder = Request.Builder().url(dataSpec.uri.toString())
        cacheControl?.let { builder.cacheControl(it) }

        val headersBuilder = Headers.Builder()
        defaultRequestProperties?.snapshot?.forEach { (k, v) ->
            headersBuilder[k] = v
        }
        requestProperties.snapshot.forEach { (k, v) ->
            headersBuilder[k] = v
        }
        dataSpec.httpRequestHeaders.forEach { (k, v) ->
            headersBuilder[k] = v
        }

        if (!userAgent.isNullOrBlank() && headersBuilder["User-Agent"] == null) {
            headersBuilder["User-Agent"] = userAgent
        }
        // Request exact uncompressed audio bytes so proxies never alter Content-Length / byte offsets
        if (headersBuilder["Accept-Encoding"] == null) {
            headersBuilder["Accept-Encoding"] = "identity"
        }

        val rangeHeader = buildRangeHeader(dataSpec.position, dataSpec.length)
        if (rangeHeader != null) {
            headersBuilder["Range"] = rangeHeader
        }
        if ((dataSpec.flags and DataSpec.FLAG_ALLOW_GZIP) != 0) {
            headersBuilder.removeAll("Accept-Encoding")
        }

        builder.headers(headersBuilder.build())

        val body = when {
            dataSpec.httpBody != null -> dataSpec.httpBody!!.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(dataSpec.httpMethodString, body)
        return builder.build()
    }

    @Throws(IOException::class)
    private fun skipBytes(bytesToSkip: Long, dataSpec: DataSpec) {
        if (bytesToSkip <= 0L) return
        val stream = responseByteStream ?: return
        val skipBuffer = ByteArray(4096)
        var remaining = bytesToSkip
        while (remaining > 0L) {
            if (Thread.currentThread().isInterrupted) {
                throw HttpDataSource.HttpDataSourceException(
                    InterruptedIOException(),
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            val read = stream.read(skipBuffer, 0, min(remaining, skipBuffer.size.toLong()).toInt())
            if (read == -1) {
                throw HttpDataSource.HttpDataSourceException(
                    dataSpec,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN
                )
            }
            remaining -= read
        }
    }

    private fun closeConnectionQuietly() {
        try {
            responseByteStream?.close()
        } catch (_: IOException) {
        }
        responseByteStream = null
        try {
            response?.close()
        } catch (_: Exception) {
        }
        response = null
    }

    companion object {
        /**
         * Builds an RFC 9110 Range header string or null when requesting the entire resource
         * starting at byte 0 with unset length (so the server returns 200 OK in one continuous pass).
         */
        fun buildRangeHeader(position: Long, length: Long): String? {
            if (position <= 0L && length == C.LENGTH_UNSET.toLong()) {
                return null
            }
            val safeStart = position.coerceAtLeast(0L)
            return if (length != C.LENGTH_UNSET.toLong() && length > 0L) {
                "bytes=$safeStart-${safeStart + length - 1L}"
            } else {
                "bytes=$safeStart-"
            }
        }

        /**
         * Parses total document length from a Content-Range header like "bytes 0-1023/6400000".
         * Returns -1L when absent or unknown ("*").
         */
        fun parseTotalLengthFromContentRange(contentRange: String?): Long {
            if (contentRange.isNullOrBlank()) return -1L
            val slashIdx = contentRange.lastIndexOf('/')
            if (slashIdx == -1 || slashIdx == contentRange.length - 1) return -1L
            val totalPart = contentRange.substring(slashIdx + 1).trim()
            if (totalPart == "*") return -1L
            return totalPart.toLongOrNull()?.takeIf { it > 0L } ?: -1L
        }
    }
}
