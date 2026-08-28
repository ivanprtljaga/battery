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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import com.allthingsclaude.battery.core.UsageBucket
import com.allthingsclaude.battery.core.UsageLevel
import com.allthingsclaude.battery.windows.state.AppState
import com.allthingsclaude.battery.windows.state.UiUsage
import java.time.Instant

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
 * The flyout panel — the Windows answer to `Sources/Views/PanelRootView.swift`.
 *
 * Deliberately the same shape as the macOS popover rather than a Windows
 * reinvention: someone running Battery on both machines should not have to
 * learn it twice, and the gauges, the projection line and the extra-usage row
 * all carry meaning that is already settled.
 */
@Composable
fun Panel(state: AppState) {
    val palette = LocalBatteryPalette.current

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
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
        }
    }
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

/** Port of the countdown label. Coarse on purpose — a ticking second hand in a
 *  tray flyout is noise, and the window this measures is five hours long. */
internal fun resetsIn(at: Instant?, now: Instant = Instant.now()): String {
    if (at == null) return "—"
    val seconds = at.epochSecond - now.epochSecond
    if (seconds <= 0) return "resetting"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Suppress("unused")
private fun Modifier.unusedWidth() = this.width(0.dp)
