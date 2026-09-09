package dev.modbustool

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.modbustool.core.ConnectionState
import dev.modbustool.core.FunctionCode
import dev.modbustool.core.RegisterFormat
import dev.modbustool.core.WordOrder
import dev.modbustool.transport.SerialParity
import dev.modbustool.transport.TransportKind
import dev.modbustool.composeapp.generated.resources.Res
import dev.modbustool.composeapp.generated.resources.*
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val AppBlue = Color(0xFF2563EB)
private val Canvas = Color(0xFFF4F6FA)
private val Sidebar = Color(0xFF182235)
private val Muted = Color(0xFF64748B)

private sealed interface AppDialog {
    data class Workspace(val workspace: WorkspaceDefinition?) : AppDialog
    data class Action(val workspaceId: String, val action: ActionDefinition?) : AppDialog
    data class Write(val workspaceId: String, val action: ActionDefinition) : AppDialog
    data class DeleteWorkspace(val workspace: WorkspaceDefinition) : AppDialog
    data class DeleteAction(val workspaceId: String, val action: ActionDefinition) : AppDialog
    data class Scan(val workspaceId: String) : AppDialog
    data object Settings : AppDialog
}

@Composable
fun ModbusToolApp(viewModel: WorkspaceViewModel) {
    val state by viewModel.state.collectAsState()
    val workspace = state.selectedWorkspace
    val settingsStore = remember { AppSettingsStore() }
    var language by remember { mutableStateOf(settingsStore.loadLanguage()) }
    remember(language) { applyAppLanguage(language) }
    var dialog by remember { mutableStateOf<AppDialog?>(null) }
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    val updateChecker = remember { UpdateChecker() }
    val scope = rememberCoroutineScope()
    key(language) {
        MaterialTheme(colors = lightColors(primary = AppBlue, background = Canvas, surface = Color.White)) {
            Row(Modifier.fillMaxSize().background(Canvas)) {
            WorkspaceSidebar(
                state = state,
                onSelect = viewModel::selectWorkspace,
                onCreate = { dialog = AppDialog.Workspace(null) },
                onEdit = { dialog = AppDialog.Workspace(it) },
                onDelete = { dialog = AppDialog.DeleteWorkspace(it) },
                onSettings = { dialog = AppDialog.Settings },
            )
            if (workspace == null) EmptyWorkspace(Modifier.weight(1f)) { dialog = AppDialog.Workspace(null) }
            else WorkspaceContent(
                modifier = Modifier.weight(1f),
                workspace = workspace,
                runtime = state.selectedRuntime,
                onConnect = { viewModel.connect(workspace.id) },
                onDisconnect = { viewModel.disconnect(workspace.id) },
                onEditWorkspace = { dialog = AppDialog.Workspace(workspace) },
                onAddAction = { dialog = AppDialog.Action(workspace.id, null) },
                onEditAction = { dialog = AppDialog.Action(workspace.id, it) },
                onDeleteAction = { dialog = AppDialog.DeleteAction(workspace.id, it) },
                onRun = { action, offset, quantity ->
                    if (action.function.isRead) viewModel.runActionRow(workspace.id, action.id, offset, quantity)
                    else dialog = AppDialog.Write(workspace.id, action)
                },
                onReadAll = { viewModel.readAll(workspace.id) },
                onPoll = { viewModel.togglePolling(workspace.id) },
                onPollEnabled = { action, enabled -> viewModel.setPollEnabled(workspace.id, action.id, enabled) },
                onScan = { dialog = AppDialog.Scan(workspace.id) },
                onClearLog = { viewModel.clearLog(workspace.id) },
            )
        }

            state.warning?.let { warning ->
                AlertDialog(onDismissRequest = viewModel::dismissWarning, title = { Text(tr(Res.string.workspace_recovery)) }, text = { Text(warning) },
                    confirmButton = { TextButton(onClick = viewModel::dismissWarning) { Text("OK") } })
            }
            when (val active = dialog) {
            is AppDialog.Workspace -> WorkspaceDialog(active.workspace, state.serialPorts, viewModel::refreshSerialPorts, onDismiss = { dialog = null }) {
                if (viewModel.saveWorkspace(it)) dialog = null
            }
            is AppDialog.Action -> {
                val owner = state.document.workspaces.firstOrNull { it.id == active.workspaceId }
                if (owner != null) ActionDialog(owner, active.action, { dialog = null }) { if (viewModel.saveAction(owner.id, it)) dialog = null }
            }
            is AppDialog.Write -> ConfirmWriteDialog(active.action, { dialog = null }) { viewModel.runAction(active.workspaceId, active.action.id); dialog = null }
            is AppDialog.DeleteWorkspace -> ConfirmDelete(stringResource(Res.string.delete_workspace_title, active.workspace.name), tr(Res.string.delete_workspace_message), { dialog = null }) {
                viewModel.deleteWorkspace(active.workspace.id); dialog = null
            }
            is AppDialog.DeleteAction -> ConfirmDelete(stringResource(Res.string.delete_action_title, active.action.name), tr(Res.string.delete_action_message), { dialog = null }) {
                viewModel.deleteAction(active.workspaceId, active.action.id); dialog = null
            }
            is AppDialog.Scan -> ScanDialog(
                workspaceId = active.workspaceId,
                defaultTimeoutMillis = state.document.workspaces.firstOrNull { it.id == active.workspaceId }?.connection?.responseTimeoutMillis ?: 1_000,
                scan = state.scan?.takeIf { it.workspaceId == active.workspaceId },
                onDismiss = { viewModel.cancelScan(); dialog = null },
                onStart = viewModel::startScan,
                onUse = viewModel::useScannedUnit,
            )
            AppDialog.Settings -> SettingsDialog(
                language = language,
                updateState = updateState,
                onDismiss = { dialog = null },
                onLanguageChange = { selected ->
                    settingsStore.saveLanguage(selected)
                    language = selected
                },
                onCheck = {
                    updateState = UpdateCheckState.Checking
                    scope.launch { updateState = updateChecker.check() }
                },
                onOpenRelease = { url ->
                    runCatching { Desktop.getDesktop().browse(URI(url)) }
                        .onFailure { updateState = UpdateCheckState.Failed(it.message ?: "Unable to open browser") }
                },
            )
                null -> Unit
            }
        }
    }
}

@Composable
private fun WorkspaceSidebar(
    state: WorkspaceState,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: (WorkspaceDefinition) -> Unit,
    onDelete: (WorkspaceDefinition) -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.width(250.dp).fillMaxHeight().background(Sidebar).padding(16.dp)) {
        Text(tr(Res.string.app_name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(tr(Res.string.workspaces), color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(tr(Res.string.new_workspace)) }
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(state.document.workspaces, key = { it.id }) { workspace ->
                val runtime = state.runtimes[workspace.id] ?: WorkspaceRuntimeState()
                val selected = workspace.id == state.document.selectedWorkspaceId
                Row(
                    Modifier.fillMaxWidth().background(if (selected) Color(0xFF2A3A55) else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onSelect(workspace.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(runtime.connectionState, runtime.isPolling)
                    Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                        Text(workspace.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        Text(sidebarStatus(runtime), color = Color(0xFF94A3B8), fontSize = 11.sp)
                    }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        Text("⋮", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { menu = true }.padding(4.dp))
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(onClick = { menu = false; onEdit(workspace) }) { Text(tr(Res.string.edit)) }
                            DropdownMenuItem(onClick = { menu = false; onDelete(workspace) }) { Text(tr(Res.string.delete)) }
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onSettings,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, Color(0xFF64748B)),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = Color.Transparent,
                contentColor = Color.White,
            ),
        ) { Text("⚙  ${tr(Res.string.app_settings)}") }
        Spacer(Modifier.height(8.dp))
        Text(tr(Res.string.saved_automatically), color = Color(0xFF94A3B8), fontSize = 10.sp)
    }
}

@Composable
private fun WorkspaceContent(
    modifier: Modifier,
    workspace: WorkspaceDefinition,
    runtime: WorkspaceRuntimeState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEditWorkspace: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (ActionDefinition) -> Unit,
    onDeleteAction: (ActionDefinition) -> Unit,
    onRun: (ActionDefinition, Int, Int) -> Unit,
    onReadAll: () -> Unit,
    onPoll: () -> Unit,
    onPollEnabled: (ActionDefinition, Boolean) -> Unit,
    onScan: () -> Unit,
    onClearLog: () -> Unit,
) {
    var logHeight by remember(workspace.id) { mutableStateOf(220.dp) }
    val density = LocalDensity.current
    Column(modifier.fillMaxHeight()) {
        Surface(elevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(workspace.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp)); StatusChip(runtime.connectionState, runtime.isPolling)
                    }
                    Text(connectionSummary(workspace.connection), color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = onEditWorkspace, enabled = runtime.connectionState == ConnectionState.DISCONNECTED) { Text(tr(Res.string.settings)) }
                Spacer(Modifier.width(8.dp))
                if (runtime.connectionState == ConnectionState.CONNECTED) OutlinedButton(onClick = onDisconnect) { Text(tr(Res.string.disconnect)) }
                else Button(onClick = onConnect, enabled = runtime.connectionState == ConnectionState.DISCONNECTED) { Text(tr(Res.string.connect)) }
            }
        }
        runtime.error?.let { Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF1F2)).padding(horizontal = 22.dp, vertical = 7.dp)) }
        Row(Modifier.fillMaxWidth().padding(18.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAddAction) { Text(tr(Res.string.add_action)) }
            Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = onReadAll) { Text(tr(Res.string.read_all)) }
            Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = onPoll) { Text(tr(if (runtime.isPolling) Res.string.stop_polling else Res.string.poll_all)) }
            Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = onScan) { Text(tr(Res.string.scan_unit_ids)) }
            Spacer(Modifier.weight(1f)); Text(runtime.status, color = Muted, fontSize = 12.sp)
        }
        ActionResults(Modifier.weight(1f), workspace, runtime, onEditAction, onDeleteAction, onRun, onPollEnabled)
        Box(
            Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFCBD5E1)).pointerInput(workspace.id) {
                detectDragGestures { change, drag ->
                    change.consume()
                    with(density) { logHeight = (logHeight - drag.y.toDp()).coerceIn(120.dp, 440.dp) }
                }
            },
        )
        LogPanel(Modifier.height(logHeight), runtime.logs, onClearLog)
    }
}

@Composable
private fun ActionResults(
    modifier: Modifier,
    workspace: WorkspaceDefinition,
    runtime: WorkspaceRuntimeState,
    onEdit: (ActionDefinition) -> Unit,
    onDelete: (ActionDefinition) -> Unit,
    onRun: (ActionDefinition, Int, Int) -> Unit,
    onPollEnabled: (ActionDefinition, Boolean) -> Unit,
) {
    Column(modifier.padding(horizontal = 18.dp)) {
        Text(tr(Res.string.results), color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (workspace.actions.isEmpty()) {
            Box(Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(tr(Res.string.no_actions), fontWeight = FontWeight.Bold); Text(tr(Res.string.no_actions_hint), color = Muted) }
            }
        } else {
            Row(Modifier.fillMaxWidth().background(Color(0xFFE8EDF5), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderCell(tr(Res.string.run), 52.dp); HeaderCell("Fnc", 64.dp)
                HeaderCell("Ref", 86.dp); HeaderCell("Raw", 1.1f); HeaderCell(tr(Res.string.value), .85f)
                HeaderCell(tr(Res.string.formatted_value), 1.15f); HeaderCell(tr(Res.string.actions), 112.dp)
            }
            LazyColumn(Modifier.fillMaxWidth().background(Color.White)) {
                workspace.actions.forEach { action ->
                    val result = runtime.results[action.id] ?: ActionResult()
                    items(flatRows(action, result), key = { "${action.id}:${it.offset}" }) { row ->
                        FlatResultRow(action, row, result.status, onEdit, onDelete, onRun, onPollEnabled)
                        Divider(color = Color(0xFFE2E8F0))
                    }
                }
            }
        }
    }
}

private data class FlatRow(val offset: Int, val quantity: Int, val address: String, val reference: String, val result: ResultRow?)

private fun flatRows(action: ActionDefinition, result: ActionResult): List<FlatRow> {
    if (!action.function.isRead) {
        val row = result.rows.firstOrNull()
        return listOf(FlatRow(0, 1, row?.address ?: action.address.toString(), row?.reference ?: referenceFor(action.function, action.address), row))
    }
    val words = if (action.function in setOf(FunctionCode.READ_HOLDING_REGISTERS, FunctionCode.READ_INPUT_REGISTERS)) action.registerFormat.wordsPerValue else 1
    return (0 until action.quantity step words).map { offset ->
        val count = minOf(words, action.quantity - offset)
        val address = if (count == 1) "${action.address + offset}" else "${action.address + offset}–${action.address + offset + count - 1}"
        val reference = if (count == 1) referenceFor(action.function, action.address + offset)
            else "${referenceFor(action.function, action.address + offset)}–${referenceFor(action.function, action.address + offset + count - 1)}"
        FlatRow(offset, count, address, reference, result.rows.firstOrNull { it.address == address })
    }
}

@Composable
private fun FlatResultRow(
    action: ActionDefinition,
    row: FlatRow,
    status: ActionRunStatus,
    onEdit: (ActionDefinition) -> Unit,
    onDelete: (ActionDefinition) -> Unit,
    onRun: (ActionDefinition, Int, Int) -> Unit,
    onPollEnabled: (ActionDefinition, Boolean) -> Unit,
) {
    val value = row.result
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { Checkbox(action.pollEnabled, { onPollEnabled(action, it) }) }
        Column(Modifier.width(64.dp).padding(horizontal = 5.dp)) {
            Text(action.function.code.toString().padStart(2, '0'), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("U${action.unitId}", color = Muted, fontSize = 10.sp)
        }
        Text(row.reference, fontSize = 11.sp, modifier = Modifier.width(86.dp).padding(horizontal = 4.dp))
        ResultCell(value?.raw ?: "—", 1.1f)
        ResultCell(value?.value ?: if (status == ActionRunStatus.RUNNING) "Reading…" else "—", .85f, statusColor(status))
        ResultCell(value?.formattedValue ?: "—", 1.15f, statusColor(status))
        Row(Modifier.width(112.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onRun(action, row.offset, row.quantity) }, enabled = action.pollEnabled && status != ActionRunStatus.RUNNING, contentPadding = PaddingValues(horizontal = 5.dp)) {
                Text(tr(if (action.function.isRead) Res.string.read else Res.string.write), fontSize = 11.sp)
            }
            Box {
                var menuOpen by remember(action.id) { mutableStateOf(false) }
                Text("⋮", fontSize = 20.sp, color = Muted, modifier = Modifier.clickable { menuOpen = true }.padding(horizontal = 10.dp, vertical = 7.dp))
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(onClick = { menuOpen = false; onEdit(action) }) { Text(tr(Res.string.edit)) }
                    DropdownMenuItem(onClick = { menuOpen = false; onDelete(action) }) { Text(tr(Res.string.delete), color = Color(0xFFDC2626)) }
                }
            }
        }
    }
}

@Composable private fun RowScope.HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) = Text(text, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Muted, modifier = Modifier.width(width).padding(horizontal = 4.dp))
@Composable private fun RowScope.HeaderCell(text: String, weight: Float) = Text(text, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Muted, modifier = Modifier.weight(weight).padding(horizontal = 4.dp))
@Composable private fun RowScope.ResultCell(text: String, weight: Float, color: Color = Color.Unspecified) = Text(text, fontSize = 11.sp, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(weight).padding(horizontal = 4.dp))

private fun referenceFor(function: FunctionCode, address: Int): String {
    val base = when (function) {
        FunctionCode.READ_COILS, FunctionCode.WRITE_SINGLE_COIL, FunctionCode.WRITE_MULTIPLE_COILS -> 1
        FunctionCode.READ_DISCRETE_INPUTS -> 10_001
        FunctionCode.READ_INPUT_REGISTERS -> 30_001
        else -> 40_001
    }
    return (base + address).toString().padStart(5, '0')
}

@Composable
private fun LogPanel(modifier: Modifier, logs: List<TrafficLogEntry>, onClear: () -> Unit) {
    Column(modifier.fillMaxWidth().background(Color(0xFF101827)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Res.string.log), color = Color(0xFFCBD5E1), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.weight(1f)); TextButton(onClick = onClear) { Text(tr(Res.string.clear), color = Color(0xFF93C5FD)) }
        }
        if (logs.isEmpty()) Text(tr(Res.string.empty_log), color = Color(0xFF64748B), fontSize = 12.sp)
        else LazyColumn { items(logs.asReversed()) { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(entry.time, color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.width(92.dp))
                Text(entry.direction, color = if (entry.direction == "TX") Color(0xFF60A5FA) else Color(0xFF34D399), fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                Text(if (entry.frame.isBlank()) entry.detail else "${entry.frame}  ${entry.detail}", color = if (entry.isError) Color(0xFFFCA5A5) else Color(0xFFD1D5DB), fontSize = 11.sp)
            }
        } }
    }
}

@Composable
private fun WorkspaceDialog(
    existing: WorkspaceDefinition?,
    ports: List<dev.modbustool.transport.SerialPortInfo>,
    refreshPorts: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (WorkspaceDefinition) -> Unit,
) {
    val initial = existing ?: WorkspaceDefinition(name = "Workspace ${System.currentTimeMillis().toString().takeLast(3)}")
    var name by remember { mutableStateOf(initial.name) }; var kind by remember { mutableStateOf(initial.connection.kind) }
    var unit by remember { mutableStateOf(initial.defaultUnitId.toString()) }; var interval by remember { mutableStateOf(initial.pollingIntervalMillis.toString()) }
    var host by remember { mutableStateOf((initial.connection as? SavedConnection.Tcp)?.host ?: (initial.connection as? SavedConnection.Udp)?.host ?: "127.0.0.1") }
    var port by remember { mutableStateOf(((initial.connection as? SavedConnection.Tcp)?.port ?: (initial.connection as? SavedConnection.Udp)?.port ?: 502).toString()) }
    var serialPort by remember { mutableStateOf((initial.connection as? SavedConnection.Serial)?.portName ?: ports.firstOrNull()?.systemName.orEmpty()) }
    var baud by remember { mutableStateOf(((initial.connection as? SavedConnection.Serial)?.baudRate ?: 9600).toString()) }
    var parity by remember { mutableStateOf((initial.connection as? SavedConnection.Serial)?.parity ?: SerialParity.NONE) }
    var stopBits by remember { mutableStateOf(((initial.connection as? SavedConnection.Serial)?.stopBits ?: 1).toString()) }
    var responseTimeout by remember { mutableStateOf(initial.connection.responseTimeoutMillis.toString()) }
    var connectTimeout by remember { mutableStateOf(((initial.connection as? SavedConnection.Tcp)?.connectTimeoutMillis ?: 1000).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr(if (existing == null) Res.string.create_workspace else Res.string.workspace_settings)) },
        text = { Column(Modifier.width(700.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(name, { name = it }, tr(Res.string.workspace_name))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(Res.string.connection), color = Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    SelectField(tr(Res.string.transport), kind.label, TransportKind.entries.map { it.label }) { selected -> kind = TransportKind.entries.first { it.label == selected } }
                    if (kind == TransportKind.SERIAL) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SelectField(tr(Res.string.serial_port), serialPort.ifBlank { tr(Res.string.no_ports) }, ports.map { it.systemName }) { serialPort = it } }
                            TextButton(refreshPorts, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(tr(Res.string.refresh), fontSize = 11.sp) }
                        }
                        Field(baud, { baud = it }, tr(Res.string.baud_rate))
                        SelectField(tr(Res.string.parity), parity.label, SerialParity.entries.map { it.label }) { selected -> parity = SerialParity.entries.first { it.label == selected } }
                        Field(stopBits, { stopBits = it }, tr(Res.string.stop_bits))
                    } else {
                        Field(host, { host = it }, tr(Res.string.host))
                        Field(port, { port = it }, tr(Res.string.port))
                        if (kind == TransportKind.TCP) Field(connectTimeout, { connectTimeout = it }, tr(Res.string.connect_timeout))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(Res.string.workspace_defaults), color = Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Field(responseTimeout, { responseTimeout = it }, tr(Res.string.response_timeout))
                    Field(unit, { unit = it }, tr(Res.string.default_unit_id))
                    Field(interval, { interval = it }, tr(Res.string.polling_interval))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp) }
        } },
        confirmButton = { Button(onClick = {
            runCatching {
                val timeout = responseTimeout.toInt()
                val connection: SavedConnection = when (kind) {
                    TransportKind.SERIAL -> SavedConnection.Serial(serialPort, baud.toInt(), parity, stopBits.toInt(), timeout)
                    TransportKind.TCP -> SavedConnection.Tcp(host.trim(), port.toInt(), connectTimeout.toInt(), timeout)
                    TransportKind.UDP -> SavedConnection.Udp(host.trim(), port.toInt(), timeout)
                }
                initial.copy(name = name.trim(), connection = connection, defaultUnitId = unit.toInt(), pollingIntervalMillis = interval.toInt())
            }.onSuccess(onSave).onFailure { error = it.message ?: "Check the numeric fields." }
        }) { Text(tr(Res.string.save)) } }, dismissButton = { TextButton(onDismiss) { Text(tr(Res.string.cancel)) } })
}

@Composable
private fun ActionDialog(workspace: WorkspaceDefinition, existing: ActionDefinition?, onDismiss: () -> Unit, onSave: (ActionDefinition) -> Unit) {
    val initial = existing ?: ActionDefinition(name = "Read holding registers", unitId = workspace.defaultUnitId)
    var name by remember { mutableStateOf(initial.name) }; var function by remember { mutableStateOf(initial.function) }; var unit by remember { mutableStateOf(initial.unitId.toString()) }
    var address by remember { mutableStateOf(initial.address.toString()) }; var quantity by remember { mutableStateOf(initial.quantity.toString()) }; var values by remember { mutableStateOf(initial.values) }
    var coil by remember { mutableStateOf(initial.singleCoil) }; var format by remember { mutableStateOf(initial.registerFormat) }; var order by remember { mutableStateOf(initial.wordOrder) }; var poll by remember { mutableStateOf(initial.pollEnabled) }
    var formula by remember { mutableStateOf(initial.valueFormula) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr(if (existing == null) Res.string.add_action_title else Res.string.edit_action_title)) }, text = {
        ScrollableDialogColumn {
            Field(name, { name = it }, tr(Res.string.action_name)); SelectField(tr(Res.string.function), function.displayName, FunctionCode.entries.map { it.displayName }) { selected -> function = FunctionCode.entries.first { it.displayName == selected }; if (existing == null) name = function.displayName }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.weight(1f)) { Field(unit, { unit = it }, "Unit ID") }; Box(Modifier.weight(1f)) { Field(address, { address = it }, tr(Res.string.start_address)) } }
            if (function.isRead) { Field(quantity, { quantity = it }, tr(Res.string.quantity)) }
            else when (function) {
                FunctionCode.WRITE_SINGLE_COIL -> Row(verticalAlignment = Alignment.CenterVertically) { Switch(coil, { coil = it }); Text(if (coil) "ON" else "OFF", modifier = Modifier.padding(start = 8.dp)) }
                else -> Field(values, { values = it }, tr(if (function == FunctionCode.WRITE_MULTIPLE_COILS) Res.string.values_coils else Res.string.values_registers))
            }
            if (function in setOf(FunctionCode.READ_HOLDING_REGISTERS, FunctionCode.READ_INPUT_REGISTERS, FunctionCode.WRITE_SINGLE_REGISTER, FunctionCode.WRITE_MULTIPLE_REGISTERS)) {
                SelectField(tr(Res.string.register_format), format.label, RegisterFormat.entries.map { it.label }) { selected -> format = RegisterFormat.entries.first { it.label == selected } }
                if (format.wordsPerValue == 2) SelectField(tr(Res.string.word_order), order.label, WordOrder.entries.map { it.label }) { selected -> order = WordOrder.entries.first { it.label == selected } }
            }
            Field(formula, { formula = it }, tr(Res.string.formula_label))
            Text(tr(Res.string.formula_help), color = Muted, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(poll, { poll = it }); Text(tr(Res.string.enable_action)) }
            error?.let { Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp) }
        }
    }, confirmButton = { Button(onClick = { runCatching {
        if (formula.isNotBlank()) ValueFormula.evaluate(formula, 1.0).getOrThrow()
        initial.copy(name = name.trim(), function = function, unitId = unit.toInt(), address = address.toInt(), quantity = quantity.toIntOrNull() ?: 1, values = values, singleCoil = coil, registerFormat = format, wordOrder = order, pollEnabled = poll, valueFormula = formula.trim())
    }.onSuccess(onSave).onFailure { error = it.message ?: "Check the numeric fields." } }) { Text(tr(Res.string.save)) } }, dismissButton = { TextButton(onDismiss) { Text(tr(Res.string.cancel)) } })
}

@Composable
private fun ScanDialog(
    workspaceId: String,
    defaultTimeoutMillis: Int,
    scan: UnitScanState?,
    onDismiss: () -> Unit,
    onStart: (String, ScanRequest) -> Unit,
    onUse: (String, Int) -> Unit,
) {
    var start by remember(workspaceId) { mutableStateOf("1") }
    var end by remember(workspaceId) { mutableStateOf("247") }
    var address by remember(workspaceId) { mutableStateOf("0") }
    var quantity by remember(workspaceId) { mutableStateOf("1") }
    var timeout by remember(workspaceId) { mutableStateOf(defaultTimeoutMillis.coerceIn(50, 5_000).toString()) }
    var function by remember(workspaceId) { mutableStateOf(FunctionCode.READ_HOLDING_REGISTERS) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr(Res.string.scan_unit_ids)) }, text = { Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(Res.string.scan_description), color = Muted, fontSize = 12.sp)
        Text(tr(Res.string.scan_gateway_hint), color = Color(0xFFB45309), fontSize = 11.sp)
        if (scan?.isRunning != true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.weight(1f)) { Field(start, { start = it }, tr(Res.string.start_id)) }; Box(Modifier.weight(1f)) { Field(end, { end = it }, tr(Res.string.end_id)) } }
            SelectField(tr(Res.string.probe_function), function.displayName, FunctionCode.entries.filter { it.isRead }.map { it.displayName }) { selected -> function = FunctionCode.entries.first { it.displayName == selected } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.weight(1f)) { Field(address, { address = it }, tr(Res.string.address)) }; Box(Modifier.weight(1f)) { Field(quantity, { quantity = it }, tr(Res.string.quantity)) }; Box(Modifier.weight(1f)) { Field(timeout, { timeout = it }, tr(Res.string.timeout_ms)) } }
        }
        scan?.let { current ->
            Text(current.message, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = ((current.currentId - current.startId + 1f) / (current.endId - current.startId + 1f)).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
            if (current.foundIds.isNotEmpty()) {
                Text(tr(Res.string.found_units), color = Muted, fontSize = 11.sp)
                current.foundIds.take(32).chunked(8).forEach { units ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { units.forEach { id -> OutlinedButton(onClick = { onUse(workspaceId, id) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(id.toString()) } } }
                }
            }
        }
    } }, confirmButton = { if (scan?.isRunning != true) Button(onClick = { runCatching { ScanRequest(start.toInt(), end.toInt(), function, address.toInt(), quantity.toInt(), timeout.toInt()) }.onSuccess { onStart(workspaceId, it) } }) { Text(tr(if (scan == null) Res.string.start_scan else Res.string.scan_again)) } }, dismissButton = { TextButton(onDismiss) { Text(tr(if (scan?.isRunning == true) Res.string.cancel else Res.string.close)) } })
}

@Composable
private fun SettingsDialog(
    language: AppLanguage,
    updateState: UpdateCheckState,
    onDismiss: () -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(tr(Res.string.app_settings)) },
    text = {
        Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(Res.string.application), color = Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(tr(Res.string.app_name), fontWeight = FontWeight.SemiBold)
            Text(stringResource(Res.string.current_version, BuildConfig.VERSION), color = Muted, fontSize = 12.sp)
            SelectField(
                label = tr(Res.string.language),
                value = language.displayName,
                options = AppLanguage.entries.map { it.displayName },
                onSelect = { selected ->
                    AppLanguage.entries.firstOrNull { it.displayName == selected }?.let(onLanguageChange)
                },
            )
            Text(tr(Res.string.language_selection_note), color = Muted, fontSize = 11.sp)
            Divider(Modifier.padding(vertical = 4.dp))
            Text(tr(Res.string.updates), color = Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            when (updateState) {
                UpdateCheckState.Idle -> Text(tr(Res.string.update_idle), fontSize = 13.sp)
                UpdateCheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                    Text(tr(Res.string.checking_for_updates), fontSize = 13.sp)
                }
                is UpdateCheckState.UpToDate -> Text(
                    stringResource(Res.string.up_to_date, updateState.version),
                    color = Color(0xFF059669),
                    fontSize = 13.sp,
                )
                is UpdateCheckState.Available -> Text(
                    stringResource(Res.string.update_available, updateState.version),
                    color = AppBlue,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                UpdateCheckState.NoPublishedReleases -> Text(tr(Res.string.no_published_releases), color = Muted, fontSize = 13.sp)
                is UpdateCheckState.Failed -> Text(
                    stringResource(Res.string.update_check_failed, updateState.message),
                    color = MaterialTheme.colors.error,
                    fontSize = 13.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCheck, enabled = updateState != UpdateCheckState.Checking) {
                    Text(tr(if (updateState == UpdateCheckState.Idle) Res.string.check_for_updates else Res.string.check_again))
                }
                val releaseUrl = (updateState as? UpdateCheckState.Available)?.releaseUrl ?: BuildConfig.RELEASES_URL
                OutlinedButton(onClick = { onOpenRelease(releaseUrl) }) {
                    Text(tr(if (updateState is UpdateCheckState.Available) Res.string.download_update else Res.string.view_releases))
                }
            }
            Text(tr(Res.string.manual_install_note), color = Muted, fontSize = 11.sp)
        }
    },
    confirmButton = { TextButton(onDismiss) { Text(tr(Res.string.close)) } },
)

@Composable private fun ConfirmWriteDialog(action: ActionDefinition, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(tr(Res.string.confirm_write)) }, text = { Text(stringResource(Res.string.confirm_write_message, action.function.displayName, action.unitId.toString(), action.address.toString())) }, confirmButton = { Button(onConfirm, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFDC2626), contentColor = Color.White)) { Text(tr(Res.string.write)) } }, dismissButton = { TextButton(onDismiss) { Text(tr(Res.string.cancel)) } })
@Composable private fun ConfirmDelete(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { Button(onConfirm, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFDC2626), contentColor = Color.White)) { Text(tr(Res.string.delete)) } }, dismissButton = { TextButton(onDismiss) { Text(tr(Res.string.cancel)) } })

@Composable private fun Field(value: String, onChange: (String) -> Unit, label: String) = OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())

@Composable
private fun ScrollableDialogColumn(content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    Row(Modifier.width(500.dp).heightIn(max = 560.dp)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.fillMaxHeight().width(10.dp),
        )
    }
}

@Composable private fun SelectField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            trailingIcon = { Text("⌄") },
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only text field consumes pointer input, so this overlay owns the
        // click target and makes the entire desktop control open consistently.
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }, modifier = Modifier.fillMaxWidth(.45f)) { options.forEach { option -> DropdownMenuItem({ open = false; onSelect(option) }) { Text(option) } } }
    }
}

@Composable private fun StatusDot(state: ConnectionState, polling: Boolean) = Box(Modifier.size(9.dp).background(if (polling) Color(0xFF60A5FA) else if (state == ConnectionState.CONNECTED) Color(0xFF34D399) else Color(0xFF64748B), RoundedCornerShape(50)))
@Composable private fun StatusChip(state: ConnectionState, polling: Boolean) { val text = when { polling -> tr(Res.string.polling); state == ConnectionState.CONNECTED -> tr(Res.string.connected); state == ConnectionState.CONNECTING -> tr(Res.string.connecting); else -> tr(Res.string.disconnected) }; val color = if (polling) Color(0xFF2563EB) else if (state == ConnectionState.CONNECTED) Color(0xFF059669) else Muted; Text(text.uppercase(), color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.background(color.copy(alpha = .1f), RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp)) }
@Composable private fun EmptyWorkspace(modifier: Modifier, onCreate: () -> Unit) = Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(tr(Res.string.create_workspace_empty), fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(tr(Res.string.workspace_empty_hint), color = Muted); Spacer(Modifier.height(12.dp)); Button(onCreate) { Text(tr(Res.string.new_workspace)) } } }
@Composable private fun sidebarStatus(runtime: WorkspaceRuntimeState) = when { runtime.isPolling -> tr(Res.string.polling); runtime.connectionState == ConnectionState.CONNECTED -> tr(Res.string.connected); else -> tr(Res.string.disconnected) }
@Composable private fun tr(resource: StringResource): String = stringResource(resource)
private fun connectionSummary(connection: SavedConnection) = when (connection) { is SavedConnection.Serial -> "${connection.kind.label} · ${connection.portName} · ${connection.baudRate} baud"; is SavedConnection.Tcp -> "TCP · ${connection.host}:${connection.port}"; is SavedConnection.Udp -> "UDP · ${connection.host}:${connection.port}" }
private fun statusColor(status: ActionRunStatus) = when (status) { ActionRunStatus.SUCCESS -> Color(0xFF059669); ActionRunStatus.ERROR -> Color(0xFFDC2626); ActionRunStatus.RUNNING -> AppBlue; ActionRunStatus.IDLE -> Muted }
