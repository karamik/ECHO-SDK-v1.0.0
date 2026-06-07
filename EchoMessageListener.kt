// echo-sdk/src/main/java/com/echo/sdk/api/EchoMessageListener.kt
package com.echo.sdk.api

import com.echo.sdk.api.models.EchoContact
import com.echo.sdk.api.models.EchoMessage

/**
 * Слушатель событий mesh-сети.
 * Регистрируется через [EchoMeshClient.registerListener].
 */
interface EchoMessageListener {
    /**
     * Вызывается при получении нового сообщения (broadcast или личного).
     * @param message Полученное сообщение.
     */
    fun onMessage(message: EchoMessage)

    /**
     * Вызывается, когда в сети обнаружен новый контакт (устройство, с которым возможен обмен).
     * @param contact Информация о контакте.
     */
    fun onContactDiscovered(contact: EchoContact)
}
