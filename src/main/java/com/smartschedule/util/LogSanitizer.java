package com.smartschedule.util;

/** Loại ký tự xuống dòng/điều khiển khỏi dữ liệu người dùng trước khi ghi log (chống giả mạo dòng log - SEC-15). */
public final class LogSanitizer {
    private LogSanitizer() {}

    public static String clean(Object value) {
        if (value == null) return "null";
        String s = String.valueOf(value).replaceAll("[\\p{Cntrl}]", "_");
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
