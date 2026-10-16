package com.autocare.platform.order;

import com.autocare.platform.service.*;
import com.autocare.platform.vehicle.VehicleOwner;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** The current service task grants scope; it never delegates a customer's session. */
public class ServiceTaskScope {
 final ReservationStore db;final TechnicianAssignments assignments;final String appId;
 public ServiceTaskScope(ReservationStore db,TechnicianAssignments assignments,String appId){this.db=db;this.assignments=assignments;this.appId=appId;}
 void actor(Object a){if(a instanceof MerchantActor m)db.merchant(m,false);else if(a instanceof TechnicianActor t)db.technician(t,appId,false);else throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"无权读取服务资料");}
 static boolean active(Map<String,Object> o){return Set.of("RECEIVED","IN_SERVICE","PENDING_VERIFY").contains(o.get("status"));}
 Map<String,Object> ownerOrder(VehicleOwner a,long id,boolean lock){ServiceCatalog.validateId(id);db.owner(a,lock);return db.one("SELECT * FROM `order` WHERE id=? AND user_id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),id,a.id());}
 Map<String,Object> task(Object a,long id){
  ServiceCatalog.validateId(id);actor(a);Map<String,Object> o;
  if(a instanceof MerchantActor m)o=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,m.merchantId());
  else{var t=(TechnicianActor)a;o=assignments.own(t,id,false);var assignment=db.one("SELECT status,accepted_at FROM technician_assignment WHERE order_id=? AND technician_id=? AND is_deleted=0",id,t.staffId());if(!"ACCEPTED".equals(assignment.get("status"))||assignment.get("accepted_at")==null||!Set.of("IN_SERVICE","PENDING_VERIFY").contains(o.get("status")))throw missing();}
  if(!serviceActive(o))throw missing();
  vehicle(o,false);return o;
 }
 boolean serviceActive(Map<String,Object> o){return active(o)&&db.jdbc.queryForList("SELECT id FROM order_dispute WHERE order_id=? AND status='OPEN' AND is_deleted=0",o.get("id")).isEmpty();}
 Map<String,Object> vehicle(Map<String,Object> o,boolean lock){return db.one("SELECT v.id FROM vehicle v JOIN `user` u ON u.id=v.user_id WHERE v.id=? AND v.user_id=? AND v.is_deleted=0 AND u.status=1 AND u.is_deleted=0"+(lock?" FOR UPDATE":""),o.get("vehicle_id"),o.get("user_id"));}
 Map<String,Object> basic(Map<String,Object> o){
  var v=db.one("SELECT b.name AS brand,s.name AS series,m.year,m.config_name FROM vehicle v LEFT JOIN model m ON m.id=v.model_id AND m.is_deleted=0 LEFT JOIN series s ON s.id=m.series_id AND s.is_deleted=0 LEFT JOIN brand b ON b.id=s.brand_id AND b.is_deleted=0 WHERE v.id=? AND v.user_id=? AND v.is_deleted=0",o.get("vehicle_id"),o.get("user_id"));
  var out=new LinkedHashMap<String,Object>();for(String k:List.of("brand","series","year","config_name"))out.put(k,v.get(k));return out;
 }
}
