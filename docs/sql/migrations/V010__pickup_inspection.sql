-- V010: merchant upload identity scope and immutable pickup evidence.

-- Apply after V009; repeated execution preserves existing rows.

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='upload_request' AND column_name='owner_type')=0,'ALTER TABLE `upload_request` ADD COLUMN `owner_type` VARCHAR(24) NOT NULL DEFAULT ''user''','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='merchant_id')=0,'ALTER TABLE `pickup_check` ADD COLUMN `merchant_id` BIGINT UNSIGNED DEFAULT NULL','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='staff_id')=0,'ALTER TABLE `pickup_check` ADD COLUMN `staff_id` BIGINT UNSIGNED DEFAULT NULL','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='dashboard_photo_id')=0,'ALTER TABLE `pickup_check` ADD COLUMN `dashboard_photo_id` BIGINT UNSIGNED DEFAULT NULL','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='mileage_source')=0,'ALTER TABLE `pickup_check` ADD COLUMN `mileage_source` VARCHAR(16) NOT NULL DEFAULT ''MANUAL''','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='mileage_baseline')=0,'ALTER TABLE `pickup_check` ADD COLUMN `mileage_baseline` JSON DEFAULT NULL','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='mileage_reason')=0,'ALTER TABLE `pickup_check` ADD COLUMN `mileage_reason` VARCHAR(200) DEFAULT NULL','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='arrived_at')=0,'ALTER TABLE `pickup_check` ADD COLUMN `arrived_at` DATETIME DEFAULT NULL','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='arrival_reason')=0,'ALTER TABLE `pickup_check` ADD COLUMN `arrival_reason` VARCHAR(200) DEFAULT NULL','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='damage_status')=0,'ALTER TABLE `pickup_check` ADD COLUMN `damage_status` VARCHAR(16) DEFAULT NULL','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='upload_request' AND index_name='uk_upload_key')>0,'ALTER TABLE upload_request DROP INDEX uk_upload_key','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

SET @pickup_ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='upload_request' AND index_name='uk_upload_actor_key')=0,'ALTER TABLE upload_request ADD UNIQUE KEY uk_upload_actor_key(owner_type,owner_id,request_path,idempotency_key)','SELECT 1');
PREPARE pickup_statement FROM @pickup_ddl; EXECUTE pickup_statement; DEALLOCATE PREPARE pickup_statement;

CREATE TABLE IF NOT EXISTS pickup_check_file (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 pickup_check_id BIGINT UNSIGNED NOT NULL,
 file_id BIGINT UNSIGNED NOT NULL,
 photo_slot VARCHAR(16) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id), UNIQUE KEY uk_pickup_slot(pickup_check_id,photo_slot), UNIQUE KEY uk_pickup_file(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
