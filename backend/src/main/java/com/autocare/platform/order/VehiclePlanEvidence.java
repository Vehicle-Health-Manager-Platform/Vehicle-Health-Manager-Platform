package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.vehicle.*;
import com.autocare.platform.service.ServiceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Private evidence stays local; applicability is an explicit, versioned operator declaration. */
public class VehiclePlanEvidence {
    private final ReservationStore db;private final OperatorIdentity operators;private final HealthObservationService observations;private final ExperienceModeration experiences;
    public VehiclePlanEvidence(ReservationStore db,OperatorIdentity operators,HealthObservationService observations,ExperienceModeration experiences){this.db=db;this.operators=operators;this.observations=observations;this.experiences=experiences;}
    record Request(long vehicle,List<Long> projects) {}
    static Request request(String raw){var b=PublicReviewTextInput.parse(raw);PublicReviewTextInput.fields(b,"vehicle_id","project_ids");long vehicle=ReservationInput.id(b.get("vehicle_id"));var ids=b.get("project_ids");if(!ids.isArray()||ids.size()>8)throw ReservationInput.bad();var projects=new ArrayList<Long>();for(var n:ids){long id=ReservationInput.id(n);if(projects.contains(id))throw ReservationInput.bad();projects.add(id);}return new Request(vehicle,List.copyOf(projects));}
    static JsonNode ruleInput(String raw){var b=PublicReviewTextInput.parse(raw);PublicReviewTextInput.fields(b,"verdict","source_reference","expected_version");PublicReviewTextInput.version(b.get("expected_version"));String verdict=b.path("verdict").asText();if(!Set.of("APPLICABLE","NOT_APPLICABLE","WITHDRAW").contains(verdict))throw ReservationInput.bad();var source=b.get("source_reference");if(verdict.equals("WITHDRAW")){if(!source.isNull())throw ReservationInput.bad();}else{if(!source.isTextual())throw ReservationInput.bad();String s=source.textValue();if(s.isBlank()||!s.equals(s.strip())||s.codePointCount(0,s.length())>256||s.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.SURROGATE||Character.getType(c)==Character.FORMAT))throw ReservationInput.bad();}return b;}
    private static final String MODEL="SELECT md.id,md.series_id,s.brand_id FROM model md JOIN series s ON s.id=md.series_id JOIN brand b ON b.id=s.brand_id WHERE md.id=? AND md.status=1 AND md.is_deleted=0 AND s.status=1 AND s.is_deleted=0 AND b.status=1 AND b.is_deleted=0";
    private void catalog(long project,long model,boolean lock){ServiceCatalog.validateId(project);ServiceCatalog.validateId(model);db.one("SELECT id FROM standard_project WHERE id=? AND status=1 AND is_deleted=0"+(lock?" FOR UPDATE":""),project);var initial=db.one(MODEL,model);
        if(lock){db.one("SELECT id FROM brand WHERE id=? FOR UPDATE",initial.get("brand_id"));db.one("SELECT id FROM series WHERE id=? FOR UPDATE",initial.get("series_id"));db.one("SELECT id FROM model WHERE id=? FOR UPDATE",model);var current=db.one(MODEL+" FOR UPDATE",model);if(!initial.equals(current))throw conflict();}
    }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"车型、项目或规则版本已变化，请刷新确认");}
    private Map<String,Object> stored(long p,long m){var rows=db.jdbc.queryForList("SELECT verdict,source_reference,version FROM project_model_applicability WHERE project_id=? AND model_id=?",p,m);return rows.isEmpty()?null:rows.get(0);}
    private Map<String,Object> state(long p,long m){var row=stored(p,m);var result=new LinkedHashMap<String,Object>();result.put("project_id",p);result.put("model_id",m);result.put("version",row==null?0L:ReservationStore.number(row,"version"));result.put("rule",row==null||row.get("verdict")==null?null:Map.of("verdict",row.get("verdict"),"source_reference",row.get("source_reference")));return result;}
    public Map<String,Object> readRule(OperatorActor actor,long project,long model){return db.reads.execute(tx->{operators.authorize(actor,OperatorIdentity.Permission.CATALOG,false);catalog(project,model,false);return state(project,model);});}
    public JsonNode writeRule(OperatorActor actor,long project,long model,String key,JsonNode raw){var b=ruleInput(raw.toString());return db.writes.execute(new Actor("operator_account",actor.id()),"PUT","/api/admin/project-applicability/"+project+"/"+model,key,b,
        ()->{operators.authorize(actor,OperatorIdentity.Permission.CATALOG,true);catalog(project,model,true);},
        ()->{var before=state(project,model);long version=((Number)before.get("version")).longValue();if(version!=b.get("expected_version").longValue()||version>=9007199254740991L)throw conflict();boolean clear=b.get("verdict").textValue().equals("WITHDRAW");
            db.jdbc.update("INSERT INTO project_model_applicability(project_id,model_id,verdict,source_reference,version,operator_id) VALUES(?,?,?,?,1,?) ON DUPLICATE KEY UPDATE verdict=VALUES(verdict),source_reference=VALUES(source_reference),version=version+1,operator_id=VALUES(operator_id)",project,model,clear?null:b.get("verdict").textValue(),clear?null:b.get("source_reference").textValue(),actor.id());
            var after=state(project,model);return new Change("PROJECT_MODEL_APPLICABILITY","standard_project",project,before,after,after);
        });}
    public Map<String,Object> evidence(VehicleOwner owner,Request in){return db.reads.execute(tx->{db.owner(owner,false);
        var v=db.one("SELECT v.id,v.power_type,c.id AS model_id,c.model_name FROM vehicle v LEFT JOIN (SELECT md.id,CONCAT_WS(' ',b.name,s.name,md.year,md.config_name) AS model_name FROM model md JOIN series s ON s.id=md.series_id AND s.status=1 AND s.is_deleted=0 JOIN brand b ON b.id=s.brand_id AND b.status=1 AND b.is_deleted=0 WHERE md.status=1 AND md.is_deleted=0) c ON c.id=v.model_id WHERE v.id=? AND v.user_id=? AND v.is_deleted=0",in.vehicle(),owner.id());
        var vehicle=new LinkedHashMap<String,Object>();vehicle.put("vehicle_id",in.vehicle());vehicle.put("model_id",v.get("model_id"));vehicle.put("model_name",v.get("model_name"));vehicle.put("model_source","OWNER_REGISTERED");vehicle.put("power_type",v.get("power_type"));
        var rules=new ArrayList<Map<String,Object>>();for(long project:in.projects()){db.one("SELECT id FROM standard_project WHERE id=? AND status=1 AND is_deleted=0",project);var row=v.get("model_id")==null?null:stored(project,ReservationStore.number(v,"model_id"));var item=new LinkedHashMap<String,Object>();item.put("project_id",project);boolean known=row!=null&&row.get("verdict")!=null;item.put("verdict",known?row.get("verdict"):"UNKNOWN");item.put("rule_version",known?row.get("version"):0L);item.put("source_reference",known?row.get("source_reference"):null);rules.add(item);}
        var empty=new LinkedHashMap<String,Object>();empty.put("items",List.of());empty.put("next_cursor",null);
        Object examples=v.get("model_id")==null?empty:experiences.experiences(owner,in.vehicle(),null);
        return Map.of("vehicle",vehicle,"observations",observations.list(owner,in.vehicle()),"experiences",examples,"applicability",rules);
    });}
}
