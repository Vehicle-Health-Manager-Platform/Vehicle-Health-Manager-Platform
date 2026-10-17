-- Independent read capability, off by default; no business data or historical grants.
SET @analytics_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_analytics')=0,'ALTER TABLE operator_account ADD COLUMN can_analytics TINYINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE analytics_stmt FROM @analytics_ddl; EXECUTE analytics_stmt; DEALLOCATE PREPARE analytics_stmt;
