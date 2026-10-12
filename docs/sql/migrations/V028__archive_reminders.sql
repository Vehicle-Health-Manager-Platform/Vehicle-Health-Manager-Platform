-- R4a1 owner-provided targets. No automatic backfill or inferred legal dates.
CREATE TABLE IF NOT EXISTS `archive_reminder` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `vehicle_id` BIGINT UNSIGNED NOT NULL,
  `archive_type` TINYINT NOT NULL,
  `archive_id` BIGINT UNSIGNED NOT NULL,
  `due_date` DATE DEFAULT NULL,
  `due_mileage` INT UNSIGNED DEFAULT NULL,
  `active` TINYINT NOT NULL DEFAULT 1,
  `revision` INT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_archive_reminder_owner_vehicle_type` (`user_id`,`vehicle_id`,`archive_type`),
  KEY `idx_archive_reminder_source` (`archive_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
