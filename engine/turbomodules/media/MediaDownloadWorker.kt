package com.omnis.app.media

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager CoroutineWorker for downloading encrypted media chunks in the background.
 *
 * Input data:
 *   - uploadId: String — unique identifier for the media
 *   - mediaIds: IntArray — server media IDs for each chunk (in order)
 *   - apiBaseUrl: String — server base URL
 *   - authToken: String — authentication bearer token
 *   - outputDir: String — directory to write downloaded encrypted chunks
 *
 * Output data:
 *   - chunkDir: String — directory containing downloaded chunk files
 *   - totalChunks: Int
 *
 * Progress:
 *   - chunksDownloaded: Int, totalChunks: Int, progressPercent: Int
 */
class MediaDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "MediaDownloadWorker"
        const val KEY_UPLOAD_ID = "uploadId"
        const val KEY_MEDIA_IDS = "mediaIds"
        const val KEY_API_BASE_URL = "apiBaseUrl"
        const val KEY_AUTH_TOKEN = "authToken"
        const val KEY_OUTPUT_DIR = "outputDir"
        const val KEY_CHUNK_DIR = "chunkDir"
        const val KEY_TOTAL_CHUNKS = "totalChunks"
        const val KEY_CHUNKS_DOWNLOADED = "chunksDownloaded"
        const val KEY_PROGRESS_PERCENT = "progressPercent"

        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uploadId = inputData.getString(KEY_UPLOAD_ID) ?: return@withContext Result.failure()
        this@MediaDownloadWorker.uploadId = uploadId
        val mediaIds = inputData.getIntArray(KEY_MEDIA_IDS) ?: return@withContext Result.failure()
        val apiBaseUrl = inputData.getString(KEY_API_BASE_URL) ?: return@withContext Result.failure()
        val authToken = inputData.getString(KEY_AUTH_TOKEN) ?: return@withContext Result.failure()
        val outputDir = inputData.getString(KEY_OUTPUT_DIR)
            ?: "${applicationContext.cacheDir}/omnis_media/$uploadId"

        if (mediaIds.isEmpty()) return@withContext Result.failure()

        val totalChunks = mediaIds.size
        val chunkDirFile = File(outputDir)
        chunkDirFile.mkdirs()

        try {
            setForeground(createForegroundInfo("Downloading media…"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground info: ${e.message}")
        }

        for (i in 0 until totalChunks) {
            if (isStopped) {
                Log.i(TAG, "Download cancelled for $uploadId at chunk $i")
                return@withContext Result.failure(
                    workDataOf(KEY_CHUNKS_DOWNLOADED to i)
                )
            }

            val chunkFile = File(chunkDirFile, "chunk_$i")
            // Skip if already downloaded (resume support)
            if (chunkFile.exists() && chunkFile.length() > 0) {
                setProgress(workDataOf(
                    KEY_CHUNKS_DOWNLOADED to (i + 1),
                    KEY_TOTAL_CHUNKS to totalChunks,
                    KEY_PROGRESS_PERCENT to ((i + 1) * 100 / totalChunks),
                ))
                continue
            }

            var downloaded = false
            var attempt = 0

            while (attempt < MAX_RETRIES && !downloaded) {
                attempt++
                try {
                    val chunkBytes = downloadChunk(apiBaseUrl, authToken, mediaIds[i])
                    chunkFile.writeBytes(chunkBytes)
                    downloaded = true
                } catch (e: Exception) {
                    Log.w(TAG, "Download chunk $i attempt $attempt failed: ${e.message}")
                    if (attempt < MAX_RETRIES) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                    }
                }
            }

            if (!downloaded) {
                Log.e(TAG, "Failed to download chunk $i after $MAX_RETRIES attempts")
                return@withContext Result.retry()
            }

            setProgress(workDataOf(
                KEY_CHUNKS_DOWNLOADED to (i + 1),
                KEY_TOTAL_CHUNKS to totalChunks,
                KEY_PROGRESS_PERCENT to ((i + 1) * 100 / totalChunks),
            ))
        }

        return@withContext Result.success(
            workDataOf(
                KEY_CHUNK_DIR to chunkDirFile.absolutePath,
                KEY_TOTAL_CHUNKS to totalChunks,
            )
        )
    }

    /**
     * Download a single chunk by media_id.
     * Returns the raw encrypted bytes.
     */
    private fun downloadChunk(
        apiBaseUrl: String,
        authToken: String,
        mediaId: Int,
    ): ByteArray {
        val url = URL("${apiBaseUrl}/media/download/$mediaId")
        val conn = url.openConnection() as HttpURLConnection

        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $authToken")
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
            throw Exception("Download failed with $responseCode: $errorBody")
        }

        return conn.inputStream.readBytes()
    }

    /**
     * Minimal foreground notification required by WorkManager.
     * User-facing progress is managed by @notifee/react-native on the TS side.
     */
    private fun createForegroundInfo(title: String): ForegroundInfo {
        val channelId = "omnis_worker_fg"
        val notificationId = (uploadId?.hashCode() ?: System.currentTimeMillis().toInt()) and 0x7FFFFFFF

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Background Tasks",
                android.app.NotificationManager.IMPORTANCE_MIN,
            )
            channel.setShowBadge(false)
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()

        return ForegroundInfo(notificationId, notification)
    }

    private var uploadId: String? = null
}
