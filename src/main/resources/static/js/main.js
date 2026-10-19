/**
 * Điểm vào của trang dashboard: ghép Model (api) + ViewModel + View.
 * (Thay cho app.js cũ dài 1400 dòng, ARC-01.)
 */
import { createApi } from './model/api.js';
import { createAppViewModel } from './viewmodel/appViewModel.js';
import { mountApp } from './view/appView.js';

const POLL_NOTIFICATIONS_MS = 30000;
const POLL_AI_MS = 60000;
const TICK_MS = 60000;

/**
 * Hộp thoại xác nhận bằng <dialog> (thay cho window.confirm): giữ focus trong hộp thoại, đóng bằng Esc,
 * đọc được bằng trình đọc màn hình. Trả về Promise<boolean>.
 */
function confirmDialog(message, { okText = 'Xác nhận', danger = false } = {}) {
    const dlg = document.getElementById('confirmDialog');
    if (!dlg || typeof dlg.showModal !== 'function') return Promise.resolve(window.confirm(message));
    document.getElementById('confirmMessage').textContent = message;
    const ok = document.getElementById('confirmOk');
    ok.textContent = okText;
    ok.classList.toggle('btn--danger', danger);
    ok.classList.toggle('btn--primary', !danger);
    dlg.returnValue = '';
    return new Promise((resolve) => {
        dlg.addEventListener('close', () => resolve(dlg.returnValue === 'ok'), { once: true });
        dlg.showModal();
    });
}

const ui = {
    confirm: confirmDialog,
    redirect: (url) => { window.location.href = url; },
    toast(message, type = 'info') {
        const container = document.getElementById('toastContainer');
        if (!container) return;
        const toast = document.createElement('div');
        toast.className = `toast toast--${['success', 'error', 'warning', 'info'].includes(type) ? type : 'info'}`;
        toast.textContent = message;          // textContent: không bao giờ diễn giải HTML
        container.appendChild(toast);
        setTimeout(() => toast.remove(), 4000);
    },
};

const api = createApi({ onUnauthorized: () => ui.redirect('/') });
const vm = createAppViewModel({ api, ui });
const view = mountApp({ vm });

vm.commands.init().then((ok) => {
    if (!ok) return;
    setInterval(view.updateDate, 30000);
    setInterval(vm.commands.tick, TICK_MS);
    setInterval(vm.commands.checkAIHealth, POLL_AI_MS);
    // Thông báo lưu ở server: hỏi lại định kỳ (và khi quay lại tab) để người bị cron gán ca biết ngay (D4)
    setInterval(vm.commands.loadNotifications, POLL_NOTIFICATIONS_MS);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) vm.commands.loadNotifications(); });
});
