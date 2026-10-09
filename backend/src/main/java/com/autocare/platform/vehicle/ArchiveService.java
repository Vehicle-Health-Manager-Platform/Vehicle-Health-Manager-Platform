package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;

public class ArchiveService {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;

    public ArchiveService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper) {
        this.jdbc = jdbc; this.integrity = integrity; this.mapper = mapper;
    }

    private void authorize(VehicleOwner owner, boolean lock) {
        if (!Instant.now().isBefore(owner.expires())) throw expired();
        String suffix = lock ? " FOR UPDATE" : "";
        boolean session = !jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='user' AND subject_id=? "
            + "AND role='OWNER' AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()" + suffix,
            (rs, n) -> rs.getString(1), owner.session(), owner.id()).isEmpty();
        boolean user = !jdbc.query("SELECT id FROM user WHERE id=? AND status=1 AND is_deleted=0" + suffix,
            (rs, n) -> rs.getLong(1), owner.id()).isEmpty();
        if (!session || !user) throw expired();
    }

    private void ownVehicle(VehicleOwner owner, long vehicleId, boolean lock) {
        if (vehicleId <= 0 || vehicleId > 9007199254740991L) throw WriteIntegrityService.badRequest("车辆参数无效");
        String suffix = lock ? " FOR UPDATE" : "";
        if (jdbc.query("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0" + suffix,
            (rs, n) -> rs.getLong(1), vehicleId, owner.id()).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆或档案不可用");
    }

    private static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }

    public JsonNode add(VehicleOwner owner, String key, ArchiveInput input) {
        return integrity.execute(new WriteIntegrityService.Actor("user", owner.id()), "POST", "/api/archive/add", key,
            mapper.valueToTree(input.canonical()), () -> { authorize(owner, true); ownVehicle(owner, input.vehicleId(), true); }, () -> {
                for (long fileId : input.fileIds()) {
                    boolean allowed = !jdbc.query("SELECT id FROM file_object WHERE id=? AND owner_type='user' "
                        + "AND owner_id=? AND scan_status='CLEAN' AND is_deleted=0 FOR SHARE",
                        (rs, n) -> rs.getLong(1), fileId, owner.id()).isEmpty();
                    if (!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆或图片不可用");
                }
                String content;
                try {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("version", 1); fields.put("title", input.title());
                    fields.put("notes", input.notes()); fields.put("mileage", input.mileage());
                    content = mapper.writeValueAsString(fields);
                } catch (Exception exception) { throw new IllegalStateException("Archive serialization failed"); }
                var generated = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO vehicle_archive "
                        + "(vehicle_id,archive_type,content,input_type,recorded_at) VALUES (?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, input.vehicleId()); statement.setInt(2, input.archiveType());
                    statement.setString(3, content); statement.setInt(4, input.inputType());
                    statement.setDate(5, Date.valueOf(input.recordedDate()));
                    return statement;
                }, generated);
                long archiveId = generated.getKey().longValue();
                for (int position = 0; position < input.fileIds().size(); position++) {
                    jdbc.update("INSERT INTO vehicle_archive_file (archive_id,file_id,position) VALUES (?,?,?)",
                        archiveId, input.fileIds().get(position), position);
                }
                return new WriteIntegrityService.Change("ARCHIVE_CREATE", "vehicle_archive", archiveId, Map.of(),
                    Map.of("archive_id", archiveId, "vehicle_id", input.vehicleId(), "archive_type", input.archiveType(),
                        "input_type", input.inputType(),
                        "file_count", input.fileIds().size()),
                    Map.of("archive_id", archiveId, "vehicle_id", input.vehicleId()));
            });
    }

    public Map<String, Object> list(VehicleOwner owner, long vehicleId, int page, int size) {
        VehicleService.page(page, size);
        authorize(owner, false);
        ownVehicle(owner, vehicleId, false);
        List<Map<String, Object>> rows = jdbc.query("SELECT a.id,a.archive_type,a.input_type,a.content,"
            + "DATE_FORMAT(a.recorded_at,'%Y-%m-%d') AS recorded_date,a.created_at "
            + "FROM vehicle_archive a WHERE a.vehicle_id=? AND a.is_deleted=0 AND (a.input_type IN (1,3) OR (a.input_type=4 AND JSON_EXTRACT(a.content,'$.source.user_id')=?)) "
            + "ORDER BY a.recorded_at DESC,a.id DESC LIMIT ? OFFSET ?", (rs, n) -> {
                JsonNode content;
                try { content = mapper.readTree(rs.getString("content")); }
                catch (Exception exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "档案暂不可用，请稍后重试"); }
                if (content == null || !content.isObject())
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "档案暂不可用，请稍后重试");
                long archiveId = rs.getLong("id");
                List<Long> files = jdbc.query("SELECT file_id FROM vehicle_archive_file WHERE archive_id=? ORDER BY position",
                    (file, i) -> file.getLong(1), archiveId);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("archive_id", archiveId); row.put("vehicle_id", vehicleId);
                row.put("archive_type", rs.getInt("archive_type"));
                row.put("input_type", rs.getInt("input_type"));
                row.put("recorded_date", rs.getString("recorded_date"));
                row.put("title", content.path("title").asText(""));
                row.put("notes", content.path("notes").asText(""));
                row.put("mileage", content.path("mileage").isNumber() ? content.path("mileage").intValue() : null);
                row.put("file_ids", files); row.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                if (rs.getInt("input_type") == 4) {
                    var source = content.path("source");
                    row.put("source", Map.of("order_id", source.path("order_id").asLong(), "report_id", source.path("report_id").asLong(),
                        "review_id", source.path("review_id").asLong(), "redemption_id", source.path("redemption_id").asLong()));
                    row.put("test_mode", content.path("test_mode").asBoolean());
                    row.put("parts_used", content.path("parts_used")); row.put("no_parts", content.path("no_parts").asBoolean());
                    row.put("work_minutes", content.path("work_minutes").asInt());
                    for (String time : List.of("submitted_at", "signed_at", "redeemed_at")) row.put(time, content.path(time).asText());
                }
                return row;
            }, vehicleId, owner.id(), size, (page - 1) * size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive a WHERE a.vehicle_id=? AND a.is_deleted=0 AND (a.input_type IN (1,3) OR (a.input_type=4 AND JSON_EXTRACT(a.content,'$.source.user_id')=?))",
            Long.class, vehicleId, owner.id());
        return Map.of("list", rows, "total", total, "page", page, "page_size", size);
    }

    public com.autocare.platform.file.FileMetadataRepository.Actor fileOwner(VehicleOwner owner, long archiveId, long fileId) {
        authorize(owner, false);
        var rows = jdbc.queryForList("SELECT f.owner_type,f.owner_id FROM vehicle_archive a "
            + "JOIN vehicle v ON v.id=a.vehicle_id AND v.user_id=? AND v.is_deleted=0 "
            + "JOIN service_archive_job j ON j.archive_id=a.id AND j.status='DONE' "
            + "JOIN `order` o ON o.id=j.order_id AND o.user_id=? AND o.vehicle_id=v.id AND o.is_deleted=0 "
            + "JOIN vehicle_archive_file af ON af.archive_id=a.id AND af.file_id=? "
            + "JOIN service_evidence_file e ON e.order_id=o.id AND e.file_id=af.file_id "
            + "AND e.record_id=JSON_EXTRACT(a.content,'$.source.report_id') AND e.kind IN ('PROCESS','FAULT','FINISH') "
            + "JOIN technician_report t ON t.id=e.record_id AND t.order_id=o.id AND t.is_deleted=0 AND t.status=1 AND t.signed_at IS NOT NULL "
            + "JOIN file_object f ON f.id=af.file_id AND f.owner_type='staff_account' AND f.owner_id=t.technician_id "
            + "AND f.scan_status='CLEAN' AND f.is_deleted=0 AND f.content_type IN ('image/jpeg','image/png') AND f.size_bytes BETWEEN 1 AND 10485760 "
            + "WHERE a.id=? AND a.is_deleted=0 AND a.input_type=4 AND JSON_EXTRACT(a.content,'$.source.user_id')=?",
            owner.id(), owner.id(), fileId, archiveId, owner.id());
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "档案或图片不可用");
        var file = rows.get(0);
        return new com.autocare.platform.file.FileMetadataRepository.Actor(String.valueOf(file.get("owner_type")), ((Number) file.get("owner_id")).longValue());
    }
}
