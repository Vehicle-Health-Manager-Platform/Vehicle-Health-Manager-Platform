package com.autocare.platform.order;

import com.autocare.platform.file.SignedObjectStore;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Read-only candidates. File ownership does not prove what the photographed instrument belongs to. */
public final class MileageRecognition {
    public interface Provider { JsonNode recognize(byte[] image); }
    private final ReservationStore db;
    private final SignedObjectStore store;
    private final Provider provider;
    private final boolean enabled;
    private final Semaphore permits=new Semaphore(2);
    public MileageRecognition(ReservationStore db,SignedObjectStore store,Provider provider,boolean enabled){this.db=db;this.store=store;this.provider=provider;this.enabled=enabled;}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"里程识别暂不可用，请手动填写");}
    static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"请核对车辆、图片及识别用途确认");}
    public static JsonNode input(String raw){
        try{if(raw==null||raw.length()>4096)throw invalid();var b=CouponInput.parse(raw);CouponInput.exact(b,"vehicle_id","file_id","consent","consent_version");CouponInput.id(b.get("vehicle_id"));CouponInput.id(b.get("file_id"));
            if(!b.path("consent").isBoolean()||!b.path("consent").asBoolean()||!b.path("consent_version").isTextual()||!"MILEAGE_OCR_V1".equals(b.path("consent_version").asText()))throw invalid();return b;
        }catch(RuntimeException e){throw invalid();}
    }
    private Map<String,Object> facts(VehicleOwner owner,long vehicle,long file){
        return db.reads.execute(t->{db.owner(owner,false);
            if(!enabled||store==null||provider==null)throw unavailable();
            var v=db.one("SELECT id,user_id,updated_at FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0",vehicle,owner.id());
            var f=db.one("SELECT id,object_key,content_type,size_bytes,scan_status,updated_at FROM file_object WHERE id=? AND owner_type='user' AND owner_id=? AND is_deleted=0 AND scan_status='CLEAN' AND content_type IN ('image/jpeg','image/png') AND size_bytes BETWEEN 1 AND 4194304",file,owner.id());
            return Map.of("vehicle",v,"file",f);
        });
    }
    public Map<String,Object> recognize(VehicleOwner owner,JsonNode input){
        input=input(input.toString());
        long vehicle=input.path("vehicle_id").asLong(),file=input.path("file_id").asLong();
        var before=facts(owner,vehicle,file);
        if(!permits.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"识别繁忙，请稍后重试");
        try{
            var f=(Map<?,?>)before.get("file");byte[] bytes;
            try{if(!store.isPrivate())throw unavailable();var stat=store.stat(f.get("object_key").toString());
                if(stat.sizeBytes()!=((Number)f.get("size_bytes")).longValue()||!stat.contentType().equals(f.get("content_type")))throw unavailable();
                bytes=store.read(f.get("object_key").toString(),4194304);if(bytes.length!=stat.sizeBytes())throw unavailable();
            }catch(RuntimeException e){throw unavailable();}
            JsonNode result;
            try{result=validate(provider.recognize(bytes));}catch(RuntimeException e){throw unavailable();}
            if(!before.equals(facts(owner,vehicle,file)))throw new ResponseStatusException(HttpStatus.CONFLICT,"车辆或图片已变化，请重新选择");
            var fields=new ArrayList<Map<String,Object>>();
            if(Set.of("CANDIDATE","LOW_CONFIDENCE").contains(result.path("status").asText())){
                var row=result.path("candidates").get(0);fields.add(Map.of("key","mileage","value",Integer.toString(row.path("mileage_km").asInt()),"confidence",row.path("confidence").doubleValue()));
            }
            return Map.of("vehicle_id",vehicle,"file_id",file,"review_status",result.path("status").asText(),"requires_confirmation",true,
                "recognition",Map.of("version",1,"scene","archive","status",fields.isEmpty()?"EMPTY":"CANDIDATE","fields",fields));
        }finally{permits.release();}
    }
    static JsonNode validate(JsonNode n){
        CouponInput.exact(n,"version","scene","status","candidates","requires_confirmation","image_recognition_performed");
        if(!n.path("version").isIntegralNumber()||!n.path("version").canConvertToInt()||n.path("version").asInt()!=1||!n.path("scene").isTextual()||!n.path("scene").asText().equals("mileage")
            ||!n.path("requires_confirmation").isBoolean()||!n.path("requires_confirmation").asBoolean()||!n.path("image_recognition_performed").isBoolean()||!n.path("image_recognition_performed").asBoolean())throw unavailable();
        String status=n.path("status").asText();var rows=n.path("candidates");
        if(!Set.of("CANDIDATE","LOW_CONFIDENCE","AMBIGUOUS","NO_CANDIDATE").contains(status)||!rows.isArray()||rows.size()>100)throw unavailable();
        var seen=new HashSet<Integer>();
        for(var row:rows){CouponInput.exact(row,"mileage_km","confidence");var value=row.path("mileage_km");var confidence=row.path("confidence");
            if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<0||value.asInt()>2000000||!seen.add(value.asInt())||!confidence.isNumber()||!Double.isFinite(confidence.doubleValue())||confidence.doubleValue()<0||confidence.doubleValue()>1)throw unavailable();}
        if(status.equals("NO_CANDIDATE")?rows.size()!=0:rows.size()==0)throw unavailable();
        if(Set.of("CANDIDATE","LOW_CONFIDENCE").contains(status)&&(rows.size()!=1||(status.equals("CANDIDATE")?rows.get(0).path("confidence").doubleValue()<.9:rows.get(0).path("confidence").doubleValue()>=.9)))throw unavailable();
        return n;
    }
}
