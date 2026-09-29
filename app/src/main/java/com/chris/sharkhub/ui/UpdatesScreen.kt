package com.chris.sharkhub.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.ota.OtaUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The OTA updater UI. Check → download (with progress) → install. Reached from Options.
 */
@Composable
fun UpdatesScreen(nav: NavController) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val prefs = remember { Prefs(ctx) }
    val ota = remember { OtaUpdater(ctx) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf(prefs.otaManifestUrl) }
    var state by remember { mutableStateOf<OtaUpdater.State>(OtaUpdater.State.Idle) }
    // Re-checked on resume so "Allow installs" goes away once it's granted in Settings.
    var canInstall by remember { mutableStateOf(ota.canInstall()) }
    LifecycleResumeEffect(Unit) {
        canInstall = ota.canInstall()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Updates", nav,
            subtitle = "Installed v${ota.currentVersionName()} (build ${ota.currentVersionCode()})")

        Split(
            Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            firstWeight = 1.4f,
            first = { pane -> Panel(pane) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CardTitle(Icons.Rounded.SystemUpdate, "Over-the-air update",
                        "Checks a latest.json manifest and installs newer builds of Shark Hub")

                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; prefs.otaManifestUrl = it },
                        label = { Text("Manifest URL (latest.json)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                state = OtaUpdater.State.Checking
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { ota.fetchManifest(url) }
                                    state = r.fold(
                                        onSuccess = { m ->
                                            if (m.versionCode > ota.currentVersionCode())
                                                OtaUpdater.State.Available(m)
                                            else OtaUpdater.State.UpToDate(ota.currentVersionCode())
                                        },
                                        onFailure = { OtaUpdater.State.Error(it.message ?: "check failed") }
                                    )
                                }
                            },
                            enabled = url.startsWith("http"),
                            modifier = Modifier.height(52.dp)
                        ) { Text("Check for updates") }

                        if (!canInstall) {
                            OutlinedButton(
                                onClick = { ctx.startActivity(ota.openInstallPermission()) },
                                modifier = Modifier.height(52.dp)
                            ) { Text("Allow installs") }
                        }
                    }

                    OtaStatus(state, onInstall = { m ->
                        state = OtaUpdater.State.Downloading(0)
                        scope.launch {
                            state = withContext(Dispatchers.IO) {
                                ota.downloadApk(m) { pct -> state = OtaUpdater.State.Downloading(pct) }
                                    .mapCatching { f -> ota.install(f).getOrThrow(); f }
                            }.fold(
                                onSuccess = { OtaUpdater.State.ReadyToInstall(it) },
                                onFailure = { OtaUpdater.State.Error(it.message ?: "update failed") }
                            )
                        }
                    })
                }
            } },
        ) { pane ->
            Panel(pane) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SectionLabel("This build")
                    Row {
                        StatBlock("Version", ota.currentVersionName(), Modifier.weight(1f))
                        StatBlock("Build", "${ota.currentVersionCode()}", Modifier.weight(1f))
                    }
                    StatBlock("Package", ctx.packageName)
                    StatBlock("Installs allowed", if (canInstall) "Yes" else "Not yet",
                        valueColor = if (canInstall) cs.primary else cs.secondary)
                    Spacer(Modifier.weight(1f))
                    Text("Updates must be signed with the same key as the installed build. Shark Hub " +
                        "refuses any APK that isn't itself, and closes while an update applies.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun OtaStatus(state: OtaUpdater.State, onInstall: (OtaUpdater.Manifest) -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        is OtaUpdater.State.Idle -> {}
        is OtaUpdater.State.Checking ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp)); Text("Checking…", color = cs.onSurface)
            }
        is OtaUpdater.State.UpToDate ->
            Text("You're on the latest (build ${state.current}).", color = cs.primary)
        is OtaUpdater.State.Available -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Update available: v${state.m.versionName} (build ${state.m.versionCode})",
                    style = MaterialTheme.typography.titleMedium, color = cs.primary)
                if (state.m.notes.isNotBlank())
                    Text(state.m.notes, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Button(onClick = { onInstall(state.m) }) { Text("Download & install") }
            }
        }
        is OtaUpdater.State.Downloading -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Downloading… ${state.pct}%", color = cs.onSurface)
                LinearProgressIndicator(progress = { state.pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        is OtaUpdater.State.ReadyToInstall ->
            Text("Installing — Shark Hub closes while the update applies. Reopen it from the app grid.",
                color = cs.primary)
        is OtaUpdater.State.Error ->
            Text("Error: ${state.message}", color = cs.error)
    }
}
