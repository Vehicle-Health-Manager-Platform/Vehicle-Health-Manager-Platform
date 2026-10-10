package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Owner-only summaries; consent queues moderation and never publishes content. */
public class ExperienceCards {
    private final ReservationStore db;
    public ExperienceCards(ReservationStore db) { this.db = db; }
    static void page(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 50) throw ReservationInput.bad();
    }

    static void create(ReservationStore db, long archive, JsonNode source) {
        // Allowlist facts. Never copy strings from a report, review or part description.
        var summary = Map.of("version", 1, "work_minutes", source.get("work_minutes").intValue(),
            "part_kinds", source.get("parts_used").size(), "no_parts", source.get("no_parts").booleanValue(),
            "recorded_month", source.get("recorded_date").textValue().substring(0, 7));
        var ids = source.get("source");
        long card = db.insert("INSERT INTO experience_card(order_id,archive_id,review_id,user_id,vehicle_id,summary,test_mode) VALUES(?,?,?,?,?,?,?)",
            ids.get("order_id").longValue(), archive, ids.get("review_id").longValue(), ids.get("user_id").longValue(),
            ids.get("vehicle_id").longValue(), db.json(summary), source.get("test_mode").booleanValue());
        db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) "
            + "VALUES('system',0,'EXPERIENCE_CARD_CREATE','experience_card',?,'{}',?,?)", card,
            db.json(Map.of("status", "DRAFT", "revision", 0, "test_mode", source.get("test_mode").booleanValue())), UUID.randomUUID().toString());
    }
    private Map<String,Object> owned(VehicleOwner owner, long id, boolean lock) {
        String tail = lock ? " FOR UPDATE" : "";
        var c = db.one("SELECT * FROM experience_card WHERE id=? AND user_id=?" + tail, id, owner.id());
        db.one("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0" + tail, c.get("vehicle_id"), owner.id());
        return c;
    }
    private Map<String,Object> view(Map<String,Object> c) {
        var summary = db.parse(c.get("summary"));
        var out = new LinkedHashMap<String,Object>();
        for (String k : List.of("id", "vehicle_id", "order_id", "archive_id", "status", "revision", "consent_version"))
            out.put(k.equals("id") ? "card_id" : k, c.get(k));
        out.put("title", "施工经验摘要"); out.put("test_mode", ReservationStore.number(c, "test_mode") == 1);
        out.put("summary", Map.of("version", 1, "work_minutes", summary.path("work_minutes").intValue(),
            "part_kinds", summary.path("part_kinds").intValue(), "no_parts", summary.path("no_parts").booleanValue(),
            "recorded_month", summary.path("recorded_month").asText()));
        out.put("consented_at", ReservationStore.iso(c.get("consented_at")));
        out.put("withdrawn_at", ReservationStore.iso(c.get("withdrawn_at"))); return out;
    }
    public Map<String,Object> list(VehicleOwner owner, long vehicle, int page, int size) {
        ServiceCatalog.validateId(vehicle);
        page(page, size);
        return db.reads.execute(tx -> {
            db.owner(owner, false);
            db.one("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0", vehicle, owner.id());
            var rows = db.jdbc.queryForList("SELECT * FROM experience_card WHERE vehicle_id=? AND user_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                vehicle, owner.id(), size, (long)(page - 1) * size).stream().map(this::view).toList();
            long total = db.jdbc.queryForObject("SELECT COUNT(*) FROM experience_card WHERE vehicle_id=? AND user_id=?", Long.class, vehicle, owner.id());
            return ReservationStore.page(rows, total, page, size);
        });
    }
    private void trusted(Map<String,Object> c) {
        if (ReservationStore.number(c, "test_mode") != 0) throw conflict();
        try {
            var job = db.one("SELECT * FROM service_archive_job WHERE order_id=? AND review_id=? AND archive_id=? AND status='DONE'",
                c.get("order_id"), c.get("review_id"), c.get("archive_id"));
            var archive = db.one("SELECT content FROM vehicle_archive WHERE id=? AND vehicle_id=? AND input_type=4 AND is_deleted=0",
                c.get("archive_id"), c.get("vehicle_id"));
            var frozen = db.parse(job.get("payload"));
            if (!frozen.equals(db.parse(archive.get("content"))) || !frozen.equals(ServiceArchiveJobs.snapshot(db,
                ReservationStore.number(c, "order_id"), ReservationStore.number(c, "review_id")))
                || frozen.path("test_mode").asBoolean(true) || frozen.path("source").path("user_id").asLong() != ReservationStore.number(c, "user_id")) throw conflict();
        } catch (ServiceArchiveJobs.InvalidSource | org.springframework.web.server.ResponseStatusException e) { throw conflict(); }
    }
    private static FulfillmentConflict conflict() { return new FulfillmentConflict(45001, "测试记录或施工来源不可用于授权，请刷新；原档案保留"); }
    private Map<String,Object> audit(Map<String,Object> c) {
        var m = new LinkedHashMap<String,Object>();
        for (String k : List.of("status", "revision", "consent_version")) m.put(k, c.get(k)); return m;
    }
    public JsonNode change(VehicleOwner owner, long id, String key, JsonNode raw, boolean consent) {
        ServiceCatalog.validateId(id); String normalized = WriteIntegrityService.normalizeKey(key);
        var body = ExperienceCardInput.validate(raw, consent);
        String path = "/api/experience-cards/" + id + (consent ? "/consent" : "/withdraw");
        return db.writes.execute(new Actor("user", owner.id()), "POST", path, normalized, body, () -> {
            db.owner(owner, true);
            // Match archive worker lock order; ownership is checked before any private response.
            var candidate = db.one("SELECT order_id FROM experience_card WHERE id=? AND user_id=?", id, owner.id());
            db.one("SELECT id FROM `order` WHERE id=? FOR UPDATE", candidate.get("order_id"));
            var c = owned(owner, id, true);
            var cached = db.jdbc.queryForList("SELECT response_body FROM idempotency_record WHERE actor_type='user' AND actor_id=? "
                + "AND request_method='POST' AND request_path=? AND idempotency_key=?", owner.id(), path, normalized);
            if (!cached.isEmpty() && cached.get(0).get("response_body") != null
                && db.parse(cached.get(0).get("response_body")).path("data").path("card").path("revision").asLong(-1) != ReservationStore.number(c, "revision"))
                throw new FulfillmentConflict(45002, "卡片已变更，请刷新后重新确认授权或撤回");
            if (consent) trusted(c);
        }, () -> {
            var before = owned(owner, id, true);
            String target = consent ? "PENDING_REVIEW" : "WITHDRAWN";
            if (!Set.of("DRAFT", "PENDING_REVIEW", "WITHDRAWN").contains(before.get("status"))) throw conflict();
            boolean changed = !target.equals(before.get("status"));
            if (changed) {
                if (consent) db.jdbc.update("UPDATE experience_card SET status=?,revision=revision+1,consent_version=?,consented_at=?,withdrawn_at=NULL WHERE id=?",
                    target, ExperienceCardInput.VERSION, ReservationStore.time(db.now()), id);
                else db.jdbc.update("UPDATE experience_card SET status=?,revision=revision+1,consent_version=NULL,consented_at=NULL,withdrawn_at=? WHERE id=?",
                    target, ReservationStore.time(db.now()), id);
            }
            var after = owned(owner, id, true);
            return new Change(consent ? "EXPERIENCE_CARD_CONSENT" : "EXPERIENCE_CARD_WITHDRAW", "experience_card", id,
                audit(before), audit(after), Map.of("card", view(after)), changed);
        });
    }
}
