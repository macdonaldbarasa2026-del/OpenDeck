package com.mixfia

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal enum class CatalogMediaType(val label: String, val query: String) {
    ALL("All", "(mediatype:audio OR mediatype:movies)"),
    AUDIO("Audio", "mediatype:audio"),
    VIDEO("Video", "mediatype:movies")
}

internal data class CatalogItem(
    val identifier: String,
    val title: String,
    val creator: String,
    val year: String,
    val mediaType: CatalogMediaType,
    val licenseUrl: String
)

internal data class DownloadRecord(
    val id: Long,
    val title: String,
    val fileName: String,
    val mediaType: CatalogMediaType,
    val licenseUrl: String,
    val status: Int = DownloadManager.STATUS_PENDING,
    val reason: Int = 0
)

internal object ArchiveCatalog {
    private const val BASE_URL = "https://archive.org"
    private const val PREFS_NAME = "mixfia_downloads"
    private const val PREFS_DOWNLOADS = "records"
    private const val MAX_AUDIO_SIZE = 500L * 1024 * 1024
    private const val MAX_VIDEO_SIZE = 2L * 1024 * 1024 * 1024

    private val acceptedLicenseSearch = buildList {
        listOf("http", "https").forEach { scheme ->
            listOf("by", "by-sa").forEach { license ->
                listOf("1.0", "2.0", "2.5", "3.0", "4.0").forEach { version ->
                    add("licenseurl:\"$scheme://creativecommons.org/licenses/$license/$version/\"")
                }
            }
            add("licenseurl:\"$scheme://creativecommons.org/publicdomain/zero/1.0/\"")
            add("licenseurl:\"$scheme://creativecommons.org/publicdomain/mark/1.0/\"")
        }
    }.joinToString(" OR ", prefix = "(", postfix = ")")

    suspend fun search(query: String, mediaType: CatalogMediaType): List<CatalogItem> =
        withContext(Dispatchers.IO) {
            val escapedQuery = query.trim()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            val searchExpression = "${mediaType.query} AND \"$escapedQuery\" AND $acceptedLicenseSearch"
            val request = Uri.parse("$BASE_URL/advancedsearch.php").buildUpon()
                .appendQueryParameter("q", searchExpression)
                .appendQueryParameter("fl[]", "identifier")
                .appendQueryParameter("fl[]", "title")
                .appendQueryParameter("fl[]", "creator")
                .appendQueryParameter("fl[]", "year")
                .appendQueryParameter("fl[]", "mediatype")
                .appendQueryParameter("fl[]", "licenseurl")
                .appendQueryParameter("rows", "40")
                .appendQueryParameter("page", "1")
                .appendQueryParameter("output", "json")
                .build()
            val docs = requestJson(request.toString())
                .optJSONObject("response")
                ?.optJSONArray("docs") ?: JSONArray()

            buildList {
                for (index in 0 until docs.length()) {
                    val doc = docs.optJSONObject(index) ?: continue
                    val identifier = doc.optString("identifier")
                    val itemType = when (doc.optString("mediatype")) {
                        "audio" -> CatalogMediaType.AUDIO
                        "movies" -> CatalogMediaType.VIDEO
                        else -> continue
                    }
                    val licenseUrl = doc.optString("licenseurl")
                    if (identifier.isBlank() || !isOpenLicense(licenseUrl)) continue
                    add(
                        CatalogItem(
                            identifier = identifier,
                            title = doc.optString("title").ifBlank { "Untitled" },
                            creator = doc.optString("creator").ifBlank { "Unknown creator" },
                            year = doc.optString("year"),
                            mediaType = itemType,
                            licenseUrl = licenseUrl
                        )
                    )
                }
            }
        }

    suspend fun enqueueDownload(
        context: Context,
        item: CatalogItem,
        wifiOnly: Boolean
    ): Long = withContext(Dispatchers.IO) {
        require(item.identifier.matches(Regex("[A-Za-z0-9_-]+"))) {
            "This catalog item has an invalid identifier."
        }
        require(isOpenLicense(item.licenseUrl)) {
            "This item does not have a supported open license."
        }

        val metadata = requestJson("$BASE_URL/metadata/${Uri.encode(item.identifier)}")
        val metadataObject = metadata.optJSONObject("metadata")
            ?: throw IllegalStateException("Catalog metadata is unavailable.")
        val actualLicense = metadataObject.optString("licenseurl")
        require(isOpenLicense(actualLicense)) {
            "A supported open license could not be verified for this item."
        }

        val files = metadata.optJSONArray("files")
            ?: throw IllegalStateException("No downloadable files were found.")
        val file = chooseFile(files, item.mediaType)
            ?: throw IllegalStateException("No supported audio or video file was found.")
        val originalName = file.optString("name")
        val safeName = originalName.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
        val destinationName = "${item.identifier.take(40)}-$safeName"
        val downloadUri = Uri.parse(
            "$BASE_URL/download/${Uri.encode(item.identifier)}/${Uri.encode(originalName, "/")}"
        )
        val request = DownloadManager.Request(downloadUri)
            .setTitle(item.title)
            .setDescription("Internet Archive · ${item.mediaType.label} · open license")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(!wifiOnly)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                destinationName
            )

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)
        saveRecord(
            context,
            DownloadRecord(
                id = downloadId,
                title = item.title,
                fileName = destinationName,
                mediaType = item.mediaType,
                licenseUrl = actualLicense
            )
        )
        downloadId
    }

    suspend fun loadDownloads(context: Context): List<DownloadRecord> =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            readRecords(context).map { record ->
                val cursor = manager.query(DownloadManager.Query().setFilterById(record.id))
                cursor?.use {
                    if (it.moveToFirst()) {
                        record.copy(
                            status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                            reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        )
                    } else {
                        record.copy(status = DownloadManager.STATUS_FAILED)
                    }
                } ?: record.copy(status = DownloadManager.STATUS_FAILED)
            }.reversed()
        }

    fun openDownload(context: Context, id: Long): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(id) ?: return false
        val record = readRecords(context).firstOrNull { it.id == id } ?: return false
        val mimeType = if (record.mediaType == CatalogMediaType.VIDEO) "video/*" else "audio/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun isOpenLicense(licenseUrl: String): Boolean {
        val uri = runCatching { Uri.parse(licenseUrl) }.getOrNull() ?: return false
        if (uri.host != "creativecommons.org") return false
        val path = uri.path.orEmpty()
        return path.startsWith("/licenses/by/") ||
            path.startsWith("/licenses/by-sa/") ||
            path.startsWith("/publicdomain/zero/") ||
            path.startsWith("/publicdomain/mark/")
    }

    private fun chooseFile(files: JSONArray, mediaType: CatalogMediaType): JSONObject? {
        val allowedExtensions = if (mediaType == CatalogMediaType.VIDEO) {
            listOf("mp4", "m4v", "webm", "mov")
        } else {
            listOf("mp3", "m4a", "ogg", "oga", "flac", "aac")
        }
        val maxSize = if (mediaType == CatalogMediaType.VIDEO) MAX_VIDEO_SIZE else MAX_AUDIO_SIZE
        return (0 until files.length())
            .mapNotNull { files.optJSONObject(it) }
            .filter { file ->
                val name = file.optString("name")
                val extension = name.substringAfterLast('.', "").lowercase()
                val size = file.optString("size").toLongOrNull() ?: 0L
                file.optString("private") != "true" &&
                    extension in allowedExtensions &&
                    size in 1..maxSize
            }
            .minByOrNull { file ->
                val extension = file.optString("name").substringAfterLast('.', "").lowercase()
                allowedExtensions.indexOf(extension)
            }
    }

    private fun requestJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Mixfia/1.0 (Android media catalog)")
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Catalog request failed ($status).")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun saveRecord(context: Context, record: DownloadRecord) {
        val records = readRecords(context).filterNot { it.id == record.id }
        val array = JSONArray()
        (records + record).takeLast(100).forEach { value ->
            array.put(
                JSONObject()
                    .put("id", value.id)
                    .put("title", value.title)
                    .put("fileName", value.fileName)
                    .put("mediaType", value.mediaType.name)
                    .put("licenseUrl", value.licenseUrl)
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_DOWNLOADS, array.toString())
            .apply()
    }

    private fun readRecords(context: Context): List<DownloadRecord> {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_DOWNLOADS, "[]") ?: "[]"
        val array = runCatching { JSONArray(value) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val record = array.optJSONObject(index) ?: continue
                val type = runCatching {
                    CatalogMediaType.valueOf(record.optString("mediaType"))
                }.getOrNull() ?: continue
                add(
                    DownloadRecord(
                        id = record.optLong("id"),
                        title = record.optString("title"),
                        fileName = record.optString("fileName"),
                        mediaType = type,
                        licenseUrl = record.optString("licenseUrl")
                    )
                )
            }
        }
    }
}