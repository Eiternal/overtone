package com.overtone.player

import java.util.Locale

internal enum class ImportDisposition { MUSIC, REVIEW, SKIP }

internal enum class ImportReason(val disposition: ImportDisposition, val label: String) {
    TAGS(ImportDisposition.MUSIC, "音乐标签"),
    LYRICS(ImportDisposition.MUSIC, "匹配到歌词"),
    NAMED_SONG(ImportDisposition.MUSIC, "歌曲文件名"),
    UNCLASSIFIED(ImportDisposition.REVIEW, "音乐信息不足"),
    SHORT(ImportDisposition.REVIEW, "较短的音频"),
    LONG(ImportDisposition.REVIEW, "较长的音频"),
    SYSTEM_SOUND(ImportDisposition.SKIP, "铃声或提示音"),
    RECORDING(ImportDisposition.SKIP, "录音或语音"),
    SPOKEN(ImportDisposition.SKIP, "播客或有声书"),
}

internal data class AudioImportInfo(
    val file: String,
    val directory: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val systemMusic: Boolean,
    val hasLyrics: Boolean,
    val declaredType: ImportReason? = null,
)

private val systemSounds = setOf("ringtones", "ringtone", "notifications", "notification sounds", "alarms", "铃声", "通知音", "闹钟")
private val recordings = setOf("recordings", "call recordings", "voice recordings", "voice recorder",
    "whatsapp voice notes", "录音", "通话录音", "语音消息")
private val spokenAudio = setOf("podcasts", "audiobooks", "播客", "有声书")
private val recordingName = Regex("""^(ptt[-_]|(?:call recording|voice recording|voice note|通话录音|语音消息|录音)[ _\-（(]*[0-9])""")
private val songName = Regex("""^.+\s[-–—]\s.+$""")

internal fun classifyAudio(info: AudioImportInfo): ImportReason {
    info.declaredType?.let { return it }
    val parts = info.directory.substringAfter(':').lowercase(Locale.ROOT).split('/').filter(String::isNotBlank)
    val stem = info.file.substringBeforeLast('.').trim()
    if (parts.any(systemSounds::contains)) return ImportReason.SYSTEM_SOUND
    if (parts.any(recordings::contains) || recordingName.containsMatchIn(stem.lowercase(Locale.ROOT))) return ImportReason.RECORDING
    if (parts.any(spokenAudio::contains)) return ImportReason.SPOKEN
    if (info.hasLyrics) return ImportReason.LYRICS

    val album = info.album.trim().lowercase(Locale.ROOT)
    val albumTag = album.isNotEmpty() && album != parts.lastOrNull() && album != stem.lowercase(Locale.ROOT)
    val artistTag = info.artist.isNotBlank()
    if (artistTag && albumTag) return ImportReason.TAGS
    if (info.duration in 1..44_999L) return ImportReason.SHORT
    if (info.duration > 20 * 60_000L) return ImportReason.LONG
    if (artistTag && info.duration > 0 && (info.systemMusic || "music" in parts || "音乐" in parts)) return ImportReason.TAGS
    if (info.systemMusic && info.duration in 60_000L..900_000L &&
        ("music" in parts || "音乐" in parts) && songName.matches(stem)) return ImportReason.NAMED_SONG
    return ImportReason.UNCLASSIFIED
}
