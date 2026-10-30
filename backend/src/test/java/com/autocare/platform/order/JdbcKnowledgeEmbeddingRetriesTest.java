package com.autocare.platform.order;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL retry, quota, authorization and atomic audit; vectors are synthetic. */
class JdbcKnowledgeEmbeddingRetriesTest extends JdbcKnowledgeEmbeddingRuntimeTest {
    void retryRuntime() {
        runtime = new KnowledgeEmbeddingRuntime(db, knowledge, profile, provider, true, false, false,
                new KnowledgeEmbeddingRetries.Policy(true, 2, Duration.ofSeconds(60)));
        knowledge.embeddingRuntime(runtime);
    }
    int retries() { return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action=?", Integer.class, KnowledgeEmbeddingRetries.ACTION); }
    void advance(long seconds) { clock.value = clock.value.plusSeconds(seconds); }
    void pump(int ticks) { for (int i=0;i<ticks;i++) runtime.tick(); }
    void transientThenSuccess() {
        provider = (p, texts) -> {
            if (calls.incrementAndGet() == 1) throw new EmbeddingProvider.Failed("UNAVAILABLE");
            return new EmbeddingProvider.Output(p.fingerprint(), texts.stream().map(t -> new double[]{1,0,0}).toList());
        };
    }
    @Test void transientFailureWaitsThenSucceedsWithSafeAuditAndNoSourceMutation() {
        long id=published(allowed());String original=jdbc.queryForObject("SELECT payload FROM knowledge_document WHERE id=?",String.class,id);
        transientThenSuccess();retryRuntime();runtime.tick();assertEquals("FAILED",jobState());
        var failedAt=jdbc.queryForObject("SELECT updated_at FROM knowledge_embedding_job LIMIT 1",java.sql.Timestamp.class);
        advance(59);pump(2);assertEquals(1,calls.get());assertEquals(0,retries());assertEquals(failedAt,jdbc.queryForObject("SELECT updated_at FROM knowledge_embedding_job LIMIT 1",java.sql.Timestamp.class));
        advance(2);pump(2);assertEquals("SUCCEEDED",jobState());assertEquals(2,calls.get());assertEquals(1,retries());
        pump(5);assertEquals(2,calls.get());assertEquals(original,jdbc.queryForObject("SELECT payload FROM knowledge_document WHERE id=?",String.class,id));
        var audit=jdbc.queryForMap("SELECT actor_type,actor_id,before_state,after_state FROM audit_log WHERE action=?",KnowledgeEmbeddingRetries.ACTION);
        assertEquals("system",audit.get("actor_type"));assertEquals(0,((Number)audit.get("actor_id")).intValue());
        assertEquals(1,db.parse(audit.get("after_state")).path("retry_count").asInt());
        assertFalse(db.json(audit).contains("冷却液"));assertFalse(db.json(audit).contains("content_text"));
    }
    @Test void permanentVectorFailureAndRevokedSourcesNeverRetry() {
        long id=published(allowed());provider=(p,t)->{calls.incrementAndGet();throw new EmbeddingProvider.Failed("INVALID_VECTOR");};
        retryRuntime();runtime.tick();for(int i=0;i<6;i++){advance(300);runtime.tick();}
        assertEquals(1,calls.get());assertEquals(0,retries());assertEquals("FAILED",jobState());
        knowledge.status(manager,id,key(),state(2,"STOPPED"));pump(3);
        assertEquals("CANCELLED",jobState());assertEquals(0,count("knowledge_embedding_chunk"));assertEquals(1,calls.get());
    }
    @Test void transientRetryQuotaSurvivesRuntimeRestart() {
        published(allowed());provider=(p,t)->{calls.incrementAndGet();throw new EmbeddingProvider.Failed("UNAVAILABLE");};
        retryRuntime();runtime.tick();for(int i=0;i<8;i++){advance(180);runtime.tick();}
        assertEquals(3,calls.get());assertEquals(2,retries());assertEquals("FAILED",jobState());
        runtime.close();knowledge=new LicensedKnowledge(db,policies.operators,new ExperienceModeration(db,policies.operators,false),ai,true,true);retryRuntime();for(int i=0;i<6;i++){advance(180);runtime.tick();}
        assertEquals(3,calls.get());assertEquals(2,retries());assertEquals(0,count("knowledge_embedding_chunk"));
    }
    @Test void auditFailureRollsBackRequeueAndDoesNotConsumeQuota() {
        published(allowed());transientThenSuccess();retryRuntime();runtime.tick();advance(61);
        jdbc.execute("CREATE TRIGGER fail_embedding_retry BEFORE INSERT ON audit_log FOR EACH ROW BEGIN IF NEW.action='KNOWLEDGE_EMBEDDING_RETRY' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic retry audit failure'; END IF; END");
        try { runtime.tick();assertTrue(runtime.cycleFailed());assertEquals("FAILED",jobState());assertEquals(1,calls.get());assertEquals(0,retries()); }
        finally { jdbc.execute("DROP TRIGGER fail_embedding_retry"); }
        runtime.tick();assertEquals("SUCCEEDED",jobState());assertEquals(2,calls.get());assertEquals(1,retries());
    }
    @Test void revokedTransientFailureIsNeverRequeued() {
        long id=published(allowed());provider=(p,t)->{calls.incrementAndGet();throw new EmbeddingProvider.Failed("UNAVAILABLE");};
        retryRuntime();runtime.tick();knowledge.status(manager,id,key(),state(2,"STOPPED"));advance(180);pump(4);
        assertEquals("CANCELLED",jobState());assertEquals(1,calls.get());assertEquals(0,retries());
    }
    @Test void lostWorkerLeaseCanRecoverOnlyAfterItsFailureDelay() {
        published(allowed());transientThenSuccess();retryRuntime();runtime.tick();
        jdbc.update("UPDATE knowledge_embedding_job SET state='RUNNING',error_code=NULL,lease_token=?,lease_until=?",key(),ReservationStore.time(clock.value.minusSeconds(1)));
        runtime.tick();assertEquals("FAILED",jobState());assertEquals("WORKER_LOST",jdbc.queryForObject("SELECT error_code FROM knowledge_embedding_job LIMIT 1",String.class));
        advance(61);pump(3);assertEquals("SUCCEEDED",jobState());assertEquals(2,calls.get());assertEquals(1,retries());
    }
    @Test void concurrentInstancesConsumeOnlyOneRetry() throws Exception {
        published(allowed());provider=(p,t)->{calls.incrementAndGet();throw new EmbeddingProvider.Failed("UNAVAILABLE");};
        retryRuntime();runtime.tick();advance(61);
        try (var otherRuntime=new KnowledgeEmbeddingRuntime(db,knowledge,profile,provider,true,false,false,new KnowledgeEmbeddingRetries.Policy(true,2,Duration.ofSeconds(60)))) {
            var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
            try {
                var a=pool.submit(()->{try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}runtime.tick();});
                var b=pool.submit(()->{try{gate.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}otherRuntime.tick();});
                gate.countDown();a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
        assertEquals(2,calls.get());assertEquals(1,retries());assertEquals("FAILED",jobState());assertEquals(0,count("knowledge_embedding_chunk"));
    }
}
