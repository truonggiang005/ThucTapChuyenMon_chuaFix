import test from 'node:test';
import assert from 'node:assert/strict';
import { load } from './_paths.mjs';

const { createApi, ApiError, query } = await load('model/api.js');

const resp = (status, body) => ({ ok: status >= 200 && status < 300, status, text: async () => (body === undefined ? '' : JSON.stringify(body)) });

test('query bỏ giá trị rỗng và mã hóa tham số', () => {
    assert.equal(query({ a: 1, b: '', c: null, d: undefined }), '?a=1');
    assert.equal(query({ q: 'a b&c' }), '?q=a%20b%26c');
    assert.equal(query({ ids: [1, 2, 3] }), '?ids=1,2,3');
    assert.equal(query({ ids: [] }), '');
    assert.equal(query({}), '');
});

test('GET không gửi CSRF; POST/PUT/DELETE gửi X-XSRF-TOKEN từ cookie', async () => {
    const calls = [];
    const api = createApi({ fetchFn: async (url, opt) => { calls.push({ url, opt }); return resp(200, {}); }, getCookie: () => 'tok123' });
    await api.shifts.listOpen(1);
    await api.shifts.take(5);
    await api.busy.remove(9);
    assert.equal(calls[0].opt.headers['X-XSRF-TOKEN'], undefined);
    assert.equal(calls[1].opt.headers['X-XSRF-TOKEN'], 'tok123');
    assert.equal(calls[2].opt.headers['X-XSRF-TOKEN'], 'tok123');
    assert.equal(calls[0].url, '/api/shifts/open?branchId=1');
    assert.equal(calls[1].opt.method, 'POST');
    assert.equal(calls[2].opt.method, 'DELETE');
    assert.equal(calls[1].opt.credentials, 'same-origin');
});

test('id đưa vào đường dẫn được mã hóa (không chèn được đường dẫn khác)', async () => {
    let seen;
    const api = createApi({ fetchFn: async (url) => { seen = url; return resp(200, {}); }, getCookie: () => null });
    await api.shifts.take('1/../../auth/logout');
    assert.equal(seen, '/api/shifts/1%2F..%2F..%2Fauth%2Flogout/take');
});

test('lỗi HTTP → ApiError mang message của server và status (409 tương tranh)', async () => {
    const api = createApi({ fetchFn: async () => resp(409, { message: 'Ca này vừa được người khác nhận' }), getCookie: () => null });
    await assert.rejects(api.shifts.take(1), (e) => e instanceof ApiError && e.status === 409 && /vừa được/.test(e.message));
});

test('401 gọi onUnauthorized, trừ các lệnh đăng nhập/me/logout', async () => {
    let n = 0;
    const api = createApi({ fetchFn: async () => resp(401, { message: 'x' }), getCookie: () => null, onUnauthorized: () => { n++; } });
    await assert.rejects(api.shifts.listOpen());
    assert.equal(n, 1);
    await assert.rejects(api.auth.login('a@b.c', 'x'));
    await assert.rejects(api.auth.me());
    assert.equal(n, 1);
});

test('mất kết nối và hết thời hạn đều cho thông báo tiếng Việt rõ ràng, không treo vô hạn', async () => {
    const down = createApi({ fetchFn: async () => { throw new TypeError('Failed to fetch'); }, getCookie: () => null });
    await assert.rejects(down.shifts.listOpen(), /Không thể kết nối/);
    const slow = createApi({
        timeoutMs: 20,
        getCookie: () => null,
        fetchFn: (url, opt) => new Promise((_, reject) => {
            opt.signal.addEventListener('abort', () => { const e = new Error('aborted'); e.name = 'AbortError'; reject(e); });
        }),
    });
    await assert.rejects(slow.shifts.listOpen(), /quá chậm/);
});

test('phản hồi không phải JSON không làm vỡ client', async () => {
    const api = createApi({ fetchFn: async () => ({ ok: false, status: 502, text: async () => '<html>Bad gateway</html>' }), getCookie: () => null });
    await assert.rejects(api.shifts.listOpen(), (e) => e.status === 502 && /HTTP 502/.test(e.message));
});

test('myScores gom nhiều ca vào MỘT request (B4)', async () => {
    const calls = [];
    const api = createApi({ fetchFn: async (url) => { calls.push(url); return resp(200, { scores: [] }); }, getCookie: () => null });
    await api.shifts.myScores([1, 2, 3, 4]);
    assert.equal(calls.length, 1);
    assert.equal(calls[0], '/api/shifts/my-scores?shiftIds=1,2,3,4');
});
