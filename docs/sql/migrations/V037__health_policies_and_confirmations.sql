-- Configurable source rules only: never seed technical thresholds or confirm legacy data.
CREATE TABLE IF NOT EXISTS model_health_policy (
 model_id BIGINT UNSIGNED NOT NULL, metric VARCHAR(32) NOT NULL,
 rule_json JSON NULL, version BIGINT UNSIGNED NOT NULL, operator_id BIGINT UNSIGNED NOT NULL,
 PRIMARY KEY(model_id,metric),
 CONSTRAINT ck_health_policy_metric CHECK(metric IN ('OIL','BRAKE_PAD','TIRE','TRACTION_BATTERY','COOLANT','AUX_BATTERY')),
 CONSTRAINT ck_health_policy_version CHECK(version>0),
 CONSTRAINT ck_health_policy_rule CHECK(rule_json IS NULL OR JSON_TYPE(rule_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS health_evidence_confirmation (
 user_id BIGINT UNSIGNED NOT NULL, vehicle_id BIGINT UNSIGNED NOT NULL, metric VARCHAR(32) NOT NULL,
 model_id BIGINT UNSIGNED NULL, policy_version BIGINT UNSIGNED NOT NULL, observation_revision INT NOT NULL,
 confirmed TINYINT NOT NULL, revision INT NOT NULL,
 PRIMARY KEY(user_id,vehicle_id,metric),
 CONSTRAINT ck_health_confirmation CHECK(confirmed IN (0,1) AND revision>0 AND observation_revision>=0),
 CONSTRAINT ck_health_confirmation_metric CHECK(metric IN ('OIL','BRAKE_PAD','TIRE','TRACTION_BATTERY','COOLANT','AUX_BATTERY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS vehicle_reminder_preference (
 user_id BIGINT UNSIGNED NOT NULL, vehicle_id BIGINT UNSIGNED NOT NULL,
 maintenance_days INT NOT NULL, maintenance_km INT NOT NULL, insurance_days INT NOT NULL, inspection_days INT NOT NULL,
 maintenance_project_id BIGINT UNSIGNED NULL,
 revision INT NOT NULL, PRIMARY KEY(user_id,vehicle_id),
 CONSTRAINT ck_reminder_preference CHECK(revision>0 AND maintenance_days BETWEEN 0 AND 365 AND insurance_days BETWEEN 0 AND 365 AND inspection_days BETWEEN 0 AND 365 AND maintenance_km BETWEEN 0 AND 100000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
