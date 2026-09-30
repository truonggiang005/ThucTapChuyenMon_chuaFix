/**
 * FlexiShift AI - Frontend Application (Redesigned)
 *
 * Giao tiếp với Spring Boot REST API (http://localhost:8080/api/...)
 * Pages:
 *   1. Lịch Cá Nhân Tổng Hợp (Personal Schedule)
 *   2. Chợ Ca Trực (Shift Marketplace)
 *   3. Tài Khoản (Account)
 *   4. Quản Lý (Admin)
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
let branches = [];
let skills = [];

// Day name mapping
const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thur', 'Fri', 'Sat'];
const DAY_NAMES_VI = ['CN', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];

// ============================================================
// KHỞI TẠO
// ============================================================
document.addEventListener('DOMContentLoaded', () => {
    initNavigation();
    loadEmployees();
    loadAllShifts();
    loadFilterOptions();
    checkAIHealth();

    // Kiểm tra AI health mỗi 30 giây
    setInterval(checkAIHealth, 30000);

    // Listener chọn nhân viên
    document.getElementById('currentUser').addEventListener('change', (e) => {
        currentEmployeeId = e.target.value ? parseInt(e.target.value) : null;
        updateUserDisplay();
        refreshCurrentPage();
    });

    // Form listeners
    document.getElementById('createShiftForm')?.addEventListener('submit', handleCreateShift);
    document.getElementById('busyScheduleForm')?.addEventListener('submit', handleCreateBusySchedule);

    // Filter listeners
    document.getElementById('filterBranch')?.addEventListener('change', loadMarketShifts);
    document.getElementById('filterSkill')?.addEventListener('change', loadMarketShifts);
});

// ============================================================
// NAVIGATION (Pages)
// ============================================================
function initNavigation() {
    const navLinks = document.querySelectorAll('.nav-link');
    navLinks.forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();

            // Deactivate all
            navLinks.forEach(l => l.classList.remove('active'));
            document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));

            // Activate selected
            link.classList.add('active');
            const pageId = 'page-' + link.getAttribute('data-page');
            document.getElementById(pageId).classList.add('active');

            // Load data for the page
            const page = link.getAttribute('data-page');
            if (page === 'personal-schedule') loadPersonalSchedule();
            if (page === 'shift-market') loadMarketShifts();
            if (page === 'account') loadAccountInfo();
            if (page === 'admin') { loadCreateShiftOptions(); loadAllShifts(); }
        });
    });
}

function refreshCurrentPage() {
    const activePage = document.querySelector('.nav-link.active');
    if (activePage) {
        const page = activePage.getAttribute('data-page');
        if (page === 'personal-schedule') loadPersonalSchedule();
        if (page === 'shift-market') loadMarketShifts();
        if (page === 'account') loadAccountInfo();
        if (page === 'admin') { loadAllShifts(); }
    }
}

function updateUserDisplay() {
    const nameEl = document.getElementById('userName');
    if (currentEmployeeId) {
        const emp = employees.find(e => e.id === currentEmployeeId);
        if (emp) {
            nameEl.textContent = emp.fullName;
        }
    } else {
        nameEl.textContent = '—';
    }
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

        // Try parsing JSON response
        let data;
        const text = await res.text();
        try {
            data = JSON.parse(text);
        } catch {
            console.error('[apiFetch] Non-JSON response:', text);
            if (!res.ok) throw new Error(`Lỗi server (HTTP ${res.status})`);
            return text;
        }

        if (!res.ok) {
            console.error('[apiFetch] Error response:', data);
            throw new Error(data.message || `HTTP ${res.status}: ${JSON.stringify(data)}`);
        }

        return data;
    } catch (err) {
        if (err.message.includes('Failed to fetch') || err.message.includes('NetworkError') || err.message.includes('Load failed')) {
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

/** Load filter options for marketplace */
async function loadFilterOptions() {
    try {
        const [branchList, skillList] = await Promise.all([
            apiFetch(`${API_BASE}/shifts/branches`),
            apiFetch(`${API_BASE}/shifts/skills`)
        ]);

        branches = branchList;
        skills = skillList;

        // Populate branch filter
        const branchSelect = document.getElementById('filterBranch');
        branchSelect.innerHTML = '<option value="">Chi nhánh</option>' +
            branches.map(b => `<option value="${b.id}">${b.name}</option>`).join('');

        // Populate skill filter
        const skillSelect = document.getElementById('filterSkill');
        skillSelect.innerHTML = '<option value="">All Vị trí</option>' +
            skills.map(s => `<option value="${s.id}">${s.name}</option>`).join('');
    } catch (err) {
        // Silently fail, filters will just not populate
    }
}

/** Tải tất cả ca → cập nhật stats + grid */
async function loadAllShifts() {
    try {
        allShifts = await apiFetch(`${API_BASE}/shifts`);
        updateStats();
        renderAdminShiftsGrid('allShiftsGrid', allShifts);
    } catch (err) {
        showToast('Lỗi tải danh sách ca: ' + err.message, 'error');
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
            text.textContent = 'AI';
        } else {
            dot.classList.remove('healthy');
            text.textContent = 'AI';
        }
    } catch {
        dot.classList.remove('healthy');
        text.textContent = 'AI';
    }
}

// ============================================================
// PAGE 1: LỊCH CÁ NHÂN TỔNG HỢP
// ============================================================

async function loadPersonalSchedule() {
    const container = document.getElementById('weeklySchedule');

    if (!currentEmployeeId) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state__icon">👤</div>
                <div class="empty-state__text">Vui lòng chọn nhân viên ở menu trên</div>
            </div>`;
        document.getElementById('busyEntriesDisplay').innerHTML = '';
        return;
    }

    try {
        // Load shifts + busy schedules concurrently
        const [shifts, busySchedules] = await Promise.all([
            apiFetch(`${API_BASE}/shifts/employee/${currentEmployeeId}`),
            apiFetch(`${API_BASE}/busy-schedules/employee/${currentEmployeeId}`).catch(() => [])
        ]);

        // Render weekly schedule
        renderWeeklySchedule(container, shifts);

        // Render busy entries on right panel
        renderBusyEntries(busySchedules);

    } catch (err) {
        showToast('Lỗi tải lịch cá nhân: ' + err.message, 'error');
    }
}

function renderWeeklySchedule(container, shifts) {
    if (!shifts || shifts.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state__icon">📭</div>
                <div class="empty-state__text">Chưa có ca nào được gán</div>
            </div>`;
        return;
    }

    // Group shifts by day of week
    const grouped = {};
    shifts.forEach(s => {
        const date = new Date(s.startTime);
        const dayIndex = date.getDay(); // 0=Sun ... 6=Sat
        const dayKey = DAY_NAMES[dayIndex];
        if (!grouped[dayKey]) grouped[dayKey] = [];
        grouped[dayKey].push(s);
    });

    // Render in order Mon-Sun
    const dayOrder = ['Mon', 'Tue', 'Wed', 'Thur', 'Fri', 'Sat', 'Sun'];
    let html = '';

    dayOrder.forEach(day => {
        const dayShifts = grouped[day];
        if (!dayShifts || dayShifts.length === 0) return;

        html += `<div class="day-group">
            <div class="day-group__label">${day}</div>
            <div class="day-group__shifts">`;

        dayShifts.forEach(s => {
            const isForce = s.status === 'FORCE_ASSIGNED';
            const statusClass = isForce ? 'force' : s.status.toLowerCase();
            const startTime = formatTime(s.startTime);
            const endTime = formatTime(s.endTime);

            html += `
                <div class="schedule-card schedule-card--${statusClass}">
                    <div class="schedule-card__header">
                        <div class="schedule-card__title">Ca #${s.id} - ${s.requiredSkillName || s.title} <span class="schedule-card__status schedule-card__status--${s.status}">(${s.status})</span></div>
                    </div>
                    <div class="schedule-card__meta">
                        <span>📍 ${s.branchName || 'Chưa xác định'}</span>
                        <span>🕐 ${startTime} - ${endTime}</span>
                    </div>
                    ${s.status === 'ASSIGNED' || s.status === 'TAKEN' ? `
                        <div class="schedule-card__action">
                            <button class="btn-release" onclick="releaseShift(${s.id})">Nhả ca</button>
                        </div>` : ''}
                    ${isForce ? `
                        <div class="schedule-card__action">
                            <button class="btn-release btn-release--red" onclick="releaseShift(${s.id})">Nhả ca</button>
                        </div>` : ''}
                </div>`;
        });

        html += `</div></div>`;
    });

    container.innerHTML = html || `
        <div class="empty-state">
            <div class="empty-state__icon">📭</div>
            <div class="empty-state__text">Chưa có ca nào được gán</div>
        </div>`;
}

function renderBusyEntries(busySchedules) {
    const container = document.getElementById('busyEntriesDisplay');

    if (!busySchedules || busySchedules.length === 0) {
        container.innerHTML = '';
        return;
    }

    container.innerHTML = busySchedules.map(s => {
        const date = new Date(s.startTime);
        const dayName = DAY_NAMES_VI[date.getDay()];
        const startTime = formatTime(s.startTime);
        const endTime = formatTime(s.endTime);

        return `
            <div class="busy-entry-card">
                <button class="busy-entry-card__delete" onclick="deleteBusySchedule(${s.id})" title="Xóa">✕</button>
                <div class="busy-entry-card__title">
                    🕐 | ${s.reason || 'Bận việc riêng'} (${s.reason || 'Lý do không xác định'})
                </div>
                <div class="busy-entry-card__detail">
                    📅 ${dayName}, ${startTime} - ${endTime}
                </div>
            </div>`;
    }).join('');
}

// ============================================================
// PAGE 2: CHỢ CA TRỰC (SHIFT MARKETPLACE)
// ============================================================

async function loadMarketShifts() {
    const grid = document.getElementById('marketGrid');

    try {
        const branchId = document.getElementById('filterBranch').value;
        const skillId = document.getElementById('filterSkill').value;

        let url = `${API_BASE}/shifts/open`;
        if (branchId) url += `?branchId=${branchId}`;

        let shifts = await apiFetch(url);

        // Client-side filter by skill
        if (skillId) {
            shifts = shifts.filter(s => s.requiredSkillId && s.requiredSkillId.toString() === skillId);
        }

        if (!shifts || shifts.length === 0) {
            grid.innerHTML = `
                <div class="empty-state" style="grid-column: 1/-1;">
                    <div class="empty-state__icon">📭</div>
                    <div class="empty-state__text">Không có ca trực nào đang mở</div>
                </div>`;
            return;
        }

        // If user is selected, try to get AI scores for all shifts
        let aiScores = {};
        if (currentEmployeeId) {
            // Get AI scores for each open shift (fire and forget errors)
            const scorePromises = shifts.map(async (s) => {
                try {
                    const data = await apiFetch(`${API_BASE}/shifts/${s.id}/candidates`);
                    if (data.ranked_candidates) {
                        const myScore = data.ranked_candidates.find(c => c.employee_id === currentEmployeeId);
                        if (myScore) {
                            aiScores[s.id] = myScore.match_score;
                        }
                    }
                } catch {
                    // Silently skip - AI might be offline
                }
            });
            await Promise.allSettled(scorePromises);
        }

        renderMarketGrid(grid, shifts, aiScores);
    } catch (err) {
        showToast('Lỗi tải chợ ca trực: ' + err.message, 'error');
    }
}

function renderMarketGrid(container, shifts, aiScores = {}) {
    container.innerHTML = shifts.map(s => {
        const startTime = formatTime(s.startTime);
        const endTime = formatTime(s.endTime);
        const score = aiScores[s.id];
        const hasScore = score !== undefined && score !== null;

        let badgeClass = 'ai-badge--mid';
        let badgeText = 'Phù hợp';
        if (hasScore) {
            if (score >= 80) { badgeClass = 'ai-badge--high'; badgeText = 'Phù hợp rất cao'; }
            else if (score >= 50) { badgeClass = 'ai-badge--mid'; badgeText = 'Phù hợp'; }
            else { badgeClass = 'ai-badge--low'; badgeText = 'Ít phù hợp'; }
        }

        return `
            <div class="market-card">
                <div class="market-card__header">
                    <div class="market-card__title">Ca làm #${s.id} - ${s.requiredSkillName || s.title} <span class="market-card__status">(MỞ)</span></div>
                </div>
                <div class="market-card__details">
                    <span>🕐 ${startTime} - ${endTime}</span>
                    <span>📍 ${s.branchName || 'Chưa xác định'}</span>
                </div>
                ${hasScore ? `
                    <div class="market-card__ai-row">
                        <span class="market-card__ai-score">
                            <span class="ai-icon">🔵</span> AI Match Score: ${Math.round(score)}%
                        </span>
                        <span class="ai-badge ${badgeClass}">${badgeText}</span>
                    </div>` : ''}
                <div class="market-card__requirement">
                    Yêu cầu: ${s.requiredSkillName || 'Không yêu cầu'} | Mức ${s.requiredLevel}
                </div>
                <div class="market-card__action">
                    <button class="btn-take" onclick="takeShift(${s.id})"
                        ${!currentEmployeeId ? 'disabled title="Chọn nhân viên trước"' : ''}>
                        Đăng ký nhận ca
                    </button>
                </div>
            </div>`;
    }).join('');
}

// ============================================================
// PAGE 3: TÀI KHOẢN
// ============================================================

function loadAccountInfo() {
    const container = document.getElementById('accountInfo');

    if (!currentEmployeeId) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state__icon">👤</div>
                <div class="empty-state__text">Vui lòng chọn nhân viên để xem thông tin</div>
            </div>`;
        return;
    }

    const emp = employees.find(e => e.id === currentEmployeeId);
    if (!emp) return;

    container.innerHTML = `
        <div class="account-info-list">
            <div class="account-info-item">
                <span class="account-info-item__label">Họ tên</span>
                <span class="account-info-item__value">${emp.fullName}</span>
            </div>
            <div class="account-info-item">
                <span class="account-info-item__label">Email</span>
                <span class="account-info-item__value">${emp.email}</span>
            </div>
            <div class="account-info-item">
                <span class="account-info-item__label">Giờ tối đa/tuần</span>
                <span class="account-info-item__value">${emp.maxHoursPerWeek || '—'}h</span>
            </div>
            <div class="account-info-item">
                <span class="account-info-item__label">Ca hoàn thành</span>
                <span class="account-info-item__value">${emp.totalCompleted || 0}</span>
            </div>
            <div class="account-info-item">
                <span class="account-info-item__label">Ca đã hủy</span>
                <span class="account-info-item__value">${emp.totalCancelled || 0}</span>
            </div>
            <div class="account-info-item">
                <span class="account-info-item__label">Đi trễ</span>
                <span class="account-info-item__value">${emp.lateArrivalCount || 0} lần</span>
            </div>
            <div class="account-info-item">
                <span class="account-info-item__label">Giờ ưu tiên</span>
                <span class="account-info-item__value">${emp.preferredTime || '—'}</span>
            </div>
            <div class="account-info-item">
                <span class="account-info-item__label">Chi nhánh chính</span>
                <span class="account-info-item__value">${emp.primaryBranchName || '—'}</span>
            </div>
        </div>`;
}

// ============================================================
// ADMIN: TẠO CA MỚI
// ============================================================

/** Tải dữ liệu cho form tạo ca */
async function loadCreateShiftOptions() {
    try {
        const [branchList, skillList] = await Promise.all([
            apiFetch(`${API_BASE}/shifts/branches`),
            apiFetch(`${API_BASE}/shifts/skills`)
        ]);

        const branchSelect = document.getElementById('shiftBranch');
        branchSelect.innerHTML = '<option value="">-- Chọn chi nhánh --</option>' +
            branchList.map(b => `<option value="${b.id}">${b.name}</option>`).join('');

        const skillSelect = document.getElementById('shiftSkill');
        skillSelect.innerHTML = '<option value="">-- Không yêu cầu --</option>' +
            skillList.map(s => `<option value="${s.id}">${s.name}</option>`).join('');

        const empSelect = document.getElementById('shiftEmployee');
        empSelect.innerHTML = '<option value="">-- Để trống (Trạng thái OPEN) --</option>' +
            employees.map(e => `<option value="${e.id}">${e.fullName}</option>`).join('');

    } catch (err) {
        showToast('Lỗi tải dữ liệu form tạo ca: ' + err.message, 'error');
    }
}

/** Xử lý submit form tạo ca */
async function handleCreateShift(e) {
    e.preventDefault();

    const request = {
        title: document.getElementById('shiftTitle').value,
        startTime: document.getElementById('shiftStartTime').value,
        endTime: document.getElementById('shiftEndTime').value,
        branchId: parseInt(document.getElementById('shiftBranch').value),
        requiredSkillId: document.getElementById('shiftSkill').value ? parseInt(document.getElementById('shiftSkill').value) : null,
        requiredLevel: parseInt(document.getElementById('shiftLevel').value),
        assignedToId: document.getElementById('shiftEmployee').value ? parseInt(document.getElementById('shiftEmployee').value) : null
    };

    try {
        const result = await apiFetch(`${API_BASE}/shifts`, {
            method: 'POST',
            body: JSON.stringify(request)
        });

        showToast('✅ ' + result.message, 'success');
        e.target.reset();
        loadAllShifts();
    } catch (err) {
        showToast('❌ ' + err.message, 'error');
    }
}

// ============================================================
// EMPLOYEE: LỊCH BẬN (BUSY SCHEDULE)
// ============================================================

/** Đăng ký lịch bận mới */
async function handleCreateBusySchedule(e) {
    e.preventDefault();
    if (!currentEmployeeId) {
        showToast('Vui lòng chọn nhân viên trước', 'error');
        return;
    }

    const busyDate = document.getElementById('busyDate').value;
    const startTimeVal = document.getElementById('busyStartTime').value;
    const endTimeVal = document.getElementById('busyEndTime').value;

    if (!busyDate) {
        showToast('Vui lòng chọn ngày', 'error');
        return;
    }

    if (!startTimeVal || !endTimeVal) {
        showToast('Vui lòng chọn giờ bắt đầu và kết thúc', 'error');
        return;
    }

    // Ensure time format is HH:mm (input type=time always returns HH:mm or HH:mm:ss)
    const startTimeFmt = startTimeVal.length === 5 ? startTimeVal + ':00' : startTimeVal;
    const endTimeFmt = endTimeVal.length === 5 ? endTimeVal + ':00' : endTimeVal;

    const request = {
        employeeId: currentEmployeeId,
        startTime: `${busyDate}T${startTimeFmt}`,
        endTime: `${busyDate}T${endTimeFmt}`,
        reason: document.getElementById('busyReason').value || ''
    };

    console.log('[BusySchedule] Sending request:', JSON.stringify(request));

    try {
        const result = await apiFetch(`${API_BASE}/busy-schedules`, {
            method: 'POST',
            body: JSON.stringify(request)
        });

        // Show inline success message
        const successMsg = document.getElementById('busySuccessMsg');
        const date = new Date(request.startTime);
        const dayName = DAY_NAMES_VI[date.getDay()];
        document.getElementById('busySuccessText').textContent =
            `Đăng ký lịch bận ${dayName} thành công! (Cập nhật sau 00h00)`;
        successMsg.style.display = 'flex';

        setTimeout(() => { successMsg.style.display = 'none'; }, 5000);

        showToast('✅ ' + result.message, 'success');
        e.target.reset();
        loadPersonalSchedule(); // Refresh
    } catch (err) {
        showToast('❌ ' + err.message, 'error');
    }
}

/** Xóa lịch bận */
async function deleteBusySchedule(busyId) {
    if (!confirm('Bạn có chắc muốn xóa lịch bận này?')) return;

    try {
        const result = await apiFetch(`${API_BASE}/busy-schedules/${busyId}?employeeId=${currentEmployeeId}`, {
            method: 'DELETE'
        });

        showToast('✅ ' + result.message, 'success');
        loadPersonalSchedule(); // Refresh
    } catch (err) {
        showToast('❌ ' + err.message, 'error');
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

    if (!confirm('Bạn có muốn đăng ký nhận ca này?')) return;

    console.log('[TakeShift] shiftId:', shiftId, 'employeeId:', currentEmployeeId);

    try {
        const result = await apiFetch(
            `${API_BASE}/shifts/${shiftId}/take?employeeId=${currentEmployeeId}`,
            { method: 'POST' }
        );
        showToast('✅ ' + (result.message || 'Đăng ký nhận ca thành công!'), 'success');
        closeCandidatesPanel();
        refreshAll();
    } catch (err) {
        console.error('[TakeShift] Error:', err);
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
// RENDER UI - ADMIN
// ============================================================

/** Render admin grid ca làm việc */
function renderAdminShiftsGrid(containerId, shifts) {
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
        if (isOpen) {
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
    refreshCurrentPage();
}

// ============================================================
// HELPERS
// ============================================================

/** Format thời gian range */
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

/** Format time only (HH:MM) */
function formatTime(dateStr) {
    try {
        const d = new Date(dateStr);
        return d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
    } catch {
        return dateStr;
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
