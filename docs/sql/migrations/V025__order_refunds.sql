-- R2b2 repeatable refund agreement/outbox fields; legacy rows remain unmanaged.
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='refund_no')=0,'ALTER TABLE refund ADD COLUMN refund_no VARCHAR(40) NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='revision')=0,'ALTER TABLE refund ADD COLUMN revision BIGINT UNSIGNED NOT NULL DEFAULT 0','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='from_status')=0,'ALTER TABLE refund ADD COLUMN from_status VARCHAR(24) NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='owner_consented_at')=0,'ALTER TABLE refund ADD COLUMN owner_consented_at DATETIME NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='merchant_consented_at')=0,'ALTER TABLE refund ADD COLUMN merchant_consented_at DATETIME NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='requested_by')=0,'ALTER TABLE refund ADD COLUMN requested_by BIGINT UNSIGNED NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='merchant_actor')=0,'ALTER TABLE refund ADD COLUMN merchant_actor BIGINT UNSIGNED NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='decision_note')=0,'ALTER TABLE refund ADD COLUMN decision_note VARCHAR(256) NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='currency')=0,'ALTER TABLE refund ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT ''CNY''','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='next_attempt_at')=0,'ALTER TABLE refund ADD COLUMN next_attempt_at DATETIME NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='dispatch_token')=0,'ALTER TABLE refund ADD COLUMN dispatch_token VARCHAR(36) NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='attempts')=0,'ALTER TABLE refund ADD COLUMN attempts BIGINT UNSIGNED NOT NULL DEFAULT 0','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='last_error')=0,'ALTER TABLE refund ADD COLUMN last_error VARCHAR(32) NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='refund' AND column_name='controlled_order_id')=0,'ALTER TABLE refund ADD COLUMN controlled_order_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN refund_no IS NOT NULL THEN order_id ELSE NULL END) STORED','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='refund' AND index_name='uk_managed_refund_no')=0,'ALTER TABLE refund ADD UNIQUE INDEX uk_managed_refund_no(refund_no)','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='refund' AND index_name='uk_managed_refund_order')=0,'ALTER TABLE refund ADD UNIQUE INDEX uk_managed_refund_order(controlled_order_id)','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='refund' AND index_name='idx_refund_recovery_due')=0,'ALTER TABLE refund ADD  INDEX idx_refund_recovery_due(status,next_attempt_at)','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
CREATE TABLE IF NOT EXISTS refund_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 channel VARCHAR(32) NOT NULL,
 event_id VARCHAR(36) NOT NULL,
 refund_id BIGINT UNSIGNED NOT NULL,
 event_hash CHAR(64) NOT NULL,
 outcome VARCHAR(24) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 is_deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_refund_event(channel,event_id), KEY idx_refund_event(refund_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
