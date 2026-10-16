/**
 * Điểm vào của trang dashboard: ghép Model (api) + ViewModel + View.
 * (Thay cho app.js cũ dài 1400 dòng, ARC-01.)
 */
import { createApi } from './model/api.js';
import { createAppViewModel } from './viewmodel/appViewModel.js';
import { mountApp } from './view/appView.js';

const ui = {
    confirm: (msg) => window.confirm(msg),
    redirect: (url) => { window.location.href = url; },
    toast(message, type = 'info') {
        const container = document.getElementById('toastContainer');
        if (!container) return;
        const toast = document.createElement('div');
        toast.className = `toast toast--${type}`;
        toast.textContent = message;          // textContent: không bao giờ diễn giải HTML
        container.appendChild(toast);
        setTimeout(() => toast.remove(), 4000);
    },
};

const api = createApi({ onUnauthorized: () => ui.redirect('/') });
const vm = createAppViewModel({ api, ui, storage: window.localStorage });
const view = mountApp({ vm });

vm.commands.init().then((ok) => {
    if (!ok) return;
    setInterval(view.updateDate, 30000);
    setInterval(vm.commands.checkAIHealth, 30000);
});
