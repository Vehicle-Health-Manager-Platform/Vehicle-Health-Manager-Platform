package com.autocare.platform.order;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneOffset;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Database outbox: producers join the review transaction, consumers own a new transaction. */
public class ServiceArchiveJobs {
    private static final Logger log = LoggerFactory.getLogger(ServiceArchiveJobs.class);
    private final ReservationStore db;
    private final boolean enabled;
    private final boolean cardsEnabled;
    public ServiceArchiveJobs(ReservationStore db, boolean enabled) { this(db, enabled, false); }
    public ServiceArchiveJobs(ReservationStore db, boolean enabled, boolean cardsEnabled) { this.db = db; this.enabled = enabled; this.cardsEnabled = cardsEnabled; }
    static final class InvalidSource extends RuntimeException {}
    private static void require(boolean value) { if (!value) throw new InvalidSource(); }

    static JsonNode snapshot(ReservationStore db, long orderId, long reviewId) {
        var o = db.one("SELECT * FROM `order` WHERE id=? AND is_deleted=0", orderId);
        var r = db.one("SELECT * FROM order_redemption WHERE order_id=?", orderId);
        var review = db.one("SELECT * FROM order_review WHERE id=? AND order_id=?", reviewId, orderId);
        require(new OrderReviews(db).unavailable(o, r, false) == null);
        require(Objects.equals(review.get("user_id"), o.get("user_id"))
            && Objects.equals(review.get("redemption_id"), r.get("id"))
            && Objects.equals(review.get("test_mode"), r.get("test_mode")));
        require(!db.jdbc.queryForList("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0",
            o.get("vehicle_id"), o.get("user_id")).isEmpty());
        var reports = db.jdbc.queryForList("SELECT t.*,s.no_parts,s.no_fault_parts,s.submitted_at FROM technician_report t "
            + "JOIN service_report_submission s ON s.report_id=t.id AND s.order_id=t.order_id WHERE t.order_id=? AND t.is_deleted=0", orderId);
        require(reports.size() == 1); var report = reports.get(0);
        require(ReservationStore.number(report, "status") == 1 && report.get("signed_at") != null
            && report.get("submitted_at") != null && o.get("service_report_ready_at") != null);
        var body = db.mapper.createObjectNode(); body.put("order_id", orderId);
        for (String name : List.of("process_photos", "fault_part_photos", "finish_photos", "parts_used")) body.set(name, db.parse(report.get(name)));
        for (String name : List.of("repair_plan", "fault_analysis")) body.put(name, Objects.toString(report.get(name), ""));
        require(report.get("work_hours") instanceof Number);
        body.put("work_hours", ((Number) report.get("work_hours")).intValue());
        body.put("no_parts", ReservationStore.number(report, "no_parts") == 1);
        body.put("no_fault_parts", ReservationStore.number(report, "no_fault_parts") == 1);
        try { ServiceWorkInput.report(body); } catch (org.springframework.web.server.ResponseStatusException e) { throw new InvalidSource(); }
        var expected = new TreeMap<Long, String>();
        for (var pair : Map.of("process_photos", "PROCESS", "fault_part_photos", "FAULT", "finish_photos", "FINISH").entrySet())
            body.get(pair.getKey()).forEach(file -> expected.put(file.longValue(), pair.getValue()));
        var files = db.jdbc.queryForList("SELECT e.file_id,e.kind,f.owner_type,f.owner_id,f.scan_status,f.is_deleted,f.content_type,f.size_bytes "
            + "FROM service_evidence_file e JOIN file_object f ON f.id=e.file_id WHERE e.order_id=? AND e.record_id=? "
            + "AND e.kind IN ('PROCESS','FAULT','FINISH','SIGNATURE') ORDER BY e.file_id", orderId, report.get("id"));
        int signatures = 0;
        for (var file : files) {
            require("staff_account".equals(file.get("owner_type")) && Objects.equals(file.get("owner_id"), report.get("technician_id"))
                && "CLEAN".equals(file.get("scan_status")) && ReservationStore.number(file, "is_deleted") == 0
                && Set.of("image/png", "image/jpeg").contains(file.get("content_type"))
                && ReservationStore.number(file, "size_bytes") >= 1 && ReservationStore.number(file, "size_bytes") <= 10485760);
            if ("SIGNATURE".equals(file.get("kind"))) { require("image/png".equals(file.get("content_type"))); signatures++; }
            else require(Objects.equals(expected.get(ReservationStore.number(file, "file_id")), file.get("kind")));
        }
        require(signatures == 1 && files.size() == expected.size() + 1);
        var content = db.mapper.createObjectNode(); content.put("version", 2);
        content.put("title", "施工记录"); content.putNull("mileage");
        content.put("notes", "维修方案：" + body.path("repair_plan").asText() + "\n故障分析：" + body.path("fault_analysis").asText());
        content.put("test_mode", ReservationStore.number(r, "test_mode") == 1);
        content.set("parts_used", body.get("parts_used")); content.put("work_minutes", body.get("work_hours").intValue());
        content.put("no_parts", body.get("no_parts").booleanValue());
        content.put("recorded_date", ReservationStore.instant(report.get("signed_at")).atZone(ZoneOffset.UTC).toLocalDate().toString());
        content.put("submitted_at", ReservationStore.iso(report.get("submitted_at")));
        content.put("signed_at", ReservationStore.iso(report.get("signed_at")));
        content.put("redeemed_at", ReservationStore.iso(r.get("redeemed_at")));
        content.set("file_ids", db.mapper.valueToTree(expected.keySet()));
        content.set("source", db.mapper.valueToTree(Map.of("order_id", orderId, "report_id", report.get("id"),
            "review_id", reviewId, "redemption_id", r.get("id"), "vehicle_id", o.get("vehicle_id"), "user_id", o.get("user_id"))));
        // Canonicalize numeric node widths before comparing with MySQL JSON on a retry.
        return db.parse(db.json(content));
    }

    static void enqueue(ReservationStore db, long orderId, long reviewId) {
        JsonNode payload = null;
        // Incomplete historical work must not invalidate an otherwise valid owner review.
        try { payload = snapshot(db, orderId, reviewId); }
        catch (InvalidSource | org.springframework.web.server.ResponseStatusException e) { /* recoverable source gap */ }
        db.jdbc.update("INSERT INTO service_archive_job(order_id,review_id,payload) VALUES(?,?,?)", orderId, reviewId,
            payload == null ? null : db.json(payload));
    }

    public void consume(long id) {
        db.transactions.executeWithoutResult(tx -> {
            var candidate = db.one("SELECT order_id FROM service_archive_job WHERE id=?", id);
            db.one("SELECT id FROM `order` WHERE id=? FOR UPDATE", candidate.get("order_id"));
            var job = db.one("SELECT * FROM service_archive_job WHERE id=? FOR UPDATE", id);
            if ("DONE".equals(job.get("status"))) return;
            var current = snapshot(db, ReservationStore.number(job, "order_id"), ReservationStore.number(job, "review_id"));
            var frozen = db.parse(job.get("payload"));
            require(frozen.isNull() || frozen.equals(current));
            var source = current.get("source");
            require(!db.jdbc.queryForList("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0 FOR UPDATE",
                source.get("vehicle_id").longValue(), source.get("user_id").longValue()).isEmpty());
            long archive = db.insert("INSERT INTO vehicle_archive(vehicle_id,archive_type,content,input_type,recorded_at) VALUES(?,2,?,4,?)",
                source.get("vehicle_id").longValue(), db.json(current), java.sql.Date.valueOf(current.get("recorded_date").textValue()));
            int position = 0; for (var file : current.get("file_ids"))
                db.jdbc.update("INSERT INTO vehicle_archive_file(archive_id,file_id,position) VALUES(?,?,?)", archive, file.longValue(), position++);
            var audit = Map.of("archive_id", archive, "order_id", candidate.get("order_id"), "review_id", job.get("review_id"), "test_mode", current.get("test_mode").booleanValue());
            db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) "
                + "VALUES('system',0,'SERVICE_ARCHIVE_CREATE','vehicle_archive',?,'{}',?,?)", archive, db.json(audit), UUID.randomUUID().toString());
            db.jdbc.update("UPDATE service_archive_job SET status='DONE',archive_id=?,payload=?,completed_at=?,last_error=NULL WHERE id=?",
                archive, db.json(current), ReservationStore.time(db.now()), id);
            if (cardsEnabled) ExperienceCards.create(db, archive, current);
        });
    }

    @Scheduled(fixedDelayString = "${SERVICE_ARCHIVE_INTERVAL_MS:60000}")
    public void sweep() {
        if (!enabled) return;
        List<Long> ids;
        try { ids = db.jdbc.query("SELECT id FROM service_archive_job WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 20",
            (rs, n) -> rs.getLong(1), ReservationStore.time(db.now())); }
        catch (RuntimeException e) { log.warn("施工归档任务查询暂不可用"); return; }
        for (long id : ids) try { consume(id); }
        catch (RuntimeException e) {
            try { db.transactions.executeWithoutResult(tx -> db.jdbc.update("UPDATE service_archive_job SET attempts=attempts+1,last_error=?,next_attempt_at=? WHERE id=? AND status='PENDING'",
                e instanceof InvalidSource ? "SOURCE_UNAVAILABLE" : "DATABASE_UNAVAILABLE", ReservationStore.time(db.now().plusSeconds(60)), id)); }
            catch (RuntimeException ignored) { log.warn("施工归档恢复记录暂不可用"); }
        }
    }
}
