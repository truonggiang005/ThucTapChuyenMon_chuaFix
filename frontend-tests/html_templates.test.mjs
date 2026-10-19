import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { load, JS_ROOT, RESOURCES } from './_paths.mjs';

const { html, raw, esc, setHtml, SafeHtml } = await load('view/html.js');
const T = await load('view/templates.js');

const XSS = '<img src=x onerror=alert(1)>"\'`&';

test('html`` escape mọi giá trị chèn vào', () => {
    const out = String(html`<p title="${XSS}">${XSS}</p>`);
    assert.ok(!out.includes('<img'));
    assert.ok(!out.includes('onerror=alert') || out.includes('&lt;img'));
    assert.match(out, /&lt;img src=x onerror=alert\(1\)&gt;&quot;&#39;&#96;&amp;/);
});

test('html`` lồng nhau không bị escape hai lần; mảng được nối; false/null bị bỏ', () => {
    const inner = html`<b>${'a&b'}</b>`;
    assert.equal(String(html`<i>${inner}</i>`), '<i><b>a&amp;b</b></i>');
    assert.equal(String(html`${[inner, inner]}`), '<b>a&amp;b</b><b>a&amp;b</b>');
    assert.equal(String(html`[${false}${null}${undefined}]`), '[]');
});

test('setHtml từ chối chuỗi thường', () => {
    const el = { innerHTML: '' };
    assert.throws(() => setHtml(el, '<script>'), TypeError);
    setHtml(el, html`<p>${'x'}</p>`);
    assert.equal(el.innerHTML, '<p>x</p>');
});

const evil = {
    id: 7, title: XSS, status: 'OPEN', branchName: XSS, requiredSkillName: XSS, requiredLevel: 3, assignedToName: XSS,
    startTime: '2026-11-02T07:00:00', endTime: '2026-11-02T15:00:00', lateMarked: false,
};

function assertNoRawInjection(out) {
    const s = String(out);
    assert.ok(!/<img\b/i.test(s), 'có thẻ <img> chưa escape');
    assert.ok(!/\sstyle\s*=/i.test(s), 'có thuộc tính style inline (vi phạm CSP style-src)');
    assert.ok(!/\son[a-z]+\s*=\s*["']/i.test(s.replace(/&lt;[^]*?&gt;/g, '')), 'có handler on*= inline');
}

test('mọi template escape dữ liệu từ API và không sinh style inline', () => {
    const market = { status: 'ok', message: null, scores: { 7: { eligible: true, reason: null, score: 88, confidence: 70, source: 'heuristic' } } };
    assertNoRawInjection(T.weeklySchedule([evil], { nowIso: '2026-11-01T00:00:00', todayKey: '2026-11-02' }));
    assertNoRawInjection(T.marketGrid([evil], market));
    assertNoRawInjection(T.adminShiftsGrid([evil], { cachedIds: new Set([7]), nowIso: '2026-11-01T00:00:00' }));
    assertNoRawInjection(T.busyEntries([{ id: 1, reason: XSS, startTime: '2026-11-02T07:00:00', endTime: '2026-11-02T09:00:00' }]));
    assertNoRawInjection(T.notificationList([{ id: 1, message: XSS, type: 'info', time: '2026-11-02T07:00:00', read: false }]));
    assertNoRawInjection(T.candidatesPanel({
        open: true, shiftId: 7, loading: false, error: null, cachedAt: null,
        data: { total_candidates: 1, source: 'gemini', fallback: true, message: XSS, fallback_reason: XSS,
            ranked_candidates: [{ employee_id: 5, full_name: XSS, match_score: 91, confidence: 70, score_low: 80, score_high: 95, source: XSS,
                breakdown: { reliability: 90, skill_fit: 80, time_fit: 70, workload_fit: 60, location_fit: 100, explanation: XSS } }] },
    }));
    assertNoRawInjection(T.employeesTable([{ id: 1, fullName: XSS, email: XSS, role: 'EMPLOYEE', active: true, primaryBranchId: 1, skills: [{ skillId: 1, skillName: XSS, level: 3 }] }], new Map([[1, { name: XSS }]])));
    assertNoRawInjection(T.branchList([{ id: 1, name: XSS, address: XSS, maxCapacity: 3 }]));
    assertNoRawInjection(T.skillList([{ id: 1, name: XSS }]));
    assertNoRawInjection(T.options('-- chọn --', [{ id: 1, name: XSS }]));
});

test('Chợ ca: ca không đủ điều kiện thì nút nhận bị khóa và hiện lý do', () => {
    const market = { status: 'ok', message: null, scores: { 7: { eligible: false, reason: 'Trùng lịch bận', score: null } } };
    const out = String(T.marketGrid([evil], market));
    assert.match(out, /Trùng lịch bận/);
    assert.match(out, /data-action="take"[^>]*disabled/);
});

test('Chợ ca: chưa có điểm thì KHÔNG hiện điểm giả; đang tải thì hiện spinner', () => {
    const noScore = String(T.marketGrid([evil], { status: 'ok', message: null, scores: {} }));
    assert.ok(!/Điểm phù hợp \(ước tính\)/.test(noScore));
    assert.match(noScore, /Chưa có điểm gợi ý/);
    const loading = String(T.marketGrid([evil], { status: 'loading', message: null, scores: {} }));
    assert.match(loading, /Đang tính điểm/);
});

test('Bảng gợi ý AI: dự phòng được GẮN NHÃN rõ; AI hỏng thì không có điểm nào (B2)', () => {
    const base = { open: true, shiftId: 7, loading: false, error: null, cachedAt: null };
    const cand = { employee_id: 5, full_name: 'An', match_score: 77, source: 'heuristic', breakdown: { explanation: 'ok' } };
    const fallback = String(T.candidatesPanel({ ...base, data: { ranked_candidates: [cand], fallback: true, fallback_reason: 'GEMINI_ERROR', source: 'heuristic' } }));
    assert.match(fallback, /notice--warning/);
    assert.match(fallback, /GEMINI_ERROR/);
    assert.match(fallback, /Quy tắc/);
    const down = String(T.candidatesPanel({ ...base, data: { available: false, message: 'AI tạm ngưng', error_code: 'AI_DOWN' } }));
    assert.match(down, /notice--error/);
    assert.ok(!/candidate-row__score/.test(down));
    assert.ok(!/data-action="assign-candidate"/.test(down));
});

test('Lịch cá nhân: ca đã bắt đầu / ca bắt buộc không có nút Nhả (A5)', () => {
    const started = { ...evil, id: 1, status: 'ASSIGNED', startTime: '2026-11-02T07:00:00', endTime: '2026-11-02T15:00:00' };
    const future = { ...started, id: 2, startTime: '2026-11-05T07:00:00', endTime: '2026-11-05T15:00:00' };
    const force = { ...future, id: 3, status: 'FORCE_ASSIGNED' };
    const out = String(T.weeklySchedule([started, future, force], { nowIso: '2026-11-02T10:00:00', todayKey: '2026-11-02' }));
    assert.ok(!/data-action="release" data-id="1"/.test(out), 'ca đã bắt đầu vẫn có nút nhả');
    assert.match(out, /data-action="release" data-id="2"/);
    assert.ok(!/data-action="release" data-id="3"/.test(out), 'ca gán bắt buộc vẫn có nút nhả');
    assert.match(out, /Hôm nay/);
});

test('Admin: ca đã bắt đầu không được sửa / gợi ý, nhưng ghi nhận muộn được', () => {
    const started = { ...evil, id: 1, status: 'ASSIGNED', assignedToId: 5, startTime: '2026-11-02T07:00:00' };
    const out = String(T.adminShiftsGrid([started], { nowIso: '2026-11-02T10:00:00' }));
    assert.ok(!/data-action="edit-shift"/.test(out));
    assert.ok(!/data-action="show-candidates"/.test(out));
    assert.match(out, /data-action="mark-late"/);
    const completed = String(T.adminShiftsGrid([{ ...started, status: 'COMPLETED' }], { nowIso: '2026-11-02T10:00:00' }));
    assert.ok(!/data-action="edit-shift"/.test(completed));
});

test('Phân trang admin', () => {
    const first = String(T.adminPager({ page: 0, hasMore: true, count: 50 }));
    assert.match(first, /data-action="admin-prev"[^>]*disabled/);
    assert.ok(!/data-action="admin-next"[^>]*disabled/.test(first));
});

test('Không file giao diện nào còn style inline, onclick inline hoặc tải tài nguyên ngoài (CSP, C3)', () => {
    const files = [];
    const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).forEach(e => {
        const p = path.join(d, e.name);
        if (e.isDirectory()) walk(p); else files.push(p);
    });
    walk(JS_ROOT);
    walk(path.join(RESOURCES, 'templates'));
    walk(path.join(RESOURCES, 'static/css'));
    for (const f of files) {
        let src = fs.readFileSync(f, 'utf8');
        // Bỏ chú thích (các chú thích có nhắc tới style="..." để giải thích quy tắc, không phải mã)
        if (f.endsWith('.js')) src = src.replace(/\/\*[^]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
        if (f.endsWith('.html')) {
            src = src.replace(/<!--[^]*?-->/g, '');
            assert.ok(!/\sstyle\s*=\s*["']/.test(src), `${f}: còn style= inline`);
            assert.ok(!/\son(click|change|submit|input|load)\s*=/.test(src), `${f}: còn handler inline`);
            assert.ok(!/<script(?![^>]*\bsrc=)[^>]*>[^<]/.test(src), `${f}: còn script inline`);
            assert.ok(!/https?:\/\/(?!www\.(?:w3|thymeleaf)\.org)/.test(src), `${f}: tải tài nguyên ngoài`);
        }
        if (f.endsWith('.css')) {
            assert.ok(!/fonts\.googleapis|@import\s+url\(\s*['"]?https?:/.test(src), `${f}: import tài nguyên ngoài`);
        }
        if (f.endsWith('.js')) {
            assert.ok(!/\.style\.display\s*=/.test(src), `${f}: dùng style.display (thay bằng thuộc tính hidden)`);
            assert.ok(!/\sstyle\s*=\s*"/.test(src), `${f}: còn style= trong template`);
            assert.ok(!/\bonclick\s*=/.test(src), `${f}: còn onclick inline`);
        }
    }
});
