-- Immutable owner observation versions; application never updates/deletes history rows.
CREATE TABLE IF NOT EXISTS `vehicle_health_observation_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `vehicle_id` BIGINT UNSIGNED NOT NULL,
  `metric` VARCHAR(32) NOT NULL,
  `archive_id` BIGINT UNSIGNED NOT NULL,
  `archive_type` TINYINT NOT NULL,
  `revision` INT NOT NULL,
  `operation` VARCHAR(32) NOT NULL,
  `supersedes_revision` INT DEFAULT NULL,
  `measurement_json` JSON NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_observation_history_version` (`user_id`,`vehicle_id`,`metric`,`revision`),
  KEY `idx_observation_history_correction` (`user_id`,`vehicle_id`,`metric`,`supersedes_revision`),
  KEY `idx_observation_history_source` (`archive_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A single snapshot of the current version, never inferred earlier measurements.
INSERT INTO vehicle_health_observation_history
  (user_id,vehicle_id,metric,archive_id,archive_type,revision,operation,measurement_json,created_at,updated_at)
SELECT o.user_id,o.vehicle_id,o.metric,o.archive_id,o.archive_type,o.revision,'MIGRATED_SNAPSHOT',o.measurement_json,UTC_TIMESTAMP(),UTC_TIMESTAMP()
FROM vehicle_health_observation o WHERE o.is_deleted=0 AND NOT EXISTS (
  SELECT 1 FROM vehicle_health_observation_history h
  WHERE h.user_id=o.user_id AND h.vehicle_id=o.vehicle_id AND h.metric=o.metric AND h.revision=o.revision
);
