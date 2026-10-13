package com.autocare.platform.service;

import com.autocare.platform.vehicle.VehicleOwner;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Read-only POST queries keep the owner's ephemeral position out of URLs and write audits. */
public class NearbyMerchants {
    private final JdbcTemplate jdbc;
    private final ServiceCatalog catalog;
    private final TransactionTemplate reads;
    private static final String JOIN=" LEFT JOIN merchant_location l ON l.merchant_id=m.id AND l.coordinate_system='GCJ02' AND l.source='MERCHANT_CONFIRMED' AND l.lng BETWEEN -180 AND 180 AND l.lat BETWEEN -90 AND 90 ";
    private static final String DISTANCE="ROUND(6371008.8 * 2 * ASIN(SQRT(LEAST(1,GREATEST(0,POWER(SIN(RADIANS(l.lat - ?)/2),2) + COS(RADIANS(?))*COS(RADIANS(l.lat))*POWER(SIN(RADIANS(l.lng - ?)/2),2))))))";
    public NearbyMerchants(JdbcTemplate jdbc,ServiceCatalog catalog,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.catalog=catalog;reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setTimeout(15);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"门店或项目已不可用");}
    private static void appendPoint(List<Object> args,LocationInput.Point p){args.add(p.lat());args.add(p.lat());args.add(p.lng());}
    private static void distance(java.sql.ResultSet rs,Map<String,Object> row) throws java.sql.SQLException {
        Object meters=rs.getObject("distance_meters");row.put("distance_meters",meters==null?null:((Number)meters).longValue());
        row.put("location_source",meters==null?null:"MERCHANT_CONFIRMED");
    }
    public Map<String,Object> list(VehicleOwner owner,long project,LocationInput.Nearby input){
        ServiceCatalog.validateId(project);
        return reads.execute(tx->{
            catalog.authorize(owner);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM standard_project WHERE id=? AND status=1 AND is_deleted=0",Integer.class,project)!=1)throw missing();
            String from=" FROM merchant_project q JOIN merchant m ON m.id=q.merchant_id JOIN standard_project p ON p.id=q.project_id JOIN merchant_project_version v ON v.merchant_project_id=q.id AND v.version=(SELECT MAX(v2.version) FROM merchant_project_version v2 WHERE v2.merchant_project_id=q.id)"+JOIN+
                " WHERE q.project_id=? AND q.on_shelf=1 AND q.is_deleted=0 AND m.status=1 AND m.is_deleted=0 AND p.status=1 AND p.is_deleted=0"+(input.region()==null?"":" AND m.region_code=?");
            var filters=new ArrayList<Object>();filters.add(project);if(input.region()!=null)filters.add(input.region());
            long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,filters.toArray());
            var args=new ArrayList<Object>();appendPoint(args,input.point());args.addAll(filters);args.add(input.size());args.add((input.page()-1)*input.size());
            String order=switch(input.sort()){case "price_asc"->"v.price ASC,q.id ASC";case "price_desc"->"v.price DESC,q.id ASC";default->"distance_meters IS NULL ASC,distance_meters ASC,q.id ASC";};
            var items=jdbc.query("SELECT q.id,m.id AS merchant_id,m.name,m.address,v.price,v.id AS version_id,v.version,"+DISTANCE+" AS distance_meters"+from+" ORDER BY "+order+" LIMIT ? OFFSET ?",(rs,n)->{
                var row=new LinkedHashMap<String,Object>();row.put("merchant_project_id",rs.getLong("id"));row.put("merchant_id",rs.getLong("merchant_id"));row.put("merchant_name",rs.getString("name"));row.put("address",rs.getString("address"));
                row.put("price",rs.getBigDecimal("price").setScale(2).toPlainString());row.put("version_id",rs.getLong("version_id"));row.put("version",rs.getLong("version"));distance(rs,row);return row;
            },args.toArray());
            return Map.of("items",items,"total",total,"page",input.page(),"page_size",input.size());
        });
    }
    public Map<String,Object> distance(VehicleOwner owner,long merchant,LocationInput.Point p){
        ServiceCatalog.validateId(merchant);
        return reads.execute(tx->{
            catalog.authorize(owner);var args=new ArrayList<Object>();appendPoint(args,p);args.add(merchant);
            var rows=jdbc.query("SELECT m.id,"+DISTANCE+" AS distance_meters FROM merchant m"+JOIN+" WHERE m.id=? AND m.status=1 AND m.is_deleted=0",(rs,n)->{
                var row=new LinkedHashMap<String,Object>();row.put("merchant_id",rs.getLong("id"));distance(rs,row);return row;
            },args.toArray());
            if(rows.size()!=1)throw missing();return rows.get(0);
        });
    }
}
