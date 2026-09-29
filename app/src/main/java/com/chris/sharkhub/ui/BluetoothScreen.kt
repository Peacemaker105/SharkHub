package com.chris.sharkhub.ui

import android.content.BroadcastReceiver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.chris.sharkhub.bt.BtDeviceInfo
import com.chris.sharkhub.bt.BtHelper

/**
 * Bluetooth input helper. Lists bonded devices, scans for new ones, classifies mice/keyboards/
 * gamepads, and routes to the system pairing UI. See BtHelper for the honest limits on forcing
 * HID input acceptance.
 */
@Composable
fun BluetoothScreen(nav: NavController) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val bt = remember { BtHelper(ctx) }

    var bonded by remember { mutableStateOf<List<BtDeviceInfo>>(emptyList()) }
    val found = remember { mutableStateListOf<BtDeviceInfo>() }
    var scanning by remember { mutableStateOf(false) }
    var receiver by remember { mutableStateOf<BroadcastReceiver?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { bonded = safeBonded(bt) }

    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { bonded = safeBonded(bt) }

    LaunchedEffect(Unit) {
        requestPerms(permLauncher)
        bonded = safeBonded(bt)
    }
    DisposableEffect(Unit) { onDispose { bt.stopDiscovery(receiver) } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Bluetooth input", nav,
            subtitle = when {
                !bt.supported -> "No Bluetooth adapter on this unit"
                !bt.enabled -> "Bluetooth is off"
                else -> "Mice, keyboards and gamepads"
            }) {
            if (scanning) StatusChip("Scanning", cs.primary)
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    // Needs BLUETOOTH_CONNECT on API 31+; if that was denied, don't crash on the tap.
                    if (!bt.enabled) runCatching { enableLauncher.launch(bt.enableRequestIntent()) }
                    else {
                        found.clear(); scanning = true
                        bt.stopDiscovery(receiver)   // repeated taps would otherwise stack receivers
                        receiver = bt.startDiscovery(
                            onFound = { info ->
                                if (found.none { it.address == info.address }) found.add(info)
                            },
                            onFinished = { scanning = false },
                        )
                    }
                },
                enabled = bt.supported,
                modifier = Modifier.height(52.dp)
            ) {
                Icon(Icons.Rounded.BluetoothSearching, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (bt.enabled || !bt.supported) "Scan for devices" else "Turn Bluetooth on")
            }
            OutlinedButton(onClick = { runCatching { bt.openSystemBtSettings() } }, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open system pairing")
            }
        }
        Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 20.dp)) {
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 0.dp))
        }

        Split(
            Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            first = { pane -> DeviceColumn("Paired", bonded, "Nothing paired yet.", pane) },
        ) { pane ->
            DeviceColumn("Discovered", found,
                if (scanning) "Looking…" else "Tap “Scan for devices” with the controller in pairing mode.",
                pane)
        }

        Text(
            "Pairing bonds the device, but whether the head unit accepts it as a live mouse, keyboard " +
                "or gamepad is up to the OS input stack. If a bonded device isn't recognised, see the " +
                "README's HID section.",
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceColumn(title: String, list: List<BtDeviceInfo>, empty: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Panel(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(title)
                Spacer(Modifier.weight(1f))
                if (list.isNotEmpty()) SectionLabel("${list.size}")
            }
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(empty, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(list) { d ->
                        DeviceRow(d)
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: BtDeviceInfo) {
    val cs = MaterialTheme.colorScheme
    val icon: ImageVector = when {
        d.kind.contains("gamepad") -> Icons.Rounded.Gamepad
        d.kind.contains("keyboard") -> Icons.Rounded.Keyboard
        d.kind.contains("mouse") || d.kind.contains("pointer") -> Icons.Rounded.Mouse
        else -> Icons.Rounded.Bluetooth
    }
    val isInput = d.kind.let {
        it.contains("gamepad") || it.contains("keyboard") || it.contains("mouse") ||
            it.contains("pointer") || it.contains("peripheral") || it.contains("remote")
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        IconBadge(icon, if (isInput) cs.primary else cs.onSurfaceVariant, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text("${d.address} · ${d.kind}", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        if (isInput) StatusChip("Input", cs.primary)
    }
}

private fun safeBonded(bt: BtHelper): List<BtDeviceInfo> =
    runCatching { bt.bondedDevices() }.getOrDefault(emptyList())

private fun requestPerms(
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val perms = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    launcher.launch(perms)
}
