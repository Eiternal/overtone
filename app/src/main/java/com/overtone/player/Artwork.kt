package com.overtone.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val results = LruCache<Pair<String, String>, Result>(10)

    fun read(context: Context, track: Track): Artwork? {
        val key = track.uri to track.artwork
        results.get(key)?.let { return it.artwork }
        val bitmap = readArtworkBitmap(context, track, 820)
        val result = bitmap?.let(::analyzeArtwork)
        results.put(key, Result(result))
        return result
    }
}

private fun readArtworkBitmap(context: Context, track: Track, maxSide: Int): Bitmap? {
    val embedded = if (track.uri.startsWith("content:")) runCatching {
        val source = MediaMetadataRetriever()
        try {
            source.setDataSource(context, Uri.parse(track.uri))
            source.embeddedPicture?.takeIf { it.size <= 20_000_000 }
        } finally {
            source.release()
        }
    }.getOrNull() else null
    return embedded?.let { bytes -> decodeArtwork(maxSide) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) } }
        ?: track.artwork.takeIf { it.isNotBlank() }?.let { address ->
            runCatching {
                decodeArtwork(maxSide) { options ->
                    context.contentResolver.openInputStream(Uri.parse(address))?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }
            }.getOrNull()
        }
        ?: if (track.uri.startsWith("asset:///demo/")) {
            runCatching {
                decodeArtwork(maxSide) { options ->
                    context.assets.open("demo/sessions.webp").use { input ->
                        BitmapFactory.decodeStream(input, null, options)
                    }
                }
            }.getOrNull()
        } else null
}

private fun decodeArtwork(maxSide: Int, read: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    read(options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSide * 1.25f) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    options.inPreferredConfig = Bitmap.Config.ARGB_8888
    return read(options)
}

private data class Thumbnail(val cover: ImageBitmap?)
private val thumbnails = LruCache<Triple<String, String, Int>, Thumbnail>(108)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val thumbnailIO = Dispatchers.IO.limitedParallelism(3)

@Composable internal fun rememberThumbnail(tracks: List<Track>, revision: Int): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val candidates = remember(tracks) {
        val first = tracks.take(6)
        (first + listOfNotNull(tracks.firstOrNull { it.artwork.isNotBlank() })).distinctBy(Track::uri)
    }
    var result by remember(candidates) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(candidates, revision) {
        result = withContext(thumbnailIO) {
            candidates.firstNotNullOfOrNull { track ->
                ensureActive()
                val key = Triple(track.uri, track.artwork, revision)
                val cached = thumbnails.get(key)
                if (cached != null) cached.cover
                else {
                    val bitmap = runCatching { readArtworkBitmap(context, track, 232) }.getOrNull()
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
    val cache = remember(scan) { ArtworkCache() }
    var result by remember { mutableStateOf<Artwork?>(null) }
    LaunchedEffect(cache, track?.uri, track?.artwork) {
        result = if (track == null) null else withContext(Dispatchers.IO) { cache.read(context, track) }
    }
    val active = result.takeIf { track != null }
    return ArtworkSelection(active, active?.cover)
}

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
