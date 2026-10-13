package com.autocare.platform.order;

import com.autocare.platform.vehicle.VehicleOwner;
import java.util.*;

/** One owner snapshot binds current quote, exact project, vehicle evidence and slot availability. */
public class PlanBookingContext {
    private final ReservationStore db;private final ReservationOrders orders;private final ReservationSlots slots;private final VehiclePlanEvidence evidence;
    public PlanBookingContext(ReservationStore db,ReservationOrders orders,ReservationSlots slots,VehiclePlanEvidence evidence){this.db=db;this.orders=orders;this.slots=slots;this.evidence=evidence;}
    record Input(long quote,long project,long vehicle,String date,int page,Long selected){}
    static Input input(String raw){var b=PublicReviewTextInput.parse(raw);PublicReviewTextInput.fields(b,"merchant_project_id","standard_project_id","vehicle_id","date","page","selected_slot_id");long page=ReservationInput.id(b.get("page"));if(page>1000000||!b.get("date").isTextual()||!b.get("date").textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw ReservationInput.bad();return new Input(ReservationInput.id(b.get("merchant_project_id")),ReservationInput.id(b.get("standard_project_id")),ReservationInput.id(b.get("vehicle_id")),b.get("date").textValue(),(int)page,b.get("selected_slot_id").isNull()?null:ReservationInput.id(b.get("selected_slot_id")));}
    public Map<String,Object> read(VehicleOwner owner,Input in){return db.reads.execute(tx->{
        db.owner(owner,false);var now=db.now();var day=ReservationInput.date(in.date(),now);var quote=orders.quote(owner,in.quote());if(ReservationStore.number(quote,"standard_project_id")!=in.project())throw ReservationStore.missing();long merchant=ReservationStore.number(quote,"merchant_id");
        var vehicle=evidence.evidence(owner,new VehiclePlanEvidence.Request(in.vehicle(),List.of(in.project())));var page=slots.available(owner,merchant,in.project(),in.date(),in.page(),20);
        Map<String,Object> selected=null;if(in.selected()!=null){var found=db.jdbc.queryForList("SELECT * FROM appointment_slot WHERE id=? AND merchant_id=? AND project_id=? AND is_deleted=0 AND is_open=1 AND starts_at>? AND starts_at>=? AND starts_at<?",in.selected(),merchant,in.project(),ReservationStore.time(now),ReservationStore.time(day.atStartOfDay(ReservationInput.ZONE).toInstant()),ReservationStore.time(day.plusDays(1).atStartOfDay(ReservationInput.ZONE).toInstant()));if(!found.isEmpty()){var row=db.slotRow(found.get(0),now);if(ReservationStore.number(row,"capacity_left")>0)selected=row;}}
        var out=new LinkedHashMap<String,Object>();out.put("quote",quote);out.put("evidence",vehicle);out.put("slots",page);out.put("selected_slot",selected);return out;
    });}
}
