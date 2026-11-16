package com.example.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil.imageLoader
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Extracts dominant color metadata (`DOMINANT_COLOR:#RRGGBB`) embedded inside cover artwork files
 * across all formats (JPEG EXIF tags, PNG tEXt/iTXt chunks, WebP metadata, and raw HTTP cache entries)
 * both online and offline, with lightweight visual Palette fallback when no metadata tag is embedded.
 */
object ArtworkColorExtractor {

    private val TAGGED_HEX_REGEX = Regex(
        pattern = "DOMINANT_COLOR\\s*[:=]\\s*(#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}))",
        option = RegexOption.IGNORE_CASE
    )
    private val GENERIC_HEX_REGEX = Regex("#([0-9a-fA-F]{8}|[0-9a-fA-F]{6})\\b")

    private const val HEAD_SCAN_BYTES = 64 * 1024
    private const val TAIL_SCAN_BYTES = 32 * 1024

    fun normalizeHex(rawHex: String?): String? {
        if (rawHex.isNullOrBlank()) return null
        val trimmed = rawHex.trim()
        val withHash = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return when (withHash.length) {
            7 -> if (withHash.drop(1).all { it.isHexDigit() }) withHash.uppercase(Locale.US) else null
            9 -> if (withHash.drop(1).all { it.isHexDigit() }) "#${withHash.substring(3).uppercase(Locale.US)}" else null
            else -> null
        }
    }

    fun parseHexColor(rawHex: String?): Color? {
        val clean = normalizeHex(rawHex) ?: return null
        return try {
            Color(android.graphics.Color.parseColor(clean))
        } catch (_: Exception) {
            null
        }
    }

    fun colorToHex(color: Color): String {
        val argb = color.toArgb()
        return String.format(Locale.US, "#%06X", 0xFFFFFF and argb)
    }

    /**
     * Parses `DOMINANT_COLOR:#RRGGBB` from a raw metadata string (EXIF tag or text chunk).
     */
    fun extractHexFromMetadataString(rawMeta: String?): String? {
        if (rawMeta.isNullOrBlank()) return null
        TAGGED_HEX_REGEX.find(rawMeta)?.groupValues?.getOrNull(1)?.let { tagged ->
            normalizeHex(tagged)?.let { return it }
        }
        GENERIC_HEX_REGEX.find(rawMeta)?.value?.let { generic ->
            normalizeHex(generic)?.let { return it }
        }
        return null
    }

    /**
     * Scans raw image bytes (ISO-8859-1 lossless 1:1 byte-to-char mapping) for `DOMINANT_COLOR:#RRGGBB`.
     * Works identically for JPEG EXIF segments, PNG `tEXt`/`iTXt` chunks, WebP chunks, and raw HTTP cache files.
     */
    fun extractMetadataColorHexFromBytes(bytes: ByteArray, length: Int = bytes.size): String? {
        val safeLen = length.coerceIn(0, bytes.size)
        if (safeLen <= 0) return null
        val text = String(bytes, 0, safeLen, StandardCharsets.ISO_8859_1)
        TAGGED_HEX_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { match ->
            return normalizeHex(match)
        }
        return null
    }

    /**
     * Reads embedded color metadata from an image file on disk.
     * 1. Checks all relevant EXIF attributes independently (`ImageDescription`, `Software`, `UserComment`)
     *    so a non-null/garbled `UserComment` never short-circuits `ImageDescription`.
     * 2. Performs a bounded raw-byte head + tail scan (<= 96 KB total) to capture PNG `tEXt` chunks
     *    and EXIF blocks regardless of container format.
     */
    fun extractMetadataColorHexFromFile(file: File?): String? {
        if (file == null || !file.exists() || !file.isFile || file.length() <= 0L) return null

        // Pass 1: Standard ExifInterface check across all candidate tags independently
        try {
            val exif = ExifInterface(file.absolutePath)
            val tags = arrayOf(
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_USER_COMMENT
            )
            for (tag in tags) {
                val attr = exif.getAttribute(tag)
                val parsed = extractHexFromMetadataString(attr)
                if (parsed != null) return parsed
            }
        } catch (_: Exception) {
            // Fall through to raw byte scanner (e.g. PNG tEXt chunks or non-EXIF containers)
        }

        // Pass 2: Bounded head + tail raw byte scan (covers PNG tEXt at start or before IEND, JPEG APP1, WebP)
        return scanFileBytesForMetadataHex(file)
    }

    internal fun scanFileBytesForMetadataHex(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        val fileLen = file.length()
        if (fileLen <= 0L) return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val headSize = minOf(fileLen, HEAD_SCAN_BYTES.toLong()).toInt()
                val headBuf = ByteArray(headSize)
                val headRead = raf.read(headBuf)
                if (headRead > 0) {
                    extractMetadataColorHexFromBytes(headBuf, headRead)?.let { return it }
                }

                if (fileLen > HEAD_SCAN_BYTES) {
                    val tailStart = maxOf(0L, fileLen - TAIL_SCAN_BYTES.toLong())
                    val tailSize = (fileLen - tailStart).toInt()
                    if (tailSize > 0) {
                        raf.seek(tailStart)
                        val tailBuf = ByteArray(tailSize)
                        val tailRead = raf.read(tailBuf)
                        if (tailRead > 0) {
                            extractMetadataColorHexFromBytes(tailBuf, tailRead)?.let { return it }
                        }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves any local cover image file stored on device (permanent download cover or `file://` path).
     */
    fun resolveLocalCoverFile(context: Context, songId: Int?, imageUrl: String?): File? {
        if (songId != null && songId > 0) {
            val downloadsCover = File(context.filesDir, "downloads/${songId}_cover.jpg")
            if (downloadsCover.exists() && downloadsCover.length() > 0L) {
                return downloadsCover
            }
        }

        val url = imageUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            fileFromLocalUriOrPath(url)?.let { return it }
        }
        return null
    }

    fun fileFromLocalUriOrPath(urlOrPath: String): File? {
        if (urlOrPath.isBlank()) return null
        if (urlOrPath.startsWith("http://", ignoreCase = true) ||
            urlOrPath.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return try {
            val candidatePath = if (urlOrPath.startsWith("file://", ignoreCase = true)) {
                Uri.parse(urlOrPath).path ?: urlOrPath.removePrefix("file://")
            } else if (urlOrPath.startsWith("/")) {
                urlOrPath
            } else {
                null
            }
            val file = candidatePath?.let { File(it) }
            if (file != null && file.exists() && file.length() > 0L) file else null
        } catch (_: Exception) {
            val fallback = File(urlOrPath.removePrefix("file://"))
            if (fallback.exists() && fallback.length() > 0L) fallback else null
        }
    }

    /**
     * Checks Coil's DiskCache (keeping the snapshot open while reading so the file cannot be
     * evicted/renamed mid-read) AND OkHttp's `okhttp_image_cache` directory for offline cached covers.
     */
    fun extractFromCaches(context: Context, candidateUrls: Collection<String>): String? {
        val cleanUrls = candidateUrls
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (cleanUrls.isEmpty()) return null

        // 1. Check local file:// paths first
        for (url in cleanUrls) {
            val localFile = fileFromLocalUriOrPath(url)
            if (localFile != null) {
                extractMetadataColorHexFromFile(localFile)?.let { return it }
            }
        }

        // 2. Check Coil DiskCache (`image_cache`) while holding Snapshot lock safely
        val diskCache = try {
            context.imageLoader.diskCache
        } catch (_: Exception) {
            null
        }
        if (diskCache != null) {
            for (url in cleanUrls) {
                try {
                    val hex = diskCache.openSnapshot(url)?.use { snapshot ->
                        extractMetadataColorHexFromFile(snapshot.data.toFile())
                    }
                    if (hex != null) return hex
                } catch (_: Exception) {}
            }
        }

        // 3. Check OkHttp HTTP image cache (`okhttp_image_cache`) raw cached entries offline
        // OkHttp DiskLruCache stores request URL in `.0` metadata files and raw image bytes in `.1` body files.
        val okHttpCacheDir = File(context.cacheDir, "okhttp_image_cache")
        if (okHttpCacheDir.exists() && okHttpCacheDir.isDirectory) {
            extractFromOkHttpCacheDir(okHttpCacheDir, cleanUrls)?.let { return it }
        }

        return null
    }

    private fun extractFromOkHttpCacheDir(cacheDir: File, targetUrls: List<String>): String? {
        return try {
            val urlSet = targetUrls.toSet()
            // Also match by URL path (e.g. `/covers/abc.jpg`) in case server IP/host changed while offline!
            val targetPaths = targetUrls.mapNotNull { raw ->
                try {
                    Uri.parse(raw).encodedPath?.takeIf { it.isNotBlank() && it != "/" }
                } catch (_: Exception) {
                    null
                }
            }.toSet()

            val metaFiles = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".0") } ?: return null
            for (metaFile in metaFiles) {
                val firstLine = try {
                    metaFile.bufferedReader().use { it.readLine()?.trim() }
                } catch (_: Exception) {
                    null
                } ?: continue

                val matchesExact = firstLine in urlSet
                val matchesPath = !matchesExact && targetPaths.isNotEmpty() && run {
                    val cachedPath = try {
                        Uri.parse(firstLine).encodedPath
                    } catch (_: Exception) {
                        null
                    }
                    cachedPath != null && cachedPath in targetPaths
                }

                if (matchesExact || matchesPath) {
                    val bodyFile = File(cacheDir, metaFile.name.removeSuffix(".0") + ".1")
                    if (bodyFile.exists() && bodyFile.length() > 0L) {
                        extractMetadataColorHexFromFile(bodyFile)?.let { return it }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Copies a cached cover file from Coil DiskCache or OkHttp image cache to [destFile]
     * (used by DownloadManager so offline downloads keep cover art + EXIF metadata even if HTTP blinked).
     */
    fun copyCachedCoverToFile(context: Context, imageUrl: String, destFile: File): Boolean {
        if (imageUrl.isBlank()) return false
        try {
            fileFromLocalUriOrPath(imageUrl)?.let { src ->
                if (src.absolutePath != destFile.absolutePath) {
                    src.copyTo(destFile, overwrite = true)
                }
                return destFile.exists() && destFile.length() > 0L
            }

            context.imageLoader.diskCache?.openSnapshot(imageUrl)?.use { snap ->
                val cachedFile = snap.data.toFile()
                if (cachedFile.exists() && cachedFile.length() > 0L) {
                    cachedFile.copyTo(destFile, overwrite = true)
                    return destFile.exists() && destFile.length() > 0L
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Lightweight visual fallback using AndroidX Palette when an image has no embedded
     * `DOMINANT_COLOR` tag in its metadata. Downscales to <= 64x64 so RAM usage is negligible (< 16 KB).
     */
    fun extractVisualPaletteHex(drawable: Drawable?): String? {
        val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return null
        return extractVisualPaletteHex(bmp)
    }

    fun extractVisualPaletteHex(bitmap: Bitmap?): String? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        return try {
            val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            } ?: return null

            val palette = Palette.from(safeBitmap)
                .resizeBitmapArea(64 * 64)
                .maximumColorCount(16)
                .generate()

            if (safeBitmap !== bitmap && !safeBitmap.isRecycled) {
                safeBitmap.recycle()
            }

            val swatch = palette.vibrantSwatch
                ?: palette.lightVibrantSwatch
                ?: palette.darkVibrantSwatch
                ?: palette.dominantSwatch
                ?: palette.mutedSwatch
                ?: return null

            String.format(Locale.US, "#%06X", 0xFFFFFF and swatch.rgb)
        } catch (_: Exception) {
            null
        }
    }

    private fun Char.isHexDigit(): Boolean =
        (this in '0'..'9') || (this in 'a'..'f') || (this in 'A'..'F')
}
