/** VIEW - các hàm định dạng hiển thị. */

export function formatTimeRange(start, end) {
    try {
        const s = new Date(start);
        const e = new Date(end);
        const dateStr = s.toLocaleDateString('vi-VN', { weekday: 'short', day: '2-digit', month: '2-digit' });
        const startStr = s.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
        const endStr = e.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
        const hours = ((e - s) / 3600000).toFixed(1);
        return `${dateStr} · ${startStr} → ${endStr} (${hours}h)`;
    } catch {
        return `${start} → ${end}`;
    }
}

export function formatTime(dateStr) {
    try {
        return new Date(dateStr).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
    } catch {
        return dateStr;
    }
}

export function formatStatus(status) {
    const map = {
        ASSIGNED: 'Đã gán',
        OPEN: 'Đang mở',
        TAKEN: 'Đã nhận',
        FORCE_ASSIGNED: 'Đã gán (AI)',
        UNFILLED: 'Bỏ trống',
    };
    return map[status] || status;
}

export function getTimeAgo(date, nowMs = Date.now()) {
    const seconds = Math.floor((nowMs - date) / 1000);
    if (seconds < 60) return 'Vừa xong';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes} phút trước`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} giờ trước`;
    return `${Math.floor(hours / 24)} ngày trước`;
}

/** Ép về số trong [0,100] để dùng an toàn trong thuộc tính style (width). */
export function pct(value) {
    const n = Number(value);
    return Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0;
}
