-- A6: private immutable evidence; no historical rows are promoted to completed work.
CREATE TABLE IF NOT EXISTS service_evidence_file (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id BIGINT UNSIGNED NOT NULL,
  record_id BIGINT UNSIGNED NOT NULL,
  file_id BIGINT UNSIGNED NOT NULL,
  kind VARCHAR(16) NOT NULL COMMENT 'PROTECTION/PROCESS/FAULT/FINISH/SIGNATURE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_file(file_id), KEY idx_order(order_id,kind), KEY idx_record(record_id,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS service_report_submission (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id BIGINT UNSIGNED NOT NULL,
  report_id BIGINT UNSIGNED NOT NULL,
  no_fault_parts TINYINT NOT NULL,
  no_parts TINYINT NOT NULL,
  submitted_at DATETIME NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_order(order_id), UNIQUE KEY uk_report(report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
