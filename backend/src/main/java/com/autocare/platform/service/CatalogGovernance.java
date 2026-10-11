package com.autocare.platform.service;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.*;
import com.fasterxml.jackson.databind.*;
import java.sql.Statement;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class CatalogGovernance {
    private final JdbcTemplate jdbc; private final WriteIntegrityService integrity; private final ObjectMapper mapper;
    private final OperatorIdentity operators; private final TransactionTemplate reads; private final boolean enabled;
    public CatalogGovernance(JdbcTemplate jdbc,WriteIntegrityService integrity,ObjectMapper mapper,OperatorIdentity operators,PlatformTransactionManager tx,boolean enabled) {
        this.jdbc=jdbc;this.integrity=integrity;this.mapper=mapper;this.operators=operators;this.enabled=enabled;
        reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(4);
    }
    private void authorize(OperatorActor actor,boolean lock) { if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"目录维护尚未开启");operators.authorize(actor,OperatorIdentity.Permission.CATALOG,lock); }
    private static ResponseStatusException conflict(String reason){return new ResponseStatusException(HttpStatus.CONFLICT,reason);}
    private Map<String,Object> row(String kind,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT id,"+String.join(",",CatalogInput.columns(kind))+" FROM "+CatalogInput.table(kind)+" WHERE id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"目录项不存在");return normalize(rows.get(0));
    }
    private Map<String,Object> normalize(Map<String,Object> row) {
        var result=new LinkedHashMap<>(row);
        for(String key:List.of("source_note","quality_standard","config_name","power_type"))if(result.containsKey(key) && result.get(key)==null)result.put(key,"");
        for(String key:List.of("base_price_low","base_price_high"))if(result.get(key)!=null)result.put(key,((java.math.BigDecimal)result.get(key)).setScale(2).toPlainString());
        return result;
    }
    public Map<String,Object> list(OperatorActor actor,String kind,int page,int size) {
        CatalogInput.table(kind);if(page<1 || page>1000000 || size<1 || size>50)throw CatalogInput.bad();
        return reads.execute(t->{authorize(actor,false);String from=" FROM "+CatalogInput.table(kind)+" WHERE is_deleted=0";
            var items=jdbc.queryForList("SELECT id,"+String.join(",",CatalogInput.columns(kind))+from+" ORDER BY sort_order,id LIMIT ? OFFSET ?",size,(page-1)*size).stream().map(this::normalize).toList();
            return Map.of("items",items,"total",jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class),"page",page,"page_size",size);});
    }
    private static Object value(JsonNode node) {return node.isIntegralNumber()?node.longValue():node.textValue();}
    /** Lock hierarchy top-down. Parent association is immutable, including while unpublished. */
    private void parents(String kind,long id,JsonNode body,boolean publish) {
        if(kind.equals("projects") || kind.equals("brands"))return;
        String parentKey=kind.equals("series")?"brand_id":"series_id";long parent=body.get(parentKey).longValue();
        if(id>0){var old=row(kind,id,false);if(((Number)old.get(parentKey)).longValue()!=parent)throw conflict("目录项不可更换上级");}
        if(kind.equals("models")) {
            var series=row("series",parent,false);var brand=row("brands",((Number)series.get("brand_id")).longValue(),true);
            if(publish && ((Number)brand.get("status")).intValue()!=1)throw conflict("品牌未发布，不能发布车型");
        }
        var ancestor=row(kind.equals("series")?"brands":"series",parent,true);
        if(publish && ((Number)ancestor.get("status")).intValue()!=1)throw conflict("上级目录未发布");
    }
    private boolean referenced(String kind,long id) { String from=switch(kind) {
        case "brands" -> "vehicle v JOIN model m ON m.id=v.model_id JOIN series s ON s.id=m.series_id WHERE s.brand_id=?";
        case "series" -> "vehicle v JOIN model m ON m.id=v.model_id WHERE m.series_id=?";
        case "models" -> "vehicle v WHERE v.model_id=?"; default -> null;};
        return from!=null && !jdbc.queryForList("SELECT v.id FROM "+from+" LIMIT 1 FOR SHARE",id).isEmpty();
    }
    public JsonNode save(OperatorActor actor,String kind,long id,String key,JsonNode body) {
        CatalogInput.table(kind);if(id>0)CatalogInput.id(id);
        String path="/api/admin/catalog/"+kind+(id>0?"/"+id:"");
        return integrity.execute(new WriteIntegrityService.Actor("operator_account",actor.id()),id>0?"PUT":"POST",path,key,body,
            ()->authorize(actor,true),()->{
                boolean publish=id>0 && body.get("status").intValue()==1;parents(kind,id,body,publish);
                Map<String,Object> old=id>0?row(kind,id,true):Map.of();
                if(id>0 && ((Number)old.get("revision")).longValue()!=body.get("revision").longValue())throw conflict("目录已更新，请刷新后重试");
                if(id>0 && referenced(kind,id))for(String field:CatalogInput.identity(kind))
                    if(!Objects.equals(Objects.toString(old.get(field),""),body.get(field).asText()))throw conflict("已关联车辆的车型身份不可改写");
                // Shared parent locks serialize sibling uniqueness checks across operators.
                if(kind.equals("series") || kind.equals("models")) {
                    String filter=kind.equals("series")?"brand_id=? AND name=?":"series_id=? AND year=? AND config_name=?";
                    Object[] args=kind.equals("series")?new Object[]{body.get("brand_id").longValue(),body.get("name").textValue(),id}:
                        new Object[]{body.get("series_id").longValue(),body.get("year").textValue(),body.get("config_name").textValue(),id};
                    if(!jdbc.queryForList("SELECT id FROM "+CatalogInput.table(kind)+" WHERE "+filter+" AND id<>? AND is_deleted=0 FOR UPDATE",args).isEmpty())throw conflict("同级目录名称或配置已存在");
                }
                var columns=new ArrayList<>(CatalogInput.identity(kind));columns.addAll(List.of("sort_order","source_note"));
                var args=new ArrayList<Object>();for(String column:columns)args.add(value(body.get(column)));
                long target=id;
                try {
                    if(id==0){columns.add("status");args.add(0);var holder=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement("INSERT INTO "+CatalogInput.table(kind)+"("+String.join(",",columns)+") VALUES("+String.join(",",Collections.nCopies(columns.size(),"?"))+")",Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.size();i++)s.setObject(i+1,args.get(i));return s;},holder);target=holder.getKey().longValue();}
                    else {columns.add("status");args.add(body.get("status").intValue());args.add(id);jdbc.update("UPDATE "+CatalogInput.table(kind)+" SET "+String.join(",",columns.stream().map(c->c+"=?").toList())+",revision=revision+1 WHERE id=?",args.toArray());}
                }catch(DuplicateKeyException e){throw conflict("目录名称已存在");}
                var after=row(kind,target,false);
                return new WriteIntegrityService.Change("CATALOG_SAVE",CatalogInput.table(kind),target,old,after,after);
            });
    }
}
