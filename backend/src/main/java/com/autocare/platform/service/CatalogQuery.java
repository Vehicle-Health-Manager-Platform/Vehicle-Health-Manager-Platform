package com.autocare.platform.service;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

/** Typed MVC parameters alone do not reject unknown or repeated selectors. */
public final class CatalogQuery {
    private CatalogQuery() {}
    public static void validate(MultiValueMap<String,String> query,String... allowed) {
        var names=Set.of(allowed);
        for(var entry:query.entrySet())
            if(!names.contains(entry.getKey()) || entry.getValue().size()!=1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"目录查询参数无效");
    }
}
