package com.airecorder.mvp.core.ftp

import android.util.Log
import com.airecorder.mvp.core.ble.RecorderBleProtocol
import java.io.BufferedReader
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

data class RemoteRecording(val name: String, val bytes: Long)

class PassiveFtpClient(
    private val socketFactory: SocketFactory,
    private val host: String,
    private val username: String,
    private val password: String,
    private val activeDataAddress: Inet4Address? = null,
    private val controlPort: Int = CONTROL_PORT,
    private val preferredDataMode: FtpDataMode = FtpDataMode.PASSIVE
) : AutoCloseable {
    private lateinit var control: Socket
    private lateinit var reader: BufferedReader
    private lateinit var writer: OutputStreamWriter
    // The recorder protocol mandates PASV. Active mode remains a compatibility
    // fallback for early board firmware, not the normal transfer path.
    private var dataMode = preferredDataMode

    /** The data mode currently used after any automatic compatibility fallback. */
    val activeDataMode: FtpDataMode
        get() = dataMode

    fun connect() {
        Log.i(TAG, "Connecting to FTP $host:$controlPort")
        try {
            control = socketFactory.createSocket().apply {
                soTimeout = IO_TIMEOUT_MS
                tcpNoDelay = true
                receiveBufferSize = SOCKET_RECEIVE_BUFFER_BYTES
                // Phase-one firmware exposes FTP on the fixed control port 21.
                // Do not allow an optional BLE field or stale credential value
                // to turn this into a port-0 connection.
                connect(InetSocketAddress(host, controlPort), CONNECT_TIMEOUT_MS)
            }
            reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.US_ASCII))
            writer = OutputStreamWriter(control.getOutputStream(), Charsets.US_ASCII)
            expect(220)
        } catch (error: Exception) {
            throw ftpFailure("SYNC-FTP-CONTROL-001", error)
        }
        try {
            command("USER $username", 331)
            command("PASS $password", 230)
        } catch (error: Exception) {
            throw ftpFailure("SYNC-FTP-AUTH-002", error)
        }
        selectRecordingDirectory()
        try {
            command("TYPE I", 200)
        } catch (error: Exception) {
            throw ftpFailure("SYNC-FTP-TYPE-004", error)
        }
        Log.i(TAG, "FTP control session ready")
    }

    fun reconnect() {
        closeTransport()
        connect()
    }

    fun listRecordings(): List<RemoteRecording> = try {
        val lines = dataCommand("LIST", "SYNC-FTP-LIST-006") { input ->
            input.bufferedReader(Charsets.US_ASCII).readLines()
        }
        val recordings = lines
            .mapNotNull(::parseRecorderListLine)
            .filterNot { it.name.endsWith(".partial") }
        if (recordings.isNotEmpty() || lines.none { it.contains(".opus", ignoreCase = true) }) {
            recordings
        } else {
            // Firmware FTP servers do not all use the same human-readable LIST
            // format. NLST plus SIZE is part of the phase-one protocol and avoids
            // rejecting otherwise valid recordings only because their listing text
            // differs from a Unix FTP server.
            Log.w(TAG, "LIST contained recorder files in an unknown format; falling back to NLST/SIZE")
            listRecordingNames().map { name -> RemoteRecording(name, remoteSize(name)) }
        }
    } catch (error: FtpStageFailure) {
        throw error
    } catch (error: Exception) {
        throw ftpFailure("SYNC-FTP-LIST-PARSE-007", error)
    }

    fun download(recording: RemoteRecording, destination: File, onProgress: (Long) -> Unit) {
        try {
            require(recording.name.matches(Regex("[A-Za-z0-9_.-]+\\.opus"))) { "Unsafe remote file name" }
            destination.parentFile?.mkdirs()
            if (destination.length() > recording.bytes) destination.writeBytes(byteArrayOf())
            if (destination.length() == recording.bytes && recording.bytes > 0L) {
                onProgress(recording.bytes)
                return
            }
            val startedAt = System.nanoTime()
            val existingBytes = destination.length().coerceIn(0L, recording.bytes)
            fun transferFrom(offset: Long) {
                onProgress(offset)
                dataCommand("RETR ${recording.name}", "SYNC-FTP-RETR-008", restartOffset = offset) { input ->
                    BufferedInputStream(input, TRANSFER_BUFFER_BYTES).use { bufferedInput ->
                        BufferedOutputStream(FileOutputStream(destination, offset > 0L), TRANSFER_BUFFER_BYTES).use { output ->
                            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                            var transferred = offset
                            while (true) {
                                val read = bufferedInput.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                transferred += read
                                onProgress(transferred)
                            }
                            require(transferred == recording.bytes) {
                                "SYNC-FTP-BYTES-009 | expected ${recording.bytes}, received $transferred"
                            }
                        }
                    }
                }
            }
            try {
                transferFrom(existingBytes)
            } catch (error: FtpRestartNotSupportedException) {
                if (existingBytes == 0L) throw error
                Log.w(TAG, "SYNC-FTP-REST-016: recorder rejected resume; restarting ${recording.name}")
                destination.writeBytes(byteArrayOf())
                transferFrom(0L)
            }
            val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
            val kibPerSecond = if (elapsedSeconds > 0.0) recording.bytes / 1024.0 / elapsedSeconds else 0.0
            Log.i(TAG, "SYNC-FTP-THROUGHPUT-014: ${recording.name}; ${"%.1f".format(java.util.Locale.US, kibPerSecond)} KiB/s via ${dataMode.name}")
        } catch (error: FtpStageFailure) {
            throw error
        } catch (error: Exception) {
            throw ftpFailure("SYNC-FTP-DOWNLOAD-010", error)
        }
    }

    override fun close() {
        if (::control.isInitialized && control.isConnected) {
            runCatching { command("QUIT", 221) }
        }
        closeTransport()
    }

    private fun closeTransport() {
        if (::control.isInitialized) {
            runCatching { control.close() }
        }
    }

    private fun selectRecordingDirectory() {
        runCatching { command("CWD RECORD", 250) }
            .onSuccess { return }
            .onFailure { firstError ->
                runCatching { command("CWD /RECORD", 250) }
                    .onSuccess { return }
                    .onFailure {
                        // Some recorder firmware logs in directly to RECORD. Keep
                        // that working directory if both explicit forms are rejected.
                        Log.w(TAG, "SYNC-FTP-CWD-003: recorder rejected CWD RECORD and CWD /RECORD; using login directory", firstError)
                    }
            }
    }

    private fun listRecordingNames(): List<String> = dataCommand("NLST", "SYNC-FTP-NLST-011") { input ->
        input.bufferedReader(Charsets.US_ASCII).readLines()
            .map(String::trim)
            .filter { it.matches(Regex("[A-Za-z0-9_.-]+\\.opus", RegexOption.IGNORE_CASE)) }
            .filterNot { it.endsWith(".partial", ignoreCase = true) }
    }

    private fun remoteSize(name: String): Long {
        try {
            send("SIZE $name")
            val response = expect(213)
            return Regex("(\\d+)\\s*$").find(response)?.groupValues?.get(1)?.toLongOrNull()
                ?: error("FTP SIZE response is malformed: $response")
        } catch (error: Exception) {
            throw ftpFailure("SYNC-FTP-SIZE-012", error)
        }
    }

    private fun <T> dataCommand(
        command: String,
        failureCode: String,
        restartOffset: Long = 0L,
        action: (java.io.InputStream) -> T
    ): T {
        return when (dataMode) {
            FtpDataMode.PASSIVE -> try {
                passiveDataCommand(command, failureCode, restartOffset, action)
            } catch (error: FtpRestartNotSupportedException) {
                throw error
            } catch (error: Exception) {
                // A failed RETR may already have written part of the file. Let the
                // coordinator reconnect and resume from the new length; changing
                // modes inside this command could append the same bytes twice.
                if (command.startsWith("RETR ")) throw error
                val localAddress = activeDataAddress ?: throw error
                Log.w(TAG, "SYNC-FTP-PASV-TO-ACTIVE-013: passive FTP failed; retrying this operation in active mode", error)
                dataMode = FtpDataMode.ACTIVE
                reconnect()
                activeDataCommand(command, localAddress, restartOffset, action)
            }
            FtpDataMode.ACTIVE -> try {
                activeDataCommand(command, checkNotNull(activeDataAddress), restartOffset, action)
            } catch (error: FtpRestartNotSupportedException) {
                throw error
            } catch (error: Exception) {
                if (command.startsWith("RETR ")) throw error
                Log.w(TAG, "SYNC-FTP-ACTIVE-TO-PASV-015: active FTP failed; retrying this operation in passive mode", error)
                dataMode = FtpDataMode.PASSIVE
                reconnect()
                passiveDataCommand(command, failureCode, restartOffset, action)
            }
        }
    }

    private fun <T> activeDataCommand(
        command: String,
        localAddress: Inet4Address,
        restartOffset: Long,
        action: (java.io.InputStream) -> T
    ): T {
        ServerSocket().use { listener ->
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(localAddress, 0), ACTIVE_BACKLOG)
            listener.soTimeout = IO_TIMEOUT_MS
            val portCommand = activePortCommand(localAddress, listener.localPort)
            Log.d(TAG, "FTP -> $portCommand")
            send(portCommand)
            expect(200)
            applyRestartOffset(restartOffset)
            send(command)
            expect(150, 125)
            listener.accept().use { data ->
                data.soTimeout = IO_TIMEOUT_MS
                data.tcpNoDelay = true
                data.receiveBufferSize = SOCKET_RECEIVE_BUFFER_BYTES
                val result = action(data.getInputStream())
                expect(226, 250)
                return result
            }
        }
    }

    private fun <T> passiveDataCommand(
        command: String,
        failureCode: String,
        restartOffset: Long,
        action: (java.io.InputStream) -> T
    ): T {
        try {
            val endpoint = passiveEndpoint()
            Log.d(TAG, "FTP passive data endpoint ${endpoint.first}:${endpoint.second}")
            socketFactory.createSocket().use { data ->
                data.soTimeout = IO_TIMEOUT_MS
                data.tcpNoDelay = true
                data.receiveBufferSize = SOCKET_RECEIVE_BUFFER_BYTES
                data.connect(InetSocketAddress(endpoint.first, endpoint.second), CONNECT_TIMEOUT_MS)
                applyRestartOffset(restartOffset)
                send(command)
                expect(150, 125)
                val result = action(data.getInputStream())
                expect(226, 250)
                return result
            }
        } catch (error: FtpRestartNotSupportedException) {
            throw error
        } catch (error: FtpStageFailure) {
            throw error
        } catch (error: Exception) {
            throw ftpFailure(failureCode, error)
        }
    }

    private fun applyRestartOffset(offset: Long) {
        if (offset <= 0L) return
        try {
            send("REST $offset")
            expect(350)
        } catch (error: Exception) {
            throw FtpRestartNotSupportedException(error)
        }
    }

    private fun passiveEndpoint(): Pair<String, Int> {
        try {
            send("PASV")
            val response = expect(227)
            val match = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(response)
                ?: error("FTP PASV response is malformed: $response")
            val values = match.groupValues.drop(1).map(String::toInt)
            val advertisedHost = listOf(values[0], values[1], values[2], values[3]).joinToString(".")
            val port = values[4] * 256 + values[5]
            require(port in 1..65_535) { "FTP PASV returned an invalid data port $port" }
            // A few embedded FTP stacks advertise 0.0.0.0 in PASV even though the
            // data socket is reachable at the control connection host.
            return (if (advertisedHost == "0.0.0.0") host else advertisedHost) to port
        } catch (error: FtpStageFailure) {
            throw error
        } catch (error: Exception) {
            throw ftpFailure("SYNC-FTP-PASV-005", error)
        }
    }

    private fun command(command: String, vararg expected: Int) {
        send(command)
        expect(*expected)
    }

    private fun send(command: String) {
        Log.d(TAG, "FTP -> ${if (command.startsWith("PASS ")) "PASS <redacted>" else command}")
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
    }

    private fun expect(vararg expectedCodes: Int): String {
        val first = reader.readLine() ?: error("FTP control connection closed")
        require(first.length >= 3) { "Malformed FTP response: $first" }
        val code = first.take(3).toIntOrNull() ?: error("Malformed FTP response: $first")
        var last = first
        if (first.getOrNull(3) == '-') {
            while (true) {
                last = reader.readLine() ?: error("FTP multiline response ended unexpectedly")
                if (last.startsWith("$code ")) break
            }
        }
        Log.d(TAG, "FTP <- $last")
        require(code in expectedCodes) { "FTP expected ${expectedCodes.joinToString()} but received $last" }
        return last
    }

    private companion object {
        const val TAG = "PassiveFtpClient"
        const val CONTROL_PORT = RecorderBleProtocol.DEFAULT_FTP_PORT
        const val CONNECT_TIMEOUT_MS = 5_000
        const val IO_TIMEOUT_MS = 30_000
        const val ACTIVE_BACKLOG = 1
        const val SOCKET_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val TRANSFER_BUFFER_BYTES = 128 * 1024
    }
}

enum class FtpDataMode { PASSIVE, ACTIVE }

private class FtpStageFailure(code: String, cause: Throwable) : IllegalStateException(
    "$code | ${cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName}", cause
)

private class FtpRestartNotSupportedException(cause: Throwable) : IllegalStateException(
    "FTP server does not support REST resume", cause
)

private fun ftpFailure(code: String, cause: Throwable): FtpStageFailure = FtpStageFailure(code, cause)

internal fun parseRecorderListLine(line: String): RemoteRecording? {
    val match = Regex("^\\S+\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+.+\\s+([^\\s]+)$").find(line) ?: return null
    val name = match.groupValues[2]
    return if (name.endsWith(".opus") && !name.endsWith(".opus.partial")) RemoteRecording(name, match.groupValues[1].toLong()) else null
}

internal fun activePortCommand(address: Inet4Address, port: Int): String {
    require(port in 1..65_535) { "FTP active data port is invalid: $port" }
    val octets = address.address.joinToString(",") { (it.toInt() and 0xFF).toString() }
    return "PORT $octets,${port / 256},${port % 256}"
}
