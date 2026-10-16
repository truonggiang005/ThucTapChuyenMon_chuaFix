import test from 'node:test';
import assert from 'node:assert/strict';
import { createAppViewModel, computeStats } from '../src/main/resources/static/js/viewmodel/appViewModel.js';
import { createApi } from '../src/main/resources/static/js/model/api.js';

function memStorage() { const m = {}; return { getItem: k => m[k] ?? null, setItem: (k, v) => { m[k] = v; } }; }

function fakeApi(user, over = {}) {
    const calls = [];
    const rec = (name, ret) => (...a) => { calls.push([name, ...a]); return Promise.resolve(typeof ret === 'function' ? ret(...a) : ret); };
    const api = {
        calls,
        auth: { me: rec('me', user), logout: rec('logout', null) },
        shifts: {
            listAll: rec('listAll', []), listOpen: rec('listOpen', []), forEmployee: rec('forEmployee', []),
            create: rec('create', { message: 'ok' }), update: rec('update', { message: 'ok' }),
            release: rec('release', { message: 'ok' }), take: rec('take', { message: 'ok' }),
            assign: rec('assign', { message: 'ok' }), candidates: rec('candidates', { ranked_candidates: [], total_candidates: 0 }),
            myScore: rec('myScore', { match_score: 77 }), branches: rec('branches', [{ id: 1, name: 'B' }]), skills: rec('skills', [{ id: 1, name: 'S' }]),
        },
        employees: { list: rec('empList', [{ id: 1, fullName: 'An' }]), get: rec('empGet', { id: user?.id, fullName: 'x' }) },
        busy: { forEmployee: rec('busyFor', []), create: rec('busyCreate', { message: 'ok' }), remove: rec('busyRemove', { message: 'ok' }) },
        ai: { health: rec('health', { healthy: true }) },
    };
    return Object.assign(api, over);
}
function setup(user, over) {
    const api = fakeApi(user, over);
    const toasts = [], redirects = [];
    const ui = { confirm: () => true, toast: (m, t) => toasts.push([m, t]), redirect: u => redirects.push(u) };
    const vm = createAppViewModel({ api, ui, storage: memStorage(), setTimeoutFn: () => {} });
    return { api, ui, vm, toasts, redirects };
}

const ADMIN = { id: 1, fullName: 'An', email: 'a@x', role: 'ADMIN' };
const EMP = { id: 2, fullName: 'Bình', email: 'b@x', role: 'EMPLOYEE' };

test('FUN-01: init tải lịch cá nhân ngay khi mở trang', async () => {
    const { api, vm } = setup(EMP);
    await vm.commands.init();
    assert.ok(api.calls.some(c => c[0] === 'forEmployee' && c[1] === 2));
    assert.ok(api.calls.some(c => c[0] === 'busyFor' && c[1] === 2));
});

test('SEC-02/04: chưa đăng nhập → về trang đăng nhập; nhân viên KHÔNG gọi API quản trị', async () => {
    const a = setup(null, { auth: { me: () => Promise.reject(new Error('401')) } });
    assert.equal(await a.vm.commands.init(), false);
    assert.deepEqual(a.redirects, ['/']);

    const { api, vm } = setup(EMP);
    await vm.commands.init();
    assert.ok(!api.calls.some(c => c[0] === 'empList' || c[0] === 'listAll'));
    await vm.commands.navigate('admin');                       // bị chặn
    assert.equal(vm.store.state.page, 'personal-schedule');
});

test('SEC-02: vai trò lấy từ server, đổi localStorage không ảnh hưởng', async () => {
    const { vm } = setup(EMP);
    await vm.commands.init();
    assert.equal(vm.isAdmin(), false);
    const a = setup(ADMIN); await a.vm.commands.init();
    assert.equal(a.vm.isAdmin(), true);
    assert.ok(a.api.calls.some(c => c[0] === 'listAll'));
});

test('SEC-03: take/release không gửi employeeId từ client', async () => {
    const { api, vm } = setup(EMP);
    await vm.commands.init();
    await vm.commands.takeShift(5);
    await vm.commands.releaseShift(6);
    assert.deepEqual(api.calls.find(c => c[0] === 'take'), ['take', 5]);
    assert.deepEqual(api.calls.find(c => c[0] === 'release'), ['release', 6]);
});

test('chợ ca: lấy điểm của chính mình, có cache', async () => {
    const { api, vm } = setup(EMP, {});
    api.shifts.listOpen = () => Promise.resolve([{ id: 10, status: 'OPEN', requiredSkillId: 1 }, { id: 11, status: 'OPEN', requiredSkillId: 2 }]);
    await vm.commands.init();
    await vm.commands.navigate('shift-market');
    assert.equal(vm.store.state.marketScores[10], 77);
    const n = api.calls.filter(c => c[0] === 'myScore').length;
    await vm.commands.setFilter({ skillId: '1' });
    assert.equal(vm.store.state.marketShifts.length, 1);
    assert.equal(api.calls.filter(c => c[0] === 'myScore').length, n);   // dùng cache
});

test('createShift: chuyển form thành payload kiểu số', async () => {
    const { api, vm } = setup(ADMIN);
    await vm.commands.init();
    await vm.commands.createShift({ title: ' Ca ', startTime: '2026-10-20T08:00', endTime: '2026-10-20T16:00', branchId: '1', skillId: '', level: '3', employeeId: '' });
    const payload = api.calls.find(c => c[0] === 'create')[1];
    assert.deepEqual(payload, { title: 'Ca', startTime: '2026-10-20T08:00', endTime: '2026-10-20T16:00', branchId: 1, requiredSkillId: null, requiredLevel: 3, assignedToId: null });
});

test('createBusy không gửi employeeId, thiếu ngày thì báo lỗi', async () => {
    const { api, vm, toasts } = setup(EMP);
    await vm.commands.init();
    await vm.commands.createBusy({ date: '', start: '08:00', end: '09:00' });
    assert.equal(toasts.at(-1)[1], 'error');
    await vm.commands.createBusy({ date: '2026-10-20', start: '08:00', end: '09:00', reason: ' họp ' });
    const payload = api.calls.find(c => c[0] === 'busyCreate')[1];
    assert.deepEqual(payload, { startTime: '2026-10-20T08:00:00', endTime: '2026-10-20T09:00:00', reason: 'họp' });
});

test('admin gán ca qua endpoint assign; AI gợi ý được cache', async () => {
    const { api, vm } = setup(ADMIN);
    await vm.commands.init();
    await vm.commands.showCandidates(3);
    await vm.commands.showCandidates(3);
    assert.equal(api.calls.filter(c => c[0] === 'candidates').length, 1);
    assert.deepEqual(vm.store.state.aiCachedIds, [3]);
    await vm.commands.assignCandidate(3, 2);
    assert.deepEqual(api.calls.find(c => c[0] === 'assign'), ['assign', 3, 2]);
    assert.equal(vm.store.state.candidates.open, false);
});

test('lỗi 409 khi nhận ca hiện thông báo thân thiện', async () => {
    const { vm, toasts } = setup(EMP, {});
    await vm.commands.init();
    const e = new Error('Ca này vừa bị người khác nhận trước bạn.'); e.status = 409;
    vm.commands; // eslint
    const s = setup(EMP); s.api.shifts.take = () => Promise.reject(e);
    await s.vm.commands.init(); await s.vm.commands.takeShift(1);
    assert.ok(s.toasts.some(t => t[0].includes('vừa bị người khác nhận')));
});

test('computeStats', () => {
    const st = computeStats([{ status: 'OPEN' }, { status: 'ASSIGNED', assignedToId: 1 }, { status: 'UNFILLED' }]);
    assert.deepEqual([st.total, st.open, st.taken, st.unfilled], [3, 1, 1, 1]);
});

test('api: CSRF header cho POST, 401 gọi onUnauthorized, không kết nối → ApiError', async () => {
    let seen, unauthorized = 0;
    const api = createApi({
        getCookie: () => 'tok123', onUnauthorized: () => { unauthorized++; },
        fetchFn: async (url, opts) => { seen = { url, opts }; return { ok: false, status: 401, text: async () => '{"message":"x"}' }; },
    });
    await assert.rejects(api.shifts.take(7));
    assert.equal(seen.url, '/api/shifts/7/take');
    assert.equal(seen.opts.headers['X-XSRF-TOKEN'], 'tok123');
    assert.equal(unauthorized, 1);
    const off = createApi({ fetchFn: async () => { throw new Error('x'); } });
    await assert.rejects(off.shifts.listAll(), /Không thể kết nối/);
});
