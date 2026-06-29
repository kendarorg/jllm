package org.kendar.jllm.ollama;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.ssl.TrustStrategy;
import org.apache.hc.core5.util.Timeout;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kendar.jllm.LLMSettings;
import org.kendar.jllm.base.*;
import org.kendar.jllm.exceptions.LLMClientException;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Client talking directly to the Ollama REST API (the {@code /api/generate} endpoint)
 * using the Apache HttpClient 5 classic (blocking, HTTP/1.1 only) API.
 * <p>
 * HTTP/2 is explicitly not used and TLS/SSL verification is fully disabled.
 */
public class OllamaClient implements LLMClient {

  private final LLMSettings settings;

  public OllamaClient(LLMSettings settings) {
    this.settings = settings;
  }

  /**
   * Builds an HttpClient that trusts every certificate, performs no hostname
   * verification and uses the classic (HTTP/1.1) connection manager so HTTP/2 is
   * never negotiated.
   */
  private CloseableHttpClient buildClient() {
    try {
      TrustStrategy trustAll = (X509Certificate[] chain, String authType) -> true;
      SSLContext sslContext = SSLContexts.custom()
          .loadTrustMaterial(null, trustAll)
          .build();

      SSLConnectionSocketFactory sslFactory = new SSLConnectionSocketFactory(
          sslContext, NoopHostnameVerifier.INSTANCE);

      Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
          .register(URIScheme.HTTP.id, PlainConnectionSocketFactory.getSocketFactory())
          .register(URIScheme.HTTPS.id, sslFactory)
          .build();

      BasicHttpClientConnectionManager connectionManager =
          new BasicHttpClientConnectionManager(registry);
      connectionManager.setConnectionConfig(ConnectionConfig.custom()
          .setConnectTimeout(Timeout.ofSeconds(30))
          .setSocketTimeout(Timeout.ofMinutes(10))
          .build());

      return HttpClients.custom()
          .setConnectionManager(connectionManager)
          .build();
    } catch (Exception e) {
      throw new LLMClientException("Unable to build Ollama HTTP client", e);
    }
  }

  private String baseUrl() {
    String server = settings.getServer();
    if (server == null || server.isEmpty()) {
      server = "http://localhost:11434";
    }
    if (server.endsWith("/")) {
      server = server.substring(0, server.length() - 1);
    }
    return server;
  }

  private String buildPayload(LLMRequest request) {
    // Build the /api/chat request shape:
    // model, messages[], format, stream, think, keep_alive, options{temperature,top_k,top_p,num_ctx}.
    ObjectNode root = LLMObjectMapper.getObjectMapper().createObjectNode();
    root.put("model", settings.getModel());

    if (request.getPrompt() == null || request.getPrompt().isEmpty()) {
      throw new LLMClientException("No prompt provided in request", null);
    }

    var messages = root.putArray("messages");
    if (request.getSystem() != null && !request.getSystem().isEmpty()) {
      ObjectNode systemMsg = messages.addObject();
      systemMsg.put("role", "system");
      systemMsg.put("content", request.getSystem());
    }
    ObjectNode userMsg = messages.addObject();
    userMsg.put("role", "user");
    userMsg.put("content", request.getPrompt());

    if (request.getFormat() != null && !request.getFormat().isEmpty()) {
      root.put("format", request.getFormat());
    }

    // Streaming is not handled here; always request a single aggregated response.
    root.put("stream", false);
    if (request.getThink() != null && !request.getThink().isEmpty()) {
      root.put("think", request.getThink());
    }
    if (request.getKeepAlive() != null && !request.getKeepAlive().isEmpty()) {
      root.put("keep_alive", request.getKeepAlive());
    }

    ObjectNode options = root.putObject("options");
    options.put("temperature", request.getTemperature());
    options.put("top_k", request.getTopK());
    options.put("top_p", request.getTopP());
    options.put("num_ctx", request.getNumCtx());

    try {
      return LLMObjectMapper.getObjectMapper().writeValueAsString(root);
    } catch (Exception e) {
      throw new LLMClientException("Unable to serialize Ollama request", e);
    }
  }

  @Override
  public LLMResponse call(LLMRequest request) throws LLMClientException {
    String url = baseUrl() + "/api/chat";
    HttpPost post = new HttpPost(url);
    post.setHeader("Content-Type", "application/json");
    post.setEntity(new StringEntity(buildPayload(request), StandardCharsets.UTF_8));

    try (CloseableHttpClient client = buildClient()) {
      return client.execute(post, response -> {
        int status = response.getCode();
        String body = response.getEntity() == null
            ? ""
            : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
          throw new LLMClientException(
              "Ollama call failed with status " + status + ": " + body, null);
        }
        return parseResponse(body);
      });
    } catch (LLMClientException e) {
      throw e;
    } catch (Exception e) {
      throw new LLMClientException("Unable to execute Ollama call", e);
    }
  }

  private LLMResponse parseResponse(String body) {
    try {
      return LLMObjectMapper.getObjectMapper().readValue(body, LLMResponse.class);
    } catch (Exception e) {
      throw new LLMClientException("Unable to parse Ollama response: " + body, e);
    }
  }
}
