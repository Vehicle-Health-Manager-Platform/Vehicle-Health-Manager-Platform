package com.autocare.platform.order;
import com.autocare.platform.vehicle.VehicleOwner;
import java.util.*;
public class ServiceSearch {
 final ReservationStore db;public ServiceSearch(ReservationStore db){this.db=db;}
 record Input(String query,int category,int page){}
 static Input input(String raw){var b=PublicReviewTextInput.parse(raw);PublicReviewTextInput.fields(b,"query","category","page");HealthPolicyInput.text(b.get("query"),80);return new Input(b.get("query").textValue(),HealthPolicyInput.integer(b.get("category"),0,6),HealthPolicyInput.integer(b.get("page"),1,1000000));}
 public Map<String,Object> read(VehicleOwner owner,Input in){return db.reads.execute(tx->{db.owner(owner,false);String query="%"+in.query().replace("!","!!").replace("%","!%").replace("_","!_")+"%";String from=" FROM standard_project WHERE status=1 AND is_deleted=0 AND (project_name LIKE ? ESCAPE '!' OR service_content LIKE ? ESCAPE '!')"+(in.category()==0?"":" AND category=?");var args=new ArrayList<Object>(List.of(query,query));if(in.category()!=0)args.add(in.category());long total=db.jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());args.add(20);args.add((in.page()-1)*20);var rows=db.jdbc.queryForList("SELECT id,project_name,category,base_price_low,base_price_high"+from+" ORDER BY sort_order,id LIMIT ? OFFSET ?",args.toArray());for(var row:rows){row.put("base_price_low",row.get("base_price_low").toString());row.put("base_price_high",row.get("base_price_high").toString());}return ReservationStore.page(rows,total,in.page(),20);});}
}
