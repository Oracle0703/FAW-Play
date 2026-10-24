package com.shilapi.xcertplay.ui.components

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.i18n.tr
import com.shilapi.xcertplay.ui.theme.Lavender
import com.shilapi.xcertplay.ui.theme.LavenderCode
import com.shilapi.xcertplay.ui.theme.Radius
import com.shilapi.xcertplay.ui.theme.Space
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------------
// Icons
// ---------------------------------------------------------------------------------------------

/** Material Symbols Rounded icon (decorative unless [description] is given). */
@Composable
fun LvIcon(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    description: String? = null,
) {
    Icon(
        imageVector = IconVectors.get(LocalResources.current, icon),
        contentDescription = description,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/**
 * Vector icons parsed once per process. painterResource parses the XML during the first
 * composition that shows an icon, on the main thread; [preload] does it on a worker instead.
 */
object IconVectors {
    private val cache = ConcurrentHashMap<Int, ImageVector>()

    fun get(resources: Resources, @DrawableRes id: Int): ImageVector =
        cache.getOrPut(id) { ImageVector.vectorResource(null, resources, id) }

    fun preload(resources: Resources, @DrawableRes ids: IntArray) {
        Thread({ ids.forEach { get(resources, it) } }, "icon-preload").start()
    }
}

// ---------------------------------------------------------------------------------------------
// Status pill and state glyph
// ---------------------------------------------------------------------------------------------

enum class PillKind { OK, INFO, WARN, ERR, IDLE }

/** 28dp status label. Always carries both an icon and text, never colour alone. */
@Composable
fun StatusPill(kind: PillKind, @DrawableRes icon: Int, text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ext = Lavender.extended
    val (container, content) = when (kind) {
        PillKind.OK -> ext.successContainer to ext.onSuccessContainer
        PillKind.INFO -> scheme.primaryContainer to scheme.onPrimaryContainer
        PillKind.WARN -> ext.warningContainer to ext.onWarningContainer
        PillKind.ERR -> scheme.errorContainer to scheme.onErrorContainer
        PillKind.IDLE -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(container)
            .padding(start = Space.s2, end = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LvIcon(icon, tint = content, size = 16.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

enum class GlyphState { IDLE, ACTIVE, DONE, FAILED, SKIPPED }

/** Spinner while running, check when done, cross when failed, ring when idle or skipped. */
@Composable
fun StateGlyph(state: GlyphState, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        GlyphState.ACTIVE -> {
            val track = scheme.secondaryContainer
            // The track is static; the arc spins on the RenderThread, so it keeps turning while
            // the main thread composes a burst of handshake rows. Geometry matches avd_glyph_spin.
            Box(modifier.size(size)) {
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(
                        track,
                        radius = this.size.minDimension * (9.4f / 24f),
                        style = Stroke(this.size.minDimension * 0.14f),
                    )
                }
                RenderThreadLoop(listOf(LoopLayer(R.drawable.avd_glyph_spin, scheme.primary)), Modifier.matchParentSize())
            }
        }
        GlyphState.DONE -> FilledGlyph(R.drawable.ic_ms_check, scheme.primary, scheme.onPrimary, size, modifier)
        GlyphState.FAILED -> FilledGlyph(R.drawable.ic_ms_close, scheme.error, scheme.onError, size, modifier)
        GlyphState.IDLE, GlyphState.SKIPPED -> {
            val ring = scheme.outline
            Canvas(modifier.size(size)) {
                drawCircle(
                    ring,
                    radius = this.size.minDimension * 0.34f,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun FilledGlyph(@DrawableRes icon: Int, container: Color, content: Color, size: Dp, modifier: Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        LvIcon(icon, tint = content, size = size * 0.66f)
    }
}

/** Linear progress bar (4dp): completed part `primary`, track `secondary-container`. */
@Composable
fun LinearMeter(fraction: Float, modifier: Modifier = Modifier, track: Color = MaterialTheme.colorScheme.secondaryContainer) {
    val bar = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(4.dp).clip(CircleShape)) {
        drawRect(track)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(bar, size = androidx.compose.ui.geometry.Size(w, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
    }
}

// ---------------------------------------------------------------------------------------------
// Top app bars
// ---------------------------------------------------------------------------------------------

/** Small TopAppBar: 64dp, `title-large`. Used on second-level pages. */
@Composable
fun SmallTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp).padding(horizontal = Space.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        if (navigation != null) navigation() else Spacer(Modifier.width(Space.s3))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = Space.s1),
        )
        actions()
    }
}

/** Large TopAppBar: `headline-medium` title below the action row. Used on first-level pages. */
@Composable
fun LargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(start = Space.s1, end = Space.s1, bottom = Space.s4)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigation?.invoke()
            Spacer(Modifier.weight(1f))
            actions()
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Space.s3),
        )
    }
}

/** 48dp touch target around a 24dp icon; [description] is mandatory for accessibility. */
@Composable
fun LvIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center,
        ) {
            LvIcon(icon, tint = tint, description = description)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lists and groups
// ---------------------------------------------------------------------------------------------

/** Container colour for settings groups; raised one step inside a floating detail pane. */
val LocalGroupColor = staticCompositionLocalOf<Color?> { null }

/** `lv-subheader`: `title-small` in `primary`. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.s4 + Space.s4, end = Space.s4, top = Space.s6, bottom = Space.s2),
    )
}

class GroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    /** Adds one row; rows are separated by thin `outline-variant` lines. */
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/** `lv-group`: rounded group card (`radius-lg`) on `surface-container`. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: GroupScope.() -> Unit) {
    val scope = GroupScope().apply(content)
    if (scope.rows.isEmpty()) return
    val color = LocalGroupColor.current ?: MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.s4)
            .clip(RoundedCornerShape(Radius.lg))
            .background(color),
    ) {
        scope.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            row()
        }
    }
}

/** `lv-item`: optional leading icon, headline + support text, optional trailing content. */
@Composable
fun ListRow(
    headline: String,
    modifier: Modifier = Modifier,
    support: String? = null,
    @DrawableRes leadingIcon: Int? = null,
    leadingBox: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    val supportColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val base = if (selected) {
        modifier.clip(CircleShape).background(scheme.secondaryContainer)
    } else {
        modifier
    }
    Row(
        modifier = base
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(enabled = enabled, onClick = onClick) else it }
            .heightIn(min = if (support == null) 56.dp else 72.dp)
            .padding(horizontal = Space.s4, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        if (leadingIcon != null) {
            if (leadingBox) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { LvIcon(leadingIcon, tint = scheme.onPrimaryContainer) }
            } else {
                LvIcon(leadingIcon, tint = supportColor)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) content else content.copy(alpha = 0.38f),
            )
            if (support != null) {
                Text(
                    support,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) supportColor else supportColor.copy(alpha = 0.38f),
                )
            }
        }
        trailing?.invoke()
    }
}

/** Trailing value + chevron for navigation rows. */
@Composable
fun TrailingValue(value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        LvIcon(R.drawable.ic_ms_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Whole row toggles the switch (`<label>` behaviour). */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    support: String? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = if (support == null) 56.dp else 72.dp)
            .padding(horizontal = Space.s4, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface.dim(enabled))
            if (support != null) {
                Text(support, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.dim(enabled))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = scheme.primary,
                checkedThumbColor = scheme.onPrimary,
                checkedBorderColor = scheme.primary,
                uncheckedTrackColor = scheme.surfaceContainerHighest,
                uncheckedThumbColor = scheme.outline,
                uncheckedBorderColor = scheme.outline,
            ),
        )
    }
}

private fun Color.dim(enabled: Boolean) = if (enabled) this else copy(alpha = 0.38f)

/** Single choice from up to ~5 options: a row with the current value that opens a dialog. */
@Composable
fun <T> SelectRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    support: String? = null,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second
    ListRow(
        headline = title,
        support = support ?: current,
        enabled = enabled,
        onClick = { open = true },
        trailing = { TrailingValue(if (support != null) current else null) },
    )
    if (open) {
        ChoiceDialog(
            title = title,
            options = options,
            selected = selected,
            onDismiss = { open = false },
            onSelected = {
                open = false
                onSelected(it)
            },
        )
    }
}

/** Interrupting single-choice dialog (`surface-container-high`, `radius-xl`). */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelected: (T) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(Radius.xl),
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .selectable(selected = value == selected, role = Role.RadioButton) { onSelected(value) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = Space.s2),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s3),
                    ) {
                        RadioButton(
                            selected = value == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = scheme.primary),
                        )
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("common.cancel"), style = MaterialTheme.typography.labelLarge) }
        },
    )
}

/** Full-width segmented control for 2–4 short, mutually exclusive options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    support: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
        if (support != null) {
            Text(support, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Space.s3))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = scheme.secondaryContainer,
                        activeContentColor = scheme.onSecondaryContainer,
                        activeBorderColor = scheme.outline,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = scheme.onSurface,
                        inactiveBorderColor = scheme.outline,
                    ),
                    label = {
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

/** Stepped slider with the value label above on the right, in `primary`. */
@Composable
fun SliderRow(
    title: String,
    valueLabel: String,
    index: Int,
    count: Int,
    onIndexChange: (Int) -> Unit,
    support: String? = null,
    startLabel: String? = null,
    endLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                if (support != null) {
                    Text(support, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = scheme.primary,
            )
        }
        val last = (count - 1).coerceAtLeast(1)
        Slider(
            value = index.coerceIn(0, last).toFloat(),
            onValueChange = { onIndexChange(Math.round(it).coerceIn(0, last)) },
            valueRange = 0f..last.toFloat(),
            steps = (count - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.secondaryContainer,
                activeTickColor = scheme.onPrimary.copy(alpha = 0.6f),
                inactiveTickColor = scheme.primary.copy(alpha = 0.5f),
            ),
        )
        if (startLabel != null || endLabel != null) {
            Row {
                Text(startLabel.orEmpty(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(endLabel.orEmpty(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

/** Filled text field (`surface-container-highest` + bottom line), label inside the box. */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    error: String? = null,
    password: Boolean = false,
    numeric: Boolean = false,
    monospace: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s2)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            isError = error != null,
            label = { Text(label) },
            textStyle = if (monospace) LavenderCode.large else MaterialTheme.typography.bodyLarge,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    numeric -> KeyboardType.Number
                    password -> KeyboardType.Password
                    else -> KeyboardType.Ascii
                },
                autoCorrectEnabled = false,
            ),
            shape = RoundedCornerShape(topStart = Radius.xs, topEnd = Radius.xs),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = scheme.surfaceContainerHighest,
                unfocusedContainerColor = scheme.surfaceContainerHighest,
                disabledContainerColor = scheme.surfaceContainerHighest.copy(alpha = 0.38f),
                errorContainerColor = scheme.surfaceContainerHighest,
                focusedIndicatorColor = scheme.primary,
                unfocusedIndicatorColor = scheme.onSurfaceVariant,
                focusedLabelColor = scheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        val helpText = error ?: help
        if (helpText != null) {
            Text(
                helpText,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) scheme.error else scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Space.s4, end = Space.s4, top = Space.s1),
            )
        }
    }
}

/** Short explanation inside a group (`body-small`, `on-surface-variant`). */
@Composable
fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
    )
}

enum class BannerKind { INFO, WARN, ERR }

/** In-page persistent notice (`radius-lg`). */
@Composable
fun Banner(kind: BannerKind, title: String, modifier: Modifier = Modifier, body: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val ext = Lavender.extended
    val (container, content, icon) = when (kind) {
        BannerKind.INFO -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, R.drawable.ic_ms_info_fill)
        BannerKind.WARN -> Triple(ext.warningContainer, ext.onWarningContainer, R.drawable.ic_ms_warning_fill)
        BannerKind.ERR -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.drawable.ic_ms_error_fill)
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.s4)
            .clip(RoundedCornerShape(Radius.lg))
            .background(container)
            .padding(Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        LvIcon(icon, tint = content)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = content)
            }
        }
    }
}

/** Key/value list (`lv-kv`); values use the monospace face so they are easy to copy and compare. */
@Composable
fun KeyValueList(entries: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        entries.forEach { (key, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text(
                    key,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.42f),
                )
                Text(
                    value,
                    style = LavenderCode.large,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(0.58f),
                )
            }
        }
    }
}

/** Card surfaces per the system: filled, hero, container. */
@Composable
fun LvCard(
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = color ?: if (hero) scheme.primaryContainer else scheme.surfaceContainer
    val onContainer = if (hero) scheme.onPrimaryContainer else scheme.onSurface
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (hero) Radius.xl else Radius.lg),
        color = container,
        contentColor = onContainer,
    ) {
        Column(Modifier.padding(if (hero) Space.s6 else Space.s4), content = content)
    }
}

// ---------------------------------------------------------------------------------------------
// Logs
// ---------------------------------------------------------------------------------------------

enum class LogLevel(val code: String) { DEBUG("DBG"), INFO("INF"), OK("OK "), WARN("WRN"), ERROR("ERR") }

/** Monospace log line: timestamp / three-letter level / message; warnings and errors tint the row. */
@Composable
fun LogLine(timestamp: String, level: LogLevel, message: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ext = Lavender.extended
    val (background, textColor, levelColor, stampColor) = when (level) {
        LogLevel.WARN -> LogColors(ext.warningContainer, ext.onWarningContainer, ext.onWarningContainer, ext.onWarningContainer)
        LogLevel.ERROR -> LogColors(scheme.errorContainer, scheme.onErrorContainer, scheme.onErrorContainer, scheme.onErrorContainer)
        LogLevel.OK -> LogColors(Color.Transparent, scheme.onSurface, ext.success, scheme.onSurfaceVariant)
        LogLevel.INFO -> LogColors(Color.Transparent, scheme.onSurface, scheme.primary, scheme.onSurfaceVariant)
        LogLevel.DEBUG -> LogColors(Color.Transparent, scheme.onSurface, scheme.onSurfaceVariant, scheme.onSurfaceVariant)
    }
    Row(
        modifier.fillMaxWidth().background(background).padding(horizontal = Space.s3, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(timestamp, style = LavenderCode.medium, color = stampColor)
        Text(level.code, style = LavenderCode.medium.copy(fontWeight = FontWeight.Medium), color = levelColor)
        Text(message, style = LavenderCode.medium, color = textColor, modifier = Modifier.weight(1f))
    }
}

private data class LogColors(val background: Color, val text: Color, val level: Color, val stamp: Color)

/** Text style helper for tabular numbers. */
val TextStyle.tabular: TextStyle get() = copy(fontFeatureSettings = "tnum")

@Composable
fun ProvideGroupColor(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGroupColor provides color, content = content)
}
