package com.niftyengine.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import kotlin.math.abs

object C {
    val bg = Color(0xFF05080C)
    val s1 = Color(0xFF0A1017)
    val s2 = Color(0xFF0F1721)
    val b1 = Color(0xFF17222F)
    val green = Color(0xFF00E896)
    val red = Color(0xFFFF3D5A)
    val amber = Color(0xFFF5B700)
    val blue = Color(0xFF3D9EFF)
    val violet = Color(0xFF8B5CF6)
    val text = Color(0xFFB4CADC)
    val dim = Color(0xFF5D7A92)
    val white = Color(0xFFF2F6FA)

    fun signed(x: Double, eps: Double = 0.05) = when { x > eps -> green; x < -eps -> red; else -> amber }
}

val Mono = FontFamily.Monospace

@Composable
fun NiftyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.green, onPrimary = Color.Black, secondary = C.blue, background = C.bg, surface = C.s1,
            onSurface = C.text, onBackground = C.text, surfaceVariant = C.s2, outline = C.b1, error = C.red,
        ),
        content = content,
    )
}

@Composable
fun Card(title: String? = null, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.s1)
            .border(1.dp, C.b1, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        if (title != null || trailing != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (title != null) Label(title.uppercase(), color = C.white, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                trailing?.invoke(this)
            }
        }
        content()
    }
}

@Composable
fun Label(
    text: String, color: Color = C.text, size: TextUnit = 12.sp, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, mono: Boolean = true,
) = Text(text, modifier = modifier, color = color, fontSize = size, fontWeight = weight,
    fontFamily = if (mono) Mono else FontFamily.Default, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = size * 1.35)

@Composable
fun KV(key: String, value: String, valueColor: Color = C.white) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Label(key, color = C.dim, size = 11.sp, modifier = Modifier.weight(0.45f))
        Label(value, color = valueColor, size = 11.sp, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Box(
        Modifier.padding(end = 6.dp, bottom = 4.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    ) { Label(text, color = color, size = 10.sp, weight = FontWeight.SemiBold) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowChips(items: List<Pair<String, Color>>) {
    androidx.compose.foundation.layout.FlowRow { items.forEach { Chip(it.first, it.second) } }
}

/** Horizontal probability bar. */
@Composable
fun ProbBar(label: String, p: Double, color: Color, labelWidth: Int = 62) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, color = C.dim, size = if (labelWidth > 62) 11.sp else 12.sp, weight = FontWeight.Bold, modifier = Modifier.width(labelWidth.dp))
        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(C.s2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).clip(RoundedCornerShape(7.dp)).background(color))
        }
        Label("%3.0f%%".format(p * 100), color = color, size = 14.sp, weight = FontWeight.Bold, modifier = Modifier.width(54.dp).padding(start = 8.dp))
    }
}

/** Centered −1..+1 bar for driver scores. */
@Composable
fun ScoreBar(label: String, score: Double, sub: String? = null) {
    val col = C.signed(score)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(label, color = C.text, size = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Label(pluses(score), color = col, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.width(44.dp))
            Label("%+.2f".format(score), color = col, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.width(52.dp))
        }
        Row(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(C.s2), contentAlignment = Alignment.CenterEnd) {
                if (score < 0) Box(Modifier.fillMaxHeight().fillMaxWidth(abs(score).toFloat().coerceAtMost(1f)).background(C.red))
            }
            Spacer(Modifier.width(1.dp).fillMaxHeight().background(C.dim))
            Box(Modifier.weight(1f).fillMaxHeight().background(C.s2), contentAlignment = Alignment.CenterStart) {
                if (score > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(score.toFloat().coerceAtMost(1f)).background(C.green))
            }
        }
        if (sub != null) Label(sub, color = C.dim, size = 10.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

fun pluses(s: Double): String {
    val n = when { abs(s) >= 0.6 -> 3; abs(s) >= 0.3 -> 2; abs(s) >= 0.1 -> 1; else -> 0 }
    return if (n == 0) "·" else (if (s > 0) "+" else "−").repeat(n)
}

/** Expandable card for one engine's signal: score, tags, details. */
@Composable
fun SignalCard(sig: EngineSignal) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.s1)
            .border(1.dp, C.b1, RoundedCornerShape(10.dp)).clickable { open = !open }.padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(sig.name, color = C.white, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Label("conf %.0f%%".format(sig.confidence * 100), color = C.dim, size = 10.sp, modifier = Modifier.padding(end = 8.dp))
            Label("%+.2f".format(sig.score), color = C.signed(sig.score), weight = FontWeight.Bold)
            Label(if (open) " ▴" else " ▾", color = C.dim)
        }
        if (sig.tags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowChips(sig.tags.map { it.replace('_', ' ') to tagColor(it) })
        }
        if (open) {
            Spacer(Modifier.height(4.dp))
            sig.details.forEach { d: Detail -> KV(d.key, d.value) }
        }
    }
}

fun tagColor(t: String): Color = when {
    listOf("UP", "BULL", "STRONG", "PUT_WRITING", "ABOVE", "BREAKOUT", "LONG_BUILDUP", "SHORT_COVERING", "RISK_ON", "TAILWIND", "BROAD", "FALLING", "JOINT_BUYING", "ABSORBING")
        .any { t.contains(it) } && !t.contains("BREADTH_WEAK") -> C.green
    listOf("DOWN", "BEAR", "WEAK", "CALL_WRITING", "BELOW", "BREAKDOWN", "SHORT_BUILDUP", "UNWINDING", "RISK_OFF", "HEADWIND", "SPIKING", "RISING", "SELLING", "FAKE", "DIVERGENCE", "SHOCK")
        .any { t.contains(it) } -> C.red
    else -> C.blue
}

/** Lightweight line chart of spot with bull/bear probability strip. */
@Composable
fun SpotChart(points: List<ChartPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Label("collecting data…", color = C.dim) }
        return
    }
    Canvas(modifier.fillMaxWidth().height(130.dp)) {
        val chartH = size.height - 18f
        val lo = points.minOf { it.spot }; val hi = points.maxOf { it.spot }
        val span = (hi - lo).coerceAtLeast(1e-6)
        val dx = size.width / (points.size - 1)
        val p = Path()
        points.forEachIndexed { i, pt ->
            val x = i * dx
            val y = (chartH - (pt.spot - lo) / span * (chartH - 8) - 4).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, C.blue, style = Stroke(width = 3f))
        points.forEachIndexed { i, pt ->
            val edge = pt.pBull - pt.pBear
            val col = if (edge > 0) C.green.copy(alpha = (abs(edge)).toFloat().coerceIn(0.1f, 1f))
            else C.red.copy(alpha = (abs(edge)).toFloat().coerceIn(0.1f, 1f))
            drawLine(col, Offset(i * dx, size.height - 10f), Offset(i * dx, size.height), strokeWidth = dx.coerceAtLeast(2f) + 0.5f)
        }
    }
}

@Composable
fun SectionGap() = Spacer(Modifier.height(4.dp))

@Composable
fun TableHeader(vararg cols: Pair<String, Float>) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        cols.forEach { (t, w) -> Label(t, color = C.dim, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.weight(w)) }
    }
}

@Composable
fun TableRow(vararg cols: Triple<String, Float, Color>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Start) {
        cols.forEach { (t, w, c) -> Label(t, color = c, size = 11.sp, modifier = Modifier.weight(w), maxLines = 1) }
    }
}

fun dirColor(d: com.niftyengine.engine.model.Direction) = when (d) {
    com.niftyengine.engine.model.Direction.BULLISH -> C.green
    com.niftyengine.engine.model.Direction.BEARISH -> C.red
    com.niftyengine.engine.model.Direction.NEUTRAL -> C.amber
}

/** Row of selectable horizon chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HorizonPicker(
    selected: com.niftyengine.engine.model.HorizonId,
    options: List<com.niftyengine.engine.model.HorizonId> = com.niftyengine.engine.model.HorizonId.values().toList(),
    onSelect: (com.niftyengine.engine.model.HorizonId) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        options.forEach { h ->
            val sel = h == selected
            Box(
                Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) C.green.copy(alpha = 0.15f) else C.s2)
                    .border(1.dp, if (sel) C.green else C.b1, RoundedCornerShape(8.dp))
                    .clickable { onSelect(h) }.padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Label(h.short, color = if (sel) C.green else C.text, size = 11.sp, weight = FontWeight.Bold) }
        }
    }
}

/** Stacked bullish / neutral / bearish bar with labels. */
@Composable
fun TriBar(bull: Double, neutral: Double, bear: Double) {
    Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)).background(C.s2)) {
        if (bull > 0.001) Box(Modifier.weight(bull.toFloat()).fillMaxHeight().background(C.green))
        if (neutral > 0.001) Box(Modifier.weight(neutral.toFloat()).fillMaxHeight().background(C.amber.copy(alpha = 0.7f)))
        if (bear > 0.001) Box(Modifier.weight(bear.toFloat()).fillMaxHeight().background(C.red))
    }
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Label("↑ %.0f%%".format(bull * 100), color = C.green, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Label("→ %.0f%%".format(neutral * 100), color = C.amber, size = 11.sp, modifier = Modifier.weight(1f))
        Label("↓ %.0f%%".format(bear * 100), color = C.red, size = 11.sp, weight = FontWeight.Bold)
    }
}
