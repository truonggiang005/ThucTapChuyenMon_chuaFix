/**
 * VIEWMODEL - Store quan sát được (ARC-01).
 * state chỉ được đổi qua set(patch); mỗi lần đổi, các subscriber nhận danh sách khóa vừa đổi
 * để View chỉ vẽ lại đúng phần liên quan.
 */
export function createStore(initial) {
    let state = { ...initial };
    const listeners = new Set();
    return {
        get state() { return state; },
        set(patch) {
            const changed = Object.keys(patch).filter(k => state[k] !== patch[k]);
            if (changed.length === 0) return;
            state = { ...state, ...patch };
            listeners.forEach(fn => fn(state, new Set(changed)));
        },
        /** Gọi fn ngay lập tức với toàn bộ khóa (lần vẽ đầu) rồi mỗi lần state đổi. */
        subscribe(fn) {
            listeners.add(fn);
            fn(state, new Set(Object.keys(state)));
            return () => listeners.delete(fn);
        },
    };
}
