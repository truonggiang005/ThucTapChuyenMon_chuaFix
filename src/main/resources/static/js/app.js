/**
 * FlexiShift AI - Frontend Application (Redesigned)
 *
 * Giao tiếp với Spring Boot REST API (/api/... - cùng origin với giao diện)
 * Pages:
 *   1. Lịch Cá Nhân Tổng Hợp (Personal Schedule)
 *   2. Chợ Ca Trực (Shift Marketplace)
 *   3. Tài Khoản (Account)
 *   4. Quản Lý (Admin)
 */

// ============================================================
// CẤU HÌNH
// ============================================================
const isPort8080 = (window.location.protocol === 'http:' || window.location.protocol === 'https:') && window.location.port === '8080';
const API_BASE = isPort8080 ? '/api' : 'http://localhost:8080/api';
const LOGIN_PAGE_URL = isPort8080 ? '/' : 'http://localhost:8080/';
const AI_HEALTH_URL = 'http://localhost:8001/api/v1/health';

// State
let currentEmployeeId = null;
let currentUserRole = null;
let allShifts = [];
let employees = [];
let branches = [];
let skills = [];

// Notification system
let notifications = JSON.parse(localStorage.getItem('flexishift_notifications') || '[]');

// AI suggestion cache to prevent excessive API calls / token exhaustion
const aiSuggestionCache = {};
let aiCallCount = 0;
const AI_CALL_LIMIT = 10; // Max AI calls per session
let aiRateLimited = false;

// Day name mapping
const DAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thur', 'Fri', 'Sat'];
const DAY_NAMES_VI = ['CN', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];

// ============================================================
// KHỞI TẠO VÀ KIỂM TRA ĐĂNG NHẬP (Chạy ngay lập tức)
// ============================================================
const userStr = localStorage.getItem('flexishift_user');
if (!userStr) {
    window.location.href = LOGIN_PAGE_URL;
} else {
    const user = JSON.parse(userStr);
    currentEmployeeId = user.id;
    currentUserRole = user.role; // 'ADMIN' or 'EMPLOYEE'
}

document.addEventListener('DOMContentLoaded', () => {
    if (!userStr) return; // Đang đợi redirect
    
    try {
        const user = JSON.parse(userStr);

        // Áp dụng RBAC
        if (currentUserRole === 'EMPLOYEE') {
            const navAdmin = document.getElementById('navAdmin');
            if (navAdmin) navAdmin.style.display = 'none';
        }

        // Hiển thị user info
        const userNameEl = document.getElementById('userName');
        if (userNameEl) userNameEl.textContent = user.fullName || 'Người dùng';
        const userRoleEl = document.getElementById('userRoleStr');
        if (userRoleEl) userRoleEl.textContent = '(' + (currentUserRole === 'ADMIN' ? 'Quản lý' : 'Nhân viên') + ')';
        const busyEmpEl = document.getElementById('busyEmployeeNameDisplay');
        if (busyEmpEl) busyEmpEl.textContent = user.fullName || '';

        // Hiển thị ngày tháng hiện tại
        try {
            updateCurrentDateDisplay();
            setInterval(updateCurrentDateDisplay, 30000);
        } catch(e) {}

        // Notification system
        try {
            initNotificationSystem();
        } catch(e) {}

        // Logout
        document.getElementById('btnLogout')?.addEventListener('click', () => {
            localStorage.removeItem('flexishift_user');
            window.location.href = LOGIN_PAGE_URL;
        });

        initNavigation();
        loadEmployees(); // Must load for Create Shift dropdown
        loadAllShifts();
        loadFilterOptions();
        checkAIHealth();

        // Kiểm tra AI health mỗi 30 giây
        setInterval(checkAIHealth, 30000);

        // Form listeners
        document.getElementById('createShiftForm')?.addEventListener('submit', handleCreateShift);
        document.getElementById('editShiftForm')?.addEventListener('submit', handleEditShift);
        document.getElementById('busyScheduleForm')?.addEventListener('submit', handleCreateBusySchedule);

        // Filter listeners
        document.getElementById('filterBranch')?.addEventListener('change', loadMarketShifts);
        document.getElementById('filterSkill')?.addEventListener('change', loadMarketShifts);
    } catch (e) {
        console.error('Lỗi khi khởi chạy ứng dụng:', e);
    }
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
    // Không cần nữa vì đã có login
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

/** Tải danh sách nhân viên */
async function loadEmployees() {
    try {
        employees = await apiFetch(`${API_BASE}/employees`);
    } catch (err) {
        console.error('Lỗi tải danh sách nhân viên:', err);
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
    const grid = document.getElementById('allShiftsGrid');
    try {
        const data = await apiFetch(`${API_BASE}/shifts`);
        allShifts = Array.isArray(data) ? data : [];
        updateStats();
        renderAdminShiftsGrid('allShiftsGrid', allShifts);
    } catch (err) {
        console.error('Lỗi tải danh sách ca:', err);
        showToast('Lỗi tải danh sách ca: ' + (err.message || 'Không thể kết nối máy chủ'), 'error');
        if (grid) {
            grid.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state__icon">⚠️</div>
                    <div class="empty-state__text">Lỗi tải dữ liệu: ${err.message || 'Không thể kết nối máy chủ'}</div>
                    <button class="btn btn--outline btn--sm" onclick="loadAllShifts()" style="margin-top: 10px;">🔄 Thử lại</button>
                </div>`;
        }
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

        // If user is selected, try to get AI scores for all shifts with caching
        let aiScores = {};
        if (currentEmployeeId) {
            // Get AI scores for each open shift with cache check to prevent token exhaustion
            const scorePromises = shifts.map(async (s) => {
                try {
                    const cacheKey = `shift_${s.id}`;
                    let data;
                    if (aiSuggestionCache[cacheKey]) {
                        data = aiSuggestionCache[cacheKey].data;
                    } else {
                        data = await apiFetch(`${API_BASE}/shifts/${s.id}/candidates`);
                        aiSuggestionCache[cacheKey] = { data: data, timestamp: Date.now() };
                    }
                    if (data && data.ranked_candidates) {
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
                <div class="market-card__action" style="margin-top: 15px;">
                    <button class="btn-take" style="width: 100%;" onclick="takeShift(${s.id})"
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
        
        // Add notification if shift was assigned to someone
        if (request.assignedToId) {
            const emp = employees.find(e => e.id === request.assignedToId);
            addNotification(`Đã gán ca "${request.title}" cho ${emp ? emp.fullName : 'NV #' + request.assignedToId}`, 'success');
        }
        
        e.target.reset();
        loadAllShifts();
    } catch (err) {
        showToast('❌ ' + err.message, 'error');
    }
}

/** Mở modal chỉnh sửa ca */
function openEditShiftModal(shiftId) {
    const shift = allShifts.find(s => s.id === shiftId);
    if (!shift) return;

    // Populate dropdowns first
    const branchSelect = document.getElementById('editShiftBranch');
    branchSelect.innerHTML = '<option value="">-- Chọn chi nhánh --</option>' +
        branches.map(b => `<option value="${b.id}">${b.name}</option>`).join('');

    const skillSelect = document.getElementById('editShiftSkill');
    skillSelect.innerHTML = '<option value="">-- Không yêu cầu --</option>' +
        skills.map(s => `<option value="${s.id}">${s.name}</option>`).join('');

    const empSelect = document.getElementById('editShiftEmployee');
    empSelect.innerHTML = '<option value="">-- Để trống (Trạng thái OPEN) --</option>' +
        employees.map(e => `<option value="${e.id}">${e.fullName}</option>`).join('');

    // Fill data
    document.getElementById('editShiftId').value = shift.id;
    document.getElementById('editShiftTitle').value = shift.title;
    document.getElementById('editShiftStartTime').value = shift.startTime.substring(0, 16);
    document.getElementById('editShiftEndTime').value = shift.endTime.substring(0, 16);
    document.getElementById('editShiftBranch').value = shift.branchId || '';
    document.getElementById('editShiftSkill').value = shift.requiredSkillId || '';
    document.getElementById('editShiftLevel').value = shift.requiredLevel || 1;
    document.getElementById('editShiftEmployee').value = shift.assignedToId || '';

    document.getElementById('editShiftModal').style.display = 'flex';
}

/** Xử lý submit form sửa ca */
async function handleEditShift(e) {
    e.preventDefault();
    const id = document.getElementById('editShiftId').value;
    const request = {
        title: document.getElementById('editShiftTitle').value,
        startTime: document.getElementById('editShiftStartTime').value,
        endTime: document.getElementById('editShiftEndTime').value,
        branchId: parseInt(document.getElementById('editShiftBranch').value),
        requiredSkillId: document.getElementById('editShiftSkill').value ? parseInt(document.getElementById('editShiftSkill').value) : null,
        requiredLevel: parseInt(document.getElementById('editShiftLevel').value),
        assignedToId: document.getElementById('editShiftEmployee').value ? parseInt(document.getElementById('editShiftEmployee').value) : null
    };

    try {
        const result = await apiFetch(`${API_BASE}/shifts/${id}`, {
            method: 'PUT',
            body: JSON.stringify(request)
        });

        showToast('✅ ' + result.message, 'success');
        if (request.assignedToId) {
            const emp = employees.find(e => e.id === request.assignedToId);
            addNotification(`Admin đã gán ca "${request.title}" (#${id}) cho ${emp ? emp.fullName : 'NV #' + request.assignedToId}`, 'success');
        }
        delete aiSuggestionCache[`shift_${id}`];
        document.getElementById('editShiftModal').style.display = 'none';
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
        const emp = employees.find(e => e.id === currentEmployeeId);
        addNotification(`${emp ? emp.fullName : 'Nhân viên #' + currentEmployeeId} đã nhả ca #${shiftId}`, 'warning');
        delete aiSuggestionCache[`shift_${shiftId}`];
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
        const emp = employees.find(e => e.id === currentEmployeeId);
        addNotification(`${emp ? emp.fullName : 'Nhân viên #' + currentEmployeeId} đã nhận ca #${shiftId}`, 'info');
        delete aiSuggestionCache[`shift_${shiftId}`];
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
                            Giờ: ${breakdown.time_fit ?? '—'}% ·
                            Khối lượng: ${breakdown.workload_fit ?? '—'}% ·
                            Chi nhánh: ${breakdown.location_fit ?? '—'}%
                        </div>
                        <div class="candidate-row__explanation" style="margin-top: 8px; font-size: 13px; color: var(--text-secondary); background: #f8fafc; padding: 6px 10px; border-radius: 4px; border-left: 3px solid var(--accent-blue);">
                            💡 <strong>AI nhận xét:</strong> ${breakdown.explanation || 'Không có thông tin chi tiết.'}
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
        
        // Add notification
        const emp = employees.find(e => e.id === employeeId);
        addNotification(`Admin đã gán ca #${shiftId} cho ${emp ? emp.fullName : 'NV #' + employeeId} qua AI gợi ý`, 'success');
        
        // Clear cache for this shift
        delete aiSuggestionCache[`shift_${shiftId}`];
        
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
    if (!container) return;

    if (!shifts || shifts.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state__icon">📭</div>
                <div class="empty-state__text">Không có ca nào</div>
            </div>`;
        return;
    }

    try {
        container.innerHTML = shifts.map(s => {
            const isOpen = s.status === 'OPEN';
            const isUnfilled = s.status === 'UNFILLED';
            const isMyShift = s.assignedToId && s.assignedToId === currentEmployeeId;
            const hasNobody = !s.assignedToId;

            // AI suggestion button - hiển thị cho mọi ca khi là ADMIN
            const showAiBtn = currentUserRole === 'ADMIN';
            const aiCacheKey = `shift_${s.id}`;
            const hasCachedSuggestion = aiSuggestionCache[aiCacheKey];

            let actionsHtml = '<div class="shift-card__actions">';
            
            if (isOpen) {
                actionsHtml += `
                        <button class="btn btn--success btn--sm" onclick="takeShift(${s.id})"
                            ${!currentEmployeeId ? 'disabled title="Chọn nhân viên trước"' : ''}>
                            ✋ Nhận Ca
                        </button>`;
            }
            
            if (showAiBtn) {
                actionsHtml += `
                        <button class="btn btn--ai btn--sm" onclick="showCandidatesSafe(${s.id})" id="aiBtnShift${s.id}">
                            🤖 AI Gợi Ý ${hasCachedSuggestion ? '(đã lưu)' : ''}
                        </button>`;
            }
            
            if (isMyShift && (s.status === 'ASSIGNED' || s.status === 'TAKEN')) {
                actionsHtml += `
                        <button class="btn btn--danger btn--sm" onclick="releaseShift(${s.id})">
                            Nhả Ca
                        </button>`;
            }
            
            actionsHtml += `
                        <button class="btn btn--outline btn--sm" onclick="openEditShiftModal(${s.id})">
                            ✏️ Sửa
                        </button>
                    </div>`;

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
    } catch (err) {
        console.error('Lỗi khi render danh sách ca:', err);
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state__icon">⚠️</div>
                <div class="empty-state__text">Lỗi hiển thị ca: ${err.message}</div>
            </div>`;
    }
}

/** Cập nhật stats bar */
function updateStats() {
    if (!allShifts || !Array.isArray(allShifts)) return;
    const count = (status) => allShifts.filter(s => s.status === status).length;
    // Ca đã nhận = tất cả các ca đã có người nhận hoặc được gán
    const takenCount = allShifts.filter(s => s.assignedToId != null || s.status === 'TAKEN' || s.status === 'ASSIGNED' || s.status === 'FORCE_ASSIGNED').length;

    const setVal = (id, val) => {
        const el = document.getElementById(id);
        if (el) el.textContent = val;
    };

    setVal('statTotal', allShifts.length);
    setVal('statOpen', count('OPEN'));
    setVal('statTaken', takenCount);
    setVal('statUnfilled', count('UNFILLED'));
    setVal('statAssigned', count('ASSIGNED'));
    setVal('statForce', count('FORCE_ASSIGNED'));
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
        'FORCE_ASSIGNED': 'Đã gán (AI)',
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

// ============================================================
// NGÀY THÁNG HIỆN TẠI
// ============================================================
function updateCurrentDateDisplay() {
    const now = new Date();
    const options = { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' };
    const dateStr = now.toLocaleDateString('vi-VN', options);
    const timeStr = now.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
    const el = document.getElementById('currentDateText');
    if (el) el.textContent = `${dateStr} • ${timeStr}`;
}

// ============================================================
// HỆ THỐNG THÔNG BÁO (NOTIFICATION)
// ============================================================
function initNotificationSystem() {
    const btn = document.getElementById('notificationBtn');
    const dropdown = document.getElementById('notificationDropdown');
    const clearBtn = document.getElementById('clearNotifications');

    if (btn) {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const isVisible = dropdown.style.display !== 'none';
            dropdown.style.display = isVisible ? 'none' : 'block';
        });
    }

    if (clearBtn) {
        clearBtn.addEventListener('click', () => {
            notifications = [];
            localStorage.setItem('flexishift_notifications', '[]');
            renderNotifications();
        });
    }

    // Click outside to close
    document.addEventListener('click', (e) => {
        const wrapper = document.getElementById('notificationWrapper');
        if (wrapper && !wrapper.contains(e.target)) {
            dropdown.style.display = 'none';
        }
    });

    renderNotifications();
}

function addNotification(message, type = 'info') {
    const notif = {
        id: Date.now(),
        message,
        type,
        time: new Date().toISOString(),
        read: false
    };
    notifications.unshift(notif);
    // Keep max 20 notifications
    if (notifications.length > 20) notifications = notifications.slice(0, 20);
    localStorage.setItem('flexishift_notifications', JSON.stringify(notifications));
    renderNotifications();
}

function renderNotifications() {
    const list = document.getElementById('notificationList');
    const badge = document.getElementById('notificationBadge');
    const unreadCount = notifications.filter(n => !n.read).length;

    if (badge) {
        if (unreadCount > 0) {
            badge.style.display = 'flex';
            badge.textContent = unreadCount > 9 ? '9+' : unreadCount;
        } else {
            badge.style.display = 'none';
        }
    }

    if (!list) return;

    if (notifications.length === 0) {
        list.innerHTML = '<div class="notification-dropdown__empty">Không có thông báo mới</div>';
        return;
    }

    list.innerHTML = notifications.map(n => {
        const timeAgo = getTimeAgo(new Date(n.time));
        const typeIcon = n.type === 'success' ? '✅' : n.type === 'warning' ? '⚠️' : n.type === 'error' ? '❌' : 'ℹ️';
        return `
            <div class="notification-item ${n.read ? '' : 'notification-item--unread'}" onclick="markNotificationRead(${n.id})">
                <span class="notification-item__icon">${typeIcon}</span>
                <div class="notification-item__content">
                    <div class="notification-item__message">${n.message}</div>
                    <div class="notification-item__time">${timeAgo}</div>
                </div>
            </div>`;
    }).join('');
}

function markNotificationRead(id) {
    const notif = notifications.find(n => n.id === id);
    if (notif) notif.read = true;
    localStorage.setItem('flexishift_notifications', JSON.stringify(notifications));
    renderNotifications();
}

function getTimeAgo(date) {
    const seconds = Math.floor((new Date() - date) / 1000);
    if (seconds < 60) return 'Vừa xong';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes} phút trước`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} giờ trước`;
    const days = Math.floor(hours / 24);
    return `${days} ngày trước`;
}

// ============================================================
// AI GỢI Ý VỚI CACHE & RATE LIMITING
// ============================================================

/** Gọi AI gợi ý có cache và rate limiting để tránh hết token */
async function showCandidatesSafe(shiftId) {
    const cacheKey = `shift_${shiftId}`;

    // Kiểm tra cache trước
    if (aiSuggestionCache[cacheKey]) {
        const cached = aiSuggestionCache[cacheKey];
        const ageMinutes = (Date.now() - cached.timestamp) / 60000;
        
        // Cache valid trong 10 phút
        if (ageMinutes < 10) {
            renderCachedCandidates(shiftId, cached.data);
            showToast('📋 Hiển thị gợi ý AI đã lưu (cache)', 'info');
            return;
        }
    }

    // Rate limiting check
    if (aiRateLimited) {
        showToast('⚠️ AI đang bị giới hạn. Vui lòng đợi 1 phút rồi thử lại.', 'warning');
        // Still show cached result if available
        if (aiSuggestionCache[cacheKey]) {
            renderCachedCandidates(shiftId, aiSuggestionCache[cacheKey].data);
        }
        return;
    }

    if (aiCallCount >= AI_CALL_LIMIT) {
        showToast('⚠️ Đã đạt giới hạn gọi AI trong phiên này. Sử dụng kết quả đã lưu hoặc tải lại trang.', 'warning');
        if (aiSuggestionCache[cacheKey]) {
            renderCachedCandidates(shiftId, aiSuggestionCache[cacheKey].data);
        }
        return;
    }

    // Call AI with error handling for token exhaustion
    try {
        aiCallCount++;
        const panel = document.getElementById('candidatesPanel');
        panel.innerHTML = `
            <div class="candidates-panel">
                <div class="candidates-panel__title">
                    <span class="spinner"></span> Đang gọi AI chấm điểm cho ca #${shiftId}...
                    <span style="font-size: 12px; color: var(--text-muted);">(Lượt gọi: ${aiCallCount}/${AI_CALL_LIMIT})</span>
                </div>
            </div>`;

        const data = await apiFetch(`${API_BASE}/shifts/${shiftId}/candidates`);

        // Save to cache
        aiSuggestionCache[cacheKey] = {
            data: data,
            timestamp: Date.now()
        };

        renderCachedCandidates(shiftId, data);

    } catch (err) {
        const errMsg = err.message || '';
        // Detect token/quota exhaustion
        if (errMsg.includes('429') || errMsg.includes('quota') || errMsg.includes('RESOURCE_EXHAUSTED') || errMsg.includes('hết lượt')) {
            aiRateLimited = true;
            showToast('🚨 API AI đã hết lượt gọi (429). Tự động giới hạn để bảo vệ chương trình.', 'error');
            // Auto unlock after 60 seconds
            setTimeout(() => {
                aiRateLimited = false;
                showToast('✅ AI đã sẵn sàng trở lại', 'success');
            }, 60000);
        }

        const panel = document.getElementById('candidatesPanel');
        panel.innerHTML = `
            <div class="candidates-panel">
                <div class="candidates-panel__title">🤖 AI Gợi Ý — Ca #${shiftId}</div>
                <div class="empty-state">
                    <div class="empty-state__icon">⚠️</div>
                    <div class="empty-state__text">${errMsg.includes('429') ? 'AI đã hết lượt gọi miễn phí. Vui lòng đợi 1 phút.' : errMsg}</div>
                </div>
                ${aiSuggestionCache[cacheKey] ? '<button class="btn btn--outline btn--sm" onclick="renderCachedCandidates(' + shiftId + ', aiSuggestionCache.shift_' + shiftId + '.data)" style="margin: 10px auto; display: block;">📋 Xem kết quả cũ</button>' : ''}
            </div>`;
    }
}

/** Render candidates from cache */
function renderCachedCandidates(shiftId, data) {
    const panel = document.getElementById('candidatesPanel');

    if (!data || !data.ranked_candidates || data.ranked_candidates.length === 0) {
        panel.innerHTML = `
            <div class="candidates-panel">
                <div class="candidates-panel__title">🤖 AI Gợi Ý — Ca #${shiftId}</div>
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
                        Giờ: ${breakdown.time_fit ?? '—'}% ·
                        Khối lượng: ${breakdown.workload_fit ?? '—'}% ·
                        Chi nhánh: ${breakdown.location_fit ?? '—'}%
                    </div>
                    <div class="candidate-row__explanation" style="margin-top: 8px; font-size: 13px; color: var(--text-secondary); background: #f8fafc; padding: 6px 10px; border-radius: 4px; border-left: 3px solid var(--accent-blue);">
                        💡 <strong>AI nhận xét:</strong> ${breakdown.explanation || 'Không có thông tin chi tiết.'}
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

    const cacheInfo = aiSuggestionCache[`shift_${shiftId}`];
    const cacheTime = cacheInfo ? new Date(cacheInfo.timestamp).toLocaleTimeString('vi-VN') : '';

    panel.innerHTML = `
        <div class="candidates-panel">
            <div class="candidates-panel__title">
                🤖 AI Gợi Ý — Ca #${shiftId}
                <span style="margin-left: auto; font-size: 13px; font-weight: 400; color: var(--text-secondary);">
                    ${data.total_candidates} ứng viên ${cacheTime ? `· Cache lúc ${cacheTime}` : ''}
                </span>
                <button class="btn btn--outline btn--sm" onclick="closeCandidatesPanel()" style="margin-left: 12px;">✕ Đóng</button>
            </div>
            ${candidatesHtml}
        </div>`;
}
