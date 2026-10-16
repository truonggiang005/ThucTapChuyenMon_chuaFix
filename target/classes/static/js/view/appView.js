/**
 * VIEW - gắn ViewModel với DOM (ARC-01).
 *  - Đăng ký vào store: state đổi → vẽ lại đúng phần giao diện liên quan (khai báo theo template).
 *  - Sự kiện: event delegation qua thuộc tính data-action (không onclick inline → tương thích CSP).
 */
import { setHtml, html } from './html.js';
import * as T from './templates.js';
import { computeStats } from '../viewmodel/appViewModel.js';

export function mountApp({ vm, doc = document }) {
    const $ = (id) => doc.getElementById(id);
    const { store, commands } = vm;

    // ---------------- Vẽ lại theo state ----------------
    function renderSelectsKeepingValue(id, safeHtml) {
        const el = $(id);
        if (!el) return;
        const prev = el.value;
        setHtml(el, safeHtml);
        if ([...el.options].some(o => o.value === prev)) el.value = prev;
    }

    function renderEditModal(state) {
        const modal = $('editShiftModal');
        if (!modal) return;
        const shift = state.editShiftId != null ? state.allShifts.find(s => s.id === state.editShiftId) : null;
        if (!shift) { modal.style.display = 'none'; return; }

        setHtml($('editShiftBranch'), T.options('-- Chọn chi nhánh --', state.branches));
        setHtml($('editShiftSkill'), T.options('-- Không yêu cầu --', state.skills));
        setHtml($('editShiftEmployee'), T.options('-- Để trống (Trạng thái OPEN) --', state.employees, 'fullName'));

        $('editShiftId').value = shift.id;
        $('editShiftTitle').value = shift.title;
        $('editShiftStartTime').value = shift.startTime.substring(0, 16);
        $('editShiftEndTime').value = shift.endTime.substring(0, 16);
        $('editShiftBranch').value = shift.branchId || '';
        $('editShiftSkill').value = shift.requiredSkillId || '';
        $('editShiftLevel').value = shift.requiredLevel || 1;
        $('editShiftEmployee').value = shift.assignedToId || '';
        modal.style.display = 'flex';
    }

    function render(state, changed) {
        const has = (...keys) => keys.some(k => changed.has(k));
        const isAdmin = state.user?.role === 'ADMIN';

        if (has('user')) {
            if (state.user) {
                $('userName').textContent = state.user.fullName || 'Người dùng';
                $('userRoleStr').textContent = '(' + (isAdmin ? 'Quản lý' : 'Nhân viên') + ')';
                const busyName = $('busyEmployeeNameDisplay');
                if (busyName) busyName.textContent = state.user.fullName || '';
                // Chỉ ẩn cho gọn: quyền thật do server kiểm tra
                $('navAdmin').style.display = isAdmin ? '' : 'none';
            }
        }

        if (has('page')) {
            doc.querySelectorAll('.nav-link').forEach(l => l.classList.toggle('active', l.dataset.page === state.page));
            doc.querySelectorAll('.page').forEach(p => p.classList.toggle('active', p.id === 'page-' + state.page));
        }

        if (has('personalShifts')) setHtml($('weeklySchedule'), T.weeklySchedule(state.personalShifts));
        if (has('busySchedules')) setHtml($('busyEntriesDisplay'), T.busyEntries(state.busySchedules));
        if (has('busySuccess')) {
            const box = $('busySuccessMsg');
            if (box) {
                box.style.display = state.busySuccess ? 'flex' : 'none';
                $('busySuccessText').textContent = state.busySuccess || '';
            }
        }

        if (has('marketShifts', 'marketScores')) setHtml($('marketGrid'), T.marketGrid(state.marketShifts, state.marketScores));
        if (has('account')) setHtml($('accountInfo'), T.accountInfo(state.account));

        if (has('allShifts')) {
            const st = computeStats(state.allShifts);
            const set = (id, v) => { const el = $(id); if (el) el.textContent = v; };
            set('statTotal', st.total); set('statOpen', st.open); set('statTaken', st.taken);
            set('statUnfilled', st.unfilled); set('statAssigned', st.assigned); set('statForce', st.force);
        }
        if (has('allShifts', 'user', 'aiCachedIds')) {
            setHtml($('allShiftsGrid'), T.adminShiftsGrid(state.allShifts, {
                currentUserId: state.user?.id, isAdmin, cachedIds: new Set(state.aiCachedIds),
            }));
        }
        if (has('candidates')) setHtml($('candidatesPanel'), T.candidatesPanel(state.candidates));

        if (has('notifications')) {
            const unread = state.notifications.filter(n => !n.read).length;
            const badge = $('notificationBadge');
            if (badge) {
                badge.style.display = unread > 0 ? 'flex' : 'none';
                badge.textContent = unread > 9 ? '9+' : String(unread);
            }
            setHtml($('notificationList'), T.notificationList(state.notifications));
        }

        if (has('aiHealthy')) {
            $('aiStatusDot').classList.toggle('healthy', state.aiHealthy);
            $('aiStatusText').textContent = 'AI';
        }

        if (has('branches')) {
            renderSelectsKeepingValue('filterBranch', T.options('Chi nhánh', state.branches));
            renderSelectsKeepingValue('shiftBranch', T.options('-- Chọn chi nhánh --', state.branches));
        }
        if (has('skills')) {
            renderSelectsKeepingValue('filterSkill', T.options('All Vị trí', state.skills));
            renderSelectsKeepingValue('shiftSkill', T.options('-- Không yêu cầu --', state.skills));
        }
        if (has('employees')) {
            renderSelectsKeepingValue('shiftEmployee', T.options('-- Để trống (Trạng thái OPEN) --', state.employees, 'fullName'));
        }

        if (has('editShiftId', 'allShifts', 'branches', 'skills', 'employees')) renderEditModal(state);
    }

    store.subscribe(render);

    // ---------------- Sự kiện ----------------
    const num = (v) => Number(v);
    const actions = {
        'take': (el) => commands.takeShift(num(el.dataset.id)),
        'release': (el) => commands.releaseShift(num(el.dataset.id)),
        'delete-busy': (el) => commands.deleteBusy(num(el.dataset.id)),
        'show-candidates': (el) => commands.showCandidates(num(el.dataset.id)),
        'close-candidates': () => commands.closeCandidates(),
        'assign-candidate': (el) => commands.assignCandidate(num(el.dataset.shiftId), num(el.dataset.employeeId)),
        'edit-shift': (el) => commands.openEditShift(num(el.dataset.id)),
        'close-edit': () => commands.closeEditShift(),
        'mark-read': (el) => commands.markNotificationRead(num(el.dataset.id)),
    };

    doc.addEventListener('click', (e) => {
        const nav = e.target.closest('.nav-link[data-page]');
        if (nav) { e.preventDefault(); commands.navigate(nav.dataset.page); return; }

        const el = e.target.closest('[data-action]');
        if (el && actions[el.dataset.action]) { actions[el.dataset.action](el); return; }

        // Menu thông báo
        const dropdown = $('notificationDropdown');
        if (e.target.closest('#notificationBtn')) {
            dropdown.style.display = dropdown.style.display === 'none' ? 'block' : 'none';
        } else if (dropdown && !e.target.closest('#notificationWrapper')) {
            dropdown.style.display = 'none';
        }
    });

    $('clearNotifications')?.addEventListener('click', () => commands.clearNotifications());
    $('btnLogout')?.addEventListener('click', () => commands.logout());

    $('filterBranch')?.addEventListener('change', (e) => commands.setFilter({ branchId: e.target.value }));
    $('filterSkill')?.addEventListener('change', (e) => commands.setFilter({ skillId: e.target.value }));

    $('createShiftForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const ok = await commands.createShift({
            title: $('shiftTitle').value, startTime: $('shiftStartTime').value, endTime: $('shiftEndTime').value,
            branchId: $('shiftBranch').value, skillId: $('shiftSkill').value,
            level: $('shiftLevel').value, employeeId: $('shiftEmployee').value,
        });
        if (ok) e.target.reset();
    });

    $('editShiftForm')?.addEventListener('submit', (e) => {
        e.preventDefault();
        commands.updateShift(num($('editShiftId').value), {
            title: $('editShiftTitle').value, startTime: $('editShiftStartTime').value, endTime: $('editShiftEndTime').value,
            branchId: $('editShiftBranch').value, skillId: $('editShiftSkill').value,
            level: $('editShiftLevel').value, employeeId: $('editShiftEmployee').value,
        });
    });

    $('busyScheduleForm')?.addEventListener('submit', async (e) => {
        e.preventDefault();
        const ok = await commands.createBusy({
            date: $('busyDate').value, start: $('busyStartTime').value,
            end: $('busyEndTime').value, reason: $('busyReason').value,
        });
        if (ok) e.target.reset();
    });

    // Ngày giờ hiện tại ở header
    function updateDate() {
        const now = new Date();
        const d = now.toLocaleDateString('vi-VN', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
        const t = now.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
        const el = $('currentDateText');
        if (el) el.textContent = `${d} • ${t}`;
    }
    updateDate();

    return { updateDate };
}
