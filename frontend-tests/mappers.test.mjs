import test from 'node:test';
import assert from 'node:assert/strict';
import { load } from './_paths.mjs';

const m = await load('model/mappers.js');

test('safeStatus chỉ cho phép trạng thái đã biết (dùng trong tên class CSS)', () => {
    assert.equal(m.safeStatus('OPEN'), 'OPEN');
    assert.equal(m.safeStatus('COMPLETED'), 'COMPLETED');
    assert.equal(m.safeStatus('"><script>'), 'UNKNOWN');
    assert.equal(m.mapShift({ id: 1, status: 'x y' }).status, 'UNKNOWN');
});

test('mapNotification chuẩn hóa type lạ về info, không tin dữ liệu ngoài', () => {
    const n = m.mapNotification({ id: 3, message: null, type: 'hack', createdAt: '2026-11-02T10:00:00', read: 1 });
    assert.deepEqual(n, { id: 3, message: '', type: 'info', time: '2026-11-02T10:00:00', read: true });
});

test('toScore: null/rỗng là null, không phải 0; kẹp trong [0,100]', () => {
    assert.equal(m.toScore(null), null);
    assert.equal(m.toScore(undefined), null);
    assert.equal(m.toScore(''), null);
    assert.equal(m.toScore('abc'), null);
    assert.equal(m.toScore(0), 0);
    assert.equal(m.toScore(250), 100);
    assert.equal(m.toScore(-3), 0);
    assert.equal(m.toScore('72.5'), 72.5);
});

test('toInt', () => {
    assert.equal(m.toInt(''), null);
    assert.equal(m.toInt(null), null);
    assert.equal(m.toInt('abc'), null);
    assert.equal(m.toInt('12'), 12);
});

test('withSeconds bổ sung giây cho datetime-local', () => {
    assert.equal(m.withSeconds('2026-11-02T07:00'), '2026-11-02T07:00:00');
    assert.equal(m.withSeconds('2026-11-02T07:00:30'), '2026-11-02T07:00:30');
    assert.equal(m.withSeconds('07:00'), '07:00:00');
});

test('shiftFormToPayload: version chỉ gửi khi sửa (khóa lạc quan), id rỗng → null', () => {
    const create = m.shiftFormToPayload({ title: '  Ca sáng ', startTime: '2026-11-02T07:00', endTime: '2026-11-02T15:00', branchId: '1', skillId: '', level: '', employeeId: '' });
    assert.equal(create.title, 'Ca sáng');
    assert.equal(create.startTime, '2026-11-02T07:00:00');
    assert.equal(create.branchId, 1);
    assert.equal(create.requiredSkillId, null);
    assert.equal(create.assignedToId, null);
    assert.ok(!('version' in create));
    const edit = m.shiftFormToPayload({ title: 'x', startTime: '2026-11-02T07:00', endTime: '2026-11-02T15:00', branchId: '1', version: '4' });
    assert.equal(edit.version, 4);
});

test('mapMyScore: không đủ điều kiện hoặc không có điểm đều được thể hiện rõ', () => {
    assert.deepEqual(m.mapMyScore({ shiftId: 9, eligible: false, reason: 'Trùng lịch bận' }),
        { shiftId: 9, eligible: false, reason: 'Trùng lịch bận', score: null, confidence: null, source: null });
    const ok = m.mapMyScore({ shiftId: 9, matchScore: 81, confidence: 60, source: 'heuristic' });
    assert.equal(ok.eligible, true);
    assert.equal(ok.score, 81);
    assert.equal(m.mapMyScore({ shiftId: 9, matchScore: null }).score, null);
});

test('groupScheduleByWeek: gom theo TUẦN rồi NGÀY, ca của nhiều tuần không bị trộn (D1)', () => {
    const shifts = [
        { id: 3, startTime: '2026-11-10T07:00:00' },   // tuần 09/11
        { id: 1, startTime: '2026-11-02T07:00:00' },   // tuần 02/11
        { id: 2, startTime: '2026-11-02T13:00:00' },
        { id: 4, startTime: '2026-11-08T07:00:00' },   // CN, vẫn tuần 02/11
        { id: 5, startTime: 'hỏng' },
    ];
    const g = m.groupScheduleByWeek(shifts);
    assert.deepEqual(g.map(w => w.weekKey), ['2026-11-02', '2026-11-09']);
    assert.deepEqual(g[0].days.map(d => d.dayKey), ['2026-11-02', '2026-11-08']);
    assert.deepEqual(g[0].days[0].shifts.map(s => s.id), [1, 2]);
    assert.deepEqual(g[1].days[0].shifts.map(s => s.id), [3]);
    assert.deepEqual(m.groupScheduleByWeek(null), []);
});

test('hasStarted so sánh chuỗi giờ địa phương', () => {
    assert.equal(m.hasStarted({ startTime: '2026-11-02T07:00:00' }, '2026-11-02T07:00:00'), true);
    assert.equal(m.hasStarted({ startTime: '2026-11-02T07:00:01' }, '2026-11-02T07:00:00'), false);
});
