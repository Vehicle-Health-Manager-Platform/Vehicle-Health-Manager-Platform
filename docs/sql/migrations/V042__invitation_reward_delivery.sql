-- Preserve public once-per-user semantics while allowing event-scoped rewards.
SET @reward_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coupon_issue' AND column_name='origin')=0,
'ALTER TABLE coupon_issue ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT ''PUBLIC'', ADD COLUMN reward_item_id BIGINT UNSIGNED DEFAULT NULL, ADD COLUMN public_user_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN origin=''PUBLIC'' THEN user_id ELSE NULL END) STORED, DROP INDEX uk_coupon_issue_owner, ADD UNIQUE KEY uk_coupon_issue_owner(coupon_id,public_user_id), ADD UNIQUE KEY uk_coupon_issue_reward(reward_item_id), ADD CONSTRAINT ck_coupon_issue_origin CHECK((origin=''PUBLIC'' AND reward_item_id IS NULL) OR (origin=''INVITE'' AND reward_item_id IS NOT NULL))', 'SELECT 1');
PREPARE reward_stmt FROM @reward_ddl;
EXECUTE reward_stmt;
DEALLOCATE PREPARE reward_stmt;
CREATE TABLE IF NOT EXISTS invite_reward_batch (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 record_id CHAR(36) NOT NULL, invitee_id BIGINT UNSIGNED NOT NULL,
 source_type VARCHAR(32) NOT NULL, source_id BIGINT UNSIGNED NOT NULL, merchant_id BIGINT UNSIGNED DEFAULT NULL,
 policy_id BIGINT UNSIGNED DEFAULT NULL, policy_revision BIGINT UNSIGNED DEFAULT NULL,
 qualified_at DATETIME NOT NULL, rule_version VARCHAR(32) NOT NULL,
 state VARCHAR(32) NOT NULL, attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME DEFAULT NULL, completed_at DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_invite_reward_event(record_id), UNIQUE KEY uk_invite_reward_new_user(invitee_id),
 KEY idx_invite_reward_retry(state,next_attempt_at),
 CONSTRAINT ck_invite_reward_state CHECK(state IN ('READY','WAIT_STOCK','WAIT_SOURCE','WAIT_ACCOUNT','UNKNOWN_POLICY','EXPIRED','INVALID_EVIDENCE','COMPLETE')),
 CONSTRAINT ck_invite_reward_actor CHECK((source_type='user' AND merchant_id IS NULL) OR (source_type='staff_account' AND merchant_id IS NOT NULL)),
 CONSTRAINT ck_invite_reward_policy CHECK((policy_id IS NULL AND policy_revision IS NULL AND state='UNKNOWN_POLICY') OR (policy_id IS NOT NULL AND policy_revision IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Historical qualifications have no policy-at-event evidence. Record UNKNOWN only;
-- never choose a later policy or manufacture coupon issuance.
INSERT IGNORE INTO invite_reward_batch(record_id,invitee_id,source_type,source_id,merchant_id,qualified_at,rule_version,state)
SELECT record_id,user_id,source_type,source_id,merchant_id,qualified_at,'R6B_REWARD_V1','UNKNOWN_POLICY'
FROM invite_registration WHERE qualification_status='QUALIFIED' AND identity_source='VERIFIED_WECHAT'
AND attribution_result='ATTRIBUTED' AND qualified_at IS NOT NULL AND source_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS invite_reward_item (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, batch_id BIGINT UNSIGNED NOT NULL,
 role VARCHAR(16) NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 coupon_id BIGINT UNSIGNED NOT NULL, publication_revision BIGINT UNSIGNED NOT NULL, terms_json JSON NOT NULL,
 user_coupon_id BIGINT UNSIGNED DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_invite_reward_role(batch_id,role), UNIQUE KEY uk_invite_reward_coupon(user_coupon_id),
 KEY idx_invite_reward_owner(user_id,batch_id),
 CONSTRAINT ck_invite_reward_role CHECK(role IN ('INVITER','INVITEE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
