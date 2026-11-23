package com.aasif.btstereo.tv

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import java.lang.reflect.Method

/**
 * Bluetooth A2DP Sink helper.
 *
 * Sachhi baat: A2DP Sink (profile id 11) ek system profile hai. Ek normal
 * third-party app Bluetooth stack ko zabardasti Sink nahi bana sakti —
 * ye TV ke firmware me manufacturer ne enable kiya hota hai ya nahi.
 * OnePlus TV, kai Sony / Xiaomi TV me ye pehle se enable milta hai aur
 * system me "TV Bluetooth Stereo / Bluetooth Audio / Bluetooth Music"
 * naam ki chhupi activity hoti hai.
 *
 * Is helper ka kaam:
 *  1. Sink support detect karna (profile proxy + connection state, reflection se)
 *  2. System ki wohi chhupi Bluetooth-audio activity dhundh ke kholna
 *  3. TV ko discoverable banana taaki phone use pair kar sake
 *  4. Pehle se paired devices ki list dena
 */
object BluetoothSinkHelper {

    /** BluetoothProfile.A2DP = 2 (source side), A2DP_SINK = 11 (hidden constant). */
    const val PROFILE_A2DP = 2
    const val PROFILE_A2DP_SINK = 11
    const val PROFILE_AVRCP_CONTROLLER = 12

    const val ACTION_A2DP_SINK_CONNECTION_STATE_CHANGED =
        "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"

    fun adapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothOn(context: Context): Boolean = adapter(context)?.isEnabled == true

    /**
     * Sink support ka best-effort detection:
     * - Agar A2DP_SINK profile par koi state milta hai (disconnected bhi), profile मौजूद hai.
     * - Ya system me koi bluetooth-audio activity milti hai.
     * Note: kuch firmware getProfileConnectionState(11) par hamesha STATE_DISCONNECTED
     * dete hain chahe sink band ho, isliye result ko "likely" kaha gaya hai —
     * pakka test phone pair karke hi hota hai.
     */
    fun detectSinkSupport(context: Context): SinkSupport {
        val bt = adapter(context) ?: return SinkSupport(false, "Is TV me Bluetooth adapter nahi mila")
        val activity = findBluetoothAudioActivity(context)
        var profileKnown = false
        try {
            val state = bt.getProfileConnectionState(PROFILE_A2DP_SINK)
            profileKnown = state != BluetoothProfile.STATE_DISCONNECTED || activity != null
        } catch (e: Exception) {
            profileKnown = activity != null
        }
        val reason = when {
            activity != null -> "System Bluetooth Audio activity mili: ${activity.flattenToShortString()}"
            profileKnown -> "A2DP Sink profile (11) system me jawab de raha hai"
            else -> "Na A2DP Sink profile mila, na system Bluetooth Audio activity — is TV par Bluetooth mode shayad firmware me band hai, WiFi mode use karo"
        }
        return SinkSupport(profileKnown || activity != null, reason)
    }

    data class SinkSupport(val likelySupported: Boolean, val detail: String)

    /**
     * Known + discovered candidates for the system's Bluetooth audio receiver.
     * Pehle known component list try hoti hai, phir PackageManager se scan:
     * koi bhi exported activity jiske naam/package me bluetooth + (audio|music|stereo|sink) ho.
     */
    fun findBluetoothAudioActivity(context: Context): ComponentName? {
        val known = listOf(
            // OnePlus / Oppo TV Bluetooth stereo
            ComponentName("com.oneplus.tv.bluetooth", "com.oneplus.tv.bluetooth.BluetoothStereoActivity"),
            ComponentName("com.oplus.tv.bluetooth", "com.oplus.tv.bluetooth.BluetoothStereoActivity"),
            // AOSP / Android TV settings bluetooth
            ComponentName("com.android.tv.settings", "com.android.tv.settings.accessories.BluetoothActivity"),
            ComponentName("com.android.settings", "com.android.settings.bluetooth.BluetoothSettings"),
            // Xiaomi / MiTV
            ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.bluetooth.BluetoothActivity"),
            // Generic AOSP bluetooth music receiver
            ComponentName("com.android.bluetooth", "com.android.bluetooth.a2dpsink.A2dpSinkActivity")
        )
        val pm = context.packageManager
        for (candidate in known) {
            try {
                pm.getActivityInfo(candidate, 0)
                return candidate
            } catch (e: PackageManager.NameNotFoundException) {
                // next candidate
            }
        }
        // Dynamic scan: LAUNCHER/MAIN nahi, Settings categories wali activities dekhte hain
        val scanIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        val resolved = try {
            pm.queryIntentActivities(scanIntent, PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            emptyList()
        }
        for (info in resolved) {
            val comp = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            val hay = (comp.packageName + "." + comp.className).lowercase()
            if (hay.contains("bluetooth") && (hay.contains("audio") || hay.contains("music") || hay.contains("stereo") || hay.contains("sink"))) {
                return comp
            }
        }
        return resolved.firstOrNull()?.let {
            ComponentName(it.activityInfo.packageName, it.activityInfo.name)
        }
    }

    /** System Bluetooth Audio / Settings kholo. True agar kuch khula. */
    fun openSystemBluetoothAudio(context: Context): Boolean {
        val comp = findBluetoothAudioActivity(context)
        if (comp != null) {
            try {
                val intent = Intent().setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // fall through to generic settings
            }
        }
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(context: Context): List<BluetoothDevice> {
        return try {
            adapter(context)?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Hidden BluetoothDevice.connect() reflection — kuch firmware par sink connect trigger karta hai. */
    @SuppressLint("MissingPermission")
    fun tryConnectSink(context: Context, device: BluetoothDevice): Boolean {
        val bt = adapter(context) ?: return false
        var ok = false
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    val connect: Method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    ok = connect.invoke(proxy, device) as? Boolean ?: false
                } catch (e: Exception) {
                    ok = false
                } finally {
                    bt.closeProfileProxy(profile, proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }
        return try {
            bt.getProfileProxy(context, listener, PROFILE_A2DP_SINK)
            true // request bhej di; asal result connection broadcast se pata chalega
        } catch (e: Exception) {
            ok
        }
    }
}
