CREATE TABLE IF NOT EXISTS `vehicle_archive_file` (
  `archive_id` BIGINT UNSIGNED NOT NULL,
  `file_id` BIGINT UNSIGNED NOT NULL,
  `position` TINYINT UNSIGNED NOT NULL,
  PRIMARY KEY (`archive_id`, `position`),
  UNIQUE KEY `uk_archive_file` (`archive_id`, `file_id`),
  KEY `idx_file_id` (`file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆档案私有图片引用';
