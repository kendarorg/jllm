package org.kendar.llm.web.commons;

/**
 * A cookie to set on the response.
 *
 * @param maxAge   seconds; 0 removes the cookie
 * @param sameSite {@code Strict}, {@code Lax} or {@code None}
 */
public record CookieOut(String name, String value, String path, long maxAge, boolean httpOnly, boolean secure,
                        String sameSite) {
}
