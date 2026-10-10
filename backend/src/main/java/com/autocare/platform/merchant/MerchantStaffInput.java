package com.autocare.platform.merchant;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 严格、白名单化的门店员工与门店资料输入；未知字段一律拒绝。 */
final class MerchantStaffInput {
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** 本接口只维护店员与技师；店长账号不可经此创建或修改。 */
    static final Set<String> ROLES = Set.of("STAFF", "TECHNICIAN");
    private static final Set<String> CREATE_FIELDS = Set.of("role", "display_name", "phone", "password");
    private static final Set<String> PROFILE_FIELDS = Set.of("name", "address", "contact_phone", "lng", "lat");

    private MerchantStaffInput() {}

    static ResponseStatusException bad() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "门店员工或资料参数无效");
    }

    /** 严格 JSON：无重复键、无尾随内容、有界长度、根必须是对象。 */
    static JsonNode parse(String raw) {
        try {
            if (raw == null || raw.length() > 2048) throw bad();
            JsonNode body = JSON.readTree(raw);
            if (body == null || !body.isObject()) throw bad();
            return body;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw bad();
        }
    }

    static long id(long value) {
        if (value < 1 || value > 9007199254740991L) throw bad();
        return value;
    }

    static void page(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 50) throw bad();
    }

    /** 开关必须是字面 true/false，避免把拼写错误当成"已开启"。 */
    static boolean enabled(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("MERCHANT_STAFF_ENABLED must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    static String role(String value) {
        if (value == null || !ROLES.contains(value)) throw bad();
        return value;
    }

    static JsonNode creation(JsonNode body) {
        if (body == null || !body.isObject() || body.size() < 3 || body.size() > 4) throw bad();
        body.fieldNames().forEachRemaining(field -> {
            if (!CREATE_FIELDS.contains(field)) throw bad();
        });
        for (String required : List.of("role", "display_name", "password")) {
            if (!body.has(required)) throw bad();
        }
        String role = role(body.get("role").isTextual() ? body.get("role").textValue() : null);
        displayName(body.get("display_name"));
        // 店员走账号+密码+短信登录，短信依赖登录手机号，因此手机号必填。
        if ("STAFF".equals(role) && !body.has("phone")) throw bad();
        if (body.has("phone")) phone(body.get("phone"));
        password(body.get("password"));
        return body;
    }

    static JsonNode profile(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != PROFILE_FIELDS.size()) throw bad();
        for (String field : PROFILE_FIELDS) {
            if (!body.has(field)) throw bad();
        }
        text(body.get("name"), 2, 64);
        text(body.get("address"), 1, 256);
        phone(body.get("contact_phone"));
        coordinate(body.get("lng"), 180);
        coordinate(body.get("lat"), 90);
        return body;
    }

    private static void displayName(JsonNode node) {
        text(node, 2, 32);
    }

    private static void text(JsonNode node, int min, int max) {
        if (node == null || !node.isTextual()) throw bad();
        String value = node.textValue();
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max || !value.equals(value.trim())
            || value.codePoints().anyMatch(c -> c < 0x20)) throw bad();
    }

    private static void phone(JsonNode node) {
        if (node == null || !node.isTextual() || !node.textValue().matches("1[3-9][0-9]{9}")) throw bad();
    }

    private static void password(JsonNode node) {
        if (node == null || !node.isTextual()) throw bad();
        String value = node.textValue();
        if (value.length() < 8 || value.length() > 64
            || value.codePoints().anyMatch(c -> c < 0x21 || c > 0x7e)
            || !value.codePoints().anyMatch(Character::isLetter)
            || !value.codePoints().anyMatch(Character::isDigit)) throw bad();
    }

    private static void coordinate(JsonNode node, int bound) {
        if (node == null || node.isNull()) return;
        if (!node.isNumber()) throw bad();
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value < -bound || value > bound) throw bad();
    }
}
