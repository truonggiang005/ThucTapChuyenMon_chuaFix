-- ============================================================================
-- SmartSchedule - Schema MySQL 8 cho môi trường THẬT (profile prod dùng ddl-auto=validate).
--
-- Cách dùng (một lần, trên DB trống):
--   mysql -u <user> -p -e "CREATE DATABASE smart_schedule CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
--   mysql -u <user> -p smart_schedule < src/main/resources/db/schema.sql
-- Sau đó đặt BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD ở lần chạy đầu để tạo ADMIN đầu tiên (xem README).
--
-- Khớp với các @Entity trong com.smartschedule.entity. Nếu thêm/đổi entity, cập nhật file này (hoặc chuyển sang Flyway).
-- Kiểu cột cố ý giống thứ Hibernate sinh ra (bigint, int, varchar, datetime(6), bit(1)) để `validate` chấp nhận.
-- ============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS branches (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(150) NOT NULL,
    address       VARCHAR(300) NULL,
    max_capacity  INT          NULL,           -- số người làm ĐỒNG THỜI tối đa; NULL = không giới hạn
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS skills (
    id    BIGINT       NOT NULL AUTO_INCREMENT,
    name  VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skills_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS employees (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    full_name           VARCHAR(150) NOT NULL,
    email               VARCHAR(200) NOT NULL,   -- luôn lưu chữ thường
    password_hash       VARCHAR(255) NOT NULL,   -- BCrypt
    role                VARCHAR(20)  NOT NULL DEFAULT 'EMPLOYEE',   -- ADMIN | EMPLOYEE
    max_hours_per_week  INT          NULL,
    total_completed     INT          NULL,
    total_cancelled     INT          NULL,
    late_arrival_count  INT          NULL,
    preferred_time      VARCHAR(20)  NULL,       -- MORNING | AFTERNOON | EVENING | NIGHT
    active              BIT(1)       NOT NULL DEFAULT b'1',
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        DATETIME(6)  NULL,
    primary_branch_id   BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_employees_email (email),
    KEY idx_employees_primary_branch (primary_branch_id),
    CONSTRAINT fk_employees_branch FOREIGN KEY (primary_branch_id) REFERENCES branches (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS employee_skills (
    employee_id  BIGINT NOT NULL,
    skill_id     BIGINT NOT NULL,
    skill_level  INT    NOT NULL,                -- 1..5
    PRIMARY KEY (employee_id, skill_id),
    KEY idx_employee_skills_skill (skill_id),
    CONSTRAINT fk_es_employee FOREIGN KEY (employee_id) REFERENCES employees (id),
    CONSTRAINT fk_es_skill    FOREIGN KEY (skill_id)    REFERENCES skills (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS shifts (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    title              VARCHAR(200) NOT NULL,
    start_time         DATETIME(6)  NOT NULL,
    end_time           DATETIME(6)  NOT NULL,
    required_level     INT          NULL,
    status             VARCHAR(20)  NOT NULL,   -- ASSIGNED | OPEN | TAKEN | FORCE_ASSIGNED | UNFILLED | COMPLETED
    version            BIGINT       NOT NULL,   -- khóa lạc quan (@Version)
    branch_id          BIGINT       NOT NULL,
    required_skill_id  BIGINT       NULL,
    assigned_to        BIGINT       NULL,
    late_marked        BIT(1)       NOT NULL DEFAULT b'0',
    PRIMARY KEY (id),
    KEY idx_shift_status (status),
    KEY idx_shift_start_time (start_time),
    KEY idx_shift_status_start (status, start_time),
    KEY idx_shift_assigned_to (assigned_to, start_time),
    KEY idx_shift_branch (branch_id),
    KEY idx_shift_skill (required_skill_id),
    CONSTRAINT fk_shift_branch   FOREIGN KEY (branch_id)         REFERENCES branches (id),
    CONSTRAINT fk_shift_skill    FOREIGN KEY (required_skill_id) REFERENCES skills (id),
    CONSTRAINT fk_shift_employee FOREIGN KEY (assigned_to)       REFERENCES employees (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS busy_schedules (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    employee_id  BIGINT       NOT NULL,
    start_time   DATETIME(6)  NOT NULL,
    end_time     DATETIME(6)  NOT NULL,
    reason       VARCHAR(300) NULL,
    PRIMARY KEY (id),
    KEY idx_busy_employee (employee_id),
    KEY idx_busy_time (start_time, end_time),
    CONSTRAINT fk_busy_employee FOREIGN KEY (employee_id) REFERENCES employees (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Thông báo lưu ở server (D4, A7). "read" là từ khóa MySQL nên cột tên is_read.
CREATE TABLE IF NOT EXISTS notifications (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    employee_id  BIGINT       NOT NULL,
    type         VARCHAR(20)  NOT NULL,         -- success | warning | error | info
    message      VARCHAR(500) NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    is_read      BIT(1)       NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_employee_created (employee_id, created_at),
    CONSTRAINT fk_notification_employee FOREIGN KEY (employee_id) REFERENCES employees (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Khóa cho cron job (A10): chỉ một instance chạy tại một thời điểm.
CREATE TABLE IF NOT EXISTS scheduler_locks (
    name          VARCHAR(100) NOT NULL,
    locked_until  DATETIME(6)  NOT NULL,
    locked_by     VARCHAR(100) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
