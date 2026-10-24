package com.shilapi.xcertplay.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shilapi.xcertplay.HandshakeTimeline
import com.shilapi.xcertplay.HandshakeTimeline.ChipState
import com.shilapi.xcertplay.HandshakeTimeline.Direction
import com.shilapi.xcertplay.HandshakeTimeline.LinkKind
import com.shilapi.xcertplay.HandshakeTimeline.LinkState
import com.shilapi.xcertplay.HandshakeTimeline.PhaseKind
import com.shilapi.xcertplay.HandshakeTimeline.State
import com.shilapi.xcertplay.ScreenLogLine
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.components.GlyphState
import com.shilapi.xcertplay.ui.components.LinearMeter
import com.shilapi.xcertplay.ui.components.LogLevel
import com.shilapi.xcertplay.ui.components.LogLine
import com.shilapi.xcertplay.ui.components.LvIcon
import com.shilapi.xcertplay.ui.components.LvIconButton
import com.shilapi.xcertplay.ui.components.PillKind
import com.shilapi.xcertplay.ui.components.SmallTopBar
import com.shilapi.xcertplay.ui.components.IconVectors
import com.shilapi.xcertplay.ui.components.LoopLayer
import com.shilapi.xcertplay.ui.components.RenderThreadLoop
import com.shilapi.xcertplay.ui.components.StateGlyph
import com.shilapi.xcertplay.ui.components.StatusPill
import com.shilapi.xcertplay.ui.components.tabular
import com.shilapi.xcertplay.ui.i18n.tr
import com.shilapi.xcertplay.ui.i18n.trOr
import com.shilapi.xcertplay.ui.theme.Lavender
import com.shilapi.xcertplay.ui.theme.LavenderCode
import com.shilapi.xcertplay.ui.theme.Radius
import com.shilapi.xcertplay.ui.theme.Space
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main screen while no CarPlay video is on screen: a hero status card (conclusion first:
 * phase, progress, links) next to, or above, the connection log.
 *
 * [revision] must change whenever [timeline] is mutated; the model itself is not observable.
 * A non-null [onRetry] means automatic retries ran out: the status glyph becomes a retry button.
 */
@Composable
fun HomeScreen(
    timeline: HandshakeTimeline?,
    revision: Int,
    status: String,
    wireless: Boolean,
    debugLogs: Boolean,
    logLines: List<ScreenLogLine>,
    onOpenSettings: () -> Unit,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxSize()
            .background(scheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        SmallTopBar(
            title = tr("app.name"),
            actions = {
                LvIconButton(
                    icon = R.drawable.ic_ms_settings,
                    description = tr("home.open_settings"),
                    onClick = onOpenSettings,
                    tint = scheme.onSecondaryContainer,
                    container = scheme.secondaryContainer,
                )
            },
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(start = Space.marginCompact, end = Space.marginCompact, bottom = Space.marginCompact),
        ) {
            val wide = maxWidth > maxHeight * 1.2f
            if (wide) {
                val heroWidth = (maxWidth * 0.4f).coerceIn(300.dp, 440.dp).coerceAtMost(maxWidth / 2)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Box(Modifier.width(heroWidth).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        HeroCard(timeline, revision, status, wireless, onRetry, Modifier.fillMaxWidth())
                    }
                    LogPanel(timeline, revision, debugLogs, logLines, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    HeroCard(
                        timeline, revision, status, wireless, onRetry,
                        Modifier.fillMaxWidth().widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
                    )
                    LogPanel(
                        timeline, revision, debugLogs, logLines,
                        Modifier.weight(1f).fillMaxWidth().widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Hero status card
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(
    timeline: HandshakeTimeline?,
    revision: Int,
    status: String,
    wireless: Boolean,
    onRetry: (() -> Unit)?,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    @Suppress("UNUSED_VARIABLE") val tick = revision
    val failure = timeline?.failure
    val done = (timeline?.completedAtMillis ?: 0L) > 0L
    val phase = timeline?.let(::currentPhase)
    val glyph = when {
        failure != null -> GlyphState.FAILED
        done -> GlyphState.DONE
        else -> GlyphState.ACTIVE
    }
    val title = when {
        failure != null -> failure.title
        done -> tr("home.connected")
        phase != null -> phaseTitle(phase)
        else -> tr("home.starting")
    }
    val subtitle = when {
        timeline == null -> status
        failure != null -> failure.detail
        done -> tr("home.connected_detail")
        else -> timeline.currentAwaiting()?.second?.text ?: status
    }

    // No size animation: each animated frame re-measured the log list below the card.
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.xl),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                if (onRetry != null) {
                    LvIconButton(
                        icon = R.drawable.ic_ms_replay,
                        description = tr("home.retry"),
                        onClick = onRetry,
                        modifier = Modifier.size(40.dp),
                        tint = scheme.onPrimary,
                        container = scheme.primary,
                    )
                } else {
                    StateGlyph(glyph, size = 40.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (failure != null) scheme.error else scheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Always two lines tall: a subtitle wrapping between one and two lines resized the
                    // card, and with it re-measured the log list below, on every status update.
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Space.s1),
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                when {
                    failure != null -> StatusPill(PillKind.ERR, R.drawable.ic_ms_error_fill, tr("home.state.failed"))
                    done -> StatusPill(PillKind.OK, R.drawable.ic_ms_check_circle_fill, tr("home.state.connected"))
                    else -> StatusPill(PillKind.IDLE, R.drawable.ic_ms_hourglass_empty, tr("home.state.connecting"))
                }
                StatusPill(
                    PillKind.IDLE,
                    if (wireless) R.drawable.ic_ms_wifi else R.drawable.ic_ms_usb,
                    if (wireless) tr("home.transport.wireless") else tr("home.transport.wired"),
                )
            }

            timeline?.previousFailure?.let { previous ->
                StatusPill(
                    PillKind.ERR,
                    R.drawable.ic_ms_replay,
                    tr("home.previous_failure", "reason" to previous.substringBefore(": ")),
                )
            }

            if (timeline != null) {
                PhaseTrack(timeline, revision)
                LinkPills(timeline, revision)
            }
        }
    }
}

@Composable
private fun PhaseTrack(timeline: HandshakeTimeline, revision: Int) {
    @Suppress("UNUSED_VARIABLE") val tick = revision
    val scheme = MaterialTheme.colorScheme
    val phases = timeline.phases
    val finished = phases.count { it.state == State.DONE || it.state == State.SKIPPED }
    val current = currentPhase(timeline)
    val position = ((current?.let { phases.indexOf(it) } ?: -1).takeIf { it >= 0 } ?: finished).coerceAtMost(phases.size - 1) + 1
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("home.step_of", "step" to position, "total" to phases.size),
                style = MaterialTheme.typography.labelMedium.tabular,
                color = scheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (current != null && timeline.completedAtMillis == 0L) {
                Text(
                    phaseShortLabel(current.kind, current.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
        LinearMeter(
            fraction = if (phases.isEmpty()) 0f else finished / phases.size.toFloat(),
            track = scheme.surfaceContainerLowest.copy(alpha = 0.6f),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val count = phases.size.coerceAtLeast(1)
            val gap = 6.dp
            val node: Dp = ((maxWidth - gap * (count - 1)) / count).coerceIn(20.dp, 36.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                phases.forEach { phase ->
                    PhaseNode(phase.kind, phase.state, timeline.failure != null, node, phaseShortLabel(phase.kind, phase.label))
                }
            }
        }
    }
}

@Composable
private fun PhaseNode(kind: PhaseKind, state: State, failed: Boolean, size: Dp, label: String) {
    val scheme = MaterialTheme.colorScheme
    if (state == State.ACTIVE && !failed) {
        // Fill and ring breathe on the RenderThread, independent of main-thread composition.
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            RenderThreadLoop(
                listOf(
                    LoopLayer(R.drawable.avd_phase_pulse_disc, scheme.surfaceContainerLowest),
                    LoopLayer(R.drawable.avd_phase_pulse_ring, scheme.primary),
                ),
                // Larger than the node and centred: a view clips its drawing, a layer scale did not.
                Modifier.requiredSize(size * PULSE_PEAK),
            )
            LvIcon(phaseIcon(kind), tint = scheme.primary, size = size * 0.56f, description = label)
        }
        return
    }
    val (container, content, ring) = when (state) {
        State.DONE -> Triple(scheme.primary, scheme.onPrimary, Color.Transparent)
        State.ACTIVE -> Triple(scheme.surfaceContainerLowest, scheme.primary, scheme.primary)
        State.FAILED -> Triple(scheme.error, scheme.onError, Color.Transparent)
        State.SKIPPED -> Triple(Color.Transparent, scheme.onPrimaryContainer.copy(alpha = 0.38f), scheme.onPrimaryContainer.copy(alpha = 0.24f))
        State.IDLE -> Triple(scheme.onPrimaryContainer.copy(alpha = 0.08f), scheme.onPrimaryContainer.copy(alpha = 0.56f), Color.Transparent)
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .border(if (ring == Color.Transparent) 0.dp else 2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        LvIcon(phaseIcon(kind), tint = content, size = size * 0.56f, description = label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkPills(timeline: HandshakeTimeline, revision: Int) {
    @Suppress("UNUSED_VARIABLE") val tick = revision
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        timeline.links.forEach { link ->
            val kind = when (link.state) {
                LinkState.UP, LinkState.ENCRYPTED -> PillKind.OK
                LinkState.LISTENING, LinkState.CONNECTING -> PillKind.INFO
                LinkState.FAILED -> PillKind.ERR
                LinkState.OFF, LinkState.RELEASED -> PillKind.IDLE
            }
            val icon = if (link.state == LinkState.ENCRYPTED) R.drawable.ic_ms_lock else linkIcon(link.kind)
            val stateText = tr("link.state.${link.state.name.lowercase(Locale.ROOT)}")
            val streams = if (link.kind == LinkKind.AIRPLAY && timeline.streamCount > 0) {
                " · " + tr("home.streams", "count" to timeline.streamCount)
            } else {
                ""
            }
            StatusPill(kind, icon, "${link.label} · $stateText$streams")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Log panel: grouped exchange, or the raw debug log
// ---------------------------------------------------------------------------------------------

private enum class LogView { EXCHANGE, DEBUG }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogPanel(
    timeline: HandshakeTimeline?,
    revision: Int,
    debugLogs: Boolean,
    logLines: List<ScreenLogLine>,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var view by rememberSaveable { mutableStateOf(if (debugLogs) LogView.DEBUG else LogView.EXCHANGE) }
    LaunchedEffect(debugLogs) { view = if (debugLogs) LogView.DEBUG else LogView.EXCHANGE }
    Surface(modifier, shape = RoundedCornerShape(Radius.lg), color = scheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = Space.s4, end = Space.s2, top = Space.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LvIcon(R.drawable.ic_ms_receipt_long, tint = scheme.primary, size = 20.dp)
                Text(
                    tr("home.log.title"),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f).padding(start = Space.s2),
                )
                if (debugLogs) {
                    SingleChoiceSegmentedButtonRow(Modifier.height(36.dp)) {
                        listOf(LogView.EXCHANGE to tr("home.log.exchange"), LogView.DEBUG to tr("home.log.debug"))
                            .forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = view == value,
                                    onClick = { view = value },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = scheme.secondaryContainer,
                                        activeContentColor = scheme.onSecondaryContainer,
                                        inactiveContainerColor = Color.Transparent,
                                        activeBorderColor = scheme.outline,
                                        inactiveBorderColor = scheme.outline,
                                    ),
                                    icon = {},
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                    }
                }
            }
            if (view == LogView.DEBUG && debugLogs) {
                DebugLogList(logLines, Modifier.weight(1f).fillMaxWidth())
            } else {
                ExchangeList(timeline, revision, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

private sealed interface ExchangeItem {
    val key: String

    class Header(val group: HandshakeTimeline.Group, val index: Int, val expanded: Boolean) : ExchangeItem {
        override val key = "g$index"
    }

    class Entry(val row: HandshakeTimeline.Row) : ExchangeItem {
        override val key = "r${row.id}"
    }
}

@Composable
private fun ExchangeList(timeline: HandshakeTimeline?, revision: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (timeline == null || timeline.groups.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                StateGlyph(GlyphState.ACTIVE, size = 28.dp)
                Text(tr("home.log.waiting"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        return
    }
    // Per-attempt expansion choices; a new timeline starts with fresh defaults.
    val groupChoice = remember(timeline) { mutableStateMapOf<Int, Boolean>() }
    val rowOpen = remember(timeline) { mutableStateMapOf<Int, Boolean>() }
    val items = run {
        @Suppress("UNUSED_VARIABLE") val tick = revision
        buildList<ExchangeItem> {
            timeline.groups.forEachIndexed { index, group ->
                val auto = group.state != State.DONE || group === timeline.latestGroup
                val expanded = groupChoice[index] ?: auto
                add(ExchangeItem.Header(group, index, expanded))
                if (expanded) group.rows.forEach { add(ExchangeItem.Entry(it)) }
            }
        }
    }
    val listState = rememberLazyListState()
    FollowLatest(listState, revision, items.size)
    LazyColumn(modifier, state = listState) {
        // Rows get only what they show, so a new record recomposes the rows it changed, not all of them.
        for (entry in items) {
            item(key = entry.key) {
                when (entry) {
                    is ExchangeItem.Header -> {
                        val group = entry.group
                        val index = entry.index
                        val expanded = entry.expanded
                        GroupHeader(
                            title = group.title,
                            tag = group.tag,
                            state = if (timeline.failure != null && group.state == State.ACTIVE) State.SKIPPED else group.state,
                            durationMillis = if (group.state == State.DONE && group.endedAtMillis > 0) {
                                group.endedAtMillis - group.startedAtMillis
                            } else {
                                null
                            },
                            expanded = expanded,
                            onToggle = { groupChoice[index] = !expanded },
                        )
                    }
                    is ExchangeItem.Entry -> {
                        val id = entry.row.id
                        ExchangeRow(
                            entry.row, entry.row.revision,
                            open = rowOpen[id] == true,
                            onToggle = { rowOpen[id] = rowOpen[id] != true },
                        )
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(Space.s3)) }
    }
}

private const val PULSE_PEAK = 1.06f

/** Every icon the home screen can show, for [IconVectors.preload]. */
val HOME_SCREEN_ICONS = intArrayOf(
    R.drawable.ic_ms_airplay,
    R.drawable.ic_ms_arrow_back,
    R.drawable.ic_ms_arrow_forward,
    R.drawable.ic_ms_bluetooth,
    R.drawable.ic_ms_cancel_fill,
    R.drawable.ic_ms_cell_tower,
    R.drawable.ic_ms_check,
    R.drawable.ic_ms_check_circle_fill,
    R.drawable.ic_ms_close,
    R.drawable.ic_ms_error_fill,
    R.drawable.ic_ms_expand_more,
    R.drawable.ic_ms_fiber_manual_record,
    R.drawable.ic_ms_hourglass_empty,
    R.drawable.ic_ms_key,
    R.drawable.ic_ms_lan,
    R.drawable.ic_ms_layers,
    R.drawable.ic_ms_link,
    R.drawable.ic_ms_lock,
    R.drawable.ic_ms_play_circle,
    R.drawable.ic_ms_receipt_long,
    R.drawable.ic_ms_replay,
    R.drawable.ic_ms_settings,
    R.drawable.ic_ms_sync_alt,
    R.drawable.ic_ms_usb,
    R.drawable.ic_ms_verified_user,
    R.drawable.ic_ms_wifi,
)

/** Keeps the newest entry in view unless the reader scrolled away from the bottom. */
@Composable
private fun FollowLatest(state: LazyListState, revision: Int, count: Int) {
    var follow by remember { mutableStateOf(true) }
    val autoScrolling = remember { booleanArrayOf(false) }
    val atBottom by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress && !autoScrolling[0]) follow = atBottom
    }
    // New rows land below the viewport, so the list first holds its place and then slides
    // down; the slide is shorter than one log frame, so it never queues behind the next.
    LaunchedEffect(revision, count) {
        if (!follow || count <= 0) return@LaunchedEffect
        autoScrolling[0] = true
        try {
            var distance = distanceToEnd(state)
            // Every row the slide passes through is composed, so a large backlog jumps to
            // within half a viewport first and only the rest is animated.
            val maxSlide = state.layoutInfo.viewportSize.height * 0.5f
            if (distance > maxSlide) {
                state.scrollBy(distance - maxSlide)
                distance = maxSlide
            }
            if (distance > 0f) state.animateScrollBy(distance, tween(FOLLOW_SLIDE_MILLIS, easing = FastOutSlowInEasing))
            state.scrollToItem(count)
        } finally {
            autoScrolling[0] = false
        }
    }
}

private const val FOLLOW_SLIDE_MILLIS = 180

/** Pixels left to the end of the list; rows not laid out yet are taken at the visible average. */
private fun distanceToEnd(state: LazyListState): Float {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    val last = visible.lastOrNull() ?: return 0f
    val average = visible.sumOf { it.size }.toFloat() / visible.size
    val hidden = info.totalItemsCount - 1 - last.index
    return (last.offset + last.size - info.viewportEndOffset + info.afterContentPadding) + hidden * average
}

@Composable
private fun GroupHeader(
    title: String,
    tag: String?,
    state: State,
    durationMillis: Long?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, tween(150), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(start = Space.s4, end = Space.s3, top = Space.s2, bottom = Space.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        StateGlyph(glyphOf(state), size = 18.dp)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        tagIcon(tag)?.let { LvIcon(it, tint = scheme.onSurfaceVariant, size = 16.dp) }
        if (durationMillis != null) {
            Text(
                duration(durationMillis),
                style = LavenderCode.medium,
                color = scheme.onSurfaceVariant,
            )
        }
        LvIcon(
            R.drawable.ic_ms_expand_more,
            tint = scheme.onSurfaceVariant,
            size = 20.dp,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
            description = if (expanded) tr("home.log.collapse") else tr("home.log.expand"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExchangeRow(row: HandshakeTimeline.Row, rowRevision: Int, open: Boolean, onToggle: () -> Unit) {
    // [row] is mutated in place; [rowRevision] is what tells Compose this row changed.
    @Suppress("UNUSED_VARIABLE") val tick = rowRevision
    val scheme = MaterialTheme.colorScheme
    val ext = Lavender.extended
    val detail = row.detail
    val (arrow, arrowTint) = when (row.direction) {
        Direction.ACCESSORY_TO_PHONE -> R.drawable.ic_ms_arrow_forward to scheme.primary
        Direction.PHONE_TO_ACCESSORY -> R.drawable.ic_ms_arrow_back to scheme.tertiary
        Direction.LOCAL -> R.drawable.ic_ms_fiber_manual_record to scheme.outline
    }
    val nameColor = when {
        row.failed -> scheme.error
        row.warning -> ext.warning
        row.direction == Direction.LOCAL -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (detail != null) it.clickable(onClick = onToggle) else it }
            .padding(start = Space.s4, end = Space.s3, top = Space.s1, bottom = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Box(Modifier.size(18.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            LvIcon(
                arrow,
                tint = arrowTint,
                size = if (row.direction == Direction.LOCAL) 8.dp else 16.dp,
                description = directionLabel(row.direction),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (detail != null) {
                    val rotation by animateFloatAsState(if (open) 0f else -90f, tween(150), label = "detail")
                    LvIcon(R.drawable.ic_ms_expand_more, tint = scheme.onSurfaceVariant, size = 18.dp, modifier = Modifier.graphicsLayer { rotationZ = rotation })
                }
            }
            if (row.direction != Direction.LOCAL && row.meaning != row.name) {
                Text(
                    row.meaning,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.failed) scheme.error else scheme.onSurfaceVariant,
                )
            }
            row.reply?.let { reply ->
                val ok = reply.status == "200"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LvIcon(
                        if (ok) R.drawable.ic_ms_check_circle_fill else R.drawable.ic_ms_cancel_fill,
                        tint = if (ok) ext.success else scheme.error,
                        size = 14.dp,
                    )
                    Text(
                        reply.meaning ?: if (ok) tr("home.log.reply_ok") else reply.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) scheme.onSurfaceVariant else scheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${reply.latencyMillis} ms", style = LavenderCode.medium, color = scheme.onSurfaceVariant)
                }
            }
            if (row.chips.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = Space.s1),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.chips.forEach { chip -> ChipTag(chip.label, chip.state) }
                }
            }
            if (detail != null && open) {
                Text(
                    detail,
                    style = LavenderCode.medium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = Space.s1)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.md))
                        .background(scheme.surfaceContainer)
                        .padding(horizontal = Space.s3, vertical = Space.s2),
                )
            }
        }
    }
}

@Composable
private fun ChipTag(label: String, state: ChipState) {
    val scheme = MaterialTheme.colorScheme
    val ext = Lavender.extended
    val (container, content) = when (state) {
        ChipState.OK -> ext.successContainer to ext.onSuccessContainer
        ChipState.PARTIAL -> ext.warningContainer to ext.onWarningContainer
        ChipState.MISSING -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(
            textDecoration = if (state == ChipState.MISSING) TextDecoration.LineThrough else null,
        ),
        color = content,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(container)
            .padding(horizontal = Space.s2, vertical = 2.dp),
    )
}

@Composable
private fun DebugLogList(lines: List<ScreenLogLine>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("home.log.empty"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        return
    }
    val state = rememberLazyListState()
    FollowLatest(state, lines.size, lines.size)
    LazyColumn(modifier, state = state) {
        items(lines.size, key = { lines[it].id }) { index ->
            val line = lines[index]
            LogLine(formatTime(line.timestampMillis), levelOf(line.text), line.text)
        }
        item(key = "end") { Spacer(Modifier.height(Space.s3)) }
    }
}

/**
 * Debug log shown over running CarPlay video. Read-only: it never consumes touches, so input
 * still reaches the phone.
 */
@Composable
fun StreamingLogOverlay(lines: List<ScreenLogLine>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val visible = lines.takeLast(MAX_OVERLAY_LINES)
    if (visible.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(Space.s3)
            .clip(RoundedCornerShape(Radius.md))
            .background(scheme.surfaceContainerLowest.copy(alpha = 0.86f))
            .padding(vertical = Space.s2),
    ) {
        visible.forEach { line ->
            LogLine(formatTime(line.timestampMillis), levelOf(line.text), line.text)
        }
    }
}

private const val MAX_OVERLAY_LINES = 14

// ---------------------------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------------------------

/** The failed phase, else the phase of the latest exchange while it runs, else the last running one. */
private fun currentPhase(model: HandshakeTimeline): HandshakeTimeline.Phase? {
    val latest = model.latestGroup?.phase
    if (model.failure != null) return model.phases.firstOrNull { it.state == State.FAILED } ?: latest
    if (latest?.state == State.ACTIVE) return latest
    return model.phases.lastOrNull { it.state == State.ACTIVE } ?: latest
}

private fun phaseTitle(phase: HandshakeTimeline.Phase): String =
    trOr("phase.${phase.kind.name.lowercase(Locale.ROOT)}.title", phase.title)

private fun phaseShortLabel(kind: PhaseKind, fallback: String): String =
    trOr("phase.${kind.name.lowercase(Locale.ROOT)}.label", fallback)

private fun glyphOf(state: State): GlyphState = when (state) {
    State.IDLE -> GlyphState.IDLE
    State.ACTIVE -> GlyphState.ACTIVE
    State.DONE -> GlyphState.DONE
    State.FAILED -> GlyphState.FAILED
    State.SKIPPED -> GlyphState.SKIPPED
}

private fun directionLabel(direction: Direction): String = when (direction) {
    Direction.ACCESSORY_TO_PHONE -> tr("home.log.to_phone")
    Direction.PHONE_TO_ACCESSORY -> tr("home.log.from_phone")
    Direction.LOCAL -> tr("home.log.local")
}

@DrawableRes
private fun phaseIcon(kind: PhaseKind): Int = when (kind) {
    PhaseKind.MFI -> R.drawable.ic_ms_verified_user
    PhaseKind.HOTSPOT -> R.drawable.ic_ms_wifi
    PhaseKind.ADVERTISE -> R.drawable.ic_ms_cell_tower
    PhaseKind.BLUETOOTH -> R.drawable.ic_ms_bluetooth
    PhaseKind.USB -> R.drawable.ic_ms_usb
    PhaseKind.USB_DATA -> R.drawable.ic_ms_layers
    PhaseKind.LOCKDOWN -> R.drawable.ic_ms_lock
    PhaseKind.NETWORK -> R.drawable.ic_ms_lan
    PhaseKind.IAP2 -> R.drawable.ic_ms_link
    PhaseKind.PAIR -> R.drawable.ic_ms_key
    PhaseKind.SESSION -> R.drawable.ic_ms_airplay
    PhaseKind.STREAMS -> R.drawable.ic_ms_play_circle
    PhaseKind.HANDOFF -> R.drawable.ic_ms_sync_alt
}

@DrawableRes
private fun linkIcon(kind: LinkKind): Int = when (kind) {
    LinkKind.WIFI -> R.drawable.ic_ms_wifi
    LinkKind.RFCOMM -> R.drawable.ic_ms_bluetooth
    LinkKind.USB -> R.drawable.ic_ms_usb
    LinkKind.NCM -> R.drawable.ic_ms_lan
    LinkKind.AIRPLAY -> R.drawable.ic_ms_airplay
    LinkKind.TUNNEL -> R.drawable.ic_ms_sync_alt
}

@DrawableRes
private fun tagIcon(tag: String?): Int? = when (tag) {
    "Bluetooth" -> R.drawable.ic_ms_bluetooth
    "Tunnel" -> R.drawable.ic_ms_sync_alt
    "CarKit" -> R.drawable.ic_ms_usb
    "USB network" -> R.drawable.ic_ms_lan
    "Encrypted" -> R.drawable.ic_ms_lock
    "TCP 7000" -> R.drawable.ic_ms_wifi
    else -> null
}

private fun duration(millis: Long): String =
    if (millis < 1000) "${millis.coerceAtLeast(0)} ms" else String.format(Locale.US, "%.1f s", millis / 1000.0)

private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}

private fun formatTime(millis: Long): String = timeFormat.get()!!.format(Date(millis))

/** Three-letter level for a host log message (the host log carries no explicit level). */
internal fun levelOf(message: String): LogLevel {
    val text = message.lowercase(Locale.ROOT)
    return when {
        text.startsWith("error") || "failed" in text || "failure" in text || "error" in text ||
            "exception" in text || "timeout" in text -> LogLevel.ERROR
        "denied" in text || "unavailable" in text || "retrying" in text || "warn" in text ||
            "stale" in text || "reconnecting" in text -> LogLevel.WARN
        "granted" in text || "ready" in text || "active" in text || "complete" in text ||
            "saved" in text || "established" in text -> LogLevel.OK
        text.startsWith("trace") || text.startsWith("iap2 ") || text.startsWith("airplay rx") ||
            text.startsWith("airplay tx") -> LogLevel.DEBUG
        else -> LogLevel.INFO
    }
}

@Suppress("unused")
private fun Modifier.dimmed(enabled: Boolean) = if (enabled) this else alpha(0.38f)
