package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class HealthObservationService {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    private final TransactionTemplate reads;
    private final Clock clock;
    public HealthObservationService(JdbcTemplate jdbc,WriteIntegrityService integrity,ObjectMapper mapper,PlatformTransactionManager manager,Clock clock){
        this.jdbc=jdbc;this.integrity=integrity;this.mapper=mapper;this.clock=clock;
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setTimeout(15);
    }
    public Map<String,Object> context(VehicleOwner owner,long archive,String metric){
        HealthObservationInput.metric(metric);
        return reads.execute(tx->{
            authorize(owner,false);var source=source(archive);ownVehicle(owner,source.vehicle(),false);
            var result=new LinkedHashMap<String,Object>();result.put("vehicle_id",source.vehicle());result.put("archive_id",archive);
            result.put("archive_type",source.type());result.put("recorded_date",source.recorded());result.put("metric",metric);result.put("today",today().toString());
            result.put("observation",view(slot(owner.id(),source.vehicle(),metric,false),metric));return result;
        });
    }
    public Map<String,Object> list(VehicleOwner owner,long vehicle){
        return reads.execute(tx->{
            authorize(owner,false);ownVehicle(owner,vehicle,false);var rows=new ArrayList<Map<String,Object>>();
            for(String metric:HealthObservationInput.METRICS)rows.add(view(slot(owner.id(),vehicle,metric,false),metric));
            return Map.of("vehicle_id",vehicle,"today",today().toString(),"list",rows);
        });
    }
    public JsonNode save(VehicleOwner owner,long archive,String key,HealthObservationInput input){
        final Source[] selected=new Source[1];
        return integrity.execute(new WriteIntegrityService.Actor("user",owner.id()),"PUT","/api/archive/"+archive+"/health-observation",key,
            mapper.valueToTree(input.canonical()),()->{
                authorize(owner,true);var initial=source(archive);ownVehicle(owner,initial.vehicle(),true);selected[0]=source(archive,true);
                if(!initial.equals(selected[0]))throw conflict();input.validate(today());
            },()->{
                var source=selected[0];var before=slot(owner.id(),source.vehicle(),input.metric(),true);
                if(input.revision()!=(before==null?0:before.revision()))throw conflict();
                boolean clear=input.measurement().isEmpty();
                if(clear&&(before==null||before.measurement().isEmpty()||before.archive()!=archive))throw conflict();
                if(!clear&&before!=null&&!before.measurement().isEmpty()&&before.archive()!=archive&&!input.replaceSource())throw conflict();
                if(input.operation().equals("CORRECT")&&(before==null||before.measurement().isEmpty()||before.archive()!=archive||before.type()!=source.type()||input.replaceSource()))throw conflict();
                int next=input.revision()+1;
                if(before==null)jdbc.update("INSERT INTO vehicle_health_observation(user_id,vehicle_id,metric,archive_id,archive_type,measurement_json,revision) VALUES (?,?,?,?,?,?,?)",
                    owner.id(),source.vehicle(),input.metric(),archive,source.type(),json(input.measurement()),next);
                else jdbc.update("UPDATE vehicle_health_observation SET archive_id=?,archive_type=?,measurement_json=?,revision=? WHERE id=?",
                    archive,source.type(),json(input.measurement()),next,before.id());
                jdbc.update("INSERT INTO vehicle_health_observation_history(user_id,vehicle_id,metric,archive_id,archive_type,revision,operation,supersedes_revision,measurement_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                    owner.id(),source.vehicle(),input.metric(),archive,source.type(),next,input.operation(),input.operation().equals("CORRECT")?input.revision():null,json(input.measurement()));
                var after=slot(owner.id(),source.vehicle(),input.metric(),true);
                return new WriteIntegrityService.Change(clear?"HEALTH_OBSERVATION_CLEAR":input.operation().equals("CORRECT")?"HEALTH_OBSERVATION_CORRECT":"HEALTH_OBSERVATION_SET","vehicle_health_observation",after.id(),
                    before==null?Map.of():audit(before),audit(after),Map.of("vehicle_id",source.vehicle(),"archive_id",archive,"metric",input.metric(),"revision",next,
                        "status",clear?"NOT_SET":"RECORDED_UNVERIFIED"));
            });
    }
    public Map<String,Object> history(VehicleOwner owner,long vehicle,String metric,Integer before,int limit){
        HealthObservationInput.metric(metric);
        if(limit<1||limit>50||before!=null&&before<1)throw WriteIntegrityService.badRequest("历史分页参数无效");
        return reads.execute(tx->{
            authorize(owner,false);ownVehicle(owner,vehicle,false);
            var rows=jdbc.query("SELECT h.archive_id,h.archive_type,h.revision,h.measurement_json,h.operation,h.supersedes_revision,DATE_FORMAT(h.created_at,'%Y-%m-%dT%H:%i:%sZ') AS saved_at,"+
                "EXISTS(SELECT 1 FROM vehicle_health_observation_history c WHERE c.user_id=h.user_id AND c.vehicle_id=h.vehicle_id AND c.metric=h.metric AND c.supersedes_revision=h.revision AND c.is_deleted=0) AS corrected "+
                "FROM vehicle_health_observation_history h WHERE h.user_id=? AND h.vehicle_id=? AND h.metric=? AND h.is_deleted=0"+
                (before==null?"":" AND h.revision<?")+" ORDER BY h.revision DESC LIMIT ?",
                (rs,n)->{
                    var value=view(new Slot(0,vehicle,rs.getLong("archive_id"),rs.getInt("archive_type"),rs.getInt("revision"),read(rs.getString("measurement_json"))),metric);
                    value.put("operation",rs.getString("operation"));value.put("supersedes_revision",rs.getObject("supersedes_revision",Integer.class));
                    value.put("saved_at",rs.getString("saved_at"));value.put("corrected",rs.getBoolean("corrected"));return value;
                },before==null?new Object[]{owner.id(),vehicle,metric,limit+1}:new Object[]{owner.id(),vehicle,metric,before,limit+1});
            boolean more=rows.size()>limit;var page=new ArrayList<>(rows.subList(0,Math.min(rows.size(),limit)));
            var result=new LinkedHashMap<String,Object>();result.put("vehicle_id",vehicle);result.put("metric",metric);result.put("today",today().toString());
            result.put("list",page);result.put("has_more",more);result.put("next_before_revision",more?page.get(page.size()-1).get("revision"):null);return result;
        });
    }
    private Map<String,Object> audit(Slot row){return Map.of("archive_id",row.archive(),"revision",row.revision(),"present",!row.measurement().isEmpty());}
    private Map<String,Object> view(Slot row,String metric){
        boolean present=row!=null&&!row.measurement().isEmpty();
        var sources=present?jdbc.query("SELECT DATE_FORMAT(recorded_at,'%Y-%m-%d') FROM vehicle_archive WHERE id=? AND vehicle_id=? AND archive_type=? AND input_type IN (1,3) AND is_deleted=0",
            (rs,n)->rs.getString(1),row.archive(),row.vehicle(),row.type()):List.<String>of();
        boolean valid=present&&sources.size()==1;
        var result=new LinkedHashMap<String,Object>();result.put("metric",metric);result.put("revision",row==null?0:row.revision());
        result.put("archive_id",valid?row.archive():null);result.put("recorded_date",valid?sources.get(0):null);
        result.put("measurement",valid?row.measurement():mapper.createObjectNode());
        result.put("status",!present?"NOT_SET":valid?"RECORDED_UNVERIFIED":"SOURCE_UNAVAILABLE");return result;
    }
    private Slot slot(long owner,long vehicle,String metric,boolean lock){
        var rows=jdbc.query("SELECT id,vehicle_id,archive_id,archive_type,revision,measurement_json FROM vehicle_health_observation WHERE user_id=? AND vehicle_id=? AND metric=? AND is_deleted=0"+(lock?" FOR UPDATE":""),
            (rs,n)->new Slot(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getInt(4),rs.getInt(5),read(rs.getString(6))),owner,vehicle,metric);
        return rows.isEmpty()?null:rows.get(0);
    }
    private String json(JsonNode value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw unavailable();}}
    private JsonNode read(String value){try{return mapper.readTree(value);}catch(Exception e){throw unavailable();}}
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));}
    private Source source(long archive) { return source(archive, false); }
    private Source source(long archive, boolean lock) {
        if (archive <= 0 || archive > 9007199254740991L) throw WriteIntegrityService.badRequest("档案参数无效");
        var rows = jdbc.query("SELECT vehicle_id,archive_type,DATE_FORMAT(recorded_at,'%Y-%m-%d') AS recorded_date FROM vehicle_archive "
            + "WHERE id=? AND is_deleted=0 AND input_type IN (1,3) AND archive_type IN (1,2)" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Source(rs.getLong("vehicle_id"), rs.getInt("archive_type"), rs.getString("recorded_date")), archive);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源档案不可用，请选择本人保养或维修记录");
        return rows.get(0);
    }
    private void ownVehicle(VehicleOwner owner, long vehicle, boolean lock) {
        if (vehicle <= 0 || vehicle > 9007199254740991L) throw WriteIntegrityService.badRequest("车辆参数无效");
        var rows = jdbc.query("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> rs.getLong(1), vehicle, owner.id());
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆或来源档案不可用");
    }
    private void authorize(VehicleOwner owner, boolean lock) {
        if (owner == null || !Instant.now().isBefore(owner.expires())) throw expired();
        String suffix = lock ? " FOR UPDATE" : "";
        if (jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='user' AND subject_id=? AND role='OWNER' AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()" + suffix,
            (rs, n) -> rs.getString(1), owner.session(), owner.id()).isEmpty() || jdbc.query("SELECT id FROM user WHERE id=? AND status=1 AND is_deleted=0" + suffix,
            (rs, n) -> rs.getLong(1), owner.id()).isEmpty()) throw expired();
    }


    private static ResponseStatusException expired(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录");}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"本类读数已变化，请刷新复核来源与读数");}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"读数服务暂不可用");}
    private record Source(long vehicle,int type,String recorded){}
    private record Slot(long id,long vehicle,long archive,int type,int revision,JsonNode measurement){}
}
