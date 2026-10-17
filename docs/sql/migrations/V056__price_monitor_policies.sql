-- Independent source configuration. No seeded ranges, inherited grants, or historical comparison.
SET @price_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_price_monitor')=0,'ALTER TABLE operator_account ADD COLUMN can_price_monitor TINYINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE price_stmt FROM @price_ddl; EXECUTE price_stmt; DEALLOCATE PREPARE price_stmt;
CREATE TABLE IF NOT EXISTS price_monitor_policy (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 quote_id BIGINT UNSIGNED NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_snapshot JSON NOT NULL,
 lower_price DECIMAL(10,2) NOT NULL,
 upper_price DECIMAL(10,2) NOT NULL,
 price_unit VARCHAR(32) NOT NULL,
 applicability_note VARCHAR(256) NOT NULL,
 source_note VARCHAR(256) NOT NULL,
 valid_from DATETIME NOT NULL,
 valid_to DATETIME NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 rule_version VARCHAR(32) NOT NULL DEFAULT 'R8B_PRICE_SOURCE_V1',
 created_by BIGINT UNSIGNED NOT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 creation_result JSON DEFAULT NULL,
 created_at DATETIME NOT NULL,
 published_at DATETIME DEFAULT NULL,
 stopped_at DATETIME DEFAULT NULL,
 published_quote BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN quote_id ELSE NULL END) STORED,
 PRIMARY KEY(id), UNIQUE KEY uk_price_submission(created_by,submission_key),
 UNIQUE KEY uk_price_published(published_quote), KEY idx_price_quote(quote_id,status,id),
 CONSTRAINT ck_price_range CHECK(lower_price>0 AND upper_price>=lower_price),
 CONSTRAINT ck_price_window CHECK(valid_to>valid_from),
 CONSTRAINT ck_price_status CHECK(status IN ('DRAFT','PUBLISHED','STOPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
