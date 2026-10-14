-- R6a: no legacy backfill or production rewards; UTC evidence, Beijing progress.
CREATE TABLE IF NOT EXISTS invite_code (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 code CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_type VARCHAR(24) NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,
 merchant_id BIGINT UNSIGNED DEFAULT NULL,
 created_at DATETIME NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_invite_code(code), UNIQUE KEY uk_invite_actor(actor_type,actor_id),
 CONSTRAINT chk_invite_actor CHECK ((actor_type='user' AND merchant_id IS NULL) OR (actor_type='staff_account' AND merchant_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS invite_registration (
 user_id BIGINT UNSIGNED NOT NULL,
 record_id CHAR(36) NOT NULL,
 app_id VARCHAR(64) NOT NULL,
 identity_source VARCHAR(24) NOT NULL,
 invite_code_id BIGINT UNSIGNED DEFAULT NULL,
 source_type VARCHAR(24) DEFAULT NULL,
 source_id BIGINT UNSIGNED DEFAULT NULL,
 merchant_id BIGINT UNSIGNED DEFAULT NULL,
 attribution_result VARCHAR(24) NOT NULL,
 qualification_status VARCHAR(24) NOT NULL,
 qualification_reason VARCHAR(32) DEFAULT NULL,
 registered_at DATETIME NOT NULL,
 qualified_at DATETIME DEFAULT NULL,
 reward_month CHAR(7) DEFAULT NULL,
 rule_version VARCHAR(32) NOT NULL,
 PRIMARY KEY(user_id), UNIQUE KEY uk_invite_record_id(record_id), KEY idx_invite_source(source_type,source_id,reward_month,qualification_status),
 KEY idx_invite_record(source_type,source_id,registered_at,user_id),
 CONSTRAINT chk_invite_identity CHECK(identity_source IN ('VERIFIED_WECHAT','UNKNOWN','TEST')),
 CONSTRAINT chk_invite_qualification CHECK(qualification_status IN ('PENDING','UNKNOWN','TEST','QUALIFIED','REJECTED')),
 CONSTRAINT chk_invite_qualified CHECK((qualification_status='QUALIFIED' AND qualified_at IS NOT NULL AND reward_month IS NOT NULL AND invite_code_id IS NOT NULL) OR (qualification_status<>'QUALIFIED' AND qualified_at IS NULL AND reward_month IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS invite_phone_evidence (
 phone_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT UNSIGNED NOT NULL,
 app_id VARCHAR(64) NOT NULL,
 verified_at DATETIME NOT NULL,
 PRIMARY KEY(phone_hash), KEY idx_invite_phone_owner(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
