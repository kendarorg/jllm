package org.kendar.jllm.tools;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class WebFetchTool implements LLMTool {

  private static final int MAX_CHARS = 100_000;

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
    return "web_fetch";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("web_fetch", "Fetch the contents of a URL via HTTP GET and return the response body.")
        .prop("url", "string", "The URL to fetch.", true)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String url = args == null ? null : args.get("url");
    if (url == null || url.isEmpty()) {
      throw new LLMToolException("Missing required argument: url");
    }
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
      throw new LLMToolException("Unable to fetch URL: " + url, e);
    }
  }
}
