package fr.acinq.phoenixd

import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.utils.sat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TrampolineFeesOverrideTestsCommon {

    @Test
    fun `does not override trampoline fees when parameters are absent`() {
        assertNull(buildTrampolineFeesOverride(null, null, null, CltvExpiryDelta(576)))
    }

    @Test
    fun `builds trampoline fees override`() {
        val fees = buildTrampolineFeesOverride(
            feeBaseSat = 42,
            feeProportional = 5_000,
            cltvExpiryDelta = 720,
            defaultCltvExpiryDelta = CltvExpiryDelta(576)
        )

        assertEquals(42.sat, fees?.feeBase)
        assertEquals(5_000, fees?.feeProportional)
        assertEquals(CltvExpiryDelta(720), fees?.cltvExpiryDelta)
    }

    @Test
    fun `uses default cltv expiry delta when only fee parameters are provided`() {
        val fees = buildTrampolineFeesOverride(
            feeBaseSat = 42,
            feeProportional = 5_000,
            cltvExpiryDelta = null,
            defaultCltvExpiryDelta = CltvExpiryDelta(576)
        )

        assertEquals(CltvExpiryDelta(576), fees?.cltvExpiryDelta)
    }

    @Test
    fun `rejects partial trampoline fee override`() {
        assertFailsWith<IllegalArgumentException> {
            buildTrampolineFeesOverride(42, null, null, CltvExpiryDelta(576))
        }
        assertFailsWith<IllegalArgumentException> {
            buildTrampolineFeesOverride(null, 5_000, null, CltvExpiryDelta(576))
        }
        assertFailsWith<IllegalArgumentException> {
            buildTrampolineFeesOverride(null, null, 720, CltvExpiryDelta(576))
        }
    }

    @Test
    fun `rejects invalid trampoline fee override`() {
        assertFailsWith<IllegalArgumentException> {
            buildTrampolineFeesOverride(-1, 5_000, null, CltvExpiryDelta(576))
        }
        assertFailsWith<IllegalArgumentException> {
            buildTrampolineFeesOverride(42, -1, null, CltvExpiryDelta(576))
        }
        assertFailsWith<IllegalArgumentException> {
            buildTrampolineFeesOverride(42, 5_000, 0, CltvExpiryDelta(576))
        }
    }
}
