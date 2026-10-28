package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.file.*;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Durable candidate work. No transaction spans object-store or provider I/O. */
public class CandidateJobs {
    enum Kind { ASR, DOCUMENT }
    private final Kind kind;
    private final String PATH;
    private final ReservationStore db;private final boolean enabled;
    CandidateJobs(ReservationStore db,boolean enabled,Kind kind){this.db=db;this.enabled=enabled;this.kind=kind;this.PATH=kind==Kind.ASR?"/api/recognition/asr/jobs":"/api/recognition/documents/jobs";}
    static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"请核对当前车辆、本人材料及识别用途同意");}
    static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"车辆、材料或识别任务不可用");}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"识别服务未配置或暂不可用，请手动填写");}
    static JsonNode input(String raw,Kind kind){try{
        if(raw==null||raw.length()>2048)throw bad();var b=CouponInput.parse(raw);CouponInput.exact(b,"vehicle_id","material_id","purpose","consent","consent_version");
        CouponInput.id(b.get("vehicle_id"));CouponInput.id(b.get("material_id"));
        if(!b.path("purpose").isTextual()||!Set.of("AI","ARCHIVE").contains(b.path("purpose").textValue())||!b.path("consent").isBoolean()||!b.path("consent").booleanValue()||!b.path("consent_version").isTextual()||!b.path("consent_version").textValue().equals(kind==Kind.ASR?"ASR_TRANSCRIBE_V1":"DOCUMENT_EXTRACT_V1"))throw bad();return b;
    }catch(RuntimeException e){throw bad();}}
    public static void cancelInput(String raw){try{if(raw==null||raw.length()>32)throw bad();CouponInput.exact(CouponInput.parse(raw));}catch(RuntimeException e){throw bad();}}
    private void vehicle(long owner,long vehicle,boolean lock){
        if(db.jdbc.queryForList("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),vehicle,owner).isEmpty())throw missing();
    }
    private Map<String,Object> source(long owner,long vehicle,long material,String purpose,boolean lock){
        vehicle(owner,vehicle,lock);
        var rows=db.jdbc.queryForList("SELECT id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status,created_at FROM input_material WHERE id=? AND owner_id=? AND vehicle_id=? AND purpose=? AND scan_status='CLEAN' AND is_deleted=0 AND "+(kind==Kind.ASR?"content_type='audio/mpeg' AND size_bytes BETWEEN 1 AND 3145728 AND duration_ms BETWEEN 1000 AND 60000 AND pages=0":"(duration_ms=0 AND ((content_type='application/pdf' AND size_bytes BETWEEN 1 AND 10485760 AND pages BETWEEN 1 AND 50) OR (content_type IN ('image/jpeg','image/png') AND size_bytes BETWEEN 1 AND 4194304 AND pages=1)))")+(lock?" FOR UPDATE":""),material,owner,vehicle,purpose);
        if(rows.isEmpty())throw missing();return rows.get(0);
    }
    private String hash(Map<String,Object> material){
        var sorted=new TreeMap<String,String>();material.forEach((k,v)->sorted.put(k,Objects.toString(v,"")));
        return AuthTokens.sha256(db.json(sorted));
    }
    private Map<String,Object> owned(VehicleOwner owner,long id,boolean lock){
        if(id<1||id>=9007199254740991L)throw bad();var rows=db.jdbc.queryForList("SELECT * FROM recognition_job WHERE id=? AND owner_id=? AND task=?"+(lock?" FOR UPDATE":""),id,owner.id(),kind.name());
        if(rows.isEmpty())throw missing();return rows.get(0);
    }
    private long number(Map<String,Object> row,String key){return ReservationStore.number(row,key);}
    private boolean originalActive(Map<String,Object> row){
        if(!ReservationStore.instant(row.get("origin_expires_at")).isAfter(db.now()))return false;
        return !db.jdbc.queryForList("SELECT s.id FROM auth_session s JOIN user u ON u.id=s.subject_id WHERE s.id=? AND s.subject_id=? AND s.subject_type='user' AND s.role='OWNER' AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP() AND u.status=1 AND u.is_deleted=0",row.get("origin_session_id"),row.get("owner_id")).isEmpty();
    }
    private Map<String,Object> jobSource(Map<String,Object> job){return source(number(job,"owner_id"),number(job,"vehicle_id"),number(job,"material_id"),job.get("purpose").toString(),false);}
    private String invalidation(Map<String,Object> job){
        if(!ReservationStore.instant(job.get("expires_at")).isAfter(db.now()))return "EXPIRED";
        if(!enabled)return "FEATURE_DISABLED";
        if(!originalActive(job))return "AUTH_REVOKED";
        if(job.get("state").equals("RUNNING")&&!ReservationStore.instant(job.get("lease_until")).isAfter(db.now()))return "WORKER_LOST";
        try{if(!hash(jobSource(job)).equals(job.get("source_hash")))return "SOURCE_CHANGED";}catch(ResponseStatusException e){return "SOURCE_CHANGED";}
        return null;
    }
    private static String invalidState(String code){return code.equals("EXPIRED")?"EXPIRED":Set.of("AUTH_REVOKED","FEATURE_DISABLED").contains(code)?"CANCELLED":"FAILED";}
    private void invalidate(long id,String code){db.jdbc.update("UPDATE recognition_job SET state=?,error_code=?,result_json=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",invalidState(code),code,id);}
    private void rate(VehicleOwner owner){
        var window=ReservationStore.time(Instant.ofEpochSecond(Math.floorDiv(db.now().getEpochSecond(),60)*60));String key=AuthTokens.sha256("owner:"+owner.id());String rateScope=kind==Kind.ASR?"asr_create":"document_create";
        db.jdbc.update("DELETE FROM auth_rate_limit WHERE scope=? AND key_hash=? AND window_start<?",rateScope,key,ReservationStore.time(db.now().minusSeconds(86400)));
        db.jdbc.update("INSERT IGNORE INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES(?,?,?,0)",rateScope,key,window);
        int count=db.jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope=? AND key_hash=? AND window_start=? FOR UPDATE",Integer.class,rateScope,key,window);
        int active=db.jdbc.queryForObject("SELECT COUNT(*) FROM recognition_job WHERE owner_id=? AND state IN ('QUEUED','RUNNING') AND expires_at>?",Integer.class,owner.id(),ReservationStore.time(db.now()));
        if(count>=5||active>=2)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"识别任务频繁或已有两个任务，请稍后重试");
        db.jdbc.update("UPDATE auth_rate_limit SET attempts=attempts+1 WHERE scope=? AND key_hash=? AND window_start=?",rateScope,key,window);
    }
    public JsonNode create(VehicleOwner owner,JsonNode request,String key){
        var body=input(request==null?null:request.toString(),kind);WriteIntegrityService.normalizeKey(key);
        return db.writes.execute(new WriteIntegrityService.Actor("user",owner.id()),"POST",PATH,key,body,()->{
            db.owner(owner,true);if(!enabled)throw unavailable();source(owner.id(),body.path("vehicle_id").longValue(),body.path("material_id").longValue(),body.path("purpose").textValue(),true);
        },()->{
            rate(owner);var material=source(owner.id(),body.path("vehicle_id").longValue(),body.path("material_id").longValue(),body.path("purpose").textValue(),true);
            long id=db.insert("INSERT INTO recognition_job(owner_id,vehicle_id,material_id,purpose,origin_session_id,origin_expires_at,source_hash,expires_at,task) VALUES(?,?,?,?,?,?,?,?,?)",owner.id(),body.path("vehicle_id").longValue(),body.path("material_id").longValue(),body.path("purpose").textValue(),owner.session(),ReservationStore.time(owner.expires()),hash(material),ReservationStore.time(db.now().plusSeconds(600)),kind.name());
            var data=Map.<String,Object>of("job_id",id,"vehicle_id",body.path("vehicle_id").longValue(),"material_id",body.path("material_id").longValue(),"purpose",body.path("purpose").textValue(),"task",kind.name());
            return new WriteIntegrityService.Change(kind.name()+"_JOB_CREATE","recognition_job",id,Map.of(),data,data);
        });
    }
    public JsonNode get(VehicleOwner owner,long id){return db.transactions.execute(t->{
        db.owner(owner,true);var row=owned(owner,id,true);vehicle(owner.id(),number(row,"vehicle_id"),false);
        String state=row.get("state").toString();
        if(!Set.of("CANCELLED","EXPIRED").contains(state)){
            String code=invalidation(row);if(code!=null){invalidate(id,code);row=owned(owner,id,true);}
        }else if(!ReservationStore.instant(row.get("expires_at")).isAfter(db.now())&&!state.equals("EXPIRED")){invalidate(id,"EXPIRED");row=owned(owner,id,true);}
        var out=db.mapper.createObjectNode().put("job_id",id).put("vehicle_id",number(row,"vehicle_id")).put("material_id",number(row,"material_id")).put("purpose",row.get("purpose").toString()).put("task",kind.name())
            .put("state",row.get("state").toString()).put("expires_at",ReservationStore.iso(row.get("expires_at")));
        if(row.get("error_code")==null)out.putNull("error_code");else out.put("error_code",row.get("error_code").toString());
        JsonNode result=null;
        if(row.get("state").equals("SUCCEEDED"))try{result=validateResult(db.parse(row.get("result_json")),jobSource(row),Objects.toString(row.get("content_sha256"),""));}catch(RuntimeException e){invalidate(id,"INVALID_RESULT");out.put("state","FAILED").put("error_code","INVALID_RESULT");}
        if(result==null)out.putNull("result");else out.set("result",result);return out;
    });}
    public JsonNode cancel(VehicleOwner owner,long id,String key){
        return db.writes.execute(new WriteIntegrityService.Actor("user",owner.id()),"POST",PATH+"/"+id+"/cancel",key,db.mapper.createObjectNode(),()->{db.owner(owner,true);owned(owner,id,true);},()->{
            var row=owned(owner,id,true);String before=row.get("state").toString();
            if(!before.equals("EXPIRED"))db.jdbc.update("UPDATE recognition_job SET state='CANCELLED',error_code='USER_CANCELLED',result_json=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",id);
            var data=Map.<String,Object>of("job_id",id);return new WriteIntegrityService.Change(kind.name()+"_JOB_CANCEL","recognition_job",id,Map.of("state",before),Map.of("state",before.equals("EXPIRED")?"EXPIRED":"CANCELLED"),data);
        });
    }
    public void cleanup(){db.transactions.executeWithoutResult(t->{
        db.jdbc.update("UPDATE recognition_job SET state='EXPIRED',error_code='EXPIRED',result_json=NULL,lease_token=NULL,lease_until=NULL WHERE task=? AND expires_at<=? AND state<>'EXPIRED' LIMIT 100",kind.name(),ReservationStore.time(db.now()));
        db.jdbc.update("UPDATE recognition_job SET state='FAILED',error_code='WORKER_LOST',result_json=NULL,lease_token=NULL,lease_until=NULL WHERE task=? AND state='RUNNING' AND lease_until<=? LIMIT 100",kind.name(),ReservationStore.time(db.now()));
    });}
    Map<String,Object> claim(){if(!enabled)return null;cleanup();return db.transactions.execute(t->{
        var rows=db.jdbc.queryForList("SELECT * FROM recognition_job WHERE task=? AND state='QUEUED' AND expires_at>? ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",kind.name(),ReservationStore.time(db.now()));
        if(rows.isEmpty())return null;var row=rows.get(0);String code=invalidation(row);if(code!=null){invalidate(number(row,"id"),code);return null;}
        String token=UUID.randomUUID().toString();db.jdbc.update("UPDATE recognition_job SET state='RUNNING',lease_token=?,lease_until=? WHERE id=? AND state='QUEUED'",token,ReservationStore.time(db.now().plusSeconds(180)),row.get("id"));
        return db.jdbc.queryForMap("SELECT * FROM recognition_job WHERE id=?",row.get("id"));
    });}
    private void finish(Map<String,Object> claimed,JsonNode result,String error,String contentHash){db.transactions.executeWithoutResult(t->{
        var rows=db.jdbc.queryForList("SELECT * FROM recognition_job WHERE id=? FOR UPDATE",claimed.get("id"));if(rows.isEmpty())return;var current=rows.get(0);
        if(!current.get("state").equals("RUNNING")||!Objects.equals(current.get("lease_token"),claimed.get("lease_token")))return;
        if(!ReservationStore.instant(current.get("lease_until")).isAfter(db.now())){invalidate(number(current,"id"),"WORKER_LOST");return;}
        String code=invalidation(current);if(code!=null){invalidate(number(current,"id"),code);return;}
        db.jdbc.update("UPDATE recognition_job SET state=?,error_code=?,result_json=?,content_sha256=?,lease_token=NULL,lease_until=NULL WHERE id=? AND state='RUNNING' AND lease_token=?",result==null?"FAILED":"SUCCEEDED",result==null?error:null,result==null?null:db.json(result),contentHash,current.get("id"),claimed.get("lease_token"));
    });}
    interface Provider {JsonNode recognize(byte[] bytes,String contentType,int duration,int pages);}
    private JsonNode validateResult(JsonNode result,Map<String,Object> material,String hash){return kind==Kind.ASR?AsrResult.validate(result,((Number)material.get("duration_ms")).intValue()):DocumentResult.validate(result,material.get("content_type").toString(),hash,((Number)material.get("pages")).intValue());}
    boolean workCandidate(SignedObjectStore store,Provider provider){
        var job=claim();if(job==null)return false;JsonNode result=null;String failure="UNAVAILABLE",contentHash=null;
        try{
            var material=db.reads.execute(t->{String code=invalidation(job);if(code!=null)throw missing();return jobSource(job);});
            if(store==null||provider==null||!store.isPrivate())throw unavailable();String object=material.get("object_key").toString();var stat=store.stat(object);
            String contentType=material.get("content_type").toString();if(!contentType.equals(stat.contentType())||stat.sizeBytes()!=number(material,"size_bytes"))throw unavailable();
            int maximum=kind==Kind.ASR?3145728:contentType.equals("application/pdf")?10485760:4194304;byte[] bytes=store.read(object,maximum);if(bytes.length!=stat.sizeBytes())throw unavailable();
            int duration=((Number)material.get("duration_ms")).intValue();int pages=((Number)material.get("pages")).intValue();
            if(kind==Kind.ASR){var checked=new InputMaterialValidator().validate("source.mp3",new ByteArrayInputStream(bytes));if(checked.durationMs()!=duration)throw unavailable();}
            else if(pages>4)throw new DocumentProvider.Failed("LIMIT_EXCEEDED");
            contentHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            // Storage I/O must not allow a revoked session or changed source into inference.
            db.reads.executeWithoutResult(t->{if(invalidation(job)!=null)throw missing();});
            JsonNode output=provider.recognize(bytes,contentType,duration,pages);
            try{result=validateResult(output,material,contentHash);}catch(RuntimeException e){failure="INVALID_RESULT";}
        }catch(DocumentProvider.Failed error){failure=kind==Kind.DOCUMENT?error.code():"UNAVAILABLE";}catch(Exception ignored){/* Safe failure only: never log source bytes, transcript or URLs. */}
        finish(job,result,failure,contentHash);return true;
    }
}
