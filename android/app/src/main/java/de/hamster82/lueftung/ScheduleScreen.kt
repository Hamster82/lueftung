package de.hamster82.lueftung

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val DAY_NAMES = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

fun daysText(days: Int): String = when (days and 127) {
    127 -> "täglich"
    31 -> "Mo–Fr"
    96 -> "Sa, So"
    0 -> "nie"
    else -> DAY_NAMES.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
}

@Composable
fun ScheduleScreen(vm: MainViewModel) {
    val list by vm.schedules.collectAsStateWithLifecycle()
    val cmds by vm.commands.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val labels = cmds.associate { it.key to it.label }
    // -1 = neu, >=0 = Index in Bearbeitung, null = kein Dialog
    var editing by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    if (status?.timeValid == false) "Achtung: Die Brücke hat keine gültige Uhrzeit (kein Internet?). Zeitpläne laufen erst, wenn die Zeit synchron ist."
                    else "Zeitpläne laufen auf der Brücke selbst – das Handy muss dafür nicht eingeschaltet sein.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (list.isEmpty()) {
                item { Text("Noch keine Zeitpläne. Mit „Neu“ unten rechts anlegen.", modifier = Modifier.padding(top = 24.dp)) }
            }
            itemsIndexed(list) { i, s ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = i }
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("%02d:%02d".format(s.h, s.m), fontSize = 26.sp)
                            Text("${labels[s.cmd] ?: s.cmd} · ${daysText(s.days)}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = s.en, onCheckedChange = { on ->
                            vm.saveSchedules(list.toMutableList().also { it[i] = s.copy(en = on) })
                        })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = -1 },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Neu") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    editing?.let { idx ->
        val orig = if (idx >= 0) list.getOrNull(idx) else null
        ScheduleDialog(
            initial = orig ?: Schedule(127, 22, 0, "stufe1", true),
            cmds = sortCommands(cmds),
            isNew = orig == null,
            onDismiss = { editing = null },
            onDelete = {
                vm.saveSchedules(list.filterIndexed { j, _ -> j != idx })
                editing = null
            },
            onSave = { s ->
                val nl = list.toMutableList()
                if (orig == null) nl += s else nl[idx] = s
                vm.saveSchedules(nl.sortedWith(compareBy<Schedule>({ it.h }, { it.m })))
                editing = null
            }
        )
    }
}

@Composable
private fun ScheduleDialog(
    initial: Schedule,
    cmds: List<Cmd>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (Schedule) -> Unit,
) {
    var hh by remember { mutableStateOf("%02d".format(initial.h)) }
    var mm by remember { mutableStateOf("%02d".format(initial.m)) }
    var days by remember { mutableIntStateOf(initial.days) }
    var cmd by remember { mutableStateOf(initial.cmd) }
    var menu by remember { mutableStateOf(false) }
    val h = hh.toIntOrNull()
    val m = mm.toIntOrNull()
    val valid = h != null && h in 0..23 && m != null && m in 0..59 && days != 0 && cmd.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neuer Zeitplan" else "Zeitplan bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hh, onValueChange = { hh = it.filter(Char::isDigit).take(2) },
                        label = { Text("Std") }, singleLine = true, modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(" : ", fontSize = 22.sp)
                    OutlinedTextField(
                        value = mm, onValueChange = { mm = it.filter(Char::isDigit).take(2) },
                        label = { Text("Min") }, singleLine = true, modifier = Modifier.width(80.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Text("Tage", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_NAMES.take(4).forEachIndexed { i, n -> DayChip(n, days and (1 shl i) != 0) { days = days xor (1 shl i) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_NAMES.drop(4).forEachIndexed { j, n ->
                        val i = j + 4
                        DayChip(n, days and (1 shl i) != 0) { days = days xor (1 shl i) }
                    }
                    TextButton(onClick = { days = if (days == 127) 31 else 127 }) { Text(if (days == 127) "Mo–Fr" else "Alle") }
                }
                Text("Befehl", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(cmds.firstOrNull { it.key == cmd }?.label ?: "Auswählen")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        cmds.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.label + if (c.learned) "" else "  (nicht angelernt)") },
                                onClick = { cmd = c.key; menu = false }
                            )
                        }
                    }
                }
                if (cmds.firstOrNull { it.key == cmd }?.learned == false) {
                    Text("Dieser Befehl ist noch nicht angelernt und wird erst danach ausgeführt.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (!isNew) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDelete) { Text("Zeitplan löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(Schedule(days, h!!, m!!, cmd, initial.en)) }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun DayChip(name: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(name) })
}
