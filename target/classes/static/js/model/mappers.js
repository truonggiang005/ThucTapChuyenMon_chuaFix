/** MODEL - chuẩn hóa dữ liệu từ API trước khi đưa vào ViewModel (ARC-01). */

export const SHIFT_STATUSES = ['ASSIGNED', 'OPEN', 'TAKEN', 'FORCE_ASSIGNED', 'UNFILLED'];

/** Chỉ chấp nhận trạng thái đã biết (dùng trong tên class CSS), tránh giá trị lạ lọt vào giao diện. */
export function safeStatus(status) {
    return SHIFT_STATUSES.includes(status) ? status : 'UNKNOWN';
}

export function mapShift(s) {
    return { ...s, status: safeStatus(s.status) };
}

export function toInt(value) {
    if (value === '' || value === null || value === undefined) return null;
    const n = parseInt(value, 10);
    return Number.isNaN(n) ? null : n;
}

/** Điểm AI luôn là số trong [0,100] hoặc null. */
export function toScore(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return null;
    return Math.max(0, Math.min(100, n));
}

/** Form tạo/sửa ca (giá trị chuỗi từ input) → payload gửi API. */
export function shiftFormToPayload(f) {
    return {
        title: String(f.title || '').trim(),
        startTime: f.startTime,
        endTime: f.endTime,
        branchId: toInt(f.branchId),
        requiredSkillId: toInt(f.skillId),
        requiredLevel: toInt(f.level),
        assignedToId: toInt(f.employeeId),
    };
}
