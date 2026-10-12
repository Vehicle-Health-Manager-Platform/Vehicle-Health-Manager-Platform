package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class ArchiveShareService {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ArchiveReportService reports;
    private final ArchiveShareTokens tokens;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    public ArchiveShareService(JdbcTemplate jdbc, WriteIntegrityService integrity, ArchiveReportService reports,
                               ObjectMapper mapper, PlatformTransactionManager transactions, String secret) {
        this.jdbc = jdbc; this.integrity = integrity; this.reports = reports; this.mapper = mapper;
        this.tokens = new ArchiveShareTokens(secret); this.tx = new TransactionTemplate(transactions);
        this.tx.setTimeout(15);
    }
    public JsonNode create(VehicleOwner owner, String key, ArchiveShareInput input) {
        long vehicle = input.filter().vehicleId();
        return integrity.execute(new WriteIntegrityService.Actor("user", owner.id()), "POST", "/api/archive/shares", key,
            input.request(), () -> { authorize(owner, true); ownVehicle(owner.id(), vehicle, true); }, () -> {
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM archive_report_share WHERE vehicle_id=? AND owner_id=? "
                    + "AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP() AND is_deleted=0", Integer.class, vehicle, owner.id());
                if (count >= 20) throw new ResponseStatusException(HttpStatus.CONFLICT, "有效分享已达 20 条，请先撤销旧分享");
                var report = reports.load(owner, input.filter());
                String publicId = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO archive_report_share(public_id,token_hash,owner_id,vehicle_id,summary,expires_at) "
                    + "VALUES (?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL ? HOUR))", publicId, ArchiveShareTokens.hash(tokens.derive(publicId)),
                    owner.id(), vehicle, ArchiveShareSummary.from(report, mapper).toString(), input.hours());
                long id = jdbc.queryForObject("SELECT id FROM archive_report_share WHERE public_id=?", Long.class, publicId);
                return new WriteIntegrityService.Change("ARCHIVE_SHARE_CREATE", "archive_report_share", id,
                    Map.of(), Map.of("vehicle_id", vehicle, "expires_in_hours", input.hours()), metadata(load(id, false)));
            });
    }
    public Map<String, Object> list(VehicleOwner owner, long vehicle) {
        return tx.execute(status -> {
            authorize(owner, false); ownVehicle(owner.id(), vehicle, false);
            var ids = jdbc.query("SELECT id FROM archive_report_share WHERE owner_id=? AND vehicle_id=? AND is_deleted=0 ORDER BY id DESC LIMIT 100",
                (rs, n) -> rs.getLong(1), owner.id(), vehicle);
            return Map.of("list", ids.stream().map(id -> metadata(load(id, false))).toList());
        });
    }
    public Map<String, Object> link(VehicleOwner owner, long id) {
        return tx.execute(status -> {
            authorize(owner, false); var row = load(id, true); owned(owner, row); usable(row);
            return Map.of("share_id", id, "path", "/pages/archive/shared?token=" + tokens.derive(row.publicId()), "expires_at", row.expires());
        });
    }
    public JsonNode revoke(VehicleOwner owner, long id, String key, JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 0) throw WriteIntegrityService.badRequest("撤销请求应为空对象");
        return integrity.execute(new WriteIntegrityService.Actor("user", owner.id()), "POST", "/api/archive/shares/" + id + "/revoke", key, body,
            () -> { authorize(owner, true); var row = load(id, true); owned(owner, row); }, () -> {
                jdbc.update("UPDATE archive_report_share SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP()) WHERE id=?", id);
                return new WriteIntegrityService.Change("ARCHIVE_SHARE_REVOKE", "archive_report_share", id, Map.of(),
                    Map.of("revoked", true), metadata(load(id, true)));
            });
    }
    public Map<String, Object> view(VehicleOwner recipient, String token) {
        if (!ArchiveShareTokens.valid(token)) throw gone();
        return tx.execute(status -> {
            authorize(recipient, false);
            var ids = jdbc.query("SELECT id FROM archive_report_share WHERE token_hash=? AND is_deleted=0", (rs, n) -> rs.getLong(1), ArchiveShareTokens.hash(token));
            if (ids.size() != 1) throw gone();
            var row = load(ids.get(0), true); usable(row);
            if (jdbc.query("SELECT id FROM user WHERE id=? AND status=1 AND is_deleted=0", (rs, n) -> rs.getLong(1), row.owner()).isEmpty()) throw gone();
            if (jdbc.query("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0", (rs, n) -> rs.getLong(1), row.vehicle(), row.owner()).isEmpty()) throw gone();
            JsonNode summary = ArchiveShareSummary.read(row.summary(), mapper);
            jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) "
                + "VALUES ('user',?,'ARCHIVE_SHARE_VIEW','archive_report_share',?,'{}','{}',?)", recipient.id(), row.id(), UUID.randomUUID().toString());
            return Map.of("summary", summary, "expires_at", row.expires());
        });
    }
    private void authorize(VehicleOwner owner, boolean lock) {
        if (owner == null || !Instant.now().isBefore(owner.expires()) || jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='user' AND subject_id=? "
            + "AND role='OWNER' AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> rs.getString(1), owner.session(), owner.id()).isEmpty()
            || jdbc.query("SELECT id FROM user WHERE id=? AND status=1 AND is_deleted=0" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> rs.getLong(1), owner.id()).isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    private void ownVehicle(long owner, long vehicle, boolean lock) {
        if (vehicle <= 0 || vehicle > 9007199254740991L) throw WriteIntegrityService.badRequest("车辆参数无效");
        if (jdbc.query("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> rs.getLong(1), vehicle, owner).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆或分享不可用");
    }
    private void owned(VehicleOwner owner, Row row) {
        if (row.owner() != owner.id()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆或分享不可用");
        ownVehicle(owner.id(), row.vehicle(), false);
    }
    private Row load(long id, boolean lock) {
        if (id <= 0 || id > 9007199254740991L) throw WriteIntegrityService.badRequest("分享参数无效");
        var rows = jdbc.query("SELECT *,expires_at>UTC_TIMESTAMP() AS live FROM archive_report_share WHERE id=? AND is_deleted=0" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Row(rs.getLong("id"), rs.getString("public_id"), rs.getString("token_hash"), rs.getLong("owner_id"),
                rs.getLong("vehicle_id"), rs.getString("summary"), rs.getTimestamp("expires_at").toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).toString(),
                rs.getTimestamp("created_at").toLocalDateTime().toInstant(java.time.ZoneOffset.UTC).toString(), rs.getTimestamp("revoked_at") != null, rs.getBoolean("live")), id);
        if (rows.size() != 1) throw gone();
        return rows.get(0);
    }
    private Map<String, Object> metadata(Row row) {
        return Map.of("share_id", row.id(), "vehicle_id", row.vehicle(), "created_at", row.created(), "expires_at", row.expires(),
            "status", row.revoked() ? "REVOKED" : !row.live() || !tokens.matches(row.publicId(), row.hash()) ? "EXPIRED" : "ACTIVE");
    }
    private void usable(Row row) { if (row.revoked() || !row.live() || !tokens.matches(row.publicId(), row.hash())) throw gone(); }
    private static ResponseStatusException gone() { return new ResponseStatusException(HttpStatus.GONE, "分享链接已失效，请联系车主重新分享"); }
    private record Row(long id, String publicId, String hash, long owner, long vehicle, String summary, String expires, String created, boolean revoked, boolean live) {}
}
