package com.autocare.platform.vehicle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Reads one bounded, owner-authorized snapshot. PDF rendering occurs after execute() commits. */
public class ArchiveReportService {
    public static final int MAX_ENTRIES = 500;
    private static final LocalDate LAST_MYSQL_DATE = LocalDate.of(9999, 12, 31);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate snapshot;

    public ArchiveReportService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ArchiveReport load(VehicleOwner owner, ArchiveReportFilter filter) {
        return snapshot.execute(status -> loadSnapshot(owner, filter, true));
    }
    public ArchiveReport loadShared(VehicleOwner owner,ArchiveReportFilter filter){return snapshot.execute(status->loadSnapshot(owner,filter,false));}

    private ArchiveReport loadSnapshot(VehicleOwner owner, ArchiveReportFilter filter,boolean privateKinds) {
        if (owner == null || filter == null || !Instant.now().isBefore(owner.expires())) throw expired();
        if (jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='user' AND subject_id=? "
                + "AND role='OWNER' AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()",
                (rs, n) -> rs.getString(1), owner.session(), owner.id()).isEmpty()
            || jdbc.query("SELECT id FROM user WHERE id=? AND status=1 AND is_deleted=0",
                (rs, n) -> rs.getLong(1), owner.id()).isEmpty()) throw expired();

        var vehicles = jdbc.query("SELECT v.current_mileage,v.plate_no,v.vin,"
                + "CONCAT_WS(' ',b.name,s.name,m.year,m.config_name) AS model_name "
                + "FROM vehicle v LEFT JOIN model m ON m.id=v.model_id "
                + "LEFT JOIN series s ON s.id=m.series_id LEFT JOIN brand b ON b.id=s.brand_id "
                + "WHERE v.id=? AND v.user_id=? AND v.is_deleted=0",
            (rs, n) -> new VehicleSummary(rs.getString("model_name"), mask(rs.getString("plate_no"), 2, 1),
                mask(rs.getString("vin"), 3, 4), nullableInteger(rs.getObject("current_mileage"))),
            filter.vehicleId(), owner.id());
        if (vehicles.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆或档案不可用");
        VehicleSummary vehicle = vehicles.get(0);

        String visibility = " AND (a.input_type IN (1,3) OR (a.input_type=4 "
            + "AND JSON_EXTRACT(a.content,'$.source.user_id')=?))";
        if(!privateKinds)visibility+=" AND a.archive_type<=7";
        long undated = 0;
        if (filter.from() != null) {
            undated = jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive a WHERE a.vehicle_id=? "
                + "AND a.is_deleted=0" + visibility + " AND a.recorded_at IS NULL",
                Long.class, filter.vehicleId(), owner.id());
        }
        String dateClause = filter.from() == null ? "" : " AND a.recorded_at>=? AND "
            + (LAST_MYSQL_DATE.equals(filter.to()) ? "DATE(a.recorded_at)<=?" : "a.recorded_at<DATE_ADD(?,INTERVAL 1 DAY)");
        List<Object> arguments = new ArrayList<>(List.of(filter.vehicleId(), owner.id()));
        if (filter.from() != null) {
            arguments.add(Date.valueOf(filter.from()));
            arguments.add(Date.valueOf(filter.to()));
        }
        arguments.add(MAX_ENTRIES + 1);
        List<ArchiveReport.Entry> entries = jdbc.query("SELECT a.archive_type,a.input_type,a.content,"
                + "DATE_FORMAT(a.recorded_at,'%Y-%m-%d') AS recorded_date,"
                + "(SELECT COUNT(*) FROM vehicle_archive_file af WHERE af.archive_id=a.id) AS photo_count "
                + "FROM vehicle_archive a WHERE a.vehicle_id=? AND a.is_deleted=0" + visibility + dateClause
                + " ORDER BY a.recorded_at IS NULL,a.recorded_at DESC,a.id DESC LIMIT ?",
            (rs, n) -> parseEntry(rs.getInt("archive_type"), rs.getInt("input_type"),
                rs.getString("content"), rs.getString("recorded_date"), rs.getInt("photo_count"), owner.id()),
            arguments.toArray());
        if (entries.size() > MAX_ENTRIES)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "档案记录超过导出上限，请缩小日期范围");
        return new ArchiveReport(vehicle.modelName(), vehicle.maskedPlate(), vehicle.maskedVin(),
            vehicle.registeredMileage(), filter.from(), filter.to(), Math.toIntExact(undated), Instant.now(), entries);
    }

    private ArchiveReport.Entry parseEntry(int archiveType, int inputType, String rawContent,
                                            String rawDate, int photoCount, long ownerId) {
        if (archiveType < 1 || archiveType > 10 || !List.of(1, 3, 4).contains(inputType) || photoCount < 0)
            throw unavailable();
        JsonNode content;
        try { content = mapper.readTree(rawContent); }
        catch (Exception exception) { throw unavailable(); }
        if (content == null || !content.isObject()) throw unavailable();
        LocalDate date = null;
        if (rawDate != null) {
            try { date = LocalDate.parse(rawDate); }
            catch (RuntimeException exception) { throw unavailable(); }
        }
        Integer mileage = content.path("mileage").isIntegralNumber() ? nullableInteger(content.path("mileage").numberValue()) : null;
        String title = text(content, "title");
        String notes = text(content, "notes");
        boolean testMode = false;
        int workMinutes = 0;
        boolean noParts = false;
        List<ArchiveReport.Part> parts = List.of();
        String submittedAt = null, signedAt = null, redeemedAt = null;
        if (inputType == 4) {
            JsonNode source = content.path("source");
            if (source.path("user_id").asLong(-1) != ownerId
                || !content.path("test_mode").isBoolean()
                || !content.path("work_minutes").canConvertToInt()
                || content.path("work_minutes").intValue() < 1 || content.path("work_minutes").intValue() > 1440
                || !content.path("no_parts").isBoolean() || !content.path("parts_used").isArray()) throw unavailable();
            testMode = content.path("test_mode").booleanValue();
            workMinutes = content.path("work_minutes").intValue();
            noParts = content.path("no_parts").booleanValue();
            List<ArchiveReport.Part> parsedParts = new ArrayList<>();
            if (content.path("parts_used").size() > 20) throw unavailable();
            for (JsonNode part : content.path("parts_used")) {
                if (!part.isObject() || !part.path("quantity").canConvertToInt()
                    || part.path("quantity").intValue() < 1 || part.path("quantity").intValue() > 999)
                    throw unavailable();
                parsedParts.add(new ArchiveReport.Part(text(part, "name"), text(part, "model"),
                    text(part, "brand"), part.path("quantity").intValue()));
            }
            if (noParts != parsedParts.isEmpty()) throw unavailable();
            parts = List.copyOf(parsedParts);
            submittedAt = nullableText(content, "submitted_at");
            signedAt = nullableText(content, "signed_at");
            redeemedAt = nullableText(content, "redeemed_at");
        }
        return new ArchiveReport.Entry(archiveType, inputType, date, title, notes, mileage, photoCount,
            testMode, workMinutes, noParts, parts, submittedAt, signedAt, redeemedAt);
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() ? value.textValue() : "";
    }

    private static String nullableText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static Integer nullableInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            long asLong = number.longValue();
            if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) return (int) asLong;
        }
        return null;
    }

    private static String mask(String value, int start, int end) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= start + end) return "***";
        return value.substring(0, start) + "*".repeat(value.length() - start - end) + value.substring(value.length() - end);
    }

    private static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "档案报告暂不可用，请稍后重试");
    }

    private record VehicleSummary(String modelName, String maskedPlate, String maskedVin, Integer registeredMileage) {}
}
