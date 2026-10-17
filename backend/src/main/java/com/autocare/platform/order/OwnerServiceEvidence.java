package com.autocare.platform.order;

import com.autocare.platform.file.FileMetadataRepository;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.service.ServiceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** Owner-only historical facts. No technician/merchant authority or write is delegated. */
public class OwnerServiceEvidence {
    private final ReservationStore db;
    public OwnerServiceEvidence(ReservationStore db){this.db=db;}
    private Map<String,Object> order(VehicleOwner owner,long id){
        ServiceCatalog.validateId(id);db.owner(owner,false);
        return db.one("SELECT * FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());
    }
    public Map<String,Object> detail(VehicleOwner owner,long id){return db.reads.execute(tx->project(order(owner,id)).view());}
    public FileMetadataRepository.Actor fileOwner(VehicleOwner owner,long id,long file){
        ServiceCatalog.validateId(file);
        return db.reads.execute(tx->{var p=project(order(owner,id));var actor=p.files().get(file);if(actor==null)throw missing();return actor;});
    }
    private record Projection(Map<String,Object> view,Map<Long,FileMetadataRepository.Actor> files){}
    private boolean safe(Map<String,Object> f,long owner,boolean signature){
        return f!=null&&"staff_account".equals(f.get("owner_type"))&&f.get("owner_id") instanceof Number actor&&actor.longValue()==owner
            &&"CLEAN".equals(f.get("scan_status"))&&f.get("is_deleted") instanceof Number deleted&&deleted.intValue()==0
            &&(signature?"image/png".equals(f.get("content_type")):Set.of("image/png","image/jpeg").contains(Objects.toString(f.get("content_type"),"")))
            &&f.get("size_bytes") instanceof Number n&&n.longValue()>0&&n.longValue()<=10485760;
    }
    private Map<String,Object> photo(long id,String slot,Map<String,Object> f,long owner,boolean signature,Map<Long,FileMetadataRepository.Actor> allowed){
        boolean available=safe(f,owner,signature);if(available)allowed.put(id,new FileMetadataRepository.Actor("staff_account",owner));
        return Map.of("file_id",id,"slot",slot,"available",available);
    }
    private Map<String,Object> group(String kind,String state,List<Map<String,Object>> photos){return Map.of("kind",kind,"state",state,"photos",photos);}
    private Map<String,Object> relation(List<Map<String,Object>> rows,long record,String kind,long file){
        var found=rows.stream().filter(r->number(r,"record_id")==record&&kind.equals(r.get("kind"))&&number(r,"file_id")==file).toList();
        return found.size()==1?found.get(0):null;
    }
    private JsonNode reportBody(Map<String,Object> r){
        var b=db.mapper.createObjectNode();b.put("order_id",number(r,"order_id"));
        for(String key:List.of("process_photos","fault_part_photos","finish_photos","parts_used"))b.set(key,db.parse(r.get(key)));
        for(String key:List.of("repair_plan","fault_analysis"))b.put(key,Objects.toString(r.get(key),""));
        b.put("work_hours",r.get("work_hours") instanceof Number n?n.longValue():0);
        b.put("no_parts",number(r,"no_parts")==1);b.put("no_fault_parts",number(r,"no_fault_parts")==1);
        ServiceWorkInput.report(b);return b;
    }
    private Projection project(Map<String,Object> o){
        long id=number(o,"id");var allowed=new HashMap<Long,FileMetadataRepository.Actor>();var groups=new ArrayList<Map<String,Object>>();
        var timeline=new ArrayList<Map<String,Object>>();
        for(var pair:List.of(new String[]{"CREATED","created_at"},new String[]{"CHECKED_IN","check_in_completed_at"},new String[]{"OWNER_CONFIRMED","owner_confirmed_at"},new String[]{"ASSIGNED","assigned_at"}))event(timeline,pair[0],o.get(pair[1]));
        var pickup=db.jdbc.queryForList("SELECT * FROM pickup_check WHERE order_id=?",id);var before=new ArrayList<Map<String,Object>>();String pickupState=pickup.isEmpty()?"MISSING":"UNKNOWN";
        if(pickup.size()==1){var p=pickup.get(0);if(number(p,"is_deleted")==0&&Objects.equals(p.get("merchant_id"),o.get("merchant_id"))&&p.get("arrived_at")!=null){
            pickupState="RECORDED";
            var photos=db.jdbc.queryForList("SELECT e.photo_slot,e.file_id,f.owner_type,f.owner_id,f.content_type,f.size_bytes,f.scan_status,f.is_deleted FROM pickup_check_file e LEFT JOIN file_object f ON f.id=e.file_id WHERE e.pickup_check_id=? ORDER BY e.photo_slot",p.get("id"));
            for(var f:photos)if(PickupInput.SLOTS.contains(f.get("photo_slot"))||"FUEL".equals(f.get("photo_slot")))before.add(photo(number(f,"file_id"),f.get("photo_slot").toString(),f,number(p,"staff_id"),false,allowed));
        }}groups.add(group("PICKUP",pickupState,before));
        var links=db.jdbc.queryForList("SELECT e.record_id,e.kind,e.file_id,f.owner_type,f.owner_id,f.content_type,f.size_bytes,f.scan_status,f.is_deleted FROM service_evidence_file e LEFT JOIN file_object f ON f.id=e.file_id WHERE e.order_id=? ORDER BY e.file_id",id);
        var protection=db.jdbc.queryForList("SELECT * FROM repair_protection WHERE order_id=?",id);var covers=new ArrayList<Map<String,Object>>();String protectionState=protection.isEmpty()?"MISSING":"UNKNOWN";
        if(protection.size()==1){var p=protection.get(0);if(number(p,"is_deleted")==0&&p.get("uploaded_at")!=null){
            var items=db.parse(p.get("items"));var selected=new HashSet<String>();if(items.isArray())items.forEach(n->selected.add(n.asText()));
            var sources=links.stream().filter(f->number(f,"record_id")==number(p,"id")&&"PROTECTION".equals(f.get("kind"))).toList();
            if(items.isArray()&&items.size()==selected.size()&&selected.size()<=4&&Set.of("SEAT_COVER","STEERING_COVER","FLOOR_MAT","FENDER_COVER").containsAll(selected)&&selected.containsAll(Set.of("SEAT_COVER","STEERING_COVER"))&&sources.size()==1){protectionState="RECORDED";event(timeline,"PROTECTED",p.get("uploaded_at"));
                var staff=new HashSet<>(db.jdbc.query("SELECT id FROM staff_account WHERE merchant_id=? AND role IN ('MERCHANT','STAFF')",(rs,n)->rs.getLong(1),o.get("merchant_id")));
                var f=sources.get(0);long actor=f.get("owner_id") instanceof Number n?n.longValue():0;covers.add(photo(number(f,"file_id"),"",staff.contains(actor)?f:null,actor,false,allowed));
            }
        }}groups.add(group("PROTECTION",protectionState,covers));
        var reports=db.jdbc.queryForList("SELECT r.*,s.no_fault_parts,s.no_parts,s.submitted_at FROM technician_report r LEFT JOIN service_report_submission s ON s.report_id=r.id AND s.order_id=r.order_id WHERE r.order_id=?",id);
        String reportState=reports.isEmpty()?"MISSING":"UNKNOWN";Map<String,Object> report=null;JsonNode body=null;Map<String,Object> r=null;
        if(reports.size()==1){r=reports.get(0);if(number(r,"is_deleted")==0&&r.get("submitted_at")!=null){
            try{body=reportBody(r);}catch(org.springframework.web.server.ResponseStatusException|IllegalArgumentException ex){body=null;}
            boolean submitted=number(r,"status")==0&&r.get("signed_at")==null;
            boolean signed=number(r,"status")==1&&r.get("signed_at")!=null&&o.get("service_report_ready_at")!=null;
            if(body!=null&&(submitted||signed)){reportState=signed?"SIGNED":"SUBMITTED";report=new LinkedHashMap<>();
                for(String key:List.of("repair_plan","fault_analysis","parts_used","no_parts","no_fault_parts"))report.put(key,body.get(key));
                report.put("work_minutes",body.get("work_hours"));report.put("submitted_at",iso(r.get("submitted_at")));report.put("signed_at",iso(r.get("signed_at")));
                event(timeline,"REPORTED",r.get("submitted_at"));if(signed)event(timeline,"QUALITY_SIGNED",r.get("signed_at"));
            }
        }}
        for(var pair:List.of(new String[]{"PROCESS","process_photos"},new String[]{"FAULT","fault_part_photos"},new String[]{"FINISH","finish_photos"},new String[]{"SIGNATURE",""})){
            var photos=new ArrayList<Map<String,Object>>();String state=reportState.equals("MISSING")?"MISSING":"UNKNOWN";
            if(report!=null){state="RECORDED";long actor=number(r,"technician_id"),record=number(r,"id");
                if(!pair[1].isEmpty())for(var file:body.get(pair[1])){long f=file.longValue();photos.add(photo(f,"",relation(links,record,pair[0],f),actor,false,allowed));}
                else if(reportState.equals("SIGNED")){var signs=links.stream().filter(f->number(f,"record_id")==record&&"SIGNATURE".equals(f.get("kind"))).toList();if(signs.size()==1){var f=signs.get(0);photos.add(photo(number(f,"file_id"),"",f,actor,true,allowed));}else state="UNKNOWN";}
                else state="MISSING";
            }groups.add(group(pair[0],state,photos));
        }
        var redeemed=db.jdbc.queryForList("SELECT redeemed_at FROM order_redemption WHERE order_id=? AND merchant_id=?",id,o.get("merchant_id"));if(redeemed.size()==1)event(timeline,"REDEEMED",redeemed.get(0).get("redeemed_at"));
        event(timeline,"CLOSED",o.get("closed_at"));timeline.sort(Comparator.comparing(e->e.get("occurred_at").toString()));
        var out=new LinkedHashMap<String,Object>();out.put("order_id",id);out.put("order_status",o.get("status"));out.put("timeline",timeline);out.put("groups",groups);out.put("report_state",reportState);out.put("report",report);
        return new Projection(out,allowed);
    }
    private void event(List<Map<String,Object>> timeline,String event,Object time){if(time!=null)timeline.add(Map.of("event",event,"occurred_at",iso(time)));}
}
