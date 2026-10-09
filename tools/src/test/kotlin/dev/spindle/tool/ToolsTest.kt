package dev.spindle.tool

import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.store.InMemorySnapshotStore
import dev.spindle.core.store.SnapshotStore
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.core.tool.ShellResult
import dev.spindle.core.tool.SubagentResult
import dev.spindle.core.tool.SubagentSpec
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolProgress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

/** Minimal ToolContext backed by a temp directory with a scripted answer. */
private class FakeToolContext(
    override val cwd: Path,
    private val scriptedAnswer: List<String> = emptyList(),
    private val scriptedSubagent: SubagentResult = SubagentResult(SessionId("child-session"), "ok", true),
    private val snapshotStore: SnapshotStore? = null,
) : ToolContext {
    override val sessionId = SessionId("test-session")
    override val snapshots: SnapshotStore? get() = snapshotStore
    override val session = Session(
        id = sessionId,
        cwd = cwd.toString(),
        createdAt = 0L,
        updatedAt = 0L,
    )
    var lastQuestion: String? = null
    var lastOptions: List<String> = emptyList()
    var lastMultiple: Boolean = false
    var permissionRequests: Int = 0
    var permissionAllowed: Boolean = true
    var lastPermissionTool: String? = null
    var lastPermissionDetail: String? = null
    var lastPermissionPattern: String? = null
    var lastSubagentSpec: SubagentSpec? = null
    var setTodosCalls: Int = 0
    private var storedTodos: List<TodoItem> = emptyList()

    override suspend fun requestPermission(tool: String, detail: String, pattern: String?): Boolean {
        permissionRequests++
        lastPermissionTool = tool
        lastPermissionDetail = detail
        lastPermissionPattern = pattern
        return permissionAllowed
    }

    override suspend fun ask(question: String, options: List<String>, multiple: Boolean): List<String> {
        lastQuestion = question
        lastOptions = options
        lastMultiple = multiple
        return scriptedAnswer
    }

    override fun emit(event: ToolProgress) {}
    override suspend fun checkAborted() {}

    override suspend fun setTodos(todos: List<TodoItem>) {
        setTodosCalls++
        storedTodos = todos
    }

    override suspend fun todos(): List<TodoItem> = storedTodos

    override suspend fun subagent(spec: SubagentSpec): SubagentResult {
        lastSubagentSpec = spec
        return scriptedSubagent
    }
}

/** Scripted [ShellExecutor] so BashTool formatting can be asserted without a process. */
private class FakeShell(
    private val result: ShellResult,
) : ShellExecutor {
    override val id = "fake"
    var calls: Int = 0
    var lastCommand: String? = null
    var lastCwd: Path? = null
    var lastTimeoutMs: Long? = null

    override suspend fun run(
        command: String,
        cwd: Path,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ShellResult {
        calls++
        lastCommand = command
        lastCwd = cwd
        lastTimeoutMs = timeoutMs
        return result
    }
}

private suspend fun <T> withTempDir(block: suspend (Path) -> T): T {
    val dir = Files.createTempDirectory("spindle-tools-test")
    try {
        return block(dir)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

class ToolsTest {

    @Test
    fun readAfterWriteRoundTrip() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val write = WriteTool().run(obj("""{"path":"notes/hello.txt","content":"hello\nworld\n"}"""), ctx)
            assertFalse(write.isError)
            assertTrue(write.output.contains("bytes"))
            assertTrue(Files.exists(dir.resolve("notes/hello.txt")))

            val read = ReadTool().run(obj("""{"path":"notes/hello.txt"}"""), ctx)
            assertFalse(read.isError)
            assertTrue(read.output.contains("hello"))
            assertTrue(read.output.contains("world"))
            assertTrue(read.output.contains("1\t"), "expected numbered lines, got: ${read.output}")
        }
    }

    @Test
    fun editAppliesAndReportsDiff() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            WriteTool().run(obj("""{"path":"a.txt","content":"one\ntwo\nthree\n"}"""), ctx)

            val outcome = EditTool().run(obj("""{"path":"a.txt","oldString":"two","newString":"TWO"}"""), ctx)
            assertFalse(outcome.isError)
            assertNotNull(outcome.diff)
            assertTrue(outcome.diff!!.contains("-two"), outcome.diff!!)
            assertTrue(outcome.diff!!.contains("+TWO"), outcome.diff!!)
            assertEquals("one\nTWO\nthree\n", Files.readString(dir.resolve("a.txt")))
        }
    }

    @Test
    fun editReturnsStructuredFileEditAndRecordsSnapshot() = runTest {
        withTempDir { dir ->
            val snapshots = InMemorySnapshotStore()
            val ctx = FakeToolContext(dir, snapshotStore = snapshots)
            val seed = WriteTool().run(obj("""{"path":"a.txt","content":"one\ntwo\nthree\n"}"""), ctx)
            assertNotNull(seed.edit)
            assertTrue(seed.edit!!.created, "a new file must be marked created")
            assertEquals(null, seed.snapshotId, "a creation has nothing to snapshot")

            val outcome = EditTool().run(obj("""{"path":"a.txt","oldString":"two","newString":"TWO"}"""), ctx)
            assertFalse(outcome.isError)
            val edit = assertNotNull(outcome.edit)
            assertEquals("a.txt", edit.path)
            assertEquals(1, edit.added)
            assertEquals(1, edit.removed)
            assertEquals(false, edit.created)
            assertEquals(2, edit.startLine)
            assertEquals(2, edit.endLine)
            assertTrue(edit.unifiedDiff.contains("+TWO"), edit.unifiedDiff)

            val snapshotId = assertNotNull(outcome.snapshotId)
            val snapshot = assertNotNull(snapshots.latest(ctx.sessionId, "a.txt"))
            assertEquals(snapshotId, snapshot.id)
            assertEquals("one\ntwo\nthree\n", snapshot.content)
        }
    }

    @Test
    fun writeOverwriteRecordsSnapshotAndCountsLines() = runTest {
        withTempDir { dir ->
            val snapshots = InMemorySnapshotStore()
            val ctx = FakeToolContext(dir, snapshotStore = snapshots)
            WriteTool().run(obj("""{"path":"b.txt","content":"alpha\nbeta\ngamma\n"}"""), ctx)

            val outcome = WriteTool().run(obj("""{"path":"b.txt","content":"alpha\nBETA\n"}"""), ctx)
            assertFalse(outcome.isError)
            val edit = assertNotNull(outcome.edit)
            assertEquals(2, edit.added)
            assertEquals(3, edit.removed)
            assertEquals(false, edit.created)
            assertNotNull(outcome.snapshotId)
            assertEquals("alpha\nbeta\ngamma\n", snapshots.latest(ctx.sessionId, "b.txt")?.content)
        }
    }

    @Test
    fun editMissingOldStringIsError() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            WriteTool().run(obj("""{"path":"a.txt","content":"hello"}"""), ctx)

            val outcome = EditTool().run(obj("""{"path":"a.txt","oldString":"nope","newString":"x"}"""), ctx)
            assertTrue(outcome.isError)
            assertTrue(outcome.output.contains("not found", ignoreCase = true), outcome.output)
            assertEquals("hello", Files.readString(dir.resolve("a.txt")))
        }
    }

    @Test
    fun globFindsNestedFiles() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            Files.createDirectories(dir.resolve("src/deep"))
            Files.writeString(dir.resolve("src/deep/Thing.kt"), "class Thing")
            Files.writeString(dir.resolve("src/Main.kt"), "fun main() {}")
            Files.writeString(dir.resolve("readme.md"), "# hi")

            val outcome = GlobTool().run(obj("""{"pattern":"**/*.kt"}"""), ctx)
            assertFalse(outcome.isError)
            assertTrue(outcome.output.contains("src/deep/Thing.kt"), outcome.output)
            assertTrue(outcome.output.contains("src/Main.kt"), outcome.output)
            assertFalse(outcome.output.contains("readme.md"), outcome.output)
        }
    }

    @Test
    fun grepMatchesWithIncludeFilter() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            Files.writeString(dir.resolve("a.kt"), "val x = 1\nval needle = 2\n")
            Files.writeString(dir.resolve("b.txt"), "needle in text\n")

            val outcome = GrepTool().run(obj("""{"pattern":"needle","include":"*.kt"}"""), ctx)
            assertFalse(outcome.isError)
            assertTrue(outcome.output.contains("a.kt:2:"), outcome.output)
            assertFalse(outcome.output.contains("b.txt"), outcome.output)
        }
    }

    @Test
    fun pathEscapeIsRejected() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val read = ReadTool().run(obj("""{"path":"../secret.txt"}"""), ctx)
            assertTrue(read.isError, read.output)
            val write = WriteTool().run(obj("""{"path":"../secret.txt","content":"x"}"""), ctx)
            assertTrue(write.isError, write.output)
            assertFalse(Files.exists(dir.parent.resolve("secret.txt")))
        }
    }

    @Test
    fun questionReturnsScriptedAnswer() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir, scriptedAnswer = listOf("yes", "maybe"))
            val outcome = QuestionTool().run(
                obj("""{"question":"Proceed?","options":["yes","no","maybe"],"multiple":true}"""),
                ctx,
            )
            assertFalse(outcome.isError)
            assertEquals("yes, maybe", outcome.output)
            assertEquals("Proceed?", ctx.lastQuestion)
            assertEquals(listOf("yes", "no", "maybe"), ctx.lastOptions)
            assertTrue(ctx.lastMultiple)
        }
    }

    @Test
    fun questionWithoutOptionsIsFreeForm() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir, scriptedAnswer = listOf("typed answer"))
            val outcome = QuestionTool().run(obj("""{"question":"Name?"}"""), ctx)
            assertFalse(outcome.isError)
            assertEquals("typed answer", outcome.output)
            assertTrue(ctx.lastOptions.isEmpty())
            assertFalse(ctx.lastMultiple)
        }
    }

    @Test
    fun todoWriteRendersChecklist() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val outcome = TodoWriteTool().run(
                obj(
                    """
                    {"todos":[
                      {"content":"write tests","status":"done"},
                      {"content":"implement tools","status":"in_progress"},
                      {"content":"ship it","status":"pending"}
                    ]}
                    """.trimIndent(),
                ),
                ctx,
            )
            assertFalse(outcome.isError)
            assertTrue(outcome.output.contains("- [x] write tests"), outcome.output)
            assertTrue(outcome.output.contains("- [~] implement tools"), outcome.output)
            assertTrue(outcome.output.contains("- [ ] ship it"), outcome.output)

            val stored = ctx.todos()
            assertEquals(3, stored.size)
            assertEquals(1, ctx.setTodosCalls)
            assertEquals("write tests", stored[0].content)
            assertEquals(TodoStatus.DONE, stored[0].status)
            assertEquals(TodoStatus.IN_PROGRESS, stored[1].status)
            assertEquals(TodoStatus.PENDING, stored[2].status)
            assertTrue(stored.all { it.id.startsWith("todo_") }, "ids: ${stored.map { it.id }}")
        }
    }

    @Test
    fun bashEchoReturnsExitZeroAndOutput() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val outcome = BashTool().run(obj("""{"command":"echo hello"}"""), ctx)

            assertFalse(outcome.isError, outcome.output)
            assertTrue(outcome.output.startsWith("[exit 0]"), outcome.output)
            assertTrue(outcome.output.contains("hello"), outcome.output)
            assertEquals("0", outcome.metadata["exitCode"])
            assertTrue(ctx.permissionRequests >= 1)
            assertEquals("bash", ctx.lastPermissionTool)
            assertEquals("echo", ctx.lastPermissionPattern)
        }
    }

    @Test
    fun bashDeniedPermissionIsError() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            ctx.permissionAllowed = false
            val outcome = BashTool().run(obj("""{"command":"echo hello"}"""), ctx)

            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("denied", ignoreCase = true), outcome.output)
        }
    }

    @Test
    fun bashTimeoutIsReportedAsError() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val outcome = BashTool().run(obj("""{"command":"sleep 5","timeoutMs":300}"""), ctx)

            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("timed out", ignoreCase = true), outcome.output)
            assertEquals("true", outcome.metadata["timeout"])
        }
    }

    @Test
    fun bashDelegatesToInjectedShell() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val shell = FakeShell(ShellResult(exitCode = 0, output = "ok\n"))
            val outcome = BashTool(shell).run(obj("""{"command":"echo ok","timeoutMs":1234}"""), ctx)

            assertFalse(outcome.isError, outcome.output)
            assertEquals(1, shell.calls)
            assertEquals("echo ok", shell.lastCommand)
            assertEquals(dir.toAbsolutePath().normalize(), shell.lastCwd?.toAbsolutePath()?.normalize())
            assertEquals(1234L, shell.lastTimeoutMs)
            assertEquals("[exit 0]\nok\n", outcome.output)
        }
    }

    @Test
    fun bashZeroTimeoutMeansNoDeadline() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val shell = FakeShell(ShellResult(exitCode = 0, output = "ok\n"))
            val outcome = BashTool(shell).run(obj("""{"command":"serve","timeoutMs":0}"""), ctx)

            assertFalse(outcome.isError, outcome.output)
            assertEquals(0L, shell.lastTimeoutMs, "timeoutMs=0 must reach the executor as no-deadline")
            assertEquals("0", outcome.metadata["timeoutMs"])
        }
    }

    @Test
    fun bashFormatsTruncationMarker() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val shell = FakeShell(ShellResult(exitCode = 0, output = "body", truncated = true))
            val outcome = BashTool(shell).run(obj("""{"command":"emit"}"""), ctx)

            assertEquals(
                "[exit 0]\nbody\n…[output truncated at ${Limits.BASH_MAX_OUTPUT_CHARS} chars]",
                outcome.output,
            )
            assertEquals("true", outcome.metadata["truncated"])
        }
    }

    @Test
    fun bashFormatsTimeoutFromExecutor() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val shell = FakeShell(ShellResult(exitCode = -1, output = "partial", timedOut = true))
            val outcome = BashTool(shell).run(obj("""{"command":"sleep","timeoutMs":50}"""), ctx)

            assertTrue(outcome.isError, outcome.output)
            assertEquals("[timeout]\nCommand timed out after 50ms\npartial", outcome.output)
            assertEquals("true", outcome.metadata["timeout"])
            assertEquals("50", outcome.metadata["timeoutMs"])
        }
    }

    @Test
    fun bashDeniedPermissionSkipsExecutor() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            ctx.permissionAllowed = false
            val shell = FakeShell(ShellResult(exitCode = 0, output = "never"))
            val outcome = BashTool(shell).run(obj("""{"command":"echo hi"}"""), ctx)

            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("denied", ignoreCase = true), outcome.output)
            assertEquals(0, shell.calls)
        }
    }

    @Test
    fun applyPatchAddsThenUpdatesFile() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val add = ApplyPatchTool().run(
                obj(
                    """
                    {"patchText":"*** Begin Patch\n*** Add File: hello.txt\n+line one\n+line two\n+line three\n*** End Patch"}
                    """.trimIndent(),
                ),
                ctx,
            )
            assertFalse(add.isError, add.output)
            assertEquals("line one\nline two\nline three\n", Files.readString(dir.resolve("hello.txt")))
            assertNotNull(add.diff)
            assertTrue(add.diff!!.contains("+line one"), add.diff!!)

            val update = ApplyPatchTool().run(
                obj(
                    """
                    {"patchText":"*** Begin Patch\n*** Update File: hello.txt\n@@\n line one\n-line two\n+line TWO\n line three\n*** End Patch"}
                    """.trimIndent(),
                ),
                ctx,
            )
            assertFalse(update.isError, update.output)
            assertEquals("line one\nline TWO\nline three\n", Files.readString(dir.resolve("hello.txt")))
            assertTrue(update.diff!!.contains("-line two"), update.diff!!)
            assertTrue(update.diff!!.contains("+line TWO"), update.diff!!)
        }
    }

    @Test
    fun applyPatchMismatchedContextIsError() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            Files.writeString(dir.resolve("hello.txt"), "line one\nalpha\nline three\n")

            val outcome = ApplyPatchTool().run(
                obj(
                    """
                    {"patchText":"*** Begin Patch\n*** Update File: hello.txt\n@@\n line one\n-nope\n+whatever\n line three\n*** End Patch"}
                    """.trimIndent(),
                ),
                ctx,
            )

            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("context not found", ignoreCase = true), outcome.output)
            assertTrue(outcome.output.contains("nope"), outcome.output)
            assertEquals("line one\nalpha\nline three\n", Files.readString(dir.resolve("hello.txt")))
        }
    }

    @Test
    fun applyPatchRejectsEscapingPath() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val outcome = ApplyPatchTool().run(
                obj(
                    """
                    {"patchText":"*** Begin Patch\n*** Add File: ../escape.txt\n+oops\n*** End Patch"}
                    """.trimIndent(),
                ),
                ctx,
            )

            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("escape", ignoreCase = true), outcome.output)
            assertFalse(Files.exists(dir.parent.resolve("escape.txt")))
        }
    }

    @Test
    fun webfetchRejectsNonHttpUrl() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val fileUrl = WebFetchTool().run(obj("""{"url":"file:///etc/passwd"}"""), ctx)
            assertTrue(fileUrl.isError, fileUrl.output)
            assertTrue(fileUrl.output.contains("non-http", ignoreCase = true), fileUrl.output)

            val ftp = WebFetchTool().run(obj("""{"url":"ftp://example.com/x"}"""), ctx)
            assertTrue(ftp.isError, ftp.output)

            val garbage = WebFetchTool().run(obj("""{"url":"not a url"}"""), ctx)
            assertTrue(garbage.isError, garbage.output)
        }
    }

    @Test
    fun taskForwardsSpecAndReturnsSubagentText() = runTest {
        withTempDir { dir ->
            val child = SessionId("ses_child")
            val ctx = FakeToolContext(
                dir,
                scriptedSubagent = SubagentResult(
                    sessionId = child,
                    text = "the answer is 42",
                    ok = true,
                    childTokens = 1234,
                    costUsd = 0.005,
                ),
            )

            val outcome = TaskTool().run(
                obj(
                    """
                    {"description":"find the answer","prompt":"where is the answer?",
                     "agent":"explore","model":"openai/gpt-4o"}
                    """.trimIndent(),
                ),
                ctx,
            )

            assertFalse(outcome.isError, outcome.output)
            val spec = assertNotNull(ctx.lastSubagentSpec)
            assertEquals("find the answer", spec.description)
            assertEquals("where is the answer?", spec.prompt)
            assertEquals("explore", spec.agent)
            assertEquals("openai/gpt-4o", spec.model)
            assertTrue(outcome.output.contains("the answer is 42"), outcome.output)
            assertTrue(outcome.output.contains("explore"), outcome.output)
            assertTrue(outcome.output.contains("1234 tok"), outcome.output)
        }
    }

    @Test
    fun taskWithFailingSubagentIsError() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(
                dir,
                scriptedSubagent = SubagentResult(SessionId("ses_fail"), "boom", ok = false),
            )

            val outcome = TaskTool().run(
                obj("""{"description":"do it","prompt":"do the thing"}"""),
                ctx,
            )

            assertTrue(outcome.isError, outcome.output)
            assertTrue(outcome.output.contains("boom"), outcome.output)
            assertEquals(null, ctx.lastSubagentSpec?.agent)
        }
    }

    @Test
    fun taskMetadataCarriesChildSessionAndTokens() = runTest {
        withTempDir { dir ->
            val child = SessionId("ses_meta")
            val ctx = FakeToolContext(
                dir,
                scriptedSubagent = SubagentResult(child, "done", ok = true, childTokens = 77),
            )

            val outcome = TaskTool().run(
                obj("""{"description":"d","prompt":"p"}"""),
                ctx,
            )

            assertEquals("ses_meta", outcome.metadata["sessionId"])
            assertEquals("77", outcome.metadata["tokens"])
            assertNotNull(outcome.metadata["cost"])
        }
    }

    @Test
    fun symlinkDirectoryEscapeIsRejected() = runTest {
        withTempDir { dir ->
            val outside = Files.createTempDirectory("spindle-outside")
            try {
                Files.writeString(outside.resolve("secret.txt"), "TOP SECRET")
                val link = Files.createSymbolicLink(dir.resolve("link"), outside)
                val ctx = FakeToolContext(dir)

                val read = ReadTool().run(obj("""{"path":"link/secret.txt"}"""), ctx)
                assertTrue(read.isError, read.output)
                assertTrue(read.output.contains("escape", ignoreCase = true), read.output)

                val write = WriteTool().run(obj("""{"path":"link/new.txt","content":"pwned"}"""), ctx)
                assertTrue(write.isError, write.output)
                assertFalse(Files.exists(outside.resolve("new.txt")), "write escaped through symlink")

                val edit = EditTool().run(
                    obj("""{"path":"link/secret.txt","oldString":"TOP","newString":"BOTTOM"}"""),
                    ctx,
                )
                assertTrue(edit.isError, edit.output)
                assertEquals("TOP SECRET", Files.readString(outside.resolve("secret.txt")))

                val patch = ApplyPatchTool().run(
                    obj("""{"patchText":"*** Begin Patch\n*** Add File: link/evil.txt\n+x\n*** End Patch"}"""),
                    ctx,
                )
                assertTrue(patch.isError, patch.output)
                assertFalse(Files.exists(outside.resolve("evil.txt")), "patch escaped through symlink")

                val shell = FakeShell(ShellResult(0, "must not run"))
                val bash = BashTool(shell).run(obj("""{"command":"echo hi","cwd":"link"}"""), ctx)
                assertTrue(bash.isError, bash.output)
                assertEquals(0, shell.calls)

                assertTrue(Files.isSymbolicLink(link))
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun symlinkedFilesAndDirsAreSkippedByGlobAndGrep() = runTest {
        withTempDir { dir ->
            val outside = Files.createTempDirectory("spindle-outside")
            try {
                Files.writeString(outside.resolve("secret.txt"), "needle-secret\n")
                Files.createSymbolicLink(dir.resolve("alias.txt"), outside.resolve("secret.txt"))
                Files.createSymbolicLink(dir.resolve("linkdir"), outside)
                Files.writeString(dir.resolve("real.txt"), "public needle\n")
                val ctx = FakeToolContext(dir)

                val glob = GlobTool().run(obj("""{"pattern":"**/*.txt"}"""), ctx)
                assertFalse(glob.isError, glob.output)
                assertTrue(glob.output.contains("real.txt"), glob.output)
                assertFalse(glob.output.contains("alias.txt"), glob.output)
                assertFalse(glob.output.contains("linkdir"), glob.output)

                val grep = GrepTool().run(obj("""{"pattern":"needle"}"""), ctx)
                assertFalse(grep.isError, grep.output)
                assertFalse(grep.output.contains("alias.txt"), grep.output)
                assertFalse(grep.output.contains("secret"), grep.output)
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun readRefusesOversizedAndBinaryFiles() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            Files.write(dir.resolve("big.txt"), ByteArray(Limits.READ_MAX_BYTES + 1) { 'a'.toByte() })
            val started = System.nanoTime()
            val big = ReadTool().run(obj("""{"path":"big.txt"}"""), ctx)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(big.isError, big.output)
            assertTrue(big.output.contains("too large", ignoreCase = true), big.output)
            assertTrue(elapsedMs < 5_000, "oversize guard took ${elapsedMs}ms")
            assertEquals("true", big.metadata["truncated"])

            Files.write(dir.resolve("nul.bin"), byteArrayOf('a'.toByte(), 0, 'b'.toByte()))
            val binary = ReadTool().run(obj("""{"path":"nul.bin"}"""), ctx)
            assertTrue(binary.isError, binary.output)
            assertTrue(binary.output.contains("binary", ignoreCase = true), binary.output)
        }
    }

    @Test
    fun grepBoundsCatastrophicBacktracking() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            Files.writeString(dir.resolve("redos.txt"), "a".repeat(40_000) + "b\n")
            val started = System.nanoTime()
            val outcome = GrepTool().run(obj("""{"pattern":"(a+)+$"}"""), ctx)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 5_000, "grep took ${elapsedMs}ms on a pathological pattern")
            assertTrue(outcome.isError, "expected the regex budget to abort, got: ${outcome.output}")
        }
    }

    @Test
    fun applyPatchPartialFailureRevertsAllWrites() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            Files.writeString(dir.resolve("blocker"), "orig")
            val outcome = ApplyPatchTool().run(
                obj(
                    """
                    {"patchText":"*** Begin Patch\n*** Add File: ok.txt\n+ok\n*** Add File: blocker/inside.txt\n+x\n*** End Patch"}
                    """.trimIndent(),
                ),
                ctx,
            )
            assertTrue(outcome.isError, outcome.output)
            assertFalse(Files.exists(dir.resolve("ok.txt")), "partial write must be rolled back")
            assertTrue(Files.isRegularFile(dir.resolve("blocker")))
            assertEquals("orig", Files.readString(dir.resolve("blocker")))
        }
    }

    @Test
    fun webfetchRejectsLoopbackTargets() = runTest {
        withTempDir { dir ->
            val ctx = FakeToolContext(dir)
            val loopback = WebFetchTool().run(obj("""{"url":"http://127.0.0.1:1/"}"""), ctx)
            assertTrue(loopback.isError, loopback.output)
            assertTrue(loopback.output.contains("Refusing", ignoreCase = true), loopback.output)

            val localhost = WebFetchTool().run(obj("""{"url":"http://localhost/"}"""), ctx)
            assertTrue(localhost.isError, localhost.output)
        }
    }

    @Test
    fun webfetchRejectsRedirectToLoopback() = runTest {
        val transport = object : HttpResponseTransport {
            override suspend fun get(uri: URI, maxBytes: Int): HttpResponseData {
                assertEquals("8.8.8.8", uri.host)
                return HttpResponseData(
                    status = 302,
                    location = "http://127.0.0.1:1/",
                    body = ByteArray(0),
                    truncated = false,
                )
            }
        }
        val result = fetchFollowingRedirects(URI("http://8.8.8.8/"), transport, 1024)
        assertTrue(result is FetchResult.Failure, "expected redirect rejection, got: $result")
        assertTrue(
            (result as FetchResult.Failure).message.contains("Refusing", ignoreCase = true),
            result.message,
        )
    }

    @Test
    fun webfetchSurfacesHttpStatusInOutput() = runTest {
        withTempDir { dir ->
            val transport = object : HttpResponseTransport {
                override suspend fun get(uri: URI, maxBytes: Int): HttpResponseData =
                    HttpResponseData(
                        status = 200,
                        location = null,
                        body = "<html><body><h1>Hello</h1></body></html>".toByteArray(),
                        truncated = false,
                    )
            }
            val outcome = WebFetchTool(transport)
                .run(obj("""{"url":"https://8.8.8.8/page"}"""), FakeToolContext(dir))
            assertFalse(outcome.isError, outcome.output)
            assertTrue(outcome.output.startsWith("HTTP 200"), outcome.output)
            assertEquals("200", outcome.metadata["status"])
        }
    }

    @Test
    fun blockedAddressReasonAllowsUnresolvedOnlyWhenProxyCanResolve() {
        // Reserved .invalid TLD never resolves. A proxy-only sandbox lets the
        // proxy do the lookup, so the guard must not veto the fetch.
        assertNull(blockedAddressReason("does-not-exist.invalid", allowUnresolved = true))
        assertEquals(
            "could not resolve host",
            blockedAddressReason("does-not-exist.invalid", allowUnresolved = false),
        )
        // Dangerous hosts stay blocked even with a proxy in play.
        assertEquals("loopback host", blockedAddressReason("localhost", allowUnresolved = true))
        assertNotNull(blockedAddressReason("127.0.0.1", allowUnresolved = true))
    }

    @Test
    fun sourceDoesNotUseJdkHttpClient() {
        val root = listOf(
            File("src/main/kotlin/dev/spindle/tool"),
            File("tools/src/main/kotlin/dev/spindle/tool"),
        ).firstOrNull { it.isDirectory } ?: return
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("java.net.http") }
            .toList()
        assertTrue(offenders.isEmpty(), "still reference java.net.http: $offenders")
    }

    @Test
    fun defaultRegistryIncludesAllToolsWithSchemas() {
        val registry = DefaultTools.registry()
        val specs = registry.specs
        val names = specs.map { it.name }.toSet()
        assertEquals(
            setOf(
                "read", "write", "edit", "bash", "apply_patch", "glob",
                "grep", "todowrite", "webfetch", "websearch", "question", "task",
                "skill", "external-directory",
            ),
            names,
        )
        assertEquals(14, specs.size)
        for (spec in specs) {
            assertTrue(spec.description.isNotBlank(), "blank description for ${spec.name}")
            assertTrue(spec.parametersJson.isNotBlank(), "blank schema for ${spec.name}")
            val parsed = json.parseToJsonElement(spec.parametersJson)
            assertTrue(parsed is JsonObject, "schema for ${spec.name} is not a JSON object")
            assertEquals(
                "object",
                (parsed as JsonObject)["type"]?.toString()?.trim('"'),
                "schema type for ${spec.name}",
            )
        }
    }
}
