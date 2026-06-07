// echo-sdk/src/main/java/com/echo/sdk/db/entity/DagMessageEntity.kt
package com.echo.sdk.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сообщение в направленном ациклическом графе (DAG).
 * Каждое сообщение привязано к автору и имеет порядковый номер, ссылку на предыдущее сообщение автора.
 */
@Entity(tableName = "dag_messages")
data class DagMessageEntity(
    @PrimaryKey
    val messageId: String,               // SHA-256(authorPubkey + sequenceNumber + parentHash)
    val authorPubkey: String,            // Ed25519 публичный ключ автора (hex)
    val sequenceNumber: Long,            // Порядковый номер сообщения автора (1, 2, 3...)
    val parentHash: String,              // messageId предыдущего сообщения этого автора, "0" для первого
    val timestamp: Long,                 // Время создания (Unix ms)
    val isPrivate: Boolean,              // true — личное сообщение (зашифровано)
    val payload: ByteArray,              // Текст сообщения (подписанный, возможно зашифрованный)
    val signature: ByteArray,            // Подпись автора (Ed25519) над (messageId + payload)
    val relayReward: Long = 0,           // Токены, выделенные на ретрансляцию
    val powNonce: Long = 0,              // Nonce для Proof-of-Work (если relayReward == 0)
    val totalRelays: Int = 0,            // Счётчик ретрансляций (локально обновляется)
    val receivedAt: Long = System.currentTimeMillis()  // Время получения узлом
)
