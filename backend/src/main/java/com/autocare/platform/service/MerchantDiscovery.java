package com.autocare.platform.service;

import com.autocare.platform.vehicle.VehicleOwner;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Owner-only minimal storefront projection. No private onboarding/staff/customer data. */
public class MerchantDiscovery {
    private final JdbcTemplate jdbc;
    private final ServiceCatalog catalog;
    private final TransactionTemplate reads;
    public MerchantDiscovery(JdbcTemplate jdbc,ServiceCatalog catalog,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.catalog=catalog;reads=new TransactionTemplate(manager);
        reads.setReadOnly(true);reads.setTimeout(15);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public static void validateRegion(String region){
        if(region!=null&&!region.matches("[0-9]{6}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请重新选择省市区");
    }
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"门店或项目已不可用");}
    public Map<String,Object> detail(VehicleOwner owner,long merchant,Long project,int page,int size){
        ServiceCatalog.validateId(merchant);ServiceCatalog.validate(null,page,size);if(project!=null)ServiceCatalog.validateId(project);
        return reads.execute(tx->{
            catalog.authorize(owner);
            var shops=jdbc.query("SELECT id,name,address,merchant_type,region_code FROM merchant WHERE id=? AND status=1 AND is_deleted=0",(rs,n)->{
                var row=new LinkedHashMap<String,Object>();row.put("merchant_id",rs.getLong("id"));row.put("name",rs.getString("name"));row.put("address",rs.getString("address"));
                row.put("merchant_type",rs.getInt("merchant_type"));var region=rs.getString("region_code");row.put("region_code",region!=null&&region.matches("[0-9]{6}")?region:null);return row;
            },merchant);
            if(shops.size()!=1)throw missing();
            if(project!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM standard_project WHERE id=? AND status=1 AND is_deleted=0",Integer.class,project)!=1)throw missing();
            String from=" FROM merchant_project q JOIN standard_project p ON p.id=q.project_id JOIN merchant_project_version v ON v.merchant_project_id=q.id "+
                "AND v.version=(SELECT MAX(v2.version) FROM merchant_project_version v2 WHERE v2.merchant_project_id=q.id) "+
                "WHERE q.merchant_id=? AND q.on_shelf=1 AND q.is_deleted=0 AND p.status=1 AND p.is_deleted=0"+(project==null?"":" AND p.id=?");
            var args=new ArrayList<Object>();args.add(merchant);if(project!=null)args.add(project);
            long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
            args.add(size);args.add((page-1)*size);
            var items=jdbc.query("SELECT q.id,p.id AS standard_project_id,p.project_name,p.category,v.price,v.id AS version_id,v.version"+from+
                " ORDER BY p.id,q.id LIMIT ? OFFSET ?",(rs,n)->Map.<String,Object>of("merchant_project_id",rs.getLong("id"),"standard_project_id",rs.getLong("standard_project_id"),
                    "project_name",rs.getString("project_name"),"category",rs.getInt("category"),"price",rs.getBigDecimal("price").setScale(2).toPlainString(),
                    "version_id",rs.getLong("version_id"),"version",rs.getLong("version")),args.toArray());
            return Map.of("merchant",shops.get(0),"offers",Map.of("items",items,"total",total,"page",page,"page_size",size));
        });
    }
}
