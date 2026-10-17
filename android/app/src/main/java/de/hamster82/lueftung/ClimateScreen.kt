package de.hamster82.lueftung

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Farben je Ort (in allen Diagrammen gleich), Stufe und Feuchte-Stufe
private val COL_OUT = Color(0xFF1E88E5)
private val COL_IN = Color(0xFFF4511E)
private val COL_STUFE = Color(0xFF2E7D32)
private val COL_FEUCHTE = Color(0xFF8E24AA)

private fun fmt1(v: Double?) = if (v == null) "–" else String.format(Locale.GERMANY, "%.1f", v)
private fun fmt0(v: Double?) = if (v == null) "–" else v.roundToInt().toString()

@Composable
fun ClimateScreen(vm: MainViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val klima by vm.klima.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val cmds by vm.commands.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        vm.klimaActive.value = true
        vm.refreshKlima()
        onDispose { vm.klimaActive.value = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (status == null) {
            ConnectCard(vm, error)
            return@Column
        }
        val k = klima
        if (k == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Lade Klimadaten …")
            }
            Text(
                "Zeigt die Brücke nichts an, braucht sie die Firmware ab Version 1.1 (Reiter Bus → Firmware-Update).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        CurrentCard(k, vm)
        Section("Verlauf 24 Stunden") { HistoryChart(history) }
        RulesCard(k, cmds, vm)
        PlaceCard(k, vm)
    }
}

// ------------------------------------------------------------------ aktuelle Werte
@Composable
private fun CurrentCard(k: Klima, vm: MainViewModel) {
    Section("Aktuell") {
        Row(Modifier.fillMaxWidth()) {
            ValueColumn("Außen", COL_OUT, k.outdoor, Modifier.weight(1f))
            ValueColumn("Innen", COL_IN, k.indoor, Modifier.weight(1f))
        }
        val o = k.outdoor
        val i = k.indoor
        if (o.valid && i.valid && o.ah != null && i.ah != null) {
            val diff = i.ah - o.ah
            Spacer(Modifier.height(8.dp))
            Text(
                if (diff > 0) "Außenluft ist um ${fmt1(diff)} g/m³ trockener als innen – Lüften entfeuchtet."
                else "Außenluft ist um ${fmt1(-diff)} g/m³ feuchter als innen – Lüften bringt Feuchte herein.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(6.dp))
        val srcOut = when {
            k.weatherErr.isNotEmpty() -> "Außen: ${k.weatherErr}"
            o.valid -> "Außen: ${o.src}" + (if (k.place.isNotEmpty()) " · ${k.place}" else "") + " · vor ${o.agoS / 60} min"
            else -> "Außen: noch keine Daten"
        }
        val srcIn = when {
            i.valid -> "Innen: ${i.src}"
            k.sensor.isEmpty() -> "Innen: kein Sensor am Grove-Anschluss"
            else -> "Innen: ${k.sensor} liefert keine Werte"
        }
        Text("$srcOut\n$srcIn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { vm.refreshWeather() }) { Text("Wetter jetzt abrufen") }
    }
}

@Composable
private fun ValueColumn(title: String, color: Color, r: ClimateReading, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
        Text(if (r.valid) "${fmt1(r.t)} °C" else "–", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (r.valid) "${fmt0(r.rh)} % rF · ${fmt1(r.ah)} g/m³" else "keine Werte",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------ Diagramm
@Composable
private fun HistoryChart(h: History?) {
    val pts = h?.points.orEmpty()
    if (pts.size < 2) {
        Text(
            "Noch zu wenige Messpunkte. Die Brücke speichert alle 5 Minuten einen Punkt – nach einer halben Stunde erscheint hier der Verlauf.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    val tEnd = (h?.now?.takeIf { it > 0 } ?: pts.last().epoch).toDouble()
    val tStart = tEnd - 86400.0
    var selX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)
    val leftPad = 34f * androidx.compose.ui.platform.LocalDensity.current.density

    // ausgewählter Punkt für die Anzeige
    val sel: HistPoint? = selX?.let { x ->
        val frac = ((x - leftPad) / (widthPx - leftPad)).coerceIn(0f, 1f)
        val t = tStart + frac * 86400.0
        pts.minByOrNull { kotlin.math.abs(it.epoch - t) }
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.GERMANY) }

    // Legende / Ablesewert
    if (sel != null) {
        Text(
            "${timeFmt.format(Date(sel.epoch * 1000))} Uhr · außen ${fmt1(sel.outT)} °C, ${fmt0(sel.outRH)} % · innen ${fmt1(sel.inT)} °C, ${fmt0(sel.inRH)} % · " +
                "Stufe ${sel.stufe?.let { if (it == 0) "Aus" else it.toString() } ?: "–"} · Feuchte ${sel.feuchte ?: "–"}",
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(COL_OUT, "außen")
            Legend(COL_IN, "innen")
            Legend(COL_STUFE, "Stufe")
            Legend(COL_FEUCHTE, "Feuchte-Stufe", dashed = true)
        }
    }
    Spacer(Modifier.height(6.dp))

    val tVals = pts.flatMap { listOfNotNull(it.outT, it.inT) }
    val tMin = floor((tVals.minOrNull() ?: 0.0) / 5.0) * 5.0
    val tMax = max(ceil((tVals.maxOrNull() ?: 20.0) / 5.0) * 5.0, tMin + 5.0)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(330.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) { detectTapGestures { selX = if (selX == null) it.x else null } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { selX = it.x },
                    onHorizontalDrag = { change, _ -> selX = change.position.x }
                )
            }
    ) {
        val gap = 14.dp.toPx()
        val axisH = 16.dp.toPx()
        val panelH = (size.height - axisH - 2 * gap) / 3f
        val x0 = leftPad
        val w = size.width - x0
        fun xOf(t: Long) = x0 + ((t - tStart) / 86400.0 * w).toFloat()

        val panels = listOf(0f, panelH + gap, 2 * (panelH + gap))

        // Zeitraster alle 6 Stunden (volle Stunden)
        val firstTick = (ceil(tStart / 21600.0) * 21600.0).toLong()
        var tick = firstTick
        while (tick <= tEnd) {
            val x = xOf(tick)
            panels.forEach { y -> drawLine(gridColor, Offset(x, y), Offset(x, y + panelH), 1f) }
            val lbl = textMeasurer.measure(timeFmt.format(Date(tick * 1000)), labelStyle)
            drawText(lbl, topLeft = Offset((x - lbl.size.width / 2f).coerceIn(x0, size.width - lbl.size.width), size.height - lbl.size.height))
            tick += 21600
        }

        // Panel 1: Temperatur
        panelFrame(textMeasurer, labelStyle, gridColor, x0, panels[0], w, panelH, tMin, tMax, "°C")
        series(pts, panels[0], panelH, tMin, tMax, ::xOf, COL_OUT) { it.outT }
        series(pts, panels[0], panelH, tMin, tMax, ::xOf, COL_IN) { it.inT }

        // Panel 2: relative Feuchte
        panelFrame(textMeasurer, labelStyle, gridColor, x0, panels[1], w, panelH, 0.0, 100.0, "%")
        series(pts, panels[1], panelH, 0.0, 100.0, ::xOf, COL_OUT) { it.outRH }
        series(pts, panels[1], panelH, 0.0, 100.0, ::xOf, COL_IN) { it.inRH }

        // Panel 3: Lüftungsstufe 0–5 und Feuchte-Stufe 1–5 als Treppen
        panelFrame(textMeasurer, labelStyle, gridColor, x0, panels[2], w, panelH, 0.0, 5.0, "Stufe")
        steps(pts, panels[2], panelH, ::xOf, COL_STUFE, fill = true) { it.stufe }
        steps(pts, panels[2], panelH, ::xOf, COL_FEUCHTE, fill = false) { it.feuchte }

        // Ablese-Linie
        selX?.let { sx ->
            val x = sx.coerceIn(x0, size.width)
            drawLine(axisColor, Offset(x, 0f), Offset(x, panels[2] + panelH), 1.5f)
        }
    }
    Text(
        "Antippen oder seitlich ziehen zeigt die Werte zu einer Uhrzeit.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Legend(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3f,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun DrawScope.panelFrame(
    tm: TextMeasurer, style: TextStyle, grid: Color,
    x0: Float, y0: Float, w: Float, h: Float, lo: Double, hi: Double, unit: String,
) {
    // drei waagerechte Linien: unten, Mitte, oben – mit Beschriftung
    listOf(lo, (lo + hi) / 2, hi).forEach { v ->
        val y = y0 + h - ((v - lo) / (hi - lo) * h).toFloat()
        drawLine(grid, Offset(x0, y), Offset(x0 + w, y), 1f)
        val txt = v.roundToInt().toString()
        val l = tm.measure(txt, style)
        drawText(l, topLeft = Offset(x0 - l.size.width - 4f, y - l.size.height / 2f))
    }
    val u = tm.measure(unit, style)
    drawText(u, topLeft = Offset(x0 + 4f, y0))
}

private fun DrawScope.series(
    pts: List<HistPoint>, y0: Float, h: Float, lo: Double, hi: Double,
    xOf: (Long) -> Float, color: Color, value: (HistPoint) -> Double?,
) {
    val path = Path()
    var open = false
    var prevT = 0L
    pts.forEach { p ->
        val v = value(p)
        // Lücke: kein Wert oder mehr als 15 min ohne Punkt
        if (v == null || (open && p.epoch - prevT > 900)) open = false
        if (v != null) {
            val x = xOf(p.epoch)
            val y = y0 + h - ((v.coerceIn(lo, hi) - lo) / (hi - lo) * h).toFloat()
            if (!open) path.moveTo(x, y) else path.lineTo(x, y)
            open = true
            prevT = p.epoch
        }
    }
    drawPath(path, color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
}

private fun DrawScope.steps(
    pts: List<HistPoint>, y0: Float, h: Float, xOf: (Long) -> Float, color: Color, fill: Boolean,
    value: (HistPoint) -> Int?,
) {
    val step = 300L
    pts.forEach { p ->
        val v = value(p) ?: return@forEach
        val x1 = xOf(p.epoch)
        val x2 = xOf(p.epoch + step)
        val y = y0 + h - (v.coerceIn(0, 5) / 5f * h)
        if (fill) {
            drawRect(color.copy(alpha = 0.25f), topLeft = Offset(x1, y), size = Size(max(x2 - x1, 1f), y0 + h - y))
            drawLine(color, Offset(x1, y), Offset(x2, y), 2.5f)
        } else {
            drawLine(color, Offset(x1, y), Offset(x2, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
        }
    }
}

// ------------------------------------------------------------------ Regeln
private fun ruleText(r: Rule, labels: Map<String, String>): String {
    val cmd = labels[r.cmd] ?: r.cmd
    return if (r.sensor == "absDiff") {
        if (r.op == ">") "Wenn Außenluft mehr als ${fmt1(r.value)} g/m³ trockener ist → $cmd"
        else "Wenn Außenluft weniger als ${fmt1(r.value)} g/m³ trockener ist → $cmd"
    } else {
        "Wenn ${Sensors.label(r.sensor)} ${if (r.op == ">") "über" else "unter"} ${fmt1(r.value)} ${Sensors.unit(r.sensor)} → $cmd"
    }
}

@Composable
private fun RulesCard(k: Klima, cmds: List<Cmd>, vm: MainViewModel) {
    val labels = cmds.associate { it.key to it.label }
    var edit by remember { mutableStateOf<Int?>(null) }   // -1 = neu
    var holdText by remember(k.holdMin) { mutableStateOf(k.holdMin.toString()) }

    Section("Automatik") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Regeln anwenden", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = k.autoEn, onCheckedChange = { vm.setAuto(it) })
        }
        Text(
            "Die Brücke prüft jede Minute. Treffen mehrere Regeln für dieselbe Einstellung zu, gilt die oberste. " +
                "Endet eine Regel, kehrt die Brücke zur vorherigen Einstellung zurück. Eine Änderung von Hand pausiert die Regel, bis ihre Bedingung endet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (k.rules.isEmpty()) {
            Text("Noch keine Regeln.", style = MaterialTheme.typography.bodyMedium)
        }
        k.rules.forEachIndexed { i, r ->
            val group = groupOfKey(r.cmd)
            val g = k.groups[group]
            val wirkt = g?.rule == i
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(ruleText(r, labels), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val stateTxt = when {
                        !r.en -> "aus"
                        Sensors.needsIndoor(r.sensor) && !k.indoor.valid -> "wartet auf Innensensor"
                        wirkt && g?.suppressed == true -> "pausiert (von Hand geändert)"
                        wirkt -> "wirkt gerade"
                        r.active -> "zutreffend, andere Regel hat Vorrang"
                        else -> "nicht zutreffend"
                    }
                    Text(
                        stateTxt, fontSize = 12.sp,
                        color = if (wirkt && g?.suppressed != true) COL_STUFE else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (wirkt) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (i > 0) vm.saveRules(k.rules.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }, enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, "nach oben")
                    }
                    IconButton(onClick = { if (i < k.rules.size - 1) vm.saveRules(k.rules.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }, enabled = i < k.rules.size - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, "nach unten")
                    }
                    IconButton(onClick = { edit = i }) { Icon(Icons.Filled.Edit, "bearbeiten") }
                }
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { edit = -1 }) { Text("Regel hinzufügen") }
            if (k.rules.isEmpty()) OutlinedButton(onClick = { vm.saveRules(exampleRules()) }) { Text("Beispiele laden") }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = holdText, onValueChange = { holdText = it.filter(Char::isDigit).take(3) },
                label = { Text("Mindestzeit zwischen Wechseln (min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { holdText.toIntOrNull()?.let { vm.setHold(it) } }, enabled = holdText.toIntOrNull() != k.holdMin) { Text("Speichern") }
        }
    }

    edit?.let { idx ->
        val orig = k.rules.getOrNull(idx)
        RuleDialog(
            initial = orig ?: Rule("aussenT", "<", 0.0, 1.0, "stufe1", true),
            isNew = orig == null,
            cmds = sortCommands(cmds).filter { groupOfKey(it.key) != "sonst" },
            onDismiss = { edit = null },
            onDelete = {
                vm.saveRules(k.rules.filterIndexed { j, _ -> j != idx })
                edit = null
            },
            onSave = { r ->
                val l = k.rules.toMutableList()
                if (orig == null) l += r else l[idx] = r
                vm.saveRules(l)
                edit = null
            }
        )
    }
}

fun groupOfKey(key: String) = when {
    key == "aus" || key.startsWith("stufe") -> "stufe"
    key.startsWith("feuchte") -> "feuchte"
    key.startsWith("modus") -> "modus"
    else -> "sonst"
}

private fun exampleRules() = listOf(
    Rule("aussenT", "<", -5.0, 1.0, "stufe1", true),        // Frost: wenig Kaltluft
    Rule("aussenT", ">", 28.0, 1.0, "stufe1", true),        // Hitze am Tag: wenig Warmluft
    Rule("aussenRH", ">", 90.0, 5.0, "stufe2", true),       // Nebel / Regen
    Rule("absDiff", ">", 3.0, 1.0, "stufe4", true),         // trockene Außenluft: kräftig entfeuchten
)

@Composable
private fun RuleDialog(
    initial: Rule, isNew: Boolean, cmds: List<Cmd>,
    onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Rule) -> Unit,
) {
    var sensor by remember { mutableStateOf(initial.sensor) }
    var op by remember { mutableStateOf(initial.op) }
    var value by remember { mutableStateOf(fmt1(initial.value).replace("–", "")) }
    var hyst by remember { mutableStateOf(fmt1(initial.hyst).replace("–", "")) }
    var cmd by remember { mutableStateOf(initial.cmd) }
    var en by remember { mutableStateOf(initial.en) }
    var sensorMenu by remember { mutableStateOf(false) }
    var cmdMenu by remember { mutableStateOf(false) }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    val valid = num(value) != null && num(hyst) != null && num(hyst)!! >= 0 && cmd.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neue Regel" else "Regel bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wenn", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { sensorMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(Sensors.label(sensor)) }
                    DropdownMenu(expanded = sensorMenu, onDismissRequest = { sensorMenu = false }) {
                        Sensors.all.forEach { s ->
                            DropdownMenuItem(text = { Text(Sensors.label(s)) }, onClick = { sensor = s; sensorMenu = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = op == ">", onClick = { op = ">" }, label = { Text(if (sensor == "absDiff") "um mehr als" else "über") })
                    FilterChip(selected = op == "<", onClick = { op = "<" }, label = { Text(if (sensor == "absDiff") "um weniger als" else "unter") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it.take(6) },
                        label = { Text("Wert (${Sensors.unit(sensor)})") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = hyst, onValueChange = { hyst = it.take(5) },
                        label = { Text("Hysterese") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (sensor == "absDiff") {
                    Text(
                        "Vergleicht die absolute Feuchte (g/m³) innen und außen. Positiv = Außenluft ist trockener, Lüften entfeuchtet. Braucht den Innensensor.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (Sensors.needsIndoor(sensor)) {
                    Text("Braucht den Innensensor am Grove-Anschluss.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Hysterese: die Regel endet erst, wenn der Wert um diesen Betrag zurückgegangen ist – so wird nicht ständig hin- und hergeschaltet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("dann", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { cmdMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(cmds.firstOrNull { it.key == cmd }?.label ?: "Befehl wählen")
                    }
                    DropdownMenu(expanded = cmdMenu, onDismissRequest = { cmdMenu = false }) {
                        cmds.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.label + if (c.sendable) "" else "  (nicht angelernt)") },
                                onClick = { cmd = c.key; cmdMenu = false }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Regel aktiv", Modifier.weight(1f))
                    Switch(checked = en, onCheckedChange = { en = it })
                }
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("Regel löschen", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(Rule(sensor, op, num(value)!!, num(hyst)!!, cmd, en)) }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

// ------------------------------------------------------------------ Ort
@Composable
private fun PlaceCard(k: Klima, vm: MainViewModel) {
    val places by vm.places.collectAsStateWithLifecycle()
    val searching by vm.placeSearching.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    Section("Ort für das Außenwetter") {
        Text(
            if (k.place.isNotEmpty()) "Aktuell: ${k.place}" + (if (k.lat != null && k.lon != null) String.format(Locale.GERMANY, " (%.3f, %.3f)", k.lat, k.lon) else "")
            else "Noch kein Ort eingestellt – ohne Ort gibt es keine Außenwerte.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = q, onValueChange = { q = it },
                label = { Text("Ort oder PLZ") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.searchPlace(q) }, enabled = q.isNotBlank() && !searching) {
                if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Suchen")
            }
        }
        places.forEach { p ->
            TextButton(onClick = { vm.choosePlace(p) }) {
                Text("${p.name}" + if (p.detail.isNotEmpty()) " – ${p.detail}" else "")
            }
        }
        Text(
            "Wetterdaten: Open-Meteo.com (CC BY 4.0). Die Brücke ruft sie alle 15 Minuten ab.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
