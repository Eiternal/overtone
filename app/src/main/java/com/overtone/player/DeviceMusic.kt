package com.overtone.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File
import android.provider.MediaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

internal const val AUTO_FOLDER = "media-folder/"
private const val PRIMARY = "external_primary"

internal class DeviceMusic(private val context: Context) {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val hasPermission get() = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun changes() = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val volumes = if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context)
            .map { MediaStore.Audio.Media.getContentUri(it) } else emptyList()
        try {
            (volumes + collection).distinct().forEach { resolver.registerContentObserver(it, true, observer) }
            awaitClose { }
        } finally {
            // Also undo partial registration if a provider became unavailable.
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    fun tracks(checkActive: () -> Unit): List<ImportCandidate> {
        if (!hasPermission) return emptyList()
        val modern = Build.VERSION.SDK_INT >= 29
        val lyrics = indexedLyrics(modern, checkActive)
        val columns = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
        )
        if (modern) {
            columns += MediaStore.MediaColumns.RELATIVE_PATH
            columns += MediaStore.MediaColumns.VOLUME_NAME
        } else columns += MediaStore.MediaColumns.DATA
        val types = buildList {
            add(MediaStore.Audio.Media.IS_RINGTONE to ImportReason.SYSTEM_SOUND)
            add(MediaStore.Audio.Media.IS_NOTIFICATION to ImportReason.SYSTEM_SOUND)
            add(MediaStore.Audio.Media.IS_ALARM to ImportReason.SYSTEM_SOUND)
            add(MediaStore.Audio.Media.IS_PODCAST to ImportReason.SPOKEN)
            if (modern) add(MediaStore.Audio.Media.IS_AUDIOBOOK to ImportReason.SPOKEN)
            if (Build.VERSION.SDK_INT >= 31) add(MediaStore.Audio.Media.IS_RECORDING to ImportReason.RECORDING)
        }
        val firstType = columns.size
        columns += types.map { it.first }
        return resolver.query(collection, columns.toTypedArray(), null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    checkActive()
                    val file = cursor.getString(1) ?: continue
                    if (file.substringAfterLast('.', "").lowercase(Locale.ROOT) !in formats) continue
                    val path = cursor.getString(7).orEmpty()
                    val volume = if (modern) cursor.getString(8).orEmpty().ifBlank { PRIMARY } else PRIMARY
                    val key = if (modern && path.isNotBlank()) storageKey(volume, "$path/$file")
                        else if (!modern) legacyStorageKey(path) else ""
                    val parent = if (key.isNotBlank()) key.substringBeforeLast('/', "")
                        .ifEmpty { if (volume == PRIMARY) "" else key.substringBefore(':') + ":" } else ""
                    val uri = ContentUris.withAppendedId(
                        if (modern) MediaStore.Audio.Media.getContentUri(volume) else collection, cursor.getLong(0)
                    )
                    fun tag(index: Int) = cursor.getString(index)?.takeUnless {
                        it.equals(MediaStore.UNKNOWN_STRING, true)
                    }.orEmpty()
                    val track = Track(uri.toString(), file, autoFolder(parent), tag(2).ifBlank { file.substringBeforeLast('.') },
                        tag(3), tag(4), cursor.getLong(5), lyrics = lyrics[lyricKey(key)].orEmpty(), storageKey = key)
                    val declaredType = types.indices.firstOrNull { cursor.getInt(firstType + it) != 0 }
                        ?.let { types[it].second }
                    val reason = classifyAudio(AudioImportInfo(file, parent, track.artist, track.album, track.duration,
                        cursor.getInt(6) != 0, track.lyrics.isNotBlank(), declaredType))
                    add(ImportCandidate(track, reason))
                }
            }
        }.orEmpty()
    }
    private fun indexedLyrics(modern: Boolean, checkActive: () -> Unit): Map<String, String> {
        val fields = if (modern) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME)
        else arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
        val collection = MediaStore.Files.getContentUri("external")
        return try {
            resolver.query(collection, fields, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%.lrc"), "${MediaStore.MediaColumns._ID} ASC")?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        checkActive()
                        val file = cursor.getString(1) ?: continue
                        val path = cursor.getString(2) ?: continue
                        val volume = if (modern) cursor.getString(3).orEmpty().ifBlank { PRIMARY } else ""
                        val key = if (modern) storageKey(volume, "$path/$file") else legacyStorageKey(path)
                        if (key.isBlank()) continue
                        val base = if (modern) MediaStore.Files.getContentUri(volume) else collection
                        putIfAbsent(lyricKey(key), ContentUris.withAppendedId(base, cursor.getLong(0)).toString())
                    }
                }
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }

}

private fun legacyStorageKey(path: String): String {
    val primary = Regex("^/(?:storage/emulated/[0-9]+|sdcard)(?:/|$)").find(path)
    if (primary != null) return storageKey(PRIMARY, path.drop(primary.value.length))
    val card = Regex("^/(?:storage|mnt/media_rw)/([0-9a-fA-F]{4}-[0-9a-fA-F]{4})/").find(path)
    return if (card == null) "" else storageKey(card.groupValues[1], path.drop(card.value.length))
}

private fun lyricKey(key: String): String {
    val separator = maxOf(key.lastIndexOf('/'), key.indexOf(':'))
    return key.take(separator + 1) +
        key.drop(separator + 1).substringBeforeLast('.').lowercase(Locale.ROOT)
}

internal fun lyricDirectoryHint(track: Track): Uri? {
    val key = track.storageKey
    if (':' !in key) return null
    val volume = key.substringBefore(':').let { if (it == PRIMARY) "primary" else it.uppercase(Locale.ROOT) }
    val relative = key.substringAfter(':').substringBeforeLast('/', "")
    return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "$volume:$relative")
}

internal fun readableSibling(context: Context, track: Track): Uri? {
    val key = track.storageKey
    if (':' !in key) return null
    val volume = key.substringBefore(':')
    val path = key.substringAfter(':').split('/')
    if (path.any { it.isBlank() || it == "." || it == ".." }) return null
    val root = if (volume == PRIMARY) Environment.getExternalStorageDirectory()
    else if (Build.VERSION.SDK_INT >= 30) context.getSystemService(StorageManager::class.java)?.storageVolumes
        ?.firstOrNull { it.uuid?.equals(volume, true) == true }?.directory else null
    root ?: return null
    val folder = path.dropLast(1).fold(root) { file, part -> File(file, part) }
    val target = path.last().substringBeforeLast('.') + ".lrc"
    return runCatching {
        val exact = File(folder, target)
        val sidecar = exact.takeIf { it.isFile && it.canRead() }
            ?: folder.listFiles { _, name -> name.equals(target, true) }
                ?.sortedBy(File::getName)?.firstOrNull { it.isFile && it.canRead() }
        sidecar?.let(Uri::fromFile)
    }.getOrNull()
}

internal fun documentStorageKey(tree: Uri, id: String): String {
    if (tree.authority != "com.android.externalstorage.documents" || ':' !in id) return ""
    return storageKey(id.substringBefore(':'), id.substringAfter(':'))
}

private fun storageKey(volume: String, path: String): String {
    val canonicalVolume = volume.lowercase(Locale.ROOT).let { if (it == "primary") PRIMARY else it }
    return canonicalVolume + ":" + path.split('/').filter(String::isNotEmpty).joinToString("/")
}

private fun autoFolder(parent: String): String = AUTO_FOLDER + parent

internal fun automaticFolders(tracks: List<Track>): List<Folder> {
    val folders = linkedMapOf<String, Folder>()
    for (track in tracks) {
        var path = track.folder.removePrefix(AUTO_FOLDER)
        val volume = path.substringBefore(':')
        val separator = path.indexOf(':')
        if (separator >= 0) path = path.substring(separator + 1)
        val segments = path.split('/').filter(String::isNotBlank)
        var parent = AUTO_FOLDER
        fun count(id: String, name: String, ancestor: String) {
            folders[id] = Folder(id, name, ancestor, (folders[id]?.count ?: 0) + 1)
        }
        count(parent, "自动收录", "")
        val parts = if (volume.isNotBlank() && volume != PRIMARY) listOf(volume) + segments else segments
        for ((index, name) in parts.withIndex()) {
            val volumeTitle = volume.isNotBlank() && volume != PRIMARY && index == 0
            val start = if (volume.isNotBlank() && volume != PRIMARY) 1 else 0
            val id = if (volumeTitle) autoFolder("$volume:") else {
                autoFolder("$volume:" + parts.drop(start).take(index - start + 1).joinToString("/"))
            }
            count(id, name, parent)
            parent = id
        }
    }
    return folders.values.toList()
}
