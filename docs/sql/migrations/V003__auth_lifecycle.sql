-- S0-7.1e: durable login sessions and shared binding attempt limits.
-- Apply after V002. These tables are safe to create repeatedly; never alter V001/V002 in place.
CREATE TABLE IF NOT EXISTS `auth_session` (
  `id` CHAR(36) NOT NULL,
  `subject_type` VARCHAR(24) NOT NULL,
  `subject_id` BIGINT UNSIGNED NOT NULL,
  `role` VARCHAR(24) NOT NULL,
  `app_id` VARCHAR(32) NOT NULL,
  `binding_id` BIGINT UNSIGNED DEFAULT NULL,
  `merchant_id` BIGINT UNSIGNED DEFAULT NULL,
  `refresh_hash` CHAR(64) NOT NULL,
  `expires_at` DATETIME NOT NULL,
  `revoked_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_hash` (`refresh_hash`),
  KEY `idx_subject_active` (`subject_type`, `subject_id`, `revoked_at`),
  KEY `idx_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Refresh rotation and access-token revocation';

CREATE TABLE IF NOT EXISTS `auth_rate_limit` (
  `scope` VARCHAR(32) NOT NULL,
  `key_hash` CHAR(64) NOT NULL,
  `window_start` DATETIME NOT NULL,
  `attempts` INT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`scope`, `key_hash`, `window_start`),
  KEY `idx_window` (`window_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Shared auth attempt counters';
