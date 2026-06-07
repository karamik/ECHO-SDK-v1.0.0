// echo-sdk/src/main/java/com/echo/sdk/api/models/EchoContact.kt
package com.echo.sdk.api.models

/**
 * Контакт (другое устройство) в mesh-сети.
 * @param publicKeyHex Публичный ключ устройства (Ed25519 в hex).
 * @param name Отображаемое имя (если известно).
 * @param lastSeen Время последнего получения данных от контакта (Unix миллисекунды).
 * @param reputation Текущая репутация (0–100). Влияет на приоритет синхронизации.
 */
data class EchoContact(
    val publicKeyHex: String,
    val name: String?,
    val lastSeen: Long,
    val reputation: Int
)
