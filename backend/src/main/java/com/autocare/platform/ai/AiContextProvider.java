package com.autocare.platform.ai;

import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Builds the archive-aware context that grounds an owner's AI conversation.
 * Only the owner's own vehicle is readable; plate number and VIN are never included,
 * because the context leaves the platform for a third-party model.
 */
public class AiContextProvider {
    private static final int MAX_ARCHIVES = 5;
    private static final int MAX_NOTE_CHARS = 200;
    private static final int MAX_CONTEXT_CHARS = 1800;
    private static final List<String> ARCHIVE_TYPES =
        List.of("保养", "维修", "保险", "事故", "改装", "违章", "年检");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AiContextProvider(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Context(long vehicleId, String modelName, String summary) {}

    record Archive(String date, int archiveType, String title, String notes, Integer mileage) {}

    public Context forVehicle(VehicleOwner owner, long vehicleId) {
        if (vehicleId <= 0 || vehicleId > 9007199254740991L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "车辆参数无效");
        }
        List<Map<String, Object>> vehicles = jdbc.query(
            "SELECT v.id,v.current_mileage,v.power_type,CONCAT_WS(' ',b.name,s.name,m.year,m.config_name) AS model_name "
                + "FROM vehicle v LEFT JOIN model m ON m.id=v.model_id LEFT JOIN series s ON s.id=m.series_id "
                + "LEFT JOIN brand b ON b.id=s.brand_id WHERE v.id=? AND v.user_id=? AND v.is_deleted=0",
            (rs, n) -> Map.of(
                "mileage", rs.getInt("current_mileage"),
                "power", rs.getString("power_type") == null ? "" : rs.getString("power_type"),
                "name", rs.getString("model_name") == null ? "" : rs.getString("model_name")),
            vehicleId, owner.id());
        if (vehicles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆或档案不可用");
        }
        Map<String, Object> vehicle = vehicles.get(0);
        List<Archive> archives = jdbc.query(
            "SELECT a.archive_type,a.content,DATE_FORMAT(a.recorded_at,'%Y-%m-%d') AS recorded_date "
                + "FROM vehicle_archive a WHERE a.vehicle_id=? AND a.is_deleted=0 AND a.input_type IN (1,3) "
                + "ORDER BY a.recorded_at DESC,a.id DESC LIMIT ?",
            (rs, n) -> parseArchive(mapper, rs.getInt("archive_type"), rs.getString("content"), rs.getString("recorded_date")),
            vehicleId, MAX_ARCHIVES);
        String name = (String) vehicle.get("name");
        return new Context(vehicleId, name, describe(name, (Integer) vehicle.get("mileage"),
            (String) vehicle.get("power"), archives));
    }

    static Archive parseArchive(ObjectMapper mapper, int archiveType, String content, String date) {
        String title = "", notes = "";
        Integer mileage = null;
        try {
            JsonNode parsed = mapper.readTree(content);
            if (parsed != null && parsed.isObject()) {
                title = parsed.path("title").asText("");
                notes = parsed.path("notes").asText("");
                if (parsed.path("mileage").isNumber()) {
                    mileage = parsed.path("mileage").intValue();
                }
            }
        } catch (Exception ignored) {
            // 单条档案解析失败不应阻断对话，按无内容处理。
        }
        return new Archive(date == null ? "" : date, archiveType, singleLine(title, 60), singleLine(notes, MAX_NOTE_CHARS), mileage);
    }

    static String describe(String name, Integer mileage, String power, List<Archive> archives) {
        List<String> lines = new ArrayList<>();
        lines.add("车型：" + (name == null || name.isBlank() ? "未知" : name));
        lines.add("当前里程：" + mileage + " km");
        if (power != null && !power.isBlank()) {
            lines.add("动力类型：" + power);
        }
        if (archives.isEmpty()) {
            lines.add("该车暂无可用的历史养护档案。");
        } else {
            lines.add("近期养护档案（倒序，最多 " + MAX_ARCHIVES + " 条）：");
            for (Archive archive : archives) {
                StringBuilder line = new StringBuilder("- ").append(archive.date()).append(' ')
                    .append(typeName(archive.archiveType())).append("：")
                    .append(archive.title().isBlank() ? "未填写标题" : archive.title());
                if (archive.mileage() != null) {
                    line.append("（里程 ").append(archive.mileage()).append(" km）");
                }
                if (!archive.notes().isBlank()) {
                    line.append("；备注：").append(archive.notes());
                }
                lines.add(line.toString());
            }
        }
        return cap(String.join("\n", lines), MAX_CONTEXT_CHARS);
    }

    private static String typeName(int archiveType) {
        return archiveType >= 1 && archiveType <= ARCHIVE_TYPES.size() ? ARCHIVE_TYPES.get(archiveType - 1) : "其他";
    }

    /** 单行字段：折叠空白并截断，避免换行破坏上下文结构。 */
    static String singleLine(String value, int max) {
        if (value == null) return "";
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return cap(collapsed, max);
    }

    /** 保留换行，仅按长度截断。 */
    static String cap(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
