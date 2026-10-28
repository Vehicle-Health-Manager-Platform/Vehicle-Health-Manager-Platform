package com.autocare.platform.order;

import com.autocare.platform.file.SignedObjectStore;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** New registration purpose, independent of an existing vehicle, never a certificate authenticity check. */
public final class LicenseRecognition {
    private final ReservationStore db;
    private final SignedObjectStore store;
    private final MileageRecognition.Provider provider;
    private final boolean enabled;
    private final Semaphore permits=new Semaphore(2);
    public LicenseRecognition(ReservationStore db,SignedObjectStore store,MileageRecognition.Provider provider,boolean enabled){this.db=db;this.store=store;this.provider=provider;this.enabled=enabled;}
    static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"请核对图片、新车用途及行驶证识别同意");}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"行驶证识别未配置或暂不可用，请手动填写");}
    static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"本人识别图片不存在或不可用");}
    public static JsonNode input(String raw){
        try{
            if(raw==null||raw.length()>1024)throw invalid();
            var body=CouponInput.parse(raw);CouponInput.exact(body,"file_id","purpose","consent","consent_version");CouponInput.id(body.get("file_id"));
            if(!body.path("purpose").isTextual()||!body.path("purpose").textValue().equals("NEW_VEHICLE")||!body.path("consent").isBoolean()||!body.path("consent").booleanValue()
                    ||!body.path("consent_version").isTextual()||!body.path("consent_version").textValue().equals("NEW_VEHICLE_LICENSE_OCR_V1"))throw invalid();
            return body;
        }catch(RuntimeException e){throw invalid();}
    }
    private Map<String,Object> facts(VehicleOwner owner,long file){
        return db.reads.execute(t->{
            db.owner(owner,false);
            if(!enabled||store==null||provider==null)throw unavailable();
            var rows=db.jdbc.queryForList("SELECT id,owner_id,object_key,content_type,size_bytes,scan_status,updated_at FROM file_object WHERE id=? AND owner_type='user' AND owner_id=? AND is_deleted=0 AND scan_status='CLEAN' AND content_type IN ('image/jpeg','image/png') AND size_bytes BETWEEN 1 AND 4194304",file,owner.id());
            if(rows.isEmpty())throw missing();return rows.get(0);
        });
    }
    private void admit(VehicleOwner owner){
        boolean allowed=db.transactions.execute(t->{
            db.owner(owner,true);String hash=AuthTokens.sha256("owner:"+owner.id());
            var window=ReservationStore.time(Instant.ofEpochSecond(Math.floorDiv(db.now().getEpochSecond(),60)*60));
            db.jdbc.update("DELETE FROM auth_rate_limit WHERE scope='license_ocr' AND key_hash=? AND window_start<?",hash,ReservationStore.time(db.now().minusSeconds(86400)));
            db.jdbc.update("INSERT IGNORE INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES('license_ocr',?,?,0)",hash,window);
            int count=db.jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='license_ocr' AND key_hash=? AND window_start=? FOR UPDATE",Integer.class,hash,window);
            if(count>=5)return false;
            db.jdbc.update("UPDATE auth_rate_limit SET attempts=attempts+1 WHERE scope='license_ocr' AND key_hash=? AND window_start=?",hash,window);return true;
        });
        if(!allowed)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"行驶证查询频繁，请下一分钟重试或手动录车");
    }
    public JsonNode recognize(VehicleOwner owner,JsonNode raw){
        if(raw==null)throw invalid();var input=input(raw.toString());long file=input.path("file_id").longValue();
        var before=facts(owner,file);
        if(!permits.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"行驶证识别繁忙，请稍后重试");
        try{
            admit(owner);JsonNode result=null;
            try{
                if(!store.isPrivate())throw unavailable();String key=before.get("object_key").toString();var stat=store.stat(key);
                if(stat.sizeBytes()!=((Number)before.get("size_bytes")).longValue()||!stat.contentType().equals(before.get("content_type")))throw unavailable();
                byte[] bytes=store.read(key,4194304);if(bytes.length!=stat.sizeBytes())throw unavailable();
                // Recheck before inference as storage access may have taken time.
                if(!before.equals(facts(owner,file)))throw unavailable();
                result=LicenseResult.validate(provider.recognize(bytes));
            }catch(RuntimeException ignored){ /* Do not return certificate text, object keys or upstream diagnostics. */ }
            if(!before.equals(facts(owner,file)))throw new ResponseStatusException(HttpStatus.CONFLICT,"识别图片已变化，请重新上传或选择");
            if(result==null)throw unavailable();
            var out=db.mapper.createObjectNode().put("file_id",file).put("purpose","NEW_VEHICLE").put("source","LOCAL_OCR")
                .put("review_status",result.path("status").textValue()).put("requires_confirmation",true);
            out.set("fields",result.path("fields"));return out;
        }finally{permits.release();}
    }
}
