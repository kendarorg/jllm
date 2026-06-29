package org.kendar.jllm.tools;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class WebSearchTool implements LLMTool {

  private static final int MAX_CHARS = 100_000;

  private final String endpoint;

  public WebSearchTool(String endpoint) {
    this.endpoint = endpoint;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean requiresApproval() {
    return false;
  }

  @Override
  public String name() {
    return "web_search";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("web_search", "Search the web for a query. Requires a configured search endpoint.")
        .prop("query", "string", "The search query.", true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String query = args == null ? null : args.get("query");
    if (query == null || query.isEmpty()) {
      throw new LLMToolException("Missing required argument: query");
    }
    if (endpoint == null || endpoint.isEmpty()) {
      return "web_search is not configured (no search endpoint set).";
    }
    String url = endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8);
    HttpGet get = new HttpGet(url);
    try (CloseableHttpClient client = HttpClients.createDefault()) {
      return client.execute(get, response -> {
        String body = response.getEntity() == null
            ? ""
            : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        if (body.length() > MAX_CHARS) {
          body = body.substring(0, MAX_CHARS) + "\n[truncated]";
        }
        return body;
      });
    } catch (Exception e) {
      throw new LLMToolException("Web search failed for query: " + query, e);
    }
  }
}
