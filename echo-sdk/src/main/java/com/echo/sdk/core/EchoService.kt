package com.echo.sdk.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.echo.sdk.api.EchoMessageListener
import com.echo.sdk.api.models.EchoContact
import com.echo.sdk.api.models.EchoMessage
import com.echo.sdk.db.EchoDatabase
import com.echo.sdk.db.entity.DagMessageEntity
import com.echo.sdk.ghost.GhostAuthManager
import kotlinx.coroutines.*

class EchoService : Service() {
    private val binder = LocalBinder()
    private lateinit var transport: EchoTransportManager
    private lateinit var crypto: EchoCryptoCore
    private lateinit var economy: EchoEconomyManager
    private lateinit var gossip: EchoGossipManager
    private lateinit var ghostAuth: GhostAuthManager
    private lateinit var database: EchoDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private val listeners = mutableListOf<EchoMessageListener>()
    private var nodeName: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): EchoService = this@EchoService
    }

    override fun onCreate() {
        super.onCreate()
        database = EchoDatabase.getInstance(this)
        crypto = EchoCryptoCore()
        transport = EchoTransportManager(this)
        economy = EchoEconomyManager(database.tokenBalanceDao(), database.processedReceiptDao(), crypto)
        gossip = EchoGossipManager(this, database.dagMessageDao(), transport, crypto, economy)
        ghostAuth = GhostAuthManager(this) { hasInternet ->
            if (hasInternet) {
                gossip.forceSync()
                broadcastMyPresence()
            }
        }

        transport.listener = object : EchoTransportManager.Listener {
            override fun onPacket(buffer: ByteArray, length: Int, senderIp: String) {
                gossip.handleIncomingPacket(buffer, length, senderIp)
                processUIMessage(buffer, length, senderIp)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            scope.launch {
                economy.init()
                transport.start()
                ghostAuth.startMonitoring()
                gossip.start()
                isRunning = true
                startPresenceAnnouncements()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        gossip.stop()
        transport.stop()
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setNodeName(name: String?) { nodeName = name }

    fun sendBroadcastMessage(text: String) {
        val msgBytes = text.toByteArray()
        val signed = ByteArray(msgBytes.size + 64)
        val len = crypto.sign(msgBytes, signed)
        if (len > 0) {
            val packet = ByteArray(len + 1)
            packet[0] = 0x10 // тип: broadcast
            System.arraycopy(signed, 0, packet, 1, len)
            transport.broadcast(packet)
            saveOutgoingMessage(text, false, 0, 0)
        }
    }

    fun sendPrivateMessage(recipientPubKey: ByteArray, text: String) {
        val plain = text.toByteArray()
        val encrypted = ByteArray(32 + 12 + plain.size + 16)
        val outLen = crypto.encryptFor(recipientPubKey, plain, encrypted)
        if (outLen > 0) {
            val packet = ByteArray(outLen + 1)
            packet[0] = 0x11 // тип: private
            System.arraycopy(encrypted, 0, packet, 1, outLen)
            transport.broadcast(packet)
            saveOutgoingMessage(text, true, 0, 0)
        }
    }

    fun addListener(listener: EchoMessageListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: EchoMessageListener) {
        listeners.remove(listener)
    }

    fun getMyPublicKeyHex(): String = crypto.ed25519PublicKey.joinToString("") { "%02x".format(it) }
    suspend fun getMyBalance(): Long = economy.getBalance(getMyPublicKeyHex())
    suspend fun getKnownContacts(): List<EchoContact> {
        val authors = database.dagMessageDao().getDistinctAuthors()
        return authors.map { author ->
            val lastMsg = database.dagMessageDao().getLastMessageByAuthor(author)
            EchoContact(author, null, lastMsg?.timestamp ?: 0, economy.getReputation(author))
        }
    }

    private fun processUIMessage(buffer: ByteArray, length: Int, senderIp: String) {
        if (length < 1) return
        val type = buffer[0]
        val payload = buffer.copyOfRange(1, length) // TODO: zero-copy later
        when (type) {
            0x10 -> {
                val verified = ByteArray(payload.size)
                val msgLen = crypto.verify(payload, verified)
                if (msgLen > 0) {
                    val text = String(verified, 0, msgLen)
                    val msg = EchoMessage(
                        senderPublicKeyHex = "unknown",
                        senderName = null,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isPrivate = false,
                        relayReward = 0,
                        powNonce = 0
                    )
                    listeners.forEach { it.onMessage(msg) }
                }
            }
            0x11 -> {
                val plain = ByteArray(payload.size)
                val plainLen = crypto.decryptFrom(payload, payload.size, plain)
                if (plainLen > 0) {
                    val text = String(plain, 0, plainLen)
                    val msg = EchoMessage(
                        senderPublicKeyHex = "private_sender",
                        senderName = null,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isPrivate = true,
                        relayReward = 0,
                        powNonce = 0
                    )
                    listeners.forEach { it.onMessage(msg) }
                }
            }
        }
    }

    private fun saveOutgoingMessage(text: String, isPrivate: Boolean, reward: Long, powNonce: Long) {
        scope.launch {
            val authorHex = getMyPublicKeyHex()
            val lastSeq = (database.dagMessageDao().getLastSeq(authorHex) ?: 0) + 1
            val parentHash = database.dagMessageDao().getHeadHash(authorHex) ?: "0"
            val msgId = bytesToHex(sha256("$authorHex$lastSeq$parentHash".toByteArray()))
            val payload = text.toByteArray()
            val signature = ByteArray(64) // TODO: real signature
            val entity = DagMessageEntity(
                messageId = msgId,
                authorPubkey = authorHex,
                sequenceNumber = lastSeq,
                parentHash = parentHash,
                timestamp = System.currentTimeMillis(),
                isPrivate = isPrivate,
                payload = payload,
                signature = signature,
                relayReward = reward,
                powNonce = powNonce,
                totalRelays = 0
            )
            database.dagMessageDao().insert(entity)
            gossip.updateLocalState(authorHex, lastSeq, msgId)
        }
    }

    private fun broadcastMyPresence() {
        val presence = "ECHO_PRESENCE:${getMyPublicKeyHex()}".toByteArray()
        val signed = ByteArray(presence.size + 64)
        val len = crypto.sign(presence, signed)
        if (len > 0) {
            val packet = ByteArray(len + 1)
            packet[0] = 0x10 // broadcast type
            System.arraycopy(signed, 0, packet, 1, len)
            transport.broadcast(packet)
        }
    }

    private fun startPresenceAnnouncements() {
        scope.launch {
            while (isRunning) {
                delay(5000)
                broadcastMyPresence()
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun sha256(data: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(data)
}
