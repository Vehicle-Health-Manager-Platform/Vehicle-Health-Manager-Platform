-- V013: dispute handling, owner review and the resume conditions after an objection.

-- Apply after V012; repeated execution preserves existing rows and never invents history.

CREATE TABLE IF NOT EXISTS `order_dispute` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `pickup_check_id` BIGINT UNSIGNED NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `from_status` VARCHAR(32) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  `opened_by` BIGINT UNSIGNED NOT NULL,
  `opened_at` DATETIME NOT NULL,
  `resolved_at` DATETIME DEFAULT NULL,
  `resolved_by` BIGINT UNSIGNED DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_order` (`order_id`), KEY `idx_merchant_status` (`merchant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_dispute_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `dispute_id` BIGINT UNSIGNED NOT NULL,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `action` VARCHAR(24) NOT NULL,
  `actor_type` VARCHAR(16) NOT NULL,
  `actor_id` BIGINT UNSIGNED NOT NULL,
  `note` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`), KEY `idx_dispute` (`dispute_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- owner_confirm now also carries 3 = dispute accepted by the owner after review.
SET @dispute_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='owner_confirm' AND column_comment NOT LIKE '%3%')>0,'ALTER TABLE pickup_check MODIFY COLUMN owner_confirm TINYINT DEFAULT 0 COMMENT ''0待1确认2异议3争议已解决''','SELECT 1');
PREPARE dispute_statement FROM @dispute_ddl; EXECUTE dispute_statement; DEALLOCATE PREPARE dispute_statement;
