package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class ArchiveStructuredService {
    private final com.autocare.platform.order.PointLedger points;
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    private final TransactionTemplate reads;
    public ArchiveStructuredService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this(jdbc,integrity,mapper,transactions,null);
    }
    public ArchiveStructuredService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper, PlatformTransactionManager transactions,com.autocare.platform.order.PointLedger points) {
        this.points=points;
        this.jdbc=jdbc; this.integrity=integrity; this.mapper=mapper;
        reads=new TransactionTemplate(transactions); reads.setReadOnly(true); reads.setTimeout(15);
    }
    public Map<String,Object> context(VehicleOwner owner, long archive) {
        return reads.execute(tx -> {
            authorize(owner,false); Source source=source(archive); ownVehicle(owner,source.vehicle(),false);
            var row=slot(owner.id(),archive,false);
            var result=new LinkedHashMap<String,Object>(receipt(source,archive,row==null?0:row.revision(),row==null||row.fields().isEmpty()));
            result.put("recorded_date",source.recorded()); result.put("fields",row==null?mapper.createObjectNode():row.fields());
            return result;
        });
    }
    public JsonNode save(VehicleOwner owner,long archive,String key,ArchiveStructuredInput input) {
        final Source[] selected=new Source[1];
        return integrity.execute(new WriteIntegrityService.Actor("user",owner.id()),"PUT","/api/archive/"+archive+"/structured",key,
            mapper.valueToTree(input.canonical()),()->{
                authorize(owner,true); Source initial=source(archive); ownVehicle(owner,initial.vehicle(),true);
                selected[0]=source(archive,true); if(!initial.equals(selected[0])) throw conflict();
                input.validateFor(initial.type());
            },()->{
                Source source=selected[0]; var before=slot(owner.id(),archive,true);
                if(input.revision()!=(before==null?0:before.revision())) throw conflict();
                int next=input.revision()+1;
                if(before==null) jdbc.update("INSERT INTO archive_structured_detail(user_id,vehicle_id,archive_id,archive_type,fields_json,revision) VALUES (?,?,?,?,?,?)",
                    owner.id(),source.vehicle(),archive,source.type(),json(input.fields()),next);
                else jdbc.update("UPDATE archive_structured_detail SET vehicle_id=?,archive_type=?,fields_json=?,revision=? WHERE id=?",
                    source.vehicle(),source.type(),json(input.fields()),next,before.id());
                var after=slot(owner.id(),archive,true);
                if(points!=null && (before==null||!com.autocare.platform.order.PointLedger.completeManual(source.type(),before.fields())) && com.autocare.platform.order.PointLedger.completeManual(source.type(),after.fields()))points.archiveCompleted(owner,archive);
                return new WriteIntegrityService.Change("ARCHIVE_STRUCTURED_SET","archive_structured_detail",after.id(),
                    before==null?Map.of():audit(before),audit(after),receipt(source,archive,next,input.fields().isEmpty()));
            });
    }
    private Map<String,Object> receipt(Source source,long archive,int revision,boolean empty) {
        return Map.of("vehicle_id",source.vehicle(),"archive_id",archive,"archive_type",source.type(),"revision",revision,
            "status",empty?"NOT_SET":"OWNER_ENTERED_UNVERIFIED");
    }
    private Map<String,Object> audit(Slot row) {
        var names=new TreeSet<String>(); row.fields().fieldNames().forEachRemaining(names::add);
        return Map.of("revision",row.revision(),"fields",names);
    }
    private Slot slot(long owner,long archive,boolean lock) {
        var rows=jdbc.query("SELECT id,revision,fields_json FROM archive_structured_detail WHERE user_id=? AND archive_id=?"+(lock?" FOR UPDATE":""),
            (rs,n)->new Slot(rs.getLong(1),rs.getInt(2),read(rs.getString(3))),owner,archive);
        return rows.isEmpty()?null:rows.get(0);
    }
    private String json(JsonNode value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw unavailable(); } }
    private JsonNode read(String value) { try { return mapper.readTree(value); } catch(Exception e) { throw unavailable(); } }
    private Source source(long archive) { return source(archive, false); }
    private Source source(long archive, boolean lock) {
        if (archive <= 0 || archive > 9007199254740991L) throw WriteIntegrityService.badRequest("档案参数无效");
        var rows = jdbc.query("SELECT vehicle_id,archive_type,DATE_FORMAT(recorded_at,'%Y-%m-%d') AS recorded_date FROM vehicle_archive "
            + "WHERE id=? AND is_deleted=0 AND input_type IN (1,3) AND archive_type IN (1,2,3,6,7,8,9,10)" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Source(rs.getLong("vehicle_id"), rs.getInt("archive_type"), rs.getString("recorded_date")), archive);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源档案不可用，请选择本人可填写结构化资料的记录");
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

    private static ResponseStatusException expired() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"资料已变化，请刷新并复核"); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"资料暂不可用"); }
    private record Source(long vehicle,int type,String recorded) {}
    private record Slot(long id,int revision,JsonNode fields) {}
}
