/**
 * MODEL - API client (ARC-01).
 * Toàn bộ lời gọi fetch nằm ở đây. Giao diện do Spring phục vụ nên luôn gọi CÙNG ORIGIN (/api) - FUN-02.
 * Phiên đăng nhập là cookie HttpOnly do server cấp; CSRF token đọc từ cookie XSRF-TOKEN và gửi lại ở header.
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

/**
 * @param {object} deps
 * @param {Function} [deps.fetchFn]        fetch (inject để test)
 * @param {Function} [deps.getCookie]      đọc cookie (inject để test)
 * @param {Function} [deps.onUnauthorized] gọi khi server trả 401 (phiên hết hạn)
 */
export function createApi({ fetchFn = (...a) => fetch(...a), getCookie = readCookie, onUnauthorized = () => {} } = {}) {
    const BASE = '/api';

    async function request(path, { method = 'GET', body, allow401 = false } = {}) {
        const headers = { 'Accept': 'application/json' };
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (method !== 'GET') {
            const token = getCookie('XSRF-TOKEN');
            if (token) headers['X-XSRF-TOKEN'] = token;
        }

        let res;
        try {
            res = await fetchFn(`${BASE}${path}`, {
                method,
                headers,
                credentials: 'same-origin',
                body: body !== undefined ? JSON.stringify(body) : undefined,
            });
        } catch (e) {
            throw new ApiError('Không thể kết nối đến server. Kiểm tra Spring Boot đã chạy chưa.', 0, null);
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
        },
        shifts: {
            listAll: () => request('/shifts'),
            listOpen: (branchId) => request('/shifts/open' + (branchId ? `?branchId=${encodeURIComponent(branchId)}` : '')),
            forEmployee: (id) => request(`/shifts/employee/${encodeURIComponent(id)}`),
            create: (payload) => request('/shifts', { method: 'POST', body: payload }),
            update: (id, payload) => request(`/shifts/${encodeURIComponent(id)}`, { method: 'PUT', body: payload }),
            release: (id) => request(`/shifts/${encodeURIComponent(id)}/release`, { method: 'POST' }),
            take: (id) => request(`/shifts/${encodeURIComponent(id)}/take`, { method: 'POST' }),
            assign: (id, employeeId) => request(`/shifts/${encodeURIComponent(id)}/assign`, { method: 'POST', body: { employeeId } }),
            candidates: (id) => request(`/shifts/${encodeURIComponent(id)}/candidates`),
            myScore: (id) => request(`/shifts/${encodeURIComponent(id)}/my-score`),
            branches: () => request('/shifts/branches'),
            skills: () => request('/shifts/skills'),
        },
        employees: {
            list: () => request('/employees'),
            get: (id) => request(`/employees/${encodeURIComponent(id)}`),
        },
        busy: {
            forEmployee: (id) => request(`/busy-schedules/employee/${encodeURIComponent(id)}`),
            create: (payload) => request('/busy-schedules', { method: 'POST', body: payload }),
            remove: (id) => request(`/busy-schedules/${encodeURIComponent(id)}`, { method: 'DELETE' }),
        },
        ai: {
            health: () => request('/ai/health'),
        },
    };
}
