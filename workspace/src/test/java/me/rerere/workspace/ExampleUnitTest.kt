package me.rerere.workspace

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class ExampleUnitTest {
    @Test
    fun fileOperationsWorkInsideWorkspaceRoot() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        fileSystem.writeText(root, "src/main.txt", "hello\nworkspace")

        assertEquals("hello\nworkspace", fileSystem.readText(root, "src/main.txt"))
        assertEquals(listOf("src"), fileSystem.list(root).map { it.path })
        assertEquals(listOf("src/main.txt"), fileSystem.glob(root, "**/*.txt").map { it.path })
        assertEquals(
            listOf(WorkspaceSearchMatch(path = "src/main.txt", line = 2, text = "workspace")),
            fileSystem.grep(root, "workspace"),
        )
    }

    @Test
    fun pathEscapeIsRejected() {
        val root = Files.createTempDirectory("workspace-test").toFile()
        val fileSystem = WorkspaceFileSystem()

        var rejected = false
        try {
            fileSystem.writeText(root, "../escape.txt", "nope")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun rootfsRequiresShellEntryPoint() {
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        assertFalse(manager.hasRootfs(root))

        File(manager.linuxDir(root), "etc").mkdirs()
        assertFalse(manager.hasRootfs(root))

        File(manager.linuxDir(root), "bin").mkdirs()
        File(manager.linuxDir(root), "bin/sh").writeText("#!/bin/sh\n")
        assertTrue(manager.hasRootfs(root))
    }

    @Test
    fun rootfsInstallerDownloadsAndExtractsTarGz() {
        val baseDir = Files.createTempDirectory("workspace-manager-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val installer = RootfsInstaller(manager)
        val archive = tarGz(
            TarTestEntry("bin/", type = '5'),
            TarTestEntry("bin/hello", content = "echo hello\n".toByteArray(), mode = 493),
            TarTestEntry("usr/bin/hello-link", type = '2', linkName = "../../bin/hello"),
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rootfs.tar.gz") { exchange ->
            exchange.sendResponseHeaders(200, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
        }
        server.start()
        try {
            val root = "test-workspace"
            installer.install(root, "http://127.0.0.1:${server.address.port}/rootfs.tar.gz")

            val linuxDir = manager.linuxDir(root)
            assertEquals("echo hello\n", File(linuxDir, "bin/hello").readText())
            assertTrue(File(linuxDir, "bin/hello").canExecute())
            assertTrue(Files.isSymbolicLink(File(linuxDir, "usr/bin/hello-link").toPath()))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun commandRunsInsideWorkspaceFilesDirectory() {
        val baseDir = Files.createTempDirectory("workspace-command-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "printf hello > command.txt && cat command.txt")

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("hello", File(manager.filesDir(root), "command.txt").readText())
    }

    @Test
    fun commandReceivesStdin() {
        val baseDir = Files.createTempDirectory("workspace-stdin-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(
            root = root,
            command = "cat > stdin.txt",
            stdin = "hello\nstdin".toByteArray(),
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello\nstdin", File(manager.filesDir(root), "stdin.txt").readText())
    }

    @Test
    fun prootRunnerRequiresRootfs() {
        val baseDir = Files.createTempDirectory("workspace-proot-test").toFile()
        val manager = WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(File(baseDir, "native"))
        )
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(root, "cat /etc/os-release")

        assertEquals(127, result.exitCode)
        assertEquals("Rootfs is not installed", result.stderr)
    }

    @Test
    fun commandOutputIsTruncatedAtLimit() {
        val baseDir = Files.createTempDirectory("workspace-truncate-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.executeCommand(
            root,
            "awk 'BEGIN { for (i = 0; i < 300000; i++) printf \"a\" }'",
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertEquals(MAX_OUTPUT_CHARS, result.stdout.length)
    }

    @Test
    fun quickCommandCompletesWithoutRetainingSession() {
        val baseDir = Files.createTempDirectory("workspace-session-quick-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val result = manager.startCommandSession(root, "printf quick", yieldMillis = 2_000)

        assertEquals(WorkspaceShellSessionStatus.COMPLETED, result.status)
        assertNull(result.sessionId)
        assertEquals(0, result.exitCode)
        assertEquals("quick", result.stdout)
    }

    @Test
    fun backgroundCommandRequestsPtyWithConfiguredSize() {
        val baseDir = Files.createTempDirectory("workspace-session-pty-test").toFile()
        lateinit var capturedContext: WorkspaceShellContext
        val runner = object : WorkspaceShellRunner {
            override fun start(context: WorkspaceShellContext): WorkspaceShellProcess {
                capturedContext = context
                return WorkspaceShellProcess.completed(WorkspaceCommandResult(0, "", ""))
            }
        }
        val manager = WorkspaceManager(baseDir, shellRunner = runner)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        manager.startCommandSession(
            root = root,
            command = "printf quick",
            yieldMillis = 0,
            terminalRows = 42,
            terminalColumns = 132,
        )

        assertTrue(capturedContext.usePty)
        assertEquals(42, capturedContext.terminalRows)
        assertEquals(132, capturedContext.terminalColumns)
    }

    @Test
    fun longCommandReturnsIncrementalSessionOutput() {
        val baseDir = Files.createTempDirectory("workspace-session-output-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val started = manager.startCommandSession(
            root = root,
            command = "printf first; sleep 2; printf second",
            yieldMillis = 200,
        )
        assertEquals(WorkspaceShellSessionStatus.RUNNING, started.status)
        val sessionId = requireNotNull(started.sessionId)
        var firstOutput = started.stdout
        repeat(5) {
            if (firstOutput.isEmpty()) {
                firstOutput += manager.waitCommandSession(root, sessionId, yieldMillis = 100).stdout
            }
        }
        assertEquals("first", firstOutput)

        val completed = manager.waitCommandSession(root, sessionId, yieldMillis = 3_000)
        assertEquals(WorkspaceShellSessionStatus.COMPLETED, completed.status)
        assertEquals("second", completed.stdout)
        assertEquals(0, completed.exitCode)

        val emptyDelta = manager.waitCommandSession(root, sessionId, yieldMillis = 0)
        assertEquals(WorkspaceShellSessionStatus.COMPLETED, emptyDelta.status)
        assertEquals("", emptyDelta.stdout)
    }

    @Test
    fun backgroundSessionAcceptsStdin() {
        val baseDir = Files.createTempDirectory("workspace-session-stdin-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val started = manager.startCommandSession(
            root = root,
            command = "read line; printf 'got:%s' \"\$line\"",
            yieldMillis = 100,
        )
        assertEquals(WorkspaceShellSessionStatus.RUNNING, started.status)

        val written = manager.updateCommandSession(
            root = root,
            sessionId = requireNotNull(started.sessionId),
            stdin = "hello\n".toByteArray(),
            closeStdin = true,
        )
        val completed = if (written.status == WorkspaceShellSessionStatus.COMPLETED) {
            written
        } else {
            manager.waitCommandSession(root, requireNotNull(started.sessionId), yieldMillis = 2_000)
        }
        assertEquals(WorkspaceShellSessionStatus.COMPLETED, completed.status)
        assertEquals("got:hello", written.stdout + completed.stdout)
    }

    @Test
    fun shellSessionCannotBeAccessedFromAnotherWorkspace() {
        val baseDir = Files.createTempDirectory("workspace-session-isolation-test").toFile()
        val manager = WorkspaceManager(baseDir)
        manager.ensureWorkspace("workspace-a")
        manager.ensureWorkspace("workspace-b")

        val started = manager.startCommandSession("workspace-a", "sleep 10", yieldMillis = 20)
        val sessionId = requireNotNull(started.sessionId)

        assertThrows(IllegalStateException::class.java) {
            manager.waitCommandSession("workspace-b", sessionId, yieldMillis = 0)
        }
        val terminationResult = manager.updateCommandSession("workspace-a", sessionId, terminate = true)
        val terminated = if (terminationResult.status == WorkspaceShellSessionStatus.COMPLETED) {
            terminationResult
        } else {
            manager.waitCommandSession("workspace-a", sessionId, yieldMillis = 2_000)
        }
        assertEquals(WorkspaceShellSessionStatus.COMPLETED, terminated.status)
    }

    @Test
    fun rootfsPatcherAppliesAndroidProotDefaults() {
        val linuxDir = Files.createTempDirectory("rootfs-patch-test").toFile()
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/resolv.conf").writeText("nameserver 127.0.0.53\n")
        File(linuxDir, "etc/group").writeText("root:x:0:\n")

        RootfsPatcher().patch(
            linuxDir,
            RootfsPatchOptions(
                nameservers = listOf("9.9.9.9", "8.8.8.8"),
                hostname = "workspace-test",
                groupIds = listOf(3003, 9997),
            )
        )

        assertEquals(
            """
            # Generated by RikkaHub workspace.
            nameserver 9.9.9.9
            nameserver 8.8.8.8
            options edns0 trust-ad

            """.trimIndent(),
            File(linuxDir, "etc/resolv.conf").readText()
        )
        assertTrue(File(linuxDir, "etc/hosts").readText().contains("127.0.0.1 localhost workspace-test"))
        assertEquals("workspace-test\n", File(linuxDir, "etc/hostname").readText())
        assertEquals("LANG=C.UTF-8\n", File(linuxDir, "etc/default/locale").readText())
        assertTrue(File(linuxDir, "etc/group").readText().contains("android_gid_3003:x:3003:"))
        assertTrue(File(linuxDir, "etc/group").readText().contains("android_gid_9997:x:9997:"))
        assertTrue(File(linuxDir, "tmp").canWrite())
        assertTrue(File(linuxDir, "var/tmp").canWrite())
        assertTrue(File(linuxDir, "root").isDirectory)
    }

    private fun tarGz(vararg entries: TarTestEntry): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            entries.forEach { entry ->
                gzip.write(tarHeader(entry))
                if (entry.content.isNotEmpty()) {
                    gzip.write(entry.content)
                    gzip.write(ByteArray(entry.content.size.paddingSize()))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return output.toByteArray()
    }

    private fun tarHeader(entry: TarTestEntry): ByteArray {
        val header = ByteArray(512)
        header.writeString(0, 100, entry.name)
        header.writeOctal(100, 8, entry.mode.toLong())
        header.writeOctal(108, 8, 0)
        header.writeOctal(116, 8, 0)
        header.writeOctal(124, 12, entry.content.size.toLong())
        header.writeOctal(136, 12, 0)
        header.fill(' '.code.toByte(), 148, 156)
        header[156] = entry.type.code.toByte()
        header.writeString(157, 100, entry.linkName)
        header.writeString(257, 6, "ustar")
        header.writeString(263, 2, "00")
        val checksum = header.sumOf { it.toUByte().toInt() }
        header.writeOctal(148, 8, checksum.toLong())
        return header
    }

    private fun ByteArray.writeString(offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray()
        bytes.copyInto(this, offset, 0, minOf(bytes.size, length))
    }

    private fun ByteArray.writeOctal(offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0')
        writeString(offset, length, text)
        this[offset + length - 1] = 0
    }

    private fun Int.paddingSize(): Int = (512 - (this % 512)).let {
        if (it == 512) 0 else it
    }

    private data class TarTestEntry(
        val name: String,
        val content: ByteArray = byteArrayOf(),
        val mode: Int = 420,
        val type: Char = '0',
        val linkName: String = "",
    )
}
