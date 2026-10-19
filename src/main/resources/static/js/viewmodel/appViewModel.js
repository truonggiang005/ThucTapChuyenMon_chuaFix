/**
 * VIEWMODEL - trạng thái + tính toán + lệnh của ứng dụng (ARC-01).
 *
 * Không đụng DOM: mọi thứ liên quan giao diện (hộp thoại xác nhận, toast, chuyển trang) đi qua `ui` được inject,
 * nên có thể test bằng Node (xem frontend-tests/).
 *
 * Bảo mật: vai trò & id lấy từ server (/api/auth/me). Việc ẩn menu Quản lý chỉ để tiện dùng;
 * quyền thật do server kiểm tra (SEC-02).
 */
import { createStore } from './store.js';
import {
    mapShift, mapNotification, mapMyScore, shiftFormToPayload, toInt, withSeconds,
} from '../model/mappers.js';
import { toKey } from '../model/dates.js';

const AI_CACHE_MINUTES = 10;
const ADMIN_PAGE_SIZE = 50;
const NOTIFICATION_POLL_TOAST_LIMIT = 2;

export const PAGES = ['personal-schedule', 'shift-market', 'account', 'admin'];
export const ADMIN_TABS = ['shifts', 'employees', 'catalog'];

/** Số liệu thống kê của danh sách ca đang xem. "Nhân viên đã nhận" CHỈ đếm TAKEN (tự nhận), không lẫn ca được gán (D6). */
export function computeStats(shifts) {
    const count = (...statuses) => shifts.filter(s => statuses.includes(s.status)).length;
    return {
        total: shifts.length,
        open: count('OPEN'),
        taken: count('TAKEN'),
        assigned: count('ASSIGNED', 'FORCE_ASSIGNED'),
        completed: count('COMPLETED'),
        unfilled: count('UNFILLED'),
    };
}

/** "YYYY-MM-DDTHH:mm:ss" theo giờ địa phương của trình duyệt (để so sánh với chuỗi giờ của ca). */
export function localIso(ms) {
    const d = new Date(ms);
    const p = (n) => String(n).padStart(2, '0');
    return `${toKey(d)}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * @param {object} deps
 * @param {object} deps.api       api client (model/api.js createApi)
 * @param {object} deps.ui        { confirm(msg, opts) → Promise<boolean>, toast(msg,type), redirect(url) }
 * @param {Function} [deps.now]   () => ms
 */
export function createAppViewModel({ api, ui, now = () => Date.now() }) {
    const store = createStore({
        user: null,                // {id, fullName, email, role}
        page: 'personal-schedule',
        adminTab: 'shifts',
        employees: [],             // chỉ ADMIN mới tải được
        branches: [],
        skills: [],
        allShifts: [],             // chỉ ADMIN (một trang trong khoảng lọc)
        adminFilter: { from: '', to: '', status: '', page: 0 },
        adminHasMore: false,
        personalShifts: [],
        busySchedules: [],
        marketShifts: [],
        market: { status: 'idle', message: null, scores: {} },   // scores: { [shiftId]: {eligible, reason, score, confidence, source} }
        filter: { branchId: '', skillId: '' },
        candidates: { open: false, shiftId: null, loading: false, error: null, data: null, cachedAt: null },
        editShiftId: null,
        dialog: null,              // { type: 'employee'|'password'|'branch'|'skill', id: number|null }
        notifications: [],
        ai: { checked: false, healthy: false, geminiConfigured: false, reason: null, message: null },
        account: null,             // hồ sơ của chính mình (trang Tài khoản)
        aiCachedIds: [],           // id các ca đã có gợi ý AI lưu cache (hiển thị '(đã lưu)')
        tick: 0,                   // tăng định kỳ để View vẽ lại phần phụ thuộc thời gian ("đã bắt đầu", "x phút trước")
    });

    // Cache chỉ để tiết kiệm lượt gọi; MỌI thao tác làm thay đổi dữ liệu đều xóa SẠCH cache (D3).
    const aiCache = new Map();     // shiftId -> { data, at }
    const scoreCache = new Map();  // shiftId -> { row, at }
    let marketSeq = 0;             // bỏ kết quả của lần tải Chợ ca đã cũ
    let knownNotificationIds = null;
    const inflight = new Set();

    const get = () => store.state;
    const isAdmin = () => get().user?.role === 'ADMIN';
    const fresh = (entry) => entry && (now() - entry.at) < AI_CACHE_MINUTES * 60000;
    const errMsg = (e) => (e && e.message) || 'Đã xảy ra lỗi';
    const syncCachedIds = () => store.set({ aiCachedIds: [...aiCache.keys()] });
    const invalidateAll = () => { aiCache.clear(); scoreCache.clear(); syncCachedIds(); };

    /** Chặn bấm đúp: bỏ qua nếu thao tác cùng khóa đang chạy. */
    async function once(key, fn) {
        if (inflight.has(key)) return undefined;
        inflight.add(key);
        try { return await fn(); } finally { inflight.delete(key); }
    }

    // ---------------- Tải dữ liệu ----------------
    async function loadCatalog() {
        try {
            const [branches, skills] = await Promise.all([api.catalog.branches(), api.catalog.skills()]);
            store.set({ branches, skills });
        } catch { /* bộ lọc sẽ trống */ }
    }

    function adminQuery() {
        const { from, to, page } = get().adminFilter;
        return {
            from: from ? withSeconds(`${from}T00:00`) : undefined,
            to: to ? withSeconds(`${to}T23:59`) : undefined,
            page, size: ADMIN_PAGE_SIZE,
        };
    }

    async function loadAdminShifts() {
        if (!isAdmin()) return;
        try {
            const shifts = await api.shifts.listAll(adminQuery());
            const list = (Array.isArray(shifts) ? shifts : []).map(mapShift);
            store.set({ allShifts: list, adminHasMore: list.length >= ADMIN_PAGE_SIZE });
        } catch (e) {
            ui.toast('Lỗi tải danh sách ca: ' + errMsg(e), 'error');
        }
    }

    async function loadEmployees() {
        if (!isAdmin()) return;
        try {
            store.set({ employees: await api.employees.list() });
        } catch (e) {
            ui.toast('Lỗi tải danh sách nhân viên: ' + errMsg(e), 'error');
        }
    }

    async function loadAdminData() {
        if (!isAdmin()) return;
        await Promise.all([loadAdminShifts(), loadEmployees()]);
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

    /**
     * Chợ ca: hiện danh sách ngay, rồi lấy điểm của CHÍNH mình cho cả trang bằng MỘT lời gọi (B4).
     * Có trạng thái loading/lỗi rõ ràng; không bao giờ hiện điểm giả khi AI hỏng (B2, E8).
     */
    async function loadMarket() {
        const seq = ++marketSeq;
        let shifts;
        try {
            const { branchId, skillId } = get().filter;
            shifts = (await api.shifts.listOpen(branchId)).map(mapShift);
            if (skillId) shifts = shifts.filter(s => s.requiredSkillId != null && String(s.requiredSkillId) === String(skillId));
        } catch (e) {
            ui.toast('Lỗi tải chợ ca trực: ' + errMsg(e), 'error');
            return;
        }
        if (seq !== marketSeq) return;

        const scores = {};
        const need = [];
        for (const s of shifts) {
            const cached = scoreCache.get(s.id);
            if (fresh(cached)) scores[s.id] = cached.row; else need.push(s.id);
        }
        store.set({ marketShifts: shifts, market: { status: need.length ? 'loading' : 'ok', message: null, scores: { ...scores } } });
        if (need.length === 0) return;

        let status = 'ok';
        let message = null;
        try {
            const r = await api.shifts.myScores(need);
            if (seq !== marketSeq) return;
            for (const raw of (r && r.scores) || []) {
                const row = mapMyScore(raw);
                scores[row.shiftId] = row;
                if (row.eligible === false || row.score !== null) scoreCache.set(row.shiftId, { row, at: now() });
            }
            if (r && r.available === false) { status = 'error'; message = r.message || 'Chưa lấy được điểm gợi ý.'; }
        } catch (e) {
            if (seq !== marketSeq) return;
            status = 'error';
            message = e.status === 429 ? 'Bạn thao tác quá nhanh, vui lòng đợi một chút rồi thử lại.' : errMsg(e);
        }
        store.set({ market: { status, message, scores } });
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
        await Promise.all([loadAdminShifts(), refreshPage()]);
    }

    /** Sau MỌI thay đổi dữ liệu: xóa sạch cache AI/điểm rồi tải lại (D3). */
    async function afterMutation() {
        invalidateAll();
        await refreshAll();
    }

    async function checkAIHealth() {
        try {
            const r = await api.ai.health();
            store.set({ ai: { checked: true, healthy: !!(r && r.healthy), geminiConfigured: !!(r && r.geminiConfigured),
                reason: (r && r.reason) || null, message: (r && r.message) || null } });
        } catch {
            store.set({ ai: { checked: true, healthy: false, geminiConfigured: false, reason: 'AI_UNREACHABLE',
                message: 'Không kiểm tra được trạng thái AI.' } });
        }
    }

    // ---------------- Thông báo: lưu ở SERVER, đồng bộ giữa thiết bị (D4) ----------------
    async function loadNotifications() {
        try {
            const list = (await api.notifications.list()).map(mapNotification);
            const first = knownNotificationIds === null;
            if (!first) {
                const newOnes = list.filter(n => !n.read && !knownNotificationIds.has(n.id)).slice(0, NOTIFICATION_POLL_TOAST_LIMIT);
                newOnes.forEach(n => ui.toast(n.message, n.type));
            }
            knownNotificationIds = new Set(list.map(n => n.id));
            store.set({ notifications: list });
        } catch { /* mạng chập chờn: lần poll sau sẽ thử lại */ }
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
            await Promise.all([loadCatalog(), loadAdminData(), loadPersonal(), checkAIHealth(), loadNotifications()]);
            return true;
        },

        async navigate(page) {
            if (!PAGES.includes(page)) return;
            if (page === 'admin' && !isAdmin()) return;
            store.set({ page });
            await refreshPage();
        },

        async setAdminTab(tab) {
            if (!ADMIN_TABS.includes(tab) || !isAdmin()) return;
            store.set({ adminTab: tab });
            if (tab === 'employees') await loadEmployees();
            if (tab === 'catalog') await loadCatalog();
        },

        async setFilter(patch) {
            store.set({ filter: { ...get().filter, ...patch } });
            await loadMarket();
        },

        /** Lọc lưới ca của admin theo khoảng ngày (phía server) và trạng thái (phía client). */
        async setAdminFilter(patch) {
            const prev = get().adminFilter;
            const next = { ...prev, ...patch };
            const serverSide = ['from', 'to'].some(k => next[k] !== prev[k]);
            if (serverSide && !('page' in patch)) next.page = 0;
            store.set({ adminFilter: next });
            if (serverSide || next.page !== prev.page) await loadAdminShifts();
        },

        async adminPage(delta) {
            const page = Math.max(0, get().adminFilter.page + delta);
            if (page === get().adminFilter.page) return;
            await commands.setAdminFilter({ page });
        },

        tick() { store.set({ tick: get().tick + 1 }); },

        checkAIHealth,
        loadNotifications,
        refreshAll,
        loadPersonal,
        loadAdminData,
        reloadMarket: loadMarket,

        async takeShift(shiftId) {
            if (!(await ui.confirm('Bạn có muốn đăng ký nhận ca này?', { okText: 'Nhận ca' }))) return;
            await once(`take:${shiftId}`, async () => {
                try {
                    const r = await api.shifts.take(shiftId);
                    ui.toast('✅ ' + (r.message || 'Đăng ký nhận ca thành công!'), 'success');
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');   // gồm cả lỗi tương tranh 409
                }
                commands.closeCandidates();
                await afterMutation();
                await loadNotifications();
            });
        },

        async releaseShift(shiftId) {
            if (!(await ui.confirm('Bạn có chắc muốn nhả ca này? Ca sẽ quay lại Chợ ca và được tính vào số lần hủy của bạn.', { okText: 'Nhả ca', danger: true }))) return;
            await once(`release:${shiftId}`, async () => {
                try {
                    const r = await api.shifts.release(shiftId);
                    ui.toast('✅ ' + r.message, 'success');
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                }
                await afterMutation();
            });
        },

        async createBusy({ date, endDate, start, end, reason }) {
            if (!date) { ui.toast('Vui lòng chọn ngày', 'error'); return false; }
            if (!start || !end) { ui.toast('Vui lòng chọn giờ bắt đầu và kết thúc', 'error'); return false; }
            const lastDay = endDate || date;
            if (lastDay < date) { ui.toast('Ngày kết thúc phải từ ngày bắt đầu trở đi', 'error'); return false; }
            const payload = {
                startTime: `${date}T${withSeconds(start)}`,
                endTime: `${lastDay}T${withSeconds(end)}`,
                reason: (reason || '').trim(),
            };
            if (payload.endTime <= payload.startTime) { ui.toast('Thời gian kết thúc phải sau thời gian bắt đầu', 'error'); return false; }
            return once('busy:create', async () => {
                try {
                    const r = await api.busy.create(payload);
                    ui.toast('✅ ' + r.message, 'success');
                    invalidateAll();
                    await loadPersonal();
                    return true;
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                    return false;
                }
            });
        },

        async deleteBusy(busyId) {
            if (!(await ui.confirm('Bạn có chắc muốn xóa lịch bận này?', { okText: 'Xóa', danger: true }))) return;
            await once(`busy:delete:${busyId}`, async () => {
                try {
                    const r = await api.busy.remove(busyId);
                    ui.toast('✅ ' + r.message, 'success');
                    invalidateAll();
                    await loadPersonal();
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                }
            });
        },

        async createShift(form) {
            const payload = shiftFormToPayload(form);
            return once('shift:create', async () => {
                try {
                    const r = await api.shifts.create(payload);
                    ui.toast('✅ ' + r.message, 'success');
                    await afterMutation();
                    return true;
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                    return false;
                }
            });
        },

        openEditShift(shiftId) {
            if (get().allShifts.some(s => s.id === shiftId)) store.set({ editShiftId: shiftId });
        },
        closeEditShift() { if (get().editShiftId !== null) store.set({ editShiftId: null }); },

        async updateShift(shiftId, form) {
            const payload = shiftFormToPayload(form);
            return once(`shift:update:${shiftId}`, async () => {
                try {
                    const r = await api.shifts.update(shiftId, payload);
                    ui.toast('✅ ' + r.message, 'success');
                    store.set({ editShiftId: null });
                    await afterMutation();
                    return true;
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                    if (e.status === 409) await loadAdminShifts();   // dữ liệu đã đổi: tải lại để sửa trên bản mới nhất
                    return false;
                }
            });
        },

        /** Admin xem gợi ý AI cho một ca (cache 10 phút; `force` = bỏ cache, chấm lại). */
        async showCandidates(shiftId, { force = false } = {}) {
            const cached = aiCache.get(shiftId);
            if (!force && fresh(cached)) {
                store.set({ candidates: { open: true, shiftId, loading: false, error: null, data: cached.data, cachedAt: cached.at } });
                return;
            }
            store.set({ candidates: { open: true, shiftId, loading: true, error: null, data: null, cachedAt: null } });
            try {
                const data = await api.shifts.candidates(shiftId);
                // Chỉ lưu cache kết quả THUẦN Gemini: điểm dự phòng/AI hỏng không được lưu để hồi phục là có điểm ngay (B3)
                if (data && data.available !== false && !data.fallback) {
                    aiCache.set(shiftId, { data, at: now() });
                    syncCachedIds();
                }
                if (get().candidates.shiftId === shiftId) {
                    store.set({ candidates: { open: true, shiftId, loading: false, error: null, data, cachedAt: null } });
                }
            } catch (e) {
                const msg = e.status === 429 ? 'Bạn gọi AI quá nhanh. Vui lòng đợi ít phút rồi thử lại.' : errMsg(e);
                if (get().candidates.shiftId === shiftId) {
                    store.set({ candidates: { open: true, shiftId, loading: false, error: msg, data: null, cachedAt: null } });
                }
            }
        },

        closeCandidates() {
            if (get().candidates.open) {
                store.set({ candidates: { open: false, shiftId: null, loading: false, error: null, data: null, cachedAt: null } });
            }
        },

        /** Admin gán ca cho ứng viên được gợi ý. */
        async assignCandidate(shiftId, employeeId) {
            const emp = get().employees.find(e => e.id === employeeId);
            const fallback = get().candidates.data && get().candidates.data.fallback;
            const msg = `Gán ca này cho ${emp ? emp.fullName : 'nhân viên được chọn'}?`
                + (fallback ? ' Lưu ý: điểm hiện tại là điểm dự phòng theo quy tắc, không phải do Gemini chấm.' : '');
            if (!(await ui.confirm(msg, { okText: 'Gán ca' }))) return;
            await once(`assign:${shiftId}`, async () => {
                try {
                    const r = await api.shifts.assign(shiftId, employeeId);
                    ui.toast('✅ ' + r.message, 'success');
                    commands.closeCandidates();
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                }
                await afterMutation();
            });
        },

        async markLate(shiftId) {
            if (!(await ui.confirm('Ghi nhận nhân viên này ĐẾN MUỘN ở ca này? Hành động này cộng vào chỉ số đi muộn và không hoàn tác được.', { okText: 'Ghi nhận', danger: true }))) return;
            await once(`late:${shiftId}`, async () => {
                try {
                    const r = await api.shifts.markLate(shiftId);
                    ui.toast('✅ ' + (r.message || 'Đã ghi nhận đến muộn'), 'success');
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                }
                await afterMutation();
            });
        },

        // ----- Nhân viên / danh mục (ADMIN) -----
        openDialog(type, id = null) {
            if (!isAdmin()) return;
            store.set({ dialog: { type, id } });
        },
        closeDialog() { if (get().dialog !== null) store.set({ dialog: null }); },

        async saveEmployee(form, id = null) {
            const payload = {
                fullName: String(form.fullName || '').trim(),
                role: form.role || 'EMPLOYEE',
                maxHoursPerWeek: toInt(form.maxHoursPerWeek),
                preferredTime: form.preferredTime || 'ANY',
                primaryBranchId: toInt(form.primaryBranchId),
                skills: (form.skills || []).map(k => ({ skillId: toInt(k.skillId), level: toInt(k.level) })),
            };
            return once('employee:save', async () => {
                try {
                    if (id === null) {
                        await api.employees.create({ ...payload, email: String(form.email || '').trim(), password: form.password || '' });
                        ui.toast('✅ Đã tạo nhân viên', 'success');
                    } else {
                        await api.employees.update(id, { ...payload, active: form.active !== false });
                        ui.toast('✅ Đã cập nhật nhân viên', 'success');
                    }
                    store.set({ dialog: null });
                    invalidateAll();
                    await loadEmployees();
                    await refreshPage();
                    return true;
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                    return false;
                }
            });
        },

        async resetEmployeePassword(id, newPassword) {
            return once('employee:reset', async () => {
                try {
                    await api.employees.resetPassword(id, newPassword);
                    ui.toast('✅ Đã đặt lại mật khẩu. Hãy báo cho nhân viên mật khẩu mới và yêu cầu đổi ngay.', 'success');
                    store.set({ dialog: null });
                    return true;
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                    return false;
                }
            });
        },

        async saveBranch(form, id = null) {
            const payload = { name: String(form.name || '').trim(), address: String(form.address || '').trim(), maxCapacity: toInt(form.maxCapacity) };
            return once('branch:save', async () => {
                try {
                    if (id === null) await api.catalog.createBranch(payload); else await api.catalog.updateBranch(id, payload);
                    ui.toast('✅ Đã lưu chi nhánh', 'success');
                    store.set({ dialog: null });
                    await loadCatalog();
                    return true;
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                    return false;
                }
            });
        },

        async saveSkill(form, id = null) {
            const payload = { name: String(form.name || '').trim() };
            return once('skill:save', async () => {
                try {
                    if (id === null) await api.catalog.createSkill(payload); else await api.catalog.updateSkill(id, payload);
                    ui.toast('✅ Đã lưu kỹ năng', 'success');
                    store.set({ dialog: null });
                    await loadCatalog();
                    return true;
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                    return false;
                }
            });
        },

        /** Đổi mật khẩu của chính mình (C4). Kiểm tra khớp ở client cho tiện; chính sách mật khẩu do server quyết định. */
        async changePassword({ current, next, confirm }) {
            if (!current || !next) { ui.toast('Vui lòng nhập đủ mật khẩu hiện tại và mật khẩu mới', 'error'); return false; }
            if (next !== confirm) { ui.toast('Mật khẩu nhập lại không khớp', 'error'); return false; }
            return once('password:change', async () => {
                try {
                    await api.auth.changePassword(current, next);
                    ui.toast('✅ Đã đổi mật khẩu', 'success');
                    return true;
                } catch (e) {
                    ui.toast('❌ ' + errMsg(e), 'error');
                    return false;
                }
            });
        },

        // ----- Thông báo -----
        async markNotificationRead(id) {
            const target = get().notifications.find(n => n.id === id);
            if (!target || target.read) return;
            store.set({ notifications: get().notifications.map(n => (n.id === id ? { ...n, read: true } : n)) });
            try { await api.notifications.markRead(id); } catch { /* lần poll sau sẽ đồng bộ lại */ }
        },
        async markAllNotificationsRead() {
            store.set({ notifications: get().notifications.map(n => ({ ...n, read: true })) });
            try { await api.notifications.markAllRead(); } catch { /* đồng bộ lại ở lần poll sau */ }
        },
        async clearNotifications() {
            store.set({ notifications: [] });
            knownNotificationIds = new Set();
            try { await api.notifications.clear(); } catch { /* đồng bộ lại ở lần poll sau */ }
        },

        async logout() {
            try { await api.auth.logout(); } catch { /* vẫn về trang đăng nhập */ }
            ui.redirect('/');
        },
    };

    return { store, commands, isAdmin };
}
