package com.autocare.platform.order;

import com.autocare.platform.gateway.identity.OperatorActor;
import com.autocare.platform.gateway.identity.OperatorIdentity;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.autocare.platform.order.ReservationStore.*;

/** Local recorded flows only. Never contacts a channel or asserts settlement. */
public class LocalFinance {
    static final String VERSION = "R9_LOCAL_FINANCE_V1";
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int LIMIT = 10000;
    private final ReservationStore db;
    private final OperatorIdentity operators;

    public LocalFinance(ReservationStore db, OperatorIdentity operators) {
        this.db = db;
        this.operators = operators;
    }

    static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            LocalDate day = LocalDate.parse(value);
            if (!day.toString().equals(value)) throw new IllegalArgumentException();
            return day;
        } catch (RuntimeException e) { throw ReservationInput.bad(); }
    }

    static void channel(String value) {
        if (!Set.of("WECHAT", "LOCAL_TEST").contains(value == null ? "" : value)) throw ReservationInput.bad();
    }

    // A current local success needs its original order and exactly one accepted event.
    // No FOR UPDATE: all sources and the permission read share the repeatable-read snapshot.
    private static final String PAYMENT_VALID = """
        p.is_deleted=0 AND o.id IS NOT NULL AND o.is_deleted=0
        AND p.status='SUCCEEDED' AND p.currency='CNY' AND p.amount>0 AND p.amount=o.pay_amount
        AND p.paid_at IS NOT NULL AND p.paid_at>=o.created_at AND p.paid_at>=p.created_at
        AND p.paid_at<=? AND TRIM(COALESCE(p.payment_no,''))<>''
        AND TRIM(COALESCE(p.channel_payment_no,''))<>''
        AND (SELECT COUNT(*) FROM payment sp WHERE sp.order_id=p.order_id AND sp.status='SUCCEEDED' AND sp.is_deleted=0)=1
        AND (SELECT COUNT(*) FROM payment_event e WHERE e.payment_id=p.id AND e.channel=p.channel
             AND e.outcome='PAID' AND e.event_hash REGEXP '^[a-f0-9]{64}$' AND e.is_deleted=0)=1
        AND NOT EXISTS(SELECT 1 FROM payment_exception x WHERE x.order_id=p.order_id)
        """;

    private static class Total {
        int success, anomaly, other;
        BigDecimal amount = new BigDecimal("0.00");
        void add(Map<String,Object> row, Set<String> otherStatuses) {
            String status = String.valueOf(row.get("status"));
            if ("SUCCEEDED".equals(status)) {
                if (row.get("source_valid") instanceof Number n && n.intValue() == 1) {
                    success++; amount = amount.add((BigDecimal) row.get("amount"));
                } else anomaly++;
            } else if (otherStatuses.contains(status)) other++;
            else anomaly++;
        }
        Map<String,Object> view(boolean unclassified) {
            var result = new LinkedHashMap<String,Object>();
            boolean known = anomaly == 0 && !unclassified;
            result.put("state", known ? "KNOWN" : "UNKNOWN");
            result.put("success_count", success);
            result.put("anomaly_count", anomaly);
            result.put("other_count", other);
            result.put("amount", known ? amount.setScale(2).toPlainString() : null);
            return result;
        }
    }

    public Map<String,Object> report(OperatorActor actor, String value, String selectedChannel) {
        LocalDate day = date(value); channel(selectedChannel);
        return db.reads.execute(tx -> {
            operators.authorize(actor, OperatorIdentity.Permission.ANALYTICS, false);
            Instant now = db.now(), start = day.atStartOfDay(ZONE).toInstant();
            if (start.isAfter(now)) throw new ResponseStatusException(HttpStatus.CONFLICT, "未来日期尚无资金记录");
            Instant next = day.plusDays(1).atStartOfDay(ZONE).toInstant();
            Instant end = now.isBefore(next) ? now : next;
            String paymentTime = "CASE WHEN p.status='SUCCEEDED' THEN COALESCE(p.paid_at,p.created_at) ELSE p.created_at END";
            String refundTime = "CASE WHEN r.status='SUCCEEDED' THEN COALESCE(r.refunded_at,r.created_at) ELSE r.created_at END";
            var payments = db.jdbc.queryForList("SELECT p.status,p.amount,(" + PAYMENT_VALID + ") AS source_valid "
                + "FROM payment p LEFT JOIN `order` o ON o.id=p.order_id WHERE p.is_deleted=0 AND p.channel=? AND "
                + paymentTime + ">=? AND " + paymentTime + "<? ORDER BY p.id LIMIT 10001",
                time(now), selectedChannel, time(start), time(end));
            var refunds = db.jdbc.queryForList("SELECT r.status,r.amount,(" + PAYMENT_VALID + """
                AND r.order_id=p.order_id AND r.currency='CNY' AND r.amount=p.amount
                AND r.refunded_at IS NOT NULL AND r.refunded_at>=p.paid_at AND r.refunded_at>=r.created_at
                AND r.refunded_at<=? AND r.owner_consented_at>=p.paid_at
                AND r.owner_consented_at<=r.refunded_at AND r.merchant_consented_at>=r.owner_consented_at
                AND r.merchant_consented_at<=r.refunded_at
                AND o.status='CLOSED' AND o.close_reason='REFUND_CANCELLED'
                AND TRIM(COALESCE(r.refund_no,''))<>'' AND TRIM(COALESCE(r.channel_refund_no,''))<>''
                AND (SELECT COUNT(*) FROM refund sr WHERE sr.order_id=r.order_id AND sr.status='SUCCEEDED' AND sr.is_deleted=0)=1
                AND (SELECT COUNT(*) FROM refund_event e WHERE e.refund_id=r.id AND e.channel=p.channel
                     AND e.outcome='SUCCEEDED' AND e.event_hash REGEXP '^[a-f0-9]{64}$' AND e.is_deleted=0)=1
                ) AS source_valid FROM refund r LEFT JOIN payment p ON p.id=r.payment_id
                LEFT JOIN `order` o ON o.id=p.order_id WHERE r.is_deleted=0 AND p.channel=? AND
                """ + refundTime + ">=? AND " + refundTime + "<? ORDER BY r.id LIMIT 10001",
                time(now), time(now), selectedChannel, time(start), time(end));
            long unclassified = db.jdbc.queryForObject("SELECT COUNT(*) FROM payment p WHERE p.is_deleted=0 "
                + "AND p.channel NOT IN ('WECHAT','LOCAL_TEST') AND " + paymentTime + ">=? AND " + paymentTime + "<?",
                Long.class, time(start), time(end));
            unclassified += db.jdbc.queryForObject("SELECT COUNT(*) FROM refund r LEFT JOIN payment p ON p.id=r.payment_id "
                + "WHERE r.is_deleted=0 AND (p.id IS NULL OR p.channel NOT IN ('WECHAT','LOCAL_TEST')) AND "
                + refundTime + ">=? AND " + refundTime + "<?", Long.class, time(start), time(end));
            if (payments.size() + refunds.size() + unclassified > LIMIT)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "单日记录超过查询上限，请联系管理员分批核对");
            Total paid = new Total(), refunded = new Total();
            payments.forEach(r -> paid.add(r, Set.of("CREATED", "PENDING", "FAILED", "CLOSED")));
            refunds.forEach(r -> refunded.add(r, Set.of("REQUESTED", "PENDING", "FAILED", "REJECTED", "WITHDRAWN")));
            boolean known = paid.anomaly == 0 && refunded.anomaly == 0 && unclassified == 0;
            var result = new LinkedHashMap<String,Object>();
            result.put("date", value); result.put("channel", selectedChannel);
            result.put("test_mode", "LOCAL_TEST".equals(selectedChannel));
            result.put("timezone", ZONE.getId()); result.put("currency", "CNY"); result.put("source_version", VERSION);
            result.put("window_from", start.toString()); result.put("window_to", end.toString()); result.put("observed_at", now.toString());
            result.put("payments", paid.view(unclassified > 0)); result.put("refunds", refunded.view(unclassified > 0));
            result.put("unclassified_count", unclassified);
            var net = new LinkedHashMap<String,Object>(); net.put("state", known ? "KNOWN" : "UNKNOWN");
            net.put("amount", known ? paid.amount.subtract(refunded.amount).setScale(2).toPlainString() : null);
            result.put("local_net", net);
            var statement = new LinkedHashMap<String,Object>(); statement.put("state", "UNAVAILABLE");
            statement.put("net_amount", null); statement.put("difference", null); result.put("channel_statement", statement);
            return result;
        });
    }
}
