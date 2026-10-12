package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class ArchiveReminderService {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    private final TransactionTemplate reads;
    private final Clock clock;
    public ArchiveReminderService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper,
                                  PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc; this.integrity = integrity; this.mapper = mapper; this.clock = clock;
        this.reads = new TransactionTemplate(transactions); this.reads.setReadOnly(true); this.reads.setTimeout(15);
    }
    public Map<String, Object> context(VehicleOwner owner, long archive) {
        return reads.execute(tx -> {
            authorize(owner, false); Source source = source(archive); Integer current = ownVehicle(owner, source.vehicle(), false);
            var result = new LinkedHashMap<String, Object>();
            result.put("vehicle_id", source.vehicle()); result.put("archive_id", archive); result.put("archive_type", source.type());
            result.put("recorded_date", source.recorded()); result.put("current_mileage", current); result.put("today", today().toString());
            result.put("setting", view(slot(owner.id(), source.vehicle(), source.type(), false), current, source.type()));
            return result;
        });
    }
    public Map<String, Object> list(VehicleOwner owner, long vehicle) {
        return reads.execute(tx -> {
            authorize(owner, false); Integer current = ownVehicle(owner, vehicle, false);
            var rows = new ArrayList<Map<String, Object>>();
            for (int type : List.of(1, 3, 7)) rows.add(view(slot(owner.id(), vehicle, type, false), current, type));
            var result = new LinkedHashMap<String, Object>(); result.put("vehicle_id", vehicle); result.put("today", today().toString());
            result.put("current_mileage", current); result.put("list", rows); return result;
        });
    }
    public JsonNode save(VehicleOwner owner, long archive, String key, ArchiveReminderInput input) {
        final Source[] selected = new Source[1];
        return integrity.execute(new WriteIntegrityService.Actor("user", owner.id()), "PUT", "/api/archive/" + archive + "/reminder", key,
            mapper.valueToTree(input.canonical()), () -> {
                authorize(owner, true); Source source = source(archive); ownVehicle(owner, source.vehicle(), true);
                // Lock source after vehicle, including replay authorization.
                selected[0] = source(archive, true);
                if (!source.equals(selected[0])) throw conflict();
                input.validateFor(source.type());
            }, () -> {
                Source source = selected[0]; Slot before = slot(owner.id(), source.vehicle(), source.type(), true);
                if (input.revision() != (before == null ? 0 : before.revision())) throw conflict();
                if (!input.active() && (before == null || before.archive() != archive)) throw conflict();
                if (input.active() && before != null && before.archive() != archive && !input.replaceSource()) throw conflict();
                int next = input.revision() + 1;
                if (before == null) jdbc.update("INSERT INTO archive_reminder(user_id,vehicle_id,archive_type,archive_id,due_date,due_mileage,active,revision) VALUES (?,?,?,?,?,?,1,?)",
                    owner.id(), source.vehicle(), source.type(), archive, sqlDate(input.date()), input.mileage(), next);
                else if (input.active()) jdbc.update("UPDATE archive_reminder SET archive_id=?,due_date=?,due_mileage=?,active=1,revision=? WHERE id=?",
                    archive, sqlDate(input.date()), input.mileage(), next, before.id());
                else jdbc.update("UPDATE archive_reminder SET active=0,revision=? WHERE id=?", next, before.id());
                var after = slot(owner.id(), source.vehicle(), source.type(), true);
                return new WriteIntegrityService.Change(input.active() ? "ARCHIVE_REMINDER_SET" : "ARCHIVE_REMINDER_DISABLE", "archive_reminder", after.id(),
                    before == null ? Map.of() : audit(before), audit(after),
                    Map.of("vehicle_id", source.vehicle(), "archive_id", archive, "archive_type", source.type(), "revision", next, "active", input.active()));
            });
    }
    private Map<String, Object> audit(Slot row) { return Map.of("archive_id", row.archive(), "revision", row.revision(), "active", row.active()); }
    private Map<String, Object> view(Slot row, Integer current, int type) {
        var result = new LinkedHashMap<String, Object>();
        result.put("archive_type", type); result.put("revision", row == null ? 0 : row.revision());
        result.put("active", row != null && row.active());
        boolean valid = row != null && !jdbc.query("SELECT id FROM vehicle_archive WHERE id=? AND vehicle_id=? AND archive_type=? AND input_type IN (1,3) AND is_deleted=0",
            (rs, n) -> rs.getLong(1), row.archive(), row.vehicle(), type).isEmpty();
        result.put("archive_id", valid ? row.archive() : null);
        result.put("recorded_date", valid ? source(row.archive()).recorded() : null);
        result.put("due_date", valid && row.date() != null ? row.date().toString() : null);
        result.put("due_mileage", valid ? row.mileage() : null);
        List<String> triggers = valid && row.active() ? ArchiveReminderStatus.triggered(today(), current, row.date(), row.mileage()) : List.of();
        String status = row == null ? "NOT_SET" : !valid ? "UNAVAILABLE" : !row.active() ? "INACTIVE" : !triggers.isEmpty() ? "DUE"
            : row.mileage() != null && current == null ? "UNKNOWN" : "PLANNED";
        result.put("status", status); result.put("triggered_by", triggers); return result;
    }
    private Slot slot(long owner, long vehicle, int type, boolean lock) {
        var rows = jdbc.query("SELECT * FROM archive_reminder WHERE user_id=? AND vehicle_id=? AND archive_type=? AND is_deleted=0" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Slot(rs.getLong("id"), rs.getLong("vehicle_id"), rs.getLong("archive_id"), rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                rs.getObject("due_mileage") == null ? null : rs.getInt("due_mileage"), rs.getBoolean("active"), rs.getInt("revision")), owner, vehicle, type);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private Source source(long archive) { return source(archive, false); }
    private Source source(long archive, boolean lock) {
        if (archive <= 0 || archive > 9007199254740991L) throw WriteIntegrityService.badRequest("档案参数无效");
        var rows = jdbc.query("SELECT vehicle_id,archive_type,DATE_FORMAT(recorded_at,'%Y-%m-%d') AS recorded_date FROM vehicle_archive "
            + "WHERE id=? AND is_deleted=0 AND input_type IN (1,3) AND archive_type IN (1,3,7)" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Source(rs.getLong("vehicle_id"), rs.getInt("archive_type"), rs.getString("recorded_date")), archive);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源档案不可用，请选择本人保养、保险或年检记录");
        return rows.get(0);
    }
    private Integer ownVehicle(VehicleOwner owner, long vehicle, boolean lock) {
        if (vehicle <= 0 || vehicle > 9007199254740991L) throw WriteIntegrityService.badRequest("车辆参数无效");
        var rows = jdbc.query("SELECT current_mileage FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> rs.getObject(1) == null ? null : rs.getInt(1), vehicle, owner.id());
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆或来源档案不可用");
        return rows.get(0);
    }
    private void authorize(VehicleOwner owner, boolean lock) {
        if (owner == null || !Instant.now().isBefore(owner.expires())) throw expired();
        String suffix = lock ? " FOR UPDATE" : "";
        if (jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='user' AND subject_id=? AND role='OWNER' AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()" + suffix,
            (rs, n) -> rs.getString(1), owner.session(), owner.id()).isEmpty() || jdbc.query("SELECT id FROM user WHERE id=? AND status=1 AND is_deleted=0" + suffix,
            (rs, n) -> rs.getLong(1), owner.id()).isEmpty()) throw expired();
    }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))); }
    private static Date sqlDate(LocalDate date) { return date == null ? null : Date.valueOf(date); }
    private static ResponseStatusException expired() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "提醒设置已变化，请刷新后复核目标与来源"); }
    private record Source(long vehicle, int type, String recorded) {}
    private record Slot(long id, long vehicle, long archive, LocalDate date, Integer mileage, boolean active, int revision) {}
}
