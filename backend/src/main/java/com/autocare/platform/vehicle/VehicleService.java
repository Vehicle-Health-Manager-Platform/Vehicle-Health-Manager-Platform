package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;

public class VehicleService {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    public VehicleService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper) {
        this.jdbc = jdbc; this.integrity = integrity; this.mapper = mapper;
    }
    private void authorize(VehicleOwner owner, boolean lock) {
        if (!Instant.now().isBefore(owner.expires())) throw expired();
        String suffix = lock ? " FOR UPDATE" : "";
        var sessions = jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='user' AND subject_id=? "
            + "AND role='OWNER' AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()" + suffix,
            (rs, n) -> rs.getString(1), owner.session(), owner.id());
        var users = jdbc.query("SELECT id FROM user WHERE id=? AND status=1 AND is_deleted=0" + suffix,
            (rs, n) -> rs.getLong(1), owner.id());
        if (sessions.isEmpty() || users.isEmpty()) throw expired();
    }
    private static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    public static void page(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100) throw WriteIntegrityService.badRequest("分页参数无效");
    }
    private Map<String, Object> result(List<?> rows, long total, int page, int size) {
        return Map.of("list", rows, "total", total, "page", page, "page_size", size);
    }
    public Map<String, Object> list(VehicleOwner owner, int page, int size) {
        page(page, size); authorize(owner, false);
        var rows = jdbc.query("SELECT v.id,v.model_id,v.current_mileage,v.plate_no,v.vin,"
            + "CONCAT_WS(' ',b.name,s.name,m.year,m.config_name) AS model_name FROM vehicle v "
            + "LEFT JOIN model m ON m.id=v.model_id LEFT JOIN series s ON s.id=m.series_id "
            + "LEFT JOIN brand b ON b.id=s.brand_id WHERE v.user_id=? AND v.is_deleted=0 "
            + "ORDER BY v.id DESC LIMIT ? OFFSET ?", (rs,n) -> Map.<String,Object>of(
                "vehicle_id", rs.getLong("id"), "model_id", rs.getLong("model_id"),
                "model_name", rs.getString("model_name"), "current_mileage", rs.getInt("current_mileage"),
                "plate_no_masked", mask(rs.getString("plate_no"),2,1), "vin_masked", mask(rs.getString("vin"),3,4)),
            owner.id(), size, (page-1)*size);
        return result(rows, jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE user_id=? AND is_deleted=0", Long.class, owner.id()), page, size);
    }
    public Map<String, Object> catalog(VehicleOwner owner, String kind, long parent, int page, int size) {
        page(page, size); authorize(owner, false);
        String from, select;
        Object[] countArgs, rowArgs;
        if ("brand".equals(kind)) {
            from = " FROM brand b WHERE b.is_deleted=0"; select = "SELECT b.id,b.name";
            countArgs = new Object[]{}; rowArgs = new Object[]{size,(page-1)*size};
        } else {
            if (parent <= 0 || parent > 9007199254740991L) throw WriteIntegrityService.badRequest("上级车型参数无效");
            if ("series".equals(kind)) {
                from = " FROM series s JOIN brand b ON b.id=s.brand_id WHERE s.is_deleted=0 AND b.is_deleted=0 AND b.id=?";
                select = "SELECT s.id,s.name";
            } else if ("model".equals(kind)) {
                from = " FROM model m JOIN series s ON s.id=m.series_id JOIN brand b ON b.id=s.brand_id "
                    + "WHERE m.is_deleted=0 AND s.is_deleted=0 AND b.is_deleted=0 AND s.id=?";
                select = "SELECT m.id,m.year,COALESCE(m.config_name,'') AS config_name";
            } else throw new IllegalArgumentException("Invalid server catalog kind");
            countArgs = new Object[]{parent}; rowArgs = new Object[]{parent,size,(page-1)*size};
        }
        String order = "model".equals(kind) ? "m.id" : "series".equals(kind) ? "s.id" : "b.id";
        var rows = jdbc.query(select + from + " ORDER BY " + order + " LIMIT ? OFFSET ?", (rs,n) ->
            "model".equals(kind) ? Map.<String,Object>of("id",rs.getLong("id"),"year",rs.getString("year"),"config_name",rs.getString("config_name"))
            : Map.<String,Object>of("id",rs.getLong("id"),"name",rs.getString("name")), rowArgs);
        return result(rows, jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, countArgs), page, size);
    }
    public JsonNode add(VehicleOwner owner, String key, VehicleInput input) {
        return integrity.execute(new WriteIntegrityService.Actor("user",owner.id()), "POST", "/api/vehicle/add", key,
            mapper.valueToTree(input.canonical()), () -> authorize(owner,true), () -> {
                var models = jdbc.query("SELECT CONCAT_WS(' ',b.name,s.name,m.year,m.config_name),m.power_type "
                    + "FROM model m JOIN series s ON s.id=m.series_id JOIN brand b ON b.id=s.brand_id "
                    + "WHERE m.id=? AND m.is_deleted=0 AND s.is_deleted=0 AND b.is_deleted=0 FOR SHARE",
                    (rs,n) -> new String[]{rs.getString(1),rs.getString(2)}, input.modelId());
                if (models.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"车型不存在或已停用，请重新选择");
                int duplicates = jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE user_id=? AND is_deleted=0 "
                    + "AND ((?<>'' AND plate_no=?) OR (?<>'' AND vin=?))", Integer.class,
                    owner.id(),input.plate(),input.plate(),input.vin(),input.vin());
                if (duplicates > 0) throw new ResponseStatusException(HttpStatus.CONFLICT,"该车牌或VIN已在本人车辆中登记");
                var generated = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO vehicle(user_id,model_id,current_mileage,plate_no,vin,power_type) VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1,owner.id()); statement.setLong(2,input.modelId()); statement.setInt(3,input.mileage());
                    statement.setString(4,input.plate().isEmpty()?null:input.plate()); statement.setString(5,input.vin().isEmpty()?null:input.vin());
                    statement.setString(6,models.get(0)[1]); return statement;
                }, generated);
                long id = generated.getKey().longValue();
                return new WriteIntegrityService.Change("VEHICLE_CREATE","vehicle",id, Map.of(),
                    Map.of("vehicle_id",id,"model_id",input.modelId(),"current_mileage",input.mileage()),
                    Map.of("vehicle_id",id,"model_name",models.get(0)[0],"need_archive",true));
            });
    }
    private static String mask(String value, int start, int end) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= start+end) return "***";
        return value.substring(0,start) + "*".repeat(value.length()-start-end) + value.substring(value.length()-end);
    }
}
