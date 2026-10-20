-- Auxiliary materials are deliberately separate from business image file IDs.
CREATE TABLE IF NOT EXISTS input_material (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 owner_id BIGINT UNSIGNED NOT NULL,
 vehicle_id BIGINT UNSIGNED NOT NULL,
 purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_type VARCHAR(64) NOT NULL,
 size_bytes BIGINT UNSIGNED NOT NULL,
 duration_ms INT UNSIGNED NOT NULL DEFAULT 0,
 pages INT UNSIGNED NOT NULL DEFAULT 0,
 scan_status VARCHAR(16) NOT NULL,
 is_deleted TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_input_material_object(object_key),
 KEY idx_input_material_owner(owner_id,vehicle_id,purpose,id),
 CONSTRAINT ck_input_material CHECK(purpose IN ('AI','ARCHIVE') AND scan_status='CLEAN' AND is_deleted IN (0,1)
  AND ((content_type='audio/mpeg' AND size_bytes BETWEEN 1 AND 3145728 AND duration_ms BETWEEN 1000 AND 60000 AND pages=0)
    OR (content_type='application/pdf' AND size_bytes BETWEEN 1 AND 10485760 AND duration_ms=0 AND pages BETWEEN 1 AND 50)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
