package dev.spindle.core.provider

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `0.0` prices are ambiguous: the model may be free, or nobody reported a price.
 * `isFree` must only be true for the former, because the UI renders "free".
 */
class ModelPricingTest {

    @Test
    fun `an unreported price is not free`() {
        val m = ModelInfo("p", "m")
        assertFalse(m.pricingKnown)
        assertFalse(m.isFree, "unknown pricing must never claim to be free")
    }

    @Test
    fun `a confirmed zero price is free`() {
        val m = ModelInfo("p", "m", inputCostPerM = 0.0, outputCostPerM = 0.0, pricingKnown = true)
        assertTrue(m.isFree)
    }

    @Test
    fun `a priced model is not free`() {
        val m = ModelInfo("p", "m", inputCostPerM = 0.28, outputCostPerM = 0.42, pricingKnown = true)
        assertFalse(m.isFree)
    }

    @Test
    fun `one free side with a paid side is not free`() {
        val m = ModelInfo("p", "m", inputCostPerM = 0.0, outputCostPerM = 2.0, pricingKnown = true)
        assertFalse(m.isFree, "a paid output means the model costs money")
    }
}
