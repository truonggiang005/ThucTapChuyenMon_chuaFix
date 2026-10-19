/**
 * Trang đăng nhập. Xác thực chạy ở SERVER (POST /api/auth/login); trình duyệt chỉ nhận cookie phiên HttpOnly.
 * Không còn mật khẩu viết cứng, không tải danh sách nhân viên, không lưu vai trò ở localStorage (SEC-02, SEC-04).
 */
import { createApi } from './model/api.js';

const api = createApi();
const form = document.getElementById('loginForm');
const errorMsg = document.getElementById('errorMsg');
const submit = document.getElementById('btnSubmit');

// Đã đăng nhập sẵn → vào thẳng dashboard
api.auth.me().then(() => { window.location.href = '/dashboard'; }).catch(() => {});

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    errorMsg.hidden = true;
    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;
    submit.disabled = true;           // chặn bấm đúp khi đang chờ server
    try {
        await api.auth.login(email, password);
        window.location.href = '/dashboard';
    } catch (err) {
        errorMsg.textContent = err.message;      // textContent: an toàn; server báo cả trường hợp bị khóa tạm/quá nhiều lần thử
        errorMsg.hidden = false;
        submit.disabled = false;
        document.getElementById('password').select();
    }
});
