-- Explicit GCJ02 coordinates confirmed by a store manager. Never backfill legacy merchant.lng/lat.
CREATE TABLE IF NOT EXISTS merchant_location (
  merchant_id BIGINT UNSIGNED NOT NULL,
  lng DECIMAL(10,7) DEFAULT NULL,
  lat DECIMAL(10,7) DEFAULT NULL,
  coordinate_system VARCHAR(16) DEFAULT NULL,
  source VARCHAR(32) DEFAULT NULL,
  version BIGINT UNSIGNED NOT NULL,
  actor_staff_id BIGINT UNSIGNED NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (merchant_id),
  CONSTRAINT chk_merchant_location CHECK (
    (lng IS NULL AND lat IS NULL AND coordinate_system IS NULL AND source IS NULL) OR
    (lng IS NOT NULL AND lat IS NOT NULL AND lng BETWEEN -180 AND 180 AND lat BETWEEN -90 AND 90
      AND coordinate_system IS NOT NULL AND coordinate_system = 'GCJ02'
      AND source IS NOT NULL AND source = 'MERCHANT_CONFIRMED')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
