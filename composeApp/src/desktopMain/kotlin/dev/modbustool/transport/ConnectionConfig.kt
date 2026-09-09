package dev.modbustool.transport

enum class TransportKind(val label: String) {
    SERIAL("Serial RTU"),
    TCP("TCP"),
    UDP("UDP"),
}

enum class SerialParity(val label: String) {
    NONE("None"),
    EVEN("Even"),
    ODD("Odd"),
}

sealed interface ConnectionConfig {
    val responseTimeoutMillis: Int

    data class Serial(
        val portName: String,
        val baudRate: Int,
        val parity: SerialParity,
        val stopBits: Int,
        override val responseTimeoutMillis: Int,
    ) : ConnectionConfig

    data class Tcp(
        val host: String,
        val port: Int,
        val connectTimeoutMillis: Int,
        override val responseTimeoutMillis: Int,
    ) : ConnectionConfig

    data class Udp(
        val host: String,
        val port: Int,
        override val responseTimeoutMillis: Int,
    ) : ConnectionConfig
}

data class SerialPortInfo(
    val systemName: String,
    val description: String,
) {
    override fun toString(): String = if (description.isBlank() || description == systemName) systemName else "$systemName — $description"
}
