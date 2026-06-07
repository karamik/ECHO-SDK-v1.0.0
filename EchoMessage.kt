// echo-sdk/src/main/java/com/echo/sdk/api/models/EchoMessage.kt
package com.echo.sdk.api.models

/**
 * Сообщение, полученное из mesh-сети.
 * @param senderPublicKeyHex Публичный ключ отправителя (Ed25519 в hex).
 * @param senderName Отображаемое имя отправителя (если известно).
 * @param text Текст сообщения (расшифрованный).
 * @param timestamp Время получения (Unix миллисекунды).
 * @param isPrivate true, если сообщение было зашифровано (личное).
 * @param relayReward Количество токенов, выделенных отправителем на ретрансляцию.
 * @param powNonce Nonce, использованный для Proof-of-Work (если relayReward == 0).
 */
data class EchoMessage(
    val senderPublicKeyHex: String,
    val senderName: String?,
    val text: String,
    val timestamp: Long,
    val isPrivate: Boolean,
    val relayReward: Long,
    val powNonce: Long
)
