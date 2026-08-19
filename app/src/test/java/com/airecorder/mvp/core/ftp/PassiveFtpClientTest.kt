package com.airecorder.mvp.core.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.concurrent.thread

class PassiveFtpClientTest {
    @Test fun parsesRecorderUnixListLine() {
        val file = parseRecorderListLine("-rw-rw-rw-   1 user ftp 245760 Jun 30 12:00 20260630_120000.opus")
        assertEquals("20260630_120000.opus", file?.name)
        assertEquals(245760L, file?.bytes)
    }

    @Test fun ignoresPartialFile() {
        assertNull(parseRecorderListLine("-rw-rw-rw- 1 user ftp 42 Jun 30 12:00 20260630_120000.opus.partial"))
    }

    @Test fun createsStandardActiveFtpPortCommand() {
        val address = InetAddress.getByName("192.168.4.100") as java.net.Inet4Address
        assertEquals("PORT 192,168,4,100,195,80", activePortCommand(address, 50_000))
    }

    @Test fun listsRecorderFilesUsingActiveFtpDataConnection() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverError = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { control ->
                    val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.US_ASCII))
                    val writer = OutputStreamWriter(control.getOutputStream(), Charsets.US_ASCII)
                    fun reply(value: String) {
                        writer.write("$value\r\n")
                        writer.flush()
                    }

                    reply("220 recorder FTP ready")
                    var dataHost: String? = null
                    var dataPort = 0
                    while (true) {
                        when (val command = reader.readLine() ?: return@use) {
                            "USER recorder" -> reply("331 password required")
                            "PASS 12345678" -> reply("230 logged in")
                            "CWD RECORD" -> reply("250 directory changed")
                            "TYPE I" -> reply("200 binary mode")
                            "QUIT" -> {
                                reply("221 goodbye")
                                return@use
                            }
                            "LIST" -> {
                                reply("150 opening active data connection")
                                Socket(checkNotNull(dataHost), dataPort).use { data ->
                                    data.getOutputStream().writer(Charsets.US_ASCII).use { output ->
                                        output.write("-rw-rw-rw- 1 recorder ftp 42 Jun 30 12:00 20260630_120000.opus\r\n")
                                    }
                                }
                                reply("226 transfer complete")
                            }
                            else -> {
                                if (command.startsWith("PORT ")) {
                                    val values = command.removePrefix("PORT ").split(",").map(String::toInt)
                                    dataHost = values.take(4).joinToString(".")
                                    dataPort = values[4] * 256 + values[5]
                                    reply("200 PORT command successful")
                                } else {
                                    error("Unexpected FTP command: $command")
                                }
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                serverError.set(error)
            } finally {
                finished.countDown()
                server.close()
            }
        }

        PassiveFtpClient(
            socketFactory = SocketFactory.getDefault(),
            host = "127.0.0.1",
            username = "recorder",
            password = "12345678",
            activeDataAddress = InetAddress.getLoopbackAddress() as java.net.Inet4Address,
            controlPort = server.localPort,
            preferredDataMode = FtpDataMode.ACTIVE
        ).use { client ->
            assertEquals(listOf(RemoteRecording("20260630_120000.opus", 42)), client.run {
                connect()
                listRecordings()
            })
        }

        assertTrue(finished.await(3, TimeUnit.SECONDS))
        worker.join(100)
        assertNull(serverError.get())
    }

    @Test fun listsRecorderFilesUsingPassiveFtpDataConnectionByDefault() {
        val controlServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val dataServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverError = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            try {
                controlServer.accept().use { control ->
                    val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.US_ASCII))
                    val writer = OutputStreamWriter(control.getOutputStream(), Charsets.US_ASCII)
                    fun reply(value: String) {
                        writer.write("$value\r\n")
                        writer.flush()
                    }

                    reply("220 recorder FTP ready")
                    while (true) {
                        when (reader.readLine() ?: return@use) {
                            "USER recorder" -> reply("331 password required")
                            "PASS 12345678" -> reply("230 logged in")
                            "CWD RECORD" -> reply("250 directory changed")
                            "TYPE I" -> reply("200 binary mode")
                            "PASV" -> {
                                val port = dataServer.localPort
                                reply("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
                            }
                            "LIST" -> {
                                reply("150 opening passive data connection")
                                dataServer.accept().use { data ->
                                    data.getOutputStream().writer(Charsets.US_ASCII).use { output ->
                                        output.write("-rw-rw-rw- 1 recorder ftp 42 Jun 30 12:00 20260630_120000.opus\r\n")
                                    }
                                }
                                reply("226 transfer complete")
                            }
                            "QUIT" -> {
                                reply("221 goodbye")
                                return@use
                            }
                            else -> error("Unexpected FTP command")
                        }
                    }
                }
            } catch (error: Throwable) {
                serverError.set(error)
            } finally {
                finished.countDown()
                dataServer.close()
                controlServer.close()
            }
        }

        PassiveFtpClient(
            socketFactory = SocketFactory.getDefault(),
            host = "127.0.0.1",
            username = "recorder",
            password = "12345678",
            controlPort = controlServer.localPort
        ).use { client ->
            assertEquals(listOf(RemoteRecording("20260630_120000.opus", 42)), client.run {
                connect()
                listRecordings()
            })
        }

        assertTrue(finished.await(3, TimeUnit.SECONDS))
        worker.join(100)
        assertNull(serverError.get())
    }

    @Test fun resumesPartialPassiveDownloadUsingRest() {
        val payload = "complete-recorder-audio".toByteArray()
        val existingBytes = 9
        val destination = File.createTempFile("recorder-resume", ".opus").apply {
            writeBytes(payload.copyOfRange(0, existingBytes))
        }
        val controlServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val dataServer = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverError = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)
        val worker = thread(isDaemon = true) {
            try {
                controlServer.accept().use { control ->
                    val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.US_ASCII))
                    val writer = OutputStreamWriter(control.getOutputStream(), Charsets.US_ASCII)
                    fun reply(value: String) {
                        writer.write("$value\r\n")
                        writer.flush()
                    }

                    reply("220 recorder FTP ready")
                    while (true) {
                        when (val command = reader.readLine() ?: return@use) {
                            "USER recorder" -> reply("331 password required")
                            "PASS 12345678" -> reply("230 logged in")
                            "CWD RECORD" -> reply("250 directory changed")
                            "TYPE I" -> reply("200 binary mode")
                            "PASV" -> {
                                val port = dataServer.localPort
                                reply("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
                            }
                            "REST $existingBytes" -> reply("350 restart position accepted")
                            "RETR 20260630_120000.opus" -> {
                                reply("150 opening passive data connection")
                                dataServer.accept().use { data ->
                                    data.getOutputStream().write(payload, existingBytes, payload.size - existingBytes)
                                }
                                reply("226 transfer complete")
                            }
                            "QUIT" -> {
                                reply("221 goodbye")
                                return@use
                            }
                            else -> error("Unexpected FTP command: $command")
                        }
                    }
                }
            } catch (error: Throwable) {
                serverError.set(error)
            } finally {
                finished.countDown()
                dataServer.close()
                controlServer.close()
            }
        }

        try {
            PassiveFtpClient(
                socketFactory = SocketFactory.getDefault(),
                host = "127.0.0.1",
                username = "recorder",
                password = "12345678",
                controlPort = controlServer.localPort
            ).use { client ->
                client.connect()
                client.download(RemoteRecording("20260630_120000.opus", payload.size.toLong()), destination) {}
            }
            assertTrue(payload.contentEquals(destination.readBytes()))
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            worker.join(100)
            assertNull(serverError.get())
        } finally {
            destination.delete()
        }
    }
}
