package com.overtone.player

import android.content.Context
import android.net.Uri
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal data class Lyric(val at: Long?, val text: String)
internal data class Lyrics(val lines: List<Lyric>, val source: Source) {
    enum class Source { SIDECAR, EMBEDDED }
    val synchronized get() = lines.any { it.at != null }
}

private val timeTag = Regex("""\[(\d{1,4}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val offsetTag = Regex("""\[offset\s*:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
private val infoTag = Regex("""^\s*(?:\[[A-Za-z][\w-]*:[^\]]*]\s*)+$""")
private val wordTag = Regex("""<\d{1,4}:\d{2}(?:[.:]\d{1,3})?>""")
private const val maxLines = 6000

internal fun parseLyricText(text: String): List<Lyric> {
    val clean = text.trimStart('\uFEFF')
    val offset = offsetTag.findAll(clean).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
        ?.coerceIn(-86_400_000, 86_400_000) ?: 0
    val lines = clean.lineSequence().take(maxLines).toList()
    val timed = lines.asSequence().flatMap { line ->
        val words = line.replace(timeTag, "").replace(wordTag, "").trim()
        if (words.isEmpty() || infoTag.matches(words)) emptySequence() else timeTag.findAll(line).mapNotNull { match ->
            val (minutes, seconds, fraction) = match.destructured
            val second = seconds.toLong()
            if (second >= 60) null else {
                val at = (minutes.toLong() * 60 + second) * 1000 + fraction.padEnd(3, '0').toLong() + offset
                Lyric(at.coerceAtLeast(0), words)
            }
        }
    }.take(maxLines).sortedBy(Lyric::at).distinct().toList()
    return timed.ifEmpty { lines.mapNotNull { line ->
        line.trim().takeIf { it.isNotEmpty() && !infoTag.matches(it) && !timeTag.matches(it) }?.let { Lyric(null, it) }
    } }
}

internal fun readLyricText(bytes: ByteArray): String {
    val bigEndian = bytes.size >= 2 && bytes[0].toInt() and 255 == 254 && bytes[1].toInt() and 255 == 255
    val littleEndian = bytes.size >= 2 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 255 == 254
    if (bigEndian || littleEndian) return bytes.toString(Charsets.UTF_16)
    return runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrElse { bytes.toString(Charset.forName("GB18030")) }
}

internal fun readLyrics(context: Context, track: Track): Lyrics? {
    val resolver = context.contentResolver
    val sidecars = sequence {
        track.lyrics.takeIf(String::isNotBlank)?.let { yield(Uri.parse(it)) }
        readableSibling(context, track)?.let { yield(it) }
    }.distinct()
    for (uri in sidecars) {
        val lines = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readUpTo(1_048_577)
                if (bytes.size <= 1_048_576) parseLyricText(readLyricText(bytes)) else emptyList()
            }
        }.getOrNull().orEmpty()
        if (lines.isNotEmpty()) return Lyrics(lines, Lyrics.Source.SIDECAR)
    }
    if (!track.file.endsWith(".mp3", true)) return null
    val embedded = runCatching {
        resolver.openInputStream(Uri.parse(track.uri))?.buffered()?.use(::readId3Lyrics)
    }.getOrNull().orEmpty()
    return embedded.takeIf(List<Lyric>::isNotEmpty)?.let { Lyrics(it, Lyrics.Source.EMBEDDED) }
}

private fun InputStream.readUpTo(length: Int): ByteArray {
    val output = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = read(output, read, length - read)
        if (count <= 0) break
        read += count
    }
    return if (read == length) output else output.copyOf(read)
}

internal fun readId3Lyrics(input: InputStream): List<Lyric> {
    val header = input.readUpTo(10)
    if (header.size != 10 || !header.copyOfRange(0, 3).contentEquals("ID3".toByteArray(Charsets.US_ASCII))) return emptyList()
    val size = (6..9).fold(0) { size, i ->
        val byte = header[i].toInt() and 255
        if (byte >= 128) return emptyList()
        size * 128 + byte
    }
    if (size !in 1..16_777_216) return emptyList()
    val body = input.readUpTo(size)
    if (body.size != size) return emptyList()
    // Let Media3 handle extended headers, tag versions and unsynchronisation.
    val decoder = Id3Decoder { version, a, b, c, d ->
        val id = if (version == 2) String(charArrayOf(a.toChar(), b.toChar(), c.toChar()))
            else String(charArrayOf(a.toChar(), b.toChar(), c.toChar(), d.toChar()))
        id in setOf("USLT", "ULT", "SYLT", "SLT", "TXXX", "TXX")
    }
    val tag = header + body
    val metadata = decoder.decode(tag, tag.size) ?: return emptyList()
    var fallback = emptyList<Lyric>()
    var frameDurationUs: Long? = null
    var lookedForFrames = false
    for (i in 0 until metadata.length()) {
        val frame = metadata[i]
        val lyrics = when (frame) {
            is BinaryFrame -> when (frame.id) {
                "USLT", "ULT" -> readUnsynced(frame.data)
                "SYLT", "SLT" -> {
                    if (frame.data.getOrNull(4) == 1.toByte() && !lookedForFrames) {
                        frameDurationUs = mpegFrameDurationUs(input.readUpTo(16_384))
                        lookedForFrames = true
                    }
                    readSynced(frame.data, frameDurationUs)
                }
                else -> emptyList()
            }
            is TextInformationFrame -> if (frame.description?.uppercase(java.util.Locale.ROOT) in setOf("LYRICS", "USLT", "UNSYNCEDLYRICS"))
                parseLyricText(frame.values.joinToString("\n")) else emptyList()
            else -> emptyList()
        }
        if (lyrics.any { it.at != null }) return lyrics
        if (fallback.isEmpty() && lyrics.isNotEmpty()) fallback = lyrics
    }
    return fallback
}

private class TagStrings(private val data: ByteArray) {
    private val encoding = data[0].toInt() and 255
    private var utf16 = Charsets.UTF_16BE
    private val wide = encoding == 1 || encoding == 2
    private fun text(start: Int, end: Int): String {
        if (encoding == 1 && end - start >= 2) {
            val first = data[start].toInt() and 255
            val second = data[start + 1].toInt() and 255
            if (first == 255 && second == 254) utf16 = Charsets.UTF_16LE
            if (first == 254 && second == 255) utf16 = Charsets.UTF_16BE
        }
        val charset = when (encoding) {
            1 -> utf16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(data, start, end - start, charset).trimStart('\uFEFF').trimEnd('\u0000')
    }
    fun remainder(start: Int): String = text(start, data.size)
    fun terminated(start: Int): Pair<String, Int>? {
        val step = if (wide) 2 else 1
        for (end in start..data.size - step step step) {
            if (data[end] == 0.toByte() && (!wide || data[end + 1] == 0.toByte())) {
                return text(start, end) to end + step
            }
        }
        return null
    }
}

private fun readUnsynced(data: ByteArray): List<Lyric> {
    if (data.size < 5 || data[0].toInt() !in 0..3) return emptyList()
    val strings = TagStrings(data)
    val (_, start) = strings.terminated(4) ?: return emptyList()
    return parseLyricText(strings.remainder(start))
}

private fun readSynced(data: ByteArray, frameUs: Long?): List<Lyric> {
    if (data.size < 7 || data[0].toInt() !in 0..3 || data[5].toInt() !in 0..1) return emptyList()
    val unit = data[4].toInt()
    if (unit !in 1..2) return emptyList()
    val strings = TagStrings(data)
    var cursor = strings.terminated(6)?.second ?: return emptyList()
    val pieces = mutableListOf<Lyric>()
    while (cursor < data.size && pieces.size < maxLines) {
        val (text, stamp) = strings.terminated(cursor) ?: break
        if (stamp > data.size - 4) break
        val raw = unsignedInt(data, stamp)
        val at = if (unit == 2) raw else frameUs?.let { raw * it / 1000 }
        pieces += Lyric(at, text)
        cursor = stamp + 4
    }
    val lines = if (pieces.any { '\n' in it.text || '\r' in it.text }) {
        buildList {
            val text = StringBuilder()
            var at: Long? = null
            for (piece in pieces) {
                val segments = piece.text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
                segments.forEachIndexed { i, segment ->
                    if (i > 0) {
                        if (text.isNotBlank()) add(Lyric(at, text.toString().trim()))
                        text.setLength(0)
                        at = null
                    }
                    if (text.isEmpty()) at = piece.at
                    text.append(segment)
                }
            }
            if (text.isNotBlank()) add(Lyric(at, text.toString().trim()))
        }
    } else pieces.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
    return lines.sortedBy(Lyric::at).distinct()
}

private fun unsignedInt(data: ByteArray, at: Int) =
    (at until at + 4).fold(0L) { value, index -> (value shl 8) or (data[index].toLong() and 255) }

private fun mpegFrameDurationUs(bytes: ByteArray): Long? {
    val first = MpegAudioUtil.Header()
    val next = MpegAudioUtil.Header()
    for (offset in 0..bytes.size - 4) {
        if (!first.setForHeaderData(unsignedInt(bytes, offset).toInt())) continue
        val following = offset + first.frameSize
        if (following > bytes.size - 4 || !next.setForHeaderData(unsignedInt(bytes, following).toInt())) continue
        if (first.sampleRate == next.sampleRate && first.samplesPerFrame == next.samplesPerFrame) {
            return first.samplesPerFrame * 1_000_000L / first.sampleRate
        }
    }
    return null
}
