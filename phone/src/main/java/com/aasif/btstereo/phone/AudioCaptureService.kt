package com.aasif.btstereo.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone ki playback audio capture karke WiFi par TV ko bhejta hai.
 *
 * Android 10+ ka official rasta: MediaProjection ki permission ke saath
 * AudioPlaybackCapture — jo apps capture allow karti hain (zyadatar music /
 * video apps) unki audio TV par jayegi. Jo app DRM / capture-block lagati
 * hai, uski audio Android khud block kar deta hai — ye system ki limit hai,
 * app use bypass nahi karti.
 *
 * Protocol TV ke WifiAudioService jaisa hi hai:
 *  TCP, header "AAS1" + sampleRate + channels, phir raw PCM 16-bit stream.
 */
class AudioCaptureService : Service() {

    companion object {
        const val PORT = 24880
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TV_IP = "tv_ip"
        const val ACTION_STATUS = "com.aasif.btstereo.phone.CAPTURE_STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "aasif_audio_capture"
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var socket: Socket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val tvIp = intent.getStringExtra(EXTRA_TV_IP)?.trim().orEmpty()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (tvIp.isEmpty() || resultData == null) {
            broadcastStatus("TV ka IP ya screen-capture permission missing hai")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aasif Bluetooth Stereo")
            .setContentText("Phone ki audio TV par ja rahi hai — $tvIp")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(42, notification)
        }

        if (!running.compareAndSet(false, true)) return START_STICKY
        worker = Thread({ streamAudio(tvIp, resultCode, resultData) }, "AasifPhoneAudioCapture").apply { start() }
        return START_STICKY
    }

    private fun streamAudio(tvIp: String, resultCode: Int, resultData: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
                .build()

            socket = Socket(tvIp, PORT)
            val out = DataOutputStream(socket!!.getOutputStream())
            out.writeBytes("AAS1")
            out.writeInt(SAMPLE_RATE)
            out.writeInt(CHANNELS)
            out.flush()

            record!!.startRecording()
            broadcastStatus("Connected — phone ki audio ab TV par bajegi")
            val buffer = ByteArray(8192)
            while (running.get()) {
                val read = record!!.read(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                } else if (read < 0) {
                    break
                }
            }
        } catch (e: SecurityException) {
            broadcastStatus("Permission nahi mili: ${e.message}")
        } catch (e: Exception) {
            broadcastStatus("Stream band: ${e.message} — TV par WiFi Mode ON hai? IP sahi hai?")
        } finally {
            cleanup()
            stopSelf()
        }
    }

    private fun cleanup() {
        running.set(false)
        try { record?.stop() } catch (e: Exception) {}
        record?.release(); record = null
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        try { projection?.stop() } catch (e: Exception) {}
        projection = null
    }

    override fun onDestroy() {
        cleanup()
        broadcastStatus("Audio bhejna band")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun broadcastStatus(text: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, text))
    }
}
