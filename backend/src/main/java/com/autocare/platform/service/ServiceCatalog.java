package com.autocare.platform.service;

import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.vehicle.VehicleService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class ServiceCatalog {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate reads;
    public ServiceCatalog(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public static void validate(Integer category, int page, int size) {
        VehicleService.page(page, size);
        if (category != null && (category < 1 || category > 6))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务分类无效");
    }
    public static void validateId(long id) {
        if (id <= 0 || id > 9007199254740991L)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目编号无效");
    }
    private void authorize(VehicleOwner owner) {
        if (!Instant.now().isBefore(owner.expires()) || jdbc.queryForObject(
            "SELECT COUNT(*) FROM auth_session s JOIN user u ON u.id=s.subject_id "
            + "WHERE s.id=? AND s.subject_id=? AND s.subject_type='user' AND s.role='OWNER' "
            + "AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP() AND u.status=1 AND u.is_deleted=0",
            Integer.class, owner.session(), owner.id()) != 1)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    private Map<String,Object> row(ResultSet rs, boolean detail) throws SQLException {
        var result = new LinkedHashMap<String,Object>();
        result.put("id", rs.getLong("id"));
        result.put("project_name", rs.getString("project_name"));
        result.put("category", rs.getInt("category"));
        result.put("base_price_low", rs.getBigDecimal("base_price_low").setScale(2).toPlainString());
        result.put("base_price_high", rs.getBigDecimal("base_price_high").setScale(2).toPlainString());
        if (detail) {
            result.put("service_content", rs.getString("service_content"));
            result.put("quality_standard", rs.getString("quality_standard"));
        }
        return result;
    }
    public Map<String,Object> list(VehicleOwner owner, Integer category, int page, int size) {
        validate(category,page,size);
        return reads.execute(status -> {
            authorize(owner);
            String filter = " FROM standard_project WHERE status=1 AND is_deleted=0" + (category == null ? "" : " AND category=?");
            Object[] countArgs = category == null ? new Object[]{} : new Object[]{category};
            Object[] rowArgs = category == null ? new Object[]{size,(page-1)*size} : new Object[]{category,size,(page-1)*size};
            long total = jdbc.queryForObject("SELECT COUNT(*)" + filter, Long.class, countArgs);
            var items = jdbc.query("SELECT id,project_name,category,base_price_low,base_price_high" + filter
                + " ORDER BY id ASC LIMIT ? OFFSET ?", (rs,n) -> row(rs,false), rowArgs);
            return Map.of("items",items,"total",total,"page",page,"page_size",size);
        });
    }
    public Map<String,Object> detail(VehicleOwner owner, long id) {
        validateId(id);
        return reads.execute(status -> {
            authorize(owner);
            var items = jdbc.query("SELECT id,project_name,category,base_price_low,base_price_high,service_content,quality_standard "
                + "FROM standard_project WHERE id=? AND status=1 AND is_deleted=0", (rs,n) -> row(rs,true), id);
            if (items.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"项目不存在或已停用");
            return items.get(0);
        });
    }
}
