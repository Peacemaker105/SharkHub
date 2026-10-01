package com.chris.sharkhub.ui.bake

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.chris.sharkhub.bake.BakeFiles
import com.chris.sharkhub.bake.BakeInfo
import com.chris.sharkhub.bake.BakePhase
import com.chris.sharkhub.bake.BakeRunner
import com.chris.sharkhub.bake.BakeState
import com.chris.sharkhub.bake.CarSources
import com.chris.sharkhub.ui.ActionChip
import com.chris.sharkhub.ui.IconBadge
import com.chris.sharkhub.ui.Panel
import com.chris.sharkhub.ui.ScreenHeader
import com.chris.sharkhub.ui.SectionLabel
import com.chris.sharkhub.ui.Split
import com.chris.sharkhub.ui.StatusChip
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** What the bake screen knows about this unit before anything runs. */
class BakeSetup(
    /** BYD's My Car app with the Shark model is on this unit. */
    val hasModel: Boolean,
    /** Rage Mode's scene files (the x-ray driveline) are on this unit. */
    val hasDriveline: Boolean,
    /** The last successful bake, if any. */
    val last: BakeInfo?,
    /** A set exists but an older renderer made it. */
    val needsRebuild: Boolean,
)

/**
 * Options → "Build the truck from this car": runs the bake (bake/BakeRunner) in the foreground with
 * its progress, a live preview of the renderer, the log, and cancel. Leaving the screen cancels a
 * running bake — the renderer lives in this screen's WebView.
 */
@Composable
fun BakeScreen(nav: NavController) {
    val ctx = LocalContext.current
    val state by BakeRunner.state.collectAsState()
    var setup by remember { mutableStateOf(readSetup(ctx)) }
    LaunchedEffect(state.phase) { if (!state.busy) setup = readSetup(ctx) }
    BakeContent(
        nav = nav, state = state, setup = setup,
        onStart = { BakeRunner.reset(); BakeRunner.start(ctx) },
        onCancel = { BakeRunner.cancel("cancelled from the screen") },
        onRemove = { BakeRunner.remove(ctx); setup = readSetup(ctx) },
        preview = {
            // the renderer's WebView lives here: releasing it (leaving the screen) cancels a running bake
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c -> WebView(c).also { BakeRunner.attach(it) } },
                onRelease = { BakeRunner.detach(it); it.destroy() },
            )
        },
    )
}

private fun readSetup(ctx: android.content.Context): BakeSetup {
    val sources = CarSources.find(ctx)
    return BakeSetup(sources.hasModel, sources.rageDir != null, BakeInfo.read(BakeFiles(ctx).state), BakeRunner.needsRebuild(ctx))
}

@Composable
fun BakeContent(
    nav: NavController,
    state: BakeState,
    setup: BakeSetup,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.busy) { while (state.busy) { delay(1000); now = System.currentTimeMillis() } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Build the truck from this car", nav, subtitle = "The dashboard truck rendered from the model in this car's own head unit — nothing leaves the car") {
            StatusChip(state.phase.label, when (state.phase) {
                BakePhase.DONE -> cs.tertiary; BakePhase.FAILED, BakePhase.CANCELLED -> cs.error
                BakePhase.IDLE -> cs.onSurfaceVariant; else -> cs.primary
            })
        }
        Split(
            Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            firstWeight = 1.1f,
            first = { m -> Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconBadge(Icons.Rounded.Build, cs.primary, size = 44.dp)
                            Column(Modifier.weight(1f)) {
                                Text("Your own truck", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                                Text(
                                    "BYD's My Car app carries a 3D model of the Shark 6. This reads it from this unit, renders the dashboard's " +
                                        "truck layers right here and keeps them in Shark Hub's own storage. The public app ships none of BYD's art.",
                                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                                )
                            }
                        }
                        SetupRow("Model", if (setup.hasModel) "My Car app found" else "My Car app not on this unit", setup.hasModel)
                        SetupRow("Driveline", if (setup.hasDriveline) "Rage Mode files found (x-ray driveline)" else "Rage Mode files absent — x-ray shows the shell only", setup.hasDriveline)
                        val last = setup.last
                        SetupRow("Last build",
                            if (last == null) "none yet — the bundled truck is in use"
                            else "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last.finishedAt))} · ${last.files} files · ${"%.0f".format(last.bytes / 1048576.0)} MB · ${last.seconds / 60} min" +
                                (if (setup.needsRebuild) " · made by an older renderer, rebuild recommended" else ""),
                            last != null && !setup.needsRebuild)
                        Text(
                            "Takes a few minutes; keep this screen open. The previous set stays until the new one is complete. " +
                                "Paint colour is applied live by the app, so a rebuild is only needed when the scenery changes.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.busy) ActionChip("Cancel", Icons.Rounded.Close, cs.error, onClick = onCancel)
                            else {
                                ActionChip(if (setup.last == null) "Build now" else "Rebuild", if (setup.last == null) Icons.Rounded.PlayArrow else Icons.Rounded.Refresh, onClick = onStart)
                                if (setup.last != null) ActionChip("Remove", Icons.Rounded.DeleteOutline, cs.onSurfaceVariant, onClick = onRemove)
                            }
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Progress")
                        Text(state.message.ifEmpty { "Not started" }, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                        LinearProgressIndicator(progress = { state.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = if (state.phase == BakePhase.FAILED) cs.error else cs.primary, trackColor = cs.surfaceVariant)
                        val elapsed = if (state.startedAt > 0) ((if (state.busy) now else state.finishedAt.takeIf { it > 0 } ?: now) - state.startedAt) / 1000 else 0L
                        Text(
                            buildString {
                                append(state.phase.label)
                                if (state.phase == BakePhase.RENDER && state.layersTotal > 0) append(" · ${state.layersDone} of ${state.layersTotal} layers")
                                if (elapsed > 0) append(" · ${elapsed / 60} min ${elapsed % 60} s")
                                if (!state.driveline) append(" · no driveline")
                            },
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Log  ·  adb logcat -s SharkHubBake:W")
                        state.probe?.let { Text("WebGL: " + it.replace("\n", " ").take(400), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace) }
                        if (state.log.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        state.log.takeLast(14).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace) }
                    }
                }
            } },
        ) { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Renderer")
                Panel(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.fillMaxSize().background(cs.surfaceVariant)) { preview() }
                }
                Text("What the renderer is drawing right now — each layer is saved as it finishes.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}

@Composable
private fun SetupRow(label: String, value: String, good: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(0.3f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = if (good) cs.onSurface else cs.secondary, modifier = Modifier.weight(1f))
    }
}
