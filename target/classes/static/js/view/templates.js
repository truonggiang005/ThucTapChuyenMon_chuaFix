/**
 * VIEW - các template thuần (state → SafeHtml), không đụng DOM.
 * Không dùng onclick inline: nút mang data-action / data-* và được xử lý bằng event delegation (appView.js),
 * để tương thích Content-Security-Policy "script-src 'self'" (SEC-17).
 */
import { html, raw } from './html.js';
import { formatTime, formatTimeRange, formatStatus, getTimeAgo, pct } from './format.js';

const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thur', 'Fri', 'Sat'];
const DAY_NAMES_VI = ['CN', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];
const DAY_ORDER = ['Mon', 'Tue', 'Wed', 'Thur', 'Fri', 'Sat', 'Sun'];

const emptyState = (icon, text, extraStyle = '') => html`
    <div class="empty-state" ${raw(extraStyle)}>
        <div class="empty-state__icon">${icon}</div>
        <div class="empty-state__text">${text}</div>
    </div>`;

// ---------- Lịch cá nhân ----------
export function weeklySchedule(shifts) {
    if (!shifts || shifts.length === 0) return emptyState('📭', 'Chưa có ca nào được gán');

    const grouped = {};
    shifts.forEach(s => {
        const day = DAY_NAMES[new Date(s.startTime).getDay()];
        (grouped[day] = grouped[day] || []).push(s);
    });

    const groups = DAY_ORDER.filter(d => grouped[d] && grouped[d].length).map(day => html`
        <div class="day-group">
            <div class="day-group__label">${day}</div>
            <div class="day-group__shifts">
                ${grouped[day].map(s => {
                    const isForce = s.status === 'FORCE_ASSIGNED';
                    const statusClass = isForce ? 'force' : s.status.toLowerCase();
                    const canRelease = ['ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED'].includes(s.status);
                    return html`
                    <div class="schedule-card schedule-card--${statusClass}">
                        <div class="schedule-card__header">
                            <div class="schedule-card__title">Ca #${s.id} - ${s.requiredSkillName || s.title} <span class="schedule-card__status schedule-card__status--${s.status}">(${s.status})</span></div>
                        </div>
                        <div class="schedule-card__meta">
                            <span>📍 ${s.branchName || 'Chưa xác định'}</span>
                            <span>🕐 ${formatTime(s.startTime)} - ${formatTime(s.endTime)}</span>
                        </div>
                        ${canRelease ? html`
                            <div class="schedule-card__action">
                                <button class="btn-release${isForce ? ' btn-release--red' : ''}" data-action="release" data-id="${s.id}">Nhả ca</button>
                            </div>` : ''}
                    </div>`;
                })}
            </div>
        </div>`);

    return html`${groups}`;
}

export function busyEntries(busySchedules) {
    if (!busySchedules || busySchedules.length === 0) return html``;
    return html`${busySchedules.map(s => html`
        <div class="busy-entry-card">
            <button class="busy-entry-card__delete" data-action="delete-busy" data-id="${s.id}" title="Xóa">✕</button>
            <div class="busy-entry-card__title">
                🕐 | ${s.reason || 'Bận việc riêng'} (${s.reason || 'Lý do không xác định'})
            </div>
            <div class="busy-entry-card__detail">
                📅 ${DAY_NAMES_VI[new Date(s.startTime).getDay()]}, ${formatTime(s.startTime)} - ${formatTime(s.endTime)}
            </div>
        </div>`)}`;
}

// ---------- Chợ ca trực ----------
export function marketGrid(shifts, scores) {
    if (!shifts || shifts.length === 0) {
        return emptyState('📭', 'Không có ca trực nào đang mở', 'style="grid-column: 1/-1;"');
    }
    return html`${shifts.map(s => {
        const score = scores[s.id];
        const hasScore = score !== undefined && score !== null;
        let badgeClass = 'ai-badge--mid', badgeText = 'Phù hợp';
        if (hasScore) {
            if (score >= 80) { badgeClass = 'ai-badge--high'; badgeText = 'Phù hợp rất cao'; }
            else if (score >= 50) { badgeClass = 'ai-badge--mid'; badgeText = 'Phù hợp'; }
            else { badgeClass = 'ai-badge--low'; badgeText = 'Ít phù hợp'; }
        }
        return html`
        <div class="market-card">
            <div class="market-card__header">
                <div class="market-card__title">Ca làm #${s.id} - ${s.requiredSkillName || s.title} <span class="market-card__status">(MỞ)</span></div>
            </div>
            <div class="market-card__details">
                <span>🕐 ${formatTime(s.startTime)} - ${formatTime(s.endTime)}</span>
                <span>📍 ${s.branchName || 'Chưa xác định'}</span>
            </div>
            ${hasScore ? html`
                <div class="market-card__ai-row">
                    <span class="market-card__ai-score"><span class="ai-icon">🔵</span> AI Match Score: ${Math.round(score)}%</span>
                    <span class="ai-badge ${badgeClass}">${badgeText}</span>
                </div>` : ''}
            <div class="market-card__requirement">
                Yêu cầu: ${s.requiredSkillName || 'Không yêu cầu'} | Mức ${s.requiredLevel}
            </div>
            <div class="market-card__action" style="margin-top: 15px;">
                <button class="btn-take" style="width: 100%;" data-action="take" data-id="${s.id}">Đăng ký nhận ca</button>
            </div>
        </div>`;
    })}`;
}

// ---------- Tài khoản ----------
export function accountInfo(emp) {
    if (!emp) return emptyState('👤', 'Không tải được thông tin tài khoản');
    const row = (label, value) => html`
        <div class="account-info-item">
            <span class="account-info-item__label">${label}</span>
            <span class="account-info-item__value">${value}</span>
        </div>`;
    return html`<div class="account-info-list">
        ${row('Họ tên', emp.fullName)}
        ${row('Email', emp.email)}
        ${row('Giờ tối đa/tuần', (emp.maxHoursPerWeek || '—') + 'h')}
        ${row('Ca hoàn thành', emp.totalCompleted || 0)}
        ${row('Ca đã hủy', emp.totalCancelled || 0)}
        ${row('Đi trễ', (emp.lateArrivalCount || 0) + ' lần')}
        ${row('Giờ ưu tiên', emp.preferredTime || '—')}
        ${row('Chi nhánh chính', emp.primaryBranchName || '—')}
    </div>`;
}

// ---------- Quản lý: lưới ca ----------
export function adminShiftsGrid(shifts, { currentUserId, isAdmin, cachedIds }) {
    if (!shifts || shifts.length === 0) return emptyState('📭', 'Không có ca nào');

    return html`${shifts.map(s => {
        const isOpen = s.status === 'OPEN';
        const isMyShift = s.assignedToId && s.assignedToId === currentUserId;
        return html`
        <div class="shift-card shift-card--${s.status}">
            <div class="shift-card__header">
                <div>
                    <div class="shift-card__title">${s.title}</div>
                    <div class="shift-card__id">ID: #${s.id}</div>
                </div>
                <span class="shift-card__status status--${s.status}">${formatStatus(s.status)}</span>
            </div>
            <div class="shift-card__details">
                <div class="shift-card__detail"><span class="shift-card__detail-icon">🕐</span> ${formatTimeRange(s.startTime, s.endTime)}</div>
                <div class="shift-card__detail"><span class="shift-card__detail-icon">🏢</span> ${s.branchName || 'Chưa xác định'}</div>
                <div class="shift-card__detail"><span class="shift-card__detail-icon">🎯</span> ${s.requiredSkillName || 'Không yêu cầu'} — Level ${s.requiredLevel}</div>
                <div class="shift-card__detail"><span class="shift-card__detail-icon">👤</span> ${s.assignedToName || html`<em style="color: var(--accent-green)">Chưa có người nhận</em>`}</div>
            </div>
            <div class="shift-card__actions">
                ${isOpen ? html`<button class="btn btn--success btn--sm" data-action="take" data-id="${s.id}">✋ Nhận Ca</button>` : ''}
                ${isAdmin ? html`<button class="btn btn--ai btn--sm" data-action="show-candidates" data-id="${s.id}">🤖 AI Gợi Ý ${cachedIds.has(s.id) ? '(đã lưu)' : ''}</button>` : ''}
                ${isMyShift && (s.status === 'ASSIGNED' || s.status === 'TAKEN') ? html`<button class="btn btn--danger btn--sm" data-action="release" data-id="${s.id}">Nhả Ca</button>` : ''}
                <button class="btn btn--outline btn--sm" data-action="edit-shift" data-id="${s.id}">✏️ Sửa</button>
            </div>
        </div>`;
    })}`;
}

// ---------- Bảng gợi ý AI ----------
export function candidatesPanel(c) {
    if (!c.open) return html``;
    const title = html`🤖 AI Gợi Ý — Ca #${c.shiftId}`;

    if (c.loading) {
        return html`<div class="candidates-panel"><div class="candidates-panel__title"><span class="spinner"></span> Đang gọi AI chấm điểm cho ca #${c.shiftId}...</div></div>`;
    }
    if (c.error) {
        return html`<div class="candidates-panel">
            <div class="candidates-panel__title">${title}</div>
            ${emptyState('⚠️', c.error)}
        </div>`;
    }
    const list = (c.data && c.data.ranked_candidates) || [];
    if (list.length === 0) {
        return html`<div class="candidates-panel">
            <div class="candidates-panel__title">${title}</div>
            ${emptyState('😕', 'Không tìm thấy ứng viên phù hợp')}
        </div>`;
    }

    const rows = list.map((cand, i) => {
        const rank = i + 1;
        const rankClass = rank <= 3 ? `candidate-row__rank--${rank}` : 'candidate-row__rank--default';
        const score = Number(cand.match_score) || 0;
        const level = score >= 70 ? 'high' : score >= 40 ? 'mid' : 'low';
        const color = level === 'high' ? 'green' : level === 'mid' ? 'orange' : 'red';
        const b = cand.breakdown || {};
        return html`
        <div class="candidate-row">
            <div class="candidate-row__rank ${rankClass}">${rank}</div>
            <div class="candidate-row__info">
                <div class="candidate-row__name">${cand.full_name}</div>
                <div class="candidate-row__details">
                    Tin cậy: ${b.reliability ?? '—'}% ·
                    Kỹ năng: ${b.skill_fit ?? '—'}% ·
                    Giờ: ${b.time_fit ?? '—'}% ·
                    Khối lượng: ${b.workload_fit ?? '—'}% ·
                    Chi nhánh: ${b.location_fit ?? '—'}%
                </div>
                <div class="candidate-row__explanation" style="margin-top: 8px; font-size: 13px; color: var(--text-secondary); background: #f8fafc; padding: 6px 10px; border-radius: 4px; border-left: 3px solid var(--accent-blue);">
                    💡 <strong>AI nhận xét:</strong> ${b.explanation || 'Không có thông tin chi tiết.'}
                </div>
            </div>
            <div class="candidate-row__score">
                <div class="candidate-row__score-value" style="color: var(--accent-${color})">${score}%</div>
                <div class="candidate-row__score-label">Match Score</div>
                <div class="score-bar"><div class="score-bar__fill score-bar__fill--${level}" style="width: ${pct(score)}%"></div></div>
            </div>
            <div class="candidate-row__action">
                <button class="btn btn--success btn--sm" data-action="assign-candidate" data-shift-id="${c.shiftId}" data-employee-id="${cand.employee_id}">Gán ca</button>
            </div>
        </div>`;
    });

    const cacheTime = c.cachedAt ? new Date(c.cachedAt).toLocaleTimeString('vi-VN') : '';
    return html`<div class="candidates-panel">
        <div class="candidates-panel__title">
            ${title}
            <span style="margin-left: auto; font-size: 13px; font-weight: 400; color: var(--text-secondary);">
                ${c.data.total_candidates} ứng viên ${cacheTime ? `· Cache lúc ${cacheTime}` : ''}
            </span>
            <button class="btn btn--outline btn--sm" data-action="close-candidates" style="margin-left: 12px;">✕ Đóng</button>
        </div>
        ${rows}
    </div>`;
}

// ---------- Thông báo ----------
export function notificationList(list, nowMs = Date.now()) {
    if (!list || list.length === 0) return html`<div class="notification-dropdown__empty">Không có thông báo mới</div>`;
    const icons = { success: '✅', warning: '⚠️', error: '❌', info: 'ℹ️' };
    return html`${list.map(n => html`
        <div class="notification-item ${n.read ? '' : 'notification-item--unread'}" data-action="mark-read" data-id="${n.id}">
            <span class="notification-item__icon">${icons[n.type] || 'ℹ️'}</span>
            <div class="notification-item__content">
                <div class="notification-item__message">${n.message}</div>
                <div class="notification-item__time">${getTimeAgo(new Date(n.time), nowMs)}</div>
            </div>
        </div>`)}`;
}

// ---------- Ô chọn (select) ----------
export function options(placeholder, items, labelKey = 'name') {
    return html`<option value="">${placeholder}</option>${items.map(i => html`<option value="${i.id}">${i[labelKey]}</option>`)}`;
}
