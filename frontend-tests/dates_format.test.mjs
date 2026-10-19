import test from 'node:test';
import assert from 'node:assert/strict';
import { load } from './_paths.mjs';

const dates = await load('model/dates.js');
const fmt = await load('view/format.js');

test('dateKey cắt chuỗi ISO, không phụ thuộc múi giờ', () => {
    assert.equal(dates.dateKey('2026-11-02T07:00:00'), '2026-11-02');
    assert.equal(dates.dateKey('2026-11-02T23:59:59'), '2026-11-02');
    assert.equal(dates.dateKey(null), '');
    assert.equal(dates.dateKey('abc'), '');
});

test('weekStartKey trả về thứ Hai (tuần làm việc bắt đầu thứ Hai)', () => {
    assert.equal(dates.weekStartKey('2026-11-02'), '2026-11-02'); // thứ Hai
    assert.equal(dates.weekStartKey('2026-11-08'), '2026-11-02'); // Chủ nhật thuộc tuần trước
    assert.equal(dates.weekStartKey('2026-11-09'), '2026-11-09');
    assert.equal(dates.weekStartKey('2026-01-01'), '2025-12-29'); // qua ranh giới năm
    assert.equal(dates.weekStartKey('rác'), '');
});

test('addDays đi qua ranh giới tháng/năm đúng', () => {
    assert.equal(dates.addDays('2026-12-31', 1), '2027-01-01');
    assert.equal(dates.addDays('2026-03-01', -1), '2026-02-28');
    assert.equal(dates.addDays('2026-11-02', 6), '2026-11-08');
});

test('formatDateKey / formatWeekRange / formatTime tiếng Việt, ổn định', () => {
    assert.equal(fmt.formatDateKey('2026-11-02'), 'Thứ 2, 02/11/2026');
    assert.equal(fmt.formatDateKey('2026-11-08'), 'Chủ nhật, 08/11/2026');
    assert.equal(fmt.formatWeekRange('2026-11-02'), 'Tuần 02/11 – 08/11/2026');
    assert.equal(fmt.formatTime('2026-11-02T07:05:00'), '07:05');
    assert.equal(fmt.formatDateTime('2026-11-02T07:05:00'), '02/11 07:05');
});

test('formatTimeRange có thứ + ngày và xử lý ca qua đêm', () => {
    assert.equal(fmt.formatTimeRange('2026-11-02T07:00:00', '2026-11-02T15:00:00'), 'Thứ 2 02/11 · 07:00 → 15:00 (8.0h)');
    const overnight = fmt.formatTimeRange('2026-11-02T22:00:00', '2026-11-03T06:00:00');
    assert.match(overnight, /22:00 → 03\/11 06:00 \(8\.0h\)/);
});

test('formatStatus phủ mọi trạng thái, kể cả COMPLETED', () => {
    for (const s of ['ASSIGNED', 'OPEN', 'TAKEN', 'FORCE_ASSIGNED', 'UNFILLED', 'COMPLETED']) {
        assert.notEqual(fmt.formatStatus(s), s, `thiếu nhãn cho ${s}`);
    }
});

test('getTimeAgo', () => {
    const now = Date.parse('2026-11-02T10:00:00Z');
    assert.equal(fmt.getTimeAgo(new Date(now - 5000), now), 'Vừa xong');
    assert.equal(fmt.getTimeAgo(new Date(now - 5 * 60000), now), '5 phút trước');
    assert.equal(fmt.getTimeAgo(new Date(now - 3 * 3600000), now), '3 giờ trước');
    assert.equal(fmt.getTimeAgo(new Date(now - 2 * 86400000), now), '2 ngày trước');
    assert.equal(fmt.getTimeAgo('không phải ngày', now), '');
});

test('pct và scoreText: null KHÔNG bị coi là 0', () => {
    assert.equal(fmt.pct(150), 100);
    assert.equal(fmt.pct(-5), 0);
    assert.equal(fmt.pct('abc'), 0);
    assert.equal(fmt.scoreText(null), '—');
    assert.equal(fmt.scoreText(undefined), '—');
    assert.equal(fmt.scoreText(0), '0');
    assert.equal(fmt.scoreText(82.6), '83');
});
