package com.autocare.platform.vehicle;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

/** Reject ignored selectors and ambiguous duplicate values before reading private data. */
final class OwnerArchiveQuery {
    private OwnerArchiveQuery() {}
    static void validate(MultiValueMap<String,String> query, String... allowed) {
        var names = Set.of(allowed);
        if (query.entrySet().stream().anyMatch(e -> !names.contains(e.getKey()) || e.getValue().size() != 1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询参数无效");
    }
}
