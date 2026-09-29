package com.chris.sharkhub.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

data class BtDeviceInfo(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val majorClass: Int,
    val kind: String,
)

/**
 * Bluetooth discovery/pairing helper aimed at input devices (mice, keyboards, gamepads).
 *
 * Important honesty: an ordinary app cannot force the head unit to *use* an HID device the platform
 * refuses to bind. Whether a paired mouse/keyboard/gamepad is accepted as an input source is decided
 * by the OS input stack (HID/HOGP profile support + any BYD whitelist), not by us. What this helper
 * CAN do: enumerate and pair devices, classify them, surface the Bluetooth class bits, and hand off
 * to the system BT settings — which is usually enough to get a gamepad bonded. If input still isn't
 * delivered after bonding, that's an OS/firmware limitation and the probe/ADB notes in the README
 * cover the deeper options.
 */
class BtHelper(private val ctx: Context) {

    // Guarded: a unit (or renderer) without Bluetooth may throw here instead of returning null.
    private val adapter: BluetoothAdapter? = runCatching {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    val supported: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun hasScanPermission(): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.BLUETOOTH_ADMIN
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BtDeviceInfo> =
        adapter?.bondedDevices?.map { it.toInfo(true) } ?: emptyList()

    @SuppressLint("MissingPermission")
    fun startDiscovery(onFound: (BtDeviceInfo) -> Unit, onFinished: () -> Unit = {}): BroadcastReceiver? {
        val a = adapter ?: return null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND ->
                        IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            ?.let { onFound(it.toInfo(false)) }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()
                }
            }
        }
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })
        // startDiscovery() returns false (or throws without BLUETOOTH_SCAN) when it can't scan; call
        // that finished too so the UI doesn't sit on "scanning…" forever.
        if (!runCatching { a.startDiscovery() }.getOrDefault(false)) onFinished()
        return receiver
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery(receiver: BroadcastReceiver?) {
        runCatching { adapter?.cancelDiscovery() }
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
    }

    /** Kick the user to the system pairing UI — the reliable path for bonding a new device. */
    fun openSystemBtSettings() {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun enableRequestIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toInfo(bonded: Boolean): BtDeviceInfo {
        // Class (like name) needs BLUETOOTH_CONNECT on API 31+. This runs inside onReceive on the
        // main thread, so a denied permission must degrade, not crash the app.
        val cls = runCatching { bluetoothClass }.getOrNull()
        val major = cls?.majorDeviceClass ?: 0
        val devClass = cls?.deviceClass ?: 0
        return BtDeviceInfo(
            name = (runCatching { name }.getOrNull()) ?: "(unknown)",
            address = address,
            bonded = bonded,
            majorClass = major,
            kind = classifyInput(major, devClass)
        )
    }

    /**
     * Best-effort human label, biased toward the input devices we care about. Within the Peripheral
     * major class (0x0500) the minor byte holds two fields: bits 6-7 are keyboard/pointing flags and
     * bits 2-5 an *enumerated* sub-type (0x04 joystick, 0x08 gamepad, 0x0C remote…). Bit-testing
     * across the whole class value mislabelled remotes (0x050C) — and even wearables — as gamepads.
     */
    private fun classifyInput(major: Int, devClass: Int): String {
        val PERIPHERAL = 0x0500
        if (major == PERIPHERAL) {
            val kbPtr = devClass and 0xC0
            val subType = devClass and 0x3C
            return when {
                kbPtr == 0xC0 -> "keyboard + pointer"
                kbPtr == 0x40 -> "keyboard"
                kbPtr == 0x80 -> "mouse / pointer"
                subType == 0x04 || subType == 0x08 -> "gamepad"
                subType == 0x0C -> "remote control"
                else -> "input peripheral"
            }
        }
        return when (major) {
            0x0400 -> "audio/video"
            0x0200 -> "phone"
            else -> "other"
        }
    }
}
