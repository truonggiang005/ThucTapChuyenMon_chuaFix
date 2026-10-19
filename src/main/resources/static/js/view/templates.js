/**
 * VIEW - các template thuần (state → SafeHtml), không đụng DOM.
 *
 * Quy tắc an toàn (SEC-05, C3):
 *  - Không onclick inline: nút mang data-action / data-* và được xử lý bằng event delegation (appView.js).
 *  - KHÔNG có thuộc tính style="..." trong markup (CSP style-src 'self'). Màu theo class; độ rộng thanh điểm đặt qua
 *    data-w rồi appView.applyDynamicStyles() gán bằng CSSOM (CSSOM không bị CSP chặn).
 */
import { html, raw } from './html.js';
import {
    formatTime, formatTimeRange, formatStatus, getTimeAgo, scoreText,
    formatDateKey, formatWeekRange, formatDateTime, durationHours, pct,
} from './format.js';
import { groupScheduleByWeek, hasStarted } from '../model/mappers.js';

const DISABLED = raw('disabled');

const emptyState = (icon, text, wide = false) => html`
    <div class="empty-state${wide ? ' empty-state--wide' : ''}">
        <div class="empty-state__icon" aria-hidden="true">${icon}</div>
        <div class="empty-state__text">${text}</div>
    </div>`;

const scoreLevel = (score) => (score >= 70 ? 'high' : score >= 40 ? 'mid' : 'low');

// ---------- Lịch cá nhân (theo TUẦN rồi theo NGÀY - D1) ----------
function scheduleCard(s, nowIso) {
    const isForce = s.status === 'FORCE_ASSIGNED';
    const statusClass = isForce ? 'force' : s.status.toLowerCase();
    const started = hasStarted(s, nowIso);
    const canRelease = ['ASSIGNED', 'TAKEN'].includes(s.status) && !started;
    return html`
        <article class="schedule-card schedule-card--${statusClass}">
            <div class="schedule-card__header">
                <div class="schedule-card__title">${s.title}
                    <span class="schedule-card__status schedule-card__status--${s.status}">${formatStatus(s.status)}</span>
                </div>
            </div>
            <div class="schedule-card__meta">
                <span>📍 ${s.branchName || 'Chưa xác định'}</span>
                <span>🕐 ${formatTime(s.startTime)} – ${formatTime(s.endTime)} (${durationHours(s.startTime, s.endTime).toFixed(1)}h)</span>
                ${s.requiredSkillName ? html`<span>🎯 ${s.requiredSkillName} · Lv.${s.requiredLevel}</span>` : ''}
                ${s.lateMarked ? html`<span class="text-warn">⚠️ Đã ghi nhận đến muộn</span>` : ''}
            </div>
            ${canRelease ? html`
                <div class="schedule-card__action">
                    <button class="btn-release" data-action="release" data-id="${s.id}">Nhả ca</button>
                </div>` : ''}
            ${isForce && !started ? html`
                <p class="schedule-card__hint">Ca do hệ thống gán bắt buộc vì sát giờ nên không thể nhả. Liên hệ quản lý nếu có việc đột xuất.</p>` : ''}
        </article>`;
}

export function weeklySchedule(shifts, { nowIso = '', todayKey = '' } = {}) {
    if (!shifts || shifts.length === 0) return emptyState('📭', 'Chưa có ca nào được gán');

    return html`${groupScheduleByWeek(shifts).map(w => html`
        <section class="week-group" aria-label="${formatWeekRange(w.weekKey)}">
            <h3 class="week-group__label">${formatWeekRange(w.weekKey)}</h3>
            ${w.days.map(d => html`
                <div class="day-group">
                    <div class="day-group__label${d.dayKey === todayKey ? ' day-group__label--today' : ''}">
                        ${formatDateKey(d.dayKey)}${d.dayKey === todayKey ? html` <span class="day-group__today">Hôm nay</span>` : ''}
                    </div>
                    <div class="day-group__shifts">${d.shifts.map(s => scheduleCard(s, nowIso))}</div>
                </div>`)}
        </section>`)}`;
}

export function busyEntries(busySchedules) {
    if (!busySchedules || busySchedules.length === 0) {
        return html`<p class="busy-empty">Bạn chưa đăng ký lịch bận sắp tới.</p>`;
    }
    return html`${busySchedules.map(s => html`
        <div class="busy-entry-card">
            <button class="busy-entry-card__delete" data-action="delete-busy" data-id="${s.id}"
                    title="Xóa lịch bận" aria-label="Xóa lịch bận ${formatDateTime(s.startTime)}">✕</button>
            <div class="busy-entry-card__title">🕐 ${s.reason || 'Bận việc riêng'}</div>
            <div class="busy-entry-card__detail">
                📅 ${formatDateTime(s.startTime)} → ${formatDateTime(s.endTime)}
            </div>
        </div>`)}`;
}

/** Thống kê THẬT từ dữ liệu (thay cho con số "10 ngày nghỉ" cố định - D5). */
export function busyStats(busySchedules) {
    const list = busySchedules || [];
    const hours = list.reduce((sum, s) => sum + durationHours(s.startTime, s.endTime), 0);
    return html`
        <h4>📊 Lịch bận sắp tới</h4>
        <p>Số lần đăng ký: <strong>${list.length}</strong> · Tổng thời lượng: <strong>${hours.toFixed(1)}h</strong></p>
        <p>Hệ thống sẽ không gán ca vào khung giờ bận. Nếu đã có ca trong khung giờ đó, hãy nhả ca trước.</p>`;
}

// ---------- Chợ ca trực ----------
function marketScore(s, market) {
    const row = market.scores[s.id];
    if (row && row.eligible === false) {
        return html`<div class="market-card__ineligible" role="note">⛔ Bạn chưa thể nhận ca này: ${row.reason || 'không đủ điều kiện'}</div>`;
    }
    if (row && row.score !== null && row.score !== undefined) {
        const level = scoreLevel(row.score);
        const label = level === 'high' ? 'Phù hợp rất cao' : level === 'mid' ? 'Phù hợp' : 'Ít phù hợp';
        return html`
            <div class="market-card__ai-row"
                 title="Ước tính nhanh theo quy tắc từ kỹ năng, lịch sử làm việc, khung giờ ưa thích và số giờ đã làm trong tuần. Không gọi Gemini.">
                <span class="market-card__ai-score">Điểm phù hợp (ước tính): <strong>${scoreText(row.score)}%</strong></span>
                <span class="ai-badge ai-badge--${level}">${label}</span>
                ${row.confidence !== null && row.confidence !== undefined
                    ? html`<span class="market-card__ai-conf">Độ tin cậy ${scoreText(row.confidence)}%</span>` : ''}
            </div>`;
    }
    if (market.status === 'loading') {
        return html`<div class="market-card__ai-row market-card__ai-row--muted"><span class="spinner" aria-hidden="true"></span> Đang tính điểm phù hợp…</div>`;
    }
    return html`<div class="market-card__ai-row market-card__ai-row--muted">Chưa có điểm gợi ý cho ca này</div>`;
}

export function marketGrid(shifts, market) {
    if (!shifts || shifts.length === 0) {
        return emptyState('📭', 'Không có ca trực nào đang mở', true);
    }
    return html`${shifts.map(s => {
        const row = market.scores[s.id];
        const blocked = row && row.eligible === false;
        return html`
        <article class="market-card">
            <div class="market-card__header">
                <div class="market-card__title">${s.title} <span class="market-card__status">(Mở)</span></div>
            </div>
            <div class="market-card__details">
                <span>🕐 ${formatTimeRange(s.startTime, s.endTime)}</span>
                <span>📍 ${s.branchName || 'Chưa xác định'}</span>
            </div>
            ${marketScore(s, market)}
            <div class="market-card__requirement">
                Yêu cầu: ${s.requiredSkillName || 'Không yêu cầu'} | Mức ${s.requiredLevel}
            </div>
            <div class="market-card__action">
                <button class="btn-take btn--full" data-action="take" data-id="${s.id}" ${blocked ? DISABLED : ''}>Đăng ký nhận ca</button>
            </div>
        </article>`;
    })}`;
}

/** Thông báo trạng thái chấm điểm của Chợ ca (đang tải / lỗi / AI không khả dụng) - E8. */
export function marketNotice(market) {
    if (market.status === 'error' || (market.message && market.status !== 'loading')) {
        return html`<div class="notice notice--warning" role="status">
            <span>⚠️ ${market.message || 'Không lấy được điểm gợi ý lúc này.'} Bạn vẫn có thể đăng ký nhận ca bình thường.</span>
            <button class="btn btn--outline btn--sm" data-action="reload-market">Thử lại</button>
        </div>`;
    }
    return html``;
}

// ---------- Tài khoản ----------
export function accountInfo(emp) {
    if (!emp) return emptyState('👤', 'Không tải được thông tin tài khoản');
    const row = (label, value) => html`
        <div class="account-info-item">
            <span class="account-info-item__label">${label}</span>
            <span class="account-info-item__value">${value}</span>
        </div>`;
    const skills = (emp.skills || []).map(k => `${k.skillName} (Lv.${k.level})`).join(', ');
    return html`<div class="account-info-list">
        ${row('Họ tên', emp.fullName)}
        ${row('Email', emp.email)}
        ${row('Vai trò', emp.role === 'ADMIN' ? 'Quản lý' : 'Nhân viên')}
        ${row('Giờ tối đa/tuần', (emp.maxHoursPerWeek || '—') + 'h')}
        ${row('Ca hoàn thành', emp.totalCompleted || 0)}
        ${row('Ca đã hủy', emp.totalCancelled || 0)}
        ${row('Đi trễ', (emp.lateArrivalCount || 0) + ' lần')}
        ${row('Giờ ưu tiên', formatPreferred(emp.preferredTime))}
        ${row('Chi nhánh chính', emp.primaryBranchName || '—')}
        ${row('Kỹ năng', skills || '—')}
    </div>`;
}

export function formatPreferred(v) {
    return ({ MORNING: 'Buổi sáng', AFTERNOON: 'Buổi chiều', EVENING: 'Buổi tối', NIGHT: 'Ban đêm', ANY: 'Linh hoạt' })[v] || '—';
}

// ---------- Quản lý: lưới ca ----------
export function adminShiftsGrid(shifts, { cachedIds = new Set(), nowIso = '' } = {}) {
    if (!shifts || shifts.length === 0) return emptyState('📭', 'Không có ca nào trong khoảng đang xem', true);

    return html`${shifts.map(s => {
        const started = hasStarted(s, nowIso);
        const canSuggest = s.status === 'OPEN' && !started;
        const canEdit = !started && s.status !== 'COMPLETED';
        const canMarkLate = started && !!s.assignedToId && !s.lateMarked
            && ['ASSIGNED', 'TAKEN', 'FORCE_ASSIGNED', 'COMPLETED'].includes(s.status);
        return html`
        <article class="shift-card shift-card--${s.status}">
            <div class="shift-card__header">
                <div>
                    <h3 class="shift-card__title">${s.title}</h3>
                    <div class="shift-card__id">ID: #${s.id}</div>
                </div>
                <span class="shift-card__status status--${s.status}">${formatStatus(s.status)}</span>
            </div>
            <div class="shift-card__details">
                <div class="shift-card__detail"><span class="shift-card__detail-icon" aria-hidden="true">🕐</span> ${formatTimeRange(s.startTime, s.endTime)}</div>
                <div class="shift-card__detail"><span class="shift-card__detail-icon" aria-hidden="true">🏢</span> ${s.branchName || 'Chưa xác định'}</div>
                <div class="shift-card__detail"><span class="shift-card__detail-icon" aria-hidden="true">🎯</span> ${s.requiredSkillName || 'Không yêu cầu'} — Level ${s.requiredLevel}</div>
                <div class="shift-card__detail"><span class="shift-card__detail-icon" aria-hidden="true">👤</span> ${s.assignedToName || html`<em class="text-green">Chưa có người nhận</em>`}${s.lateMarked ? html` <span class="text-warn">· đã ghi nhận muộn</span>` : ''}</div>
            </div>
            <div class="shift-card__actions">
                ${canSuggest ? html`<button class="btn btn--ai btn--sm" data-action="show-candidates" data-id="${s.id}">🤖 AI Gợi Ý ${cachedIds.has(s.id) ? '(đã lưu)' : ''}</button>` : ''}
                ${canMarkLate ? html`<button class="btn btn--outline btn--sm" data-action="mark-late" data-id="${s.id}">⏰ Ghi nhận đến muộn</button>` : ''}
                ${canEdit ? html`<button class="btn btn--outline btn--sm" data-action="edit-shift" data-id="${s.id}">✏️ Sửa</button>` : ''}
            </div>
        </article>`;
    })}`;
}

/** Thanh phân trang/lọc của lưới ca: chỉ chữ, không cần style. */
export function adminPager({ page = 0, hasMore = false, count = 0 } = {}) {
    return html`
        <button class="btn btn--outline btn--sm" data-action="admin-prev" ${page <= 0 ? DISABLED : ''}>‹ Trang trước</button>
        <span class="pager__info">Trang ${page + 1} · ${count} ca</span>
        <button class="btn btn--outline btn--sm" data-action="admin-next" ${hasMore ? '' : DISABLED}>Trang sau ›</button>`;
}

// ---------- Bảng gợi ý AI ----------
const SOURCE_LABEL = { gemini: 'Gemini', heuristic: 'Quy tắc' };

export function candidatesPanel(c) {
    if (!c.open) return html``;
    const title = html`🤖 AI Gợi Ý — Ca #${c.shiftId}`;
    const header = (extra) => html`
        <div class="candidates-panel__title">
            ${title}
            <span class="candidates-panel__meta">${extra}</span>
            <button class="btn btn--outline btn--sm" data-action="refresh-candidates" data-id="${c.shiftId}" ${c.loading ? DISABLED : ''}>↻ Chấm lại</button>
            <button class="btn btn--outline btn--sm" data-action="close-candidates">✕ Đóng</button>
        </div>`;

    if (c.loading) {
        return html`<div class="candidates-panel" aria-busy="true"><div class="candidates-panel__title"><span class="spinner" aria-hidden="true"></span> Đang gọi AI chấm điểm cho ca #${c.shiftId}…</div></div>`;
    }
    if (c.error) {
        return html`<div class="candidates-panel">${header('')}${emptyState('⚠️', c.error)}</div>`;
    }
    const data = c.data || {};
    // AI không dùng được: KHÔNG có điểm nào, và nói rõ lý do (B2) thay vì hiện điểm giả
    if (data.available === false) {
        return html`<div class="candidates-panel">${header('')}
            <div class="notice notice--error" role="alert">❌ ${data.message || 'AI hiện không khả dụng.'}
                ${data.error_code ? html`<span class="notice__code">Mã: ${data.error_code}</span>` : ''}</div>
            <p class="candidates-panel__hint">Bạn vẫn có thể gán ca thủ công bằng nút "Sửa" ở ca (chọn nhân viên) khi AI hoạt động trở lại, hoặc để nhân viên tự nhận ở Chợ ca.</p>
        </div>`;
    }
    const list = data.ranked_candidates || [];
    if (list.length === 0) {
        return html`<div class="candidates-panel">${header('')}
            ${data.message ? html`<div class="notice notice--info" role="status">${data.message}</div>` : ''}
            ${emptyState('😕', 'Không tìm thấy ứng viên phù hợp')}</div>`;
    }

    const rows = list.map((cand, i) => {
        const rank = i + 1;
        const rankClass = rank <= 3 ? `candidate-row__rank--${rank}` : 'candidate-row__rank--default';
        const score = Number(cand.match_score) || 0;
        const level = scoreLevel(score);
        const b = cand.breakdown || {};
        const hasBand = cand.score_low !== undefined && cand.score_low !== null && cand.score_high !== undefined && cand.score_high !== null;
        const src = cand.source || data.source || 'gemini';
        return html`
        <div class="candidate-row">
            <div class="candidate-row__rank ${rankClass}">${rank}</div>
            <div class="candidate-row__info">
                <div class="candidate-row__name">${cand.full_name}
                    <span class="source-badge source-badge--${src === 'gemini' ? 'gemini' : 'rule'}">${SOURCE_LABEL[src] || src}</span>
                </div>
                <div class="candidate-row__details">
                    Tin cậy: ${scoreText(b.reliability)}% ·
                    Kỹ năng: ${scoreText(b.skill_fit)}% ·
                    Giờ: ${scoreText(b.time_fit)}% ·
                    Khối lượng: ${scoreText(b.workload_fit)}% ·
                    Chi nhánh: ${scoreText(b.location_fit)}%
                </div>
                <div class="candidate-row__explanation">
                    💡 <strong>Nhận xét:</strong> ${b.explanation || 'Không có thông tin chi tiết.'}
                </div>
            </div>
            <div class="candidate-row__score">
                <div class="candidate-row__score-value score--${level}">${scoreText(score)}%</div>
                <div class="candidate-row__score-label">Match Score</div>
                <div class="score-bar" role="img" aria-label="Điểm ${scoreText(score)} trên 100"><div class="score-bar__fill score-bar__fill--${level}" data-w="${pct(score)}"></div></div>
                ${hasBand ? html`<div class="candidate-row__band" title="Khoảng dao động hợp lý của điểm">±: ${scoreText(cand.score_low)}–${scoreText(cand.score_high)}</div>` : ''}
                ${cand.confidence !== undefined && cand.confidence !== null
                    ? html`<div class="candidate-row__band" title="Nhiều dữ liệu lịch sử và đồng thuận giữa các nguồn chấm thì độ tin cậy cao">Tin cậy ${scoreText(cand.confidence)}%</div>` : ''}
            </div>
            <div class="candidate-row__action">
                <button class="btn btn--success btn--sm" data-action="assign-candidate" data-shift-id="${c.shiftId}" data-employee-id="${cand.employee_id}">Gán ca</button>
            </div>
        </div>`;
    });

    const cacheTime = c.cachedAt ? new Date(c.cachedAt).toLocaleTimeString('vi-VN') : '';
    return html`<div class="candidates-panel">
        ${header(html`${data.total_candidates ?? list.length} ứng viên ${cacheTime ? `· Lưu lúc ${cacheTime}` : ''}`)}
        ${data.fallback ? html`<div class="notice notice--warning" role="alert">⚠️ ${data.message || 'Điểm hiển thị là điểm dự phòng theo quy tắc, không phải do Gemini chấm.'}
            ${data.fallback_reason ? html`<span class="notice__code">Mã: ${data.fallback_reason}</span>` : ''}
            <span>Điểm dự phòng do bộ quy tắc tính, chỉ để tham khảo.</span></div>` : ''}
        ${rows}
        <p class="candidates-panel__privacy">🔒 Để chấm điểm, hệ thống gửi tới Google Gemini dữ liệu đã ẩn danh (mã nhân viên, kỹ năng, lịch sử ca, giờ làm) — KHÔNG gồm họ tên hay email.</p>
    </div>`;
}

// ---------- Quản lý nhân viên / danh mục ----------
export function employeesTable(employees, branchesById = new Map()) {
    if (!employees || employees.length === 0) return emptyState('👥', 'Chưa có nhân viên nào', true);
    return html`
    <table class="data-table">
        <caption class="sr-only">Danh sách nhân viên</caption>
        <thead><tr>
            <th scope="col">Nhân viên</th><th scope="col">Vai trò</th><th scope="col">Chi nhánh</th>
            <th scope="col">Giờ/tuần</th><th scope="col">Kỹ năng</th><th scope="col">Trạng thái</th><th scope="col">Thao tác</th>
        </tr></thead>
        <tbody>
        ${employees.map(e => html`<tr class="${e.active === false ? 'data-table__row--off' : ''}">
            <td><strong>${e.fullName}</strong><br><span class="text-muted">${e.email}</span></td>
            <td>${e.role === 'ADMIN' ? 'Quản lý' : 'Nhân viên'}</td>
            <td>${e.primaryBranchName || branchesById.get(e.primaryBranchId) || '—'}</td>
            <td>${e.maxHoursPerWeek ?? '—'}h</td>
            <td>${(e.skills || []).map(k => `${k.skillName} Lv.${k.level}`).join(', ') || '—'}</td>
            <td>${e.active === false ? html`<span class="badge badge--off">Đã vô hiệu</span>` : html`<span class="badge badge--on">Hoạt động</span>`}</td>
            <td class="data-table__actions">
                <button class="btn btn--outline btn--sm" data-action="edit-employee" data-id="${e.id}">✏️ Sửa</button>
                <button class="btn btn--outline btn--sm" data-action="reset-password" data-id="${e.id}">🔑 Đặt lại MK</button>
            </td>
        </tr>`)}
        </tbody>
    </table>`;
}

export function branchList(branches) {
    if (!branches || branches.length === 0) return emptyState('🏢', 'Chưa có chi nhánh', true);
    return html`<ul class="catalog-list">${branches.map(b => html`
        <li class="catalog-list__item">
            <div><strong>${b.name}</strong><br>
                <span class="text-muted">${b.address || 'Chưa có địa chỉ'} · Sức chứa: ${b.maxCapacity ? b.maxCapacity + ' người cùng lúc' : 'không giới hạn'}</span></div>
            <button class="btn btn--outline btn--sm" data-action="edit-branch" data-id="${b.id}">✏️ Sửa</button>
        </li>`)}</ul>`;
}

export function skillList(skills) {
    if (!skills || skills.length === 0) return emptyState('🎯', 'Chưa có kỹ năng', true);
    return html`<ul class="catalog-list">${skills.map(k => html`
        <li class="catalog-list__item">
            <strong>${k.name}</strong>
            <button class="btn btn--outline btn--sm" data-action="edit-skill" data-id="${k.id}">✏️ Sửa</button>
        </li>`)}</ul>`;
}

/** Hàng chọn kỹ năng + mức trong form nhân viên. `levels` = { [skillId]: level } của nhân viên đang sửa. */
export function skillPicker(skills, levels = {}) {
    if (!skills || skills.length === 0) return html`<p class="text-muted">Chưa có kỹ năng nào. Hãy tạo ở tab Danh mục.</p>`;
    return html`${skills.map(k => {
        const lv = levels[k.id];
        return html`
        <div class="skill-picker__row">
            <label class="skill-picker__check"><input type="checkbox" data-skill-check="${k.id}" ${lv ? raw('checked') : ''}> ${k.name}</label>
            <select class="form-input skill-picker__level" data-skill-level="${k.id}" aria-label="Mức kỹ năng ${k.name}">
                ${[1, 2, 3, 4, 5].map(n => html`<option value="${n}" ${Number(lv || 1) === n ? raw('selected') : ''}>Lv.${n}</option>`)}
            </select>
        </div>`;
    })}`;
}

// ---------- Thông báo (lưu ở server - D4) ----------
export function notificationList(list, nowMs = Date.now()) {
    if (!list || list.length === 0) return html`<div class="notification-dropdown__empty">Không có thông báo</div>`;
    const icons = { success: '✅', warning: '⚠️', error: '❌', info: 'ℹ️' };
    return html`${list.map(n => html`
        <div class="notification-item ${n.read ? '' : 'notification-item--unread'}" data-action="mark-read" data-id="${n.id}" role="button" tabindex="0">
            <span class="notification-item__icon" aria-hidden="true">${icons[n.type] || 'ℹ️'}</span>
            <div class="notification-item__content">
                <div class="notification-item__message">${n.message}</div>
                <div class="notification-item__time">${n.time ? getTimeAgo(n.time, nowMs) : ''}</div>
            </div>
        </div>`)}`;
}

// ---------- Ô chọn (select) ----------
export function options(placeholder, items, labelKey = 'name') {
    return html`<option value="">${placeholder}</option>${items.map(i => html`<option value="${i.id}">${i[labelKey]}</option>`)}`;
}
