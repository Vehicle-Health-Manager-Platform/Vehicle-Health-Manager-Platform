package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.file.FileMetadataRepository;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * R1a merchant onboarding: owner application and resubmission, operator review against a
 * fail-closed region/category quota, and the single-transaction store opening.
 *
 * Fixed lock order: applicant user (or operator account) → application → quota → merchant
 * → staff_account, matching the identity write order so onboarding cannot deadlock dispatch.
 */
public class MerchantOnboarding {
    private final ReservationStore db;
    private final OperatorIdentity operators;
    private final AuthRateLimiter limits;
    private final boolean enabled;

    public MerchantOnboarding(ReservationStore db, OperatorIdentity operators, AuthRateLimiter limits, boolean enabled) {
        this.db = db; this.operators = operators; this.limits = limits; this.enabled = enabled;
    }

    private void enabled() { if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "商家入驻尚未开启"); }
    private void limit(OperatorActor actor) { if (limits != null) limits.check("onboard-operator", Long.toString(actor.id()), 240, 900); }
    private static FulfillmentConflict conflict(int code, String reason) { return new FulfillmentConflict(code, reason); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "入驻申请或资质文件不存在"); }

    /** Qualification snapshots must already be private uploads owned by the applicant. */
    private void usable(long applicant, List<Long> ids) {
        for (long file : ids) {
            var rows = db.jdbc.queryForList("SELECT id FROM file_object WHERE id=? AND owner_type='user' AND owner_id=? "
                + "AND scan_status='CLEAN' AND is_deleted=0 FOR UPDATE", file, applicant);
            if (rows.isEmpty()) throw conflict(49002, "资质文件不可用或不属于申请人，请重新上传");
        }
    }
    private List<Long> snapshotFiles(Map<String,Object> row) {
        var node = db.parse(row.get("qualification_files"));
        var ids = new ArrayList<Long>();
        if (node != null && node.isArray()) node.forEach(item -> ids.add(item.longValue()));
        return ids;
    }
    private static Map<String,Object> audit(Map<String,Object> row) {
        var out = new LinkedHashMap<String,Object>();
        for (String key : List.of("status", "revision", "category", "region_code", "merchant_id")) out.put(key, row.get(key));
        return out;
    }
    private Map<String,Object> view(Map<String,Object> row) {
        var out = new LinkedHashMap<String,Object>();
        out.put("application_id", ReservationStore.number(row, "id"));
        out.put("merchant_name", row.get("merchant_name"));
        out.put("category", row.get("category"));
        out.put("region_code", row.get("region_code"));
        out.put("address", row.get("address"));
        out.put("contact_phone", row.get("contact_phone"));
        out.put("status", row.get("status"));
        out.put("revision", ReservationStore.number(row, "revision"));
        out.put("merchant_id", row.get("merchant_id") == null ? null : ReservationStore.number(row, "merchant_id"));
        out.put("review_reason", row.get("last_reason_code"));
        out.put("qualification_file_ids", snapshotFiles(row));
        out.put("created_at", ReservationStore.iso(row.get("created_at")));
        out.put("updated_at", ReservationStore.iso(row.get("updated_at")));
        return out;
    }
    private List<Map<String,Object>> reviews(long application) {
        var rows = db.jdbc.queryForList("SELECT revision,decision,reason_code,merchant_id,decided_at "
            + "FROM merchant_application_review WHERE application_id=? ORDER BY revision DESC", application);
        var items = new ArrayList<Map<String,Object>>();
        for (var row : rows) {
            var item = new LinkedHashMap<String,Object>();
            item.put("revision", ReservationStore.number(row, "revision"));
            item.put("decision", row.get("decision"));
            item.put("reason_code", row.get("reason_code"));
            item.put("merchant_id", row.get("merchant_id") == null ? null : ReservationStore.number(row, "merchant_id"));
            item.put("decided_at", ReservationStore.iso(row.get("decided_at")));
            items.add(item);
        }
        return items;
    }
    /** A cached response for the same key means this is a replay: never re-judge business state. */
    private boolean replayed(String actorType, long actorId, String path, String key) {
        var rows = db.jdbc.queryForList("SELECT response_body FROM idempotency_record WHERE actor_type=? AND actor_id=? "
            + "AND request_method IN ('POST','PUT') AND request_path=? AND idempotency_key=? "
            + "AND is_deleted=0 AND expires_at>UTC_TIMESTAMP()", actorType, actorId, path, key);
        return !rows.isEmpty() && rows.get(0).get("response_body") != null;
    }
    private long quota(String region, String category) {
        var rows = db.jdbc.queryForList("SELECT max_active FROM merchant_region_category_quota "
            + "WHERE region_code=? AND category=? FOR UPDATE", region, category);
        return rows.isEmpty() ? 0L : ReservationStore.number(rows.get(0), "max_active");
    }
    private long activeStores(String region, int merchantType) {
        String filter = " FROM merchant WHERE region_code=? AND merchant_type=? AND status=1 AND is_deleted=0";
        // Inside a write transaction the count must read the latest committed rows, not the
        // REPEATABLE READ snapshot taken before this transaction waited for the quota row.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            return db.jdbc.queryForObject("SELECT COUNT(*)" + filter, Long.class, region, merchantType);
        return db.jdbc.queryForList("SELECT id" + filter + " FOR UPDATE", region, merchantType).size();
    }

    /** Owner submit or resubmit. One row per applicant; each resubmission advances the revision. */
    public JsonNode submit(VehicleOwner owner, String key, JsonNode raw) {
        String normalized = WriteIntegrityService.normalizeKey(key);
        JsonNode body = MerchantOnboardingInput.application(raw);
        var fileIds = MerchantOnboardingInput.files(body.get("qualification_file_ids"));
        String path = "/api/merchant-applications";
        return db.writes.execute(new Actor("user", owner.id()), "POST", path, normalized, body, () -> {
            // Locking the user row serializes concurrent submissions for the same applicant.
            db.owner(owner, true);
            if (replayed("user", owner.id(), path, normalized)) return;
            var live = db.jdbc.queryForList("SELECT id FROM merchant_application WHERE applicant_user_id=? "
                + "AND is_deleted=0 AND status IN ('PENDING_REVIEW','APPROVED') FOR UPDATE", owner.id());
            if (!live.isEmpty()) throw conflict(49001, "已存在待审或已通过的入驻申请，请勿重复提交");
            usable(owner.id(), fileIds);
        }, () -> {
            var prior = db.jdbc.queryForList("SELECT id,revision FROM merchant_application WHERE applicant_user_id=? "
                + "AND is_deleted=0 ORDER BY id DESC LIMIT 1 FOR UPDATE", owner.id());
            String name = body.get("merchant_name").textValue();
            String category = body.get("category").textValue();
            String region = body.get("region_code").textValue();
            String address = body.get("address").textValue();
            String phone = body.get("contact_phone").textValue();
            String files = db.json(fileIds);
            long application; long revision;
            if (prior.isEmpty()) {
                application = db.insert("INSERT INTO merchant_application(applicant_user_id,merchant_name,category,"
                    + "region_code,address,contact_phone,qualification_files,status,revision) "
                    + "VALUES(?,?,?,?,?,?,?,'PENDING_REVIEW',1)", owner.id(), name, category, region, address, phone, files);
                revision = 1;
            } else {
                application = ReservationStore.number(prior.get(0), "id");
                revision = ReservationStore.number(prior.get(0), "revision") + 1;
                db.jdbc.update("UPDATE merchant_application SET merchant_name=?,category=?,region_code=?,address=?,"
                    + "contact_phone=?,qualification_files=?,status='PENDING_REVIEW',revision=?,last_reason_code=NULL,"
                    + "merchant_id=NULL,reviewed_by=NULL,reviewed_at=NULL WHERE id=?",
                    name, category, region, address, phone, files, revision, application);
            }
            var after = db.jdbc.queryForList("SELECT * FROM merchant_application WHERE id=?", application).get(0);
            var data = new LinkedHashMap<String,Object>();
            data.put("application", view(after));
            data.put("revision", revision);
            return new Change("MERCHANT_APPLICATION_SUBMIT", "merchant_application", application,
                Map.of(), audit(after), data);
        });
    }

    /** The caller's own application plus its immutable decision history. */
    public Map<String,Object> mine(VehicleOwner owner) {
        return db.reads.execute(tx -> {
            db.owner(owner, false);
            enabled();
            var rows = db.jdbc.queryForList("SELECT * FROM merchant_application WHERE applicant_user_id=? "
                + "AND is_deleted=0 ORDER BY id DESC LIMIT 1", owner.id());
            var out = new LinkedHashMap<String,Object>();
            if (rows.isEmpty()) { out.put("application", null); return out; }
            var application = rows.get(0);
            out.put("application", view(application));
            out.put("reviews", reviews(ReservationStore.number(application, "id")));
            return out;
        });
    }

    /** Fixed summary only: no applicant id, phone number or qualification file is exposed. */
    public Map<String,Object> pending(OperatorActor actor, int page, int size) {
        MerchantOnboardingInput.page(page, size);
        limit(actor);
        return db.reads.execute(tx -> {
            operators.authorize(actor, OperatorIdentity.Permission.ONBOARD, false);
            enabled();
            var rows = db.jdbc.queryForList("SELECT * FROM merchant_application WHERE status='PENDING_REVIEW' "
                + "AND is_deleted=0 ORDER BY id DESC LIMIT ? OFFSET ?", size, (long)(page - 1) * size);
            var items = new ArrayList<Map<String,Object>>();
            for (var row : rows) {
                var item = new LinkedHashMap<String,Object>();
                item.put("application_id", ReservationStore.number(row, "id"));
                item.put("merchant_name", row.get("merchant_name"));
                item.put("category", row.get("category"));
                item.put("region_code", row.get("region_code"));
                item.put("revision", ReservationStore.number(row, "revision"));
                item.put("submitted_at", ReservationStore.iso(row.get("updated_at")));
                items.add(item);
            }
            long total = db.jdbc.queryForObject("SELECT COUNT(*) FROM merchant_application WHERE status='PENDING_REVIEW' "
                + "AND is_deleted=0", Long.class);
            return ReservationStore.page(items, total, page, size);
        });
    }

    public Map<String,Object> detail(OperatorActor actor, long id) {
        MerchantOnboardingInput.id(id);
        limit(actor);
        return db.reads.execute(tx -> {
            operators.authorize(actor, OperatorIdentity.Permission.ONBOARD, false);
            enabled();
            var rows = db.jdbc.queryForList("SELECT * FROM merchant_application WHERE id=? AND is_deleted=0", id);
            if (rows.isEmpty()) throw notFound();
            var application = rows.get(0);
            var out = new LinkedHashMap<String,Object>();
            out.put("application", view(application));
            out.put("reviews", reviews(id));
            return out;
        });
    }

    /** Controlled, short-lived access to one qualification file of one application. */
    public FileMetadataRepository.Actor fileOwner(OperatorActor actor, long id, long file) {
        MerchantOnboardingInput.id(id); MerchantOnboardingInput.id(file);
        limit(actor);
        return db.reads.execute(tx -> {
            operators.authorize(actor, OperatorIdentity.Permission.ONBOARD, false);
            enabled();
            var rows = db.jdbc.queryForList("SELECT qualification_files FROM merchant_application WHERE id=? AND is_deleted=0", id);
            if (rows.isEmpty() || !snapshotFiles(rows.get(0)).contains(file)) throw notFound();
            var files = db.jdbc.queryForList("SELECT owner_type,owner_id FROM file_object WHERE id=? AND is_deleted=0", file);
            if (files.isEmpty()) throw notFound();
            return new FileMetadataRepository.Actor(String.valueOf(files.get(0).get("owner_type")),
                ReservationStore.number(files.get(0), "owner_id"));
        });
    }

    public JsonNode moderate(OperatorActor actor, long id, String key, JsonNode raw) {
        MerchantOnboardingInput.id(id);
        String normalized = WriteIntegrityService.normalizeKey(key);
        JsonNode body = MerchantOnboardingInput.moderation(raw);
        String path = "/api/admin/merchant-applications/" + id + "/moderate";
        limit(actor);
        return db.writes.execute(new Actor("operator_account", actor.id()), "POST", path, normalized, body, () -> {
            operators.authorize(actor, OperatorIdentity.Permission.ONBOARD, true);
            enabled();
            if (replayed("operator_account", actor.id(), path, normalized)) return;
            var rows = db.jdbc.queryForList("SELECT * FROM merchant_application WHERE id=? AND is_deleted=0 FOR UPDATE", id);
            if (rows.isEmpty()) throw notFound();
            var application = rows.get(0);
            if (!"PENDING_REVIEW".equals(application.get("status"))
                || ReservationStore.number(application, "revision") != body.get("revision").longValue())
                throw conflict(49003, "申请版本或状态已变化，请刷新待审列表");
            // Take the quota row lock now so the decision below follows the fixed lock order.
            db.jdbc.queryForList("SELECT id FROM merchant_region_category_quota WHERE region_code=? AND category=? FOR UPDATE",
                application.get("region_code"), application.get("category"));
        }, () -> {
            var before = db.jdbc.queryForList("SELECT * FROM merchant_application WHERE id=? FOR UPDATE", id).get(0);
            String decision = body.get("decision").textValue();
            int revision = body.get("revision").intValue();
            int merchantType = MerchantOnboardingInput.CATEGORY_TYPES.get(String.valueOf(before.get("category")));
            Long merchant = null;
            if ("APPROVE".equals(decision)) {
                String region = String.valueOf(before.get("region_code"));
                if (activeStores(region, merchantType) >= quota(region, String.valueOf(before.get("category"))))
                    throw conflict(49010, "该区域品类配额已满，无法开店");
                merchant = db.insert("INSERT INTO merchant(merchant_type,name,address,contact_phone,qualification,"
                    + "region_code,status) VALUES(?,?,?,?,?,?,1)", merchantType, before.get("merchant_name"),
                    before.get("address"), before.get("contact_phone"), db.json(snapshotFiles(before)), region);
                db.jdbc.update("INSERT INTO staff_account(merchant_id,role,account,status) "
                    + "VALUES(?,?,?, 'PENDING_ACTIVATION')", merchant, "MERCHANT", "m" + merchant);
                db.jdbc.update("UPDATE merchant_application SET status='APPROVED',merchant_id=?,last_reason_code=NULL,"
                    + "reviewed_by=?,reviewed_at=? WHERE id=?", merchant, actor.id(), ReservationStore.time(db.now()), id);
                db.jdbc.update("INSERT INTO merchant_application_review(application_id,revision,decision,reason_code,"
                    + "merchant_id,operator_id,decided_at) VALUES(?,?,?,?,?,?,?)",
                    id, revision, "APPROVED", null, merchant, actor.id(), ReservationStore.time(db.now()));
            } else {
                String reason = body.get("reason_code").textValue();
                db.jdbc.update("UPDATE merchant_application SET status='REJECTED',last_reason_code=?,merchant_id=NULL,"
                    + "reviewed_by=?,reviewed_at=? WHERE id=?", reason, actor.id(), ReservationStore.time(db.now()), id);
                db.jdbc.update("INSERT INTO merchant_application_review(application_id,revision,decision,reason_code,"
                    + "merchant_id,operator_id,decided_at) VALUES(?,?,?,?,?,?,?)",
                    id, revision, "REJECTED", reason, null, actor.id(), ReservationStore.time(db.now()));
            }
            var after = db.jdbc.queryForList("SELECT * FROM merchant_application WHERE id=?", id).get(0);
            var data = new LinkedHashMap<String,Object>();
            data.put("application", view(after));
            data.put("decision", decision);
            data.put("reason_code", body.get("reason_code").isNull() ? null : body.get("reason_code").textValue());
            data.put("merchant_id", merchant);
            return new Change("MERCHANT_APPLICATION_MODERATE", "merchant_application", id, audit(before), audit(after), data);
        });
    }

    public Map<String,Object> quotas(OperatorActor actor) {
        limit(actor);
        return db.reads.execute(tx -> {
            operators.authorize(actor, OperatorIdentity.Permission.ONBOARD, false);
            enabled();
            var rows = db.jdbc.queryForList("SELECT region_code,category,max_active FROM merchant_region_category_quota "
                + "ORDER BY region_code,category");
            var items = new ArrayList<Map<String,Object>>();
            for (var row : rows) {
                var item = new LinkedHashMap<String,Object>();
                String region = String.valueOf(row.get("region_code")), category = String.valueOf(row.get("category"));
                item.put("region_code", region);
                item.put("category", category);
                item.put("max_active", ReservationStore.number(row, "max_active"));
                item.put("active_stores", activeStores(region, MerchantOnboardingInput.CATEGORY_TYPES.get(category)));
                items.add(item);
            }
            return Map.of("items", items);
        });
    }

    public JsonNode setQuota(OperatorActor actor, String key, JsonNode raw) {
        String normalized = WriteIntegrityService.normalizeKey(key);
        JsonNode body = MerchantOnboardingInput.quota(raw);
        String path = "/api/admin/merchant-quotas";
        limit(actor);
        return db.writes.execute(new Actor("operator_account", actor.id()), "PUT", path, normalized, body, () -> {
            operators.authorize(actor, OperatorIdentity.Permission.ONBOARD, true);
            enabled();
            db.jdbc.queryForList("SELECT id FROM merchant_region_category_quota WHERE region_code=? AND category=? FOR UPDATE",
                body.get("region_code").textValue(), body.get("category").textValue());
        }, () -> {
            String region = body.get("region_code").textValue(), category = body.get("category").textValue();
            int maximum = body.get("max_active").intValue();
            long active = activeStores(region, MerchantOnboardingInput.CATEGORY_TYPES.get(category));
            if (maximum < active) throw conflict(49011, "配额不能低于当前有效商家数");
            var existing = db.jdbc.queryForList("SELECT id,max_active FROM merchant_region_category_quota "
                + "WHERE region_code=? AND category=? FOR UPDATE", region, category);
            Map<String,Object> previous = Map.of();
            long quotaId;
            if (existing.isEmpty()) {
                quotaId = db.insert("INSERT INTO merchant_region_category_quota(region_code,category,max_active,updated_by) "
                    + "VALUES(?,?,?,?)", region, category, maximum, actor.id());
            } else {
                quotaId = ReservationStore.number(existing.get(0), "id");
                previous = Map.of("max_active", ReservationStore.number(existing.get(0), "max_active"));
                db.jdbc.update("UPDATE merchant_region_category_quota SET max_active=?,updated_by=? WHERE id=?",
                    maximum, actor.id(), quotaId);
            }
            var next = Map.<String,Object>of("max_active", (long) maximum);
            boolean changed = !next.equals(previous);
            var data = new LinkedHashMap<String,Object>();
            data.put("region_code", region);
            data.put("category", category);
            data.put("max_active", maximum);
            data.put("active_stores", active);
            return new Change("MERCHANT_QUOTA_SET", "merchant_region_category_quota", quotaId,
                previous, changed ? next : previous, data, changed);
        });
    }
}
