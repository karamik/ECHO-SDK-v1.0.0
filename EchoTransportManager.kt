// echo-sdk/src/main/java/com/echo/sdk/core/EchoTransportManager.kt
package com.echo.sdk.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class EchoTransportManager(private val context: Context) {
    companion object {
        private const val PORT = 8888
        private const val BROADCAST_ADDR = "255.255.255.255"
        private const val TICK_INTERVAL_MS = 1000L
        private const val WAKE_LOCK_TIMEOUT = 5000L
    }

    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Zero-allocation буфер
    private val receiveBuffer = ByteArray(2048)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickJob: Job? = null

    interface Listener {
        fun onPacket(buffer: ByteArray, length: Int, senderIp: String)
    }

    var listener: Listener? = null

    fun start() {
        if (isRunning.getAndSet(true)) return
        startSocket()
        startTickSender()
        startReceiver()
    }

    fun stop() {
        isRunning.set(false)
        tickJob?.cancel()
        socket?.close()
        releaseLocks()
        scope.cancel()
    }

    fun broadcast(data: ByteArray) {
        if (!isRunning.get()) return
        scope.launch {
            acquireLocksTemporarily()
            try {
                val packet = DatagramPacket(data, data.size, InetAddress.getByName(BROADCAST_ADDR), PORT)
                socket?.send(packet)
            } catch (e: Exception) { /* log */ }
        }
    }

    fun sendDirected(targetIp: String, data: ByteArray) {
        if (!isRunning.get()) return
        scope.launch {
            acquireLocksTemporarily()
            try {
                val packet = DatagramPacket(data, data.size, InetAddress.getByName(targetIp), PORT)
                socket?.send(packet)
            } catch (e: Exception) { /* log */ }
        }
    }

    fun scanSubnetAndSend(data: ByteArray) {
        val localIp = getLocalIpAddress() ?: return
        val prefix = localIp.substringBeforeLast('.')
        scope.launch {
            acquireLocksTemporarily()
            (1..254).map { suffix ->
                async {
                    try {
                        val targetIp = "$prefix.$suffix"
                        val addr = InetAddress.getByName(targetIp)
                        val packet = DatagramPacket(data, data.size, addr, PORT)
                        socket?.send(packet)
                    } catch (e: Exception) { /* ignore */ }
                }
            }.awaitAll()
        }
    }

    private fun startSocket() {
        socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 2000
            bind(InetSocketAddress(PORT))
            broadcast = true
        }
    }

    private fun startTickSender() {
        tickJob = scope.launch {
            while (isRunning.get()) {
                delay(TICK_INTERVAL_MS)
                // Пустой тик – можно отправлять heartbeat, но пока просто dummy
                val dummyData = "TICK:${System.currentTimeMillis()}".toByteArray()
                broadcast(dummyData)
            }
        }
    }

    private fun startReceiver() {
        scope.launch {
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
            while (isRunning.get()) {
                try {
                    socket?.receive(packet)
                    val senderIp = packet.address.hostAddress
                    listener?.onPacket(receiveBuffer, packet.length, senderIp)
                    packet.length = receiveBuffer.size
                } catch (e: java.net.SocketTimeoutException) {
                    // нормально, продолжаем
                } catch (e: Exception) {
                    if (isRunning.get()) delay(500)
                }
            }
        }
    }

    private fun acquireLocksTemporarily() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echo:WakeLock")
            }
            if (wifiLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Echo:WifiLock")
                multicastLock = wifiManager.createMulticastLock("Echo:MulticastLock")
            }
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT)
            wifiLock?.acquire()
            multicastLock?.acquire()
        } catch (e: Exception) { }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) { }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (networkInterface in java.util.Collections.list(interfaces)) {
                for (addr in java.util.Collections.list(networkInterface.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) { }
        return null
    }
}
