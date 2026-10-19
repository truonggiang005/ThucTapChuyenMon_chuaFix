/**
 * MODEL - tiện ích ngày thuần (không đụng DOM, không phụ thuộc múi giờ trình duyệt).
 * Ngày giờ từ server là LocalDateTime dạng chuỗi ISO KHÔNG múi giờ ("2026-11-02T07:00:00") theo giờ địa phương của ca,
 * nên "khóa ngày" lấy bằng cách cắt chuỗi, và các phép tính ngày dùng Date theo giờ địa phương (không qua UTC).
 */

export const pad2 = (n) => String(n).padStart(2, '0');

/** "2026-11-02T07:00:00" → "2026-11-02" ('' nếu không đọc được) */
export function dateKey(iso) {
    const m = /^(\d{4}-\d{2}-\d{2})/.exec(String(iso ?? ''));
    return m ? m[1] : '';
}

/** "2026-11-02" → Date lúc 00:00 giờ địa phương (không lệch ngày do UTC) */
export function parseKey(key) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(key ?? ''));
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
}

export function toKey(date) {
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

export function addDays(key, n) {
    const d = parseKey(key);
    if (!d) return key;
    d.setDate(d.getDate() + n);
    return toKey(d);
}

/** Thứ Hai của tuần chứa ngày này (tuần làm việc bắt đầu thứ Hai, giống quy tắc giờ/tuần ở server). */
export function weekStartKey(key) {
    const d = parseKey(key);
    if (!d) return '';
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
    return toKey(d);
}
