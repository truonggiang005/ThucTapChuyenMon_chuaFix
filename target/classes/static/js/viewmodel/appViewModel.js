/**
 * VIEWMODEL - trạng thái + tính toán + lệnh của ứng dụng (ARC-01).
 *
 * Không đụng DOM: mọi thứ liên quan giao diện (confirm, toast, chuyển trang) đi qua `ui` được inject,
 * nên có thể test bằng Node (xem frontend-tests/).
 *
 * Bảo mật: vai trò & id lấy từ server (/api/auth/me). Việc ẩn menu Quản lý chỉ để tiện dùng;
 * quyền thật do server kiểm tra (SEC-02).
 */
import { createStore } from './store.js';
import { mapShift, toScore, shiftFormToPayload } from '../model/mappers.js';

const AI_CACHE_MINUTES = 10;
const NOTIFICATION_LIMIT = 20;
const NOTIFICATION_TYPES = ['success', 'warning', 'error', 'info'];

export const PAGES = ['personal-schedule', 'shift-market', 'account', 'admin'];

/** Số liệu thống kê (computed). */
export function computeStats(shifts) {
    const count = (status) => shifts.filter(s => s.status === status).length;
    return {
        total: shifts.length,
        open: count('OPEN'),
        // Ca đã nhận = mọi ca đã có người nhận hoặc được gán
        taken: shifts.filter(s => s.assignedToId != null || ['TAKEN', 'ASSIGNED', 'FORCE_ASSIGNED'].includes(s.status)).length,
        unfilled: count('UNFILLED'),
        assigned: count('ASSIGNED'),
        force: count('FORCE_ASSIGNED'),
    };
}

/**
 * @param {object} deps
 * @param {object} deps.api       api client (model/api.js createApi)
 * @param {object} deps.ui        { confirm(msg), toast(msg,type), redirect(url) }
 * @param {object} deps.storage   { getItem, setItem } (localStorage)
 * @param {Function} [deps.now]   () => ms
 * @param {Function} [deps.setTimeoutFn]
 */
export function createAppViewModel({ api, ui, storage, now = () => Date.now(), setTimeoutFn = (f, ms) => setTimeout(f, ms) }) {
    const store = createStore({
        user: null,                // {id, fullName, email, role}
        page: 'personal-schedule',
        employees: [],             // chỉ ADMIN mới tải được
        branches: [],
        skills: [],
        allShifts: [],             // chỉ ADMIN
        personalShifts: [],
        busySchedules: [],
        busySuccess: null,         // thông báo thành công inline sau khi đăng ký lịch bận
        marketShifts: [],
        marketScores: {},          // { [shiftId]: score }
        filter: { branchId: '', skillId: '' },
        candidates: { open: false, shiftId: null, loading: false, error: null, data: null, cachedAt: null },
        editShiftId: null,
        notifications: [],
        aiHealthy: false,
        account: null,             // hồ sơ của chính mình (trang Tài khoản)
        aiCachedIds: [],           // id các ca đã có gợi ý AI lưu cache (hiển thị '(đã lưu)')
    });

    const aiCache = new Map();     // shiftId -> { data, at }  (chỉ để tiết kiệm lượt gọi; server vẫn giới hạn)
    const scoreCache = new Map();  // shiftId -> { score, at }

    const get = () => store.state;
    const isAdmin = () => get().user?.role === 'ADMIN';
    const fresh = (entry) => entry && (now() - entry.at) < AI_CACHE_MINUTES * 60000;
    const errMsg = (e) => (e && e.message) || 'Đã xảy ra lỗi';
    const syncCachedIds = () => store.set({ aiCachedIds: [...aiCache.keys()] });
    const invalidate = (shiftId) => { aiCache.delete(shiftId); scoreCache.delete(shiftId); syncCachedIds(); };

    // ---------------- Thông báo (lưu cục bộ theo từng người dùng) ----------------
    const notifKey = () => `flexishift_notifications_${get().user?.id ?? 'anon'}`;

    function loadNotifications() {
        try {
            const arr = JSON.parse(storage.getItem(notifKey()) || '[]');
            if (!Array.isArray(arr)) return [];
            return arr.filter(n => n && typeof n.message === 'string')
                .map(n => ({ ...n, type: NOTIFICATION_TYPES.includes(n.type) ? n.type : 'info' }))
                .slice(0, NOTIFICATION_LIMIT);
        } catch { return []; }
    }
    function saveNotifications(list) {
        try { storage.setItem(notifKey(), JSON.stringify(list)); } catch { /* hết dung lượng: bỏ qua */ }
    }
    function addNotification(message, type = 'info') {
        const list = [{
            id: now() + Math.random(), message: String(message), time: new Date(now()).toISOString(),
            type: NOTIFICATION_TYPES.includes(type) ? type : 'info', read: false,
        }, ...get().notifications].slice(0, NOTIFICATION_LIMIT);
        store.set({ notifications: list });
        saveNotifications(list);
    }

    // ---------------- Tải dữ liệu ----------------
    async function loadCatalog() {
        try {
            const [branches, skills] = await Promise.all([api.shifts.branches(), api.shifts.skills()]);
            store.set({ branches, skills });
        } catch { /* bộ lọc sẽ trống */ }
    }

    async function loadAdminData() {
        if (!isAdmin()) return;
        try {
            const [employees, shifts] = await Promise.all([api.employees.list(), api.shifts.listAll()]);
            store.set({ employees, allShifts: (Array.isArray(shifts) ? shifts : []).map(mapShift) });
        } catch (e) {
            ui.toast('Lỗi tải dữ liệu quản lý: ' + errMsg(e), 'error');
        }
    }

    async function loadPersonal() {
        const me = get().user;
        if (!me) return;
        try {
            const [shifts, busy] = await Promise.all([
                api.shifts.forEmployee(me.id),
                api.busy.forEmployee(me.id).catch(() => []),
            ]);
            store.set({ personalShifts: (shifts || []).map(mapShift), busySchedules: busy || [] });
        } catch (e) {
            ui.toast('Lỗi tải lịch cá nhân: ' + errMsg(e), 'error');
        }
    }

    async function loadMarket() {
        try {
            const { branchId, skillId } = get().filter;
            let shifts = (await api.shifts.listOpen(branchId)).map(mapShift);
            if (skillId) shifts = shifts.filter(s => s.requiredSkillId != null && String(s.requiredSkillId) === String(skillId));
            store.set({ marketShifts: shifts });

            // Điểm AI của CHÍNH mình cho từng ca (có cache để không gọi lặp)
            const scores = {};
            await Promise.allSettled(shifts.map(async (s) => {
                const cached = scoreCache.get(s.id);
                if (fresh(cached)) { scores[s.id] = cached.score; return; }
                try {
                    const r = await api.shifts.myScore(s.id);
                    const score = toScore(r && r.match_score);
                    scoreCache.set(s.id, { score, at: now() });
                    if (score !== null) scores[s.id] = score;
                } catch { /* AI có thể đang tắt hoặc bị giới hạn: bỏ qua */ }
            }));
            store.set({ marketScores: scores });
        } catch (e) {
            ui.toast('Lỗi tải chợ ca trực: ' + errMsg(e), 'error');
        }
    }

    async function loadAccount() {
        const me = get().user;
        if (!me) return;
        try {
            store.set({ account: await api.employees.get(me.id) });
        } catch (e) {
            ui.toast('Lỗi tải thông tin tài khoản: ' + errMsg(e), 'error');
        }
    }

    async function refreshPage() {
        const page = get().page;
        if (page === 'personal-schedule') await loadPersonal();
        if (page === 'shift-market') await loadMarket();
        if (page === 'account') await loadAccount();
        if (page === 'admin') await loadAdminData();
    }

    async function refreshAll() {
        await Promise.all([loadAdminData(), refreshPage()]);
    }

    async function checkAIHealth() {
        try {
            const r = await api.ai.health();
            store.set({ aiHealthy: !!(r && r.healthy) });
        } catch { store.set({ aiHealthy: false }); }
    }

    // ---------------- Lệnh ----------------
    const commands = {
        /** Khởi tạo: hỏi server ai đang đăng nhập. Chưa đăng nhập → về trang đăng nhập. */
        async init() {
            let user;
            try {
                user = await api.auth.me();
            } catch {
                ui.redirect('/');
                return false;
            }
            store.set({ user });
            store.set({ notifications: loadNotifications() });
            await Promise.all([loadCatalog(), loadAdminData(), loadPersonal(), checkAIHealth()]);   // FUN-01: tải lịch cá nhân ngay
            return true;
        },

        async navigate(page) {
            if (!PAGES.includes(page)) return;
            if (page === 'admin' && !isAdmin()) return;
            store.set({ page });
            await refreshPage();
        },

        async setFilter(patch) {
            store.set({ filter: { ...get().filter, ...patch } });
            await loadMarket();
        },

        checkAIHealth,
        refreshAll,
        loadPersonal,
        loadAdminData,

        async takeShift(shiftId) {
            if (!ui.confirm('Bạn có muốn đăng ký nhận ca này?')) return;
            const me = get().user;
            try {
                const r = await api.shifts.take(shiftId);
                ui.toast('✅ ' + (r.message || 'Đăng ký nhận ca thành công!'), 'success');
                addNotification(`${me.fullName} đã nhận ca #${shiftId}`, 'info');
            } catch (e) {
                ui.toast('❌ ' + errMsg(e), 'error');   // gồm cả lỗi tương tranh 409
            }
            invalidate(shiftId);
            commands.closeCandidates();
            await refreshAll();
        },

        async releaseShift(shiftId) {
            if (!ui.confirm('Bạn có chắc muốn nhả ca này?')) return;
            const me = get().user;
            try {
                const r = await api.shifts.release(shiftId);
                ui.toast(r.message, 'success');
                addNotification(`${me.fullName} đã nhả ca #${shiftId}`, 'warning');
            } catch (e) {
                ui.toast(errMsg(e), 'error');
            }
            invalidate(shiftId);
            await refreshAll();
        },

        async createBusy({ date, start, end, reason }) {
            if (!date) return ui.toast('Vui lòng chọn ngày', 'error');
            if (!start || !end) return ui.toast('Vui lòng chọn giờ bắt đầu và kết thúc', 'error');
            const withSeconds = (t) => (t.length === 5 ? t + ':00' : t);
            const payload = {
                startTime: `${date}T${withSeconds(start)}`,
                endTime: `${date}T${withSeconds(end)}`,
                reason: (reason || '').trim(),
            };
            try {
                const r = await api.busy.create(payload);
                store.set({ busySuccess: 'Đăng ký lịch bận thành công! (Cập nhật sau 00h00)' });
                setTimeoutFn(() => store.set({ busySuccess: null }), 5000);
                ui.toast('✅ ' + r.message, 'success');
                await loadPersonal();
                return true;
            } catch (e) {
                ui.toast('❌ ' + errMsg(e), 'error');
                return false;
            }
        },

        async deleteBusy(busyId) {
            if (!ui.confirm('Bạn có chắc muốn xóa lịch bận này?')) return;
            try {
                const r = await api.busy.remove(busyId);
                ui.toast('✅ ' + r.message, 'success');
                await loadPersonal();
            } catch (e) {
                ui.toast('❌ ' + errMsg(e), 'error');
            }
        },

        async createShift(form) {
            const payload = shiftFormToPayload(form);
            try {
                const r = await api.shifts.create(payload);
                ui.toast('✅ ' + r.message, 'success');
                if (payload.assignedToId) {
                    const emp = get().employees.find(e => e.id === payload.assignedToId);
                    addNotification(`Đã gán ca "${payload.title}" cho ${emp ? emp.fullName : 'NV #' + payload.assignedToId}`, 'success');
                }
                await loadAdminData();
                return true;
            } catch (e) {
                ui.toast('❌ ' + errMsg(e), 'error');
                return false;
            }
        },

        openEditShift(shiftId) {
            if (get().allShifts.some(s => s.id === shiftId)) store.set({ editShiftId: shiftId });
        },
        closeEditShift() { store.set({ editShiftId: null }); },

        async updateShift(shiftId, form) {
            const payload = shiftFormToPayload(form);
            try {
                const r = await api.shifts.update(shiftId, payload);
                ui.toast('✅ ' + r.message, 'success');
                if (payload.assignedToId) {
                    const emp = get().employees.find(e => e.id === payload.assignedToId);
                    addNotification(`Admin đã gán ca "${payload.title}" (#${shiftId}) cho ${emp ? emp.fullName : 'NV #' + payload.assignedToId}`, 'success');
                }
                invalidate(shiftId);
                store.set({ editShiftId: null });
                await loadAdminData();
                return true;
            } catch (e) {
                ui.toast('❌ ' + errMsg(e), 'error');
                return false;
            }
        },

        /** Admin xem gợi ý AI cho một ca (có cache 10 phút). */
        async showCandidates(shiftId) {
            const cached = aiCache.get(shiftId);
            if (fresh(cached)) {
                store.set({ candidates: { open: true, shiftId, loading: false, error: null, data: cached.data, cachedAt: cached.at } });
                ui.toast('📋 Hiển thị gợi ý AI đã lưu (cache)', 'info');
                return;
            }
            store.set({ candidates: { open: true, shiftId, loading: true, error: null, data: null, cachedAt: null } });
            try {
                const data = await api.shifts.candidates(shiftId);
                aiCache.set(shiftId, { data, at: now() });
                syncCachedIds();
                store.set({ candidates: { open: true, shiftId, loading: false, error: null, data, cachedAt: null } });
            } catch (e) {
                const msg = e.status === 429 ? 'Bạn gọi AI quá nhanh. Vui lòng đợi ít phút rồi thử lại.' : errMsg(e);
                store.set({ candidates: { open: true, shiftId, loading: false, error: msg, data: null, cachedAt: null } });
            }
        },

        closeCandidates() {
            store.set({ candidates: { open: false, shiftId: null, loading: false, error: null, data: null, cachedAt: null } });
        },

        /** Admin gán ca cho ứng viên được gợi ý. */
        async assignCandidate(shiftId, employeeId) {
            if (!ui.confirm('Gán ca này cho nhân viên được chọn?')) return;
            try {
                const r = await api.shifts.assign(shiftId, employeeId);
                ui.toast('✅ ' + r.message, 'success');
                const emp = get().employees.find(e => e.id === employeeId);
                addNotification(`Admin đã gán ca #${shiftId} cho ${emp ? emp.fullName : 'NV #' + employeeId} qua AI gợi ý`, 'success');
                invalidate(shiftId);
                commands.closeCandidates();
            } catch (e) {
                ui.toast('❌ ' + errMsg(e), 'error');
            }
            await refreshAll();
        },

        markNotificationRead(id) {
            const list = get().notifications.map(n => (n.id === id ? { ...n, read: true } : n));
            store.set({ notifications: list });
            saveNotifications(list);
        },
        clearNotifications() {
            store.set({ notifications: [] });
            saveNotifications([]);
        },

        async logout() {
            try { await api.auth.logout(); } catch { /* vẫn về trang đăng nhập */ }
            ui.redirect('/');
        },
    };

    return { store, commands, isAdmin };
}
