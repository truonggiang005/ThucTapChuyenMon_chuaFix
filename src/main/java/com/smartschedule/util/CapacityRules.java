package com.smartschedule.util;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tính số người làm đồng thời tối đa tại một chi nhánh (dùng để kiểm tra Branch.maxCapacity - A14).
 */
public final class CapacityRules {

    private CapacityRules() {}

    private record Event(LocalDateTime at, int delta) {}

    /**
     * Số ca chạy đồng thời lớn nhất trong khoảng [start, end), chỉ tính các khoảng đã cho (KHÔNG gồm ca mới).
     * Khoảng nửa mở: ca kết thúc đúng lúc ca khác bắt đầu thì KHÔNG chồng nhau.
     *
     * @param intervals mỗi phần tử là {bắt đầu, kết thúc}
     */
    public static int peakConcurrency(List<LocalDateTime[]> intervals, LocalDateTime start, LocalDateTime end) {
        List<Event> events = new ArrayList<>();
        for (LocalDateTime[] iv : intervals) {
            LocalDateTime s = iv[0].isBefore(start) ? start : iv[0];
            LocalDateTime e = iv[1].isAfter(end) ? end : iv[1];
            if (!e.isAfter(s)) {
                continue;
            }
            events.add(new Event(s, +1));
            events.add(new Event(e, -1));
        }
        // Cùng thời điểm: xử lý "kết thúc" (-1) trước "bắt đầu" (+1)
        events.sort(Comparator.comparing(Event::at).thenComparingInt(Event::delta));
        int current = 0;
        int peak = 0;
        for (Event ev : events) {
            current += ev.delta();
            peak = Math.max(peak, current);
        }
        return peak;
    }
}
