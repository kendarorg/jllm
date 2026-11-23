package org.kendar.llm.web.commons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a handler answers when more than a body is needed: status, headers and cookies. A plain body is returned as
 * itself; an error is an {@link ApiException}.
 *
 * @param body null for an empty response
 */
public record Reply<T>(int status, T body, Map<String, String> headers, List<CookieOut> cookies) {

    public static <T> Reply<T> status(int status, T body) {
        return new Reply<>(status, body, Map.of(), List.of());
    }

    public static Reply<Void> empty(int status) {
        return status(status, null);
    }

    public Reply<T> header(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new Reply<>(status, body, h, cookies);
    }

    public Reply<T> cookie(CookieOut cookie) {
        List<CookieOut> c = new ArrayList<>(cookies);
        c.add(cookie);
        return new Reply<>(status, body, headers, c);
    }
}
