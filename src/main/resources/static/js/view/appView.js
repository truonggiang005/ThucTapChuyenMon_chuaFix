/**
 * VIEW - gắn ViewModel với DOM (ARC-01).
 *  - Đăng ký vào store: state đổi → vẽ lại đúng phần giao diện liên quan (khai báo theo template).
 *  - Sự kiện: event delegation qua thuộc tính data-action (không onclick inline → tương thích CSP).
 *  - Không dùng thuộc tính style="..." (CSP style-src 'self'): màu theo class, độ rộng thanh điểm gán bằng CSSOM.
 */
import { setHtml } from './html.js';
import * as T from './templates.js';
import { pct, dateKey } from './format.js';
import { computeStats, localIso, ADMIN_TABS } from '../viewmodel/appViewModel.js';

export function mountApp({ vm, doc = document, now = () => Date.now() }) {
    const $ = (id) => doc.getElementById(id);
    const { store, commands } = vm;

    // ---------------- Tiện ích DOM ----------------
    /** Thanh điểm: độ rộng đặt qua data-w rồi gán bằng CSSOM (không bị CSP chặn như style="..."). */
    function applyDynamicStyles(root) {
        if (!root || !root.querySelectorAll) return;
        root.querySelectorAll('[data-w]').forEach(el => { el.style.width = pct(el.dataset.w) + '%'; });
    }
    const paint = (el, safe) => { setHtml(el, safe); applyDynamicStyles(el); };

    const dlgOpen = (dlg) => {
        if (!dlg || dlg.open) return;
        if (typeof dlg.showModal === 'function') dlg.showModal(); else dlg.setAttribute('open', '');
    };
    const dlgClose = (dlg) => {
        if (!dlg || !dlg.open) return;
        if (typeof dlg.close === 'function') dlg.close(); else dlg.removeAttribute('open');
    };

    function renderSelectsKeepingValue(id, safeHtml) {
        const el = $(id);
        if (!el) return;
        const prev = el.value;
        setHtml(el, safeHtml);
        if ([...el.options].some(o => o.value === prev)) el.value = prev;
    }

    // ---------------- Hộp thoại ----------------
    function renderEditDialog(state) {
        const dlg = $('editShiftDialog');
        if (!dlg) return;
        const shift = state.editShiftId != null ? state.allShifts.find(s => s.id === state.editShiftId) : null;
        if (!shift) { dlgClose(dlg); return; }

        // Chỉ nạp lại danh sách + giá trị khi MỞ hộp thoại: tránh ghi đè những gì admin đang gõ/chọn khi dữ liệu nền được tải lại
        if (!dlg.open) {
            setHtml($('editShiftBranch'), T.options('-- Chọn chi nhánh --', state.branches));
            setHtml($('editShiftSkill'), T.options('-- Không yêu cầu --', state.skills));
            setHtml($('editShiftEmployee'), T.options('-- Để trống (trạng thái OPEN) --', state.employees, 'fullName'));
            $('editShiftId').value = shift.id;
            $('editShiftVersion').value = shift.version ?? '';
            $('editShiftTitle').value = shift.title;
            $('editShiftStartTime').value = String(shift.startTime).substring(0, 16);
            $('editShiftEndTime').value = String(shift.endTime).substring(0, 16);
            $('editShiftBranch').value = shift.branchId || '';
            $('editShiftSkill').value = shift.requiredSkillId || '';
            $('editShiftLevel').value = shift.requiredLevel || 1;
            $('editShiftEmployee').value = shift.assignedToId || '';
        }
        dlgOpen(dlg);
    }

    function levelsOf(emp) {
        const out = {};
        (emp?.skills || []).forEach(k => { out[k.skillId] = k.level; });
        return out;
    }

    function renderDialog(state) {
        const d = state.dialog;
        const map = { employee: 'employeeDialog', password: 'passwordDialog', branch: 'branchDialog', skill: 'skillDialog' };
        for (const [type, id] of Object.entries(map)) {
            if (!d || d.type !== type) dlgClose($(id));
        }
        if (!d || !map[d.type]) return;
        const dlg = $(map[d.type]);
        if (dlg.open) return;   // đã mở: không ghi đè dữ liệu đang nhập

        if (d.type === 'employee') {
            const emp = d.id != null ? state.employees.find(e => e.id === d.id) : null;
            $('employeeHeading').textContent = emp ? 'Sửa nhân viên' : 'Thêm nhân viên';
            $('employeeName').value = emp ? emp.fullName : '';
            $('employeeEmail').value = emp ? emp.email : '';
            $('employeePassword').value = '';
            $('employeeRole').value = emp ? emp.role : 'EMPLOYEE';
            setHtml($('employeeBranch'), T.options('-- Chưa chọn --', state.branches));
            $('employeeBranch').value = emp && emp.primaryBranchId ? emp.primaryBranchId : '';
            $('employeeMaxHours').value = emp && emp.maxHoursPerWeek ? emp.maxHoursPerWeek : 40;
            $('employeePreferred').value = emp && emp.preferredTime ? emp.preferredTime : 'ANY';
            paint($('employeeSkills'), T.skillPicker(state.skills, levelsOf(emp)));
            $('employeeActive').checked = emp ? emp.active !== false : true;
            // Tạo mới: cần email + mật khẩu. Sửa: không đổi email/mật khẩu ở đây (dùng "Đặt lại MK").
            $('employeeCreateOnly').hidden = !!emp;
            $('employeePwHelp').hidden = !!emp;
            $('employeeEmail').required = !emp;
            $('employeePassword').required = !emp;
            $('employeeActiveRow').hidden = !emp;
        } else if (d.type === 'password') {
            const emp = state.employees.find(e => e.id === d.id);
            $('passwordFor').textContent = emp ? `Đặt mật khẩu mới cho ${emp.fullName} (${emp.email}).` : '';
            $('resetPasswordValue').value = '';
        } else if (d.type === 'branch') {
            const b = d.id != null ? state.branches.find(x => x.id === d.id) : null;
            $('branchHeading').textContent = b ? 'Sửa chi nhánh' : 'Thêm chi nhánh';
            $('branchName').value = b ? b.name : '';
            $('branchAddress').value = b && b.address ? b.address : '';
            $('branchCapacity').value = b && b.maxCapacity ? b.maxCapacity : '';
        } else if (d.type === 'skill') {
            const k = d.id != null ? state.skills.find(x => x.id === d.id) : null;
            $('skillHeading').textContent = k ? 'Sửa kỹ năng' : 'Thêm kỹ năng';
            $('skillName').value = k ? k.name : '';
        }
        dlgOpen(dlg);
    }

    // ---------------- Vẽ lại theo state ----------------
    function render(state, changed) {
        const has = (...keys) => keys.some(k => changed.has(k));
        const isAdmin = state.user?.role === 'ADMIN';
        const nowIso = localIso(now());
        const todayKey = dateKey(nowIso);

        if (has('user')) {
            if (state.user) {
                $('userName').textContent = state.user.fullName || 'Người dùng';
                $('userRoleStr').textContent = '(' + (isAdmin ? 'Quản lý' : 'Nhân viên') + ')';
                const busyName = $('busyEmployeeNameDisplay');
                if (busyName) busyName.textContent = state.user.fullName || '';
                // Chỉ ẩn cho gọn: quyền thật do server kiểm tra
                $('navAdmin').hidden = !isAdmin;
            }
        }

        if (has('page')) {
            doc.querySelectorAll('.nav-link').forEach(l => {
                const active = l.dataset.page === state.page;
                l.classList.toggle('active', active);
                if (active) l.setAttribute('aria-current', 'page'); else l.removeAttribute('aria-current');
            });
            doc.querySelectorAll('.page').forEach(p => p.classList.toggle('active', p.id === 'page-' + state.page));
        }

        if (has('personalShifts', 'tick')) paint($('weeklySchedule'), T.weeklySchedule(state.personalShifts, { nowIso, todayKey }));
        if (has('busySchedules')) {
            paint($('busyEntriesDisplay'), T.busyEntries(state.busySchedules));
            paint($('busyStats'), T.busyStats(state.busySchedules));
        }

        if (has('marketShifts', 'market')) {
            paint($('marketGrid'), T.marketGrid(state.marketShifts, state.market));
            paint($('marketNotice'), T.marketNotice(state.market));
        }
        if (has('account')) paint($('accountInfo'), T.accountInfo(state.account));

        if (has('allShifts')) {
            const st = computeStats(state.allShifts);
            const set = (id, v) => { const el = $(id); if (el) el.textContent = v; };
            set('statTotal', st.total); set('statOpen', st.open); set('statTaken', st.taken);
            set('statAssigned', st.assigned); set('statCompleted', st.completed); set('statUnfilled', st.unfilled);
        }
        if (has('allShifts', 'aiCachedIds', 'adminFilter', 'tick')) {
            const status = state.adminFilter.status;
            const list = status ? state.allShifts.filter(s => s.status === status) : state.allShifts;
            paint($('allShiftsGrid'), T.adminShiftsGrid(list, { cachedIds: new Set(state.aiCachedIds), nowIso }));
            paint($('adminPager'), T.adminPager({ page: state.adminFilter.page, hasMore: state.adminHasMore, count: state.allShifts.length }));
        }
        if (has('candidates')) paint($('candidatesPanel'), T.candidatesPanel(state.candidates));

        if (has('adminTab')) {
            ADMIN_TABS.forEach(tab => {
                const panel = $('adminPanel-' + tab);
                const btn = $('tab-' + tab);
                if (panel) panel.hidden = tab !== state.adminTab;
                if (btn) {
                    btn.classList.toggle('active', tab === state.adminTab);
                    btn.setAttribute('aria-selected', String(tab === state.adminTab));
                }
            });
        }
        if (has('employees', 'branches')) {
            const byId = new Map(state.branches.map(b => [b.id, b.name]));
            paint($('employeesTable'), T.employeesTable(state.employees, byId));
        }
        if (has('branches')) paint($('branchList'), T.branchList(state.branches));
        if (has('skills')) paint($('skillList'), T.skillList(state.skills));

        if (has('notifications', 'tick')) {
            const unread = state.notifications.filter(n => !n.read).length;
            const badge = $('notificationBadge');
            if (badge) {
                badge.hidden = unread === 0;
                badge.textContent = unread > 9 ? '9+' : String(unread);
            }
            const btn = $('notificationBtn');
            if (btn) btn.setAttribute('aria-label', unread > 0 ? `Thông báo, ${unread} chưa đọc` : 'Thông báo');
            paint($('notificationList'), T.notificationList(state.notifications, now()));
        }

        if (has('ai')) {
            const ai = state.ai;
            const dot = $('aiStatusDot');
            const mode = !ai.checked ? 'unknown' : !ai.healthy ? 'down' : ai.geminiConfigured ? 'ok' : 'degraded';
            dot.classList.toggle('healthy', mode === 'ok');
            dot.classList.toggle('degraded', mode === 'degraded');
            dot.classList.toggle('down', mode === 'down');
            const label = { unknown: 'AI: đang kiểm tra', ok: 'AI: sẵn sàng', degraded: 'AI: chế độ quy tắc', down: 'AI: không khả dụng' }[mode];
            $('aiStatusText').textContent = label;
            const box = $('aiStatus');
            if (box) box.title = ai.message || label;
        }

        if (has('branches')) {
            renderSelectsKeepingValue('filterBranch', T.options('Tất cả chi nhánh', state.branches));
            renderSelectsKeepingValue('shiftBranch', T.options('-- Chọn chi nhánh --', state.branches));
        }
        if (has('skills')) {
            renderSelectsKeepingValue('filterSkill', T.options('Tất cả vị trí', state.skills));
            renderSelectsKeepingValue('shiftSkill', T.options('-- Không yêu cầu --', state.skills));
        }
        if (has('employees')) {
            renderSelectsKeepingValue('shiftEmployee', T.options('-- Để trống (trạng thái OPEN) --', state.employees, 'fullName'));
        }

        if (has('editShiftId', 'allShifts', 'branches', 'skills', 'employees')) renderEditDialog(state);
        if (has('dialog')) renderDialog(state);
    }

    store.subscribe(render);

    // ---------------- Sự kiện ----------------
    const num = (v) => Number(v);
    const actions = {
        'take': (el) => commands.takeShift(num(el.dataset.id)),
        'release': (el) => commands.releaseShift(num(el.dataset.id)),
        'delete-busy': (el) => commands.deleteBusy(num(el.dataset.id)),
        'show-candidates': (el) => commands.showCandidates(num(el.dataset.id)),
        'refresh-candidates': (el) => commands.showCandidates(num(el.dataset.id), { force: true }),
        'close-candidates': () => commands.closeCandidates(),
        'assign-candidate': (el) => commands.assignCandidate(num(el.dataset.shiftId), num(el.dataset.employeeId)),
        'edit-shift': (el) => commands.openEditShift(num(el.dataset.id)),
        'close-edit': () => commands.closeEditShift(),
        'mark-late': (el) => commands.markLate(num(el.dataset.id)),
        'mark-read': (el) => commands.markNotificationRead(num(el.dataset.id)),
        'reload-market': () => commands.reloadMarket(),
        'admin-prev': () => commands.adminPage(-1),
        'admin-next': () => commands.adminPage(1),
        'edit-employee': (el) => commands.openDialog('employee', num(el.dataset.id)),
        'reset-password': (el) => commands.openDialog('password', num(el.dataset.id)),
        'edit-branch': (el) => commands.openDialog('branch', num(el.dataset.id)),
        'edit-skill': (el) => commands.openDialog('skill', num(el.dataset.id)),
        'close-dialog': () => commands.closeDialog(),
    };

    function setNotificationMenu(open) {
        const dropdown = $('notificationDropdown');
        const btn = $('notificationBtn');
        if (!dropdown) return;
        dropdown.hidden = !open;
        if (btn) btn.setAttribute('aria-expanded', String(open));
    }

    doc.addEventListener('click', (e) => {
        const nav = e.target.closest('.nav-link[data-page]');
        if (nav) { e.preventDefault(); commands.navigate(nav.dataset.page); return; }

        const tab = e.target.closest('[data-tab]');
        if (tab) { commands.setAdminTab(tab.dataset.tab); return; }

        const el = e.target.closest('[data-action]');
        if (el && actions[el.dataset.action]) { actions[el.dataset.action](el); return; }

        // Menu thông báo
        const dropdown = $('notificationDropdown');
        if (e.target.closest('#notificationBtn')) {
            setNotificationMenu(dropdown.hidden);
        } else if (dropdown && !e.target.closest('#notificationWrapper')) {
            setNotificationMenu(false);
        }
    });

    doc.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') setNotificationMenu(false);
        // Mục thông báo là role="button": hỗ trợ Enter/Space bằng bàn phím
        if ((e.key === 'Enter' || e.key === ' ') && e.target.matches && e.target.matches('.notification-item[data-action]')) {
            e.preventDefault();
            actions['mark-read'](e.target);
        }
    });

    // <dialog>: Esc hoặc bấm nền → đóng; báo cho ViewModel để state khớp
    const bindDialog = (id, onClose) => {
        const dlg = $(id);
        if (!dlg) return;
        dlg.addEventListener('close', onClose);
        dlg.addEventListener('click', (e) => { if (e.target === dlg) dlgClose(dlg); });
    };
    bindDialog('editShiftDialog', () => commands.closeEditShift());
    ['employeeDialog', 'passwordDialog', 'branchDialog', 'skillDialog'].forEach(id => bindDialog(id, () => commands.closeDialog()));

    $('markAllNotifications')?.addEventListener('click', () => commands.markAllNotificationsRead());
    $('clearNotifications')?.addEventListener('click', () => commands.clearNotifications());
    $('btnLogout')?.addEventListener('click', () => commands.logout());

    $('filterBranch')?.addEventListener('change', (e) => commands.setFilter({ branchId: e.target.value }));
    $('filterSkill')?.addEventListener('change', (e) => commands.setFilter({ skillId: e.target.value }));
    $('adminFrom')?.addEventListener('change', (e) => commands.setAdminFilter({ from: e.target.value }));
    $('adminTo')?.addEventListener('change', (e) => commands.setAdminFilter({ to: e.target.value }));
    $('adminStatus')?.addEventListener('change', (e) => commands.setAdminFilter({ status: e.target.value }));

    $('btnAddEmployee')?.addEventListener('click', () => commands.openDialog('employee'));
    $('btnAddBranch')?.addEventListener('click', () => commands.openDialog('branch'));
    $('btnAddSkill')?.addEventListener('click', () => commands.openDialog('skill'));

    const shiftForm = (prefix) => ({
        title: $(prefix + 'Title').value, startTime: $(prefix + 'StartTime').value, endTime: $(prefix + 'EndTime').value,
        branchId: $(prefix + 'Branch').value, skillId: $(prefix + 'Skill').value,
        level: $(prefix + 'Level').value, employeeId: $(prefix + 'Employee').value,
    });

    $('createShiftForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const ok = await commands.createShift(shiftForm('shift'));
        if (ok) e.target.reset();
    });

    $('editShiftForm')?.addEventListener('submit', (e) => {
        e.preventDefault();
        commands.updateShift(num($('editShiftId').value), { ...shiftForm('editShift'), version: $('editShiftVersion').value });
    });

    $('busyScheduleForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const ok = await commands.createBusy({
            date: $('busyDate').value, endDate: $('busyEndDate').value, start: $('busyStartTime').value,
            end: $('busyEndTime').value, reason: $('busyReason').value,
        });
        if (ok) e.target.reset();
    });

    $('changePasswordForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const ok = await commands.changePassword({ current: $('pwCurrent').value, next: $('pwNew').value, confirm: $('pwConfirm').value });
        if (ok) e.target.reset();
    });

    $('employeeForm')?.addEventListener('submit', (e) => {
        e.preventDefault();
        const d = store.state.dialog;
        const skills = [];
        doc.querySelectorAll('#employeeSkills [data-skill-check]').forEach(chk => {
            if (chk.checked) {
                const lv = doc.querySelector(`#employeeSkills [data-skill-level="${chk.dataset.skillCheck}"]`);
                skills.push({ skillId: chk.dataset.skillCheck, level: lv ? lv.value : 1 });
            }
        });
        commands.saveEmployee({
            fullName: $('employeeName').value, email: $('employeeEmail').value, password: $('employeePassword').value,
            role: $('employeeRole').value, primaryBranchId: $('employeeBranch').value,
            maxHoursPerWeek: $('employeeMaxHours').value, preferredTime: $('employeePreferred').value,
            active: $('employeeActive').checked, skills,
        }, d && d.id != null ? d.id : null);
    });

    $('resetPasswordForm')?.addEventListener('submit', (e) => {
        e.preventDefault();
        const d = store.state.dialog;
        if (d && d.id != null) commands.resetEmployeePassword(d.id, $('resetPasswordValue').value);
    });

    $('branchForm')?.addEventListener('submit', (e) => {
        e.preventDefault();
        const d = store.state.dialog;
        commands.saveBranch({ name: $('branchName').value, address: $('branchAddress').value, maxCapacity: $('branchCapacity').value },
            d && d.id != null ? d.id : null);
    });

    $('skillForm')?.addEventListener('submit', (e) => {
        e.preventDefault();
        const d = store.state.dialog;
        commands.saveSkill({ name: $('skillName').value }, d && d.id != null ? d.id : null);
    });

    // Ngày giờ hiện tại ở header
    function updateDate() {
        const d = new Date(now());
        const dateStr = d.toLocaleDateString('vi-VN', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
        const timeStr = d.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
        const el = $('currentDateText');
        if (el) el.textContent = `${dateStr} • ${timeStr}`;
    }
    updateDate();

    return { updateDate, applyDynamicStyles };
}
