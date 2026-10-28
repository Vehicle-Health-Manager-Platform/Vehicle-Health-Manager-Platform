-- Structural backup fixture only: not a trusted publication or real model output.
INSERT INTO knowledge_document(id,model_id,payload,valid_from,valid_to,source_hash,created_at,submission_actor,submission_key,submission_hash)
 VALUES(1,1,JSON_OBJECT('synthetic_backup_fixture',true),'2026-01-01','2027-01-01',SHA2('synthetic-source',256),'2026-10-27',1,'11111111-1111-4111-8111-111111111111',SHA2('synthetic-submission',256));
INSERT INTO knowledge_embedding_job(id,document_id,document_revision,source_hash,profile_hash,profile_json,state,created_at,updated_at)
 VALUES(1,1,1,SHA2('synthetic-source',256),SHA2('synthetic-profile',256),JSON_OBJECT('synthetic_backup_fixture',true),'SUCCEEDED','2026-10-27','2026-10-27');
INSERT INTO knowledge_embedding_chunk(job_id,ordinal,start_offset,end_offset,chunk_hash,content_text,dimension,vector_json)
 VALUES(1,0,0,9,SHA2('synthetic',256),'synthetic',3,JSON_ARRAY(1,0,0));
