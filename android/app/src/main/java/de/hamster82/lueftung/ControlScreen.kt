package de.hamster82.lueftung

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ControlScreen(vm: MainViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val cmds by vm.commands.collectAsStateWithLifecycle()
    val byKey = cmds.associateBy { it.key }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val s = status
        if (s == null) {
            ConnectCard(vm, error)
            return@Column
        }

        if (cmds.isNotEmpty() && cmds.none { it.sendable }) {
            Section("Noch nichts angelernt") {
                Text(
                    "Die Tasten werden aktiv, sobald du im Reiter „Anlernen“ die Telegramme vom Bedienfeld aufgezeichnet hast.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // ---------------------------------------------------- Abgleich mit dem Bedienteil
        val pr = s.proto
        if (pr != null && pr.enabled && pr.seenKnown && !pr.inSync) {
            Section("Bedienteil zeigt etwas anderes") {
                Text(
                    "Anzeige am Bedienteil: ${pr.stufeLabel(pr.seenV)} · ${pr.feuchteLabel(pr.seenH)} · ${pr.modusLabel(pr.seenM)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Das Bedienteil bekommt Änderungen aus der App nicht mit. Wird dort getippt, übernimmt die Brücke nur die " +
                        "dort geänderte Einstellung und behält den Rest aus der App bei.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------------------------------------------------- Stufe
        Section("Lüftungsstufe") {
            StateLine(s.stufe)
            if (pr != null && pr.runStufe != null && pr.masterAgoS in 0..600) {
                Text(
                    "Lüfter melden: ${pr.stufeLabel(pr.runStufe)}" +
                        (if (pr.runDir == 1) " · Richtung A" else if (pr.runDir == 2) " · Richtung B" else "") +
                        " (vor ${pr.masterAgoS} s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            CmdRow(listOf("aus", "stufe1", "stufe2", "stufe3", "stufe4", "stufe5"), byKey, s.stufe.key, vm, short = true)
        }

        // ---------------------------------------------------- Stoßlüften
        Section("Stoßlüften") {
            if (s.boostActive) {
                val label = byKey[s.boostKey]?.label ?: s.boostKey
                Text("$label läuft noch ${s.boostRemainingS / 60}:${"%02d".format(s.boostRemainingS % 60)} min, danach zurück auf die vorige Stufe.")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.cancelBoost() }) { Text("Abbrechen") }
            } else {
                var minutes by remember { mutableIntStateOf(30) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
                    }
                }
                val boostKey = listOf("stufe5", "stufe4").firstOrNull { byKey[it]?.sendable == true }
                Button(
                    onClick = { boostKey?.let { vm.sendCmd(it, minutes) } },
                    enabled = boostKey != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (boostKey != null) "${byKey[boostKey]?.label} für $minutes min" else "Stufe V/IV noch nicht angelernt") }
            }
        }

        // ---------------------------------------------------- Feuchte / Modus
        Section("Feuchte") {
            StateLine(s.feuchte)
            Spacer(Modifier.height(8.dp))
            CmdRow(listOf("feuchte0", "feuchte1", "feuchte2", "feuchte3", "feuchte4", "feuchte5"), byKey, s.feuchte.key, vm, short = true)
        }
        Section("Betriebsart") {
            StateLine(s.modus)
            Spacer(Modifier.height(8.dp))
            CmdRow(listOf("modus1", "modus2", "modus3"), byKey, s.modus.key, vm)
        }

        val extra = cmds.filter { it.group == "sonst" && it.sendable }
        if (extra.isNotEmpty()) {
            Section("Weitere Befehle") {
                extra.chunked(3).forEach { row ->
                    CmdRow(row.map { it.key }, byKey, null, vm)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // ---------------------------------------------------- Filter
        FilterCard(s, vm)

        // ---------------------------------------------------- Info
        Text(
            buildString {
                append("Gerät ${s.ip} · FW ${s.fw}")
                if (s.rssi != 0) append(" · WLAN ${s.rssi} dBm")
                append(if (s.timeValid) " · Uhrzeit ${s.time}" else " · Uhrzeit nicht synchron – Zeitpläne inaktiv")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StateLine(g: GroupState) {
    val txt = if (g.key == null) "Zustand unbekannt (wird erkannt, sobald ein gelernter Befehl gesendet oder am Bedienfeld gewählt wird)"
    else "Aktuell: ${g.label}" + (if (g.src.isNotEmpty()) " · über ${g.src}" else "") + (if (g.time.isNotEmpty()) " · ${g.time} Uhr" else "")
    Text(txt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CmdRow(keys: List<String>, byKey: Map<String, Cmd>, current: String?, vm: MainViewModel, short: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEach { k ->
            val c = byKey[k]
            val label = when {
                short && k == "aus" -> "Aus"
                short && k.startsWith("stufe") -> listOf("I", "II", "III", "IV", "V").getOrElse(k.removePrefix("stufe").toIntOrNull()?.minus(1) ?: 0) { k }
                short && k == "feuchte0" -> "Aus"
                short && k.startsWith("feuchte") -> k.removePrefix("feuchte")
                (c?.label ?: "").let { l -> l.startsWith("W") && l.contains("rmer") } -> "WRG"
                else -> c?.label ?: k
            }
            val enabled = c?.sendable == true
            val mod = Modifier
                .weight(1f)
                .height(48.dp)
            val pad = PaddingValues(horizontal = 2.dp)
            if (k == current) {
                Button(onClick = { vm.sendCmd(k) }, enabled = enabled, modifier = mod, contentPadding = pad) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                }
            } else {
                OutlinedButton(onClick = { vm.sendCmd(k) }, enabled = enabled, modifier = mod, contentPadding = pad) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun FilterCard(s: Status, vm: MainViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Section("Filter") {
        val frac = if (s.filterIntervalH > 0) (s.filterHours / s.filterIntervalH).toFloat().coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth(),
            color = if (s.filterDue) Color(0xFFE53935) else MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (s.filterDue) "Filterwechsel fällig! (${s.filterHours.toInt()} von ${s.filterIntervalH} Betriebsstunden)"
            else "${s.filterHours.toInt()} von ${s.filterIntervalH} Betriebsstunden seit dem letzten Wechsel",
            style = MaterialTheme.typography.bodyMedium,
            color = if (s.filterDue) Color(0xFFE53935) else Color.Unspecified
        )
        Text(
            "Gezählt wird, solange die Stufe nicht „Aus“ ist. Leuchten am Bedienfeld alle Lampen, meldet das Gerät selbst einen Filtertausch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { confirm = true }) { Text("Filter gewechselt") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Filterzähler zurücksetzen?") },
            text = { Text("Nur bestätigen, wenn die Filter getauscht oder gereinigt wurden.") },
            confirmButton = { TextButton(onClick = { vm.resetFilter(); confirm = false }) { Text("Zurücksetzen") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
fun ConnectCard(vm: MainViewModel, error: String?) {
    val host by vm.host.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    var input by remember(host) { mutableStateOf(host) }

    Section("Verbindung zur Lüftungs-Brücke") {
        Text(error ?: "Nicht verbunden", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("IP-Adresse oder Name") },
            placeholder = { Text("z.B. 192.168.178.50") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.setHost(input) }) { Text("Verbinden") }
            OutlinedButton(onClick = { vm.discover() }, enabled = !searching) {
                if (searching) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                else Text("Im WLAN suchen")
            }
        }
        found.forEach { d ->
            TextButton(onClick = { vm.setHost(d.address) }) { Text("${d.name} – ${d.address}") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Neues Gerät? Mit dem WLAN „Lueftung-Setup“ (Passwort lueftung123) verbinden – die Einrichtungsseite öffnet sich, dort dein Heim-WLAN eintragen.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
