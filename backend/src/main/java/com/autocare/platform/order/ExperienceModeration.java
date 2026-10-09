package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ExperienceModeration {
    private final ReservationStore db;private final OperatorIdentity operators;private final boolean enabled;
    public ExperienceModeration(ReservationStore db,OperatorIdentity operators,boolean enabled){this.db=db;this.operators=operators;this.enabled=enabled;}
    private void enabled(){if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"经验审核与展示尚未开启");}
    private Long model(Map<String,Object> c,boolean lock){
        var rows=db.jdbc.queryForList("SELECT v.model_id FROM vehicle v JOIN model m ON m.id=v.model_id AND m.is_deleted=0 JOIN series s ON s.id=m.series_id AND s.is_deleted=0 JOIN brand b ON b.id=s.brand_id AND b.is_deleted=0 WHERE v.id=? AND v.user_id=? AND v.is_deleted=0"+(lock?" FOR UPDATE":""),c.get("vehicle_id"),c.get("user_id"));
        return rows.isEmpty()?null:ReservationStore.number(rows.get(0),"model_id");
    }
    private Map<String,Object> summary(Map<String,Object> c){
        // Reuse the private view's fixed field allowlist; never return its source identifiers.
        return (Map<String,Object>)new ExperienceCards(db).view(c).get("summary");
    }
    public Map<String,Object> pending(OperatorActor a,int page,int size){
        ExperienceCards.page(page,size);return db.reads.execute(t->{operators.authorize(a,true,false);enabled();
            var rows=db.jdbc.queryForList("SELECT * FROM experience_card WHERE status='PENDING_REVIEW' AND test_mode=0 AND consent_version=? AND consented_at IS NOT NULL AND withdrawn_at IS NULL ORDER BY id DESC LIMIT ? OFFSET ?",ExperienceCardInput.VERSION,size,(long)(page-1)*size);
            var items=new ArrayList<Map<String,Object>>();for(var c:rows){var item=new LinkedHashMap<String,Object>();item.put("card_id",c.get("id"));item.put("revision",c.get("revision"));item.put("title","施工经验摘要");item.put("summary",summary(c));item.put("model_id",model(c,false));items.add(item);}
            long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM experience_card WHERE status='PENDING_REVIEW' AND test_mode=0 AND consent_version=? AND consented_at IS NOT NULL AND withdrawn_at IS NULL",Long.class,ExperienceCardInput.VERSION);
            return ReservationStore.page(items,count,page,size);
        });
    }
    private static FulfillmentConflict conflict(){return new FulfillmentConflict(45003,"审核版本或授权已变化，请刷新待审列表");}
    private boolean consent(Map<String,Object> c){return ReservationStore.number(c,"test_mode")==0 && ExperienceCardInput.VERSION.equals(c.get("consent_version")) && c.get("consented_at")!=null && c.get("withdrawn_at")==null;}
    public JsonNode moderate(OperatorActor a,long id,String key,JsonNode raw){
        ServiceCatalog.validateId(id);String normalized=WriteIntegrityService.normalizeKey(key);var b=ExperienceModerationInput.validate(raw);
        String path="/api/admin/experience-cards/"+id+"/moderate";
        return db.writes.execute(new Actor("operator_account",a.id()),"POST",path,normalized,b,()->{
            operators.authorize(a,true,true);enabled();var candidate=db.one("SELECT order_id FROM experience_card WHERE id=?",id);
            db.one("SELECT id FROM `order` WHERE id=? FOR UPDATE",candidate.get("order_id"));
            var c=db.one("SELECT * FROM experience_card WHERE id=? FOR UPDATE",id);
            if(!consent(c))throw conflict();
            int requested=b.get("revision").intValue();long revision=ReservationStore.number(c,"revision");
            boolean replay=revision==requested+1L && ("APPROVE".equals(b.get("decision").textValue())?"PUBLISHED":"REJECTED").equals(c.get("status"));
            if(!(revision==requested && "PENDING_REVIEW".equals(c.get("status"))) && !replay)throw conflict();
            if(replay){var review=db.one("SELECT * FROM experience_card_moderation WHERE card_id=? AND request_revision=?",id,requested);
                if(!b.get("decision").textValue().equals(review.get("decision")) || !Objects.equals(b.get("reason_code").isNull()?null:b.get("reason_code").textValue(),review.get("reason_code")))throw conflict();}
            // Withdrawal can always remove invalid sources; approval must revalidate all evidence.
            if("APPROVE".equals(b.get("decision").textValue())){new ExperienceCards(db).trusted(c);if(model(c,true)==null)throw new FulfillmentConflict(45004,"缺少有效车型，不能发布同款经验");}
        },()->{
            var c=db.one("SELECT * FROM experience_card WHERE id=? FOR UPDATE",id);boolean changed=ReservationStore.number(c,"revision")==b.get("revision").longValue();
            String decision=b.get("decision").textValue(),status=decision.equals("APPROVE")?"PUBLISHED":"REJECTED";
            var before=Map.<String,Object>of("status",c.get("status"),"revision",c.get("revision"));
            if(changed){var reason=b.get("reason_code").isNull()?null:b.get("reason_code").textValue();Long model=decision.equals("APPROVE")?model(c,true):null;
                db.jdbc.update("INSERT INTO experience_card_moderation(card_id,request_revision,decision,reason_code,model_id,public_id,operator_id,decided_at) VALUES(?,?,?,?,?,?,?,?)",id,b.get("revision").intValue(),decision,reason,model,decision.equals("APPROVE")?UUID.randomUUID().toString():null,a.id(),ReservationStore.time(db.now()));
                db.jdbc.update("UPDATE experience_card SET status=?,revision=revision+1 WHERE id=?",status,id);
            }
            var after=db.one("SELECT status,revision FROM experience_card WHERE id=?",id);
            var data=new LinkedHashMap<String,Object>();data.put("card_id",id);data.putAll(after);data.put("decision",decision);data.put("reason_code",b.get("reason_code").isNull()?null:b.get("reason_code").textValue());
            return new Change("EXPERIENCE_CARD_MODERATE","experience_card",id,before,after,data,changed);
        });
    }
    public Map<String,Object> experiences(VehicleOwner owner,long vehicle,Long cursor){
        ServiceCatalog.validateId(vehicle);if(cursor!=null)ServiceCatalog.validateId(cursor);
        return db.reads.execute(t->{db.owner(owner,false);var v=db.one("SELECT model_id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0",vehicle,owner.id());
            var out=new LinkedHashMap<String,Object>();out.put("items",List.of());out.put("next_cursor",null);
            if(!enabled || v.get("model_id")==null)return out;
            var rows=db.jdbc.queryForList("SELECT c.*,m.id AS moderation_id,m.model_id AS approved_model,m.public_id,m.decided_at FROM experience_card_moderation m JOIN experience_card c ON c.id=m.card_id JOIN vehicle v ON v.id=c.vehicle_id AND v.user_id=c.user_id AND v.model_id=m.model_id AND v.is_deleted=0 JOIN user u ON u.id=c.user_id AND u.status=1 AND u.is_deleted=0 JOIN model md ON md.id=m.model_id AND md.is_deleted=0 JOIN series s ON s.id=md.series_id AND s.is_deleted=0 JOIN brand b ON b.id=s.brand_id AND b.is_deleted=0 WHERE m.decision='APPROVE' AND m.model_id=? AND c.status='PUBLISHED' AND c.test_mode=0 AND c.revision=m.request_revision+1 AND c.consent_version=? AND c.consented_at IS NOT NULL AND c.withdrawn_at IS NULL AND m.id<? ORDER BY m.id DESC LIMIT 21",v.get("model_id"),ExperienceCardInput.VERSION,cursor==null?Long.MAX_VALUE:cursor);
            var items=new ArrayList<Map<String,Object>>();int count=Math.min(20,rows.size());
            for(int n=0;n<count;n++){var c=rows.get(n);try{new ExperienceCards(db).trusted(c);}catch(FulfillmentConflict e){continue;}
                items.add(Map.of("experience_id",c.get("public_id"),"title","施工经验摘要","summary",summary(c),"model_id",c.get("approved_model"),"published_at",ReservationStore.iso(c.get("decided_at"))));}
            out.put("items",items);out.put("next_cursor",rows.size()>20?rows.get(19).get("moderation_id"):null);return out;
        });
    }
}
