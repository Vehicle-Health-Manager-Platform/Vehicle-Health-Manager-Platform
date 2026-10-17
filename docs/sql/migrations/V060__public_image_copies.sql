-- Only independently consented, re-encoded snapshots; never publish private object keys.
CREATE TABLE IF NOT EXISTS public_image_control (
 id TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO public_image_control(id) VALUES(1);
CREATE TABLE IF NOT EXISTS public_image_application (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 owner_id BIGINT UNSIGNED NOT NULL, experience_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 model_id BIGINT UNSIGNED NOT NULL, archive_id BIGINT UNSIGNED NOT NULL,
 source_file_id BIGINT UNSIGNED NOT NULL, copy_file_id BIGINT UNSIGNED NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 preview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 png MEDIUMBLOB NOT NULL, width INT NOT NULL, height INT NOT NULL,
 consent_version VARCHAR(32) NOT NULL, revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', reason_code VARCHAR(24) DEFAULT NULL,
 public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 created_at DATETIME NOT NULL, published_at DATETIME DEFAULT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 creation_result JSON DEFAULT NULL,
 active_source BIGINT UNSIGNED GENERATED ALWAYS AS(CASE WHEN status IN ('PENDING','APPROVED') THEN source_file_id ELSE NULL END) STORED,
 UNIQUE KEY uk_public_image_intent(owner_id,submission_key),
 UNIQUE KEY uk_public_image_source(owner_id,experience_id,active_source),
 UNIQUE KEY uk_public_image_uuid(public_id),
 KEY idx_public_image_owner(owner_id,id), KEY idx_public_image_parent(experience_id,status,id),
 CONSTRAINT ck_public_image_state CHECK(status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN') AND revision>0 AND width>0 AND height>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS public_image_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 application_id BIGINT UNSIGNED NOT NULL, revision BIGINT UNSIGNED NOT NULL,
 status VARCHAR(16) NOT NULL, preview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 occurred_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_public_image_revision(application_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
