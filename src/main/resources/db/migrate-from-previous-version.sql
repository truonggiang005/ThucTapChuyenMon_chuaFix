-- ============================================================================
-- Nâng cấp một DB ĐÃ TỒN TẠI từ phiên bản trước lên phiên bản này (MySQL 8).
-- Chạy MỘT lần, SAU KHI sao lưu. DB mới hoàn toàn thì dùng schema.sql, không cần file này.
-- (Môi trường dev dùng ddl-auto=update tự thêm cột/bảng mới, nhưng KHÔNG đổi kiểu cột status ->
--  vẫn cần câu MODIFY ở mục 1, hoặc đơn giản là xóa DB dev và để seeder tạo lại.)
-- ============================================================================

-- 1) status: bỏ kiểu ENUM cũ của MySQL (nếu có) để nhận thêm giá trị COMPLETED
ALTER TABLE shifts MODIFY COLUMN status VARCHAR(20) NOT NULL;

-- 2) Cột mới cho ca và nhân viên
ALTER TABLE shifts    ADD COLUMN late_marked BIT(1) NOT NULL DEFAULT b'0';
ALTER TABLE employees ADD COLUMN active BIT(1) NOT NULL DEFAULT b'1';
ALTER TABLE employees ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0;
ALTER TABLE employees ADD COLUMN locked_until DATETIME(6) NULL;

-- 3) Email luôn chữ thường (đăng nhập tra theo chữ thường). Nếu có 2 email chỉ khác hoa/thường, sửa tay trước khi chạy.
UPDATE employees SET email = LOWER(email);

-- 4) Bảng mới: thông báo + khóa cron (tạo bằng schema.sql: chỉ cần chạy hai CREATE TABLE tương ứng)
CREATE TABLE IF NOT EXISTS notifications (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    employee_id  BIGINT       NOT NULL,
    type         VARCHAR(20)  NOT NULL,
    message      VARCHAR(500) NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    is_read      BIT(1)       NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_employee_created (employee_id, created_at),
    CONSTRAINT fk_notification_employee FOREIGN KEY (employee_id) REFERENCES employees (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS scheduler_locks (
    name          VARCHAR(100) NOT NULL,
    locked_until  DATETIME(6)  NOT NULL,
    locked_by     VARCHAR(100) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
