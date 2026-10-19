import test from 'node:test';
import assert from 'node:assert/strict';
import { load } from './_paths.mjs';

const { createAppViewModel, computeStats, localIso } = await load('viewmodel/appViewModel.js');
const { ApiError } = await load('model/api.js');

const ADMIN = { id: 1, fullName: 'Quản lý', email: 'a@x', role: 'ADMIN' };
const EMP = { id: 2, fullName: 'Nhân viên', email: 'e@x', role: 'EMPLOYEE' };

const openShift = (id, extra = {}) => ({ id, title: `Ca ${id}`, status: 'OPEN', startTime: '2026-11-05T07:00:00', endTime: '2026-11-05T15:00:00', ...extra });

function fakeEnv({ user = EMP, confirmAnswer = true, overrides = {} } = {}) {
    const calls = [];
    const rec = (name, impl) => async (...a) => { calls.push([name, ...a]); return impl ? impl(...a) : {}; };
    const api = {
        auth: { me: rec('me', () => user), logout: rec('logout'), changePassword: rec('changePassword') },
        catalog: { branches: rec('branches', () => []), skills: rec('skills', () => []) },
        shifts: {
            listAll: rec('listAll', () => []),
            listOpen: rec('listOpen', () => [openShift(10), openShift(11)]),
            forEmployee: rec('forEmployee', () => []),
            take: rec('take', () => ({ message: 'ok' })),
            release: rec('release', () => ({ message: 'ok' })),
            assign: rec('assign', () => ({ message: 'ok' })),
            update: rec('update', () => ({ message: 'ok' })),
            create: rec('create', () => ({ message: 'ok' })),
            markLate: rec('markLate', () => ({ message: 'ok' })),
            candidates: rec('candidates', () => ({ available: true, fallback: false, ranked_candidates: [{ employee_id: 5, full_name: 'An', match_score: 80 }] })),
            myScores: rec('myScores', (ids) => ({ available: true, scores: ids.map(id => ({ shiftId: id, eligible: true, matchScore: 70, confidence: 50, source: 'heuristic' })) })),
        },
        employees: { list: rec('empList', () => []), get: rec('empGet', () => EMP), create: rec('empCreate'), update: rec('empUpdate'), resetPassword: rec('reset') },
        busy: { forEmployee: rec('busyList', () => []), create: rec('busyCreate', () => ({ message: 'ok' })), remove: rec('busyRemove', () => ({ message: 'ok' })) },
        notifications: { list: rec('notifList', () => []), markRead: rec('markRead'), markAllRead: rec('markAll'), clear: rec('clear') },
        ai: { health: rec('health', () => ({ healthy: true, geminiConfigured: true })) },
        ...overrides,
    };
    const toasts = [];
    const confirms = [];
    const ui = {
        confirm: async (msg) => { confirms.push(msg); return confirmAnswer; },
        toast: (msg, type) => toasts.push({ msg, type }),
        redirect: (url) => calls.push(['redirect', url]),
    };
    let t = Date.parse('2026-11-02T10:00:00');
    const vm = createAppViewModel({ api, ui, now: () => t });
    return { vm, api, calls, toasts, confirms, advance: (ms) => { t += ms; } };
}
const count = (calls, name) => calls.filter(c => c[0] === name).length;

test('computeStats: "đã nhận" chỉ đếm TAKEN, ca gán không lẫn vào (D6)', () => {
    const s = computeStats([{ status: 'OPEN' }, { status: 'TAKEN' }, { status: 'ASSIGNED' }, { status: 'FORCE_ASSIGNED' }, { status: 'COMPLETED' }, { status: 'UNFILLED' }]);
    assert.deepEqual(s, { total: 6, open: 1, taken: 1, assigned: 2, completed: 1, unfilled: 1 });
});

test('localIso dùng giờ địa phương', () => {
    assert.match(localIso(Date.now()), /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
});

test('init: chưa đăng nhập → chuyển về trang đăng nhập', async () => {
    const env = fakeEnv({ overrides: { auth: { me: async () => { throw new ApiError('x', 401); } } } });
    assert.equal(await env.vm.commands.init(), false);
    assert.deepEqual(env.calls.at(-1), ['redirect', '/']);
});

test('nhân viên không tải dữ liệu admin và không vào được trang admin (SEC-02)', async () => {
    const env = fakeEnv({ user: EMP });
    await env.vm.commands.init();
    assert.equal(count(env.calls, 'listAll'), 0);
    assert.equal(count(env.calls, 'empList'), 0);
    await env.vm.commands.navigate('admin');
    assert.notEqual(env.vm.store.state.page, 'admin');
    env.vm.commands.openDialog('employee');
    assert.equal(env.vm.store.state.dialog, null);
});

test('admin tải ca + nhân viên khi khởi tạo', async () => {
    const env = fakeEnv({ user: ADMIN });
    await env.vm.commands.init();
    assert.equal(count(env.calls, 'listAll'), 1);
    assert.equal(count(env.calls, 'empList'), 1);
});

test('Chợ ca: lấy điểm cả trang bằng ĐÚNG MỘT lời gọi, không gọi theo từng ca (B4)', async () => {
    const env = fakeEnv();
    await env.vm.commands.init();
    await env.vm.commands.navigate('shift-market');
    assert.equal(count(env.calls, 'myScores'), 1);
    const m = env.vm.store.state.market;
    assert.equal(m.status, 'ok');
    assert.equal(m.scores[10].score, 70);
});

test('Chợ ca: dùng cache lần sau, và MỌI thao tác thay đổi dữ liệu xóa sạch cache (D3)', async () => {
    const env = fakeEnv();
    await env.vm.commands.init();
    await env.vm.commands.navigate('shift-market');
    await env.vm.commands.reloadMarket();
    assert.equal(count(env.calls, 'myScores'), 1, 'lần tải lại trong 10 phút phải dùng cache');
    await env.vm.commands.createBusy({ date: '2026-11-06', start: '08:00', end: '10:00', reason: 'x' });
    await env.vm.commands.reloadMarket();
    assert.equal(count(env.calls, 'myScores'), 2, 'sau khi tạo lịch bận phải chấm lại');
    await env.vm.commands.takeShift(10);
    assert.equal(count(env.calls, 'myScores'), 3, 'sau khi nhận ca phải chấm lại');
});

test('Chợ ca: cache hết hạn sau 10 phút', async () => {
    const env = fakeEnv();
    await env.vm.commands.init();
    await env.vm.commands.navigate('shift-market');
    env.advance(11 * 60000);
    await env.vm.commands.reloadMarket();
    assert.equal(count(env.calls, 'myScores'), 2);
});

test('Chợ ca: AI/điểm lỗi → trạng thái error có thông điệp, KHÔNG có điểm giả; danh sách ca vẫn hiện (B2, E8)', async () => {
    const base = fakeEnv();
    base.api.shifts.myScores = async () => { throw new ApiError('Dịch vụ AI không khả dụng', 503); };
    await base.vm.commands.init();
    await base.vm.commands.navigate('shift-market');
    const s = base.vm.store.state;
    assert.equal(s.marketShifts.length, 2);
    assert.equal(s.market.status, 'error');
    assert.match(s.market.message, /AI không khả dụng/);
    assert.deepEqual(s.market.scores, {});
    // lỗi không được cache: lần sau thử lại
    base.api.shifts.myScores = async (ids) => ({ available: true, scores: ids.map(id => ({ shiftId: id, eligible: true, matchScore: 60 })) });
    await base.vm.commands.reloadMarket();
    assert.equal(base.vm.store.state.market.status, 'ok');
});

test('Chợ ca: 429 hiện thông báo thân thiện', async () => {
    const env = fakeEnv();
    env.api.shifts.myScores = async () => { throw new ApiError('Too many', 429); };
    await env.vm.commands.init();
    await env.vm.commands.navigate('shift-market');
    assert.match(env.vm.store.state.market.message, /quá nhanh/);
});

test('Chợ ca: ca không đủ điều kiện được lưu lại cùng lý do', async () => {
    const env = fakeEnv();
    env.api.shifts.myScores = async (ids) => ({ available: true, scores: ids.map(id => ({ shiftId: id, eligible: false, reason: 'Trùng lịch bận' })) });
    await env.vm.commands.init();
    await env.vm.commands.navigate('shift-market');
    assert.equal(env.vm.store.state.market.scores[10].eligible, false);
    assert.equal(env.vm.store.state.market.scores[10].reason, 'Trùng lịch bận');
});

test('Chợ ca: kết quả của lần tải cũ bị bỏ khi đã có lần tải mới (không ghi đè dữ liệu mới)', async () => {
    const env = fakeEnv();
    let release;
    env.api.shifts.myScores = (ids) => new Promise((res) => { release = () => res({ available: true, scores: ids.map(id => ({ shiftId: id, eligible: true, matchScore: 11 })) }); });
    await env.vm.commands.init();
    const first = env.vm.commands.reloadMarket();
    await new Promise(r => setImmediate(r));
    const releaseFirst = release;
    env.api.shifts.myScores = async (ids) => ({ available: true, scores: ids.map(id => ({ shiftId: id, eligible: true, matchScore: 99 })) });
    await env.vm.commands.reloadMarket();
    releaseFirst();
    await first;
    assert.equal(env.vm.store.state.market.scores[10].score, 99);
});

test('Gợi ý AI: kết quả dự phòng KHÔNG được cache, kết quả Gemini thuần thì có (B3)', async () => {
    const env = fakeEnv({ user: ADMIN });
    env.api.shifts.listAll = async () => [openShift(20)];
    await env.vm.commands.init();
    env.api.shifts.candidates = async () => ({ available: true, fallback: true, ranked_candidates: [] });
    await env.vm.commands.showCandidates(20);
    assert.deepEqual(env.vm.store.state.aiCachedIds, []);
    let n = 0;
    env.api.shifts.candidates = async () => { n++; return { available: true, fallback: false, ranked_candidates: [] }; };
    await env.vm.commands.showCandidates(20);
    await env.vm.commands.showCandidates(20);
    assert.equal(n, 1, 'lần hai phải lấy từ cache');
    assert.deepEqual(env.vm.store.state.aiCachedIds, [20]);
    await env.vm.commands.showCandidates(20, { force: true });
    assert.equal(n, 2, 'force bỏ cache');
});

test('Gợi ý AI: AI hỏng → không cache, không có điểm; 429 thân thiện', async () => {
    const env = fakeEnv({ user: ADMIN });
    await env.vm.commands.init();
    env.api.shifts.candidates = async () => ({ available: false, message: 'AI tạm ngưng', error_code: 'AI_DOWN' });
    await env.vm.commands.showCandidates(20);
    assert.deepEqual(env.vm.store.state.aiCachedIds, []);
    assert.equal(env.vm.store.state.candidates.data.available, false);
    env.api.shifts.candidates = async () => { throw new ApiError('x', 429); };
    await env.vm.commands.showCandidates(20, { force: true });
    assert.match(env.vm.store.state.candidates.error, /quá nhanh/);
});

test('Gán ca từ gợi ý dự phòng: hộp xác nhận cảnh báo điểm không do Gemini chấm', async () => {
    const env = fakeEnv({ user: ADMIN });
    await env.vm.commands.init();
    env.api.shifts.candidates = async () => ({ available: true, fallback: true, ranked_candidates: [{ employee_id: 5, full_name: 'An', match_score: 80 }] });
    await env.vm.commands.showCandidates(20);
    await env.vm.commands.assignCandidate(20, 5);
    assert.match(env.confirms.at(-1), /dự phòng/);
    assert.equal(count(env.calls, 'assign'), 1);
});

test('Hủy xác nhận thì KHÔNG gọi API (nhả ca / nhận ca / ghi nhận muộn)', async () => {
    const env = fakeEnv({ confirmAnswer: false });
    await env.vm.commands.init();
    await env.vm.commands.takeShift(1);
    await env.vm.commands.releaseShift(1);
    await env.vm.commands.markLate(1);
    assert.equal(count(env.calls, 'take') + count(env.calls, 'release') + count(env.calls, 'markLate'), 0);
});

test('Bấm đúp nhận ca chỉ gửi MỘT request', async () => {
    const env = fakeEnv();
    let resolve;
    env.api.shifts.take = (id) => { env.calls.push(['take', id]); return new Promise(r => { resolve = r; }); };
    await env.vm.commands.init();
    const a = env.vm.commands.takeShift(10);
    const b = env.vm.commands.takeShift(10);
    await new Promise(r => setImmediate(r));
    await new Promise(r => setImmediate(r));
    resolve({ message: 'ok' });
    await Promise.all([a, b]);
    assert.equal(count(env.calls, 'take'), 1);
});

test('Lỗi tương tranh 409 khi nhận ca được hiển thị và danh sách được tải lại', async () => {
    const env = fakeEnv();
    env.api.shifts.take = async () => { throw new ApiError('Ca này vừa được người khác nhận', 409); };
    await env.vm.commands.init();
    await env.vm.commands.navigate('shift-market');
    const before = count(env.calls, 'listOpen');
    await env.vm.commands.takeShift(10);
    assert.ok(env.toasts.some(t => t.type === 'error' && /vừa được/.test(t.msg)));
    assert.ok(count(env.calls, 'listOpen') > before);
});

test('Sửa ca gửi version (khóa lạc quan); 409 thì tải lại bản mới nhất', async () => {
    const env = fakeEnv({ user: ADMIN });
    env.api.shifts.listAll = async () => { env.calls.push(['listAll']); return [openShift(30, { version: 3 })]; };
    await env.vm.commands.init();
    let payload;
    env.api.shifts.update = async (id, p) => { payload = p; throw new ApiError('Ca đã bị người khác sửa', 409); };
    const before = count(env.calls, 'listAll');
    const ok = await env.vm.commands.updateShift(30, { title: 'x', startTime: '2026-11-06T07:00', endTime: '2026-11-06T15:00', branchId: '1', version: '3' });
    assert.equal(ok, false);
    assert.equal(payload.version, 3);
    assert.ok(count(env.calls, 'listAll') > before);
});

test('Lịch bận: kiểm tra đầu vào ở client (ngày kết thúc, giờ)', async () => {
    const env = fakeEnv();
    await env.vm.commands.init();
    assert.equal(await env.vm.commands.createBusy({ date: '', start: '08:00', end: '09:00' }), false);
    assert.equal(await env.vm.commands.createBusy({ date: '2026-11-06', start: '', end: '09:00' }), false);
    assert.equal(await env.vm.commands.createBusy({ date: '2026-11-06', endDate: '2026-11-05', start: '08:00', end: '09:00' }), false);
    assert.equal(await env.vm.commands.createBusy({ date: '2026-11-06', start: '09:00', end: '08:00' }), false);
    assert.equal(count(env.calls, 'busyCreate'), 0);
    assert.equal(await env.vm.commands.createBusy({ date: '2026-11-06', endDate: '2026-11-07', start: '22:00', end: '06:00' }), true);
    assert.equal(count(env.calls, 'busyCreate'), 1);
});

test('Đổi mật khẩu: kiểm tra khớp ở client rồi gọi API', async () => {
    const env = fakeEnv();
    await env.vm.commands.init();
    assert.equal(await env.vm.commands.changePassword({ current: 'a', next: 'b', confirm: 'c' }), false);
    assert.equal(await env.vm.commands.changePassword({ current: '', next: 'b', confirm: 'b' }), false);
    assert.equal(count(env.calls, 'changePassword'), 0);
    assert.equal(await env.vm.commands.changePassword({ current: 'Old12345', next: 'New12345', confirm: 'New12345' }), true);
    assert.equal(count(env.calls, 'changePassword'), 1);
});

test('Thông báo lấy từ server; chỉ toast cho thông báo MỚI chưa đọc, lần tải đầu không toast (D4)', async () => {
    const env = fakeEnv();
    let list = [{ id: 1, message: 'Cũ', type: 'info', read: false, createdAt: '2026-11-02T09:00:00' }];
    env.api.notifications.list = async () => list;
    await env.vm.commands.init();
    assert.equal(env.toasts.length, 0);
    list = [...list, { id: 2, message: 'Mới', type: 'success', read: false, createdAt: '2026-11-02T10:00:00' }];
    await env.vm.commands.loadNotifications();
    assert.deepEqual(env.toasts.map(t => t.msg), ['Mới']);
    await env.vm.commands.loadNotifications();
    assert.equal(env.toasts.length, 1, 'không toast lặp lại');
    await env.vm.commands.markAllNotificationsRead();
    assert.ok(env.vm.store.state.notifications.every(n => n.read));
    assert.equal(count(env.calls, 'markAll'), 1);
});

test('Bộ lọc admin: đổi khoảng ngày gọi lại server và về trang 0; đổi trang giữ bộ lọc', async () => {
    const env = fakeEnv({ user: ADMIN });
    env.api.shifts.listAll = async (q) => { env.calls.push(['listAll', q]); return Array.from({ length: 50 }, (_, i) => openShift(i + 1)); };
    await env.vm.commands.init();
    assert.equal(env.vm.store.state.adminHasMore, true);
    await env.vm.commands.adminPage(1);
    assert.equal(env.calls.at(-1)[1].page, 1);
    await env.vm.commands.setAdminFilter({ from: '2026-11-01' });
    const q = env.calls.at(-1)[1];
    assert.equal(q.page, 0);
    assert.equal(q.from, '2026-11-01T00:00:00');
    const n = count(env.calls, 'listAll');
    await env.vm.commands.setAdminFilter({ status: 'OPEN' });
    assert.equal(count(env.calls, 'listAll'), n, 'lọc theo trạng thái chỉ ở client');
    await env.vm.commands.adminPage(-1);
    assert.equal(env.vm.store.state.adminFilter.page, 0);
});

test('Nhân viên: tạo mới gửi email/mật khẩu, cập nhật gửi active; kỹ năng chuyển thành số', async () => {
    const env = fakeEnv({ user: ADMIN });
    await env.vm.commands.init();
    let created, updated;
    env.api.employees.create = async (p) => { created = p; return {}; };
    env.api.employees.update = async (id, p) => { updated = { id, p }; return {}; };
    await env.vm.commands.saveEmployee({ fullName: ' An ', email: 'an@x.com', password: 'Abcd1234', maxHoursPerWeek: '40', skills: [{ skillId: '1', level: '3' }] });
    assert.equal(created.fullName, 'An');
    assert.equal(created.maxHoursPerWeek, 40);
    assert.deepEqual(created.skills, [{ skillId: 1, level: 3 }]);
    await env.vm.commands.saveEmployee({ fullName: 'An', active: false, maxHoursPerWeek: '40', skills: [] }, 7);
    assert.equal(updated.id, 7);
    assert.equal(updated.p.active, false);
    assert.ok(!('password' in updated.p) && !('email' in updated.p));
});

test('Đăng xuất luôn quay về trang đăng nhập, kể cả khi API lỗi', async () => {
    const env = fakeEnv();
    env.api.auth.logout = async () => { throw new ApiError('x', 500); };
    await env.vm.commands.init();
    await env.vm.commands.logout();
    assert.deepEqual(env.calls.at(-1), ['redirect', '/']);
});
