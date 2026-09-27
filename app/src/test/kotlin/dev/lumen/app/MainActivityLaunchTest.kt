package dev.lumen.app

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Launch the real Activity. This is the test that catches "it crashes on open".
 * No mocks of the screen — the actual MainActivity, ViewModel and Compose tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLaunchTest {

    @Test
    fun `MainActivity launches without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(!activity.isFinishing, "activity should be alive after launch")
            }
        }
    }

    @Test
    fun `activity survives a recreate (rotation)`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(!activity.isFinishing, "activity should survive recreate")
            }
        }
    }
}
