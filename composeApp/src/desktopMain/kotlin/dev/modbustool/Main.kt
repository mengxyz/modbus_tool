package dev.modbustool

import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val viewModel = remember { WorkspaceViewModel() }
    Window(
        onCloseRequest = { viewModel.shutdown(::exitApplication) },
        title = "Modbus Tool",
        state = rememberWindowState(
            size = DpSize(1180.dp, 820.dp),
            position = WindowPosition(Alignment.Center),
        ),
    ) {
        ModbusToolApp(viewModel)
    }
}
