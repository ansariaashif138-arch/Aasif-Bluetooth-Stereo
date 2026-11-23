package com.aasif.btstereo.phone

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Aasif Bluetooth Stereo — Phone companion
 *
 * Bluetooth mode: phone par koi capture nahi chahiye. Sirf neeche wale button
 * se Bluetooth settings kholo, TV (jo discoverable hai) ko pair karo — uske
 * baad phone ki saari audio khud TV par jayegi, bilkul Bluetooth speaker ki
 * tarah. Ye tabhi chalega jab TV ke firmware me A2DP Sink ho.
 *
 * WiFi mode: TV NSD par khud ko advertise karta hai ("Aasif Bluetooth Stereo").
 * TV mil jaye to IP khud bhar jata hai, warna haath se type kar sakte ho.
 * "WiFi se TV par audio bhejo" dabane par Android screen-capture wali
 * permission mangega (audio capture ke liye yahi official rasta hai),
 * phir AudioCaptureService stream shuru kar deta hai.
 */
class PhoneActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioCaptureService.ACTION_STATUS) {
                statusText.text = intent.getStringExtra(AudioCaptureService.EXTRA_STATUS) ?: ""
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone)

        statusText = findViewById(R.id.phoneStatus)
        ipInput = findViewById(R.id.tvIpInput)

        findViewById<Button>(R.id.btnBtSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                statusText.text = "Bluetooth settings khul gaya — list me apni TV chuno aur pair karo. TV par pehle 'Discoverable' wala button dabana mat bhoolna."
            } catch (e: Exception) {
                statusText.text = "Bluetooth settings nahi khule: ${e.message}"
            }
        }
        findViewById<Button>(R.id.btnFindTv).setOnClickListener { discoverTv() }
        findViewById<Button>(R.id.btnStartWifi).setOnClickListener { askCapturePermission() }
        findViewById<Button>(R.id.btnStopWifi).setOnClickListener {
            stopService(Intent(this, AudioCaptureService::class.java))
            statusText.text = "WiFi stream band kar diya"
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3001)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3002)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AudioCaptureService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
        stopDiscovery()
        super.onPause()
    }

    private fun discoverTv() {
        stopDiscovery()
        statusText.text = "WiFi par TV dhundh raha hu..."
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val ip = resolved.host?.hostAddress ?: return
                        runOnUiThread {
                            ipInput.setText(ip)
                            statusText.text = "TV mil gaya: ${resolved.serviceName} — $ip"
                        }
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                })
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                runOnUiThread { statusText.text = "TV search fail (code $code) — IP haath se type kar do" }
            }

            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {}
        }
        try {
            nsdManager?.discoverServices("_aasifaudio._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            statusText.text = "Discovery nahi chali: ${e.message} — IP haath se type kar do"
        }
    }

    private fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
        discoveryListener = null
    }

    private fun askCapturePermission() {
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            statusText.text = "Pehle TV dhundho ya TV ka IP type karo (TV app ki screen par IP dikhta hai)"
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), 4001)
        pendingIp = ip
    }

    private var pendingIp: String = ""

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4001) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                    putExtra(AudioCaptureService.EXTRA_TV_IP, pendingIp)
                    putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(AudioCaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                statusText.text = "Connect ho raha hai $pendingIp par..."
            } else {
                statusText.text = "Permission cancel — bina iske Android phone ki audio capture nahi karne deta"
            }
        }
    }
}
