/**
 * VIEW - các hàm định dạng hiển thị.
 *
 * Ngày giờ từ server là LocalDateTime dạng chuỗi ISO KHÔNG múi giờ ("2026-11-02T07:00:00") theo giờ địa phương của ca.
 * Vì vậy định dạng bằng cách cắt chuỗi, không đi qua Date/toLocale*: kết quả không phụ thuộc múi giờ của trình duyệt.
 */

import { pad2, dateKey, parseKey, toKey, addDays, weekStartKey } from '../model/dates.js';

export { dateKey, parseKey, toKey, addDays, weekStartKey };

export const WEEKDAYS_VI = ['Chủ nhật', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];

/** "2026-11-02" → "Thứ 2, 02/11/2026" */
export function formatDateKey(key) {
    const d = parseKey(key);
    if (!d) return String(key ?? '');
    return `${WEEKDAYS_VI[d.getDay()]}, ${pad2(d.getDate())}/${pad2(d.getMonth() + 1)}/${d.getFullYear()}`;
}

/** "2026-11-02" (thứ Hai) → "Tuần 02/11 – 08/11/2026" */
export function formatWeekRange(weekKey) {
    const a = parseKey(weekKey);
    const b = parseKey(addDays(weekKey, 6));
    if (!a || !b) return String(weekKey ?? '');
    return `Tuần ${pad2(a.getDate())}/${pad2(a.getMonth() + 1)} – ${pad2(b.getDate())}/${pad2(b.getMonth() + 1)}/${b.getFullYear()}`;
}

/** "2026-11-02T07:00:00" → "07:00" */
export function formatTime(iso) {
    const m = /T(\d{2}):(\d{2})/.exec(String(iso ?? ''));
    return m ? `${m[1]}:${m[2]}` : String(iso ?? '');
}

/** "02/11 07:00" (kèm ngày, dùng khi thẻ không nằm dưới tiêu đề ngày) */
export function formatDateTime(iso) {
    const key = dateKey(iso);
    const d = parseKey(key);
    return d ? `${pad2(d.getDate())}/${pad2(d.getMonth() + 1)} ${formatTime(iso)}` : String(iso ?? '');
}

export function durationHours(start, end) {
    const a = new Date(String(start ?? ''));
    const b = new Date(String(end ?? ''));
    const h = (b - a) / 3600000;
    return Number.isFinite(h) && h >= 0 ? h : 0;
}

/** "Thứ 2 02/11 · 07:00 → 15:00 (8.0h)" — có ngày để không bị nhầm giữa các tuần */
export function formatTimeRange(start, end) {
    const d = parseKey(dateKey(start));
    if (!d) return `${start} → ${end}`;
    const day = `${WEEKDAYS_VI[d.getDay()]} ${pad2(d.getDate())}/${pad2(d.getMonth() + 1)}`;
    const sameDay = dateKey(start) === dateKey(end);
    const endStr = sameDay ? formatTime(end) : formatDateTime(end);
    return `${day} · ${formatTime(start)} → ${endStr} (${durationHours(start, end).toFixed(1)}h)`;
}

export function formatStatus(status) {
    const map = {
        ASSIGNED: 'Đã gán',
        OPEN: 'Đang mở',
        TAKEN: 'Đã nhận',
        FORCE_ASSIGNED: 'Đã gán (hệ thống)',
        UNFILLED: 'Bỏ trống',
        COMPLETED: 'Đã hoàn thành',
    };
    return map[status] || status;
}

export function getTimeAgo(date, nowMs = Date.now()) {
    const t = date instanceof Date ? date.getTime() : new Date(date).getTime();
    if (!Number.isFinite(t)) return '';
    const seconds = Math.floor((nowMs - t) / 1000);
    if (seconds < 60) return 'Vừa xong';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes} phút trước`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} giờ trước`;
    return `${Math.floor(hours / 24)} ngày trước`;
}

/** Ép về số trong [0,100] để dùng an toàn làm độ rộng thanh điểm. */
export function pct(value) {
    const n = Number(value);
    return Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0;
}

/** Làm tròn điểm để hiển thị ("82") hoặc "—" nếu không có. */
export function scoreText(value) {
    const n = Number(value);
    return value === null || value === undefined || !Number.isFinite(n) ? '—' : String(Math.round(n));
}
