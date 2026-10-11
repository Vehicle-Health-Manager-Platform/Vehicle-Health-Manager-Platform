-- Preserve existing review jobs; null review means a new completion event. No backfill.
ALTER TABLE service_archive_job MODIFY COLUMN review_id BIGINT UNSIGNED NULL;
SET @r2a_add = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='service_archive_job' AND column_name='card_requested')=0,
 'ALTER TABLE service_archive_job ADD COLUMN card_requested TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE r2a_stmt FROM @r2a_add;
EXECUTE r2a_stmt;
DEALLOCATE PREPARE r2a_stmt;
