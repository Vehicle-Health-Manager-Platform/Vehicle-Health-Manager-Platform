package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

public class ReservationSlots {
    final ReservationStore db;
    public ReservationSlots(ReservationStore db){this.db=db;}
    public JsonNode publish(MerchantActor actor,String key,ReservationInput.Slot input){
        var body=Map.of("standard_project_id",input.project(),"starts_at",input.starts().toString(),"ends_at",input.ends().toString(),"capacity",input.capacity());
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/merchant/slots",key,db.mapper.valueToTree(body),()->db.merchant(actor,true),()->{
            Instant now=db.now();if(!input.starts().isAfter(now) || input.ends().isAfter(now.plus(Duration.ofDays(30))))throw ReservationInput.bad();
            db.one("SELECT p.id FROM standard_project p JOIN merchant_project q ON q.project_id=p.id WHERE p.id=? AND p.status=1 AND p.is_deleted=0 AND q.merchant_id=? AND q.on_shelf=1 AND q.is_deleted=0 FOR UPDATE",input.project(),actor.merchantId());
            if(!db.jdbc.queryForList("SELECT id FROM appointment_slot WHERE merchant_id=? AND project_id=? AND is_deleted=0 AND starts_at<? AND ends_at>? FOR UPDATE",actor.merchantId(),input.project(),ReservationStore.time(input.ends()),ReservationStore.time(input.starts())).isEmpty())throw new ReservationConflict(40904,"时段重叠，请调整时间");
            long id=db.insert("INSERT INTO appointment_slot(merchant_id,project_id,starts_at,ends_at,capacity,is_open) VALUES(?,?,?,?,?,1)",actor.merchantId(),input.project(),ReservationStore.time(input.starts()),ReservationStore.time(input.ends()),input.capacity());
            var result=db.slotRow(db.one("SELECT * FROM appointment_slot WHERE id=?",id),now);
            return new Change("SLOT_PUBLISH","appointment_slot",id,Map.of(),result,result);
        });
    }
    public JsonNode close(MerchantActor actor,String key,long id){
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/merchant/slots/"+id+"/close",key,db.mapper.createObjectNode(),()->{
            db.merchant(actor,true);db.one("SELECT id FROM appointment_slot WHERE id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",id,actor.merchantId());
        },()->{
            var before=db.slotRow(db.one("SELECT * FROM appointment_slot WHERE id=?",id),db.now());
            db.jdbc.update("UPDATE appointment_slot SET is_open=0,closed_at=COALESCE(closed_at,?) WHERE id=?",ReservationStore.time(db.now()),id);
            var result=db.slotRow(db.one("SELECT * FROM appointment_slot WHERE id=?",id),db.now());return new Change("SLOT_CLOSE","appointment_slot",id,before,result,result);
        });
    }
    public Map<String,Object> own(MerchantActor actor,String date,int page,int size){
        ServiceCatalog.validate(null,page,size);return db.reads.execute(tx->{db.merchant(actor,false);Instant now=db.now();
            String filter=" FROM appointment_slot s LEFT JOIN standard_project p ON p.id=s.project_id WHERE s.merchant_id=? AND s.is_deleted=0";
            var args=new ArrayList<Object>();args.add(actor.merchantId());
            if(date==null){filter+=" AND s.ends_at>?";args.add(ReservationStore.time(now));}
            else{LocalDate d=ReservationInput.date(date,now);filter+=" AND s.starts_at>=? AND s.starts_at<?";args.add(ReservationStore.time(d.atStartOfDay(ReservationInput.ZONE).toInstant()));args.add(ReservationStore.time(d.plusDays(1).atStartOfDay(ReservationInput.ZONE).toInstant()));}
            long count=db.jdbc.queryForObject("SELECT COUNT(*)"+filter,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
            var rows=db.jdbc.queryForList("SELECT s.*,COALESCE(p.project_name,'项目已不可用') AS project_name"+filter+" ORDER BY s.starts_at,s.id LIMIT ? OFFSET ?",args.toArray());return ReservationStore.page(rows.stream().map(row->db.slotRow(row,now)).toList(),count,page,size);
        });
    }
    public Map<String,Object> available(VehicleOwner owner,long merchant,long project,String date,int page,int size){
        ServiceCatalog.validateId(merchant);ServiceCatalog.validateId(project);ServiceCatalog.validate(null,page,size);
        return db.reads.execute(tx->{db.owner(owner,false);Instant now=db.now();LocalDate d=ReservationInput.date(date,now);
            String filter=" FROM appointment_slot s JOIN merchant m ON m.id=s.merchant_id JOIN standard_project p ON p.id=s.project_id JOIN merchant_project q ON q.merchant_id=s.merchant_id AND q.project_id=s.project_id WHERE s.merchant_id=? AND s.project_id=? AND s.is_deleted=0 AND s.is_open=1 AND s.starts_at>? AND s.starts_at>=? AND s.starts_at<? AND m.status=1 AND m.is_deleted=0 AND p.status=1 AND p.is_deleted=0 AND q.on_shelf=1 AND q.is_deleted=0 AND s.capacity>(SELECT COUNT(*) FROM `order` o WHERE o.slot_id=s.id AND o.is_deleted=0 AND o.status<>'CLOSED' AND (o.status<>'PENDING_PAYMENT' OR o.expires_at IS NULL OR o.expires_at>?))";
            Object[] args={merchant,project,ReservationStore.time(now),ReservationStore.time(d.atStartOfDay(ReservationInput.ZONE).toInstant()),ReservationStore.time(d.plusDays(1).atStartOfDay(ReservationInput.ZONE).toInstant()),ReservationStore.time(now)};
            long count=db.jdbc.queryForObject("SELECT COUNT(*)"+filter,Long.class,args);var params=new ArrayList<>(Arrays.asList(args));params.add(size);params.add((page-1)*size);
            var rows=db.jdbc.queryForList("SELECT s.*,p.project_name"+filter+" ORDER BY s.starts_at,s.id LIMIT ? OFFSET ?",params.toArray());return ReservationStore.page(rows.stream().map(row->db.slotRow(row,now)).toList(),count,page,size);
        });
    }
}
