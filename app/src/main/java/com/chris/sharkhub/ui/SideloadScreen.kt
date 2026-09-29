package com.chris.sharkhub.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.chris.sharkhub.ota.OtaUpdater
import com.chris.sharkhub.sideload.ApkInstaller
import com.chris.sharkhub.sideload.SideloadServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-app sideloader. Three ways to get an APK onto the unit without a PC ADB cable each time:
 *   1) Wi-Fi upload server  — open the shown URL on your phone/PC, drag an APK, it installs.
 *   2) Install from URL      — paste a direct .apk link; downloads then installs.
 *   3) Local files           — installs .apk files already in Download/USB storage.
 * All installs go through PackageInstaller (root-free; you confirm the system dialog once).
 */
@Composable
fun SideloadScreen(nav: NavController) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val installer = remember { ApkInstaller(ctx) }
    val ota = remember { OtaUpdater(ctx) }   // reuse its downloader for install-from-URL

    var serverOn by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf("") }
    var urlField by remember { mutableStateOf("") }

    val server = remember {
        SideloadServer(
            ctx = ctx,
            onApkReceived = { file ->
                // Runs on the server's thread, so the blocking APK copy is already off the main thread.
                installer.installFromFile(file) { log = it }
                    .onFailure { log = "install failed: ${it.message}" }
            },
            onLog = { log = it }
        )
    }
    DisposableEffect(Unit) { onDispose { server.stop() } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Sideload", nav, subtitle = log.ifBlank { "Install apps without a PC" }) {
            if (serverOn) StatusChip("Upload server on", cs.primary)
        }
        Split(
            Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            firstWeight = 1.1f,
            first = { m -> Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 1) Wi-Fi upload server
                Panel(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CardTitle(Icons.Rounded.Wifi, "Wi-Fi upload",
                            "Open the address in a browser on any phone or PC on the same Wi-Fi, pick an APK, and it installs here.")
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (serverOn) cs.primary.copy(alpha = 0.10f) else cs.surface)
                                .border(1.dp, if (serverOn) cs.primary.copy(alpha = 0.45f) else cs.outlineVariant,
                                    RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (serverOn && serverUrl != null) {
                                Text(serverUrl!!, fontFamily = FontFamily.Monospace, fontSize = 30.sp, color = cs.primary)
                            } else {
                                Text("Server off", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                            }
                        }
                        Button(
                            onClick = {
                                if (!serverOn) {
                                    if (server.start()) {
                                        val ips = SideloadServer.localIpv4s().ifEmpty { listOf("<car-ip>") }
                                        serverUrl = ips.joinToString("\n") { "http://$it:${server.port}" }
                                        serverOn = true
                                    }
                                } else {
                                    server.stop(); serverOn = false; serverUrl = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text(if (serverOn) "Stop server" else "Start upload server") }
                    }
                }
                // 2) Install from URL
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CardTitle(Icons.Rounded.Link, "Install from URL", "A direct link to an .apk file")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(urlField, { urlField = it },
                                placeholder = { Text("https://…/app.apk") },
                                singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    log = "downloading…"
                                    val dest = File(ctx.cacheDir, "incoming/download.apk")
                                    withContext(Dispatchers.IO) {
                                        ota.download(urlField, dest) { log = "downloading $it%" }
                                            .mapCatching { f -> installer.installFromFile(f) { log = it }.getOrThrow() }
                                    }.onFailure { log = "failed: ${it.message}" }
                                }
                            }, enabled = urlField.startsWith("http"), modifier = Modifier.height(52.dp)) {
                                Text("Install")
                            }
                        }
                    }
                }
            } },
        ) { m ->
            // 3) Local APK files
            Panel(m) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CardTitle(Icons.Rounded.Android, "Local APK files", "From Download/ or a USB stick")
                    val apks = remember { findApks() }
                    if (apks.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("No .apk files found in Download or shared storage.\nUse Wi-Fi upload, or copy one to Download/ from a USB stick.",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(apks) { f ->
                                Panel(Modifier.fillMaxWidth(), onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        installer.installFromFile(f) { log = it }
                                            .onFailure { log = "install failed: ${it.message}" }
                                    }
                                }) {
                                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(f.name, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${f.length() / 1024 / 1024} MB", style = MaterialTheme.typography.bodyMedium,
                                                color = cs.onSurfaceVariant)
                                        }
                                        Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CardTitle(icon: ImageVector, title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon, cs.primary, size = 44.dp)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** Scan common locations for .apk files (best-effort; respects scoped storage). */
private fun findApks(): List<File> {
    val dirs = runCatching {
        listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            @Suppress("DEPRECATION") Environment.getExternalStorageDirectory(),
        )
    }.getOrDefault(emptyList())
    return dirs.flatMap { d ->
        runCatching { d.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
    }.distinctBy { it.absolutePath }
}
