/**
 * Trang đăng nhập. Xác thực chạy ở SERVER (POST /api/auth/login); trình duyệt chỉ nhận cookie phiên HttpOnly.
 * Không còn mật khẩu viết cứng, không tải danh sách nhân viên, không lưu vai trò ở localStorage (SEC-02, SEC-04).
 */
import { createApi } from './model/api.js';

const api = createApi();
const form = document.getElementById('loginForm');
const errorMsg = document.getElementById('errorMsg');

// Đã đăng nhập sẵn → vào thẳng dashboard
api.auth.me().then(() => { window.location.href = '/dashboard'; }).catch(() => {});

form.addEventListener('submit', async (e) => {
    e.preventDefault();
    errorMsg.style.display = 'none';
    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;
    try {
        await api.auth.login(email, password);
        window.location.href = '/dashboard';
    } catch (err) {
        errorMsg.textContent = err.message;      // textContent: an toàn
        errorMsg.style.display = 'block';
    }
});
