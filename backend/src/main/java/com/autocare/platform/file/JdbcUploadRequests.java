package com.autocare.platform.file;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short database transactions only; no scanner or object-store calls here. */
public class JdbcUploadRequests {
    public record Reservation(long id, String attempt, String key, JsonNode replay) {}
    public record Cleanup(long id, long request, String key, String claim, int attempts) {}
    private record Stored(long id, String hash, String attempt, String key, String state,
                          String response, Integer error, boolean live, boolean processing) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    public JdbcUploadRequests(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(15);
    }
    private <T> T transaction(java.util.function.Supplier<T> action) {
        try { return tx.execute(s -> action.get()); }
        catch (UploadHttpException e) { throw e; }
        catch (RuntimeException e) { throw UploadHttpException.unavailable(); }
    }
    private void authorize(UploadOwner owner) {
        if (!Instant.now().isBefore(owner.tokenExpires())) throw new UploadHttpException(401, "登录已失效");
        if(owner.technician()!=null){
            var actor=owner.technician();
            var session=jdbc.queryForList("SELECT id FROM auth_session WHERE id=? AND subject_type='staff_account' AND subject_id=? AND role='TECHNICIAN' AND app_id=? AND merchant_id=? AND binding_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP() FOR UPDATE",actor.session(),actor.staffId(),actor.appId(),actor.merchantId(),actor.bindingId());
            var shop=jdbc.queryForList("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0 FOR UPDATE",actor.merchantId());
            var staff=jdbc.queryForList("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role='TECHNICIAN' AND status='ACTIVE' AND is_deleted=0 FOR UPDATE",actor.staffId(),actor.merchantId());
            var binding=jdbc.queryForList("SELECT id FROM staff_wechat_identity WHERE id=? AND staff_account_id=? AND app_id=? AND status='ACTIVE' AND is_deleted=0 AND unbound_at IS NULL FOR UPDATE",actor.bindingId(),actor.staffId(),actor.appId());
            if(!"staff_account".equals(owner.type()) || owner.id()!=actor.staffId() || owner.merchantId()!=actor.merchantId() || !owner.session().equals(actor.session()) || session.isEmpty() || shop.isEmpty() || staff.isEmpty() || binding.isEmpty())throw new UploadHttpException(401,"技师登录已失效");
            return;
        }
        if ("staff_account".equals(owner.type())) {
            var sessions = jdbc.query("SELECT s.id FROM auth_session s JOIN staff_account a ON a.id=s.subject_id JOIN merchant m ON m.id=a.merchant_id "
                + "WHERE s.id=? AND s.subject_type='staff_account' AND s.subject_id=? AND s.role='MERCHANT' AND s.app_id='merchant-account' "
                + "AND s.merchant_id=? AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP() AND a.role='MERCHANT' "
                + "AND a.merchant_id=? AND a.status='ACTIVE' AND a.is_deleted=0 AND m.status=1 AND m.is_deleted=0 FOR UPDATE",
                (rs,n) -> rs.getString(1), owner.session(), owner.id(), owner.merchantId(), owner.merchantId());
            if (sessions.isEmpty()) throw new UploadHttpException(401, "商家登录已失效");
            return;
        }
        if (!"user".equals(owner.type())) throw new UploadHttpException(403, "无权上传");
        var sessions = jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='user' AND subject_id=? "
            + "AND role='OWNER' AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP() FOR UPDATE",
            (rs,n) -> rs.getString(1), owner.session(), owner.id());
        var users = jdbc.query("SELECT id FROM user WHERE id=? AND status=1 AND is_deleted=0 FOR UPDATE",
            (rs,n) -> rs.getLong(1), owner.id());
        if (sessions.isEmpty() || users.isEmpty()) throw new UploadHttpException(401, "登录已失效");
    }
    public void admission(UploadOwner owner, boolean upload) {
        long start = Math.floorDiv(Instant.now().getEpochSecond(), 60) * 60;
        int count=transaction(() -> {
            authorize(owner);
            String scope=upload ? "file_upload" : "file_access";
            String hash=AuthTokens.sha256(owner.type()+":"+owner.id());
            var window=java.sql.Timestamp.from(Instant.ofEpochSecond(start));
            jdbc.update("INSERT INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES (?,?,?,1) "
                + "ON DUPLICATE KEY UPDATE attempts=attempts+1",scope,hash,window);
            return jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope=? AND key_hash=? AND window_start=?",
                Integer.class,scope,hash,window);
        });
        if(count>(upload?10:60)) throw new UploadHttpException(429,"图片操作过于频繁，请稍后重试",
            Math.max(1,(int)(start+60-Instant.now().getEpochSecond())));
    }
    public void checkOwner(UploadOwner owner) { transaction(() -> { authorize(owner); return null; }); }
    private Stored read(long id) {
        return jdbc.queryForObject("SELECT *,expires_at>UTC_TIMESTAMP() AS live,deadline>UTC_TIMESTAMP() AS processing "
            + "FROM upload_request WHERE id=? FOR UPDATE", (rs,n) -> new Stored(rs.getLong("id"),rs.getString("request_hash"),
            rs.getString("attempt_id"),rs.getString("object_key"),rs.getString("state"),rs.getString("response_body"),
            (Integer)rs.getObject("error_status"),rs.getBoolean("live"),rs.getBoolean("processing")), id);
    }
    public Reservation reserve(UploadOwner owner, String key, String hash) {
        return transaction(() -> {
            authorize(owner);
            String attempt = UUID.randomUUID().toString(), object = "uploads/" + UUID.randomUUID();
            jdbc.update("INSERT IGNORE INTO upload_request(owner_type,owner_id,request_path,idempotency_key,request_hash,attempt_id,object_key,state,deadline,expires_at) "
                + "VALUES (?,?,?,?,?,?,?,'PROCESSING',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 24 HOUR))",
                owner.type(), owner.id(), owner.path(), key, hash, attempt, object);
            long id = jdbc.queryForObject("SELECT id FROM upload_request WHERE owner_type=? AND owner_id=? AND request_path=? AND idempotency_key=?",
                Long.class,owner.type(),owner.id(),owner.path(),key);
            Stored row = read(id);
            if (attempt.equals(row.attempt())) return new Reservation(id, attempt, object, null);
            boolean retryConfirmedFailure=row.state().equals("REJECTED") && Integer.valueOf(503).equals(row.error()) && row.hash().equals(hash);
            if (retryConfirmedFailure || (!row.live() && (row.state().equals("SUCCEEDED") || row.state().equals("REJECTED")))) {
                jdbc.update("UPDATE upload_request SET request_hash=?,attempt_id=?,object_key=?,state='PROCESSING',response_body=NULL,error_status=NULL,"
                    + "deadline=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE),expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 24 HOUR) WHERE id=?",
                    hash,attempt,object,id);
                return new Reservation(id,attempt,object,null);
            }
            if (!row.hash().equals(hash)) throw new UploadHttpException(409,"同一幂等键不能用于不同图片");
            if (row.state().equals("SUCCEEDED")) return new Reservation(id,row.attempt(),row.key(), readableReplay(owner,row));
            if (row.state().equals("REJECTED")) throw failure(row.error());
            if (row.state().equals("PROCESSING")) throw UploadHttpException.pending();
            throw UploadHttpException.unavailable();
        });
    }
    private JsonNode readableReplay(UploadOwner owner, Stored row) {
        JsonNode response = parse(row.response());
        long id = response.path("data").path("file_id").asLong();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE id=? AND owner_type=? AND owner_id=? "
            + "AND object_key=? AND is_deleted=0 AND scan_status='CLEAN'",Integer.class,id,owner.type(),owner.id(),row.key());
        if (count == null || count != 1) throw new UploadHttpException(404,"文件不存在或无权访问");
        return response;
    }
    public JsonNode succeed(UploadOwner owner, Reservation reservation, FileValidator.Validated file) {
        return transaction(() -> {
            authorize(owner);
            Stored row=read(reservation.id());
            if (!row.attempt().equals(reservation.attempt()) || !row.state().equals("PROCESSING") || !row.processing())
                throw UploadHttpException.unavailable();
            var holder=new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var stmt=connection.prepareStatement("INSERT INTO file_object(owner_type,owner_id,object_key,content_type,size_bytes,scan_status) "
                    + "VALUES (?,?,?,?,?,'CLEAN')",Statement.RETURN_GENERATED_KEYS);
                stmt.setString(1,owner.type());stmt.setLong(2,owner.id());stmt.setString(3,row.key());stmt.setString(4,file.contentType());stmt.setLong(5,file.bytes().length);
                return stmt;
            },holder);
            Number id=holder.getKey(); if(id==null) throw new IllegalStateException();
            var data=Map.of("file_id",id.longValue(),"content_type",file.contentType(),"size_bytes",file.bytes().length);
            JsonNode response=parse(json(ApiResponse.success(data)));
            jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) "
                + "VALUES (?,?,'FILE_UPLOAD','file_object',?,NULL,?,?)",owner.type(),owner.id(),id.longValue(),json(data),response.path("request_id").asText());
            jdbc.update("UPDATE upload_request SET state='SUCCEEDED',response_body=?,expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 24 HOUR) WHERE id=?",
                json(response),row.id());
            return response;
        });
    }
    public JsonNode recovered(UploadOwner owner, Reservation reservation) {
        return transaction(() -> {
            authorize(owner);Stored row=read(reservation.id());
            if(!row.attempt().equals(reservation.attempt()) || !row.state().equals("SUCCEEDED")) throw UploadHttpException.unavailable();
            return readableReplay(owner,row);
        });
    }
    /** Resolves uncertain commit first. Never deletes an object in this method. */
    public JsonNode failed(Reservation reservation, int status, boolean mayHaveObject) {
        return transaction(() -> {
            Stored row=read(reservation.id());
            if (!row.attempt().equals(reservation.attempt())) return null;
            if (row.state().equals("SUCCEEDED")) return parse(row.response());
            if (mayHaveObject) {
                jdbc.update("UPDATE upload_request SET state='RECONCILE_REQUIRED',error_status=503 WHERE id=?",row.id());
                enqueue(row);
            } else if(row.state().equals("PROCESSING")) {
                jdbc.update("UPDATE upload_request SET state='REJECTED',error_status=? WHERE id=?",status,row.id());
            }
            return null;
        });
    }
    private void enqueue(Stored row) {
        jdbc.update("INSERT INTO upload_cleanup_task(request_id,object_key) VALUES (?,?) ON DUPLICATE KEY UPDATE next_run=LEAST(next_run,UTC_TIMESTAMP())",row.id(),row.key());
    }
    public void expireProcessing() {
        transaction(() -> {
            var ids=jdbc.query("SELECT id FROM upload_request WHERE state='PROCESSING' AND deadline<=UTC_TIMESTAMP() "
                + "ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED",(rs,n)->rs.getLong(1));
            for(long id:ids) {
                Stored row=read(id);
                jdbc.update("UPDATE upload_request SET state='RECONCILE_REQUIRED',error_status=503 WHERE id=?",id);enqueue(row);
            }
            return null;
        });
    }
    public Cleanup claimCleanup() {
        return transaction(() -> {
            var tasks=jdbc.query("SELECT id,request_id,object_key,attempts FROM upload_cleanup_task WHERE next_run<=UTC_TIMESTAMP() "
                + "ORDER BY next_run,id LIMIT 1 FOR UPDATE SKIP LOCKED",(rs,n)->new Cleanup(rs.getLong(1),rs.getLong(2),rs.getString(3),
                    UUID.randomUUID().toString(),rs.getInt(4)));
            if(tasks.isEmpty()) return null;
            var task=tasks.get(0);
            jdbc.update("UPDATE upload_cleanup_task SET claim_id=?,attempts=attempts+1,next_run=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE) WHERE id=?",
                task.claim(),task.id());return task;
        });
    }
    public boolean safeToDelete(Cleanup task) {
        return transaction(() -> {
            Stored row=read(task.request());
            // Terminal failure is a fence: the corresponding attempt can no longer succeed.
            if(row.key().equals(task.key()) && (row.state().equals("SUCCEEDED") || row.state().equals("PROCESSING"))) return false;
            return jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE object_key=?",Integer.class,task.key())==0;
        });
    }
    public void cleaned(Cleanup task) {
        transaction(() -> {
            Stored row=read(task.request());
            if(row.key().equals(task.key()) && row.state().equals("RECONCILE_REQUIRED"))
                jdbc.update("UPDATE upload_request SET state='REJECTED',error_status=503 WHERE id=?",row.id());
            // Retain and recheck tombstones for late external writes; do not purge the key.
            jdbc.update("UPDATE upload_cleanup_task SET cleaned_at=UTC_TIMESTAMP(),next_run=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),claim_id=NULL "
                + "WHERE id=? AND claim_id=?",task.id(),task.claim());return null;
        });
    }
    public void retryCleanup(Cleanup task) {
        int seconds=(int)Math.min(3600,60L << Math.min(task.attempts(),6));
        jdbc.update("UPDATE upload_cleanup_task SET next_run=DATE_ADD(UTC_TIMESTAMP(),INTERVAL ? SECOND),claim_id=NULL WHERE id=? AND claim_id=?",
            seconds,task.id(),task.claim());
    }
    private String json(Object value) {
        try {return mapper.writeValueAsString(value);} catch(Exception e){throw UploadHttpException.unavailable();}
    }
    private JsonNode parse(String value) {
        try {return mapper.readTree(value);} catch(Exception e){throw UploadHttpException.unavailable();}
    }
    private UploadHttpException failure(Integer status) {
        if(status!=null && status==422) return new UploadHttpException(422,"上传文件未通过病毒检查");
        if(status!=null && status==403) return new UploadHttpException(403,"无权上传");
        return UploadHttpException.unavailable();
    }
}
