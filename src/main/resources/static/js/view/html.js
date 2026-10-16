/**
 * VIEW - template an toàn mặc định (SEC-05).
 *
 * html`...${giá trị}...`: MỌI giá trị chèn vào đều được escape, trừ khi là kết quả của một html`` khác (SafeHtml)
 * hoặc được đánh dấu rõ bằng raw(). Chỉ gán vào DOM qua setHtml(), hàm này từ chối chuỗi thường.
 * Quy tắc: chỉ đặt giá trị trong thuộc tính có dấu nháy "..." và KHÔNG đặt dữ liệu vào style/URL/sự kiện.
 */
export class SafeHtml {
    constructor(value) { this.value = value; }
    toString() { return this.value; }
}

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;', '`': '&#96;' };

export function esc(value) {
    return String(value ?? '').replace(/[&<>"'`]/g, c => ESC[c]);
}

/** Đánh dấu chuỗi HTML đã tin cậy (chỉ dùng cho hằng số viết tay, không bao giờ cho dữ liệu từ API). */
export function raw(value) { return new SafeHtml(String(value)); }

function render(v) {
    if (v instanceof SafeHtml) return v.value;
    if (Array.isArray(v)) return v.map(render).join('');
    if (v === null || v === undefined || v === false) return '';
    return esc(v);
}

export function html(strings, ...values) {
    let out = strings[0];
    for (let i = 0; i < values.length; i++) out += render(values[i]) + strings[i + 1];
    return new SafeHtml(out);
}

export function setHtml(el, safe) {
    if (!el) return;
    if (!(safe instanceof SafeHtml)) throw new TypeError('setHtml chỉ nhận kết quả của html``');
    el.innerHTML = safe.value;
}
