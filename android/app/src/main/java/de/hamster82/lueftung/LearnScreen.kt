package de.hamster82.lueftung

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LearnScreen(vm: MainViewModel) {
    val cmds by vm.commands.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val learn by vm.learn.collectAsStateWithLifecycle()
    var editKey by remember { mutableStateOf<String?>(null) }
    var newCmd by remember { mutableStateOf(false) }
    var deleteKey by remember { mutableStateOf<String?>(null) }

    if (status == null) {
        Column(Modifier.fillMaxSize().padding(12.dp)) { ConnectCard(vm, error) }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Section("So funktioniert das Anlernen") {
                Text(
                    "1. Am Bedienfeld eine andere Stufe einstellen als die, die du anlernen willst.\n" +
                        "2. Hier beim gewünschten Befehl auf „Anlernen“ tippen.\n" +
                        "3. Innerhalb von 10 s am Bedienfeld genau diesen Befehl wählen.\n" +
                        "4. Die neu aufgetauchten Telegramme werden angezeigt – speichern.\n" +
                        "5. Bedienfeld umstellen und mit „Testen“ prüfen, ob die Lüftung zurückwechselt.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Erscheint „identisch mit …“, sendet das Bedienfeld nur einen Tastendruck statt der Zielstufe. " +
                        "Dann das Bus-Protokoll (Reiter Bus → Protokoll öffnen) teilen, dann wird das Protokoll gezielt ausgewertet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(sortCommands(cmds), key = { it.key }) { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (c.learned) "${c.frames.size} Telegramm(e) · ${c.key}" else "nicht angelernt · ${c.key}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (c.learned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { vm.startLearn(c.key) }, enabled = learn == null) { Text("Anlernen") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.testFrames(c.frames) }, enabled = c.learned) { Text("Testen") }
                        TextButton(onClick = { editKey = c.key }) { Text("Bearbeiten") }
                        TextButton(onClick = { deleteKey = c.key }, enabled = c.learned || c.group == "sonst") {
                            Text("Löschen")
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { newCmd = true }, modifier = Modifier.fillMaxWidth()) { Text("Eigenen Befehl hinzufügen") }
        }
    }

    learn?.let { l -> LearnDialog(l, cmds, vm) }

    editKey?.let { k ->
        val c = cmds.firstOrNull { it.key == k }
        if (c != null) EditDialog(c, onDismiss = { editKey = null }) { label, frames ->
            vm.saveCommand(k, label, frames)
            editKey = null
        }
    }

    if (newCmd) {
        NewCmdDialog(onDismiss = { newCmd = false }) { key, label ->
            vm.saveCommand(key, label, emptyList())
            newCmd = false
        }
    }

    deleteKey?.let { k ->
        val c = cmds.firstOrNull { it.key == k }
        AlertDialog(
            onDismissRequest = { deleteKey = null },
            title = { Text("„${c?.label ?: k}“ löschen?") },
            text = {
                Text(
                    if (c?.group == "sonst") "Der eigene Befehl wird entfernt."
                    else "Die gelernten Telegramme werden entfernt, der Platz bleibt erhalten."
                )
            },
            confirmButton = { TextButton(onClick = { vm.deleteCommand(k); deleteKey = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleteKey = null }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun LearnDialog(l: LearnState, cmds: List<Cmd>, vm: MainViewModel) {
    val cmd = cmds.firstOrNull { it.key == l.key }
    val label = cmd?.label ?: l.key
    val selected = remember(l.key) { mutableStateListOf<String>() }
    var initialized by remember(l.key) { mutableStateOf(false) }

    // nach Ende des Lernfensters: Vorauswahl = alles ohne Dublette
    LaunchedEffect(l.active, l.candidates) {
        if (!l.active && !initialized) {
            selected.clear()
            selected.addAll(l.candidates.filter { it.duplicateOf == null }.map { it.hex })
            initialized = true
        }
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Anlernen: $label") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (l.active) {
                    Text("Jetzt am Bedienfeld „$label“ wählen …", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (l.remainingS.coerceAtLeast(0) / 10f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("noch ${l.remainingS.coerceAtLeast(0)} s · ${l.candidates.size} neue Telegramme", style = MaterialTheme.typography.bodySmall)
                } else if (l.candidates.isEmpty()) {
                    Text(
                        "Keine neuen Telegramme erkannt.\n\nMögliche Ursachen: Baudrate/Format falsch (Reiter Bus → Baudrate erkennen), " +
                            "A und B vertauscht, oder am Bedienfeld war bereits genau dieser Wert eingestellt."
                    )
                } else {
                    Text("Neu aufgetauchte Telegramme – das erste ausgewählte wird auch zur Zustandserkennung verwendet:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    l.candidates.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.hex in selected, onCheckedChange = { on ->
                                if (on) selected.add(c.hex) else selected.remove(c.hex)
                            })
                            Column {
                                Text(c.hex, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                if (c.duplicateOf != null) {
                                    val other = cmds.firstOrNull { it.key == c.duplicateOf }?.label ?: c.duplicateOf
                                    Text("identisch mit „$other“", color = Color(0xFFE65100), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (l.active) {
                TextButton(onClick = { vm.stopLearn() }) { Text("Fertig") }
            } else {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        // Reihenfolge wie empfangen beibehalten
                        val frames = l.candidates.map { it.hex }.filter { it in selected }
                        vm.saveCommand(l.key, label, frames)
                    }
                ) { Text("Speichern") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (l.active) vm.stopLearn()
                vm.closeLearn()
            }) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun EditDialog(c: Cmd, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var label by remember { mutableStateOf(c.label) }
    var frames by remember { mutableStateOf(c.frames.joinToString("\n")) }
    val lines = frames.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val valid = label.isNotBlank() && lines.all { l -> l.replace(" ", "").let { it.length % 2 == 0 && it.all { ch -> ch.isDigit() || ch.uppercaseChar() in 'A'..'F' } } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Befehl bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Bezeichnung") }, singleLine = true)
                OutlinedTextField(
                    value = frames,
                    onValueChange = { frames = it },
                    label = { Text("Telegramme (Hex, eins pro Zeile)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3
                )
                if (!valid) Text("Ungültiger Hex-Wert", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(label.trim(), lines) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun NewCmdDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    val key = "x_" + label.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .filter { it.isLetterOrDigit() }.take(20)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eigener Befehl") },
        text = {
            Column {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Bezeichnung, z.B. Nachtmodus") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                Text("Danach mit „Anlernen“ das Telegramm aufzeichnen.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = key.length > 2, onClick = { onSave(key, label.trim()) }) { Text("Anlegen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
