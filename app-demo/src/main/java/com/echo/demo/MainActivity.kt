package com.echo.demo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.echo.sdk.api.EchoMeshClient
import com.echo.sdk.api.EchoMessageListener
import com.echo.sdk.api.models.EchoContact
import com.echo.sdk.api.models.EchoMessage
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var echoClient: EchoMeshClient
    private lateinit var logView: TextView
    private lateinit var inputEdit: EditText
    private var isEchoReady = false

    private val messageListener = object : EchoMessageListener {
        override fun onMessage(message: EchoMessage) {
            runOnUiThread {
                val prefix = if (message.isPrivate) "🔒" else "🌐"
                val sender = message.senderName ?: message.senderPublicKeyHex.take(8)
                appendLog("$prefix $sender: ${message.text}")
            }
        }

        override fun onContactDiscovered(contact: EchoContact) {
            runOnUiThread {
                appendLog("📡 Контакт: ${contact.publicKeyHex.take(8)}... (реп: ${contact.reputation})")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        inputEdit = findViewById(R.id.inputEdit)
        val sendBtn = findViewById<Button>(R.id.sendBtn)

        sendBtn.setOnClickListener {
            val text = inputEdit.text.toString()
            if (text.isNotBlank() && isEchoReady) {
                try {
                    EchoMeshClient.getInstance().sendBroadcast(text)
                    appendLog("Я: $text")
                    inputEdit.text.clear()
                } catch (e: IllegalStateException) {
                    appendLog("⚠️ SDK не инициализирован")
                }
            }
        }

        lifecycleScope.launch {
            try {
                echoClient = EchoMeshClient.init(
                    context = this@MainActivity,
                    config = EchoMeshClient.Config(
                        enableGhostAuth = true,
                        nodeName = android.os.Build.MODEL
                    )
                )
                isEchoReady = true
                appendLog("✅ ЭХО инициализирован. Ключ: ${echoClient.getMyPublicKeyHex().take(8)}...")
            } catch (e: Exception) {
                appendLog("❌ Ошибка инициализации: ${e.message}")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            EchoMeshClient.getInstance().registerListener(messageListener)
        } catch (e: IllegalStateException) {
            // SDK ещё не готов — проигнорируем
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            EchoMeshClient.getInstance().unregisterListener(messageListener)
        } catch (e: IllegalStateException) {
            // OK
        }
    }

    private fun appendLog(msg: String) {
        logView.append("$msg\n")
    }
}
