/*
 * CryptoModule - Kotlin TurboModule for Omnis
 *
 * Offloads all heavy cryptographic operations to native code with
 * multithreaded execution via Kotlin coroutines (Dispatchers.Default).
 *
 * Algorithms used:
 *   - EC P-384 (secp384r1) for ECDH key agreement
 *   - AES-256-GCM for symmetric encryption
 *   - PBKDF2-HMAC-SHA256 for password-based key derivation
 *   - HKDF-SHA256 (RFC 5869) for key derivation from shared secrets
 *   - SHA-256 for hashing
 *
 * All key formats (SPKI for public, PKCS8 for private) are compatible
 * with WebCrypto and @noble libraries used on the web client.
 */

package com.omnis.app.crypto

import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class CryptoModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "CryptoModule"

        // Must match engine/constants.ts
        const val PBKDF2_ITERATIONS = 100000
        const val PBKDF2_SALT_LENGTH = 32
        const val AES_KEY_LENGTH = 32       // 256 bits
        const val AES_NONCE_LENGTH = 12     // 96 bits
        const val AES_TAG_BITS = 128        // GCM auth tag length
        const val HKDF_INFO = "epoch-key-wrap"
    }

    /**
     * Coroutine scope using Dispatchers.Default (thread pool sized to CPU core count)
     * for parallel execution of CPU-bound crypto operations.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Thread-safe CSPRNG instance */
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

    // ========================= HMAC-SHA256 =========================

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ========================= HKDF (RFC 5869) =========================

    /**
     * HKDF using HMAC-SHA256, compatible with WebCrypto HKDF and @noble/hashes/hkdf.
     */
    private fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        // HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(effectiveSalt, ikm)

        // HKDF-Expand: OKM = T(1) || T(2) || ...
        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            val input = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, input, 0, t.size)
            System.arraycopy(info, 0, input, t.size, info.size)
            input[input.size - 1] = i.toByte()

            t = hmacSha256(prk, input)
            val toCopy = minOf(hashLen, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
        }

        return okm
    }

    // ========================= PBKDF2 =========================

    /**
     * Derive AES-256 key from password using PBKDF2-HMAC-SHA256.
     * Compatible with WebCrypto PBKDF2 and @noble/hashes/pbkdf2.
     */
    private fun deriveKeyFromPassword(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            AES_KEY_LENGTH * 8
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // ========================= AES-GCM =========================

    /**
     * AES-256-GCM encrypt. Returns ciphertext || 16-byte auth tag (standard layout).
     */
    private fun encryptAesGcm(
        key: ByteArray,
        plaintext: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(AES_TAG_BITS, nonce)
        )
        return cipher.doFinal(plaintext)
    }

    /**
     * AES-256-GCM decrypt. Expects ciphertext || 16-byte auth tag.
     */
    private fun decryptAesGcm(
        key: ByteArray,
        ciphertext: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(AES_TAG_BITS, nonce)
        )
        return cipher.doFinal(ciphertext)
    }

    // ========================= ECDH Key Handling =========================

    private fun loadPrivateKey(pkcs8Base64: String): PrivateKey {
        val keySpec = PKCS8EncodedKeySpec(base64Decode(pkcs8Base64))
        return KeyFactory.getInstance("EC").generatePrivate(keySpec)
    }

    private fun loadPublicKey(spkiBase64: String): PublicKey {
        val keySpec = X509EncodedKeySpec(base64Decode(spkiBase64))
        return KeyFactory.getInstance("EC").generatePublic(keySpec)
    }

    /**
     * Derive a 256-bit AES wrapping key from an ECDH shared secret.
     *
     * 1. ECDH P-384 → 48-byte x-coordinate (shared secret)
     * 2. HKDF-SHA256(ikm=sharedX, salt=zeros(32), info="epoch-key-wrap") → 32-byte wrapping key
     *
     * Compatible with both WebCrypto and @noble/curves ECDH derivation.
     */
    private fun deriveWrappingKey(
        myPrivateKey: PrivateKey,
        peerPublicKey: PublicKey
    ): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        // generateSecret() returns the raw x-coordinate (48 bytes for P-384)
        val sharedSecret = keyAgreement.generateSecret()

        return hkdf(
            ikm = sharedSecret,
            salt = ByteArray(32),
            info = HKDF_INFO.toByteArray(Charsets.UTF_8),
            length = 32
        )
    }

    // ========================= React Native Methods =========================

    /**
     * Generate a 256-bit random AES key, returned as base64.
     */
    @ReactMethod
    fun generateAESKey(promise: Promise) {
        scope.launch {
            try {
                val key = randomBytes(AES_KEY_LENGTH)
                promise.resolve(base64Encode(key))
            } catch (e: Exception) {
                promise.reject("CRYPTO_ERR", "generateAESKey failed: ${e.message}", e)
            }
        }
    }

    /**
     * Generate an EC P-384 identity key pair for ECDH.
     * Returns { publicKey: base64(SPKI), privateKey: base64(PKCS8) }.
     */
    @ReactMethod
    fun generateIdentityKeyPair(promise: Promise) {
        scope.launch {
            try {
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(ECGenParameterSpec("secp384r1"), secureRandom)
                val keyPair = kpg.generateKeyPair()

                val result = Arguments.createMap().apply {
                    putString("publicKey", base64Encode(keyPair.public.encoded))
                    putString("privateKey", base64Encode(keyPair.private.encoded))
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("CRYPTO_ERR", "generateIdentityKeyPair failed: ${e.message}", e)
            }
        }
    }

    /**
     * Encrypt plaintext string with an AES-256-GCM epoch key.
     * Returns { ciphertext: base64, nonce: base64 }.
     */
    @ReactMethod
    fun aesGcmEncrypt(plaintext: String, epochKeyBase64: String, promise: Promise) {
        scope.launch {
            try {
                val epochKey = base64Decode(epochKeyBase64)
                val nonce = randomBytes(AES_NONCE_LENGTH)
                val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
                val ciphertext = encryptAesGcm(epochKey, plaintextBytes, nonce)

                val result = Arguments.createMap().apply {
                    putString("ciphertext", base64Encode(ciphertext))
                    putString("nonce", base64Encode(nonce))
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("CRYPTO_ERR", "aesGcmEncrypt failed: ${e.message}", e)
            }
        }
    }

    /**
     * Decrypt AES-256-GCM ciphertext with an epoch key.
     * Returns the plaintext string.
     */
    @ReactMethod
    fun aesGcmDecrypt(
        ciphertextBase64: String,
        nonceBase64: String,
        epochKeyBase64: String,
        promise: Promise
    ) {
        scope.launch {
            try {
                val ciphertext = base64Decode(ciphertextBase64)
                val nonce = base64Decode(nonceBase64)
                val epochKey = base64Decode(epochKeyBase64)
                val plaintext = decryptAesGcm(epochKey, ciphertext, nonce)
                promise.resolve(String(plaintext, Charsets.UTF_8))
            } catch (e: Exception) {
                promise.reject("CRYPTO_ERR", "aesGcmDecrypt failed: ${e.message}", e)
            }
        }
    }

    /**
     * Encrypt an identity private key with a password using PBKDF2 + AES-GCM.
     * Returns { encrypted: base64, salt: base64, nonce: base64 }.
     */
    @ReactMethod
    fun encryptIdentityPrivateKey(
        privateKeyBase64: String,
        password: String,
        promise: Promise
    ) {
        scope.launch {
            try {
                val salt = randomBytes(PBKDF2_SALT_LENGTH)
                val nonce = randomBytes(AES_NONCE_LENGTH)
                val derivedKey = deriveKeyFromPassword(password, salt)
                val plaintextBytes = privateKeyBase64.toByteArray(Charsets.UTF_8)
                val ciphertext = encryptAesGcm(derivedKey, plaintextBytes, nonce)

                val result = Arguments.createMap().apply {
                    putString("encrypted", base64Encode(ciphertext))
                    putString("salt", base64Encode(salt))
                    putString("nonce", base64Encode(nonce))
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("CRYPTO_ERR", "encryptIdentityPrivateKey failed: ${e.message}", e)
            }
        }
    }

    /**
     * Decrypt an identity private key with a password using PBKDF2 + AES-GCM.
     * Returns the decrypted private key as a base64 string.
     */
    @ReactMethod
    fun decryptIdentityPrivateKey(
        encryptedPrivateKey: String,
        saltBase64: String,
        nonceBase64: String,
        password: String,
        promise: Promise
    ) {
        scope.launch {
            try {
                val salt = base64Decode(saltBase64)
                val nonce = base64Decode(nonceBase64)
                val ciphertext = base64Decode(encryptedPrivateKey)
                val derivedKey = deriveKeyFromPassword(password, salt)
                val plaintext = decryptAesGcm(derivedKey, ciphertext, nonce)
                promise.resolve(String(plaintext, Charsets.UTF_8))
            } catch (e: Exception) {
                promise.reject("CRYPTO_ERR", "decryptIdentityPrivateKey failed: ${e.message}", e)
            }
        }
    }

    /**
     * Wrap an epoch key for a recipient using ECDH + HKDF + AES-GCM.
     * Returns base64(nonce || wrappedKey).
     */
    @ReactMethod
    fun wrapEpochKey(
        epochKeyBase64: String,
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        promise: Promise
    ) {
        scope.launch {
            try {
                val myPrivateKey = loadPrivateKey(myPrivateKeyBase64)
                val peerPublicKey = loadPublicKey(peerPublicKeyBase64)
                val wrapKey = deriveWrappingKey(myPrivateKey, peerPublicKey)

                val epochKey = base64Decode(epochKeyBase64)
                val nonce = randomBytes(AES_NONCE_LENGTH)
                val wrapped = encryptAesGcm(wrapKey, epochKey, nonce)

                // Concatenate: nonce(12) || wrappedKey
                val result = ByteArray(nonce.size + wrapped.size)
                System.arraycopy(nonce, 0, result, 0, nonce.size)
                System.arraycopy(wrapped, 0, result, nonce.size, wrapped.size)

                promise.resolve(base64Encode(result))
            } catch (e: Exception) {
                promise.reject("CRYPTO_ERR", "wrapEpochKey failed: ${e.message}", e)
            }
        }
    }

    /**
     * Unwrap an epoch key received from a sender using ECDH + HKDF + AES-GCM.
     * Expects base64(nonce || wrappedKey). Returns the unwrapped epoch key as base64.
     */
    @ReactMethod
    fun unwrapEpochKey(
        wrappedKeyBase64: String,
        myPrivateKeyBase64: String,
        senderPublicKeyBase64: String,
        promise: Promise
    ) {
        scope.launch {
            try {
                val wrappedData = base64Decode(wrappedKeyBase64)
                val nonce = wrappedData.copyOfRange(0, AES_NONCE_LENGTH)
                val wrapped = wrappedData.copyOfRange(AES_NONCE_LENGTH, wrappedData.size)

                val myPrivateKey = loadPrivateKey(myPrivateKeyBase64)
                val senderPublicKey = loadPublicKey(senderPublicKeyBase64)
                val wrapKey = deriveWrappingKey(myPrivateKey, senderPublicKey)

                val rawEpochKey = decryptAesGcm(wrapKey, wrapped, nonce)
                promise.resolve(base64Encode(rawEpochKey))
            } catch (e: Exception) {
                promise.reject("CRYPTO_ERR", "unwrapEpochKey failed: ${e.message}", e)
            }
        }
    }
}
