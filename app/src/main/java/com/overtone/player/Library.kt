package com.overtone.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.util.Locale

val formats = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "mp4", "mka", "webm", "amr", "awb", "ac3", "ec3")
data class Folder(val id: String, val title: String, val parent: String, val count: Int = 0)
data class Track(
    val uri: String, val file: String, val folder: String, val title: String = file.substringBeforeLast('.'),
    val artist: String = "", val album: String = "", val duration: Long = 0, val lyrics: String = "", val artwork: String = "", val storageKey: String = ""
) {
    val favoriteKey get() = storageKey.ifBlank { uri }
    val extension get() = file.substringAfterLast('.', "").uppercase(Locale.ROOT)
    fun json() = JSONArray(listOf(uri, file, folder, title, artist, album, duration, lyrics, artwork, storageKey))
    fun mediaItem() = MediaItem.Builder().setMediaId(uri).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
            .setExtras(android.os.Bundle().apply { putString("file", file); putString("folder", folder); putLong("duration", duration); putString("lyrics", lyrics); putString("artwork", artwork); putString("storageKey", storageKey) })
            .setIsPlayable(true).build()).build()
    companion object {
        fun fromJson(a: JSONArray) = Track(a.getString(0), a.getString(1), a.getString(2), a.getString(3), a.getString(4), a.getString(5), a.getLong(6), a.optString(7), a.optString(8), a.optString(9))
        fun fromItem(item: MediaItem): Track {
            val m = item.mediaMetadata
            return Track(item.mediaId, m.extras?.getString("file") ?: "", m.extras?.getString("folder") ?: "",
                m.title?.toString() ?: "", m.artist?.toString().orEmpty(), m.albumTitle?.toString().orEmpty(),
                m.extras?.getLong("duration") ?: 0, m.extras?.getString("lyrics").orEmpty(), m.extras?.getString("artwork").orEmpty(), m.extras?.getString("storageKey").orEmpty())
        }
    }
}
internal data class ImportCandidate(val track: Track, val reason: ImportReason, val skipped: Boolean = false) {
    val excluded get() = skipped || reason.disposition == ImportDisposition.SKIP
    val label get() = if (skipped) "已手动跳过" else reason.label
    fun json() = JSONArray(listOf(track.json(), reason.name, skipped))

    companion object {
        fun fromJson(json: JSONArray) = ImportCandidate(
            Track.fromJson(json.getJSONArray(0)), ImportReason.valueOf(json.getString(1)), json.optBoolean(2)
        )
    }
}
internal data class Library(
    val folders: List<Folder> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val failed: List<String> = emptyList(),
    val review: List<ImportCandidate> = emptyList(),
)

private val libraryScanLock = Mutex()

internal class MusicLibrary(private val context: Context) {
    internal val deviceMusic = DeviceMusic(context)
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto_scan", false)
        set(value) { prefs.edit().putBoolean("auto_scan", value).apply() }
    val roots get() = (prefs.getStringSet("roots", emptySet()) ?: emptySet()).sorted()
    fun add(uri: Uri) { prefs.edit().putStringSet("roots", roots.toSet() + uri.toString()).apply() }
    fun remove(uri: String) {
        prefs.edit().putStringSet("roots", roots.toSet() - uri).apply()
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun setImportDecision(track: Track, include: Boolean) {
        val key = track.favoriteKey
        val approved = prefs.getStringSet("approved_audio", emptySet()).orEmpty()
        val skipped = prefs.getStringSet("skipped_audio", emptySet()).orEmpty()
        prefs.edit()
            .putStringSet("approved_audio", if (include) approved + key else approved - key)
            .putStringSet("skipped_audio", if (include) skipped - key else skipped + key)
            .apply()
    }
    fun load(): Library = runCatching {
        val raw = JSONArray(context.filesDir.resolve("library.json").readText())
        val folders = raw.getJSONArray(0)
        val tracks = raw.getJSONArray(1)
        val cachedReview = raw.optJSONArray(2)
        val autoAccessible = autoEnabled && deviceMusic.hasPermission && cachedReview != null
        Library((0 until folders.length()).map {
            val f = folders.getJSONArray(it)
            Folder(f.getString(0), f.getString(1), f.getString(2), f.getInt(3))
        }.filter { autoAccessible || !it.id.startsWith(AUTO_FOLDER) },
            (0 until tracks.length()).map { Track.fromJson(tracks.getJSONArray(it)) }
                .filter { autoAccessible || !it.folder.startsWith(AUTO_FOLDER) },
            review = if (autoAccessible) {
                (0 until cachedReview.length()).map { ImportCandidate.fromJson(cachedReview.getJSONArray(it)) }
            } else emptyList())
    }.getOrDefault(Library())

    suspend fun scan(previousManual: Library? = null, favoriteKeys: Set<String> = emptySet()): Library = libraryScanLock.withLock { withContext(Dispatchers.IO) {
        val scanContext = coroutineContext
        val folders = previousManual?.folders.orEmpty().filterNot { it.id.startsWith(AUTO_FOLDER) }.toMutableList()
        val tracks = previousManual?.tracks.orEmpty().filterNot { it.folder.startsWith(AUTO_FOLDER) }.toMutableList()
        val failed = previousManual?.failed.orEmpty().filterNot { it == "自动收录" }.toMutableList()
        val col = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME, Docs.Document.COLUMN_MIME_TYPE)
        for (root in if (previousManual == null) roots else emptyList()) {
            scanContext.ensureActive()
            val tree = Uri.parse(root)
            val rootId = Docs.getTreeDocumentId(tree)
            val base = Docs.buildDocumentUriUsingTree(tree, rootId)
            val label = runCatching { resolver.query(base, arrayOf(Docs.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: rootId.substringAfterLast(':')
            val seen = HashSet<String>()
            var inaccessible = false
            fun scanFolder(id: String, title: String, parent: String, depth: Int): Int {
                if (depth > 45 || !seen.add(id)) return 0
                val folderUri = Docs.buildDocumentUriUsingTree(tree, id).toString()
                val dirs = mutableListOf<Pair<String, String>>()
                data class Entry(val name: String, val docId: String)
                val entries = mutableListOf<Entry>()
                try {
                    val children = Docs.buildChildDocumentsUriUsingTree(tree, id)
                    val cursor = resolver.query(children, col, null, null, null) ?: error("Folder unavailable")
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            val docId = c.getString(0) ?: continue
                            val name = c.getString(1) ?: continue
                            if (c.getString(2) == Docs.Document.MIME_TYPE_DIR) dirs.add(docId to name)
                            else entries += Entry(name, docId)
                        }
                    }
                } catch (_: Exception) { inaccessible = true; return 0 }
                val lrc = entries.filter { it.name.endsWith(".lrc", true) }.associateBy { it.name.substringBeforeLast('.').lowercase(Locale.ROOT) }
                val artwork = entries.filter { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp") }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                    .associateBy { it.name.substringBeforeLast('.').lowercase(Locale.ROOT) }
                val folderCover = listOf("cover", "folder", "front", "album", "封面").firstNotNullOfOrNull(artwork::get)
                val audio = entries.filter { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in formats }
                for ((file, fileId) in audio) {
                    scanContext.ensureActive()
                    val stableKey = documentStorageKey(tree, fileId)
                    val uri = Docs.buildDocumentUriUsingTree(tree, fileId)
                    val lyric = lrc[file.substringBeforeLast('.').lowercase(Locale.ROOT)]?.let { Docs.buildDocumentUriUsingTree(tree, it.docId).toString() }.orEmpty()
                    val coverFile = artwork[file.substringBeforeLast('.').lowercase(Locale.ROOT)] ?: folderCover
                    val coverUri = coverFile?.let { Docs.buildDocumentUriUsingTree(tree, it.docId).toString() }.orEmpty()
                    val mmr = MediaMetadataRetriever()
                    val track = try {
                        mmr.setDataSource(context, uri)
                        Track(uri.toString(), file, folderUri,
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: file.substringBeforeLast('.'),
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0, lyric, coverUri, stableKey)
                    } catch (_: Exception) { Track(uri.toString(), file, folderUri, lyrics = lyric, artwork = coverUri, storageKey = stableKey) }
                    finally { runCatching { mmr.release() } }
                    tracks += track
                }
                val count = audio.size + dirs.sumOf { (child, name) -> scanFolder(child, name, folderUri, depth + 1) }
                if (count > 0 || depth == 0) folders += Folder(folderUri, title, parent, count)
                return count
            }
            scanFolder(rootId, label, "", 0)
            if (inaccessible) failed += label
        }
        val manual = tracks.distinctBy(Track::uri)
        val occupied = manual.mapNotNull { it.storageKey.takeIf(String::isNotBlank) }.toSet()
        val candidates = if (autoEnabled && deviceMusic.hasPermission) {
            try {
                deviceMusic.tracks { scanContext.ensureActive() }.filterNot { it.track.storageKey in occupied }
                    .distinctBy { it.track.favoriteKey }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                failed += "自动收录"
                emptyList()
            }
        } else emptyList()
        val approved = prefs.getStringSet("approved_audio", emptySet()).orEmpty()
        val skipped = prefs.getStringSet("skipped_audio", emptySet()).orEmpty()
        val indexed = ArrayList<Track>()
        val review = ArrayList<ImportCandidate>()
        candidates.forEach { candidate ->
            scanContext.ensureActive()
            val track = candidate.track
            val key = track.favoriteKey
            when {
                key in skipped -> review += candidate.copy(skipped = true)
                key in approved || key in favoriteKeys || track.uri in favoriteKeys -> indexed += track
                candidate.reason.disposition == ImportDisposition.MUSIC -> indexed += track
                else -> review += candidate
            }
        }
        val result = Library((folders + automaticFolders(indexed)).sortedBy { it.title.lowercase(Locale.ROOT) },
            (manual + indexed).sortedBy { it.file.lowercase(Locale.ROOT) }, failed,
            review.sortedBy { it.track.file.lowercase(Locale.ROOT) })
        scanContext.ensureActive()
        runCatching {
            val f = JSONArray(result.folders.map { JSONArray(listOf(it.id, it.title, it.parent, it.count)) })
            val t = JSONArray(result.tracks.map { it.json() })
            val pending = JSONArray(result.review.map { it.json() })
            android.util.AtomicFile(context.filesDir.resolve("library.json")).run {
                val out = startWrite()
                try { out.write(JSONArray(listOf(f, t, pending)).toString().toByteArray()); finishWrite(out) }
                catch (e: Exception) { failWrite(out); throw e }
            }
        }
        result
    } }
}
val demoTracks = listOf(
    Track("asset:///demo/blue-hour.m4a", "01 Blue Hour.m4a", "demo", "Blue Hour", "Overtone Sessions", "相位练习", 36000),
    Track("asset:///demo/afterimage.m4a", "02 Afterimage.m4a", "demo", "Afterimage", "Overtone Sessions", "相位练习", 36000)
)
