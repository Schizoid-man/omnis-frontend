/*
 * MediaModule - Kotlin TurboModule for Omnis Media Handling
 *
 * Handles file encryption/decryption, chunking, streaming upload/download,
 * and MediaStore integration for saving files to user-visible directories.
 *
 * Encryption scheme (matches web client):
 *   - AES-256-GCM: encrypt the entire file as one blob, then split the
 *     ciphertext into upload-sized chunks (~250 MiB each)
 *   - Same epoch key used for message bodies
 *   - Each file has a unique nonce (12 bytes)
 *   - On download: reassemble chunks, then decrypt the full blob
 */

package com.omnis.app.media

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MediaModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "MediaModule"

        const val AES_KEY_LENGTH = 32       // 256 bits
        const val AES_NONCE_LENGTH = 12     // 96 bits
        const val AES_TAG_BITS = 128        // GCM auth tag length in bits
        const val AES_TAG_BYTES = 16        // GCM auth tag length in bytes
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val secureRandom = SecureRandom()

    override fun getName(): String = NAME

    override fun invalidate() {
        super.invalidate()
        scope.cancel()
    }

    // ========================= Internal Helpers =========================

    private fun base64Encode(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun base64Decode(data: String): ByteArray =
        Base64.decode(data, Base64.DEFAULT)

    private fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    private fun encryptChunk(key: ByteArray, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(AES_TAG_BITS, nonce)
        )
        return cipher.doFinal(plaintext)
    }

    private fun decryptChunk(key: ByteArray, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(AES_TAG_BITS, nonce)
        )
        return cipher.doFinal(ciphertext)
    }

    /**
     * Get the app-private encrypted media cache directory.
     */
    private fun getEncryptedCacheDir(): File {
        val dir = File(reactContext.filesDir, "encrypted_media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Get a temp directory for decrypted files.
     */
    private fun getDecryptedTempDir(): File {
        val dir = File(reactContext.cacheDir, "decrypted_media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ========================= React Native Methods =========================

    /**
     * Generate a random base nonce for file encryption (12 bytes, base64).
     */
    @ReactMethod
    fun generateFileNonce(promise: Promise) {
        scope.launch {
            try {
                val nonce = randomBytes(AES_NONCE_LENGTH)
                promise.resolve(base64Encode(nonce))
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "generateFileNonce failed: ${e.message}", e)
            }
        }
    }

    /**
     * Generate a random file encryption key (32 bytes, base64).
     * This is used as the per-file key; it gets wrapped alongside the message metadata.
     */
    @ReactMethod
    fun generateFileKey(promise: Promise) {
        scope.launch {
            try {
                val key = randomBytes(AES_KEY_LENGTH)
                promise.resolve(base64Encode(key))
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "generateFileKey failed: ${e.message}", e)
            }
        }
    }

    /**
     * Encrypt a file and split the ciphertext into chunks.
     *
     * Matches the web client pipeline: encrypt the entire file as one
     * AES-256-GCM blob then split the ciphertext into upload-sized chunks.
     * This ensures cross-platform compatibility for download / decrypt.
     *
     * @param filePath  Absolute path to the source file
     * @param keyBase64  AES-256 key (base64)
     * @param nonceBase64  Nonce (base64, 12 bytes)
     * @param chunkSize  Max bytes per ciphertext chunk (0 = default 250 MiB)
     *
     * Returns: {
     *   totalChunks: number,
     *   chunkPaths: string[],  // absolute paths to encrypted chunk files
     *   totalSize: number       // total encrypted size
     *   uploadId: string
     * }
     */
    @ReactMethod
    fun encryptAndChunkFile(
        filePath: String,
        keyBase64: String,
        nonceBase64: String,
        chunkSize: Double,
        promise: Promise
    ) {
        scope.launch {
            try {
                val key = base64Decode(keyBase64)
                val nonce = base64Decode(nonceBase64)
                val sourceFile = File(filePath)

                if (!sourceFile.exists()) {
                    promise.reject("MEDIA_ERR", "Source file not found: $filePath")
                    return@launch
                }

                val cacheDir = getEncryptedCacheDir()
                val uploadId = java.util.UUID.randomUUID().toString()
                val chunkDir = File(cacheDir, uploadId)
                chunkDir.mkdirs()

                withContext(Dispatchers.IO) {
                    // Phase 1: encrypt the entire file as one AES-GCM blob
                    val plaintext = sourceFile.readBytes()
                    val encrypted = encryptChunk(key, plaintext, nonce)

                    // Phase 2: split the ciphertext into upload-sized chunks
                    // Default ~250 MiB to match the web client
                    val maxChunk = if (chunkSize > 0) chunkSize.toLong() else 250L * 1024L * 1024L
                    val totalChunks = ((encrypted.size + maxChunk - 1) / maxChunk).toInt()
                        .coerceAtLeast(1)
                    val chunkPaths = Arguments.createArray()
                    var totalEncryptedSize = 0L

                    for (i in 0 until totalChunks) {
                        val start = (i.toLong() * maxChunk).toInt()
                        val end = minOf(start + maxChunk.toInt(), encrypted.size)
                        val slice = encrypted.copyOfRange(start, end)

                        val chunkFile = File(chunkDir, "chunk_$i")
                        FileOutputStream(chunkFile).use { fos -> fos.write(slice) }
                        chunkPaths.pushString(chunkFile.absolutePath)
                        totalEncryptedSize += slice.size
                    }

                    val result = Arguments.createMap().apply {
                        putInt("totalChunks", totalChunks)
                        putArray("chunkPaths", chunkPaths)
                        putDouble("totalSize", totalEncryptedSize.toDouble())
                        putString("uploadId", uploadId)
                    }
                    promise.resolve(result)
                }
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "encryptAndChunkFile failed: ${e.message}", e)
            }
        }
    }

    /**
     * Reassemble encrypted chunks and decrypt back into a file.
     *
     * Matches the web client pipeline: concatenate all ciphertext chunks
     * back into the original AES-256-GCM blob, then decrypt as one unit.
     *
     * @param chunkPaths  Array of absolute paths to encrypted chunk files, ordered by chunk_index
     * @param keyBase64   AES-256 key (base64)
     * @param nonceBase64 Nonce (base64, 12 bytes)
     * @param outputPath  Where to write the decrypted file
     *
     * Returns the output file path.
     */
    @ReactMethod
    fun decryptAndReassembleChunks(
        chunkPaths: com.facebook.react.bridge.ReadableArray,
        keyBase64: String,
        nonceBase64: String,
        outputPath: String,
        promise: Promise
    ) {
        scope.launch {
            try {
                val key = base64Decode(keyBase64)
                val nonce = base64Decode(nonceBase64)
                val outFile = File(outputPath)
                outFile.parentFile?.mkdirs()

                withContext(Dispatchers.IO) {
                    // Phase 1: reassemble all chunks into one ciphertext blob
                    var totalSize = 0L
                    for (i in 0 until chunkPaths.size()) {
                        val f = File(chunkPaths.getString(i))
                        if (!f.exists()) throw Exception("Chunk file not found: ${f.absolutePath}")
                        totalSize += f.length()
                    }

                    val combined = ByteArray(totalSize.toInt())
                    var offset = 0
                    for (i in 0 until chunkPaths.size()) {
                        val chunkBytes = File(chunkPaths.getString(i)).readBytes()
                        System.arraycopy(chunkBytes, 0, combined, offset, chunkBytes.size)
                        offset += chunkBytes.size
                    }

                    // Phase 2: decrypt the full blob (single AES-GCM operation)
                    val decrypted = decryptChunk(key, combined, nonce)

                    // Phase 3: write decrypted plaintext
                    FileOutputStream(outFile).use { fos -> fos.write(decrypted) }
                }

                promise.resolve(outputPath)
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "decryptAndReassembleChunks failed: ${e.message}", e)
            }
        }
    }

    /**
     * Save a decrypted file to the public MediaStore directory based on MIME type.
     *
     * Images → Pictures/Omnis
     * Videos → Movies/Omnis
     * Audio → Music/Omnis
     * PDFs & others → Documents/Omnis
     *
     * Uses MediaStore API on Android 10+ for proper scoped storage compliance.
     *
     * @param sourcePath  Absolute path to the decrypted file
     * @param fileName    Desired file name
     * @param mimeType    MIME type string
     *
     * Returns the content URI of the saved file.
     */
    @ReactMethod
    fun saveToMediaStore(
        sourcePath: String,
        fileName: String,
        mimeType: String,
        promise: Promise
    ) {
        scope.launch {
            try {
                val sourceFile = File(sourcePath)
                if (!sourceFile.exists()) {
                    promise.reject("MEDIA_ERR", "Source file not found: $sourcePath")
                    return@launch
                }

                val (collection, relativePath) = getMediaStoreTarget(mimeType)

                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Use MediaStore API for scoped storage
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }

                        val resolver = reactContext.contentResolver
                        val uri = resolver.insert(collection, values)
                            ?: throw Exception("Failed to create MediaStore entry")

                        resolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(sourceFile).use { fis ->
                                fis.copyTo(out, bufferSize = 8192)
                            }
                        } ?: throw Exception("Failed to open output stream")

                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)

                        promise.resolve(uri.toString())
                    } else {
                        // Legacy storage for older Android versions
                        val dir = Environment.getExternalStoragePublicDirectory(
                            getPublicDirectory(mimeType)
                        )
                        val omnisDir = File(dir, "Omnis")
                        omnisDir.mkdirs()
                        val destFile = File(omnisDir, fileName)

                        FileInputStream(sourceFile).use { fis ->
                            FileOutputStream(destFile).use { fos ->
                                fis.copyTo(fos, bufferSize = 8192)
                            }
                        }

                        promise.resolve(destFile.absolutePath)
                    }
                }
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "saveToMediaStore failed: ${e.message}", e)
            }
        }
    }

    private fun getMediaStoreTarget(mimeType: String): Pair<Uri, String> {
        return when {
            mimeType.startsWith("image/") ->
                Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/Omnis")
            mimeType.startsWith("video/") ->
                Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/Omnis")
            mimeType.startsWith("audio/") ->
                Pair(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/Omnis")
            else ->
                Pair(MediaStore.Files.getContentUri("external"), "Documents/Omnis")
        }
    }

    private fun getPublicDirectory(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOCUMENTS
        }
    }

    /**
     * Get the absolute path to the app's encrypted media cache directory.
     */
    @ReactMethod
    fun getEncryptedCachePath(promise: Promise) {
        promise.resolve(getEncryptedCacheDir().absolutePath)
    }

    /**
     * Get the absolute path to the app's decrypted temp directory.
     */
    @ReactMethod
    fun getDecryptedTempPath(promise: Promise) {
        promise.resolve(getDecryptedTempDir().absolutePath)
    }

    /**
     * Delete encrypted chunk files for a given upload_id.
     */
    @ReactMethod
    fun cleanupChunks(uploadId: String, promise: Promise) {
        scope.launch {
            try {
                val chunkDir = File(getEncryptedCacheDir(), uploadId)
                if (chunkDir.exists()) {
                    chunkDir.deleteRecursively()
                }
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "cleanupChunks failed: ${e.message}", e)
            }
        }
    }

    /**
     * Get file information: size, exists, etc.
     */
    @ReactMethod
    fun getFileInfo(filePath: String, promise: Promise) {
        scope.launch {
            try {
                val file = File(filePath)
                val result = Arguments.createMap().apply {
                    putBoolean("exists", file.exists())
                    putDouble("size", file.length().toDouble())
                    putString("name", file.name)
                    putString("path", file.absolutePath)
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "getFileInfo failed: ${e.message}", e)
            }
        }
    }

    /**
     * Copy a content URI to a local file (for handling picked files).
     */
    @ReactMethod
    fun copyUriToFile(uriString: String, destPath: String, promise: Promise) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val uri = Uri.parse(uriString)
                    val destFile = File(destPath)
                    destFile.parentFile?.mkdirs()

                    reactContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    } ?: throw Exception("Failed to open URI: $uriString")
                }
                promise.resolve(destPath)
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "copyUriToFile failed: ${e.message}", e)
            }
        }
    }

    /**
     * Read a chunk file and return its bytes as base64 (for multipart upload from JS).
     */
    @ReactMethod
    fun readChunkAsBase64(chunkPath: String, promise: Promise) {
        scope.launch {
            try {
                val file = File(chunkPath)
                if (!file.exists()) {
                    promise.reject("MEDIA_ERR", "Chunk file not found: $chunkPath")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val bytes = file.readBytes()
                    promise.resolve(base64Encode(bytes))
                }
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "readChunkAsBase64 failed: ${e.message}", e)
            }
        }
    }

    /**
     * Write base64 data to a file (for storing downloaded chunks).
     */
    @ReactMethod
    fun writeBase64ToFile(base64Data: String, filePath: String, promise: Promise) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val data = base64Decode(base64Data)
                    val file = File(filePath)
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        fos.write(data)
                    }
                }
                promise.resolve(filePath)
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "writeBase64ToFile failed: ${e.message}", e)
            }
        }
    }

    /**
     * Write raw bytes from a downloaded chunk directly to a file.
     * Used to store downloaded encrypted chunks from the server.
     */
    @ReactMethod
    fun writeBytesToFile(data: String, filePath: String, promise: Promise) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = base64Decode(data)
                    val file = File(filePath)
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        fos.write(bytes)
                    }
                }
                promise.resolve(filePath)
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "writeBytesToFile failed: ${e.message}", e)
            }
        }
    }

    /**
     * Generate a thumbnail for a video file.
     * Returns the path to the generated thumbnail image.
     */
    @ReactMethod
    fun generateVideoThumbnail(videoPath: String, promise: Promise) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(videoPath)
                        val bitmap = retriever.getFrameAtTime(
                            0,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )

                        if (bitmap != null) {
                            val thumbnailFile = File(
                                getDecryptedTempDir(),
                                "thumb_${System.currentTimeMillis()}.jpg"
                            )
                            FileOutputStream(thumbnailFile).use { fos ->
                                bitmap.compress(
                                    android.graphics.Bitmap.CompressFormat.JPEG,
                                    80,
                                    fos
                                )
                            }
                            bitmap.recycle()
                            promise.resolve(thumbnailFile.absolutePath)
                        } else {
                            promise.resolve(null)
                        }
                    } finally {
                        retriever.release()
                    }
                }
            } catch (e: Exception) {
                promise.reject("MEDIA_ERR", "generateVideoThumbnail failed: ${e.message}", e)
            }
        }
    }
}
