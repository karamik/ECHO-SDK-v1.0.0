package com.echo.sdk.core

import com.echo.sdk.db.dao.ProcessedReceiptDao
import com.echo.sdk.db.dao.TokenBalanceDao
import com.echo.sdk.db.entity.ProcessedReceiptEntity
import com.echo.sdk.db.entity.TokenBalanceEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class EchoEconomyManager(
    private val tokenDao: TokenBalanceDao,
    private val receiptDao: ProcessedReceiptDao,
    private val crypto: EchoCryptoCore
) {
    private val balances = ConcurrentHashMap<String, Long>()
    private val reputations = ConcurrentHashMap<String, Int>()
    private val processedCache = ConcurrentHashMap.newKeySet<String>()
    private val mutex = Mutex()

    suspend fun init() {
        val allBalances = tokenDao.getAllBalances()
        balances.putAll(allBalances.associate { it.pubKeyHex to it.balance })
    }

    suspend fun rewardRelay(receipt: RelayReceipt): Boolean {
        val relayHex = receipt.relayPublicKey.toHexString()
        val cacheKey = "${receipt.messageId}_$relayHex"
        if (processedCache.contains(cacheKey)) return false

        val receiptBytesWithoutSig = RelayReceiptSerializer.serializeWithoutSignature(receipt)
        val isValid = crypto.verify(receipt.signature, receiptBytesWithoutSig) > 0
        if (!isValid) {
            penalize(relayHex, 10)
            return false
        }

        return mutex.withLock {
            if (processedCache.contains(cacheKey)) return@withLock false
            try {
                receiptDao.insert(ProcessedReceiptEntity(receipt.messageId, relayHex))
                processedCache.add(cacheKey)
                val current = balances[relayHex] ?: 0L
                val newBalance = current + 1
                balances[relayHex] = newBalance
                tokenDao.updateBalance(TokenBalanceEntity(relayHex, newBalance))
                increaseReputation(relayHex, 1)
                true
            } catch (e: Exception) {
                processedCache.add(cacheKey)
                false
            }
        }
    }

    suspend fun penalize(pubKeyHex: String, penalty: Int) {
        mutex.withLock {
            val current = balances[pubKeyHex] ?: 0L
            val newBalance = maxOf(0L, current - penalty)
            balances[pubKeyHex] = newBalance
            tokenDao.updateBalance(TokenBalanceEntity(pubKeyHex, newBalance))

            val rep = reputations[pubKeyHex] ?: 50
            reputations[pubKeyHex] = maxOf(0, rep - penalty)
        }
    }

    private fun increaseReputation(pubKeyHex: String, delta: Int) {
        val current = reputations[pubKeyHex] ?: 50
        reputations[pubKeyHex] = minOf(100, current + delta)
    }

    fun getReputation(pubKeyHex: String): Int = reputations[pubKeyHex] ?: 50
    fun getBalance(pubKeyHex: String): Long = balances[pubKeyHex] ?: 0L
    fun canSendWithoutPoW(pubKeyHex: String): Boolean = getBalance(pubKeyHex) >= 10

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

data class RelayReceipt(
    val messageId: String,
    val relayPublicKey: ByteArray,
    val recipientPublicKey: ByteArray,
    val timestamp: Long,
    val signature: ByteArray
)

object RelayReceiptSerializer {
    fun serializeWithoutSignature(receipt: RelayReceipt): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(baos).use { dos ->
            dos.writeUTF(receipt.messageId)
            dos.write(receipt.relayPublicKey)
            dos.write(receipt.recipientPublicKey)
            dos.writeLong(receipt.timestamp)
        }
        return baos.toByteArray()
    }
}
