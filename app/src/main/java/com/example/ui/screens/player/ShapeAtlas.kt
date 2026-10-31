package com.example.ui.screens.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.os.Process
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

@Immutable
class ShapeColorPalette(
    internal val argbColors: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShapeColorPalette) return false
        return argbColors.contentEquals(other.argbColors)
    }

    override fun hashCode(): Int = argbColors.contentHashCode()
}

private val DefaultFallbackShapePalette = ShapeColorPalette(
    intArrayOf(
        Color(0xFFFF9A8B).toArgb(),
        Color(0xFFFFD27F).toArgb(),
        Color(0xFF9FD8FF).toArgb(),
        Color(0xFFC3A6FF).toArgb(),
        Color.White.toArgb()
    )
)

fun buildShapePalette(dominantColor: Color): ShapeColorPalette {
    val hasDominant = dominantColor != Color.Unspecified && dominantColor != Color.Transparent
    if (!hasDominant) return DefaultFallbackShapePalette

    val maxCh = maxOf(dominantColor.red, dominantColor.green, dominantColor.blue, 0.25f)
    val nr = (dominantColor.red / maxCh).coerceIn(0f, 1f)
    val ng = (dominantColor.green / maxCh).coerceIn(0f, 1f)
    val nb = (dominantColor.blue / maxCh).coerceIn(0f, 1f)

    return ShapeColorPalette(
        intArrayOf(
            Color(
                red = (nr * 0.65f + 0.35f).coerceIn(0f, 1f),
                green = (ng * 0.65f + 0.35f).coerceIn(0f, 1f),
                blue = (nb * 0.65f + 0.35f).coerceIn(0f, 1f)
            ).toArgb(),
            Color(
                red = (nr * 0.40f + 0.60f).coerceIn(0f, 1f),
                green = (ng * 0.40f + 0.60f).coerceIn(0f, 1f),
                blue = (nb * 0.40f + 0.60f).coerceIn(0f, 1f)
            ).toArgb(),
            Color(
                red = (nb * 0.45f + 0.50f).coerceIn(0f, 1f),
                green = (nr * 0.45f + 0.48f).coerceIn(0f, 1f),
                blue = (ng * 0.45f + 0.52f).coerceIn(0f, 1f)
            ).toArgb(),
            Color(0xFFE8ECF8).toArgb(),
            Color.White.toArgb()
        )
    )
}

internal class ShapeAtlasData(
    val bitmap: Bitmap,
    val isAlphaMask: Boolean
)

private data class WallpaperCacheKey(
    val seedKey: String,
    val palette: ShapeColorPalette,
    val isLandscape: Boolean,
    val mode: ShapeMode,
    val minCount: Int,
    val maxCount: Int,
    val minSizeBits: Int,
    val maxSizeBits: Int,
    val minAlphaBits: Int,
    val maxAlphaBits: Int
)

private fun mixSeed(key: String): Long {
    var h = -0x61c8864680b583ebL
    for (ch in key) {
        h = (h xor ch.code.toLong()) * -0x40a7b892e31b1a47L
        h = h xor (h ushr 31)
    }
    h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
    h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
    return h xor (h ushr 31)
}

object ShapeAtlas {
    private const val ATLAS_ASSET_PATH = "shapes/atlas_compact.png"
    private const val PORTRAIT_WALLPAPER_W = 432
    private const val PORTRAIT_WALLPAPER_H = 960

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadingStarted = AtomicBoolean(false)
    private val renderLock = Any()

    @Volatile
    private var atlasData: ShapeAtlasData? = null

    internal var atlasVersion by mutableIntStateOf(0)
        private set

    // Keep at most 3 small pre-composited song wallpapers (~1.6 MB each) in memory.
    private val wallpaperCache = LruCache<WallpaperCacheKey, ImageBitmap>(3)

    fun preloadAsync(context: Context) {
        if (atlasData != null) return
        if (!loadingStarted.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        backgroundScope.launch {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val decoded = decodeAtlasAsset(appContext)
            if (decoded != null) {
                atlasData = decoded
                atlasVersion = 1
            } else {
                loadingStarted.set(false)
            }
        }
    }

    fun prewarmWallpaper(
        context: Context,
        seedKey: String,
        palette: ShapeColorPalette,
        isLandscape: Boolean = false,
        mode: ShapeMode = ShapeMode.MIX,
        minCount: Int = 24,
        maxCount: Int = 44,
        minSizeFraction: Float = 0.09f,
        maxSizeFraction: Float = 0.25f,
        minAlpha: Float = 0.52f,
        maxAlpha: Float = 0.88f
    ) {
        val appContext = context.applicationContext
        renderScope.launch {
            if (atlasData == null) {
                val decoded = decodeAtlasAsset(appContext)
                if (decoded != null && atlasData == null) {
                    atlasData = decoded
                    atlasVersion = 1
                }
            }
            getOrRenderWallpaper(
                seedKey = seedKey,
                palette = palette,
                isLandscape = isLandscape,
                mode = mode,
                minCount = minCount,
                maxCount = maxCount,
                minSizeFraction = minSizeFraction,
                maxSizeFraction = maxSizeFraction,
                minAlpha = minAlpha,
                maxAlpha = maxAlpha
            )
        }
    }

    internal fun getOrRenderWallpaper(
        seedKey: String,
        palette: ShapeColorPalette,
        isLandscape: Boolean,
        mode: ShapeMode,
        minCount: Int,
        maxCount: Int,
        minSizeFraction: Float,
        maxSizeFraction: Float,
        minAlpha: Float,
        maxAlpha: Float
    ): ImageBitmap? {
        val activeAtlas = atlasData ?: return null
        val key = WallpaperCacheKey(
            seedKey = seedKey,
            palette = palette,
            isLandscape = isLandscape,
            mode = mode,
            minCount = minCount,
            maxCount = maxCount,
            minSizeBits = minSizeFraction.toRawBits(),
            maxSizeBits = maxSizeFraction.toRawBits(),
            minAlphaBits = minAlpha.toRawBits(),
            maxAlphaBits = maxAlpha.toRawBits()
        )
        wallpaperCache.get(key)?.let { return it }

        synchronized(renderLock) {
            wallpaperCache.get(key)?.let { return it }
            val rendered = renderWallpaperBitmap(
                atlas = activeAtlas,
                seedKey = seedKey,
                palette = palette,
                isLandscape = isLandscape,
                mode = mode,
                minCount = minCount,
                maxCount = maxCount,
                minSizeFraction = minSizeFraction,
                maxSizeFraction = maxSizeFraction,
                minAlpha = minAlpha,
                maxAlpha = maxAlpha
            )
            wallpaperCache.put(key, rendered)
            return rendered
        }
    }

    private fun renderWallpaperBitmap(
        atlas: ShapeAtlasData,
        seedKey: String,
        palette: ShapeColorPalette,
        isLandscape: Boolean,
        mode: ShapeMode,
        minCount: Int,
        maxCount: Int,
        minSizeFraction: Float,
        maxSizeFraction: Float,
        minAlpha: Float,
        maxAlpha: Float
    ): ImageBitmap {
        val width = if (isLandscape) PORTRAIT_WALLPAPER_H else PORTRAIT_WALLPAPER_W
        val height = if (isLandscape) PORTRAIT_WALLPAPER_W else PORTRAIT_WALLPAPER_H
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect()
        val dstRect = RectF()

        val rng = Random(mixSeed(seedKey))
        val loCount = minCount.coerceAtLeast(1)
        val hiCount = maxCount.coerceAtLeast(loCount)
        val targetCount = rng.nextInt(loCount, hiCount + 1)

        val chosenKeys = IntArray(targetCount)
        val seen = HashSet<Int>(targetCount * 2)
        var filled = 0
        var attempts = 0
        while (filled < targetCount && attempts < targetCount * 4) {
            attempts++
            val useSolid = when (mode) {
                ShapeMode.SOLID -> true
                ShapeMode.HOLLOW -> false
                ShapeMode.MIX -> rng.nextBoolean()
            }
            val shapeId = rng.nextInt(TOTAL_SHAPE_SPECS)
            val packedChoice = (shapeId shl 1) or (if (useSolid) 1 else 0)
            if (seen.add(packedChoice)) {
                chosenKeys[filled++] = packedChoice
            }
        }

        val argbPalette = if (palette.argbColors.isNotEmpty()) {
            palette.argbColors
        } else {
            DefaultFallbackShapePalette.argbColors
        }
        val refDim = min(width, height).toFloat()

        for (i in 0 until filled) {
            val choice = chosenKeys[i]
            val shapeId = choice ushr 1
            val isSolid = (choice and 1) == 1

            val cxFrac = rng.nextFloat() * 1.06f - 0.03f
            val cyFrac = rng.nextFloat() * 1.06f - 0.03f
            val sizeFrac = minSizeFraction + rng.nextFloat() * (maxSizeFraction - minSizeFraction)
            val rotationDeg = if (rng.nextBoolean()) (rng.nextFloat() * 2f - 1f) * 70f else 0f
            val alpha = minAlpha + rng.nextFloat() * (maxAlpha - minAlpha)
            val colorIdx = rng.nextInt(1024)

            val packed = SHAPE_ATLAS_SPECS[shapeId]
            val sw = specW(packed)
            val sh = specH(packed)
            val sx = specX(packed) + if (isSolid) sw + 2 else 0
            val sy = specY(packed)

            val cx = cxFrac * width
            val cy = cyFrac * height
            val targetPx = sizeFrac * refDim
            val maxSpriteDim = maxOf(sw, sh).coerceAtLeast(1)
            val scale = targetPx / maxSpriteDim
            val halfW = (sw * scale) * 0.5f
            val halfH = (sh * scale) * 0.5f

            val baseArgb = argbPalette[colorIdx % argbPalette.size]
            val alphaInt = (alpha * 255f).roundToInt().coerceIn(0, 255)
            if (atlas.isAlphaMask) {
                paint.color = (baseArgb and 0x00FFFFFF) or (alphaInt shl 24)
            } else {
                paint.color = android.graphics.Color.argb(alphaInt, 255, 255, 255)
                paint.colorFilter = PorterDuffColorFilter(baseArgb or -0x1000000, PorterDuff.Mode.SRC_IN)
            }

            srcRect.set(sx, sy, sx + sw, sy + sh)
            dstRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)

            if (rotationDeg != 0f) {
                canvas.save()
                canvas.rotate(rotationDeg, cx, cy)
                canvas.drawBitmap(atlas.bitmap, srcRect, dstRect, paint)
                canvas.restore()
            } else {
                canvas.drawBitmap(atlas.bitmap, srcRect, dstRect, paint)
            }
        }

        outBitmap.prepareToDraw()
        return outBitmap.asImageBitmap()
    }

    private fun decodeAtlasAsset(context: Context): ShapeAtlasData? {
        return runCatching {
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.assets.open(ATLAS_ASSET_PATH).use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return null

            // Convert 2048x1530 RGBA sheet into a compact 3.13 MB CPU-only ALPHA_8 mask
            // and immediately release the temporary ARGB buffer.
            val alphaMask = decoded.extractAlpha()
            if (alphaMask != null && alphaMask.width == decoded.width && alphaMask.height == decoded.height) {
                decoded.recycle()
                ShapeAtlasData(bitmap = alphaMask, isAlphaMask = true)
            } else {
                ShapeAtlasData(bitmap = decoded, isAlphaMask = false)
            }
        }.getOrNull()
    }
}

internal const val TOTAL_SHAPE_SPECS = 295

internal inline fun specX(packed: Long): Int = ((packed ushr 32) and 0xFFFL).toInt()
internal inline fun specY(packed: Long): Int = ((packed ushr 20) and 0xFFFL).toInt()
internal inline fun specW(packed: Long): Int = ((packed ushr 10) and 0x3FFL).toInt()
internal inline fun specH(packed: Long): Int = (packed and 0x3FFL).toInt()

// Packed coordinates for all 295 shapes in shapes/atlas_compact.png (2048x1530).
// Hollow sprite is at (x, y, w, h); solid sprite is at (x + w + 2, y, w, h).
internal val SHAPE_ATLAS_SPECS = longArrayOf(
    0x0000000E850L, 0x07800012450L, 0x10E0000E450L, 0x1840001344EL, 0x2220001403FL, 0x2C600014043L,
    0x36A0001404AL, 0x40E0001404EL, 0x4B200014046L, 0x55600014040L, 0x5FA0000FC46L, 0x67C0001244AL,
    0x7120001404EL, 0x0000521404BL, 0x0A40521403BL, 0x14805214049L, 0x1EC05214010L, 0x29005210050L,
    0x3140521403DL, 0x3B805214043L, 0x45C0521303AL, 0x4F805214038L, 0x59C05212049L, 0x6300521402BL,
    0x6D40521403DL, 0x0000A411044L, 0x08C0A413042L, 0x1280A414036L, 0x1CC0A414038L, 0x2700A41403DL,
    0x3140A414045L, 0x3B80A411045L, 0x4440A41403EL, 0x4E80A40E050L, 0x55C0A411050L, 0x5E80A411046L,
    0x6740A41403DL, 0x7180A414047L, 0x0000F611C47L, 0x0920F614041L, 0x1360F61403CL, 0x1DA0F612049L,
    0x26E0F614046L, 0x3120F61401CL, 0x3B60F614041L, 0x45A0F614039L, 0x4FE0F60C050L, 0x5620F614032L,
    0x6060F61402AL, 0x6AA0F60F846L, 0x72A0F612849L, 0x00014814043L, 0x0A41481402EL, 0x1481480D84DL,
    0x1B814811446L, 0x2461480FC39L, 0x2C81480EC37L, 0x3421480D837L, 0x3B21480EC32L, 0x42C14814032L,
    0x4D01481284AL, 0x5681480D045L, 0x5D41480F83DL, 0x6541480EC3BL, 0x6CE14812C36L, 0x00019713833L,
    0x0A01971402FL, 0x1441970E44CL, 0x1BA19714041L, 0x25E1971043BL, 0x2E41970D435L, 0x3521970C434L,
    0x3B81971202FL, 0x44C19713C2FL, 0x4EE19714049L, 0x59219710C3EL, 0x61C1970E038L, 0x6901970D838L,
    0x70019714033L, 0x0001E50F432L, 0x07E1E51402BL, 0x1221E51344EL, 0x1C01E510C43L, 0x24A1E50F03EL,
    0x2C61E51143DL, 0x3541E514032L, 0x3F81E512839L, 0x4901E513039L, 0x52C1E50C04CL, 0x5901E51284AL,
    0x6281E512840L, 0x6C01E51403AL, 0x7641E512C38L, 0x00023513C35L, 0x0A223514025L, 0x14623510C41L,
    0x1D02350E03DL, 0x2442350C839L, 0x2AC2350D835L, 0x31C2351342BL, 0x3BA2351082BL, 0x44223514027L,
    0x4E623510441L, 0x56C2351143DL, 0x5FA2350E439L, 0x6702350D837L, 0x6E02350E034L, 0x75423512C35L,
    0x0002781402CL, 0x0A42780DC4EL, 0x11627811C4BL, 0x1A827811045L, 0x2342781103EL, 0x2C02780E038L,
    0x33427813C36L, 0x3D627812428L, 0x46C27810040L, 0x4F027810040L, 0x57427813C3CL, 0x6162781383AL,
    0x6B627810834L, 0x73E27810034L, 0x0002C80E82CL, 0x0782C812047L, 0x10C2C810045L, 0x1902C80A842L,
    0x1E82C812439L, 0x27E2C814036L, 0x3222C811033L, 0x3AE2C814028L, 0x4522C81144EL, 0x4E02C811850L,
    0x5702C808039L, 0x5B42C80BC4BL, 0x6162C80EC3DL, 0x6902C80B050L, 0x6EC2C81402CL, 0x00031A0F850L,
    0x08031A1402EL, 0x12431A0B04BL, 0x18031A1244FL, 0x21631A14040L, 0x2BA31A13850L, 0x35A31A1403BL,
    0x3FE31A0E443L, 0x47431A12848L, 0x50C31A1404BL, 0x5B031A09850L, 0x60031A0AC4BL, 0x65A31A0F036L,
    0x6D631A0B050L, 0x73231A12050L, 0x7C631A07050L, 0x00036C0AC4BL, 0x05A36C1402BL, 0x0FE36C12C50L,
    0x19836C11045L, 0x22436C0B04BL, 0x28036C12450L, 0x31636C13439L, 0x3B436C12048L, 0x44836C14044L,
    0x4EC36C09850L, 0x53C36C1184BL, 0x5CC36C11437L, 0x65A36C1403EL, 0x6FE36C13850L, 0x79E36C0B04BL,
    0x0003BE14031L, 0x0A43BE1402BL, 0x1483BE1284AL, 0x1E03BE11C50L, 0x2723BE0EC3EL, 0x2EC3BE09450L,
    0x33A3BE09050L, 0x3863BE11C47L, 0x4183BE0843AL, 0x45E3BE0DC3EL, 0x4D03BE14043L, 0x5743BE0BC4BL,
    0x5D63BE1083FL, 0x65E3BE0AC4BL, 0x6B83BE0F036L, 0x7343BE1402BL, 0x0004100B850L, 0x0604100D43BL,
    0x0CE4101402FL, 0x17241011C3BL, 0x2044101402BL, 0x2A841011045L, 0x3344101404FL, 0x3D841012450L,
    0x46E41009C50L, 0x4C04101403EL, 0x56441010C3FL, 0x5EE41009441L, 0x63C41007850L, 0x67C41009C50L,
    0x6CE41008050L, 0x7124100B850L, 0x77241004050L, 0x79641006850L, 0x00046209050L, 0x04C46209450L,
    0x09A4620A850L, 0x0F24620AC50L, 0x14C46208050L, 0x1904620A450L, 0x1E646207850L, 0x2264620BC50L,
    0x28846205050L, 0x2B446214048L, 0x3584620A850L, 0x3B046207C50L, 0x3F246207C50L, 0x43446209850L,
    0x48446209850L, 0x4D44620B850L, 0x53446208C50L, 0x57E46209450L, 0x5CC4620D050L, 0x6384620A450L,
    0x68E46206050L, 0x6C246207050L, 0x6FE4620B850L, 0x75E4620B450L, 0x7BC46206050L, 0x0004B40C44FL,
    0x0664B407448L, 0x0A44B40A447L, 0x0FA4B40A042L, 0x14E4B408C50L, 0x1984B40D050L, 0x2044B40B850L,
    0x2644B40B850L, 0x2C44B40C850L, 0x32C4B405C50L, 0x35E4B40A850L, 0x3B64B40BC50L, 0x4184B40D850L,
    0x4884B409C50L, 0x4DA4B409850L, 0x52A4B40C850L, 0x5924B408850L, 0x5DA4B414043L, 0x67E4B40904AL,
    0x6CA4B40B450L, 0x7284B40D047L, 0x7944B40D050L, 0x00050604450L, 0x02650609050L, 0x0725060B050L,
    0x0CE5060A450L, 0x1245060DC50L, 0x1965060B450L, 0x1F45060C450L, 0x25A5060A850L, 0x2B25060C045L,
    0x3165060E83EL, 0x38E5060A850L, 0x3E65060A450L, 0x43C5060A850L, 0x4945060B850L, 0x4F45060B850L,
    0x55450607450L, 0x59250612850L, 0x62A5060C450L, 0x6905060AC50L, 0x6EA5060D450L, 0x75850606850L,
    0x7905060B450L, 0x0005580D050L, 0x06C5580704CL, 0x0A855808050L, 0x0EC5580704FL, 0x12855809846L,
    0x1785580E839L, 0x1F05580F050L, 0x26C55805450L, 0x29A55810050L, 0x31E5581404DL, 0x3C25580B450L,
    0x42055811450L, 0x4AE5580E450L, 0x5245580B050L, 0x5805580F050L, 0x5FC55811450L, 0x68A5580E450L,
    0x70055805C50L, 0x73255810050L, 0x7B655808850L, 0x0005AA1403AL, 0x0A45AA0A850L, 0x0FC5AA08450L,
    0x1425AA1404BL
)
