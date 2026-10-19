/**
 * MODEL - API client (ARC-01).
 * Toàn bộ lời gọi fetch nằm ở đây. Giao diện do Spring phục vụ nên luôn gọi CÙNG ORIGIN (/api) - FUN-02.
 * Phiên đăng nhập là cookie HttpOnly do server cấp; CSRF token đọc từ cookie XSRF-TOKEN và gửi lại ở header.
 * Mọi request có thời hạn (mặc định 30s) để giao diện không treo vô hạn khi server/AI chậm (E8).
 */

export class ApiError extends Error {
    constructor(message, status, body) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
        this.body = body;
    }
}

function readCookie(name) {
    const parts = (typeof document !== 'undefined' ? document.cookie : '').split('; ');
    for (const part of parts) {
        const idx = part.indexOf('=');
        if (idx > 0 && part.slice(0, idx) === name) return decodeURIComponent(part.slice(idx + 1));
    }
    return null;
}

/** { a: 1, b: '', c: null } → "?a=1" (bỏ giá trị rỗng) */
export function query(params) {
    const parts = [];
    for (const [k, v] of Object.entries(params || {})) {
        if (v === undefined || v === null || v === '') continue;
        if (Array.isArray(v)) {
            if (v.length) parts.push(`${encodeURIComponent(k)}=${v.map(encodeURIComponent).join(',')}`);
        } else {
            parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
        }
    }
    return parts.length ? '?' + parts.join('&') : '';
}

/**
 * @param {object} deps
 * @param {Function} [deps.fetchFn]        fetch (inject để test)
 * @param {Function} [deps.getCookie]      đọc cookie (inject để test)
 * @param {Function} [deps.onUnauthorized] gọi khi server trả 401 (phiên hết hạn)
 * @param {number}   [deps.timeoutMs]      thời hạn mỗi request
 */
export function createApi({ fetchFn = (...a) => fetch(...a), getCookie = readCookie, onUnauthorized = () => {}, timeoutMs = 30000 } = {}) {
    const BASE = '/api';
    const enc = encodeURIComponent;

    async function request(path, { method = 'GET', body, allow401 = false, timeout = timeoutMs } = {}) {
        const headers = { 'Accept': 'application/json' };
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (method !== 'GET') {
            const token = getCookie('XSRF-TOKEN');
            if (token) headers['X-XSRF-TOKEN'] = token;
        }

        const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
        const timer = ctrl ? setTimeout(() => ctrl.abort(), timeout) : null;
        let res;
        try {
            res = await fetchFn(`${BASE}${path}`, {
                method,
                headers,
                credentials: 'same-origin',
                body: body !== undefined ? JSON.stringify(body) : undefined,
                signal: ctrl ? ctrl.signal : undefined,
            });
        } catch (e) {
            if (e && e.name === 'AbortError') {
                throw new ApiError('Máy chủ phản hồi quá chậm. Vui lòng thử lại sau.', 0, null);
            }
            throw new ApiError('Không thể kết nối đến server. Kiểm tra Spring Boot đã chạy chưa.', 0, null);
        } finally {
            if (timer) clearTimeout(timer);
        }

        const text = await res.text();
        let data = null;
        if (text) {
            try { data = JSON.parse(text); } catch { data = null; }
        }

        if (!res.ok) {
            if (res.status === 401 && !allow401) onUnauthorized();
            const message = (data && data.message) || `Lỗi server (HTTP ${res.status})`;
            throw new ApiError(message, res.status, data);
        }
        return data;
    }

    return {
        auth: {
            login: (email, password) => request('/auth/login', { method: 'POST', body: { email, password }, allow401: true }),
            me: () => request('/auth/me', { allow401: true }),
            logout: () => request('/auth/logout', { method: 'POST', allow401: true }),
            changePassword: (currentPassword, newPassword) =>
                request('/auth/change-password', { method: 'POST', body: { currentPassword, newPassword } }),
        },
        shifts: {
            /** ADMIN: ca trong khoảng [from,to] (ISO), có phân trang */
            listAll: ({ from, to, page, size } = {}) => request('/shifts' + query({ from, to, page, size })),
            listOpen: (branchId) => request('/shifts/open' + query({ branchId })),
            forEmployee: (id) => request(`/shifts/employee/${enc(id)}`),
            create: (payload) => request('/shifts', { method: 'POST', body: payload }),
            update: (id, payload) => request(`/shifts/${enc(id)}`, { method: 'PUT', body: payload }),
            release: (id) => request(`/shifts/${enc(id)}/release`, { method: 'POST' }),
            take: (id) => request(`/shifts/${enc(id)}/take`, { method: 'POST' }),
            assign: (id, employeeId) => request(`/shifts/${enc(id)}/assign`, { method: 'POST', body: { employeeId } }),
            markLate: (id) => request(`/shifts/${enc(id)}/late`, { method: 'POST' }),
            /** Gợi ý AI cho admin: gọi Gemini nên cho thời hạn dài hơn */
            candidates: (id) => request(`/shifts/${enc(id)}/candidates`, { timeout: 45000 }),
            /** Điểm của CHÍNH mình cho nhiều ca trong MỘT lời gọi (B4) */
            myScores: (shiftIds) => request('/shifts/my-scores' + query({ shiftIds }), { timeout: 20000 }),
        },
        catalog: {
            branches: () => request('/branches'),
            skills: () => request('/skills'),
            createBranch: (payload) => request('/branches', { method: 'POST', body: payload }),
            updateBranch: (id, payload) => request(`/branches/${enc(id)}`, { method: 'PUT', body: payload }),
            createSkill: (payload) => request('/skills', { method: 'POST', body: payload }),
            updateSkill: (id, payload) => request(`/skills/${enc(id)}`, { method: 'PUT', body: payload }),
        },
        employees: {
            list: () => request('/employees'),
            get: (id) => request(`/employees/${enc(id)}`),
            create: (payload) => request('/employees', { method: 'POST', body: payload }),
            update: (id, payload) => request(`/employees/${enc(id)}`, { method: 'PUT', body: payload }),
            resetPassword: (id, newPassword) =>
                request(`/employees/${enc(id)}/reset-password`, { method: 'POST', body: { newPassword } }),
        },
        busy: {
            forEmployee: (id) => request(`/busy-schedules/employee/${enc(id)}`),
            create: (payload) => request('/busy-schedules', { method: 'POST', body: payload }),
            remove: (id) => request(`/busy-schedules/${enc(id)}`, { method: 'DELETE' }),
        },
        notifications: {
            list: () => request('/notifications'),
            markRead: (id) => request(`/notifications/${enc(id)}/read`, { method: 'POST' }),
            markAllRead: () => request('/notifications/read-all', { method: 'POST' }),
            clear: () => request('/notifications', { method: 'DELETE' }),
        },
        ai: {
            health: () => request('/ai/health'),
        },
    };
}
