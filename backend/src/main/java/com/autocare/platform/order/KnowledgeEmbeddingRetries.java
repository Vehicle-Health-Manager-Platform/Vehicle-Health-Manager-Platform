package com.autocare.platform.order;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import static com.autocare.platform.order.ReservationStore.*;

/** Opt-in transient retries. The existing control lock serializes quota and audit. */
final class KnowledgeEmbeddingRetries {
    static final String ACTION = "KNOWLEDGE_EMBEDDING_RETRY";
    record Policy(boolean enabled, int limit, Duration delay) {
        Policy {
            if (limit < 1 || limit > 3 || delay == null || delay.toSeconds() < 60
                    || delay.toSeconds() > 3600 || !delay.equals(Duration.ofSeconds(delay.toSeconds())))
                throw new IllegalArgumentException("Invalid embedding retry policy");
        }
        static Policy disabled() { return new Policy(false, 2, Duration.ofSeconds(60)); }
    }
    private final ReservationStore db;
    private final KnowledgeVectorIndex index;
    private final EmbeddingProfile profile;
    private final Policy policy;
    private long cursor;

    KnowledgeEmbeddingRetries(ReservationStore db, KnowledgeVectorIndex index, EmbeddingProfile profile, Policy policy) {
        if (policy == null || policy.enabled() && (index == null || profile == null))
            throw new IllegalArgumentException("Retries require configured indexing");
        this.db = db; this.index = index; this.profile = profile; this.policy = policy;
    }

    void tick() {
        if (!policy.enabled()) return;
        long next = db.transactions.execute(tx -> {
            db.one("SELECT id FROM knowledge_control WHERE id=1 FOR UPDATE");
            var jobs = db.jdbc.queryForList("SELECT * FROM knowledge_embedding_job WHERE id>? AND profile_hash=? AND state='FAILED' AND error_code IN ('UNAVAILABLE','WORKER_LOST') ORDER BY id LIMIT 20 FOR UPDATE", cursor, profile.fingerprint());
            if (jobs.isEmpty()) return 0L;
            for (var job : jobs) {
                long id = number(job, "id");
                // This rechecks current publication, independent permission, expiry,
                // document revision and the complete model profile while holding the lock.
                if (!index.same(job)) continue;
                int attempts = db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type='knowledge_embedding_job' AND resource_id=? AND action=?", Integer.class, id, ACTION);
                if (attempts >= policy.limit()) continue;
                long wait = policy.delay().toSeconds() * (1L << attempts);
                if (instant(job.get("updated_at")).plusSeconds(wait).isAfter(db.now())) continue;
                if (db.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_chunk WHERE job_id=?", Integer.class, id) != 0)
                    throw new IllegalStateException("Inconsistent failed embedding job");
                if (db.jdbc.update("UPDATE knowledge_embedding_job SET state='QUEUED',error_code=NULL,updated_at=? WHERE id=? AND state='FAILED'", time(db.now()), id) != 1)
                    throw new IllegalStateException("Embedding retry state changed");
                String before = db.json(Map.of("state", "FAILED", "error_code", job.get("error_code"), "retry_count", attempts));
                String after = db.json(Map.of("state", "QUEUED", "retry_count", attempts + 1, "policy", "TRANSIENT_RETRY_V1"));
                db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id,created_at,updated_at) VALUES('system',0,?,'knowledge_embedding_job',?,CAST(? AS JSON),CAST(? AS JSON),?,?,?)", ACTION, id, before, after, UUID.randomUUID().toString(), time(db.now()), time(db.now()));
            }
            return number(jobs.get(jobs.size() - 1), "id");
        });
        cursor = next;
    }
}
