package dev.modbustool

import dev.modbustool.core.ConnectionState
import dev.modbustool.core.FailureKind
import dev.modbustool.core.FunctionCode
import dev.modbustool.core.ModbusClient
import dev.modbustool.core.ModbusOutcome
import dev.modbustool.core.ModbusRequest
import dev.modbustool.core.ModbusResponse
import dev.modbustool.core.ModbusTransport
import dev.modbustool.core.ReadBitsRequest
import dev.modbustool.core.ReadRegistersRequest
import dev.modbustool.core.RegisterValueCodec
import dev.modbustool.core.WriteMultipleCoilsRequest
import dev.modbustool.core.WriteMultipleRegistersRequest
import dev.modbustool.core.WriteSingleCoilRequest
import dev.modbustool.core.WriteSingleRegisterRequest
import dev.modbustool.core.toHexString
import dev.modbustool.transport.ConnectionConfig
import dev.modbustool.transport.SerialModbusTransport
import dev.modbustool.transport.SerialPortInfo
import dev.modbustool.transport.TransportFactory
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ResultRow(
    val address: String,
    val reference: String,
    val raw: String,
    val value: String,
    val formattedValue: String = value,
)

data class TrafficLogEntry(
    val time: String,
    val direction: String,
    val frame: String,
    val detail: String,
    val isError: Boolean = false,
)

enum class ActionRunStatus { IDLE, RUNNING, SUCCESS, ERROR }

data class ActionResult(
    val status: ActionRunStatus = ActionRunStatus.IDLE,
    val summary: String = "Not read yet",
    val rows: List<ResultRow> = emptyList(),
    val updatedAt: String? = null,
    val elapsedMillis: Long? = null,
)

data class WorkspaceRuntimeState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isPolling: Boolean = false,
    val isBusy: Boolean = false,
    val status: String = "Disconnected",
    val error: String? = null,
    val results: Map<String, ActionResult> = emptyMap(),
    val logs: List<TrafficLogEntry> = emptyList(),
)

data class UnitScanState(
    val workspaceId: String,
    val startId: Int,
    val endId: Int,
    val token: Long = System.nanoTime(),
    val currentId: Int = startId,
    val foundIds: List<Int> = emptyList(),
    val isRunning: Boolean = true,
    val message: String = "Starting scan…",
)

data class WorkspaceState(
    val document: WorkspaceDocument,
    val runtimes: Map<String, WorkspaceRuntimeState> = emptyMap(),
    val serialPorts: List<SerialPortInfo> = emptyList(),
    val scan: UnitScanState? = null,
    val warning: String? = null,
) {
    val selectedWorkspace: WorkspaceDefinition?
        get() = document.workspaces.firstOrNull { it.id == document.selectedWorkspaceId }
    val selectedRuntime: WorkspaceRuntimeState
        get() = runtimes[selectedWorkspace?.id] ?: WorkspaceRuntimeState()
}

data class ScanRequest(
    val startId: Int = 1,
    val endId: Int = 247,
    val function: FunctionCode = FunctionCode.READ_HOLDING_REGISTERS,
    val address: Int = 0,
    val quantity: Int = 1,
    val timeoutMillis: Int = 1_000,
)

class WorkspaceViewModel(
    private val store: WorkspaceStore = JsonWorkspaceStore(),
    private val transportFactory: (ConnectionConfig) -> ModbusTransport = TransportFactory::create,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serialPortProvider: () -> List<SerialPortInfo> = SerialModbusTransport::availablePorts,
) {
    private data class Session(
        val connection: SavedConnection,
        val transport: ModbusTransport,
        val client: ModbusClient,
        val requestMutex: Mutex = Mutex(),
        val manualPending: AtomicInteger = AtomicInteger(),
        var pollingJob: Job? = null,
    )

    private val loaded = store.load().let { result -> result.copy(document = flattenReadRanges(result.document)) }
    private val mutableState = MutableStateFlow(
        WorkspaceState(
            document = loaded.document,
            runtimes = loaded.document.workspaces.associate { it.id to WorkspaceRuntimeState() },
            warning = loaded.warning,
        ),
    )
    val state: StateFlow<WorkspaceState> = mutableState.asStateFlow()
    private val sessions = mutableMapOf<String, Session>()
    private var scanJob: Job? = null

    init {
        refreshSerialPorts()
        scope.launch(ioDispatcher) { runCatching { store.save(loaded.document) } }
    }

    fun selectWorkspace(id: String) = changeDocument { it.copy(selectedWorkspaceId = id) }

    fun saveWorkspace(workspace: WorkspaceDefinition): Boolean {
        validateWorkspace(workspace).exceptionOrNull()?.let { showError(workspace.id, it.message ?: "Invalid workspace"); return false }
        val existing = currentWorkspace(workspace.id)
        if (existing != null && existing.connection != workspace.connection && runtime(workspace.id).connectionState != ConnectionState.DISCONNECTED) {
            showError(workspace.id, "Disconnect before changing connection settings")
            return false
        }
        changeDocument { document ->
            val workspaces = if (existing == null) document.workspaces + workspace else document.workspaces.map { if (it.id == workspace.id) workspace else it }
            document.copy(selectedWorkspaceId = workspace.id, workspaces = workspaces)
        }
        mutableState.update { value -> value.copy(runtimes = value.runtimes + (workspace.id to (value.runtimes[workspace.id] ?: WorkspaceRuntimeState()))) }
        if (existing?.connection != workspace.connection) sessions.remove(workspace.id)
        return true
    }

    fun deleteWorkspace(id: String) {
        disconnect(id)
        sessions.remove(id)
        changeDocument { document ->
            val remaining = document.workspaces.filterNot { it.id == id }.ifEmpty { listOf(WorkspaceDefinition()) }
            document.copy(workspaces = remaining, selectedWorkspaceId = document.selectedWorkspaceId.takeIf { selected -> remaining.any { it.id == selected } } ?: remaining.first().id)
        }
        mutableState.update { it.copy(runtimes = it.runtimes - id) }
    }

    fun saveAction(workspaceId: String, action: ActionDefinition): Boolean {
        validateAction(action).exceptionOrNull()?.let { showError(workspaceId, it.message ?: "Invalid action"); return false }
        updateWorkspace(workspaceId) { workspace ->
            val exists = workspace.actions.any { it.id == action.id }
            val rows = splitReadAction(action)
            workspace.copy(actions = if (exists) workspace.actions.flatMap { if (it.id == action.id) rows else listOf(it) } else workspace.actions + rows)
        }
        return true
    }

    fun deleteAction(workspaceId: String, actionId: String) {
        updateWorkspace(workspaceId) { it.copy(actions = it.actions.filterNot { action -> action.id == actionId }) }
        mutableState.update { value ->
            val current = runtime(workspaceId)
            value.copy(runtimes = value.runtimes + (workspaceId to current.copy(results = current.results - actionId)))
        }
    }

    fun setPollEnabled(workspaceId: String, actionId: String, enabled: Boolean) = updateWorkspace(workspaceId) { workspace ->
        workspace.copy(actions = workspace.actions.map { if (it.id == actionId) it.copy(pollEnabled = enabled) else it })
    }

    fun connect(workspaceId: String) {
        val workspace = currentWorkspace(workspaceId) ?: return
        if (runtime(workspaceId).connectionState != ConnectionState.DISCONNECTED) return
        if (workspace.connection is SavedConnection.Serial) {
            val conflict = mutableState.value.document.workspaces.firstOrNull { other ->
                other.id != workspaceId && other.connection is SavedConnection.Serial &&
                    other.connection.portName == workspace.connection.portName && runtime(other.id).connectionState != ConnectionState.DISCONNECTED
            }
            if (conflict != null) { showError(workspaceId, "${workspace.connection.portName} is already used by ${conflict.name}"); return }
        }
        scope.launch {
            updateRuntime(workspaceId) { it.copy(connectionState = ConnectionState.CONNECTING, isBusy = true, status = "Connecting…", error = null) }
            try {
                val transport = transportFactory(workspace.connection.toTransportConfig())
                transport.connect()
                sessions[workspaceId] = Session(workspace.connection, transport, ModbusClient(transport))
                updateRuntime(workspaceId) { it.copy(connectionState = ConnectionState.CONNECTED, isBusy = false, status = "Connected via ${workspace.connection.kind.label}") }
            } catch (error: Throwable) {
                sessions.remove(workspaceId)
                updateRuntime(workspaceId) { it.copy(connectionState = ConnectionState.DISCONNECTED, isBusy = false, status = "Disconnected", error = error.message ?: "Connection failed") }
            }
        }
    }

    fun disconnect(workspaceId: String) {
        val session = sessions[workspaceId]
        session?.pollingJob?.cancel()
        if (mutableState.value.scan?.workspaceId == workspaceId) cancelScan()
        scope.launch {
            updateRuntime(workspaceId) { it.copy(isPolling = false, isBusy = true, status = "Disconnecting…") }
            runCatching { session?.transport?.disconnect() }
            sessions.remove(workspaceId)
            updateRuntime(workspaceId) { it.copy(connectionState = ConnectionState.DISCONNECTED, isPolling = false, isBusy = false, status = "Disconnected") }
        }
    }

    fun runAction(workspaceId: String, actionId: String) {
        val action = currentWorkspace(workspaceId)?.actions?.firstOrNull { it.id == actionId } ?: return
        val session = sessions[workspaceId] ?: run { showError(workspaceId, "Connect this workspace first"); return }
        scope.launch {
            session.manualPending.incrementAndGet()
            try { session.requestMutex.withLock { perform(workspaceId, action, session) } }
            finally { session.manualPending.decrementAndGet() }
        }
    }

    fun runActionRow(workspaceId: String, actionId: String, offset: Int, quantity: Int) {
        val action = currentWorkspace(workspaceId)?.actions?.firstOrNull { it.id == actionId } ?: return
        if (!action.function.isRead) { runAction(workspaceId, actionId); return }
        val session = sessions[workspaceId] ?: run { showError(workspaceId, "Connect this workspace first"); return }
        val rowAction = action.copy(address = action.address + offset, quantity = quantity)
        scope.launch {
            session.manualPending.incrementAndGet()
            try { session.requestMutex.withLock { perform(workspaceId, rowAction, session, mergeRows = true) } }
            finally { session.manualPending.decrementAndGet() }
        }
    }

    fun readAll(workspaceId: String) {
        val actions = currentWorkspace(workspaceId)?.actions?.filter { it.function.isRead && it.pollEnabled }.orEmpty()
        val session = sessions[workspaceId] ?: run { showError(workspaceId, "Connect this workspace first"); return }
        if (actions.isEmpty()) { showError(workspaceId, "Add at least one read action"); return }
        scope.launch {
            session.manualPending.incrementAndGet()
            try { session.requestMutex.withLock { actions.forEach { perform(workspaceId, it, session) } } }
            finally { session.manualPending.decrementAndGet() }
        }
    }

    fun togglePolling(workspaceId: String) {
        val session = sessions[workspaceId] ?: run { showError(workspaceId, "Connect this workspace first"); return }
        if (session.pollingJob?.isActive == true) {
            session.pollingJob?.cancel(); session.pollingJob = null
            updateRuntime(workspaceId) { it.copy(isPolling = false, status = "Polling stopped") }
            return
        }
        val workspace = currentWorkspace(workspaceId) ?: return
        if (workspace.actions.none { it.function.isRead && it.pollEnabled }) { showError(workspaceId, "Enable polling on at least one read action"); return }
        updateRuntime(workspaceId) { it.copy(isPolling = true, status = "Polling every ${workspace.pollingIntervalMillis} ms", error = null) }
        session.pollingJob = scope.launch {
            while (isActive && session.transport.connectionState.value == ConnectionState.CONNECTED) {
                val latest = currentWorkspace(workspaceId) ?: break
                for (action in latest.actions.filter { it.function.isRead && it.pollEnabled }) {
                    while (session.manualPending.get() > 0 && isActive) delay(10)
                    session.requestMutex.withLock { perform(workspaceId, action, session) }
                    if (!isActive || session.transport.connectionState.value != ConnectionState.CONNECTED) break
                }
                delay(latest.pollingIntervalMillis.toLong())
            }
            updateRuntime(workspaceId) { it.copy(isPolling = false, connectionState = session.transport.connectionState.value, status = "Polling stopped") }
        }
    }

    fun startScan(workspaceId: String, request: ScanRequest) {
        val session = sessions[workspaceId] ?: run { showError(workspaceId, "Connect this workspace before scanning"); return }
        val validation = runCatching {
            require(request.startId in 1..247 && request.endId in request.startId..247) { "Scan range must be within 1..247" }
            require(request.function.isRead) { "Scan function must be a read function" }
            require(request.address in 0..65_535 && request.quantity > 0) { "Invalid scan address or quantity" }
            require(request.timeoutMillis in 50..5_000) { "Scan timeout must be 50..5000 ms" }
        }
        validation.onFailure { showError(workspaceId, it.message ?: "Invalid scan") }.getOrElse { return }
        scanJob?.cancel()
        val scanState = UnitScanState(workspaceId, request.startId, request.endId)
        mutableState.update { it.copy(scan = scanState) }
        scanJob = scope.launch {
            session.manualPending.incrementAndGet()
            val found = mutableListOf<Int>()
            try {
                session.requestMutex.withLock {
                    for (unit in request.startId..request.endId) {
                        mutableState.update { value ->
                            value.copy(scan = value.scan?.takeIf { it.token == scanState.token }?.copy(currentId = unit, foundIds = found.toList(), message = "Scanning unit $unit…") ?: value.scan)
                        }
                        val probe = if (request.function in setOf(FunctionCode.READ_COILS, FunctionCode.READ_DISCRETE_INPUTS))
                            ReadBitsRequest(request.function, request.address, request.quantity)
                        else ReadRegistersRequest(request.function, request.address, request.quantity)
                        when (session.client.execute(unit, probe, request.timeoutMillis)) {
                            is ModbusOutcome.Success, is ModbusOutcome.DeviceException -> found += unit
                            is ModbusOutcome.Failure -> Unit
                        }
                        if (!isActive) break
                    }
                }
            } finally {
                session.manualPending.decrementAndGet()
                mutableState.update { value ->
                    value.copy(scan = value.scan?.takeIf { it.token == scanState.token }?.copy(foundIds = found, isRunning = false, message = "Found ${found.size} unit${if (found.size == 1) "" else "s"}") ?: value.scan)
                }
            }
        }
    }

    fun cancelScan() { scanJob?.cancel(); scanJob = null; mutableState.update { it.copy(scan = null) } }

    fun useScannedUnit(workspaceId: String, unitId: Int) = updateWorkspace(workspaceId) { it.copy(defaultUnitId = unitId) }

    fun clearLog(workspaceId: String) = updateRuntime(workspaceId) { it.copy(logs = emptyList()) }
    fun dismissWarning() = mutableState.update { it.copy(warning = null) }

    fun refreshSerialPorts() {
        scope.launch {
            val ports = withContext(ioDispatcher) { runCatching(serialPortProvider).getOrDefault(emptyList()) }
            mutableState.update { it.copy(serialPorts = ports) }
        }
    }

    fun shutdown(onComplete: () -> Unit) {
        scanJob?.cancel()
        sessions.values.forEach { it.pollingJob?.cancel() }
        store.save(mutableState.value.document)
        scope.launch {
            sessions.values.forEach { runCatching { it.transport.disconnect() } }
            onComplete()
        }
    }

    private suspend fun perform(workspaceId: String, action: ActionDefinition, session: Session, mergeRows: Boolean = false) {
        val request = buildRequest(action).getOrElse { showError(workspaceId, it.message ?: "Invalid action"); return }
        val previousRows = runtime(workspaceId).results[action.id]?.rows.orEmpty()
        updateResult(workspaceId, action.id, ActionResult(ActionRunStatus.RUNNING, "Running…", if (mergeRows) previousRows else emptyList()))
        when (val outcome = session.client.execute(action.unitId, request)) {
            is ModbusOutcome.Success -> {
                addExchangeLogs(workspaceId, outcome.exchange.requestFrame, outcome.exchange.responseFrame, "${outcome.exchange.elapsedMillis} ms")
                val newRows = responseRows(action, request, outcome.response)
                val rows = if (mergeRows) {
                    val replaced = newRows.map { it.reference }.toSet()
                    (previousRows.filterNot { it.reference in replaced } + newRows).sortedBy { it.address.substringBefore('–').toIntOrNull() }
                } else newRows
                updateResult(workspaceId, action.id, ActionResult(
                    ActionRunStatus.SUCCESS, "${rows.size} value${if (rows.size == 1) "" else "s"} · ${outcome.exchange.elapsedMillis} ms",
                    rows, now(), outcome.exchange.elapsedMillis,
                ))
                updateRuntime(workspaceId) { it.copy(status = "${action.name} completed", error = null) }
            }
            is ModbusOutcome.DeviceException -> {
                addExchangeLogs(workspaceId, outcome.exchange.requestFrame, outcome.exchange.responseFrame, "Exception ${outcome.code}: ${outcome.description}", true)
                updateResult(workspaceId, action.id, ActionResult(ActionRunStatus.ERROR, "Exception ${outcome.code}: ${outcome.description}", updatedAt = now()))
                updateRuntime(workspaceId) { it.copy(status = "Device exception", error = outcome.description) }
            }
            is ModbusOutcome.Failure -> {
                outcome.requestFrame?.let { addLog(workspaceId, "TX", it, "Request") }
                outcome.responseFrame?.let { addLog(workspaceId, "RX", it, outcome.message, true) }
                if (outcome.requestFrame == null && outcome.responseFrame == null) addMessageLog(workspaceId, outcome.message)
                updateResult(workspaceId, action.id, ActionResult(ActionRunStatus.ERROR, outcome.message, updatedAt = now()))
                updateRuntime(workspaceId) { it.copy(
                    connectionState = session.transport.connectionState.value,
                    status = if (outcome.kind == FailureKind.TIMEOUT) "Response timed out" else "Request failed", error = outcome.message,
                ) }
            }
        }
    }

    private fun buildRequest(action: ActionDefinition): Result<ModbusRequest> = runCatching {
        validateAction(action).getOrThrow()
        when (action.function) {
            FunctionCode.READ_COILS, FunctionCode.READ_DISCRETE_INPUTS -> ReadBitsRequest(action.function, action.address, action.quantity)
            FunctionCode.READ_HOLDING_REGISTERS, FunctionCode.READ_INPUT_REGISTERS -> ReadRegistersRequest(action.function, action.address, action.quantity)
            FunctionCode.WRITE_SINGLE_COIL -> WriteSingleCoilRequest(action.address, action.singleCoil)
            FunctionCode.WRITE_SINGLE_REGISTER -> {
                require(action.registerFormat.wordsPerValue == 1) { "Function 06 requires a 16-bit format" }
                WriteSingleRegisterRequest(action.address, parseRegisters(action).single())
            }
            FunctionCode.WRITE_MULTIPLE_COILS -> WriteMultipleCoilsRequest(action.address, parseCoils(action.values))
            FunctionCode.WRITE_MULTIPLE_REGISTERS -> WriteMultipleRegistersRequest(action.address, parseRegisters(action))
        }
    }

    private fun validateWorkspace(workspace: WorkspaceDefinition): Result<Unit> = runCatching {
        require(workspace.name.isNotBlank()) { "Workspace name is required" }
        require(workspace.defaultUnitId in 1..247) { "Default unit ID must be 1..247" }
        require(workspace.pollingIntervalMillis in 100..60_000) { "Polling interval must be 100..60000 ms" }
        require(workspace.connection.responseTimeoutMillis in 50..60_000) { "Response timeout must be 50..60000 ms" }
        when (val connection = workspace.connection) {
            is SavedConnection.Serial -> {
                require(connection.portName.isNotBlank()) { "Select a serial port" }
                require(connection.baudRate in 1..4_000_000 && connection.stopBits in 1..2) { "Invalid serial settings" }
            }
            is SavedConnection.Tcp -> {
                require(connection.host.isNotBlank() && connection.port in 1..65_535) { "Enter a valid TCP host and port" }
                require(connection.connectTimeoutMillis in 100..60_000) { "Connect timeout must be 100..60000 ms" }
            }
            is SavedConnection.Udp -> require(connection.host.isNotBlank() && connection.port in 1..65_535) { "Enter a valid UDP host and port" }
        }
    }

    private fun validateAction(action: ActionDefinition): Result<Unit> = runCatching {
        require(action.name.isNotBlank()) { "Action name is required" }
        require(action.unitId in 0..255) { "Unit ID must be 0..255" }
        require(action.address in 0..65_535) { "Address must be 0..65535" }
        if (action.function.isRead) require(action.quantity in 1..2_000) { "Quantity must be 1..2000" }
        if (action.valueFormula.isNotBlank()) ValueFormula.evaluate(action.valueFormula, 1.0).getOrThrow()
    }

    private fun parseRegisters(action: ActionDefinition): List<Int> = RegisterValueCodec.encode(
        action.values.split(',', ';', '\n'), action.registerFormat, action.wordOrder,
    ).getOrElse { error(it.message ?: "Invalid register values") }

    private fun parseCoils(input: String): List<Boolean> {
        val values = input.split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty)
        require(values.isNotEmpty()) { "Enter at least one coil value" }
        return values.map { when (it.lowercase()) { "1", "true", "on" -> true; "0", "false", "off" -> false; else -> error("'$it' is not a coil value") } }
    }

    private fun responseRows(action: ActionDefinition, request: ModbusRequest, response: ModbusResponse): List<ResultRow> = when (response) {
        is ModbusResponse.Bits -> response.values.mapIndexed { index, value ->
            val display = if (value) "ON" else "OFF"
            row(action.function, request.address + index, 1, if (value) "1" else "0", display, formatValue(action, if (value) 1.0 else 0.0, display))
        }
        is ModbusResponse.Registers -> RegisterValueCodec.decode(response.values, action.registerFormat, action.wordOrder).map { decoded ->
            val numeric = decoded.displayValue.toDoubleOrNull() ?: decoded.raw.firstOrNull()?.toDouble()
            row(action.function, request.address + decoded.offset, decoded.registerCount,
                decoded.raw.joinToString(" ") { "0x${it.toString(16).uppercase().padStart(4, '0')}" }, decoded.displayValue,
                numeric?.let { formatValue(action, it, decoded.displayValue) } ?: decoded.displayValue)
        }
        is ModbusResponse.WriteAcknowledgement -> listOf(row(action.function, response.address, 1, response.quantityOrValue.toString(), "Write acknowledged", "Write acknowledged"))
    }

    private fun row(function: FunctionCode, address: Int, count: Int, raw: String, value: String, formattedValue: String): ResultRow {
        val end = address + count - 1
        return ResultRow(if (count == 1) "$address" else "$address–$end", if (count == 1) reference(function, address) else "${reference(function, address)}–${reference(function, end)}", raw, value, formattedValue)
    }

    private fun formatValue(action: ActionDefinition, numeric: Double, fallback: String): String =
        if (action.valueFormula.isBlank()) fallback else ValueFormula.evaluate(action.valueFormula, numeric).getOrElse { "Formula error" }

    private fun reference(function: FunctionCode, address: Int): String {
        val base = when (function) {
            FunctionCode.READ_COILS, FunctionCode.WRITE_SINGLE_COIL, FunctionCode.WRITE_MULTIPLE_COILS -> 1
            FunctionCode.READ_DISCRETE_INPUTS -> 10_001
            FunctionCode.READ_INPUT_REGISTERS -> 30_001
            else -> 40_001
        }
        return (base + address).toString().padStart(5, '0')
    }

    private fun responseCount(response: ModbusResponse): String = when (response) {
        is ModbusResponse.Bits -> "${response.values.size} bits"
        is ModbusResponse.Registers -> "${response.values.size} registers"
        is ModbusResponse.WriteAcknowledgement -> "Write acknowledged"
    }

    private fun updateWorkspace(id: String, transform: (WorkspaceDefinition) -> WorkspaceDefinition) = changeDocument { document ->
        document.copy(workspaces = document.workspaces.map { if (it.id == id) transform(it) else it })
    }

    private fun changeDocument(transform: (WorkspaceDocument) -> WorkspaceDocument) {
        mutableState.update { it.copy(document = transform(it.document), warning = null) }
        val document = mutableState.value.document
        scope.launch(ioDispatcher) { runCatching { store.save(document) } }
    }

    private fun currentWorkspace(id: String): WorkspaceDefinition? = mutableState.value.document.workspaces.firstOrNull { it.id == id }
    private fun runtime(id: String): WorkspaceRuntimeState = mutableState.value.runtimes[id] ?: WorkspaceRuntimeState()
    private fun updateRuntime(id: String, transform: (WorkspaceRuntimeState) -> WorkspaceRuntimeState) =
        mutableState.update { value -> value.copy(runtimes = value.runtimes + (id to transform(value.runtimes[id] ?: WorkspaceRuntimeState()))) }
    private fun updateResult(workspaceId: String, actionId: String, result: ActionResult) = updateRuntime(workspaceId) { it.copy(results = it.results + (actionId to result)) }
    private fun showError(workspaceId: String, message: String) = updateRuntime(workspaceId) { it.copy(error = message, status = message) }

    private fun addExchangeLogs(id: String, tx: ByteArray, rx: ByteArray, detail: String, error: Boolean = false) {
        addLog(id, "TX", tx, "Request"); addLog(id, "RX", rx, detail, error)
    }
    private fun addLog(id: String, direction: String, frame: ByteArray, detail: String, error: Boolean = false) =
        appendLog(id, TrafficLogEntry(now(), direction, frame.toHexString(), detail, error))
    private fun addMessageLog(id: String, message: String) = appendLog(id, TrafficLogEntry(now(), "—", "", message, true))
    private fun appendLog(id: String, entry: TrafficLogEntry) = updateRuntime(id) { it.copy(logs = (it.logs + entry).takeLast(1_000)) }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private fun now(): String = LocalTime.now().format(TIME_FORMAT)
    }
}

private fun flattenReadRanges(document: WorkspaceDocument): WorkspaceDocument = document.copy(
    workspaces = document.workspaces.map { workspace ->
        workspace.copy(actions = workspace.actions.flatMap(::splitReadAction))
    },
)

private fun splitReadAction(action: ActionDefinition): List<ActionDefinition> {
    if (!action.function.isRead) return listOf(action)
    val words = if (action.function in setOf(FunctionCode.READ_HOLDING_REGISTERS, FunctionCode.READ_INPUT_REGISTERS)) {
        action.registerFormat.wordsPerValue
    } else 1
    if (action.quantity <= words) return listOf(action)
    return (0 until action.quantity step words).mapIndexed { index, offset ->
        action.copy(
            id = if (index == 0) action.id else UUID.randomUUID().toString(),
            address = action.address + offset,
            quantity = minOf(words, action.quantity - offset),
        )
    }
}
