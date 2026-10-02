-- S0-7.1c: independent WeChat identity binding for technicians on a single test AppID.
-- Apply after V001. Repeat execution is safe; existing V001 is intentionally unchanged.
CREATE TABLE IF NOT EXISTS `staff_wechat_identity` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `app_id` VARCHAR(32) NOT NULL,
  `openid` VARCHAR(128) NOT NULL,
  `staff_account_id` BIGINT UNSIGNED NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE or REVOKED',
  `bound_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `unbound_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  `active_openid` VARCHAR(128) GENERATED ALWAYS AS (
    CASE WHEN `status` = 'ACTIVE' AND `is_deleted` = 0 THEN `openid` ELSE NULL END
  ) STORED,
  `active_staff_account_id` BIGINT UNSIGNED GENERATED ALWAYS AS (
    CASE WHEN `status` = 'ACTIVE' AND `is_deleted` = 0 THEN `staff_account_id` ELSE NULL END
  ) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_active_app_openid` (`app_id`, `active_openid`),
  UNIQUE KEY `uk_active_app_staff` (`app_id`, `active_staff_account_id`),
  KEY `idx_staff_history` (`staff_account_id`, `created_at`),
  KEY `idx_openid_history` (`app_id`, `openid`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技师微信身份绑定历史';
