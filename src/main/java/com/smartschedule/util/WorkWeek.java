package com.smartschedule.util;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Quy ước "tuần làm việc" dùng CHUNG cho mọi nơi tính giờ/tuần (A4).
 *
 * Tuần = [thứ Hai 00:00, thứ Hai kế tiếp 00:00) — nửa mở, nên ca kết thúc đúng 00:00 thứ Hai
 * thuộc về tuần cũ và không bị rơi khỏi tuần nào. Ca vắt qua ranh giới tuần (vd: Chủ nhật 22h → thứ Hai 02h)
 * được CHIA theo từng tuần mà nó chạm tới: mỗi tuần chỉ tính số giờ nằm trong tuần đó.
 */
public final class WorkWeek {

    private WorkWeek() {}

    /** Một phần của ca nằm trong một tuần. */
    public record Slice(LocalDateTime weekStart, LocalDateTime weekEnd, double hours) {}

    /** Thứ Hai 00:00 của tuần chứa ref. */
    public static LocalDateTime weekStart(LocalDateTime ref) {
        return ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay();
    }

    /** Thứ Hai 00:00 của tuần kế tiếp (cận trên LOẠI TRỪ của tuần chứa ref). */
    public static LocalDateTime weekEnd(LocalDateTime ref) {
        return weekStart(ref).plusDays(7);
    }

    public static double hoursBetween(LocalDateTime start, LocalDateTime end) {
        return Duration.between(start, end).toMinutes() / 60.0;
    }

    /** Chia [start, end) theo từng tuần mà khoảng thời gian chạm tới. Rỗng nếu end <= start. */
    public static List<Slice> slices(LocalDateTime start, LocalDateTime end) {
        List<Slice> out = new ArrayList<>();
        if (start == null || end == null || !end.isAfter(start)) {
            return out;
        }
        LocalDateTime ws = weekStart(start);
        while (ws.isBefore(end)) {
            LocalDateTime we = ws.plusDays(7);
            LocalDateTime from = start.isAfter(ws) ? start : ws;
            LocalDateTime to = end.isBefore(we) ? end : we;
            out.add(new Slice(ws, we, hoursBetween(from, to)));
            ws = we;
        }
        return out;
    }
}
