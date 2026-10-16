import test from 'node:test';
import assert from 'node:assert/strict';
import { html, raw, esc, setHtml } from '../src/main/resources/static/js/view/html.js';
import * as T from '../src/main/resources/static/js/view/templates.js';

const PAYLOAD = '<img src=x onerror="alert(1)">';

test('html`` escape giá trị, không escape SafeHtml lồng nhau', () => {
    assert.equal(html`<b>${PAYLOAD}</b>`.toString(), '<b>&lt;img src=x onerror=&quot;alert(1)&quot;&gt;</b>');
    assert.equal(html`<i>${html`<b>${'a&b'}</b>`}</i>`.toString(), '<i><b>a&amp;b</b></i>');
    assert.equal(html`${[1, 2].map(n => html`<p>${n}</p>`)}`.toString(), '<p>1</p><p>2</p>');
});

test('setHtml từ chối chuỗi thường', () => {
    assert.throws(() => setHtml({ innerHTML: '' }, '<b>x</b>'), TypeError);
});

test('SEC-05: 4 vector XSS (tiêu đề ca, lý do lịch bận, nhận xét AI, tên ứng viên) đều bị escape', () => {
    const shift = { id: 1, title: PAYLOAD, status: 'OPEN', startTime: '2026-10-20T08:00', endTime: '2026-10-20T16:00', requiredLevel: 1, branchName: PAYLOAD };
    const grid = T.adminShiftsGrid([shift], { currentUserId: 1, isAdmin: true, cachedIds: new Set() }).toString();
    assert.ok(!grid.includes('<img'), grid);

    const busy = T.busyEntries([{ id: 9, reason: PAYLOAD, startTime: '2026-10-20T08:00', endTime: '2026-10-20T09:00' }]).toString();
    assert.ok(!busy.includes('<img'), busy);

    const panel = T.candidatesPanel({ open: true, shiftId: 1, loading: false, error: null, cachedAt: null,
        data: { total_candidates: 1, ranked_candidates: [{ employee_id: 2, full_name: PAYLOAD, match_score: 90,
            breakdown: { explanation: PAYLOAD, reliability: PAYLOAD } }] } }).toString();
    assert.ok(!panel.includes('<img'), panel);

    const err = T.candidatesPanel({ open: true, shiftId: 1, loading: false, error: PAYLOAD, data: null }).toString();
    assert.ok(!err.includes('<img'), err);

    const notif = T.notificationList([{ id: 1, message: PAYLOAD, type: 'info', read: false, time: new Date().toISOString() }]).toString();
    assert.ok(!notif.includes('<img'), notif);

    const acct = T.accountInfo({ fullName: PAYLOAD, email: PAYLOAD }).toString();
    assert.ok(!acct.includes('<img'), acct);

    const opts = T.options('x', [{ id: 1, fullName: PAYLOAD }], 'fullName').toString();
    assert.ok(!opts.includes('<img'), opts);
});

test('Không còn onclick inline trong template (tương thích CSP)', () => {
    const shift = { id: 1, title: 't', status: 'OPEN', startTime: '2026-10-20T08:00', endTime: '2026-10-20T16:00', requiredLevel: 1 };
    const out = [
        T.adminShiftsGrid([shift], { currentUserId: 1, isAdmin: true, cachedIds: new Set() }),
        T.marketGrid([shift], { 1: 80 }),
        T.weeklySchedule([{ ...shift, status: 'ASSIGNED' }]),
    ].map(String).join('');
    assert.ok(!/onclick=/i.test(out));
    assert.ok(out.includes('data-action="take"'));
});

test('điểm AI độc hại không thoát khỏi thuộc tính style', () => {
    const out = T.candidatesPanel({ open: true, shiftId: 1, loading: false, error: null, cachedAt: null,
        data: { total_candidates: 1, ranked_candidates: [{ employee_id: 2, full_name: 'a', match_score: '90;background:url(x)', breakdown: {} }] } }).toString();
    assert.ok(!out.includes('url(x)'));
});
