package com.autocare.platform.service;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class MerchantQuotes {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    private final ServiceCatalog catalog;
    private final TransactionTemplate reads;
    public MerchantQuotes(JdbcTemplate jdbc,WriteIntegrityService integrity,ObjectMapper mapper,ServiceCatalog catalog,PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.integrity=integrity; this.mapper=mapper; this.catalog=catalog;
        reads=new TransactionTemplate(manager); reads.setReadOnly(true); reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"项目或报价已不可用"); }
    private void authorize(MerchantActor actor,boolean lock) {
        String suffix=lock?" FOR UPDATE":"";
        var sessions=jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='staff_account' AND subject_id=? AND role='MERCHANT' "
            + "AND app_id='merchant-account' AND merchant_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()"+suffix,(rs,n)->rs.getString(1),actor.session(),actor.staffId(),actor.merchantId());
        var staff=jdbc.query("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role='MERCHANT' AND status='ACTIVE' AND is_deleted=0"+suffix,
            (rs,n)->rs.getLong(1),actor.staffId(),actor.merchantId());
        var shops=jdbc.query("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0"+suffix,(rs,n)->rs.getLong(1),actor.merchantId());
        if(!Instant.now().isBefore(actor.expires()) || sessions.isEmpty() || staff.isEmpty() || shops.isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"商家身份已失效，请重新登录");
    }
    private Map<String,Object> page(List<?> items,long total,int page,int size) {
        return Map.of("items",items,"total",total,"page",page,"page_size",size);
    }
    public Map<String,Object> ownerList(VehicleOwner owner,long project,String sort,int page,int size) {
        ServiceCatalog.validateId(project); ServiceCatalog.validate(null,page,size);
        if(!Set.of("price_asc","price_desc").contains(sort)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"报价排序无效");
        return reads.execute(status->{
            catalog.authorize(owner);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM standard_project WHERE id=? AND status=1 AND is_deleted=0",Integer.class,project)!=1) throw missing();
            String from=" FROM merchant_project q JOIN merchant m ON m.id=q.merchant_id JOIN standard_project p ON p.id=q.project_id "
                + "JOIN merchant_project_version v ON v.merchant_project_id=q.id AND v.version=(SELECT MAX(v2.version) FROM merchant_project_version v2 WHERE v2.merchant_project_id=q.id) "
                + "WHERE q.project_id=? AND q.on_shelf=1 AND q.is_deleted=0 AND m.status=1 AND m.is_deleted=0 AND p.status=1 AND p.is_deleted=0";
            long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,project);
            var items=jdbc.query("SELECT q.id,m.id AS merchant_id,m.name,m.address,q.price,v.id AS version_id,v.version"+from
                + " ORDER BY q.price "+(sort.equals("price_desc")?"DESC":"ASC")+",q.id ASC LIMIT ? OFFSET ?",(rs,n)->Map.<String,Object>of(
                    "merchant_project_id",rs.getLong("id"),"merchant_id",rs.getLong("merchant_id"),"merchant_name",rs.getString("name"),
                    "address",rs.getString("address"),"price",rs.getBigDecimal("price").setScale(2).toPlainString(),
                    "version_id",rs.getLong("version_id"),"version",rs.getLong("version")),project,size,(page-1)*size);
            return page(items,total,page,size);
        });
    }
    public Map<String,Object> standards(MerchantActor actor,Integer category,int page,int size) {
        ServiceCatalog.validate(category,page,size);
        return reads.execute(status->{
            authorize(actor,false);
            String from=" FROM standard_project WHERE status=1 AND is_deleted=0"+(category==null?"":" AND category=?");
            Object[] counts=category==null?new Object[]{}:new Object[]{category};
            Object[] rows=category==null?new Object[]{size,(page-1)*size}:new Object[]{category,size,(page-1)*size};
            long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,counts);
            var items=jdbc.query("SELECT id,project_name,category,base_price_low,base_price_high"+from+" ORDER BY id LIMIT ? OFFSET ?",
                (rs,n)->Map.<String,Object>of("id",rs.getLong("id"),"project_name",rs.getString("project_name"),"category",rs.getInt("category"),
                    "base_price_low",rs.getBigDecimal("base_price_low").setScale(2).toPlainString(),"base_price_high",rs.getBigDecimal("base_price_high").setScale(2).toPlainString()),rows);
            return page(items,total,page,size);
        });
    }
    public Map<String,Object> ownList(MerchantActor actor,int page,int size) {
        ServiceCatalog.validate(null,page,size);
        return reads.execute(status->{
            authorize(actor,false);
            String from=" FROM merchant_project q LEFT JOIN standard_project p ON p.id=q.project_id "
                + "JOIN merchant_project_version v ON v.merchant_project_id=q.id AND v.version=(SELECT MAX(v2.version) FROM merchant_project_version v2 WHERE v2.merchant_project_id=q.id) "
                + "WHERE q.merchant_id=? AND q.is_deleted=0";
            long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,actor.merchantId());
            var items=jdbc.query("SELECT q.id,q.project_id,q.price,q.on_shelf,v.id AS version_id,v.version,COALESCE(p.project_name,'项目已不可用') AS project_name,"
                + "COALESCE(p.status=1 AND p.is_deleted=0,0) AS available"+from+" ORDER BY q.id ASC LIMIT ? OFFSET ?",(rs,n)->Map.<String,Object>of(
                    "merchant_project_id",rs.getLong("id"),"standard_project_id",rs.getLong("project_id"),"project_name",rs.getString("project_name"),
                    "price",rs.getBigDecimal("price").setScale(2).toPlainString(),"status",rs.getInt("on_shelf"),"available",rs.getBoolean("available"),
                    "version_id",rs.getLong("version_id"),"version",rs.getLong("version")),actor.merchantId(),size,(page-1)*size);
            return page(items,total,page,size);
        });
    }
    private void lockProject(MerchantActor actor,QuoteInput input) {
        authorize(actor,true);
        var projects=jdbc.query("SELECT status,is_deleted FROM standard_project WHERE id=? FOR UPDATE",(rs,n)->rs.getInt(1)==1 && rs.getInt(2)==0,input.projectId());
        var quotes=jdbc.query("SELECT price,is_deleted FROM merchant_project WHERE merchant_id=? AND project_id=? FOR UPDATE",
            (rs,n)->new Object[]{rs.getBigDecimal(1),rs.getInt(2)},actor.merchantId(),input.projectId());
        if(!quotes.isEmpty() && (int)quotes.get(0)[1]!=0) throw missing();
        if(projects.isEmpty() || !projects.get(0)) {
            if(quotes.isEmpty() || input.status()!=0 || input.price().compareTo((java.math.BigDecimal)quotes.get(0)[0])!=0) throw missing();
        }
    }
    public JsonNode save(MerchantActor actor,String key,QuoteInput input) {
        return integrity.execute(new WriteIntegrityService.Actor("staff_account",actor.staffId()),"POST","/api/merchant/projects",key,
            mapper.valueToTree(input.canonical()),()->lockProject(actor,input),()->{
                var old=jdbc.query("SELECT id,price,on_shelf FROM merchant_project WHERE merchant_id=? AND project_id=?",
                    (rs,n)->Map.<String,Object>of("merchant_project_id",rs.getLong(1),"price",rs.getBigDecimal(2).setScale(2).toPlainString(),"status",rs.getInt(3)),actor.merchantId(),input.projectId());
                long id;
                if(old.isEmpty()) {
                    var holder=new GeneratedKeyHolder();
                    jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO merchant_project(merchant_id,project_id,price,on_shelf) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                        statement.setLong(1,actor.merchantId());statement.setLong(2,input.projectId());statement.setBigDecimal(3,input.price());statement.setInt(4,input.status());return statement;},holder);
                    id=holder.getKey().longValue();
                } else {
                    id=((Number)old.get(0).get("merchant_project_id")).longValue();
                    jdbc.update("UPDATE merchant_project SET price=?,on_shelf=? WHERE id=?",input.price(),input.status(),id);
                }
                long version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM merchant_project_version WHERE merchant_project_id=?",Long.class,id);
                var holder=new GeneratedKeyHolder(); final long quote=id;
                jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO merchant_project_version(merchant_project_id,version,price,on_shelf,actor_staff_id) VALUES(?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1,quote);statement.setLong(2,version);statement.setBigDecimal(3,input.price());statement.setInt(4,input.status());statement.setLong(5,actor.staffId());return statement;},holder);
                var data=Map.<String,Object>of("merchant_project_id",id,"version_id",holder.getKey().longValue(),"version",version,"price",input.price().toPlainString(),"status",input.status());
                return new WriteIntegrityService.Change("MERCHANT_PROJECT_SAVE","merchant_project",id,old.isEmpty()?Map.of():old.get(0),data,data);
            });
    }
}
