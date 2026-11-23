package org.kendar.llm.web.commons;

/** The cookies of a request. */
@FunctionalInterface
public interface Cookies {

    /** No cookie at all. */
    Cookies NONE = name -> null;

    /** The value of the named cookie, or null. */
    String get(String name);
}
