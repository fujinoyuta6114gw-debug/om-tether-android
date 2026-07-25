package com.example.omtether.camera

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.min

class DiagnosticLog(private val capacity: Int = 300) {
    private val lines = ArrayDeque<String>(capacity)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(message: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast("${timeFormat.format(Date())}  $message")
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

class PtpUsbTransport(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val log: DiagnosticLog,
) {
    private val transactionMutex = Mutex()
    private var nextTransactionId = 1L
    private var pending = ByteArray(0)

    suspend fun execute(
        code: Int,
        parameters: List<Long> = emptyList(),
        outgoingData: ByteArray? = null,
        acceptedResponses: Set<Int> = setOf(Ptp.RESPONSE_OK),
        transactionIdOverride: Long? = null,
    ): PtpResult = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            val transactionId = transactionIdOverride ?: (nextTransactionId++ and 0xFFFF_FFFFL)
            log.add("> ${Ptp.hex16(code)} tx=$transactionId params=${parameters.joinToString { Ptp.hex32(it) }}")
            writeFully(PtpCodec.command(code, transactionId, parameters))
            if (outgoingData != null) {
                log.add("> data ${Ptp.hex16(code)} ${outgoingData.size} B")
                writeFully(PtpCodec.data(code, transactionId, outgoingData))
            }

            var incomingData: ByteArray? = null
            repeat(MAX_CONTAINERS_PER_TRANSACTION) {
                val container = readContainer()
                if (container.type == Ptp.CONTAINER_EVENT) {
                    log.add("! event ${Ptp.hex16(container.code)} params=${container.payload.size / 4}")
                    return@repeat
                }
                if (container.transactionId != transactionId) {
                    // If an earlier live-view read was interrupted, its response can still be
                    // waiting on bulk IN. Drain that stale container so the PTP stream can
                    // recover instead of remaining one transaction behind forever.
                    log.add(
                        "! stale ${Ptp.hex16(container.code)} tx=${container.transactionId}; " +
                            "waiting for tx=$transactionId",
                    )
                    return@repeat
                }
                when (container.type) {
                    Ptp.CONTAINER_DATA -> {
                        if (container.code != code) {
                            throw PtpException(message = "PTP data code mismatch")
                        }
                        incomingData = container.payload
                        log.add("< data ${Ptp.hex16(code)} ${container.payload.size} B")
                    }
                    Ptp.CONTAINER_RESPONSE -> {
                        val result = PtpResult(
                            responseCode = container.code,
                            transactionId = transactionId,
                            data = incomingData,
                            responseParameters = PtpCodec.responseParameters(container.payload),
                        )
                        log.add("< ${Ptp.responseName(container.code)} tx=$transactionId")
                        if (container.code !in acceptedResponses) {
                            throw PtpException(
                                responseCode = container.code,
                                message = "${Ptp.hex16(code)} failed: ${Ptp.responseName(container.code)}",
                            )
                        }
                        return@withContext result
                    }
                    else -> throw PtpException(message = "Unexpected PTP container type ${container.type}")
                }
            }
            throw PtpException(message = "Too many non-response PTP containers for ${Ptp.hex16(code)}")
        }
    }

    private fun writeFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val transferred = connection.bulkTransfer(
                bulkOut,
                bytes,
                offset,
                bytes.size - offset,
                WRITE_TIMEOUT_MS,
            )
            if (transferred <= 0) {
                throw PtpException(message = "USB bulk OUT timed out at $offset/${bytes.size}")
            }
            offset += transferred
        }
    }

    private fun readContainer(): PtpContainer {
        while (pending.size < 12) pending += readChunk(READ_CHUNK_BYTES)
        val length = try {
            PtpCodec.declaredLength(pending)
        } catch (error: IllegalArgumentException) {
            throw PtpException(message = error.message ?: "Invalid PTP header", cause = error)
        }

        val header = PtpCursor(pending)
        header.u32()
        val type = header.u16()
        val code = header.u16()
        val transactionId = header.u32()

        // Allocate only the payload. The previous implementation allocated the complete
        // container and then copied the payload again, briefly doubling a large RAW file.
        val payloadLength = length - 12
        val payload = ByteArray(payloadLength)
        val fromPending = min((pending.size - 12).coerceAtLeast(0), payloadLength)
        if (fromPending > 0) {
            pending.copyInto(payload, startIndex = 12, endIndex = 12 + fromPending)
        }
        val consumedFromPending = 12 + fromPending
        pending = if (pending.size > consumedFromPending) {
            pending.copyOfRange(consumedFromPending, pending.size)
        } else {
            ByteArray(0)
        }

        var offset = fromPending
        while (offset < payloadLength) {
            val chunk = readChunk(min(READ_CHUNK_BYTES, payloadLength - offset))
            val usable = min(chunk.size, payloadLength - offset)
            chunk.copyInto(payload, destinationOffset = offset, endIndex = usable)
            offset += usable
            if (chunk.size > usable) pending += chunk.copyOfRange(usable, chunk.size)
        }
        return PtpContainer(type, code, transactionId, payload)
    }

    private fun readChunk(requestedBytes: Int): ByteArray {
        val buffer = ByteArray(requestedBytes)
        repeat(MAX_ZERO_LENGTH_READS + 1) { attempt ->
            val transferred = connection.bulkTransfer(
                bulkIn,
                buffer,
                requestedBytes,
                READ_TIMEOUT_MS,
            )
            if (transferred < 0) {
                throw PtpException(message = "USB bulk IN timed out (requested $requestedBytes B)")
            }
            if (transferred == 0) {
                // A PTP data phase whose byte count is an exact multiple of the endpoint
                // packet size may be followed by a legal USB zero-length packet.
                log.add("< USB zero-length packet ${attempt + 1}/$MAX_ZERO_LENGTH_READS")
                return@repeat
            }
            return if (transferred == buffer.size) buffer else buffer.copyOf(transferred)
        }
        throw PtpException(message = "Too many USB zero-length packets while reading bulk IN")
    }

    companion object {
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val READ_TIMEOUT_MS = 20_000
        private const val WRITE_TIMEOUT_MS = 20_000
        private const val MAX_CONTAINERS_PER_TRANSACTION = 24
        private const val MAX_ZERO_LENGTH_READS = 4
    }
}
