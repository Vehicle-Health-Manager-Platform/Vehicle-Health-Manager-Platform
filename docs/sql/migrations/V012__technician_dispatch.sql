-- A5: extend existing assignments without inventing historical evidence.
SET @dispatch_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='technician_assignment' AND column_name='assigned_by')=0,'ALTER TABLE technician_assignment ADD COLUMN assigned_by BIGINT UNSIGNED DEFAULT NULL','SELECT 1');
PREPARE dispatch_statement FROM @dispatch_ddl; EXECUTE dispatch_statement; DEALLOCATE PREPARE dispatch_statement;
SET @dispatch_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='technician_assignment' AND column_name='accepted_at')=0,'ALTER TABLE technician_assignment ADD COLUMN accepted_at DATETIME DEFAULT NULL','SELECT 1');
PREPARE dispatch_statement FROM @dispatch_ddl; EXECUTE dispatch_statement; DEALLOCATE PREPARE dispatch_statement;
