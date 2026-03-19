package com.omnis.app.media

import android.util.Log
import androidx.work.*
import com.facebook.react.bridge.*
import java.util.concurrent.TimeUnit

/**
 * React Native bridge exposing WorkManager-based background media transfers.
 * JS code calls enqueueUpload / enqueueDownload; progress and results are
 * observed through the MediaModule's progress-notification events.
 */
class MediaWorkerModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val TAG = "MediaWorkerModule"
    }

    override fun getName(): String = "MediaWorkerModule"

    /**
     * Enqueue a background upload job for a set of pre-encrypted chunks.
     *
     * @param params ReadableMap with keys:
     *   uploadId, chatId, mimeType, nonceBase64, chunkDir,
     *   totalChunks, startChunk, apiBaseUrl, authToken
     */
    @ReactMethod
    fun enqueueUpload(params: ReadableMap, promise: Promise) {
        try {
            val uploadId = params.getString("uploadId") ?: throw IllegalArgumentException("uploadId required")
            val chatId = params.getInt("chatId")
            val mimeType = params.getString("mimeType") ?: ""
            val nonceBase64 = params.getString("nonceBase64") ?: ""
            val chunkDir = params.getString("chunkDir") ?: ""
            val totalChunks = params.getInt("totalChunks")
            val startChunk = if (params.hasKey("startChunk")) params.getInt("startChunk") else 0
            val apiBaseUrl = params.getString("apiBaseUrl") ?: ""
            val authToken = params.getString("authToken") ?: ""

            val inputData = Data.Builder()
                .putString(MediaUploadWorker.KEY_UPLOAD_ID, uploadId)
                .putInt(MediaUploadWorker.KEY_CHAT_ID, chatId)
                .putString(MediaUploadWorker.KEY_MIME_TYPE, mimeType)
                .putString(MediaUploadWorker.KEY_NONCE_BASE64, nonceBase64)
                .putString(MediaUploadWorker.KEY_CHUNK_DIR, chunkDir)
                .putInt(MediaUploadWorker.KEY_TOTAL_CHUNKS, totalChunks)
                .putInt(MediaUploadWorker.KEY_START_CHUNK, startChunk)
                .putString(MediaUploadWorker.KEY_API_BASE_URL, apiBaseUrl)
                .putString(MediaUploadWorker.KEY_AUTH_TOKEN, authToken)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .addTag("media_upload_$uploadId")
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(reactApplicationContext)
                .enqueueUniqueWork(
                    "upload_$uploadId",
                    ExistingWorkPolicy.KEEP,
                    request,
                )

            val result = Arguments.createMap().apply {
                putString("workId", request.id.toString())
                putString("uploadId", uploadId)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            Log.e(TAG, "enqueueUpload failed", e)
            promise.reject("ENQUEUE_UPLOAD_ERROR", e.message, e)
        }
    }

    /**
     * Enqueue a background download job.
     *
     * @param params ReadableMap with keys:
     *   uploadId, mediaIds (array of ints), apiBaseUrl, authToken, outputDir (optional)
     */
    @ReactMethod
    fun enqueueDownload(params: ReadableMap, promise: Promise) {
        try {
            val uploadId = params.getString("uploadId") ?: throw IllegalArgumentException("uploadId required")
            val mediaIdsArray = params.getArray("mediaIds") ?: throw IllegalArgumentException("mediaIds required")
            val apiBaseUrl = params.getString("apiBaseUrl") ?: ""
            val authToken = params.getString("authToken") ?: ""
            val outputDir = params.getString("outputDir") ?: ""

            val mediaIds = IntArray(mediaIdsArray.size()) { mediaIdsArray.getInt(it) }

            val inputData = Data.Builder()
                .putString(MediaDownloadWorker.KEY_UPLOAD_ID, uploadId)
                .putIntArray(MediaDownloadWorker.KEY_MEDIA_IDS, mediaIds)
                .putString(MediaDownloadWorker.KEY_API_BASE_URL, apiBaseUrl)
                .putString(MediaDownloadWorker.KEY_AUTH_TOKEN, authToken)
                .putString(MediaDownloadWorker.KEY_OUTPUT_DIR, outputDir)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .addTag("media_download_$uploadId")
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(reactApplicationContext)
                .enqueueUniqueWork(
                    "download_$uploadId",
                    ExistingWorkPolicy.KEEP,
                    request,
                )

            val result = Arguments.createMap().apply {
                putString("workId", request.id.toString())
                putString("uploadId", uploadId)
            }
            promise.resolve(result)
        } catch (e: Exception) {
            Log.e(TAG, "enqueueDownload failed", e)
            promise.reject("ENQUEUE_DOWNLOAD_ERROR", e.message, e)
        }
    }

    /**
     * Cancel a background transfer by uploadId.
     */
    @ReactMethod
    fun cancelTransfer(uploadId: String, promise: Promise) {
        try {
            val wm = WorkManager.getInstance(reactApplicationContext)
            wm.cancelUniqueWork("upload_$uploadId")
            wm.cancelUniqueWork("download_$uploadId")
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("CANCEL_ERROR", e.message, e)
        }
    }
}
