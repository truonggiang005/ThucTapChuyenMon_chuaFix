/**
 * SmartSchedule - Frontend Application (Vanilla JS + Fetch API)
 *
 * Giao tiếp với Spring Boot REST API (http://localhost:8080/api/...)
 * Xử lý:
 *   - Hiển thị danh sách ca (OPEN, ALL, MY SCHEDULE)
 *   - Nhả ca / Nhận ca (Module 1)
 *   - Xem AI Match Score (Module 2)
 *   - Toast notifications cho UX
 */

// ============================================================
// CẤU HÌNH
// ============================================================
const API_BASE = 'http://localhost:8080/api';
const AI_HEALTH_URL = 'http://localhost:8000/api/v1/health';

// State
let currentEmployeeId = null;
let allShifts = [];
let employees = [];

// ============================================================
// KHỞI TẠO
// ============================================================
document.addEventListener('DOMContentLoaded', () => {
    initTabs();
    loadEmployees();
    loadAllShifts();
    checkAIHealth();

    // Kiểm tra AI health mỗi 30 giây
    setInterval(checkAIHealth, 30000);

    // Listener chọn nhân viên
    document.getElementById('currentUser').addEventListener('change', (e) => {
        currentEmployeeId = e.target.value ? parseInt(e.target.value) : null;
        refreshAll();
    });
});

// ============================================================
// TABS
// ============================================================
function initTabs() {
    const tabBtns = document.querySelectorAll('.tab-btn');
    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            // Deactivate all
            tabBtns.forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));

            // Activate selected
            btn.classList.add('active');
            const tabId = btn.getAttribute('data-tab');
            document.getElementById(tabId).classList.add('active');

            // Load data for the tab
            if (tabId === 'tab-open') loadOpenShifts();
            if (tabId === 'tab-my') loadMySchedule();
            if (tabId === 'tab-all') loadAllShifts();
        });
    });
}

// ============================================================
// API CALLS
// ============================================================

/** Fetch helper với error handling */
async function apiFetch(url, options = {}) {
    try {
        const res = await fetch(url, {
            headers: { 'Content-Type': 'application/json' },
            ...options
        });

        const data = await res.json();

        if (!res.ok) {
            throw new Error(data.message || `HTTP ${res.status}`);
        }

        return data;
    } catch (err) {
        if (err.message.includes('Failed to fetch') || err.message.includes('NetworkError')) {
            throw new Error('Không thể kết nối đến server. Kiểm tra Spring Boot đã chạy chưa.');
        }
        throw err;
    }
}

/** Tải danh sách nhân viên → populate dropdown */
async function loadEmployees() {
    try {
        employees = await apiFetch(`${API_BASE}/employees`);
        const select = document.getElementById('currentUser');

        // Xóa options cũ (trừ placeholder)
        while (select.options.length > 1) select.remove(1);

        employees.forEach(emp => {
            const opt = document.createElement('option');
            opt.value = emp.id;
            opt.textContent = `${emp.fullName} (${emp.email})`;
            select.appendChild(opt);
        });
    } catch (err) {
        showToast('Lỗi tải danh sách nhân viên: ' + err.message, 'error');
    }
}

/** Tải tất cả ca → cập nhật stats + grid */
async function loadAllShifts() {
    try {
        allShifts = await apiFetch(`${API_BASE}/shifts`);
        updateStats();
        renderShiftsGrid('allShiftsGrid', allShifts);
    } catch (err) {
        showToast('Lỗi tải danh sách ca: ' + err.message, 'error');
    }
}

/** Tải ca OPEN */
async function loadOpenShifts() {
    try {
        const shifts = await apiFetch(`${API_BASE}/shifts/open`);
        renderShiftsGrid('openShiftsGrid', shifts, true);
    } catch (err) {
        showToast('Lỗi tải ca mở: ' + err.message, 'error');
    }
}

/** Tải lịch làm việc của nhân viên đang chọn */
async function loadMySchedule() {
    const container = document.getElementById('mySchedule');

    if (!currentEmployeeId) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state__icon">👤</div>
                <div class="empty-state__text">Vui lòng chọn nhân viên ở menu trên</div>
            </div>`;
        return;
    }

    try {
        const shifts = await apiFetch(`${API_BASE}/shifts/employee/${currentEmployeeId}`);

        if (shifts.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state__icon">📭</div>
                    <div class="empty-state__text">Chưa có ca nào được gán</div>
                </div>`;
            return;
        }

        container.innerHTML = `
            <table class="schedule-table">
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Tiêu đề</th>
                        <th>Thời gian</th>
                        <th>Chi nhánh</th>
                        <th>Kỹ năng</th>
                        <th>Trạng thái</th>
                        <th>Hành động</th>
                    </tr>
                </thead>
                <tbody>
                    ${shifts.map(s => `
                        <tr>
                            <td>#${s.id}</td>
                            <td>${s.title}</td>
                            <td>${formatTimeRange(s.startTime, s.endTime)}</td>
                            <td>${s.branchName || '—'}</td>
                            <td>${s.requiredSkillName || '—'} (Lv${s.requiredLevel})</td>
                            <td><span class="shift-card__status status--${s.status}">${formatStatus(s.status)}</span></td>
                            <td>
                                ${s.status === 'ASSIGNED' ?
                                    `<button class="btn btn--danger btn--sm" onclick="releaseShift(${s.id})">
                                        Nhả ca
                                    </button>` : '—'}
                            </td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>`;
    } catch (err) {
        showToast('Lỗi tải lịch làm việc: ' + err.message, 'error');
    }
}

/** Kiểm tra AI service health */
async function checkAIHealth() {
    const dot = document.getElementById('aiStatusDot');
    const text = document.getElementById('aiStatusText');

    try {
        const res = await fetch(AI_HEALTH_URL, { signal: AbortSignal.timeout(3000) });
        if (res.ok) {
            dot.classList.add('healthy');
            text.textContent = 'AI: Online';
        } else {
            dot.classList.remove('healthy');
            text.textContent = 'AI: Offline';
        }
    } catch {
        dot.classList.remove('healthy');
        text.textContent = 'AI: Offline';
    }
}

// ============================================================
// MODULE 1: NHẢ CA / NHẬN CA
// ============================================================

/** Nhả ca: ASSIGNED → OPEN */
async function releaseShift(shiftId) {
    if (!currentEmployeeId) {
        showToast('Vui lòng chọn nhân viên trước', 'error');
        return;
    }

    if (!confirm('Bạn có chắc muốn nhả ca này?')) return;

    try {
        const result = await apiFetch(
            `${API_BASE}/shifts/${shiftId}/release?employeeId=${currentEmployeeId}`,
            { method: 'POST' }
        );
        showToast(result.message, 'success');
        refreshAll();
    } catch (err) {
        showToast(err.message, 'error');
    }
}

/**
 * ★ Nhận ca: OPEN → TAKEN (Optimistic Locking)
 * Nếu 2 người bấm cùng lúc, 1 người sẽ nhận được lỗi 409.
 */
async function takeShift(shiftId) {
    if (!currentEmployeeId) {
        showToast('Vui lòng chọn nhân viên trước', 'error');
        return;
    }

    if (!confirm('Bạn có muốn nhận ca này?')) return;

    try {
        const result = await apiFetch(
            `${API_BASE}/shifts/${shiftId}/take?employeeId=${currentEmployeeId}`,
            { method: 'POST' }
        );
        showToast('✅ ' + result.message, 'success');
        closeCandidatesPanel();
        refreshAll();
    } catch (err) {
        // ★ Lỗi tương tranh (409) sẽ hiện thông báo thân thiện
        showToast('❌ ' + err.message, 'error');
        refreshAll(); // Reload để thấy trạng thái mới
    }
}

// ============================================================
// MODULE 2: AI MATCHMAKING
// ============================================================

/** Xem AI gợi ý ứng viên cho ca OPEN */
async function showCandidates(shiftId) {
    const panel = document.getElementById('candidatesPanel');
    panel.innerHTML = `
        <div class="candidates-panel">
            <div class="candidates-panel__title">
                <span class="spinner"></span> Đang gọi AI chấm điểm cho ca #${shiftId}...
            </div>
        </div>`;

    try {
        const data = await apiFetch(`${API_BASE}/shifts/${shiftId}/candidates`);

        if (!data.ranked_candidates || data.ranked_candidates.length === 0) {
            panel.innerHTML = `
                <div class="candidates-panel">
                    <div class="candidates-panel__title">🤖 AI Matchmaking — Ca #${shiftId}</div>
                    <div class="empty-state">
                        <div class="empty-state__icon">😕</div>
                        <div class="empty-state__text">Không tìm thấy ứng viên phù hợp</div>
                    </div>
                </div>`;
            return;
        }

        const candidatesHtml = data.ranked_candidates.map((c, i) => {
            const rank = i + 1;
            const rankClass = rank <= 3 ? `candidate-row__rank--${rank}` : 'candidate-row__rank--default';
            const scoreClass = c.match_score >= 70 ? 'high' : c.match_score >= 40 ? 'mid' : 'low';
            const breakdown = c.breakdown || {};

            return `
                <div class="candidate-row">
                    <div class="candidate-row__rank ${rankClass}">${rank}</div>
                    <div class="candidate-row__info">
                        <div class="candidate-row__name">${c.full_name}</div>
                        <div class="candidate-row__details">
                            Tin cậy: ${breakdown.reliability ?? '—'}% · 
                            Kỹ năng: ${breakdown.skill_fit ?? '—'}% · 
                            Giờ phù hợp: ${breakdown.time_fit ?? '—'}% · 
                            Khối lượng: ${breakdown.workload_fit ?? '—'}%
                        </div>
                    </div>
                    <div class="candidate-row__score">
                        <div class="candidate-row__score-value" style="color: var(--accent-${scoreClass === 'high' ? 'green' : scoreClass === 'mid' ? 'orange' : 'red'})">${c.match_score}%</div>
                        <div class="candidate-row__score-label">Match Score</div>
                        <div class="score-bar">
                            <div class="score-bar__fill score-bar__fill--${scoreClass}" style="width: ${c.match_score}%"></div>
                        </div>
                    </div>
                    <div class="candidate-row__action">
                        <button class="btn btn--success btn--sm" onclick="takeShiftForCandidate(${shiftId}, ${c.employee_id})">
                            Gán ca
                        </button>
                    </div>
                </div>`;
        }).join('');

        panel.innerHTML = `
            <div class="candidates-panel">
                <div class="candidates-panel__title">
                    🤖 AI Matchmaking — Ca #${shiftId}
                    <span style="margin-left: auto; font-size: 13px; font-weight: 400; color: var(--text-secondary);">
                        ${data.total_candidates} ứng viên
                    </span>
                    <button class="btn btn--outline btn--sm" onclick="closeCandidatesPanel()" style="margin-left: 12px;">✕ Đóng</button>
                </div>
                ${candidatesHtml}
            </div>`;

    } catch (err) {
        panel.innerHTML = `
            <div class="candidates-panel">
                <div class="candidates-panel__title">🤖 AI Matchmaking — Ca #${shiftId}</div>
                <div class="empty-state">
                    <div class="empty-state__icon">⚠️</div>
                    <div class="empty-state__text">${err.message}</div>
                </div>
            </div>`;
    }
}

/** Gán ca cho ứng viên cụ thể (từ panel AI) */
async function takeShiftForCandidate(shiftId, employeeId) {
    if (!confirm('Gán ca này cho nhân viên được chọn?')) return;

    try {
        const result = await apiFetch(
            `${API_BASE}/shifts/${shiftId}/take?employeeId=${employeeId}`,
            { method: 'POST' }
        );
        showToast('✅ ' + result.message, 'success');
        closeCandidatesPanel();
        refreshAll();
    } catch (err) {
        showToast('❌ ' + err.message, 'error');
        refreshAll();
    }
}

function closeCandidatesPanel() {
    document.getElementById('candidatesPanel').innerHTML = '';
}

// ============================================================
// RENDER UI
// ============================================================

/** Render grid ca làm việc */
function renderShiftsGrid(containerId, shifts, showActions = false) {
    const container = document.getElementById(containerId);

    if (!shifts || shifts.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state__icon">📭</div>
                <div class="empty-state__text">Không có ca nào</div>
            </div>`;
        return;
    }

    container.innerHTML = shifts.map(s => {
        const isOpen = s.status === 'OPEN';
        const isMyShift = s.assignedToId && s.assignedToId === currentEmployeeId;

        let actionsHtml = '';
        if (showActions && isOpen) {
            actionsHtml = `
                <div class="shift-card__actions">
                    <button class="btn btn--success btn--sm" onclick="takeShift(${s.id})"
                        ${!currentEmployeeId ? 'disabled title="Chọn nhân viên trước"' : ''}>
                        ✋ Nhận Ca
                    </button>
                    <button class="btn btn--primary btn--sm" onclick="showCandidates(${s.id})">
                        🤖 AI Gợi Ý
                    </button>
                </div>`;
        } else if (isMyShift && s.status === 'ASSIGNED') {
            actionsHtml = `
                <div class="shift-card__actions">
                    <button class="btn btn--danger btn--sm" onclick="releaseShift(${s.id})">
                        Nhả Ca
                    </button>
                </div>`;
        }

        return `
            <div class="shift-card shift-card--${s.status}">
                <div class="shift-card__header">
                    <div>
                        <div class="shift-card__title">${s.title}</div>
                        <div class="shift-card__id">ID: #${s.id}</div>
                    </div>
                    <span class="shift-card__status status--${s.status}">${formatStatus(s.status)}</span>
                </div>
                <div class="shift-card__details">
                    <div class="shift-card__detail">
                        <span class="shift-card__detail-icon">🕐</span>
                        ${formatTimeRange(s.startTime, s.endTime)}
                    </div>
                    <div class="shift-card__detail">
                        <span class="shift-card__detail-icon">🏢</span>
                        ${s.branchName || 'Chưa xác định'}
                    </div>
                    <div class="shift-card__detail">
                        <span class="shift-card__detail-icon">🎯</span>
                        ${s.requiredSkillName || 'Không yêu cầu'} — Level ${s.requiredLevel}
                    </div>
                    <div class="shift-card__detail">
                        <span class="shift-card__detail-icon">👤</span>
                        ${s.assignedToName || '<em style="color: var(--accent-green)">Chưa có người nhận</em>'}
                    </div>
                </div>
                ${actionsHtml}
            </div>`;
    }).join('');
}

/** Cập nhật stats bar */
function updateStats() {
    const count = (status) => allShifts.filter(s => s.status === status).length;
    document.getElementById('statTotal').textContent = allShifts.length;
    document.getElementById('statOpen').textContent = count('OPEN');
    document.getElementById('statTaken').textContent = count('TAKEN');
    document.getElementById('statForce').textContent = count('FORCE_ASSIGNED');
    document.getElementById('statUnfilled').textContent = count('UNFILLED');
}

/** Refresh tất cả data */
function refreshAll() {
    loadAllShifts();
    loadOpenShifts();
    if (currentEmployeeId) loadMySchedule();
}

// ============================================================
// HELPERS
// ============================================================

/** Format thời gian */
function formatTimeRange(start, end) {
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

/** Format tên trạng thái */
function formatStatus(status) {
    const map = {
        'ASSIGNED': 'Đã gán',
        'OPEN': 'Đang mở',
        'TAKEN': 'Đã nhận',
        'FORCE_ASSIGNED': 'Auto gán',
        'UNFILLED': 'Bỏ trống'
    };
    return map[status] || status;
}

/** Hiển thị toast notification */
function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = `toast toast--${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    // Tự xóa sau 4 giây
    setTimeout(() => {
        if (toast.parentNode) toast.parentNode.removeChild(toast);
    }, 4000);
}
