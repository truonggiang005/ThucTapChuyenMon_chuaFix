package com.smartschedule.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapacityRulesTest {

    private static final LocalDateTime D = LocalDateTime.of(2026, 10, 20, 0, 0);

    private static LocalDateTime[] iv(int fromHour, int toHour) {
        return new LocalDateTime[]{D.plusHours(fromHour), D.plusHours(toHour)};
    }

    @Test
    void noIntervalsMeansZero() {
        assertEquals(0, CapacityRules.peakConcurrency(new ArrayList<>(), D.plusHours(8), D.plusHours(16)));
    }

    @Test
    void backToBackShiftsDoNotOverlap() {
        List<LocalDateTime[]> l = List.of(iv(6, 10), iv(10, 14));
        assertEquals(1, CapacityRules.peakConcurrency(l, D.plusHours(6), D.plusHours(14)));
    }

    @Test
    void peakIsNotJustTheCountOfOverlappingShifts() {
        // 3 ca chạm khoảng [8,16) nhưng không bao giờ có quá 2 ca chạy cùng lúc
        List<LocalDateTime[]> l = List.of(iv(6, 10), iv(9, 12), iv(12, 18));
        assertEquals(2, CapacityRules.peakConcurrency(l, D.plusHours(8), D.plusHours(16)));
    }

    @Test
    void fullyStackedShiftsReachCount() {
        List<LocalDateTime[]> l = List.of(iv(8, 16), iv(8, 16), iv(8, 16));
        assertEquals(3, CapacityRules.peakConcurrency(l, D.plusHours(8), D.plusHours(16)));
    }

    @Test
    void intervalsOutsideWindowAreIgnored() {
        List<LocalDateTime[]> l = List.of(iv(0, 6), iv(20, 23));
        assertEquals(0, CapacityRules.peakConcurrency(l, D.plusHours(8), D.plusHours(16)));
    }
}
