-- R1c repeatable catalogue fields; existing rows remain published, new API rows start unpublished.
SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='brand' AND column_name='status')=0, 'ALTER TABLE brand ADD COLUMN status TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='brand' AND column_name='sort_order')=0, 'ALTER TABLE brand ADD COLUMN sort_order INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='brand' AND column_name='revision')=0, 'ALTER TABLE brand ADD COLUMN revision BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='brand' AND column_name='source_note')=0, 'ALTER TABLE brand ADD COLUMN source_note VARCHAR(256) NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='series' AND column_name='status')=0, 'ALTER TABLE series ADD COLUMN status TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='series' AND column_name='sort_order')=0, 'ALTER TABLE series ADD COLUMN sort_order INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='series' AND column_name='revision')=0, 'ALTER TABLE series ADD COLUMN revision BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='series' AND column_name='source_note')=0, 'ALTER TABLE series ADD COLUMN source_note VARCHAR(256) NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='model' AND column_name='status')=0, 'ALTER TABLE model ADD COLUMN status TINYINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='model' AND column_name='sort_order')=0, 'ALTER TABLE model ADD COLUMN sort_order INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='model' AND column_name='revision')=0, 'ALTER TABLE model ADD COLUMN revision BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='model' AND column_name='source_note')=0, 'ALTER TABLE model ADD COLUMN source_note VARCHAR(256) NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='standard_project' AND column_name='sort_order')=0, 'ALTER TABLE standard_project ADD COLUMN sort_order INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='standard_project' AND column_name='revision')=0, 'ALTER TABLE standard_project ADD COLUMN revision BIGINT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='standard_project' AND column_name='source_note')=0, 'ALTER TABLE standard_project ADD COLUMN source_note VARCHAR(256) NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;

SET @catalog_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_catalog')=0 AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='operator_account')=1, 'ALTER TABLE operator_account ADD COLUMN can_catalog TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE catalog_statement FROM @catalog_ddl; EXECUTE catalog_statement; DEALLOCATE PREPARE catalog_statement;
