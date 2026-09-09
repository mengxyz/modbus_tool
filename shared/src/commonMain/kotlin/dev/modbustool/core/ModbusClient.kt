package dev.modbustool.core

import kotlinx.coroutines.CancellationException

class ModbusClient(
    private val transport: ModbusTransport,
) {
    suspend fun execute(unitId: Int, request: ModbusRequest, timeoutMillis: Int? = null): ModbusOutcome {
        val unitRange = if (transport is RtuTransportMarker) 1..247 else 0..255
        if (unitId !in unitRange) {
            return ModbusOutcome.Failure(FailureKind.VALIDATION, "Unit ID must be in ${unitRange.first}..${unitRange.last}")
        }
        ModbusPduCodec.validate(request)?.let {
            return ModbusOutcome.Failure(FailureKind.VALIDATION, it)
        }

        var completedExchange: TransportExchange? = null
        return try {
            val exchange = transport.transact(unitId, ModbusPduCodec.encodeRequest(request), timeoutMillis)
            completedExchange = exchange
            when (val decoded = ModbusPduCodec.decodeResponse(request, exchange.responsePdu)) {
                is DecodedPdu.Response -> ModbusOutcome.Success(decoded.value, exchange)
                is DecodedPdu.Exception -> ModbusOutcome.DeviceException(
                    request.function,
                    decoded.code,
                    decoded.description,
                    exchange,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModbusFailure) {
            ModbusOutcome.Failure(
                failure.kind,
                failure.message ?: "Modbus operation failed",
                failure.requestFrame ?: completedExchange?.requestFrame,
                failure.responseFrame ?: completedExchange?.responseFrame,
            )
        } catch (error: Throwable) {
            ModbusOutcome.Failure(FailureKind.UNKNOWN, error.message ?: error::class.simpleName.orEmpty())
        }
    }
}

/** Marker used to apply the serial-line unit-ID range without leaking a platform transport type. */
interface RtuTransportMarker
