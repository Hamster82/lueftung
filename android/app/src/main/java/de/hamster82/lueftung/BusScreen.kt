package de.hamster82.lueftung

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val BAUDS = listOf(1200, 2400, 4800, 9600, 14400, 19200, 28800, 38400, 57600, 76800, 115200, 230400, 250000)
private val FORMATS = listOf("8N1", "8E1", "8O1", "8N2")

@Composable
fun BusScreen(vm: MainViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val frames by vm.frames.collectAsStateWithLifecycle()
    val onlyNew by vm.monitorOnlyNew.collectAsStateWithLifecycle()
    val paused by vm.monitorPaused.collectAsStateWithLifecycle()
    val cmds by vm.commands.collectAsStateWithLifecycle()
    val labels = cmds.associate { it.key to it.label }
    val ctx = LocalContext.current

    DisposableEffect(Unit) {
        vm.monitorActive.value = true
        onDispose { vm.monitorActive.value = false }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { DeviceCard(vm, status, error) }

        if (status != null) {
            item { BusConfigCard(vm) }

            item {
                Section("Senden (Test)") {
                    var hex by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = hex, onValueChange = { hex = it },
                            label = { Text("Hex, z.B. 01 06 00 01") }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.testFrames(listOf(hex)) }, enabled = hex.isNotBlank()) { Text("Senden") }
                    }
                }
            }

            item {
                Section("Bus-Monitor") {
                    val s = status!!
                    Text(
                        "${s.frames} Telegramme · ${s.unique} verschiedene · ${s.bytes} Byte" +
                            if (s.lastRxAgoMs >= 0) " · zuletzt vor ${s.lastRxAgoMs / 1000} s" else " · noch nichts empfangen",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = onlyNew, onCheckedChange = {
                            vm.monitorOnlyNew.value = it
                            vm.clearFrames()
                        })
                        Text(" nur neue", fontSize = 14.sp)
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = { vm.monitorPaused.value = !paused }) { Text(if (paused) "Weiter" else "Pause") }
                        TextButton(onClick = { vm.clearFrames() }) { Text("Leeren") }
                    }
                    Row {
                        TextButton(onClick = { vm.baseUrlOrNull()?.let { openUrl(ctx, "$it/api/log.txt") } }) { Text("Protokoll öffnen") }
                        TextButton(onClick = { vm.clearUnique() }) { Text("„Neu“-Liste zurücksetzen") }
                    }
                    Text(
                        "„Nur neue“ zeigt nur Telegramme, die vorher noch nie auftauchten – ideal, um zu sehen, was ein Tastendruck am Bedienfeld auslöst.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(frames.asReversed(), key = { it.seq }) { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(
                        if (f.tx) "→" else "←",
                        color = if (f.tx) Color(0xFF1E88E5) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(18.dp),
                        fontSize = 13.sp
                    )
                    Column(Modifier.weight(1f)) {
                        Text(f.hex, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        val tags = buildList {
                            if (f.isNew) add("NEU")
                            f.known?.let { add(labels[it] ?: it) }
                        }
                        if (tags.isNotEmpty()) {
                            Text(
                                tags.joinToString(" · "),
                                fontSize = 11.sp,
                                color = if (f.known != null) Color(0xFF43A047) else Color(0xFFE65100)
                            )
                        }
                    }
                    Text("${f.t / 1000}s", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(vm: MainViewModel, status: Status?, error: String?) {
    val host by vm.host.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    var input by remember(host) { mutableStateOf(host) }
    val ctx = LocalContext.current

    Section("Gerät") {
        if (status != null) {
            Text("Verbunden · Firmware ${status.fw} · ${status.ip}", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(error ?: "Nicht verbunden", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            label = { Text("IP-Adresse oder Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.setHost(input) }) { Text("Übernehmen") }
            TextButton(onClick = { vm.discover() }, enabled = !searching) { Text(if (searching) "Suche …" else "Suchen") }
        }
        found.forEach { d -> TextButton(onClick = { vm.setHost(d.address) }) { Text("${d.name} – ${d.address}") } }
        if (status != null) {
            var confirmOta by remember { mutableStateOf(false) }
            val ota = when (status.otaState) {
                "startet", "laedt" -> "Online-Update läuft … (ca. 1 min, danach Neustart)"
                "fertig" -> "Update installiert – Brücke startet neu"
                "fehler" -> "Online-Update fehlgeschlagen: ${status.otaError}"
                else -> null
            }
            if (ota != null) {
                Text(ota, style = MaterialTheme.typography.bodySmall,
                    color = if (status.otaState == "fehler") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Button(
                onClick = { confirmOta = true },
                enabled = status.otaState != "laedt" && status.otaState != "startet" && !status.ap,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Online-Update (neueste Firmware von GitHub)") }
            if (confirmOta) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmOta = false },
                    title = { Text("Firmware aktualisieren?") },
                    text = { Text("Die Brücke lädt die neueste Firmware selbst aus dem Internet, installiert sie und startet neu. Einstellungen und angelernte Befehle bleiben erhalten. Dauer ca. 1 Minute.") },
                    confirmButton = { TextButton(onClick = { confirmOta = false; vm.onlineUpdate() }) { Text("Aktualisieren") } },
                    dismissButton = { TextButton(onClick = { confirmOta = false }) { Text("Abbrechen") } }
                )
            }
            Row {
                TextButton(onClick = { vm.baseUrlOrNull()?.let { openUrl(ctx, "$it/update") } }) { Text("Datei hochladen") }
                TextButton(onClick = { vm.baseUrlOrNull()?.let { openUrl(ctx, it) } }) { Text("WLAN ändern") }
                TextButton(onClick = { vm.restartDevice() }) { Text("Neustart") }
            }
        }
    }
}

@Composable
private fun BusConfigCard(vm: MainViewModel) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val baudRes by vm.baud.collectAsStateWithLifecycle()
    val baudRunning by vm.baudRunning.collectAsStateWithLifecycle()
    val c = cfg ?: return

    var baud by remember(c) { mutableStateOf(c.baud) }
    var format by remember(c) { mutableStateOf(c.format) }
    var gap by remember(c) { mutableStateOf(c.gapChars.toString()) }
    var repeat by remember(c) { mutableStateOf(c.repeat.toString()) }
    var sendGap by remember(c) { mutableStateOf(c.sendGapMs.toString()) }
    var filterH by remember(c) { mutableStateOf(c.filterIntervalH.toString()) }
    var menu by remember { mutableStateOf(false) }

    Section("Bus-Einstellungen") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Baudrate", Modifier.width(110.dp))
            Box {
                OutlinedButton(onClick = { menu = true }) { Text("$baud") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    BAUDS.forEach { b -> DropdownMenuItem(text = { Text("$b") }, onClick = { baud = b; menu = false }) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FORMATS.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Pause (Zeichen)", gap, Modifier.weight(1f)) { gap = it }
            NumField("Wiederholen", repeat, Modifier.weight(1f)) { repeat = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Sendeabstand ms", sendGap, Modifier.weight(1f)) { sendGap = it }
            NumField("Filter alle (h)", filterH, Modifier.weight(1f)) { filterH = it }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveConfig(
                    c.copy(
                        baud = baud, format = format,
                        gapChars = gap.toIntOrNull() ?: c.gapChars,
                        repeat = repeat.toIntOrNull() ?: c.repeat,
                        sendGapMs = sendGap.toIntOrNull() ?: c.sendGapMs,
                        filterIntervalH = filterH.toIntOrNull() ?: c.filterIntervalH
                    )
                )
            }) { Text("Speichern") }
            OutlinedButton(onClick = { vm.detectBaud() }, enabled = !baudRunning) {
                if (baudRunning) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                else Text("Baudrate erkennen")
            }
        }
        baudRes?.let { r ->
            Spacer(Modifier.height(6.dp))
            if (r.baud > 0) {
                Text("Erkannt: ${r.baud} Baud (kürzester Puls ${r.minPulseUs} µs, ${r.samples} Messungen)", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { baud = r.baud }) { Text("${r.baud} übernehmen (danach Speichern)") }
            } else {
                Text(r.hint.ifEmpty { "Nichts erkannt" }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "Während der Erkennung (ca. 4 s) am Bedienfeld tippen. Stimmen Baudrate und Format, ergeben sich bei wiederholten Tastendrücken immer gleiche Telegramme.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
