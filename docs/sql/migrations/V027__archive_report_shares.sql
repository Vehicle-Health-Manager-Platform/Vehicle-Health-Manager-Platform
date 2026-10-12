-- R3c3: immutable allowlisted summaries; never store raw share capabilities.
CREATE TABLE IF NOT EXISTS `archive_report_share` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `public_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `token_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `owner_id` BIGINT UNSIGNED NOT NULL,
  `vehicle_id` BIGINT UNSIGNED NOT NULL,
  `summary` JSON NOT NULL,
  `expires_at` DATETIME NOT NULL,
  `revoked_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_archive_share_public` (`public_id`),
  UNIQUE KEY `uk_archive_share_hash` (`token_hash`),
  KEY `idx_archive_share_owner_vehicle` (`owner_id`,`vehicle_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
