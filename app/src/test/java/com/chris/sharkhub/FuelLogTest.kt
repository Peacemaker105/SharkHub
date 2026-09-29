package com.chris.sharkhub

import com.chris.sharkhub.data.FuelEntry
import com.chris.sharkhub.data.FuelLog
import com.chris.sharkhub.data.MemoryFuelStore
import com.chris.sharkhub.data.Refill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fuel maths and the fill-up detector, without Android. */
class FuelLogTest {
    private fun fill(odo: Double, litres: Double, pct: Double? = null) = FuelEntry(odo.toLong(), odo, litres, pct)

    @Test fun economyNeedsTwoFills() {
        assertNull(FuelLog.averageL100(emptyList()))
        assertNull(FuelLog.averageL100(listOf(fill(20_000.0, 50.0))))
        // 55 L over 625 km
        assertEquals(8.8, FuelLog.averageL100(listOf(fill(20_000.0, 50.0), fill(20_625.0, 55.0)))!!, 1e-9)
    }

    @Test fun averageIsTheLastThreeFills() {
        val fills = listOf(
            fill(10_000.0, 40.0), fill(10_500.0, 100.0),   // 20 L/100 — dropped, four economies back
            fill(11_000.0, 50.0), fill(11_500.0, 45.0), fill(12_000.0, 40.0),
        )
        assertEquals((10.0 + 9.0 + 8.0) / 3, FuelLog.averageL100(fills)!!, 1e-9)
    }

    @Test fun rangeIsWhatIsLeftOverTheAverage() {
        val fills = listOf(fill(20_000.0, 50.0), fill(20_600.0, 60.0))   // 10 L/100
        // half a 60 L tank at 10 L/100 = 300 km
        assertEquals(300.0, FuelLog.calculatedRangeKm(fills, 50.0)!!, 1e-9)
        assertNull(FuelLog.calculatedRangeKm(fills, null))
    }

    @Test fun gaugeRiseOfMoreThanFivePointsIsAFill() {
        val log = FuelLog(MemoryFuelStore())
        log.onGauge(40.0)
        log.onGauge(38.0)          // using fuel: the baseline follows it down
        log.onGauge(43.0)          // +5 exactly: not yet
        assertNull(log.refill.value)
        log.onGauge(90.0)
        assertEquals(Refill(38.0, 90.0), log.refill.value)
        log.onGauge(91.0)          // settles higher: the same prompt, extended
        log.onGauge(90.5)          // and a wobble down doesn't shrink it
        assertEquals(Refill(38.0, 91.0), log.refill.value)
    }

    @Test fun slowClimbAtThePumpIsOneFill() {
        val log = FuelLog(MemoryFuelStore())
        for (p in listOf(20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0)) log.onGauge(p)
        assertEquals(Refill(20.0, 26.0), log.refill.value)
        for (p in listOf(35.0, 50.0, 70.0, 95.0)) log.onGauge(p)
        assertEquals(Refill(20.0, 95.0), log.refill.value)
    }

    @Test fun notNowDoesNotAskAgainForTheSameFill() {
        val log = FuelLog(MemoryFuelStore())
        log.onGauge(20.0); log.onGauge(90.0)
        log.clearRefill()
        log.onGauge(92.0)
        assertNull(log.refill.value)
    }

    @Test fun offMeansNoPrompt() {
        val log = FuelLog(MemoryFuelStore(autoAsk = false))
        log.onGauge(10.0); log.onGauge(90.0)
        assertNull(log.refill.value)
    }

    @Test fun loggingAFillClearsThePromptAndResetsTheBaseline() {
        val log = FuelLog(MemoryFuelStore())
        log.onGauge(10.0); log.onGauge(95.0)
        log.add(FuelEntry(1L, 22_000.0, 51.0, 95.0))
        assertNull(log.refill.value)
        log.onGauge(99.0)          // +4 on the post-fill reading: no prompt
        assertNull(log.refill.value)
    }
}
