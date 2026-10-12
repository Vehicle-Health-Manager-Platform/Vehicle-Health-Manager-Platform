-- R4a2 private owner-entered typed details. No historical content backfill.
CREATE TABLE IF NOT EXISTS `archive_structured_detail` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `vehicle_id` BIGINT UNSIGNED NOT NULL,
  `archive_id` BIGINT UNSIGNED NOT NULL,
  `archive_type` TINYINT NOT NULL,
  `fields_json` JSON NOT NULL,
  `revision` INT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_archive_structured_owner_source` (`user_id`,`archive_id`),
  KEY `idx_archive_structured_vehicle` (`user_id`,`vehicle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
