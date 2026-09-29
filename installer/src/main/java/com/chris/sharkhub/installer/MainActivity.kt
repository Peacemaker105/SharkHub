package com.chris.sharkhub.installer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shark Hub's Deep Sea palette, as the car app and the Windows installer use it. */
object Sea {
    val Bg = Color(0xFF040A16)
    val Card = Color(0xFF0A1426)
    val Field = Color(0xFF07101F)
    val Variant = Color(0xFF111E36)
    val Hairline = Color(0xFF1C2B45)
    val Edge = Color(0xFF243653)
    val Ink = Color(0xFFEAF3FF)
    val Body = Color(0xFFC9D6EA)
    val Dim = Color(0xFF8DA1BF)
    val Accent = Color(0xFF2EB8FF)
    val OnAccent = Color(0xFF00121F)
    val Good = Color(0xFF3DDC97)
    val Warn = Color(0xFFFFB547)
    val Hot = Color(0xFFFF6B5E)
}

class MainActivity : ComponentActivity() {
    private val vm: InstallerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Sea.Accent, background = Sea.Bg, surface = Sea.Card)) {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::picked) }
                InstallerContent(
                    state = UiState(
                        ip = vm.ip, ipValid = vm.target() != null, apkName = vm.apkName, apkInfo = vm.apkInfo,
                        canInstall = vm.canInstall, busy = vm.busy, installing = vm.installing,
                        status = vm.status, progress = vm.progress, found = vm.found.toList(), log = vm.log.toList(),
                    ),
                    actions = object : Actions {
                        override fun ip(v: String) = vm.onIp(v)
                        override fun latest() = vm.latest()
                        override fun pick() = picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                        override fun find() = vm.find()
                        override fun choose(hit: Hit) = vm.choose(hit)
                        override fun install() = vm.install()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** An APK opened with this app from a file manager. */
    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(vm::picked)
    }
}

data class UiState(
    val ip: String,
    val ipValid: Boolean,
    val apkName: String?,
    val apkInfo: String,
    val canInstall: Boolean,
    val busy: Boolean,
    val installing: Boolean,
    val status: Pair<Mood, String>?,
    val progress: Float?,
    val found: List<Hit>,
    val log: List<String>,
)

interface Actions {
    fun ip(v: String)
    fun latest()
    fun pick()
    fun find()
    fun choose(hit: Hit)
    fun install()
}

/** The whole screen, stateless — what the snapshot tests render. */
@Composable
fun InstallerContent(state: UiState, actions: Actions) {
    Box(
        Modifier.fillMaxSize().background(Sea.Bg)
            .background(Brush.radialGradient(listOf(Sea.Accent.copy(alpha = 0.13f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(900f, 0f), radius = 900f))
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header()
            StepsCard()
            AppCard(state, actions)
            IpCard(state, actions)
            AccentButton(if (state.installing) "Installing…" else "Install on the car", Icons.Rounded.Upload,
                enabled = state.canInstall || state.installing) { if (!state.busy) actions.install() }
            StatusCard(state)
            Text("Installs straight from this phone to the car over its hotspot or Wi-Fi, using ADB.",
                color = Sea.Dim, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Image(painterResource(R.drawable.logo_fin), null, Modifier.size(width = 52.dp, height = 44.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Shark Hub", color = Sea.Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text("By Muzz", color = Sea.Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            Text("Installer for the BYD Shark 6 head unit", color = Sea.Dim, fontSize = 13.5.sp)
        }
    }
}

@Composable
private fun Card(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Sea.Card).border(1.dp, Sea.Hairline, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Sea.Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Sea.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

/** "Plain **bold** plain" → an AnnotatedString with the bold bits in full ink. */
private fun rich(s: String): AnnotatedString = buildAnnotatedString {
    s.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Sea.Ink)) { append(part) } else append(part)
    }
}

@Composable
private fun Step(n: Int, text: String, hint: String? = null) {
    Row {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF15304F)), contentAlignment = Alignment.Center) {
            Text("$n", color = Sea.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(top = 2.dp)) {
            Text(rich(text), color = Sea.Body, fontSize = 14.5.sp, lineHeight = 20.sp)
            if (hint != null) Text(hint, color = Sea.Dim, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun StepsCard() {
    Card("Put the car in ADB mode", Icons.Rounded.DirectionsCar) {
        Step(1, "With the truck switched on, **rotate the screen to portrait**. The hidden menu only shows in portrait.")
        Step(2, "Open **Settings → System** and tap the **Factory Reset** text about 10 times, until a developer screen opens.",
            "Tap the words themselves. If a reset question ever pops up, press Cancel.")
        Step(3, "On that screen, tap the **top button** to switch ADB on.")
        Step(4, "Turn on **this phone's hotspot** and join the car to it, or put both on the same Wi-Fi.",
            "Then press Find the car below, or type the car's IP from its Settings → Wi-Fi.")
        Step(5, "When you press Install, the car asks **Allow USB debugging?** Tick Always allow and tap OK.")
    }
}

@Composable
private fun AppCard(state: UiState, actions: Actions) {
    Card("Choose the app", Icons.Rounded.Inventory2) {
        // stacked: side by side, a phone's width truncates the labels
        GhostButton("Latest Shark Hub", Icons.Rounded.Download, !state.busy, Modifier.fillMaxWidth()) { actions.latest() }
        GhostButton("Choose an APK on this phone", Icons.Rounded.FolderOpen, !state.busy, Modifier.fillMaxWidth()) { actions.pick() }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sea.Field).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (state.apkName != null) Icons.Rounded.CheckCircle else Icons.Rounded.Inventory2, null,
                tint = if (state.apkName != null) Sea.Accent else Sea.Dim, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(state.apkName ?: "No app chosen yet", color = Sea.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.apkInfo, color = Sea.Dim, fontSize = 12.5.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IpCard(state: UiState, actions: Actions) {
    Card("The car's IP address", Icons.Rounded.Wifi) {
        OutlinedTextField(
            value = state.ip, onValueChange = actions::ip, enabled = !state.busy, singleLine = true,
            placeholder = { Text("e.g. 192.168.43.120", color = Color(0xFF4A5F80), fontSize = 18.sp) },
            textStyle = TextStyle(color = Sea.Ink, fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(13.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Sea.Accent, unfocusedBorderColor = Sea.Edge, disabledBorderColor = Sea.Edge,
                focusedContainerColor = Sea.Field, unfocusedContainerColor = Sea.Field, disabledContainerColor = Sea.Field,
                cursorColor = Sea.Accent, disabledTextColor = Sea.Dim,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        val bad = state.ip.isNotBlank() && !state.ipValid
        Text(
            if (bad) "That doesn't look like an IP address. It's four numbers with dots, like 192.168.43.120."
            else "Port 5555 is used unless you add one, like 10.0.0.5:5555.",
            color = if (bad) Sea.Hot else Sea.Dim, fontSize = 12.5.sp,
        )
        GhostButton("Find the car", Icons.Rounded.Search, !state.busy, Modifier.fillMaxWidth()) { actions.find() }
        if (state.found.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.found.forEach { hit ->
                    Box(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(if (hit.ip == state.ip) Color(0xFF1A4570) else Color(0xFF12304F))
                            .border(1.dp, Sea.Accent, RoundedCornerShape(18.dp)).clickable(enabled = !state.busy) { actions.choose(hit) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(if (hit.adb) hit.ip else hit.ip + "  ?", color = Sea.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun GhostButton(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(13.dp)).background(Sea.Variant).border(BorderStroke(1.dp, Sea.Edge), RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Sea.Accent.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Sea.Ink.copy(alpha = if (enabled) 1f else 0.4f), fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1)
    }
}

@Composable
private fun AccentButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(15.dp)).background(Sea.Accent.copy(alpha = if (enabled) 1f else 0.32f))
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Sea.OnAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Sea.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun StatusCard(state: UiState) {
    val status = state.status ?: return
    var details by remember { mutableStateOf(false) }
    val (icon, tint) = when (status.first) {
        Mood.Busy -> Icons.Rounded.Sync to Sea.Accent
        Mood.Good -> Icons.Rounded.CheckCircle to Sea.Good
        Mood.Bad -> Icons.Rounded.ErrorOutline to Sea.Hot
        Mood.Info -> Icons.Rounded.Info to Sea.Warn
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sea.Card).border(1.dp, Sea.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(status.second, color = Sea.Ink, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
            Text(if (details) "Hide" else "Details", color = Sea.Dim, fontSize = 12.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { details = !details }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp))
        }
        val p = state.progress
        if (p != null) {
            if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Sea.Accent, trackColor = Color(0xFF16263F))
            else LinearProgressIndicator({ p.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Sea.Accent, trackColor = Color(0xFF16263F))
        }
        if (details) {
            Text(state.log.joinToString("\n"), color = Sea.Dim, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF050C19))
                    .verticalScroll(rememberScrollState()).padding(8.dp))
        }
    }
}
