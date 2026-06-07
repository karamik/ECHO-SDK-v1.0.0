// echo-sdk/src/main/java/com/echo/sdk/api/EchoMeshClient.kt
package com.echo.sdk.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.echo.sdk.core.EchoService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Главный публичный интерфейс SDK.
 * Инициализируется один раз через [init], затем используется [getInstance].
 *
 * @param context Application context.
 * @param config Конфигурация (имя узла, флаги).
 */
class EchoMeshClient private constructor(
    private val context: Context,
    private val config: Config
) {
    data class Config(
        val enableGhostAuth: Boolean = true,
        val enableProofOfWork: Boolean = true,
        val databaseName: String = "echo_db",
        val nodeName: String? = null
    )

    companion object {
        @Volatile
        private var instance: EchoMeshClient? = null

        /**
         * Инициализирует SDK. Должен быть вызван один раз, лучше в [android.app.Application].
         * Приостанавливающая функция – ожидает готовности сервиса.
         */
        suspend fun init(context: Context, config: Config = Config()): EchoMeshClient {
            return instance ?: synchronized(this) {
                instance ?: EchoMeshClient(context.applicationContext, config).also {
                    it.start()
                    instance = it
                }
            }
        }

        /**
         * Возвращает уже инициализированный клиент.
         * @throws IllegalStateException если [init] не был вызван.
         */
        fun getInstance(): EchoMeshClient = instance ?: throw IllegalStateException("EchoMeshClient not initialized. Call init() first.")
    }

    private var echoService: EchoService? = null
    private var isBound = false
    private val serviceConnectionDeferred = CompletableDeferred<EchoService>()
    private val pendingListeners = mutableSetOf<EchoMessageListener>()
    private val serviceIntent = Intent(context, EchoService::class.java)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EchoService.LocalBinder
            val boundService = binder.getService()
            boundService.setNodeName(config.nodeName)
            echoService = boundService
            pendingListeners.forEach { boundService.addListener(it) }
            pendingListeners.clear()
            isBound = true
            serviceConnectionDeferred.complete(boundService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            echoService = null
            isBound = false
        }
    }

    private suspend fun start() {
        context.startService(serviceIntent)
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        serviceConnectionDeferred.await() // ждём полной готовности
    }

    /**
     * Отправляет широковещательное сообщение всем устройствам в текущей mesh-сети.
     */
    fun sendBroadcast(text: String) {
        echoService?.sendBroadcastMessage(text) ?: log("Service not bound")
    }

    /**
     * Отправляет личное зашифрованное сообщение конкретному получателю.
     * @param recipientPublicKeyHex Публичный ключ получателя (Ed25519 в hex).
     */
    fun sendPrivate(recipientPublicKeyHex: String, text: String) {
        echoService?.sendPrivateMessage(hexToBytes(recipientPublicKeyHex), text) ?: log("Service not bound")
    }

    /**
     * Регистрирует слушатель событий. Слушатель будет получать сообщения и уведомления о новых контактах.
     * Рекомендуется регистрировать в [androidx.appcompat.app.AppCompatActivity.onStart] и отписываться в onStop.
     */
    fun registerListener(listener: EchoMessageListener) {
        if (echoService != null) {
            echoService?.addListener(listener)
        } else {
            pendingListeners.add(listener)
        }
    }

    /**
     * Удаляет ранее зарегистрированный слушатель.
     */
    fun unregisterListener(listener: EchoMessageListener) {
        echoService?.removeListener(listener)
        pendingListeners.remove(listener)
    }

    /**
     * Возвращает публичный ключ текущего узла (Ed25519 в hex).
     */
    suspend fun getMyPublicKeyHex(): String = echoService?.getMyPublicKeyHex() ?: ""

    /**
     * Возвращает текущий баланс токенов "Эфир" для этого устройства.
     */
    suspend fun getBalance(): Long = echoService?.getMyBalance() ?: 0L

    /**
     * Возвращает список известных контактов (устройств, с которыми был обмен).
     */
    suspend fun getContacts(): List<EchoContact> = echoService?.getKnownContacts() ?: emptyList()

    private fun log(msg: String) = android.util.Log.d("EchoMeshClient", msg)
    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
