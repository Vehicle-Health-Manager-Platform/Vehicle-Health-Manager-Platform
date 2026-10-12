-- R4a3 owner-entered raw observations, without scores or inferred validity periods.
CREATE TABLE IF NOT EXISTS `vehicle_health_observation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `vehicle_id` BIGINT UNSIGNED NOT NULL,
  `metric` VARCHAR(32) NOT NULL,
  `archive_id` BIGINT UNSIGNED NOT NULL,
  `archive_type` TINYINT NOT NULL,
  `measurement_json` JSON NOT NULL,
  `revision` INT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_health_observation_owner_vehicle_metric` (`user_id`,`vehicle_id`,`metric`),
  KEY `idx_health_observation_source` (`archive_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
