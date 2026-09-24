package dev.modbustool

import dev.modbustool.core.FunctionCode
import dev.modbustool.core.RegisterFormat
import dev.modbustool.core.WordOrder
import dev.modbustool.transport.ConnectionConfig
import dev.modbustool.transport.SerialParity
import dev.modbustool.transport.TransportKind
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WorkspaceDocument(
    val schemaVersion: Int = 1,
    val selectedWorkspaceId: String? = null,
    val workspaces: List<WorkspaceDefinition> = emptyList(),
)

@Serializable
data class WorkspaceDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Workspace 1",
    val connection: SavedConnection = SavedConnection.Tcp(),
    val defaultUnitId: Int = 1,
    val pollingIntervalMillis: Int = 1_000,
    val actions: List<ActionDefinition> = emptyList(),
)

@Serializable
data class ActionDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Read holding registers",
    val function: FunctionCode = FunctionCode.READ_HOLDING_REGISTERS,
    val unitId: Int = 1,
    val address: Int = 0,
    val quantity: Int = 1,
    val values: String = "0",
    val singleCoil: Boolean = false,
    val registerFormat: RegisterFormat = RegisterFormat.UNSIGNED_16,
    val wordOrder: WordOrder = WordOrder.HIGH_WORD_FIRST,
    val pollEnabled: Boolean = true,
    val valueFormula: String = "",
    val remark: String = "",
)

@Serializable
sealed interface SavedConnection {
    val responseTimeoutMillis: Int
    val kind: TransportKind

    @Serializable @SerialName("serial")
    data class Serial(
        val portName: String = "",
        val baudRate: Int = 9_600,
        val parity: SerialParity = SerialParity.NONE,
        val stopBits: Int = 1,
        override val responseTimeoutMillis: Int = 1_000,
    ) : SavedConnection { override val kind = TransportKind.SERIAL }

    @Serializable @SerialName("tcp")
    data class Tcp(
        val host: String = "127.0.0.1",
        val port: Int = 502,
        val connectTimeoutMillis: Int = 1_000,
        override val responseTimeoutMillis: Int = 1_000,
    ) : SavedConnection { override val kind = TransportKind.TCP }

    @Serializable @SerialName("udp")
    data class Udp(
        val host: String = "127.0.0.1",
        val port: Int = 502,
        override val responseTimeoutMillis: Int = 1_000,
    ) : SavedConnection { override val kind = TransportKind.UDP }
}

fun SavedConnection.toTransportConfig(): ConnectionConfig = when (this) {
    is SavedConnection.Serial -> ConnectionConfig.Serial(portName, baudRate, parity, stopBits, responseTimeoutMillis)
    is SavedConnection.Tcp -> ConnectionConfig.Tcp(host, port, connectTimeoutMillis, responseTimeoutMillis)
    is SavedConnection.Udp -> ConnectionConfig.Udp(host, port, responseTimeoutMillis)
}

data class WorkspaceLoad(val document: WorkspaceDocument, val warning: String? = null)

interface WorkspaceStore {
    fun load(): WorkspaceLoad
    fun save(document: WorkspaceDocument)
}

class JsonWorkspaceStore(
    private val path: Path = defaultWorkspacePath(),
    private val legacy: Preferences = Preferences.userRoot().node("dev/modbustool/workspace"),
) : WorkspaceStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): WorkspaceLoad {
        if (!Files.exists(path)) return WorkspaceLoad(migrateLegacy() ?: defaultDocument())
        return runCatching {
            WorkspaceLoad(normalize(json.decodeFromString<WorkspaceDocument>(Files.readString(path))))
        }.getOrElse { error ->
            val backup = path.resolveSibling("workspaces.corrupt-${System.currentTimeMillis()}.json")
            runCatching { Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING) }
            WorkspaceLoad(defaultDocument(), "Invalid workspace data was moved to ${backup.fileName}: ${error.message}")
        }
    }

    override fun save(document: WorkspaceDocument) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(normalize(document)))
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun migrateLegacy(): WorkspaceDocument? {
        if (legacy.keys().isEmpty()) return null
        val unit = legacy.get("unitId", "1").toIntOrNull()?.coerceIn(1, 247) ?: 1
        val timeout = legacy.get("responseTimeout", "1000").toIntOrNull()?.coerceIn(50, 60_000) ?: 1_000
        val connection = when (enumValue(legacy.get("transportKind", "TCP"), TransportKind.TCP)) {
            TransportKind.SERIAL -> SavedConnection.Serial(
                legacy.get("serialPort", ""), legacy.get("baudRate", "9600").toIntOrNull() ?: 9_600,
                enumValue(legacy.get("parity", "NONE"), SerialParity.NONE), legacy.get("stopBits", "1").toIntOrNull() ?: 1, timeout,
            )
            TransportKind.TCP -> SavedConnection.Tcp(
                legacy.get("host", "127.0.0.1"), legacy.get("networkPort", "502").toIntOrNull() ?: 502,
                legacy.get("connectTimeout", "1000").toIntOrNull() ?: 1_000, timeout,
            )
            TransportKind.UDP -> SavedConnection.Udp(
                legacy.get("host", "127.0.0.1"), legacy.get("networkPort", "502").toIntOrNull() ?: 502, timeout,
            )
        }
        val function = enumValue(legacy.get("function", FunctionCode.READ_HOLDING_REGISTERS.name), FunctionCode.READ_HOLDING_REGISTERS)
        val action = ActionDefinition(
            name = function.displayName, function = function, unitId = unit,
            address = legacy.get("address", "0").toIntOrNull()?.coerceIn(0, 65_535) ?: 0,
            quantity = legacy.get("quantity", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1,
            values = legacy.get("values", "0"), singleCoil = legacy.getBoolean("singleCoil", false),
            registerFormat = enumValue(legacy.get("registerFormat", "UNSIGNED_16"), RegisterFormat.UNSIGNED_16),
            wordOrder = enumValue(legacy.get("wordOrder", "HIGH_WORD_FIRST"), WordOrder.HIGH_WORD_FIRST), pollEnabled = function.isRead,
        )
        val workspace = WorkspaceDefinition(
            connection = connection, defaultUnitId = unit,
            pollingIntervalMillis = legacy.get("pollingInterval", "1000").toIntOrNull()?.coerceIn(100, 60_000) ?: 1_000,
            actions = listOf(action),
        )
        return WorkspaceDocument(selectedWorkspaceId = workspace.id, workspaces = listOf(workspace)).also(::save)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}

fun defaultDocument(): WorkspaceDocument {
    val workspace = WorkspaceDefinition()
    return WorkspaceDocument(selectedWorkspaceId = workspace.id, workspaces = listOf(workspace))
}

private fun normalize(document: WorkspaceDocument): WorkspaceDocument {
    val workspaces = document.workspaces.ifEmpty { defaultDocument().workspaces }
    val selected = document.selectedWorkspaceId?.takeIf { id -> workspaces.any { it.id == id } } ?: workspaces.first().id
    return document.copy(schemaVersion = 1, selectedWorkspaceId = selected, workspaces = workspaces)
}

private fun defaultWorkspacePath(): Path {
    val home = Path.of(System.getProperty("user.home"))
    return when {
        System.getProperty("os.name").startsWith("Mac", true) -> home.resolve("Library/Application Support/Modbus Tool/workspaces.json")
        System.getProperty("os.name").startsWith("Windows", true) ->
            Path.of(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString()).resolve("Modbus Tool/workspaces.json")
        else -> home.resolve(".modbus-tool/workspaces.json")
    }
}
