-- R1a 商家入驻：申请扩展、追加式审核事件、区域品类配额与运营入驻权限。
-- 既有代码未引用 merchant_application，扩展不影响现有行为；重复执行安全。

SET @onboarding_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='merchant_application' AND column_name='revision')=0,
 'ALTER TABLE merchant_application ADD COLUMN address VARCHAR(256) NOT NULL DEFAULT '''', ADD COLUMN contact_phone VARCHAR(20) NOT NULL DEFAULT '''', ADD COLUMN revision INT UNSIGNED NOT NULL DEFAULT 1, ADD COLUMN merchant_id BIGINT UNSIGNED DEFAULT NULL, ADD COLUMN last_reason_code VARCHAR(32) DEFAULT NULL',
 'SELECT 1');
PREPARE onboarding_statement FROM @onboarding_ddl; EXECUTE onboarding_statement; DEALLOCATE PREPARE onboarding_statement;

SET @onboarding_ddl=IF((SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='merchant_application' AND index_name='idx_applicant_status')=0,
 'ALTER TABLE merchant_application ADD KEY idx_applicant_status (applicant_user_id,status)',
 'SELECT 1');
PREPARE onboarding_statement FROM @onboarding_ddl; EXECUTE onboarding_statement; DEALLOCATE PREPARE onboarding_statement;

SET @onboarding_ddl=IF((SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='merchant_application' AND index_name='idx_merchant_link')=0,
 'ALTER TABLE merchant_application ADD KEY idx_merchant_link (merchant_id)',
 'SELECT 1');
PREPARE onboarding_statement FROM @onboarding_ddl; EXECUTE onboarding_statement; DEALLOCATE PREPARE onboarding_statement;

-- Append-only review events; one immutable decision per (application, revision).
CREATE TABLE IF NOT EXISTS `merchant_application_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `application_id` BIGINT UNSIGNED NOT NULL,
  `revision` INT UNSIGNED NOT NULL,
  `decision` VARCHAR(16) NOT NULL,
  `reason_code` VARCHAR(32) DEFAULT NULL,
  `merchant_id` BIGINT UNSIGNED DEFAULT NULL,
  `operator_id` BIGINT UNSIGNED NOT NULL,
  `decided_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_application_revision` (`application_id`,`revision`),
  KEY `idx_operator` (`operator_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Region and category quota. A missing row means zero: onboarding is fail-closed.
CREATE TABLE IF NOT EXISTS `merchant_region_category_quota` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `region_code` VARCHAR(32) NOT NULL,
  `category` VARCHAR(32) NOT NULL,
  `max_active` INT UNSIGNED NOT NULL DEFAULT 0,
  `updated_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_region_category` (`region_code`,`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Onboarding review is a separate permission from experience review.
SET @onboarding_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_onboard')=0,
 'ALTER TABLE operator_account ADD COLUMN can_onboard TINYINT NOT NULL DEFAULT 0 AFTER can_review',
 'SELECT 1');
PREPARE onboarding_statement FROM @onboarding_ddl; EXECUTE onboarding_statement; DEALLOCATE PREPARE onboarding_statement;

-- New stores count against a region/category quota. Pre-existing rows keep the empty
-- region code and therefore consume no quota until an operator maintains them.
SET @onboarding_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='merchant' AND column_name='region_code')=0,
 'ALTER TABLE merchant ADD COLUMN region_code VARCHAR(32) NOT NULL DEFAULT '''' AFTER address',
 'SELECT 1');
PREPARE onboarding_statement FROM @onboarding_ddl; EXECUTE onboarding_statement; DEALLOCATE PREPARE onboarding_statement;

SET @onboarding_ddl=IF((SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='merchant' AND index_name='idx_region_type_status')=0,
 'ALTER TABLE merchant ADD KEY idx_region_type_status (region_code,merchant_type,status)',
 'SELECT 1');
PREPARE onboarding_statement FROM @onboarding_ddl; EXECUTE onboarding_statement; DEALLOCATE PREPARE onboarding_statement;
