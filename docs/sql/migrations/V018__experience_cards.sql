-- Private immutable summaries. No backfill or public content.
CREATE TABLE IF NOT EXISTS experience_card (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id BIGINT UNSIGNED NOT NULL,
  archive_id BIGINT UNSIGNED NOT NULL,
  review_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  vehicle_id BIGINT UNSIGNED NOT NULL,
  summary JSON NOT NULL,
  test_mode TINYINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  revision INT UNSIGNED NOT NULL DEFAULT 0,
  consent_version VARCHAR(32) DEFAULT NULL,
  consented_at DATETIME DEFAULT NULL,
  withdrawn_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_order (order_id),
  UNIQUE KEY uk_card_archive (archive_id),
  KEY idx_card_owner_vehicle (user_id,vehicle_id,id),
  KEY idx_card_review (status,test_mode,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
