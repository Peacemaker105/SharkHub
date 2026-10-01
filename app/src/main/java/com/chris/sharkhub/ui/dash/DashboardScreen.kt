package com.chris.sharkhub.ui.dash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.chris.sharkhub.R
import com.chris.sharkhub.Routes
import com.chris.sharkhub.car.CarManager
import com.chris.sharkhub.car.ClimateState
import com.chris.sharkhub.car.CommandResult
import com.chris.sharkhub.car.Telemetry
import com.chris.sharkhub.car.VehicleControls
import com.chris.sharkhub.data.FuelEntry
import com.chris.sharkhub.data.FuelLog
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.ui.ActionChip
import com.chris.sharkhub.ui.Panel
import com.chris.sharkhub.ui.RoundIconButton
import com.chris.sharkhub.ui.SectionLabel
import com.chris.sharkhub.ui.StatusChip
import com.chris.sharkhub.ui.controlShape
import com.chris.sharkhub.ui.isPortrait
import androidx.compose.animation.Crossfade
import com.chris.sharkhub.ui.overview.CarArtState
import com.chris.sharkhub.ui.overview.CarPhotoScene
import com.chris.sharkhub.ui.overview.CarScene
import com.chris.sharkhub.ui.overview.EmptyStage
import com.chris.sharkhub.ui.overview.SceneCamera
import com.chris.sharkhub.ui.overview.SceneSettings
import kotlinx.coroutines.delay
import com.chris.sharkhub.ui.overview.SceneSettingsCog
import com.chris.sharkhub.ui.overview.SceneSettingsSheet
import com.chris.sharkhub.ui.overview.SceneState
import com.chris.sharkhub.ui.overview.save
import com.chris.sharkhub.ui.overview.TimeOfDay
import com.chris.sharkhub.ui.overview.art
import com.chris.sharkhub.ui.overview.rememberCarArt
import com.chris.sharkhub.ui.overview.rememberTimeOfDay
import com.chris.sharkhub.ui.theme.LocalStyle
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The boot screen: swipeable pages of widgets you arrange yourself. A page is either a bento of
 * sized cards, the truck on its highway with a card row, or the classic grid. The pencil enters
 * edit mode — long-press and drag a widget to move it, × removes it, + adds one from the catalogue,
 * and pages can be added or deleted. The layout persists in Prefs; "Reset" restores the preset
 * chosen in Options → Home screen.
 */
@Composable
fun DashboardScreen(nav: NavController, car: CarManager) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val tele by remember(car) { car.telemetry() }.collectAsStateWithLifecycle(Telemetry())
    val climate by car.climate.collectAsStateWithLifecycle()
    val vehicle by car.vehicle.collectAsStateWithLifecycle()
    val discovery by car.discovery.collectAsStateWithLifecycle()
    var layout by remember { mutableStateOf(DashLayout.load(prefs)) }
    var last by remember { mutableStateOf<CommandResult?>(null) }
    LaunchedEffect(car) { car.commandResults.collect { last = it } }
    val fuelLog = remember { FuelLog(prefs) }
    // every gauge reading goes to the log, which spots a fill-up by the gauge jumping
    LaunchedEffect(tele.fuelPercent) { fuelLog.onGauge(tele.fuelPercent) }
    val backdrop = remember { HomeBackdrop.byId(prefs.homeBackdrop) }
    val art = rememberCarArt()
    val sceneCard = rememberHomeBitmap("card") { loadSceneCard(it) }
    val backdropImage = rememberHomeBitmap(backdrop) { loadBackdrop(it, backdrop) }
    var settings by remember { mutableStateOf(SceneSettings.from(prefs)) }
    val timeOfDay = rememberTimeOfDay(settings.lighting)
    // only a set that says its shell is a neutral render takes the chosen paint
    val paint = if (art.art?.paintTintable == true) Color(settings.paint) else null
    // the stage's own pinch memory, apart from the Vehicle page's
    var camera by remember { mutableStateOf(prefs.sceneCamera(Prefs.SCENE_STAGE).let { (z, x, y) -> SceneCamera(z, Offset(x, y)) }) }
    LaunchedEffect(camera) { delay(400); prefs.setSceneCamera(Prefs.SCENE_STAGE, camera.zoom, camera.pan.x, camera.pan.y) }
    DashboardContent(
        nav = nav, car = car, tele = tele, climate = climate, vehicle = vehicle,
        connected = discovery?.backend != null, layout = layout, lastResult = last,
        onLayout = { layout = it; DashLayout.save(prefs, it) },
        onReset = { layout = DashLayout.default(HomePreset.byId(prefs.homeLayout)); DashLayout.reset(prefs) },
        art = art, sceneCard = sceneCard, backdrop = if (backdrop == HomeBackdrop.WAVES || backdropImage != null) backdrop else HomeBackdrop.NONE,
        backdropImage = backdropImage, dock = prefs.homeDock, driverOnRight = prefs.driverOnRight,
        fuelLog = fuelLog, timeOfDay = timeOfDay, sceneMotion = settings.motion, paint = paint,
        settings = settings, onSettings = { settings = it; prefs.save(it) },
        camera = camera, onCamera = { camera = it },
    )
}

/** Stateless body — what the screenshot tests render. */
@Composable
fun DashboardContent(
    nav: NavController,
    car: CarManager,
    tele: Telemetry,
    climate: ClimateState,
    vehicle: Map<String, Int>,
    connected: Boolean,
    layout: DashLayout,
    lastResult: CommandResult?,
    onLayout: (DashLayout) -> Unit,
    onReset: () -> Unit,
    initialPage: Int = 0,
    /** The rendered truck for scene pages: loading shows an empty stage, missing draws the wireframe. */
    art: CarArtState = CarArtState.Missing,
    sceneCard: ImageBitmap? = null,
    backdrop: HomeBackdrop = HomeBackdrop.NONE,
    backdropImage: ImageBitmap? = null,
    dock: Boolean = true,
    driverOnRight: Boolean = true,
    fuelLog: FuelLog? = null,
    /** The light the stage's truck scene is drawn in. */
    timeOfDay: TimeOfDay = TimeOfDay.DAY,
    /** Whether the stage's scene moves with road speed (Options → Scene motion). */
    sceneMotion: Boolean = true,
    /** The shell's paint for a tintable set (gated on the meta by the screen); null = as rendered. */
    paint: Color? = null,
    /** The stage page's scene sheet (same control as the Overview's); the screen mirrors it to Prefs. */
    settings: SceneSettings = SceneSettings(),
    onSettings: (SceneSettings) -> Unit = {},
    /** Where the stage's scene is pinched to; [onCamera] null leaves it fixed. */
    camera: SceneCamera = SceneCamera(),
    onCamera: ((SceneCamera) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmText by remember { mutableStateOf("") }
    // One page past the last is the Vehicle page: swiping on to it hands over to the Overview and
    // the pager steps back, so returning lands on the stage. Not while editing — page counts change then.
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, layout.pages.size - 1)) { layout.pages.size + if (editing) 0 else 1 }
    val coScope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage, editing) {
        if (!editing && pager.settledPage == layout.pages.size) {
            pager.scrollToPage(layout.pages.size - 1)
            nav.navigate(Routes.OVERVIEW)
        }
    }
    // Deleting the last page leaves the pager pointing past the end.
    LaunchedEffect(layout.pages.size) {
        if (pager.currentPage >= layout.pages.size) pager.animateScrollToPage(layout.pages.size - 1)
    }
    val scope = DashScope(car, nav, ctx, tele, climate, vehicle, connected, editing, { text, action ->
        confirmText = text; confirm = action
    }, sceneCard, driverOnRight, fuelLog, art, timeOfDay, paint)
    fun updatePage(index: Int, edit: (DashPage) -> DashPage) =
        onLayout(layout.copy(pages = layout.pages.mapIndexed { i, p -> if (i == index) edit(p) else p }))
    val page = layout.pages.getOrNull(pager.currentPage)
    // Over a picture every card goes frosted, whatever the style says.
    val style = LocalStyle.current
    val hasBackdrop = backdrop == HomeBackdrop.WAVES || (backdrop != HomeBackdrop.NONE && backdropImage != null)
    val pageStyle = if (hasBackdrop) style.copy(translucent = true, hairline = true, panelGradient = false) else style

    Box(Modifier.fillMaxSize()) {
        when (backdrop) {
            HomeBackdrop.NONE -> {}
            HomeBackdrop.WAVES -> WavesBackdrop()
            else -> Backdrop(backdropImage)
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // ---- top bar: logo, page name + dots, last command, status, edit / menu ----
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = if (dock) 8.dp else 20.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(painterResource(R.drawable.logo_fin), null, Modifier.height(36.dp))
                    Text("Shark Hub", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, maxLines = 1)
                    Text("By Muzz", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp))
                    PageDots(layout.pages.size, pager.currentPage, vehicle = !editing)
                    Spacer(Modifier.weight(1f))
                    if (lastResult != null) {
                        val ok = lastResult.result.isSuccess
                        Text((if (ok) "✓ " else "✗ ") + lastResult.label, style = MaterialTheme.typography.bodyMedium,
                            color = if (ok) cs.primary else cs.error, maxLines = 1)
                    }
                    StatusChip(if (connected) "Car" else "Offline", if (connected) cs.primary else cs.secondary)
                    if (editing) {
                        RoundIconButton(Icons.Rounded.Add, "Add widget", size = 44.dp, tint = cs.primary) { picker = true }
                        RoundIconButton(Icons.Rounded.RestartAlt, "Reset layout", size = 44.dp) { onReset() }
                        RoundIconButton(Icons.Rounded.Check, "Done", size = 44.dp, tint = cs.primary) { editing = false }
                    } else {
                        RoundIconButton(Icons.Rounded.Edit, "Edit layout", size = 44.dp) { editing = true }
                        if (!dock) RoundIconButton(Icons.Rounded.Apps, "Menu", size = 44.dp, tint = cs.primary) { nav.navigate(Routes.MENU) }
                    }
                }

                // ---- pages ----
                CompositionLocalProvider(LocalStyle provides pageStyle) {
                    HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { pageIndex ->
                        if (pageIndex >= layout.pages.size) {
                            // the hand-over page: seen only for the moment the swipe takes to settle
                            VehicleHandoff(Modifier.fillMaxSize().padding(end = if (dock) 4.dp else 20.dp, bottom = 4.dp))
                            return@HorizontalPager
                        }
                        val p = layout.pages[pageIndex]
                        val onReorder: (Int, Int) -> Unit = { from, to ->
                            updatePage(pageIndex) { pg -> pg.copy(widgets = pg.widgets.toMutableList().also { ws -> ws.add(to, ws.removeAt(from)) }) }
                        }
                        val onRemove: (Int) -> Unit = { idx -> updatePage(pageIndex) { pg -> pg.copy(widgets = pg.widgets.filterIndexed { j, _ -> j != idx }) } }
                        when (p.kind) {
                            PageKind.STAGE -> StagePage(p, scope, art, timeOfDay, sceneMotion, paint, settings, onSettings, camera, onCamera,
                                editing, onReorder, onRemove, Modifier.fillMaxSize().padding(end = if (dock) 4.dp else 20.dp, bottom = 4.dp))
                            else -> WidgetGrid(p.widgets, p.kind, scope, editing, onReorder, onRemove,
                                Modifier.fillMaxSize().padding(start = 20.dp, end = if (dock) 8.dp else 20.dp))
                        }
                    }
                }

                // ---- edit-mode page controls ----
                if (editing) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel("Long-press and drag to move · × removes")
                        Spacer(Modifier.weight(1f))
                        ActionChip("Add cards page", Icons.Rounded.Add) {
                            onLayout(layout.copy(pages = layout.pages + DashPage("Page ${layout.pages.size + 1}", emptyList(), PageKind.BENTO)))
                        }
                        ActionChip("Add scene page", Icons.Rounded.Add) {
                            onLayout(layout.copy(pages = layout.pages + DashPage("Page ${layout.pages.size + 1}", emptyList(), PageKind.STAGE)))
                        }
                        if (layout.pages.size > 1) ActionChip("Delete this page", Icons.Rounded.Delete, cs.error) {
                            val i = pager.currentPage
                            onLayout(layout.copy(pages = layout.pages.filterIndexed { j, _ -> j != i }))
                        }
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (dock) Dock(nav, onHome = { coScope.launch { pager.animateScrollToPage(0) } }, Modifier.padding(end = 10.dp, top = 12.dp, bottom = 24.dp))
        }
    }

    if (picker) {
        WidgetPicker(stage = page?.kind == PageKind.STAGE, onDismiss = { picker = false }) { w ->
            updatePage(pager.currentPage) { p -> p.copy(widgets = p.widgets + w) }
            picker = false
        }
    }
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Send to the car?") },
            text = { Text(confirmText) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    // The gauge gave a fill-up away: ask for the litres, but only while stopped — never on the move.
    if (fuelLog != null) {
        val refill by fuelLog.refill.collectAsStateWithLifecycle()
        val entries by fuelLog.entries.collectAsStateWithLifecycle()
        val autoAsk by fuelLog.autoAsk.collectAsStateWithLifecycle()
        val r = refill
        if (r != null && (tele.speedKph ?: 0.0) < 3.0) {
            FuelEntryDialog(tele.odometerKm, entries.lastOrNull(), r, autoAsk, { fuelLog.setAutoAsk(it, tele.fuelPercent) },
                onDismiss = { fuelLog.clearRefill() }, onUndoLast = { fuelLog.removeLast() }) { litres, odo ->
                fuelLog.add(FuelEntry(System.currentTimeMillis(), odo, litres, tele.fuelPercent))
            }
        }
    }
}

private const val TAU = (2 * Math.PI).toFloat()

/**
 * Flowing lines in two shades of the theme, gathering toward the bottom, with a soft accent glow in
 * two corners — a backdrop that isn't a flat colour without being a picture. Drifts slowly when the
 * style has ambient motion; still in Glass.
 */
@Composable
private fun WavesBackdrop() {
    val cs = MaterialTheme.colorScheme
    val ambient = LocalStyle.current.ambient
    val drift = if (ambient) rememberInfiniteTransition(label = "waves")
        .animateFloat(0f, TAU, infiniteRepeatable(tween(70_000, easing = LinearEasing)), label = "drift").value else 0f
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.radialGradient(listOf(cs.primary.copy(alpha = 0.14f), Color.Transparent), center = Offset(w * 0.86f, h * 0.08f), radius = w * 0.5f))
        drawRect(Brush.radialGradient(listOf(cs.tertiary.copy(alpha = 0.10f), Color.Transparent), center = Offset(w * 0.08f, h * 0.98f), radius = w * 0.48f))
        val n = 12
        val path = Path()
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val y0 = h * (0.30f + 0.66f * f * f)                    // gather toward the bottom
            val amp = h * (0.045f + 0.05f * (1f - f))
            val k1 = TAU * (1.1f + 0.25f * (i % 3)) / w
            val k2 = TAU * (2.4f + 0.6f * (i % 2)) / w
            val phase = i * 0.8f + drift * (if (i % 2 == 0) 1f else -0.6f)
            path.reset()
            val steps = 96
            for (j in 0..steps) {
                val x = w * j / steps
                val y = y0 + amp * sin(k1 * x + phase) + amp * 0.4f * sin(k2 * x - phase * 0.7f + i)
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val (color, alpha) = when (i % 3) {
                0 -> cs.primary to 0.09f + 0.2f * (1f - f)
                1 -> cs.tertiary to 0.07f + 0.16f * (1f - f)
                else -> cs.onSurface to 0.04f + 0.07f * (1f - f)
            }
            drawPath(path, color.copy(alpha = alpha), style = Stroke(width = (1.3f + 1.1f * (1f - f)).dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** The picture behind the pages, dimmed so the cards stay legible; already soft from its decode size. */
@Composable
private fun Backdrop(image: ImageBitmap?) {
    if (image == null) return
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.85f, filterQuality = FilterQuality.Low)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(cs.background.copy(alpha = 0.6f), cs.background.copy(alpha = 0.28f), cs.background.copy(alpha = 0.62f)))))
    }
}

/** The rail down the right: the places you go, one tap from any page. */
@Composable
private fun Dock(nav: NavController, onHome: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    data class Item(val icon: ImageVector?, val label: String, val go: () -> Unit)
    val items = listOf(
        Item(null, "Home", onHome),
        Item(Icons.Rounded.Apps, "Menu") { nav.navigate(Routes.MENU) },
        Item(Icons.Rounded.Speed, "Vehicle") { nav.navigate(Routes.OVERVIEW) },
        Item(Icons.Rounded.DonutLarge, "Gauges") { nav.navigate(Routes.GAUGES) },
        Item(Icons.Rounded.Thermostat, "Climate") { nav.navigate(Routes.CLIMATE) },
        Item(Icons.Rounded.Security, "Sentry") { nav.navigate(Routes.SENTRY) },
        Item(Icons.Rounded.Settings, "Options") { nav.navigate(Routes.OPTIONS) },
    )
    Panel(modifier.width(64.dp).fillMaxHeight(), shape = CircleShape) {
        Column(Modifier.fillMaxSize().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly) {
            items.forEach { it ->
                Box(Modifier.size(46.dp).clip(CircleShape).clickable(onClick = it.go), contentAlignment = Alignment.Center) {
                    if (it.icon == null) Image(painterResource(R.drawable.logo_fin), it.label, Modifier.height(26.dp))
                    else Icon(it.icon, it.label, tint = cs.onSurfaceVariant, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

/** One dot per page; with [vehicle] a hollow one after them for the Vehicle page a last swipe leads to. */
@Composable
private fun PageDots(count: Int, current: Int, vehicle: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val w by animateFloatAsState(if (i == current) 22f else 8f, label = "dot")
            Box(Modifier.width(w.dp).height(8.dp).clip(CircleShape)
                .background(if (i == current) cs.primary else cs.onSurface.copy(alpha = 0.2f)))
        }
        if (vehicle) Box(Modifier.size(8.dp).clip(CircleShape).border(1.dp, cs.onSurface.copy(alpha = 0.35f), CircleShape))
    }
}

/** What the pager shows past its last page while the swipe settles and the Overview opens. */
@Composable
private fun VehicleHandoff(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val r = LocalStyle.current.panelRadius
    Panel(modifier, shape = RoundedCornerShape(topEnd = r, bottomEnd = r)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Speed, null, tint = cs.primary, modifier = Modifier.size(36.dp))
                Text("Vehicle", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            }
        }
    }
}

// ---- the stage page ----

/** The truck fills the page; clock top-left, readings top-right, the page's widgets as a row of cards. */
@Composable
private fun StagePage(
    page: DashPage, scope: DashScope, art: CarArtState, timeOfDay: TimeOfDay, sceneMotion: Boolean, paint: Color?,
    settings: SceneSettings, onSettings: (SceneSettings) -> Unit, camera: SceneCamera, onCamera: ((SceneCamera) -> Unit)?, editing: Boolean,
    onReorder: (Int, Int) -> Unit, onRemove: (Int) -> Unit, modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val r = LocalStyle.current.panelRadius
    val tele = scope.tele
    var sheet by remember { mutableStateOf(false) }
    fun mode(id: String) = VehicleControls.selector(id)?.let { sel -> sel.optionFor(scope.vehicle[sel.id])?.label }
    val modeLabel = listOfNotNull(mode("driveMode"), mode("roadSurface")).joinToString(" · ").ifEmpty { null }
    // a solid shell on the home stage: the innards stay hidden, so nothing behind the truck shows through it
    val state = SceneState(tele, xray = 0f, home = true, modeLabel = modeLabel, vehicle = scope.vehicle)
    // squared left edge: the scene runs to the edge of the screen
    Panel(modifier, shape = RoundedCornerShape(topEnd = r, bottomEnd = r)) {
        Box(Modifier.fillMaxSize()) {
            // an empty stage while the art decodes (never the wireframe, which would flash), the truck fading in after
            Crossfade(art, animationSpec = tween(250), label = "stage") { st ->
                when (st) {
                    is CarArtState.Ready -> CarPhotoScene(state, st.art, Modifier.fillMaxSize(), timeOfDay = timeOfDay, sceneMotion = sceneMotion, paint = paint,
                        camera = camera, onCamera = onCamera)
                    CarArtState.Loading -> EmptyStage(Modifier.fillMaxSize())
                    CarArtState.Missing -> CarScene(state, Modifier.fillMaxSize().padding(6.dp))
                }
            }
            StageClock(scope, Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 12.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("Battery", tele.socPercent?.let { "${it.toInt()}%" } ?: "—", cs.primary, tele.socPercent?.toFloat()?.div(100f),
                    tele.evRangeKm?.let { "${it.toInt()} km EV" })
                StatPill("Fuel", tele.fuelPercent?.let { "${it.toInt()}%" } ?: "—", cs.secondary, tele.fuelPercent?.toFloat()?.div(100f),
                    tele.fuelRangeKm?.let { "${it.toInt()} km" })
                StatPill("Outside", tele.outsideTempC?.let { "%.0f°".format(it) } ?: "—", cs.tertiary, null, null)
            }
            WidgetGrid(page.widgets, PageKind.STAGE, scope, editing, onReorder, onRemove,
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(158.dp).padding(start = 18.dp, end = 16.dp, bottom = 14.dp))
            // scene settings: the cog under the reading pills (the card row owns the bottom), its sheet over that corner
            SceneSettingsCog({ sheet = true }, Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 70.dp))
            if (sheet) SceneSettingsSheet(settings, onSettings, { sheet = false }, Alignment.TopEnd, Modifier.padding(end = 12.dp, top = 64.dp))
        }
    }
}

@Composable
private fun StageClock(s: DashScope, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val now by produceState(LocalDateTime.now()) {
        while (true) { value = LocalDateTime.now(); kotlinx.coroutines.delay(1000L - System.currentTimeMillis() % 1000L) }
    }
    val fmt = remember(s.ctx) {
        DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(s.ctx)) "HH:mm" else "h:mm")
    }
    Column(modifier) {
        Text(now.format(fmt), style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 76.sp), color = cs.onSurface, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, maxLines = 1)
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (s.connected) cs.primary else cs.secondary))
            Text(if (s.connected) "Car connected" else "Offline", style = MaterialTheme.typography.titleSmall,
                color = if (s.connected) cs.primary else cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A see-through reading pill for the stage's top-right corner. */
@Composable
private fun StatPill(label: String, value: String, color: Color, fraction: Float?, sub: String?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(controlShape).background(cs.surface.copy(alpha = 0.55f)).border(1.dp, cs.onSurface.copy(alpha = 0.14f), controlShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (fraction != null || label != "Outside") ArcGauge(fraction, color, Modifier.size(30.dp), stroke = 3.5f)
        Column {
            SectionLabel(label)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1)
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

// ---- packing ----

/** Classic grid: left-to-right cell placement; a wide widget takes two cells and wraps if it won't fit the row. */
private fun placeCells(widgets: List<DashWidget>, cols: Int, cellW: Int, cellH: Int, gap: Int): List<Rect> {
    val out = ArrayList<Rect>(widgets.size)
    var col = 0
    var row = 0
    for (w in widgets) {
        val span = if (w.wide) 2 else 1
        if (col + span > cols) { col = 0; row++ }
        val x = (col * (cellW + gap)).toFloat()
        val y = (row * (cellH + gap)).toFloat()
        out += Rect(x, y, x + cellW * span + gap * (span - 1), y + cellH)
        col += span
        if (col >= cols) { col = 0; row++ }
    }
    return out
}

/**
 * Bento: first-fit on a [cols]-wide grid of [unitW]×[unitH] units. Each card takes the first spot,
 * scanning rows top-down and left-to-right, where its whole footprint is free — so the default page
 * packs hero / column / squares / smalls with no holes, and dragging reorders the list and repacks.
 */
private fun packBento(widgets: List<DashWidget>, cols: Int, unitW: Float, unitH: Float, gap: Float, span: (WidgetSize) -> Pair<Int, Int>): List<Rect> {
    val occ = ArrayList<BooleanArray>()
    fun row(r: Int): BooleanArray { while (occ.size <= r) occ += BooleanArray(cols); return occ[r] }
    fun free(r: Int, c: Int, w: Int, h: Int): Boolean {
        for (rr in r until r + h) for (cc in c until c + w) if (row(rr)[cc]) return false
        return true
    }
    val out = ArrayList<Rect>(widgets.size)
    for (wd in widgets) {
        val (w0, h) = span(wd.size)
        val w = w0.coerceAtMost(cols)
        var r = 0
        search@ while (true) {
            for (c in 0..cols - w) {
                if (free(r, c, w, h)) {
                    for (rr in r until r + h) for (cc in c until c + w) row(rr)[cc] = true
                    val x = c * (unitW + gap); val y = r * (unitH + gap)
                    out += Rect(x, y, x + w * unitW + (w - 1) * gap, y + h * unitH + (h - 1) * gap)
                    break@search
                }
            }
            r++
        }
    }
    return out
}

/** Stage row: equal cards across the width; the climate card gets half again. */
private fun packRow(widgets: List<DashWidget>, width: Int, height: Int, gap: Int): List<Rect> {
    if (widgets.isEmpty()) return emptyList()
    val weights = widgets.map { if (it.kind == WidgetKind.CLIMATE) 1.5f else 1f }
    val unit = (width - gap * (widgets.size - 1)) / weights.sum()
    var x = 0f
    return weights.map { wt -> val r = Rect(x, 0f, x + unit * wt, height.toFloat()); x += unit * wt + gap; r }
}

/**
 * The widgets of one page at their packed rectangles. In edit mode a long-press picks a widget up;
 * dragging it over another cell reorders live, the dragged cell floating above the rest.
 */
@Composable
private fun WidgetGrid(
    widgets: List<DashWidget>,
    kind: PageKind,
    scope: DashScope,
    editing: Boolean,
    onReorder: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val portrait = isPortrait()
    val cfg = LocalConfiguration.current
    var dragging by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val latestReorder by rememberUpdatedState(onReorder)

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val gapPx = with(density) { 12.dp.roundToPx() }
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val rects = remember(widgets, kind, widthPx, heightPx, gapPx, portrait) {
            when (kind) {
                PageKind.STAGE -> packRow(widgets, widthPx, heightPx, gapPx)
                PageKind.GRID -> {
                    val cols = if (portrait) 4 else 6
                    // Three rows fill a landscape page; portrait rows are squarer and the page scrolls.
                    val rowH = with(density) { (if (portrait) 150.dp else ((cfg.screenHeightDp - 150) / 3).coerceIn(120, 200).dp).roundToPx() }
                    placeCells(widgets, cols, (widthPx - gapPx * (cols - 1)) / cols, rowH, gapPx)
                }
                PageKind.BENTO -> if (portrait) {
                    // Half as many columns, each a little wider than landscape's, and rows about 1.4×
                    // as tall: a card keeps its column span and takes 0.75× its row span (a square card
                    // five rows, a small one two), so it comes out at about its landscape size and
                    // reflows its content rather than squashing it. The page scrolls.
                    val cols = 6
                    val unitW = (widthPx - gapPx * (cols - 1)) / cols.toFloat()
                    packBento(widgets, cols, unitW, unitW * 0.55f, gapPx.toFloat()) { s -> s.w.coerceAtMost(cols) to (s.h * 0.75f).roundToInt().coerceAtLeast(1) }
                } else {
                    val cols = 12
                    packBento(widgets, cols, (widthPx - gapPx * (cols - 1)) / cols.toFloat(), (heightPx - gapPx * 11) / 12f, gapPx.toFloat()) { s -> s.w to s.h }
                }
            }
        }
        val latestRects by rememberUpdatedState(rects)
        val totalH = if (rects.isEmpty()) heightPx else max(heightPx, rects.maxOf { it.bottom }.roundToInt())

        Box(Modifier.fillMaxSize().then(if (kind == PageKind.STAGE) Modifier else Modifier.verticalScroll(rememberScrollState()))) {
            Layout(
                modifier = Modifier.fillMaxWidth().height(with(density) { totalH.toDp() }),
                content = {
                    widgets.forEachIndexed { i, w ->
                        val isDragged = i == dragging
                        val scale by animateFloatAsState(if (isDragged) 1.04f else 1f, label = "scale")
                        Box(
                            Modifier
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    if (isDragged) { translationX = dragOffset.x; translationY = dragOffset.y }
                                    alpha = if (editing && !isDragged) 0.92f else 1f
                                }
                                .then(if (editing) Modifier.pointerInput(i, widgets.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = i; dragOffset = Offset.Zero },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount
                                            val cur = dragging
                                            val home = latestRects.getOrNull(cur) ?: return@detectDragGesturesAfterLongPress
                                            val p = home.center + dragOffset
                                            val over = latestRects.indexOfFirst { it.contains(p) }
                                            if (over >= 0 && over != cur) {
                                                // keep the pointer where it is relative to the new home cell
                                                dragOffset = p - latestRects[over].center
                                                latestReorder(cur, over)
                                                dragging = over
                                            }
                                        },
                                        onDragEnd = { dragging = -1; dragOffset = Offset.Zero },
                                        onDragCancel = { dragging = -1; dragOffset = Offset.Zero },
                                    )
                                } else Modifier)
                        ) {
                            val slot = when (kind) {
                                PageKind.STAGE -> WidgetSize.W
                                PageKind.GRID -> if (w.wide) WidgetSize.W else WidgetSize.M
                                PageKind.BENTO -> w.size
                            }
                            DashWidgetView(w, scope, Modifier.fillMaxSize(), size = slot)
                            if (editing) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).clip(CircleShape)
                                        .background(cs.error).clickable { onRemove(i) },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.Close, "Remove", tint = cs.onError, modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            ) { measurables, _ ->
                val placeables = measurables.mapIndexed { i, m ->
                    val r = rects[i]
                    m.measure(Constraints.fixed(r.width.roundToInt(), r.height.roundToInt()))
                }
                layout(widthPx, totalH) {
                    placeables.forEachIndexed { i, p -> p.place(rects[i].left.roundToInt(), rects[i].top.roundToInt()) }
                }
            }
            if (widgets.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(if (editing) "Tap + to add widgets" else "Empty page — tap the pencil to add widgets",
                        style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WidgetPicker(stage: Boolean, onDismiss: () -> Unit, onPick: (DashWidget) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (stage) "Add a card to the row" else "Add a widget") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                WidgetCatalog.groups().forEach { g ->
                    item { SectionLabel(g.title, Modifier.padding(top = 10.dp, bottom = 6.dp), color = cs.primary) }
                    items(g.entries.size) { i ->
                        val e = g.entries[i]
                        Row(
                            Modifier.fillMaxWidth().clip(controlShape)
                                .then(if (stage) Modifier.clickable { onPick(e.widget.copy(size = WidgetSize.W)) } else Modifier)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(e.title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.weight(1f))
                            // on a card page you choose the size as you add it
                            if (!stage) e.widget.kind.sizes.forEach { sz ->
                                Box(
                                    Modifier.clip(CircleShape).background(cs.primary.copy(alpha = 0.12f))
                                        .clickable { onPick(e.widget.copy(size = sz)) }.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) { Text(sz.label, style = MaterialTheme.typography.labelMedium, color = cs.primary) }
                            }
                        }
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
