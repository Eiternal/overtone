package com.overtone.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class Artwork(
    val cover: ImageBitmap,
    val backdrop: ImageBitmap,
    val main: Color,
    val secondary: Color,
)

private class ArtworkCache {
    private class Result(val artwork: Artwork?)
    private val results = object : LruCache<Triple<String, String, Int>, Result>(24 * 1024 * 1024) {
        override fun sizeOf(key: Triple<String, String, Int>, value: Result): Int =
            value.artwork?.let { it.cover.width * it.cover.height * 4 + 4096 } ?: 4096
    }

    fun read(context: Context, track: Track, pixels: Int): Artwork? {
        val key = Triple(track.uri, track.artwork, pixels)
        results.get(key)?.let { return it.artwork }
        val bitmap = readArtworkBitmap(context, track, pixels)
        val result = bitmap?.let(::analyzeArtwork)
        results.put(key, Result(result))
        return result
    }
}

private fun readArtworkBitmap(context: Context, track: Track, pixels: Int, square: Boolean = false): Bitmap? {
    val embedded = if (track.uri.startsWith("content:")) runCatching {
        val source = MediaMetadataRetriever()
        try {
            source.setDataSource(context, Uri.parse(track.uri))
            source.embeddedPicture?.takeIf { it.size <= 20_000_000 }
        } finally {
            source.release()
        }
    }.getOrNull() else null
    return embedded?.let { bytes -> decodeArtwork(pixels, square) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) } }
        ?: track.artwork.takeIf { it.isNotBlank() }?.let { address ->
            runCatching {
                decodeArtwork(pixels, square) { options ->
                    context.contentResolver.openInputStream(Uri.parse(address))?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }
            }.getOrNull()
        }
        ?: if (track.uri.startsWith("asset:///demo/")) {
            runCatching {
                decodeArtwork(pixels, square) { options ->
                    context.assets.open("demo/sessions.webp").use { input ->
                        BitmapFactory.decodeStream(input, null, options)
                    }
                }
            }.getOrNull()
        } else null
}

private fun decodeArtwork(pixels: Int, square: Boolean, read: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    read(options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    val longSide = maxOf(options.outWidth, options.outHeight)
    val targetSide = if (square) minOf(options.outWidth, options.outHeight) else longSide
    var sample = 1
    while (targetSide / (sample * 2) >= pixels || longSide / sample > 3072) sample *= 2
    options.inSampleSize = sample
    options.inJustDecodeBounds = false
    options.inPreferredConfig = Bitmap.Config.ARGB_8888
    val bitmap = read(options) ?: return null
    val scaled = if (square) {
        val side = minOf(bitmap.width, bitmap.height)
        val size = minOf(side, pixels)
        if (bitmap.width == size && bitmap.height == size) return bitmap
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bitmap, Rect(left, top, left + side, top + side),
                Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    } else {
        val size = maxOf(bitmap.width, bitmap.height)
        if (size <= pixels) return bitmap
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * pixels / size).coerceAtLeast(1),
            (bitmap.height * pixels / size).coerceAtLeast(1), true)
    }
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

private data class ThumbnailKey(val uri: String, val artwork: String, val revision: Int, val pixels: Int)
private data class Thumbnail(val cover: ImageBitmap?)
private val thumbnails = object : LruCache<ThumbnailKey, Thumbnail>(16 * 1024 * 1024) {
    override fun sizeOf(key: ThumbnailKey, value: Thumbnail): Int =
        value.cover?.let { it.width * it.height * 4 } ?: 4096
}
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val thumbnailIO = Dispatchers.IO.limitedParallelism(3)

@Composable internal fun rememberThumbnail(tracks: List<Track>, revision: Int, size: Dp): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val pixels = artworkPixelBucket(with(LocalDensity.current) { size.roundToPx() }, 32, 768)
    val candidates = remember(tracks) {
        val first = tracks.take(6)
        (first + listOfNotNull(tracks.firstOrNull { it.artwork.isNotBlank() })).distinctBy(Track::uri)
    }
    var result by remember(candidates) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(candidates, revision, pixels) {
        result = withContext(thumbnailIO) {
            candidates.firstNotNullOfOrNull { track ->
                ensureActive()
                val key = ThumbnailKey(track.uri, track.artwork, revision, pixels)
                val cached = thumbnails.get(key)
                if (cached != null) cached.cover
                else {
                    val bitmap = runCatching { readArtworkBitmap(context, track, pixels, square = true) }.getOrNull()
                    val cover = bitmap?.asImageBitmap()
                    thumbnails.put(key, Thumbnail(cover))
                    cover
                }
            }
        }
    }
    return result
}

internal data class ArtworkSelection(val backdrop: Artwork?, val cover: ImageBitmap?)

@Composable internal fun rememberArtwork(track: Track?, scan: Int): ArtworkSelection {
    val context = LocalContext.current.applicationContext
    val pixels = artworkPixelBucket(LocalWindowInfo.current.containerSize.width, 128, 2048)
    val cache = remember(scan) { ArtworkCache() }
    var result by remember { mutableStateOf<Artwork?>(null) }
    LaunchedEffect(cache, track?.uri, track?.artwork, pixels) {
        result = if (track == null) null else withContext(Dispatchers.IO) { cache.read(context, track, pixels) }
    }
    val active = result.takeIf { track != null }
    return ArtworkSelection(active, active?.cover)
}

private fun artworkPixelBucket(pixels: Int, step: Int, limit: Int): Int =
    ((pixels.coerceIn(1, limit) + step - 1) / step) * step

private fun analyzeArtwork(bitmap: Bitmap): Artwork {
    val size = 32
    val sample = Bitmap.createScaledBitmap(bitmap, size, size, true)
    val pixels = IntArray(size * size)
    sample.getPixels(pixels, 0, size, 0, 0, size, size)
    if (sample !== bitmap) sample.recycle()
    val hsv = FloatArray(3)
    val weights = FloatArray(24)
    val reds = FloatArray(24)
    val greens = FloatArray(24)
    val blues = FloatArray(24)
    var colorful = 0
    for (pixel in pixels) {
        if (android.graphics.Color.alpha(pixel) < 160) continue
        android.graphics.Color.colorToHSV(pixel, hsv)
        if (hsv[1] < .16f || hsv[2] < .13f) continue
        colorful++
        val bucket = (hsv[0] / 15).toInt().coerceIn(weights.indices)
        val weight = hsv[1] * (.4f + hsv[2] * .6f)
        weights[bucket] += weight
        reds[bucket] += android.graphics.Color.red(pixel) * weight
        greens[bucket] += android.graphics.Color.green(pixel) * weight
        blues[bucket] += android.graphics.Color.blue(pixel) * weight
    }
    fun swatch(index: Int): Color {
        val neighbors = listOf((index + 23) % 24, index, (index + 1) % 24)
        val total = neighbors.sumOf { weights[it].toDouble() }.toFloat()
        fun channel(values: FloatArray) = (neighbors.sumOf { values[it].toDouble() } / (255 * total)).toFloat().coerceIn(0f, 1f)
        return Color(channel(reds), channel(greens), channel(blues))
    }
    fun neighborhood(index: Int) = weights[(index + 23) % 24] + weights[index] + weights[(index + 1) % 24]
    val mainIndex = weights.indices.maxBy(::neighborhood)
    val secondaryIndex = weights.indices.filter { minOf((it - mainIndex + 24) % 24, (mainIndex - it + 24) % 24) >= 4 }
        .maxBy(::neighborhood)
    val main = if (colorful >= pixels.size / 18 && neighborhood(mainIndex) > 0f) swatch(mainIndex) else Color.White
    val secondary = if (main != Color.White && neighborhood(secondaryIndex) > neighborhood(mainIndex) * .26f) swatch(secondaryIndex) else main
    val blurred = soften(pixels, size)
    return Artwork(bitmap.asImageBitmap(), blurred.asImageBitmap(), main, secondary)
}

private fun soften(pixels: IntArray, size: Int): Bitmap {
    var input = pixels
    repeat(3) {
        val output = IntArray(input.size)
        for (y in 0 until size) for (x in 0 until size) {
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (j in (y - 3).coerceAtLeast(0)..(y + 3).coerceAtMost(size - 1)) {
                for (i in (x - 3).coerceAtLeast(0)..(x + 3).coerceAtMost(size - 1)) {
                    val pixel = input[j * size + i]
                    r += (pixel shr 16) and 255
                    g += (pixel shr 8) and 255
                    b += pixel and 255
                    count++
                }
            }
            output[y * size + x] = android.graphics.Color.rgb(r / count, g / count, b / count)
        }
        input = output
    }
    return Bitmap.createBitmap(input, size, size, Bitmap.Config.ARGB_8888)
}

@Composable internal fun FrostBackdrop(
    art: Artwork?, strength: Float, modifier: Modifier = Modifier, immersive: Boolean = false,
    followCover: Boolean = true
) {
    val ink = Ink
    val palette = LocalFrost.current
    val opacity = if (immersive && followCover) strength else strength * palette.amount
    Box(modifier.background(ink)) {
        if (!immersive) Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to palette.glow, .42f to palette.panel, 1f to ink
        )))
        Crossfade(art?.backdrop, Modifier.matchParentSize(), animationSpec = tween(550, easing = Ease),
            label = "cover backdrop") { image ->
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alpha = opacity)
        }
        val scrim = if (immersive) Brush.verticalGradient(
            0f to ink.copy(alpha = .64f), .22f to ink.copy(alpha = .26f), .62f to ink.copy(alpha = .20f),
            .81f to ink.copy(alpha = .62f), 1f to ink
        ) else Brush.verticalGradient(listOf(
            ink.copy(alpha = .60f), ink.copy(alpha = .29f), ink.copy(alpha = .76f), ink
        ))
        Box(Modifier.matchParentSize().background(scrim))
    }
}
