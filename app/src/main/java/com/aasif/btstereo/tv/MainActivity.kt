package com.aasif.btstereo.tv

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * Aasif Bluetooth Stereo — TV (saare Android TV ke liye)
 *
 * Do mode:
 *  1) BLUETOOTH: TV ko discoverable karke system ke A2DP Sink / TV Bluetooth
 *     Stereo ko kholta hai. Jahan firmware me sink hai (OnePlus, Sony, kai
 *     Xiaomi) waha phone bina kisi phone-app ke, sirf Bluetooth pairing se
 *     TV par audio bhejta hai.
 *  2) WIFI: Jahan sink firmware me band hai, waha ye app khud receiver ban
 *     jata hai — phone companion app WiFi par PCM audio bhejta hai aur TV
 *     use speaker par bajata hai. Isi se "feature nahi tha, app se aa gaya"
 *     wala result har Android TV par milta hai.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var supportText: TextView
    private lateinit var devicesText: TextView
    private var wifiRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiAudioService.ACTION_STATUS -> {
                    setStatus(intent.getStringExtra(WifiAudioService.EXTRA_STATUS) ?: "")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = deviceFromIntent(intent)
                    setStatus("Bluetooth connect: ${deviceName(device)} — phone me music bajao, awaaz TV par aayegi")
                    refreshDevices()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    setStatus("Bluetooth disconnect: ${deviceName(deviceFromIntent(intent))}")
                    refreshDevices()
                }
                BluetoothSinkHelper.ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED -> {
                    refreshDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        supportText = findViewById(R.id.supportText)
        devicesText = findViewById(R.id.devicesText)

        findViewById<Button>(R.id.btnDiscoverable).setOnClickListener { makeDiscoverable() }
        findViewById<Button>(R.id.btnOpenStereo).setOnClickListener {
            if (BluetoothSinkHelper.openSystemBluetoothAudio(this)) {
                setStatus("System Bluetooth Stereo / Settings khol diya — waha apna phone pair karo")
            } else {
                setStatus("System Bluetooth screen nahi khuli — is TV par Bluetooth mode mushkil hai, WiFi mode use karo")
            }
        }
        findViewById<Button>(R.id.btnWifiToggle).setOnClickListener { toggleWifi() }
        findViewById<Button>(R.id.btnPlayPause).setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

        requestNeededPermissions()
        refreshSupportInfo()
        refreshDevices()
        findViewById<Button>(R.id.btnDiscoverable).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiAudioService.ACTION_STATUS)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothSinkHelper.ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
        super.onPause()
    }

    private fun refreshSupportInfo() {
        val support = BluetoothSinkHelper.detectSinkSupport(this)
        val btState = if (BluetoothSinkHelper.isBluetoothOn(this)) "Bluetooth ON" else "Bluetooth OFF (Settings se ON karo)"
        supportText.text = buildString {
            append("Bluetooth Sink support: ")
            append(if (support.likelySupported) "LIKELY HAI ✓" else "NAHI MILA ✗")
            append("\n")
            append(support.detail)
            append("\n")
            append(btState)
            append("  •  TV ka WiFi IP: ")
            append(localIpAddress())
            append("\nAgar Bluetooth Sink nahi hai to ghabrana nahi — neeche WiFi Mode ON kar do, usi se phone ki audio is TV par aayegi. Yehi is app ka woh feature hai jo har TV par kaam karta hai.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        val devices = BluetoothSinkHelper.bondedDevices(this)
        devicesText.text = if (devices.isEmpty()) {
            "Paired devices: koi nahi — pehle phone pair karo"
        } else {
            "Paired devices:\n" + devices.joinToString("\n") { "• ${deviceName(it)}  [${it.address}]" }
        }
    }

    private fun makeDiscoverable() {
        if (!BluetoothSinkHelper.isBluetoothOn(this)) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1001)
            } catch (e: Exception) {}
            setStatus("Pehle Bluetooth ON karo, phir dobara ye button dabao")
            return
        }
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 1002)
            setStatus("TV 5 minute ke liye dikhai de raha hai — phone ke Bluetooth me jaake is TV ko pair karo")
        } catch (e: Exception) {
            setStatus("Discoverable nahi ho paya: ${e.message} — System Bluetooth Settings se pair karo")
            BluetoothSinkHelper.openSystemBluetoothAudio(this)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002) {
            if (resultCode != Activity.RESULT_CANCELED) {
                setStatus("Discoverable ON ($resultCode sec) — ab phone se pair karo")
            } else {
                setStatus("Discoverable cancel ho gaya")
            }
        }
    }

    private fun toggleWifi() {
        wifiRunning = !wifiRunning
        if (wifiRunning) {
            WifiAudioService.start(this)
            findViewById<Button>(R.id.btnWifiToggle).text = "WiFi Mode: OFF karo"
        } else {
            WifiAudioService.stop(this)
            findViewById<Button>(R.id.btnWifiToggle).text = "WiFi Mode: ON karo"
            setStatus("WiFi Receiver band")
        }
    }

    /** AVRCP media key — connected phone ko play/pause/next/prev bhejne ka best-effort rasta. */
    private fun dispatchMediaKey(keyCode: Int) {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audio.dispatchMediaKeyEvent(down)
            audio.dispatchMediaKeyEvent(up)
            setStatus("Media key bheja: $keyCode")
        } catch (e: Exception) {
            setStatus("Media key nahi gaya: ${e.message}")
        }
    }

    private fun localIpAddress(): String {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi.connectionInfo.ipAddress
            if (ip == 0) "WiFi se connect nahi" else listOf(
                ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff
            ).joinToString(".")
        } catch (e: Exception) {
            "pata nahi"
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
            needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2001)
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice?): String {
        if (device == null) return "phone"
        return try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }
}
