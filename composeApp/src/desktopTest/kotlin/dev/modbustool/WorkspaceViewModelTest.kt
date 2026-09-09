package dev.modbustool

import dev.modbustool.core.ConnectionState
import dev.modbustool.core.FunctionCode
import dev.modbustool.core.ModbusTransport
import dev.modbustool.core.TransportExchange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    @Test
    fun rangeRowsHaveIndependentEnabledState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val range = ActionDefinition(id = "range", quantity = 2, pollEnabled = true)
        val workspace = WorkspaceDefinition(actions = listOf(range))
        val viewModel = WorkspaceViewModel(
            MemoryStore(WorkspaceDocument(selectedWorkspaceId = workspace.id, workspaces = listOf(workspace))),
            { FakeTransport() }, scope, dispatcher,
        ) { emptyList() }

        val rows = viewModel.state.value.selectedWorkspace!!.actions
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.id }.distinct().size)

        viewModel.setPollEnabled(workspace.id, rows.first().id, false)
        val updated = viewModel.state.value.selectedWorkspace!!.actions
        assertFalse(updated[0].pollEnabled)
        assertTrue(updated[1].pollEnabled)
        scope.cancel()
    }

    @Test
    fun actionIsAddedAndItsResultStaysWithWorkspace() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val viewModel = WorkspaceViewModel(MemoryStore(), { transport }, scope, dispatcher) { emptyList() }
        val workspace = viewModel.state.value.selectedWorkspace!!
        val action = ActionDefinition(name = "Temperatures", function = FunctionCode.READ_HOLDING_REGISTERS, unitId = 1, address = 10)

        viewModel.saveAction(workspace.id, action)
        viewModel.connect(workspace.id)
        advanceUntilIdle()
        viewModel.runAction(workspace.id, action.id)
        advanceUntilIdle()

        val runtime = viewModel.state.value.runtimes.getValue(workspace.id)
        assertEquals(ConnectionState.CONNECTED, runtime.connectionState)
        assertEquals("42", runtime.results.getValue(action.id).rows.single().value)
        assertEquals("40011", runtime.results.getValue(action.id).rows.single().reference)
        assertEquals(2, runtime.logs.size)
        scope.cancel()
    }

    @Test
    fun pollAllRunsOnlyEnabledReadActions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val initial = WorkspaceDefinition(pollingIntervalMillis = 100, actions = listOf(
            ActionDefinition(id = "enabled", pollEnabled = true),
            ActionDefinition(id = "disabled", pollEnabled = false),
        ))
        val viewModel = WorkspaceViewModel(MemoryStore(WorkspaceDocument(selectedWorkspaceId = initial.id, workspaces = listOf(initial))), { transport }, scope, dispatcher) { emptyList() }

        viewModel.connect(initial.id)
        advanceUntilIdle()
        viewModel.togglePolling(initial.id)
        advanceTimeBy(350)
        assertTrue(viewModel.state.value.runtimes.getValue(initial.id).isPolling)
        assertTrue(transport.exchangeCount >= 3)
        assertFalse(viewModel.state.value.runtimes.getValue(initial.id).results.containsKey("disabled"))

        viewModel.togglePolling(initial.id)
        assertFalse(viewModel.state.value.runtimes.getValue(initial.id).isPolling)
        scope.cancel()
    }
}

private class MemoryStore(initial: WorkspaceDocument = defaultDocument()) : WorkspaceStore {
    private var document = initial
    override fun load() = WorkspaceLoad(document)
    override fun save(document: WorkspaceDocument) { this.document = document }
}

private class FakeTransport : ModbusTransport {
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = mutableState
    var exchangeCount = 0

    override suspend fun connect() { mutableState.value = ConnectionState.CONNECTED }
    override suspend fun transact(unitId: Int, requestPdu: ByteArray, timeoutMillis: Int?): TransportExchange {
        exchangeCount++
        return TransportExchange(requestPdu, byteArrayOf(3, 2, 0, 42), byteArrayOf(3, 2, 0, 42), 2)
    }
    override suspend fun disconnect() { mutableState.value = ConnectionState.DISCONNECTED }
}
