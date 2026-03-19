package com.omnis.app.media

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager CoroutineWorker for uploading encrypted media chunks in the background.
 *
 * Input data:
 *   - uploadId: String — unique upload identifier
 *   - chatId: Int — chat to associate the upload with
 *   - mimeType: String — file MIME type
 *   - nonceBase64: String — base nonce for chunk encryption
 *   - chunkDir: String — directory containing encrypted chunk files (chunk_0, chunk_1, …)
 *   - totalChunks: Int — total number of chunks
 *   - startChunk: Int — chunk index to resume from (0 to start fresh)
 *   - apiBaseUrl: String — server base URL
 *   - authToken: String — authentication bearer token
 *
 * Output data:
 *   - mediaIds: IntArray — server-assigned media IDs for each uploaded chunk
 *
 * Progress:
 *   - chunksUploaded: Int, totalChunks: Int, progressPercent: Int
 */
class MediaUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "MediaUploadWorker"
        const val KEY_UPLOAD_ID = "uploadId"
        const val KEY_CHAT_ID = "chatId"
        const val KEY_MIME_TYPE = "mimeType"
        const val KEY_NONCE_BASE64 = "nonceBase64"
        const val KEY_CHUNK_DIR = "chunkDir"
        const val KEY_TOTAL_CHUNKS = "totalChunks"
        const val KEY_START_CHUNK = "startChunk"
        const val KEY_API_BASE_URL = "apiBaseUrl"
        const val KEY_AUTH_TOKEN = "authToken"
        const val KEY_MEDIA_IDS = "mediaIds"
        const val KEY_CHUNKS_UPLOADED = "chunksUploaded"
        const val KEY_PROGRESS_PERCENT = "progressPercent"

        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uploadId = inputData.getString(KEY_UPLOAD_ID) ?: return@withContext Result.failure()
        this@MediaUploadWorker.uploadId = uploadId
        val chatId = inputData.getInt(KEY_CHAT_ID, -1)
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: return@withContext Result.failure()
        val nonceBase64 = inputData.getString(KEY_NONCE_BASE64) ?: return@withContext Result.failure()
        val chunkDir = inputData.getString(KEY_CHUNK_DIR) ?: return@withContext Result.failure()
        val totalChunks = inputData.getInt(KEY_TOTAL_CHUNKS, 0)
        val startChunk = inputData.getInt(KEY_START_CHUNK, 0)
        val apiBaseUrl = inputData.getString(KEY_API_BASE_URL) ?: return@withContext Result.failure()
        val authToken = inputData.getString(KEY_AUTH_TOKEN) ?: return@withContext Result.failure()

        if (chatId < 0 || totalChunks <= 0) return@withContext Result.failure()

        val mediaIds = IntArray(totalChunks)
        val chunkDirFile = File(chunkDir)

        try {
            setForeground(createForegroundInfo("Uploading media…"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground info: ${e.message}")
        }

        for (i in startChunk until totalChunks) {
            if (isStopped) {
                Log.i(TAG, "Upload cancelled for $uploadId at chunk $i")
                return@withContext Result.failure(
                    workDataOf(KEY_CHUNKS_UPLOADED to i, KEY_MEDIA_IDS to mediaIds)
                )
            }

            val chunkFile = File(chunkDirFile, "chunk_$i")
            if (!chunkFile.exists()) {
                Log.e(TAG, "Chunk file missing: ${chunkFile.absolutePath}")
                return@withContext Result.failure()
            }

            val chunkBytes = chunkFile.readBytes()
            val chunkBase64 = Base64.encodeToString(chunkBytes, Base64.NO_WRAP)

            var mediaId: Int? = null
            var attempt = 0

            while (attempt < MAX_RETRIES && mediaId == null) {
                attempt++
                try {
                    mediaId = uploadChunk(
                        apiBaseUrl, authToken, chunkBase64, chatId,
                        mimeType, nonceBase64, i, totalChunks, uploadId,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Upload chunk $i attempt $attempt failed: ${e.message}")
                    if (attempt < MAX_RETRIES) {
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                    }
                }
            }

            if (mediaId == null) {
                Log.e(TAG, "Failed to upload chunk $i after $MAX_RETRIES attempts")
                return@withContext Result.retry()
            }

            mediaIds[i] = mediaId

            // Report progress
            setProgress(workDataOf(
                KEY_CHUNKS_UPLOADED to (i + 1),
                KEY_TOTAL_CHUNKS to totalChunks,
                KEY_PROGRESS_PERCENT to ((i + 1) * 100 / totalChunks),
            ))
        }

        // Cleanup chunk directory
        try {
            chunkDirFile.deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup chunks: ${e.message}")
        }

        return@withContext Result.success(
            workDataOf(KEY_MEDIA_IDS to mediaIds)
        )
    }

    /**
     * Upload a single chunk via multipart/form-data POST.
     * Returns the server-assigned media_id.
     */
    private fun uploadChunk(
        apiBaseUrl: String,
        authToken: String,
        chunkBase64: String,
        chatId: Int,
        mimeType: String,
        nonceBase64: String,
        chunkIndex: Int,
        totalChunks: Int,
        uploadId: String,
    ): Int {
        val boundary = "----OmnisMediaBoundary${System.nanoTime()}"
        val url = URL("${apiBaseUrl}/media/upload")
        val conn = url.openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $authToken")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000

        val chunkBytes = Base64.decode(chunkBase64, Base64.NO_WRAP)

        conn.outputStream.use { out ->
            val writer = out.bufferedWriter()

            // Fields
            val fields = mapOf(
                "chat_id" to chatId.toString(),
                "mime_type" to mimeType,
                "nonce" to nonceBase64,
                "chunk_index" to chunkIndex.toString(),
                "total_chunks" to totalChunks.toString(),
                "upload_id" to uploadId,
            )

            for ((key, value) in fields) {
                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n")
                writer.write("$value\r\n")
            }

            // File part
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"chunk_$chunkIndex\"\r\n")
            writer.write("Content-Type: application/octet-stream\r\n\r\n")
            writer.flush()
            out.write(chunkBytes)
            writer.write("\r\n--$boundary--\r\n")
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
            throw Exception("Upload failed with $responseCode: $errorBody")
        }

        val responseBody = conn.inputStream.bufferedReader().readText()
        // Parse media_id from JSON response (simple extraction)
        val mediaIdMatch = Regex("\"media_id\"\\s*:\\s*(\\d+)").find(responseBody)
            ?: throw Exception("media_id not found in response")
        return mediaIdMatch.groupValues[1].toInt()
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .build()

        return ForegroundInfo(notificationId, notification)
    }

    private var uploadId: String? = null
}
