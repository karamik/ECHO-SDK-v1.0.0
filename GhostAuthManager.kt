// echo-sdk/src/main/java/com/echo/sdk/ghost/GhostAuthManager.kt
package com.echo.sdk.ghost

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Модуль "Призрак" — автоматическое обнаружение captive-порталов и попытка авторизации.
 * @param context контекст приложения
 * @param onGatewayReady колбэк: true — интернет появился (портал пройден), false — нет.
 */
class GhostAuthManager(
    private val context: Context,
    private val onGatewayReady: (Boolean) -> Unit
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var captiveClient: OkHttpClient? = null
    private var currentNetwork: Network? = null

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                captiveClient = OkHttpClient.Builder()
                    .socketFactory(network.socketFactory)
                    .followRedirects(false)
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                checkPortalAndBypass()
            }

            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    currentNetwork = null
                    captiveClient = null
                }
            }
        })
    }

    private fun checkPortalAndBypass() {
        val client = captiveClient ?: return
        val request = Request.Builder()
            .url("http://connectivitycheck.gstatic.com/generate_204")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    204 -> onGatewayReady(true) // нет портала, интернет уже есть
                    in listOf(301, 302, 307, 308) -> {
                        val redirectUrl = response.header("Location")
                        if (redirectUrl != null) attemptBypass(redirectUrl)
                        else onGatewayReady(false)
                    }
                    200 -> {
                        // портал вернул HTML вместо редиректа
                        attemptBypass("http://connectivitycheck.gstatic.com/generate_204")
                    }
                    else -> onGatewayReady(false)
                }
            }
        } catch (e: IOException) {
            onGatewayReady(false)
        }
    }

    private fun attemptBypass(portalUrl: String) {
        val client = captiveClient ?: return
        try {
            // Пытаемся отправить GET на страницу портала, чтобы получить форму
            val getRequest = Request.Builder().url(portalUrl).build()
            client.newCall(getRequest).execute().use { response ->
                val html = response.body?.string() ?: return
                // Простейший парсинг: ищем форму с action и отправляем accept=true
                val formAction = extractFormAction(html)
                val postUrl = if (formAction != null) {
                    if (formAction.startsWith("http")) formAction else "$portalUrl$formAction"
                } else portalUrl

                val postRequest = Request.Builder()
                    .url(postUrl)
                    .post(okhttp3.FormBody.Builder().add("accept", "true").build())
                    .build()

                client.newCall(postRequest).execute().use { postResponse ->
                    if (postResponse.isSuccessful || postResponse.code == 302) {
                        // После попытки авторизации проверяем, появился ли интернет
                        verifySuccess()
                    } else {
                        onGatewayReady(false)
                    }
                }
            }
        } catch (e: Exception) {
            onGatewayReady(false)
        }
    }

    private fun verifySuccess() {
        val client = captiveClient ?: return
        val request = Request.Builder().url("http://connectivitycheck.gstatic.com/generate_204").build()
        try {
            client.newCall(request).execute().use { response ->
                onGatewayReady(response.code == 204)
            }
        } catch (e: IOException) {
            onGatewayReady(false)
        }
    }

    private fun extractFormAction(html: String): String? {
        // Очень упрощённый парсинг (в реальности лучше использовать Jsoup)
        val regex = Regex("""<form[^>]*action=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
    }
}
