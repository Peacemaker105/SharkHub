package com.chris.sharkhub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.chris.sharkhub.car.NativeApp

/**
 * Sentry / dashcam. Honest placeholder: the surround cameras are walled off from third-party apps
 * on this firmware (see docs/CAMERAS_SENTRY.md), so until that's cracked this screen explains the
 * status, opens BYD's own 360 view, and lists what the feature will do once it can see a camera.
 */
@Composable
fun SentryScreen(nav: NavController) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Sentry & dashcam", nav, subtitle = "Camera access on this firmware is still being cracked") {
            StatusChip("Not available yet", cs.secondary)
        }
        Split(Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), first = { pane ->
            Panel(pane) {
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconBadge(Icons.Rounded.Security, cs.secondary)
                        Text("Where it's at", style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                    }
                    Text(
                        "The Shark 6's surround cameras only talk to BYD's own apps: the camera library is " +
                            "walled off by the unit's security policy, and the automotive camera service " +
                            "has no permission a sideloaded app can ask for. Shark Hub can't record from " +
                            "them yet — that's the firmware, not the car.",
                        style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                    Text(
                        "Next test: whether any third-party dashcam works on this build. If one does, " +
                            "this screen gets loop recording, parked sentry with radar + motion triggers, " +
                            "and a clip library — all on-device.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { NativeApp.launch(ctx, NativeApp.SURROUND_CAM) }, modifier = Modifier.height(52.dp)) {
                        Icon(Icons.Rounded.Videocam, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text("Open BYD 360 camera")
                    }
                }
            }
        }) { pane ->
            Panel(pane) {
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Planned")
                    listOf(
                        "Dashcam: front / rear / 2×2, 1080p30, H.264/H.265, loop segments with a storage budget",
                        "Auto start on drive, manual save & lock clip",
                        "Sentry when parked: motion + parking-radar proximity + impact triggers, pre/post-roll",
                        "Battery floor so sentry can't flatten the pack",
                        "Clip library with playback, lock, delete, export over Wi-Fi",
                    ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                }
            }
        }
    }
}
