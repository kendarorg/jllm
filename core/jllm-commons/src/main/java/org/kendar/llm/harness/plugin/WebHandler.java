package org.kendar.llm.harness.plugin;

/** An endpoint of a web plugin. Authentication has been done by the host before it runs. */
@FunctionalInterface
public interface WebHandler {
    WebResponse handle(WebRequest request) throws Exception;
}
