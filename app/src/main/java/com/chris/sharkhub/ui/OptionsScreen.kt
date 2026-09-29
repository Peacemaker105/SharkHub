package com.chris.sharkhub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.chris.sharkhub.R
import com.chris.sharkhub.Routes
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.ota.OtaUpdater
import com.chris.sharkhub.ui.dash.DashLayout
import com.chris.sharkhub.ui.dash.HomeBackdrop
import com.chris.sharkhub.ui.dash.HomePreset
import com.chris.sharkhub.ui.theme.StyleSpec
import com.chris.sharkhub.ui.theme.Styles
import com.chris.sharkhub.ui.theme.ThemeController
import com.chris.sharkhub.ui.theme.ThemeSpec
import com.chris.sharkhub.ui.theme.Themes

/**
 * Options hub: theme selector (inline, live), driving side, and links to Sideload, Updates and the
 * Service Probe. Add more settings here as the app grows.
 */
@Composable
fun OptionsScreen(nav: NavController, themes: ThemeController) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val version = remember { OtaUpdater(ctx).currentVersionName() }
    var driverOnRight by remember { mutableStateOf(prefs.driverOnRight) }
    var homeLayout by remember { mutableStateOf(HomePreset.byId(prefs.homeLayout)) }
    var homeBackdrop by remember { mutableStateOf(HomeBackdrop.byId(prefs.homeBackdrop)) }
    var homeDock by remember { mutableStateOf(prefs.homeDock) }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Options", nav, subtitle = "Appearance, driving side, installs and updates")
        Split(
            Modifier.weight(1f).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            firstWeight = 1.3f,
            // three panels don't fit a 1080-px screen, so this side scrolls
            first = { m -> Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SettingTitle(Icons.Rounded.Palette, "Appearance", "Style is the shape language; theme is the colour — mix any two")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Styles.all.forEach { s ->
                                StyleCard(s, s.style == themes.style.style, Modifier.weight(1f)) { themes.selectStyle(s) }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Themes.all.forEach { spec ->
                                ThemeCard(spec, spec.id == themes.current.id, Modifier.weight(1f)) {
                                    themes.select(spec)
                                }
                            }
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingTitle(Icons.Rounded.Dashboard, "Home screen", "Which pages you boot into; changing the layout resets your pages")
                        SegmentedControl(HomePreset.entries.map { it.label }, HomePreset.entries.indexOf(homeLayout), Modifier.fillMaxWidth()) { i ->
                            homeLayout = HomePreset.entries[i]
                            prefs.homeLayout = homeLayout.id
                            DashLayout.reset(prefs)
                        }
                        Text(homeLayout.blurb, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Background", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                                Text("Cards go see-through over a picture", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            SegmentedControl(HomeBackdrop.entries.map { it.label }, HomeBackdrop.entries.indexOf(homeBackdrop)) { i ->
                                homeBackdrop = HomeBackdrop.entries[i]
                                prefs.homeBackdrop = homeBackdrop.id
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Side dock", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                                Text("Screen shortcuts down the right edge", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            SegmentedControl(listOf("Hidden", "Shown"), if (homeDock) 1 else 0) { i ->
                                homeDock = i == 1
                                prefs.homeDock = homeDock
                            }
                        }
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingTitle(Icons.Rounded.DirectionsCar, "Driving side",
                            "Puts the driver's climate zone on the correct side", Modifier.weight(1f))
                        SegmentedControl(listOf("Left-hand", "Right-hand"), if (driverOnRight) 1 else 0) { i ->
                            driverOnRight = i == 1
                            prefs.driverOnRight = driverOnRight
                        }
                    }
                }
            } },
        ) { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OptionRow("Sideload apps", "Wi-Fi upload, URL or local APK — no PC", Icons.Rounded.InstallMobile) {
                    nav.navigate(Routes.SIDELOAD)
                }
                OptionRow("Updates", "Check for and install OTA updates", Icons.Rounded.SystemUpdate) {
                    nav.navigate(Routes.UPDATES)
                }
                OptionRow("Service probe", "Inspect the car service on this firmware", Icons.Rounded.BugReport) {
                    nav.navigate(Routes.PROBE)
                }
                Spacer(Modifier.weight(1f))
                Panel(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Image(painterResource(R.drawable.logo_fin), contentDescription = null, modifier = Modifier.height(52.dp))
                        Column {
                            Text("Shark Hub", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                            Text("v$version · custom head-unit dashboard for the BYD Shark 6",
                                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingTitle(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, size = 44.dp)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A style choice: name + blurb, with a tiny slab-vs-glass glyph drawn in the current theme. */
@Composable
private fun StyleCard(spec: StyleSpec, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(spec.panelRadius * 0.75f)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.10f) else cs.surfaceVariant)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // glyph: three little panels in this style's own radius
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(width = 44.dp, height = 12.dp)
                        .clip(RoundedCornerShape(spec.panelRadius * 0.3f))
                        .background(if (i == 0 && selected) cs.primary else cs.onSurface.copy(alpha = 0.18f))
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(spec.style.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                if (selected) Icon(Icons.Rounded.Check, "selected", tint = cs.primary, modifier = Modifier.size(18.dp))
            }
            Text(spec.style.blurb, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** A miniature of the theme's own dashboard — its colours, not the current theme's. */
@Composable
private fun ThemeCard(spec: ThemeSpec, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(RoundedCornerShape(12.dp))
                .background(spec.bg)
                .padding(8.dp)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(spec.surfaceVariant)
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(Modifier.size(width = 28.dp, height = 6.dp).clip(CircleShape).background(spec.ink.copy(alpha = 0.8f)))
                Box(Modifier.size(width = 44.dp, height = 4.dp).clip(CircleShape).background(spec.dim.copy(alpha = 0.6f)))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(spec.accent, spec.warn, spec.hot, spec.cool).forEach { c: Color ->
                        Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.name, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1,
                modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Rounded.Check, "selected", tint = cs.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Panel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconBadge(icon, cs.primary, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
        }
    }
}
