package com.chris.sharkhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.NativeApp
import com.chris.sharkhub.car.ProbeExport
import com.chris.sharkhub.sideload.SideloadServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Discovery screen. On the Shark 6 you use this to learn the REAL surface:
 *   - which access strategy connected (system service vs manager class)
 *   - every public method on the service object (name, params, return type)
 *   - which candidate BYD/native packages are installed (for deep links)
 *
 * Long-press a method row to copy its signature. Feed what you find back into CarManager's
 * candidate name lists and DeepLinks.NativeApp table.
 */
@Composable
fun ProbeScreen(nav: NavController, car: CarManager) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    // The live backend's methods, once discovery (which runs in the background) has finished.
    val discovery by car.discovery.collectAsStateWithLifecycle()
    val methods = remember(discovery) { car.probeSignatures() }
    val filtered = methods.filter { it.contains(query, ignoreCase = true) }
    val pkgs = remember { NativeApp.entries.map { it to NativeApp.installed(ctx, it) } }

    // Wi-Fi export: serve the probe report from memory so a phone can download it (no SD/USB/ADB).
    var serverOn by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var exportMsg by remember { mutableStateOf<String?>(null) }
    val server = remember {
        SideloadServer(ctx, onApkReceived = null, onLog = { exportMsg = it }).apply {
            addDownload("probe.json", "sharkhub-probe.json", "application/json") {
                ProbeExport.build(ctx, car).toByteArray()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { server.stop() } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Service Probe", nav,
            subtitle = exportMsg ?: "Backend: ${car.backendName} · ${methods.size} methods") {
            when {
                discovery == null -> StatusChip("Searching…", cs.onSurfaceVariant)
                car.isConnected -> StatusChip("Backend found", cs.primary)
                else -> StatusChip("No backend", cs.secondary)
            }
        }

        Panel(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (!serverOn) {
                            if (server.start(preferredPort = 8090)) {
                                val ips = SideloadServer.localIpv4s().ifEmpty { listOf("<car-ip>") }
                                serverUrl = ips.joinToString(" or ") { "http://$it:${server.port}" }
                                serverOn = true
                            }
                        } else { server.stop(); serverOn = false; serverUrl = null }
                    },
                    modifier = Modifier.height(48.dp)
                ) { Text(if (serverOn) "Stop Wi-Fi export" else "Export over Wi-Fi") }

                OutlinedButton(
                    onClick = {
                        exportMsg = "Building report…"
                        // Hundreds of read-only calls to the car: keep them off the main thread.
                        scope.launch(Dispatchers.IO) {
                            ProbeExport.toLogcat(ProbeExport.build(ctx, car))
                            exportMsg = "Dumped to logcat (tag SharkHubProbe)"
                        }
                    },
                    modifier = Modifier.height(48.dp)
                ) { Text("Dump to logcat") }

                Text(
                    if (serverOn && serverUrl != null) "Open $serverUrl on your phone → tap sharkhub-probe.json"
                    else "The report has every method, installed package and discovery attempt.",
                    color = if (serverOn) cs.primary else cs.onSurfaceVariant,
                    fontFamily = if (serverOn) FontFamily.Monospace else null,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }

        Split(
            Modifier.weight(1f).fillMaxWidth().padding(20.dp),
            firstWeight = 1.5f,
            first = { pane ->
            // Left (top in portrait): method list
            Panel(pane) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Filter methods — temp, seat, ac, soc…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    if (methods.isEmpty()) {
                        Text(
                            "No car backend connected. From a PC with ADB:\n" +
                                "  adb connect <car-ip>:5555\n" +
                                "  adb shell dumpsys -l | grep -i -E 'byd|auto|car'\n" +
                                "  adb shell pm list packages | grep -i byd\n\n" +
                                "Add the service/class name you find to CarBackend's candidate lists, " +
                                "rebuild, and this list will populate. The exported report also says " +
                                "why each candidate failed.",
                            Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant
                        )
                    } else {
                        SelectionContainer(Modifier.padding(top = 8.dp)) {
                            LazyColumn {
                                items(filtered) { sig ->
                                    Text(sig, Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = cs.onSurface)
                                    HorizontalDivider(color = cs.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
            },
        ) { pane ->
            // Right (bottom in portrait): installed native packages for deep links
            Panel(pane) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    SectionLabel("Native apps (deep-link table)")
                    LazyColumn(Modifier.padding(top = 8.dp)) {
                        items(pkgs) { (app, installed) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = installed) { NativeApp.launch(ctx, app) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                                    Text(app.pkg, fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                                }
                                StatusChip(if (installed) "Installed" else "Missing",
                                    if (installed) cs.primary else cs.secondary)
                            }
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
