package com.aasif.btstereo.tv

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WiFi Audio Receiver — ye woh mode hai jo UN TV par bhi "phone ki audio TV me"
 * wala feature de deta hai jahan Bluetooth A2DP Sink firmware me nahi hai.
 *
 * Protocol (phone companion app ke saath shared):
 *  TCP port 24880
 *  Header 12 bytes: magic "AAS1" (4) + sampleRate int BE (4) + channels int BE (4)
 *  Uske baad raw PCM 16-bit little-endian stream, band hone tak.
 * TV apne aap ko NSD par "_aasifaudio._tcp." naam se advertise karta hai
 * taaki phone app bina IP type kiye TV dhundh le.
 */
class WifiAudioService : Service() {

    companion object {
        const val PORT = 24880
        const val SERVICE_TYPE = "_aasifaudio._tcp."
        const val ACTION_STATUS = "com.aasif.btstereo.tv.WIFI_STATUS"
        const val EXTRA_STATUS = "status"

        fun start(context: Context) {
            context.startService(Intent(context, WifiAudioService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiAudioService::class.java))
        }
    }

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            registerNsd()
            serverThread = Thread({ serveLoop() }, "AasifWifiAudioServer").apply { start() }
            broadcastStatus("WiFi Receiver ON — phone app se connect karo (port $PORT)")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        unregisterNsd()
        try { clientSocket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        broadcastStatus("WiFi Receiver OFF")
        super.onDestroy()
    }

    private fun serveLoop() {
        try {
            serverSocket = ServerSocket(PORT)
            while (running.get()) {
                val client = serverSocket!!.accept()
                clientSocket = client
                handleClient(client)
            }
        } catch (e: Exception) {
            if (running.get()) broadcastStatus("WiFi Receiver error: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        var track: AudioTrack? = null
        try {
            val input = DataInputStream(socket.getInputStream())
            val magic = ByteArray(4)
            input.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "AAS1") {
                broadcastStatus("Galat client connect hua — sirf Aasif phone app chalega")
                return
            }
            val sampleRate = input.readInt()
            val channels = input.readInt()
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            broadcastStatus("WiFi se phone connect ho gaya — audio TV par baj raha hai (${sampleRate}Hz, ${channels}ch)")
            val buffer = ByteArray(8192)
            while (running.get() && !socket.isClosed) {
                val read = input.read(buffer)
                if (read <= 0) break
                track.write(buffer, 0, read)
            }
        } catch (e: Exception) {
            if (running.get()) broadcastStatus("WiFi stream band: ${e.message}")
        } finally {
            try { track?.stop() } catch (e: Exception) {}
            track?.release()
            try { socket.close() } catch (e: Exception) {}
            if (running.get()) broadcastStatus("Phone disconnect — naya connection wait ho raha hai")
        }
    }

    private fun registerNsd() {
        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "Aasif Bluetooth Stereo"
                serviceType = SERVICE_TYPE
                port = PORT
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            // NSD fail ho to bhi TCP server chalta rahega, phone IP type karke connect kar sakta hai
        }
    }

    private fun unregisterNsd() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {}
        registrationListener = null
    }

    private fun broadcastStatus(text: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, text))
    }
}
