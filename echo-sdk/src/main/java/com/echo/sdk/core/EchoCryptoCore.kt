// echo-sdk/src/main/java/com/echo/sdk/core/EchoCryptoCore.kt
package com.echo.sdk.core

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class EchoCryptoCore {
    companion object {
        private const val PUBLIC_KEY_BYTES = 32
        private const val PRIVATE_KEY_BYTES = 32
        private const val SIGNATURE_BYTES = 64
        private const val EPHEMERAL_PUBKEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val MAC_TAG_BYTES = 16
        private const val OFFSET_EPHEMERAL = 0
        private const val OFFSET_NONCE = OFFSET_EPHEMERAL + EPHEMERAL_PUBKEY_BYTES
        private const val OFFSET_CIPHERTEXT = OFFSET_NONCE + NONCE_BYTES
    }

    private val lazySodium = LazySodiumAndroid(SodiumAndroid())
    lateinit var ed25519PublicKey: ByteArray
    lateinit var ed25519PrivateKey: ByteArray
    private lateinit var x25519PublicKeyCached: ByteArray
    private lateinit var x25519PrivateKeyCached: ByteArray
    private val boxSharedSecret = ByteArray(32)
    private val ephemeralKeypair = ByteArray(EPHEMERAL_PUBKEY_BYTES + PRIVATE_KEY_BYTES)
    private val nonceBuffer = ByteArray(NONCE_BYTES)

    init {
        generateOrLoadKeys()
    }

    private fun generateOrLoadKeys() {
        ed25519PublicKey = ByteArray(PUBLIC_KEY_BYTES)
        ed25519PrivateKey = ByteArray(PRIVATE_KEY_BYTES)
        lazySodium.cryptoSignKeypair(ed25519PublicKey, ed25519PrivateKey)
        x25519PublicKeyCached = ByteArray(PUBLIC_KEY_BYTES)
        x25519PrivateKeyCached = ByteArray(PRIVATE_KEY_BYTES)
        lazySodium.cryptoSignEd25519PkToCurve25519(x25519PublicKeyCached, ed25519PublicKey)
        lazySodium.cryptoSignEd25519SkToCurve25519(x25519PrivateKeyCached, ed25519PrivateKey)
    }

    fun sign(message: ByteArray, outputBuffer: ByteArray): Int {
        if (outputBuffer.size < message.size + SIGNATURE_BYTES) return -1
        return if (lazySodium.cryptoSign(outputBuffer, message, message.size, ed25519PrivateKey))
            message.size + SIGNATURE_BYTES else -1
    }

    fun verify(signedMessage: ByteArray, outputBuffer: ByteArray): Int {
        if (outputBuffer.size < signedMessage.size - SIGNATURE_BYTES) return -1
        return if (lazySodium.cryptoSignOpen(outputBuffer, signedMessage, signedMessage.size, ed25519PublicKey))
            signedMessage.size - SIGNATURE_BYTES else -1
    }

    fun encryptFor(recipientEd25519PublicKey: ByteArray, plaintext: ByteArray, outputBuffer: ByteArray): Int {
        val expectedLen = EPHEMERAL_PUBKEY_BYTES + NONCE_BYTES + plaintext.size + MAC_TAG_BYTES
        if (outputBuffer.size < expectedLen) return -1

        val recipientX25519 = ByteArray(PUBLIC_KEY_BYTES)
        if (!lazySodium.cryptoSignEd25519PkToCurve25519(recipientX25519, recipientEd25519PublicKey)) return -1

        val ephemeralPub = ByteArray(EPHEMERAL_PUBKEY_BYTES)
        val ephemeralPriv = ByteArray(PRIVATE_KEY_BYTES)
        lazySodium.cryptoBoxKeypair(ephemeralPub, ephemeralPriv)
        lazySodium.cryptoBoxBeforeNm(boxSharedSecret, recipientX25519, ephemeralPriv)

        randomBytes(nonceBuffer, NONCE_BYTES)
        val tempCipher = ByteArray(plaintext.size + MAC_TAG_BYTES)
        lazySodium.cryptoBoxAfterNm(tempCipher, plaintext, plaintext.size, nonceBuffer, boxSharedSecret)

        System.arraycopy(ephemeralPub, 0, outputBuffer, OFFSET_EPHEMERAL, EPHEMERAL_PUBKEY_BYTES)
        System.arraycopy(nonceBuffer, 0, outputBuffer, OFFSET_NONCE, NONCE_BYTES)
        System.arraycopy(tempCipher, 0, outputBuffer, OFFSET_CIPHERTEXT, tempCipher.size)
        return expectedLen
    }

    fun decryptFrom(packet: ByteArray, packetLength: Int, outputBuffer: ByteArray): Int {
        if (packetLength < OFFSET_CIPHERTEXT + MAC_TAG_BYTES) return -1
        val ciphertextLen = packetLength - OFFSET_CIPHERTEXT
        val plaintextLen = ciphertextLen - MAC_TAG_BYTES
        if (outputBuffer.size < plaintextLen) return -1

        System.arraycopy(packet, OFFSET_EPHEMERAL, ephemeralKeypair, 0, EPHEMERAL_PUBKEY_BYTES)
        System.arraycopy(packet, OFFSET_NONCE, nonceBuffer, 0, NONCE_BYTES)
        lazySodium.cryptoBoxBeforeNm(boxSharedSecret, ephemeralKeypair, x25519PrivateKeyCached)

        val pureCiphertext = ByteArray(ciphertextLen)
        System.arraycopy(packet, OFFSET_CIPHERTEXT, pureCiphertext, 0, ciphertextLen)

        val success = lazySodium.cryptoBoxOpenAfterNm(outputBuffer, pureCiphertext, ciphertextLen, nonceBuffer, boxSharedSecret)
        return if (success) plaintextLen else -1
    }

    suspend fun findPoWNonceAsync(messageId: ByteArray, difficulty: Int): Long = withContext(Dispatchers.Default) {
        var nonce = 0L
        val buffer = ByteArray(messageId.size + 8)
        System.arraycopy(messageId, 0, buffer, 0, messageId.size)
        while (true) {
            ensureActive()
            for (i in 0 until 8) {
                buffer[messageId.size + i] = (nonce shr (i * 8)).toByte()
            }
            val hash = sha256(buffer)
            if (hash[0] == 0.toByte() && hash[1] == 0.toByte() && (difficulty <= 2 || (hash[2].toInt() and 0xFF) < 16)) {
                return@withContext nonce
            }
            nonce++
        }
    }

    private fun randomBytes(buffer: ByteArray, length: Int) {
        val random = kotlin.random.Random.Default
        for (i in 0 until length) buffer[i] = random.nextInt(256).toByte()
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
