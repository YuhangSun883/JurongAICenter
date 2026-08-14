CREATE TABLE IF NOT EXISTS console_admins (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    role VARCHAR(32) NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN / FINANCE / OPERATOR / VIEWER',
    disabled TINYINT(1) NOT NULL DEFAULT 0,
    last_login_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_console_admin_email (email),
    KEY idx_console_admin_role_disabled (role, disabled, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='独立后台管理员账号';
