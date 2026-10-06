package dev.lumen.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.spindle.core.provider.ModelInfo
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalogue is the embedded snapshot plus an optional cached live list. A
 * corrupt cache must never surface, and a blank key must never dial the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelCatalogueTest {

    private lateinit var context: Context
    private lateinit var keys: KeyStore
    private lateinit var catalogue: ModelCatalogue

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("lumen.keys", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(ModelCatalogue.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        keys = KeyStore(context)
        catalogue = ModelCatalogue(keys, context) { 42L }
    }

    @Test
    fun `models returns the embedded snapshot with no cache`() {
        assertEquals(ProviderCatalogue.defaultModels("deepseek"), catalogue.models("deepseek"))
    }

    @Test
    fun `a cached live id is merged over the embedded snapshot`() {
        catalogue.persist(
            "deepseek",
            listOf(ModelInfo("deepseek", "deepseek-future", contextWindow = 500_000)),
        )
        val models = catalogue.models("deepseek")
        assertTrue(models.any { it.id == "deepseek-future" }, "the cached live id must survive the merge")
        assertTrue(models.any { it.id == "deepseek-flash" }, "the embedded ids must survive the merge")
    }

    @Test
    fun `a corrupt cache blob is ignored`() {
        context.getSharedPreferences(ModelCatalogue.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ModelCatalogue.cacheKey("deepseek"), "{not json")
            .commit()
        assertEquals(ProviderCatalogue.defaultModels("deepseek"), catalogue.models("deepseek"))
    }

    @Test
    fun `refresh with a blank key skips the request for a keyed provider`() = runBlocking {
        val result = catalogue.refresh("opencode-go")
        assertEquals(ProviderCatalogue.defaultModels("opencode-go"), result)
        assertNull(catalogue.errors.value["opencode-go"], "a skipped request is not an error")
        assertTrue(catalogue.refreshing.value.isEmpty(), "refreshing must be cleared")
    }

    @Test
    fun `persist stamps the refresh time`() {
        assertEquals(0L, catalogue.lastRefreshed("opencode-go"))
        catalogue.persist("opencode-go", ProviderCatalogue.defaultModels("opencode-go"))
        assertEquals(42L, catalogue.lastRefreshed("opencode-go"))
    }
}
