-- Durable payment query lease; no historical receipt is invented.
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='query_token')=0,'ALTER TABLE payment ADD COLUMN query_token VARCHAR(36) NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='next_query_at')=0,'ALTER TABLE payment ADD COLUMN next_query_at DATETIME NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='query_attempts')=0,'ALTER TABLE payment ADD COLUMN query_attempts BIGINT UNSIGNED NOT NULL DEFAULT 0','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='last_query_at')=0,'ALTER TABLE payment ADD COLUMN last_query_at DATETIME NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='last_query_error')=0,'ALTER TABLE payment ADD COLUMN last_query_error VARCHAR(32) NULL','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
SET @r2b_ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='payment' AND index_name='idx_payment_query_due')=0,'ALTER TABLE payment ADD INDEX idx_payment_query_due(channel,status,next_query_at)','SELECT 1');
PREPARE r2b_stmt FROM @r2b_ddl; EXECUTE r2b_stmt; DEALLOCATE PREPARE r2b_stmt;
