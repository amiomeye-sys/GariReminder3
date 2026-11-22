package com.gari.reminder

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DailyWorker.schedule(this)
        setContent { MaterialTheme { Screen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val list by dao.observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Vehicle?>(null) }
    var prefill by remember { mutableStateOf<Entry?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var showTpl by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { if (msg.isNotEmpty()) { snack.showSnackbar(msg); msg = "" } }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        msg = if (it) "SMS permission peyechi" else "SMS permission dite hobe, noyto SMS jabe na"
    }
    LaunchedEffect(Unit) { perm.launch(Manifest.permission.SEND_SMS) }

    suspend fun saveEntries(entries: List<Entry>): Int {
        var n = 0
        for (e in entries) {
            val exp = e.exp ?: continue
            if (e.regNo.isBlank()) continue
            val old = dao.byReg(e.regNo)
            dao.upsert(
                Vehicle(
                    id = old?.id ?: 0, regNo = e.regNo,
                    clientName = e.name.ifBlank { old?.clientName ?: "" },
                    phone = e.phone.ifBlank { old?.phone ?: "" },
                    issueDate = e.issue?.toString(), expDate = exp.toString(),
                    sentForExp = if (old?.expDate == exp.toString()) old.sentForExp else null
                )
            )
            n++
        }
        return n
    }

    fun handle(entries: List<Entry>) {
        if (entries.isEmpty()) { msg = "Kichu pawa jayni"; return }
        if (entries.size == 1) { prefill = entries[0]; editing = null; showForm = true; return }
        scope.launch { msg = "${saveEntries(entries)} ti gari save holo (${entries.size} er moddhe)" }
    }

    val pickSheet = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = Importer.fileName(ctx, uri).lowercase()
                if (name.endsWith(".docx")) handle(Importer.fromDocx(ctx, uri))
                else {
                    val n = saveEntries(Importer.fromSheet(ctx, uri, name))
                    msg = "$n ti gari import holo"
                }
            } catch (e: Exception) { msg = "File porte parini: ${e.message}" }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try { handle(Importer.fromImage(ctx, uri)) }
            catch (e: Exception) { msg = "Photo porte parini: ${e.message}" }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = { Text("PUC Reminder") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; prefill = null; showForm = true }) { Text("+") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pickSheet.launch("*/*")
                }) { Text("Excel/Word") }
                OutlinedButton(onClick = { pickPhoto.launch("image/*") }) { Text("Photo") }
                OutlinedButton(onClick = { showTpl = true }) { Text("Message") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch { msg = "${DailyWorker.checkAndSend(ctx)} ta SMS pathano holo" }
            }) { Text("Ekhoni check kore SMS pathao") }
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { v ->
                    val left = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(v.expDate)).toInt()
                    val color = when {
                        left < 0 -> Color(0xFFC62828)
                        left <= Prefs.daysBefore(ctx) -> Color(0xFFEF6C00)
                        else -> Color(0xFF2E7D32)
                    }
                    Card(onClick = { editing = v; prefill = null; showForm = true }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.regNo, style = MaterialTheme.typography.titleMedium)
                                Text(v.clientName.ifBlank { "-" } + "  " + v.phone.ifBlank { "(phone nei)" })
                                Text("Last date: " + LocalDate.parse(v.expDate).format(DateUtil.out))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (left < 0) "${-left} din age sesh" else "$left din baki", color = color)
                                if (v.sentForExp == v.expDate) Text("SMS gese", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForm) FormDialog(
        initial = editing, prefill = prefill,
        onDismiss = { showForm = false },
        onDelete = { v -> scope.launch { dao.delete(v); showForm = false } },
        onSave = { e ->
            scope.launch {
                val n = saveEntries(listOf(e))
                msg = if (n == 1) "Save holo" else "Gari no ar last date dite hobe"
                if (n == 1) showForm = false
            }
        }
    )

    if (showTpl) {
        var t by remember { mutableStateOf(Prefs.template(ctx)) }
        AlertDialog(
            onDismissRequest = { showTpl = false },
            title = { Text("SMS template") },
            text = {
                Column {
                    Text("Use korun: {naam}  {gari_no}  {last_date}", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(t, { t = it }, minLines = 4)
                }
            },
            confirmButton = { TextButton({ Prefs.setTemplate(ctx, t); showTpl = false }) { Text("Save") } },
            dismissButton = { TextButton({ t = Prefs.DEFAULT_TEMPLATE }) { Text("Default") } }
        )
    }
}

@Composable
fun FormDialog(
    initial: Vehicle?, prefill: Entry?,
    onDismiss: () -> Unit, onDelete: (Vehicle) -> Unit, onSave: (Entry) -> Unit
) {
    val f = DateUtil.out
    var reg by remember { mutableStateOf(initial?.regNo ?: prefill?.regNo ?: "") }
    var name by remember { mutableStateOf(initial?.clientName ?: prefill?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: prefill?.phone ?: "") }
    var issue by remember {
        mutableStateOf(initial?.issueDate?.let { LocalDate.parse(it).format(f) } ?: prefill?.issue?.format(f) ?: "")
    }
    var exp by remember {
        mutableStateOf(initial?.expDate?.let { LocalDate.parse(it).format(f) } ?: prefill?.exp?.format(f) ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Notun gari" else "Gari edit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(reg, { reg = it }, label = { Text("Gari No (WB74AB1234)") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Client er naam") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(issue, { issue = it }, label = { Text("Issue date (dd/mm/yyyy)") }, singleLine = true)
                OutlinedTextField(exp, { exp = it }, label = { Text("Last date (dd/mm/yyyy)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                onSave(
                    Entry(
                        regNo = Importer.cleanReg(reg), name = name.trim(), phone = phone.trim(),
                        issue = DateUtil.parseOne(issue), exp = DateUtil.parseOne(exp)
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton({ onDelete(initial) }) { Text("Delete") }
                TextButton(onDismiss) { Text("Bondho") }
            }
        }
    )
}
