package dev.lumen.app.platform

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guest resource limits and thermal hysteresis, as PLAIN JVM tests.
 *
 * Deliberately not Robolectric: the Robolectric suite cannot execute in this
 * project's dev sandbox (no conscrypt native for aarch64), so anything worth
 * actually verifying has to avoid Android statics. `limitScript` is pure string
 * work and `pauseReasonFor` takes a plain [ResourcePolicy.Battery], so neither
 * needs a Context.
 */
class ResourceLimitTest {

    @Before
    fun reset() {
        ResourcePolicy.resetForTest()
    }

    @Test
    fun `limitScript prepends the memory cap before the command`() {
        val out = ResourcePolicy.limitScript("echo hi")

        assertTrue(
            out.startsWith("ulimit -v ${ResourcePolicy.GUEST_MEMORY_LIMIT_KB}"),
            "memory cap must be applied first, got: $out",
        )
        assertTrue(out.endsWith("; echo hi"), "the original command must remain the tail, got: $out")
    }

    @Test
    fun `limitScript uses a semicolon so a refused limit does not block the command`() {
        val out = ResourcePolicy.limitScript("echo hi")

        // `&&` would abort the command when a shell refuses `ulimit -v`; the
        // guards must be joined with `;` and each tolerates its own failure.
        assertFalse(out.contains("&&"), "a refused limit must not stop the command: $out")
        assertTrue(out.contains("2>/dev/null"), "a shell without -v must not print an error: $out")
    }

    @Test
    fun `limitScript applies the open-files cap too`() {
        val out = ResourcePolicy.limitScript("run")

        assertTrue(
            out.contains("ulimit -n ${ResourcePolicy.GUEST_OPEN_FILES_LIMIT}"),
            "fd cap missing, got: $out",
        )
    }

    @Test
    fun `limitScript leaves the command intact after the guards`() {
        val command = "cd /tmp && make all 2>&1 | tail -20"

        val out = ResourcePolicy.limitScript(command)

        assertTrue(out.endsWith("; $command"), "the command must be passed through verbatim, got: $out")
    }

    @Test
    fun `a build command is exempt from the memory cap but keeps the fd cap`() {
        // A JVM reserves several GB of address space; capping -v at 2 GiB makes
        // ./gradlew die with a native "insufficient memory" error that looks
        // like real OOM. Measured on this project.
        val out = ResourcePolicy.limitScript("./gradlew test")

        assertFalse(
            out.contains("ulimit -v"),
            "a build must not get the address-space cap, got: $out",
        )
        assertTrue(
            out.contains("ulimit -n ${ResourcePolicy.GUEST_OPEN_FILES_LIMIT}"),
            "the fd cap still applies to a build, got: $out",
        )
        assertTrue(out.endsWith("; ./gradlew test"))
    }

    @Test
    fun `memory hungry launchers are recognised by basename and path`() {
        listOf(
            "./gradlew assembleDebug",
            "/usr/bin/java -jar x.jar",
            "kotlinc Foo.kt",
            "npm run build",
            "python3 setup.py",
            "/opt/node/bin/node index.js",
        ).forEach { cmd ->
            assertTrue(
                ResourcePolicy.isMemoryHungry(cmd),
                "expected '$cmd' to be treated as memory-hungry",
            )
        }
    }

    @Test
    fun `ordinary commands still get the memory cap`() {
        listOf("ls -la", "git status", "echo hi", "rg foo", "cat file.txt").forEach { cmd ->
            assertFalse(
                ResourcePolicy.isMemoryHungry(cmd),
                "expected '$cmd' to keep the cap",
            )
            assertTrue(
                ResourcePolicy.limitScript(cmd).contains("ulimit -v"),
                "'$cmd' should still be capped",
            )
        }
    }

    @Test
    fun `a composite script keeps the cap (conservative)`() {
        // Only the first token is examined; `cd x && ./gradlew` is the user's own
        // composition, so it is capped rather than exempted.
        assertFalse(ResourcePolicy.isMemoryHungry("cd sub && ./gradlew test"))
    }

    @Test
    fun `a hot device pauses and a cool one does not`() {
        val hot = ResourcePolicy.Battery(percent = 80, charging = false, temperatureTenthsC = 450)
        val cool = ResourcePolicy.Battery(percent = 80, charging = false, temperatureTenthsC = 300)

        assertNotNull(ResourcePolicy.pauseReasonFor(hot))
        assertNull(ResourcePolicy.pauseReasonFor(cool))
    }

    @Test
    fun `thermal pause latches until the device cools below the resume threshold`() {
        // Exactly at the pause threshold: pauses.
        val atLimit = ResourcePolicy.Battery(
            percent = 80,
            charging = false,
            temperatureTenthsC = ResourcePolicy.THERMAL_PAUSE_TENTHS_C,
        )
        assertNotNull(ResourcePolicy.pauseReasonFor(atLimit), "at the threshold must pause")

        // A hair below the threshold but still above the resume threshold: stays
        // paused, so the device cannot flap between states on every poll.
        val justBelowLimit = ResourcePolicy.Battery(
            percent = 80,
            charging = false,
            temperatureTenthsC = ResourcePolicy.THERMAL_PAUSE_TENTHS_C - 10,
        )
        assertNotNull(
            ResourcePolicy.pauseReasonFor(justBelowLimit),
            "must stay paused until it cools past the hysteresis band",
        )

        // Exactly at the bottom of the band it is STILL paused: the band is
        // [T - H, T), so the boundary is unambiguous rather than both states.
        val atBandEdge = ResourcePolicy.Battery(
            percent = 80,
            charging = false,
            temperatureTenthsC = ResourcePolicy.THERMAL_PAUSE_TENTHS_C -
                ResourcePolicy.RESUME_HYSTERESIS_TENTHS_C,
        )
        assertNotNull(ResourcePolicy.pauseReasonFor(atBandEdge), "the band edge is still paused")

        // Strictly below the band: resumes, and stays resumed.
        val cooled = atBandEdge.copy(temperatureTenthsC = atBandEdge.temperatureTenthsC - 1)
        assertNull(ResourcePolicy.pauseReasonFor(cooled), "cooled device resumes")
        assertNull(ResourcePolicy.pauseReasonFor(cooled), "and stays resumed")
    }

    @Test
    fun `a low flat battery pauses but charging overrides it`() {
        val flat = ResourcePolicy.Battery(
            percent = ResourcePolicy.LOW_BATTERY_PERCENT,
            charging = false,
            temperatureTenthsC = 300,
        )
        val charging = flat.copy(charging = true)

        assertNotNull(ResourcePolicy.pauseReasonFor(flat))
        assertNull(ResourcePolicy.pauseReasonFor(charging), "charging overrides a low battery")
    }

    @Test
    fun `an unreadable device is never paused`() {
        assertNull(ResourcePolicy.pauseReasonFor(null), "unknown battery must not stall every run")
    }

    @Test
    fun `the constants stay within a sane range`() {
        assertTrue(ResourcePolicy.GUEST_MEMORY_LIMIT_KB > 0, "a cap must be configured")
        assertTrue(
            ResourcePolicy.RESUME_HYSTERESIS_TENTHS_C > 0 &&
                ResourcePolicy.RESUME_HYSTERESIS_TENTHS_C < ResourcePolicy.THERMAL_PAUSE_TENTHS_C,
            "the hysteresis band must be strictly inside the pause threshold",
        )
        assertEquals(
            ResourcePolicy.Battery(1, true, 2),
            ResourcePolicy.Battery(1, true, 2),
            "Battery is a value type so threshold logic is testable",
        )
    }
}
