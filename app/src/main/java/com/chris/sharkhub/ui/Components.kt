package com.chris.sharkhub.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.chris.sharkhub.ui.theme.LocalStyle

/** Corner shape for panels / cards under the current style. */
val panelShape: RoundedCornerShape
    @Composable get() = RoundedCornerShape(LocalStyle.current.panelRadius)

/** Corner shape for buttons, chips and widgets under the current style. */
val controlShape: RoundedCornerShape
    @Composable get() = RoundedCornerShape(LocalStyle.current.controlRadius)

/** True when the screen is taller than wide — the Shark 6's display rotates. */
@Composable
fun isPortrait(): Boolean {
    val c = LocalConfiguration.current
    return c.screenHeightDp > c.screenWidthDp
}

/**
 * Two panes side by side in landscape and stacked in portrait. Each pane receives a modifier that
 * carries its weight along the split, so callers just apply it to their outermost element.
 */
@Composable
fun Split(
    modifier: Modifier,
    firstWeight: Float = 1f,
    secondWeight: Float = 1f,
    spacing: Dp = 16.dp,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (isPortrait()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
            first(Modifier.weight(firstWeight).fillMaxWidth())
            second(Modifier.weight(secondWeight).fillMaxWidth())
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            first(Modifier.weight(firstWeight).fillMaxHeight())
            second(Modifier.weight(secondWeight).fillMaxHeight())
        }
    }
}

/**
 * The card everything sits on. Glass: a faint top-lit gradient plus a hairline border, so panels
 * read as separate layers without heavy fills. Auto: a flat slab one step above the background with
 * a 1px lit top edge, like a moulded dash panel catching light.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Override the style's panel shape — e.g. square off the edge that meets the screen. */
    shape: androidx.compose.ui.graphics.Shape = panelShape,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val style = LocalStyle.current
    var base = modifier.clip(shape)
    base = when {
        // frosted: see-through surface with a highlight along the top, like glass catching light
        style.translucent -> base
            .background(cs.surface.copy(alpha = 0.52f))
            .background(Brush.verticalGradient(listOf(cs.onSurface.copy(alpha = 0.07f), Color.Transparent), endY = 90f))
        style.panelGradient -> base.background(Brush.verticalGradient(listOf(cs.surfaceVariant, cs.surface)))
        else -> base.background(cs.surfaceContainerHigh)
    }
    if (style.hairline) base = base.border(1.dp, if (style.translucent) cs.onSurface.copy(alpha = 0.14f) else cs.outlineVariant, shape)
    if (!style.panelGradient && !style.translucent) {
        // lit top edge
        val edge = cs.onSurface.copy(alpha = 0.10f)
        base = base.drawBehind {
            drawLine(edge, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1.5f)
        }
    }
    Box(if (onClick != null) base.clickable { onClick() } else base) { content() }
}

/** Rounded-square icon chip in an accent tint — the leading mark on tiles and option rows. */
@Composable
fun IconBadge(icon: ImageVector, accent: Color, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val shape = RoundedCornerShape(if (LocalStyle.current.litEdge) size * 0.2f else size * 0.32f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.28f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.52f))
    }
}

/**
 * A dashboard tile: icon badge (plus an optional live [value]) over a title and caption, with a
 * soft accent bloom behind the badge. Sized for finger taps in a moving car.
 */
@Composable
fun Tile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    value: String? = null,
    caption: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val litEdge = LocalStyle.current.litEdge
    Panel(modifier = modifier, onClick = onClick) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    if (litEdge) {
                        // accent bar down the left edge, like a lit key on a dash
                        drawRect(accent, Offset(0f, 0f), androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
                        drawRect(
                            Brush.horizontalGradient(listOf(accent.copy(alpha = 0.14f), Color.Transparent),
                                endX = size.width * 0.6f),
                        )
                    } else {
                        drawRect(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                                center = Offset(size.width * 0.12f, size.height * 0.1f),
                                radius = size.maxDimension * 0.8f,
                            )
                        )
                    }
                }
        ) {
            // Stacking the badge over two lines of text needs ~150 dp; the Menu's 4-row grid gives
            // less than that on the unit, and its larger font scale then pushed the text right out of
            // the tile (icons only, no titles). Below that the tile goes horizontal instead.
            val compact = maxHeight < 150.dp
            val labels: @Composable ColumnScope.() -> Unit = {
                Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (caption != null) {
                    Text(caption, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (compact) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconBadge(icon, accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = labels)
                    if (value != null) {
                        Text(value, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        IconBadge(icon, accent)
                        Spacer(Modifier.weight(1f))
                        if (value != null) {
                            Text(value, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = labels)
                }
            }
        }
    }
}

/** Circular icon button with a hairline ring (back, +/−, fan steps). */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(cs.surfaceVariant)
            .border(1.dp, cs.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/** Standard screen header: back button, title + subtitle, and an optional trailing slot. */
@Composable
fun ScreenHeader(
    title: String,
    nav: NavController,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 24.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { nav.popBackStack() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/** Small uppercase, letter-spaced label that heads a section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

/** Pill with a status dot: "Connected", "Preview", "Scanning"… */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** A tappable pill with an icon — header actions, edit-mode controls. */
@Composable
fun ActionChip(
    label: String,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/**
 * A car-style toggle: quiet when off, lit with [activeColor] when on, with a small indicator bar
 * like the LED on a physical button. Used for A/C, AUTO, defrost and friends.
 */
@Composable
fun ControlButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val style = LocalStyle.current
    val shape = controlShape
    val bg by animateColorAsState(
        if (active) activeColor.copy(alpha = if (style.litEdge) 0.10f else 0.15f) else cs.surfaceVariant, label = "bg")
    val edge by animateColorAsState(if (active) activeColor.copy(alpha = 0.55f) else cs.outlineVariant, label = "edge")
    val fg by animateColorAsState(if (active) activeColor else cs.onSurface, label = "fg")
    val led by animateColorAsState(if (active) activeColor else cs.onSurface.copy(alpha = 0.14f), label = "led")
    val glow by animateFloatAsState(if (active) 1f else 0f, label = "glow")
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(bg)
            .then(if (style.hairline || !style.litEdge) Modifier.border(1.dp, edge, shape) else Modifier)
            .drawBehind {
                if (style.litEdge && glow > 0f) {
                    // lit bottom edge + soft bloom, like an illuminated physical key
                    val h = 3.dp.toPx()
                    drawRect(activeColor.copy(alpha = glow), Offset(0f, size.height - h),
                        androidx.compose.ui.geometry.Size(size.width, h))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, activeColor.copy(alpha = 0.22f * glow)),
                        startY = size.height * 0.4f, endY = size.height))
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        if (!style.litEdge) Box(Modifier.width(22.dp).height(3.dp).clip(CircleShape).background(led))
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** [max] dots with the first [level] lit — seat heat / vent levels. */
@Composable
fun LevelDots(level: Int, max: Int, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(max) { i ->
            Box(
                Modifier
                    .size(width = 14.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(if (i < level) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
            )
        }
    }
}

/** A row of mutually exclusive choices in one pill; the selected one is filled with the accent. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(CircleShape)
            .background(cs.surfaceVariant)
            .border(1.dp, cs.outlineVariant, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val bg by animateColorAsState(if (selected) cs.primary else Color.Transparent, label = "seg")
            val fg by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariant, label = "segText")
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}

/** Label over a value — the small stat blocks on Home and Climate. */
@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
    }
}
