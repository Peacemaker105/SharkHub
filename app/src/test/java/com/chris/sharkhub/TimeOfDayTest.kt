package com.chris.sharkhub

import com.chris.sharkhub.ui.overview.Sun
import com.chris.sharkhub.ui.overview.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The scene-lighting clock: the sunrise equation against a known day in Perth, and the fixed-hours fallback. */
class TimeOfDayTest {
    private val perth: ZoneId = ZoneId.of("Australia/Perth")
    private val lat = -31.95
    private val lon = 115.86

    private fun at(h: Int, m: Int, day: LocalDate = LocalDate.of(2026, 10, 1)) = ZonedDateTime.of(day, LocalTime.of(h, m), perth)

    @Test
    fun perthSunriseAndSunsetOnOctoberFirst() {
        // Published times for Perth on 2026-10-01 are about 05:59 sunrise / 18:18 sunset (AWST).
        val times = Sun.riseSet(at(12, 0), lat, lon)
        assertNotNull(times)
        val (rise, set) = times!!
        val riseLocal = rise.atZone(perth).toLocalTime()
        val setLocal = set.atZone(perth).toLocalTime()
        assertTrue("sunrise $riseLocal", Duration.between(LocalTime.of(5, 59), riseLocal).abs() <= Duration.ofMinutes(12))
        assertTrue("sunset $setLocal", Duration.between(LocalTime.of(18, 18), setLocal).abs() <= Duration.ofMinutes(12))
    }

    @Test
    fun phasesFollowTheSunWithAFix() {
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.at(at(4, 30), lat, lon))
        assertEquals(TimeOfDay.DAWN, TimeOfDay.at(at(5, 40), lat, lon))
        assertEquals(TimeOfDay.DAWN, TimeOfDay.at(at(6, 20), lat, lon))
        assertEquals(TimeOfDay.DAY, TimeOfDay.at(at(12, 0), lat, lon))
        assertEquals(TimeOfDay.DUSK, TimeOfDay.at(at(18, 0), lat, lon))
        assertEquals(TimeOfDay.DUSK, TimeOfDay.at(at(18, 45), lat, lon))
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.at(at(21, 0), lat, lon))
    }

    @Test
    fun fixedHoursWithoutAFix() {
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.at(at(3, 0), null, null))
        assertEquals(TimeOfDay.DAWN, TimeOfDay.at(at(6, 30), null, null))
        assertEquals(TimeOfDay.DAY, TimeOfDay.at(at(12, 0), null, null))
        assertEquals(TimeOfDay.DUSK, TimeOfDay.at(at(18, 0), null, null))
        assertEquals(TimeOfDay.NIGHT, TimeOfDay.at(at(22, 0), null, null))
    }

    @Test
    fun polarNightFallsBackToFixedHours() {
        // Svalbard in December: no sunrise, so the fixed hours decide rather than a crash or a blank
        val svalbard = ZonedDateTime.of(LocalDate.of(2026, 12, 21), LocalTime.of(12, 0), ZoneId.of("Arctic/Longyearbyen"))
        assertNull(Sun.riseSet(svalbard, 78.2, 15.6))
        assertEquals(TimeOfDay.DAY, TimeOfDay.at(svalbard, 78.2, 15.6))
    }
}
