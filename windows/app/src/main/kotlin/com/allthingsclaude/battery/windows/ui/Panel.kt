package com.allthingsclaude.battery.windows.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import com.allthingsclaude.battery.core.TimeFormatting
import com.allthingsclaude.battery.core.UsageBucket
import com.allthingsclaude.battery.core.UsageLevel
import com.allthingsclaude.battery.windows.history.LocalStats
import com.allthingsclaude.battery.windows.state.AppState
import com.allthingsclaude.battery.windows.state.UiUsage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
// Aliased: `androidx.compose.ui.text.TextStyle` above owns the short name here.
import java.time.format.TextStyle as DayNameStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The one text primitive this panel uses.
 *
 * `BasicText` rather than Material's `Text` because the panel sets its own
 * colour, size and weight on every call — a Material theme would be a layer of
 * defaults that nothing here reads.
 */
@Composable
private fun Text(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
) = BasicText(
    text = text,
    style = TextStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
    ),
)

/**
 * The corner radius for a panel that is not in a window — the `--screenshot`
 * renders, which are images rather than surfaces and have nobody to round them.
 *
 * The real flyout passes zero and lets the compositor do it, which is both the
 * radius the rest of Windows is using and a preference the user is allowed to
 * have turned off. See [com.allthingsclaude.battery.windows.win.WindowCorner].
 */
val STANDALONE_CORNER = 12.dp

/**
 * The flyout panel — the Windows answer to `Sources/Views/PanelRootView.swift`.
 *
 * Deliberately the same shape as the macOS popover rather than a Windows
 * reinvention: someone running Battery on both machines should not have to
 * learn it twice, and the gauges, the projection line and the extra-usage row
 * all carry meaning that is already settled.
 */
@Composable
fun Panel(
    state: AppState,
    modifier: Modifier = Modifier.fillMaxSize(),
    corner: Dp = STANDALONE_CORNER,
) {
    val palette = LocalBatteryPalette.current

    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(palette.surface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Header(state)
            Spacer(Modifier.height(18.dp))

            val usage = state.usage
            if (usage == null) {
                Empty(state)
            } else {
                Gauges(usage)
                usage.projection?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        it,
                        color = palette.secondary,
                        fontSize = 12.sp,
                    )
                }
                usage.extra?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = palette.secondary, fontSize = 12.sp)
                }
            }

            // The heading is always here; what hangs under it is not. Absent
            // until the panel opens and reads it, absent when the distro is
            // down, and absent when the user has collapsed it — so the flyout is
            // exactly as tall as what it has to say, which is what packing to
            // content bought.
            Spacer(Modifier.height(18.dp))
            LocalActivity(
                stats = state.stats,
                expanded = state.details,
                onToggle = { state.showDetails(!state.details) },
            )
        }
    }
}

/**
 * The seven-day chart and the project breakdown, under a heading that collapses
 * them.
 *
 * The port of macOS's `showDetails` switch, which gates the same content there.
 * A clickable heading rather than a switch in the title bar: this panel has no
 * other controls to sit beside one, and a caret on the section it opens says
 * what it does without a caption explaining it.
 */
@Composable
private fun LocalActivity(stats: LocalStats?, expanded: Boolean, onToggle: () -> Unit) {
    val palette = LocalBatteryPalette.current

    Divider()
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier
            .fillMaxWidth()
            // No indication: this panel has no Material ripple to borrow, and a
            // section heading that flashes on click would be the loudest thing
            // on a surface whose whole job is to be glanced at.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Last 7 days",
            color = if (expanded) palette.onSurface else palette.secondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Caret(expanded)
    }

    if (!expanded || stats == null) return

    Spacer(Modifier.height(12.dp))
    DayChart(stats)

    if (stats.projects.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        val busiest = stats.projects.first().tokens.coerceAtLeast(1L)
        stats.projects.forEach { project ->
            ProjectRow(project.name, project.tokens, project.tokens.toFloat() / busiest)
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** A chevron, pointing at what happens next rather than at the current state. */
@Composable
private fun Caret(expanded: Boolean) {
    val palette = LocalBatteryPalette.current
    Canvas(Modifier.size(width = 10.dp, height = 10.dp)) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        val top = if (expanded) size.height * 0.65f else size.height * 0.35f
        val point = if (expanded) size.height * 0.35f else size.height * 0.65f
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, top)
                lineTo(size.width / 2f, point)
                lineTo(size.width, top)
            },
            color = palette.secondary,
            style = stroke,
        )
    }
}

@Composable
private fun Divider() {
    val palette = LocalBatteryPalette.current
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawRect(palette.secondary.copy(alpha = 0.22f))
    }
}

/**
 * Seven bars and their weekday initials.
 *
 * Scaled against the busiest day rather than against a fixed ceiling: the useful
 * question is which day was heavy relative to the others, and a plan's absolute
 * token budget is not something this chart knows or should imply.
 */
@Composable
private fun DayChart(stats: LocalStats) {
    val palette = LocalBatteryPalette.current
    val busiest = stats.busiestDay
    val today = stats.days.lastOrNull()?.date

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        stats.days.forEach { day ->
            val fraction = if (busiest > 0) day.tokens.toFloat() / busiest else 0f
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.width(BAR_WIDTH).height(CHART_HEIGHT)) {
                    val radius = CornerRadius(size.width / 2f, size.width / 2f)
                    // The track, always full height, so an idle day reads as an
                    // empty column rather than as a missing one.
                    drawRoundRect(palette.brand.copy(alpha = 0.14f), cornerRadius = radius)
                    if (fraction > 0f) {
                        // A floor of one bar-width: a day with a handful of
                        // tokens is not the same as a day with none, and
                        // rounding it away would say it was.
                        val filled = maxOf(size.height * fraction, size.width)
                        drawRoundRect(
                            color = palette.brand,
                            topLeft = Offset(0f, size.height - filled),
                            size = Size(size.width, filled),
                            cornerRadius = radius,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    day.date.dayOfWeek.getDisplayName(DayNameStyle.NARROW, Locale.getDefault()),
                    color = if (day.date == today) palette.onSurface else palette.secondary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(name: String, tokens: Long, fraction: Float) {
    val palette = LocalBatteryPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = palette.onSurface, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(compactTokens(tokens), color = palette.secondary, fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.width(120.dp).height(6.dp)) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(palette.brand.copy(alpha = 0.14f), cornerRadius = radius)
            drawRoundRect(
                color = palette.brand,
                size = Size(
                    // Never thinner than it is tall, so the smallest project is
                    // still a dot rather than a sliver of nothing.
                    maxOf(size.width * fraction.coerceIn(0f, 1f), size.height),
                    size.height,
                ),
                cornerRadius = radius,
            )
        }
    }
}

/** The seven-day chart's bars: tall and narrow, so it reads as a chart. */
private val CHART_HEIGHT = 40.dp
private val BAR_WIDTH = 14.dp

/**
 * Token counts, shortened.
 *
 * A cache-heavy session runs to millions, and eight digits in an 11 sp row is
 * noise — the comparison between rows is the point, and the bar already carries
 * it.
 */
internal fun compactTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.0fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
private fun Header(state: AppState) {
    val palette = LocalBatteryPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                state.identity ?: "Battery",
                color = palette.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                state.sourceLabel,
                color = palette.secondary,
                fontSize = 11.sp,
            )
        }
        StatusDot(state.healthy)
    }
}

@Composable
private fun StatusDot(healthy: Boolean) {
    val palette = LocalBatteryPalette.current
    Canvas(Modifier.size(8.dp)) {
        drawCircle(if (healthy) palette.brand else palette.secondary)
    }
}

@Composable
private fun Empty(state: AppState) {
    val palette = LocalBatteryPalette.current
    Column {
        Text(
            state.message ?: "Waiting for the first reading…",
            color = palette.secondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun Gauges(usage: UiUsage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        usage.session?.let { Gauge("Session", it, Modifier.weight(1f)) }
        Gauge("Week", usage.weekly, Modifier.weight(1f))
        usage.scoped?.let { Gauge(usage.scopedLabel ?: "Model", it, Modifier.weight(1f)) }
    }
}

/** One ring. Port of `SessionGaugeView` / `WeeklyGaugeView`. */
@Composable
private fun Gauge(label: String, bucket: UsageBucket, modifier: Modifier = Modifier) {
    val palette = LocalBatteryPalette.current
    val level = UsageLevel.from(bucket.utilization)
    val color = Color(level.color)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(72.dp)) {
                val stroke = 7.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val offset = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
                drawArc(
                    color = color.copy(alpha = 0.18f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = offset,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (bucket.utilization > 0) {
                    drawArc(
                        color = color,
                        // -90 puts zero at twelve o'clock; the sweep runs
                        // clockwise, matching every other Battery surface.
                        startAngle = -90f,
                        sweepAngle = (bucket.utilization.coerceIn(0.0, 100.0) / 100.0 * 360.0).toFloat(),
                        useCenter = false,
                        topLeft = offset,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                "${bucket.utilization.toInt()}%",
                color = palette.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = palette.onSurface, fontSize = 12.sp)
        Text(
            resetsIn(bucket.resetsAt),
            color = palette.secondary,
            fontSize = 11.sp,
        )
    }
}

/**
 * When a window resets: a countdown for the near ones, a clock time for the rest.
 *
 * A five-hour session is a countdown — "4h 37m" is exactly how long you have and
 * exactly what you want to know. A seven-day window is not: "123h 47m" is a
 * number nobody converts, and it was on screen for as long as this panel has
 * existed. `core`'s [TimeFormatting.untilReset] exists because the same defect
 * showed up on Apple ("153h 0m") and it renders `5d 3h`, which is readable but
 * still arithmetic — you can't plan around it without doing a sum.
 *
 * So past a day this says *when*, not *how long*: "Wed 19:00". That is the
 * answer to the question actually being asked, and it is the shape iOS already
 * uses — `TimeFormatting` deliberately refuses it only because a shared,
 * fixture-pinned formatter cannot carry a time zone or a locale. A desktop panel
 * has both.
 *
 * Coarse below a day on purpose: a ticking second hand in a tray flyout is noise.
 */
internal fun resetsIn(
    at: Instant?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    // FORMAT, not `Locale.getDefault()`. Windows keeps the display language and
    // the regional format apart, and the JVM mirrors that split — on the machine
    // this was written on they are `en_US` and `sr_RS_#Latn`, so the plain
    // default renders "7:00 PM" beside a taskbar clock reading 19:00. FORMAT is
    // the one that means "how this person writes times".
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): String {
    if (at == null) return "—"
    val seconds = at.epochSecond - now.epochSecond
    if (seconds <= 0) return "resetting"

    // `core`'s formatter, not a second opinion about what "4h 37m" looks like.
    // It is pinned by fixtures/time-formatting.json across all four platforms.
    if (seconds < DAY_SECONDS) return TimeFormatting.shortDuration(seconds.toDouble())

    val reset = at.atZone(zone)
    val clock = reset.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    val daysAway = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), reset.toLocalDate())
    return if (daysAway <= 6) {
        // Inside a week a weekday names the day unambiguously, and it is what
        // people say out loud. Abbreviated because this sits under a gauge in a
        // three-column row: "Wednesday" is wider than the column.
        "${reset.dayOfWeek.getDisplayName(DayNameStyle.SHORT, locale)} $clock"
    } else {
        // Seven days out the weekday has come round again and would name two
        // different days. A weekly window never gets here; a plan with a longer
        // one would.
        reset.format(DateTimeFormatter.ofPattern("d MMM", locale)) + " $clock"
    }
}

private const val DAY_SECONDS = 24 * 60 * 60L

@Suppress("unused")
private fun Modifier.unusedWidth() = this.width(0.dp)
