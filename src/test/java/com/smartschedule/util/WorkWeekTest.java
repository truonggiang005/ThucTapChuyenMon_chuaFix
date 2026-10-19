package com.smartschedule.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkWeekTest {

    // 2026-10-19 là thứ Hai
    private static final LocalDateTime MON = LocalDateTime.of(2026, 10, 19, 0, 0);

    @Test
    void weekStartIsMondayMidnight() {
        assertEquals(MON, WorkWeek.weekStart(LocalDateTime.of(2026, 10, 25, 23, 59)));
        assertEquals(MON, WorkWeek.weekStart(MON));
        assertEquals(MON, WorkWeek.weekStart(LocalDateTime.of(2026, 10, 21, 8, 30)));
    }

    @Test
    void weekEndIsNextMondayExclusive() {
        assertEquals(MON.plusDays(7), WorkWeek.weekEnd(LocalDateTime.of(2026, 10, 25, 23, 59)));
    }

    @Test
    void shiftInsideOneWeekHasSingleSlice() {
        List<WorkWeek.Slice> s = WorkWeek.slices(MON.plusHours(8), MON.plusHours(16));
        assertEquals(1, s.size());
        assertEquals(8.0, s.get(0).hours(), 1e-9);
        assertEquals(MON, s.get(0).weekStart());
    }

    @Test
    void sundayNightToMondayMorningIsSplitAcrossTwoWeeks() {
        // Chủ nhật 22:00 → thứ Hai 02:00 (A4): 2h thuộc tuần cũ, 2h thuộc tuần mới
        LocalDateTime start = MON.plusDays(6).plusHours(22);
        LocalDateTime end = MON.plusDays(7).plusHours(2);
        List<WorkWeek.Slice> s = WorkWeek.slices(start, end);
        assertEquals(2, s.size());
        assertEquals(2.0, s.get(0).hours(), 1e-9);
        assertEquals(2.0, s.get(1).hours(), 1e-9);
        assertEquals(MON, s.get(0).weekStart());
        assertEquals(MON.plusDays(7), s.get(1).weekStart());
    }

    @Test
    void shiftEndingExactlyAtMondayMidnightBelongsToOldWeek() {
        LocalDateTime start = MON.plusDays(6).plusHours(20);
        List<WorkWeek.Slice> s = WorkWeek.slices(start, MON.plusDays(7));
        assertEquals(1, s.size());
        assertEquals(4.0, s.get(0).hours(), 1e-9);
        assertEquals(MON, s.get(0).weekStart());
    }

    @Test
    void emptyOrInvalidRangeGivesNoSlices() {
        assertTrue(WorkWeek.slices(MON, MON).isEmpty());
        assertTrue(WorkWeek.slices(MON.plusHours(1), MON).isEmpty());
        assertTrue(WorkWeek.slices(null, MON).isEmpty());
    }

    @Test
    void hoursKeepMinutes() {
        assertEquals(7.5, WorkWeek.hoursBetween(MON.plusHours(8), MON.plusHours(15).plusMinutes(30)), 1e-9);
    }
}
