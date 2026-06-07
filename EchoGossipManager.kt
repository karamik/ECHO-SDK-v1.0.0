// echo-sdk/src/main/java/com/echo/sdk/core/EchoGossipManager.kt
package com.echo.sdk.core

import android.content.Context
import com.echo.sdk.db.dao.DagMessageDao
import com.echo.sdk.db.entity.DagMessageEntity
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Управляет gossip-протоколом: периодическая рассылка State Vector,
 * обработка входящих векторов, запрос/отправка дельт.
 */
class EchoGossipManager(
    private val context: Context,
    private val dao: DagMessageDao,
    private val transport: EchoTransportManager,
    private val crypto: EchoCryptoCore,
    private val economy: EchoEconomyManager
) {
    companion object {
        private const val TICK_MS = 1000L
        private const val REPUTATION_THRESHOLD_REQUEST = 20
        private const val REPUTATION_THRESHOLD_SEND = 50
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private var tickJob: Job? = null
    // In-memory кэш состояний (pubKeyHex -> AuthorState)
    private val stateCache = ConcurrentHashMap<String, AuthorState>()

    data class AuthorState(
        val pubKeyHex: String,
        val lastSeq: Long,
        val headHash: String
    )

    fun start() {
        if (isRunning.getAndSet(true)) return
        scope.launch { initCacheFromDb() }
        tickJob = scope.launch {
            while (isRunning.get()) {
                delay(TICK_MS)
                broadcastStateVector()
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        tickJob?.cancel()
        scope.cancel()
    }

    /**
     * Принудительная синхронизация (например, при смене Wi-Fi сети).
     */
    suspend fun forceSync() {
        broadcastStateVector()
    }

    /**
     * Обновляет локальный кэш после добавления нового сообщения.
     */
    fun updateLocalState(authorHex: String, seq: Long, headHash: String) {
        stateCache[authorHex] = AuthorState(authorHex, seq, headHash)
    }

    private suspend fun initCacheFromDb() {
        val authors = dao.getDistinctAuthors()
        for (author in authors) {
            val lastSeq = dao.getLastSeq(author) ?: 0
            val headHash = if (lastSeq > 0) dao.getHeadHash(author) ?: "0" else "0"
            stateCache[author] = AuthorState(author, lastSeq, headHash)
        }
    }

    private fun broadcastStateVector() {
        if (stateCache.isEmpty()) return
        val states = stateCache.values.toList()
        val serialized = StateVectorSerializer.serialize(states)
        val signed = ByteArray(serialized.size + 64)
        val len = crypto.sign(serialized, signed)
        if (len > 0) {
            val packet = ByteArray(len + 1)
            packet[0] = 0x01 // тип: StateVector
            System.arraycopy(signed, 0, packet, 1, len)
            transport.broadcast(packet)
        }
    }

    /**
     * Обрабатывает входящий пакет (типы 0x01, 0x02, 0x03).
     * Вызывается из транспортного слоя.
     */
    fun handleIncomingPacket(buffer: ByteArray, length: Int, senderIp: String) {
        if (length < 1) return
        val type = buffer[0]
        val cryptoBuf = buffer.copyOfRange(1, length)
        scope.launch {
            when (type) {
                0x01 -> processStateVector(cryptoBuf, senderIp)
                0x02 -> processDelta(cryptoBuf, senderIp)
                0x03 -> processDeltaRequest(cryptoBuf, senderIp)
            }
        }
    }

    private suspend fun processStateVector(cryptoBuf: ByteArray, senderIp: String) {
        val verified = ByteArray(cryptoBuf.size)
        val msgLen = crypto.verify(cryptoBuf, verified)
        if (msgLen <= 0) return
        val remoteStates = StateVectorSerializer.deserialize(verified.copyOf(msgLen))
        // Упрощённо: извлекаем публичный ключ отправителя (в реальности из подписи сообщения)
        val senderPubKey = "temp_sender" // TODO: извлечь из последнего сообщения этого IP

        for (remote in remoteStates) {
            val myState = stateCache[remote.pubKeyHex]
            val myLastSeq = myState?.lastSeq ?: 0

            if (remote.lastSeq > myLastSeq) {
                if (economy.getReputation(senderPubKey) >= REPUTATION_THRESHOLD_REQUEST) {
                    requestDelta(remote.pubKeyHex, myLastSeq, senderIp)
                } else {
                    delay(5000) // троттлинг для ненадёжных узлов
                }
            } else if (remote.lastSeq < myLastSeq && myLastSeq > 0) {
                val myPubHex = crypto.ed25519PublicKey.toHexString()
                if (economy.canSendWithoutPoW(myPubHex) || economy.getReputation(senderPubKey) > REPUTATION_THRESHOLD_SEND) {
                    sendDelta(remote.pubKeyHex, remote.lastSeq, senderIp)
                }
            }
        }
    }

    private suspend fun requestDelta(authorHex: String, fromSeq: Long, targetIp: String) {
        val payload = ByteArray(1 + 32 + 8)
        payload[0] = 0x03
        hexToBytes(authorHex).copyInto(payload, 1)
        java.nio.ByteBuffer.wrap(ByteArray(8)).putLong(fromSeq).array().copyInto(payload, 33)
        transport.sendDirected(targetIp, payload)
    }

    private suspend fun sendDelta(authorHex: String, fromSeq: Long, targetIp: String) {
        val messages = dao.getMessagesSince(authorHex, fromSeq)
        if (messages.isEmpty()) return
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeShort(messages.size)
            for (msg in messages) {
                dos.writeUTF(msg.messageId)
                dos.writeUTF(msg.authorPubkey)
                dos.writeLong(msg.sequenceNumber)
                dos.writeUTF(msg.parentHash)
                dos.writeLong(msg.timestamp)
                dos.writeBoolean(msg.isPrivate)
                dos.writeInt(msg.payload.size)
                dos.write(msg.payload)
                dos.writeInt(msg.signature.size)
                dos.write(msg.signature)
                dos.writeLong(msg.relayReward)
                dos.writeLong(msg.powNonce)
                dos.writeInt(msg.totalRelays)
            }
        }
        val deltaBytes = baos.toByteArray()
        val packet = ByteArray(deltaBytes.size + 1)
        packet[0] = 0x02
        deltaBytes.copyInto(packet, 1)
        transport.sendDirected(targetIp, packet)
    }

    private suspend fun processDelta(deltaBuf: ByteArray, senderIp: String) {
        val bis = ByteArrayInputStream(deltaBuf)
        DataInputStream(bis).use { dis ->
            val count = dis.readShort().toInt()
            for (i in 0 until count) {
                val msgId = dis.readUTF()
                val author = dis.readUTF()
                val seq = dis.readLong()
                val parent = dis.readUTF()
                val ts = dis.readLong()
                val isPriv = dis.readBoolean()
                val payloadLen = dis.readInt()
                val payload = ByteArray(payloadLen); dis.readFully(payload)
                val sigLen = dis.readInt()
                val sig = ByteArray(sigLen); dis.readFully(sig)
                val reward = dis.readLong()
                val powNonce = dis.readLong()
                val totalRelays = dis.readInt()

                val entity = DagMessageEntity(
                    messageId = msgId,
                    authorPubkey = author,
                    sequenceNumber = seq,
                    parentHash = parent,
                    timestamp = ts,
                    isPrivate = isPriv,
                    payload = payload,
                    signature = sig,
                    relayReward = reward,
                    powNonce = powNonce,
                    totalRelays = totalRelays
                )
                dao.insert(entity)
                if (seq > (stateCache[author]?.lastSeq ?: 0)) {
                    updateLocalState(author, seq, msgId)
                }
                // TODO: сгенерировать RelayReceipt для отправителя дельты (senderIp)
            }
        }
    }

    private suspend fun processDeltaRequest(deltaBuf: ByteArray, senderIp: String) {
        // Извлекаем author и fromSeq, затем отправляем дельту (аналогично sendDelta)
        // Реализация по мере необходимости
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

object StateVectorSerializer {
    fun serialize(states: List<EchoGossipManager.AuthorState>): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeShort(states.size)
            for (s in states) {
                dos.write(hexToBytes(s.pubKeyHex))
                dos.writeLong(s.lastSeq)
                dos.write(hexToBytes(s.headHash))
            }
        }
        return baos.toByteArray()
    }

    fun deserialize(data: ByteArray): List<EchoGossipManager.AuthorState> {
        val bis = ByteArrayInputStream(data)
        DataInputStream(bis).use { dis ->
            val size = dis.readShort().toInt()
            return (1..size).map {
                val pubBytes = ByteArray(32); dis.readFully(pubBytes)
                val lastSeq = dis.readLong()
                val hashBytes = ByteArray(32); dis.readFully(hashBytes)
                EchoGossipManager.AuthorState(
                    pubKeyHex = bytesToHex(pubBytes),
                    lastSeq = lastSeq,
                    headHash = bytesToHex(hashBytes)
                )
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
