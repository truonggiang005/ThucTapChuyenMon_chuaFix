/** MODEL - chuẩn hóa dữ liệu từ API trước khi đưa vào ViewModel (ARC-01). */
import { dateKey, weekStartKey } from './dates.js';

export const SHIFT_STATUSES = ['ASSIGNED', 'OPEN', 'TAKEN', 'FORCE_ASSIGNED', 'UNFILLED', 'COMPLETED'];
const NOTIFICATION_TYPES = ['success', 'warning', 'error', 'info'];

/** Chỉ chấp nhận trạng thái đã biết (dùng trong tên class CSS), tránh giá trị lạ lọt vào giao diện. */
export function safeStatus(status) {
    return SHIFT_STATUSES.includes(status) ? status : 'UNKNOWN';
}

export function mapShift(s) {
    return { ...s, status: safeStatus(s.status) };
}

export function mapNotification(n) {
    return {
        id: n.id,
        message: String(n.message ?? ''),
        type: NOTIFICATION_TYPES.includes(n.type) ? n.type : 'info',
        time: n.createdAt || null,
        read: !!n.read,
    };
}

export function toInt(value) {
    if (value === '' || value === null || value === undefined) return null;
    const n = parseInt(value, 10);
    return Number.isNaN(n) ? null : n;
}

/** Điểm AI luôn là số trong [0,100] hoặc null (null/undefined/chuỗi rỗng KHÔNG bị coi là 0). */
export function toScore(value) {
    if (value === null || value === undefined || value === '') return null;
    const n = Number(value);
    if (!Number.isFinite(n)) return null;
    return Math.max(0, Math.min(100, n));
}

/** datetime-local ("2026-11-02T07:00") → có đủ giây cho LocalDateTime của server */
export function withSeconds(t) {
    const s = String(t ?? '');
    return /T\d{2}:\d{2}$/.test(s) || /^\d{2}:\d{2}$/.test(s) ? s + ':00' : s;
}

/** Form tạo/sửa ca (giá trị chuỗi từ input) → payload gửi API. `version` dùng cho khóa lạc quan khi sửa. */
export function shiftFormToPayload(f) {
    const payload = {
        title: String(f.title || '').trim(),
        startTime: withSeconds(f.startTime),
        endTime: withSeconds(f.endTime),
        branchId: toInt(f.branchId),
        requiredSkillId: toInt(f.skillId),
        requiredLevel: toInt(f.level),
        assignedToId: toInt(f.employeeId),
    };
    const version = toInt(f.version);
    if (version !== null) payload.version = version;
    return payload;
}

/** Kết quả điểm của CHÍNH mình cho một ca (từ /api/shifts/my-scores) → dạng gọn cho giao diện. */
export function mapMyScore(row) {
    return {
        shiftId: row.shiftId,
        eligible: row.eligible !== false,
        reason: row.reason || null,
        score: toScore(row.matchScore),
        confidence: toScore(row.confidence),
        source: row.source || null,
    };
}

/**
 * Gom ca theo TUẦN rồi theo NGÀY (D1): [{ weekKey, days: [{ dayKey, shifts }] }], tăng dần theo thời gian.
 * Khóa ngày lấy từ chuỗi ISO nên không lệch do múi giờ; ca nhiều tuần không bị trộn chung.
 */
export function groupScheduleByWeek(shifts) {
    const sorted = [...(shifts || [])].filter(s => dateKey(s.startTime))
        .sort((a, b) => String(a.startTime).localeCompare(String(b.startTime)) || (a.id - b.id));
    const weeks = new Map();
    for (const s of sorted) {
        const day = dateKey(s.startTime);
        const wk = weekStartKey(day);
        if (!weeks.has(wk)) weeks.set(wk, new Map());
        const days = weeks.get(wk);
        if (!days.has(day)) days.set(day, []);
        days.get(day).push(s);
    }
    return [...weeks.entries()].map(([weekKey, days]) => ({
        weekKey,
        days: [...days.entries()].map(([dayKey, list]) => ({ dayKey, shifts: list })),
    }));
}

/** Ca đã/đang diễn ra (không còn sửa/nhả/gán được) so với thời điểm `nowIso` ("YYYY-MM-DDTHH:mm:ss" giờ địa phương). */
export function hasStarted(shift, nowIso) {
    return String(shift.startTime || '') <= String(nowIso || '');
}
