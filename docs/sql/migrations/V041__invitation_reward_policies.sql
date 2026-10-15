-- R6b1 configuration only; no production policy seed or reward issuance.
SET @invite_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_invite')=0,'ALTER TABLE operator_account ADD COLUMN can_invite TINYINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE invite_stmt FROM @invite_ddl;
EXECUTE invite_stmt;
DEALLOCATE PREPARE invite_stmt;
CREATE TABLE IF NOT EXISTS invite_reward_policy (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 scope VARCHAR(16) NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 source_note VARCHAR(256) NOT NULL,
 valid_from DATETIME NOT NULL, valid_to DATETIME NOT NULL,
 inviter_coupon_id BIGINT UNSIGNED DEFAULT NULL,
 inviter_publication_revision BIGINT UNSIGNED DEFAULT NULL,
 inviter_terms_json JSON DEFAULT NULL,
 invitee_coupon_id BIGINT UNSIGNED NOT NULL,
 invitee_publication_revision BIGINT UNSIGNED NOT NULL,
 invitee_terms_json JSON DEFAULT NULL,
 rule_version VARCHAR(32) NOT NULL,
 created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME NOT NULL, published_at DATETIME DEFAULT NULL,
 active_scope VARCHAR(16) GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN scope ELSE NULL END) STORED,
 PRIMARY KEY(id), UNIQUE KEY uk_invite_active_policy(active_scope), KEY idx_invite_policy(id,status),
 CONSTRAINT ck_invite_policy CHECK(scope IN ('OWNER','STAFF') AND status IN ('DRAFT','PUBLISHED','STOPPED') AND revision>0 AND valid_from<valid_to AND invitee_publication_revision>0),
 CONSTRAINT ck_invite_policy_sides CHECK((scope='OWNER' AND inviter_coupon_id IS NOT NULL AND inviter_publication_revision IS NOT NULL AND inviter_publication_revision>0) OR (scope='STAFF' AND inviter_coupon_id IS NULL AND inviter_publication_revision IS NULL AND inviter_terms_json IS NULL)),
 CONSTRAINT ck_invite_policy_snapshot CHECK(status='DRAFT' OR (published_at IS NOT NULL AND invitee_terms_json IS NOT NULL AND (scope='STAFF' OR inviter_terms_json IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
