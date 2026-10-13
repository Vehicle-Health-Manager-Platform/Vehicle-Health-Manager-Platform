-- Operator-declared exact model rules, no inferred or seeded technical verdicts.
CREATE TABLE IF NOT EXISTS project_model_applicability (
 project_id BIGINT UNSIGNED NOT NULL,
 model_id BIGINT UNSIGNED NOT NULL,
 verdict VARCHAR(24) NULL,
 source_reference VARCHAR(256) NULL,
 version BIGINT UNSIGNED NOT NULL,
 operator_id BIGINT UNSIGNED NOT NULL,
 PRIMARY KEY(project_id,model_id),
 CONSTRAINT ck_project_model_verdict CHECK ((verdict IS NULL AND source_reference IS NULL) OR (verdict IS NOT NULL AND verdict IN ('APPLICABLE','NOT_APPLICABLE') AND source_reference IS NOT NULL)),
 CONSTRAINT ck_project_model_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
