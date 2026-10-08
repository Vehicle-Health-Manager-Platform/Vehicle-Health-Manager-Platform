-- Owner decision on an immutable pickup sheet; repeatable migration.
SET @decision_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='dispute_reason')=0,'ALTER TABLE pickup_check ADD COLUMN dispute_reason VARCHAR(500) DEFAULT NULL','SELECT 1');
PREPARE decision_statement FROM @decision_ddl; EXECUTE decision_statement; DEALLOCATE PREPARE decision_statement;

-- Preserve the full owner reason in the state-transition audit as well.
SET @decision_ddl=IF((SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order_status_transition' AND column_name='note')<500,'ALTER TABLE order_status_transition MODIFY COLUMN note VARCHAR(500) DEFAULT NULL','SELECT 1');
PREPARE decision_statement FROM @decision_ddl; EXECUTE decision_statement; DEALLOCATE PREPARE decision_statement;
