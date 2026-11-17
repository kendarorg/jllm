package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kendar.llm.harness.integration.RestApiToolProvider;
import org.kendar.llm.harness.settings.RestApiSettings;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The OAuth2 login of a REST API ({@code restApis[].oauth}), named {@code api:<name>}: the generic login, with the
 * first {@code oauth2} scheme of the OpenAPI spec as the last source of endpoints.
 */
public final class RestOAuthLogin extends GenericOAuthLogin {

    private final RestApiSettings api;

    public RestOAuthLogin(RestApiSettings api, HttpClient http, ObjectMapper mapper) {
        this(api, http, mapper, () -> System.currentTimeMillis() / 1000);
    }

    public RestOAuthLogin(RestApiSettings api, HttpClient http, ObjectMapper mapper, LongSupplier clock) {
        super(api.source(), "REST API '" + api.name() + "'", requireOAuth(api), http, mapper, clock);
        this.api = api;
    }

    private static org.kendar.llm.harness.settings.OAuthSettings requireOAuth(RestApiSettings api) {
        if (api.oauth() == null) throw new IllegalArgumentException("REST API '" + api.name() + "' has no oauth");
        return api.oauth();
    }

    public RestApiSettings api() {
        return api;
    }

    @Override
    protected SpecEndpoints specEndpoints(String flow) {
        // read with the static headers only: a spec behind specAuth needs its endpoints in the settings
        try {
            RestApiToolProvider.SpecOAuth s = RestApiToolProvider.specOAuth(api, flow, Map.of());
            return s == null ? null : new SpecEndpoints(s.authorizationUrl(), s.tokenUrl(), s.scopes());
        } catch (RuntimeException e) {
            return null; // the spec cannot be read now: the explicit settings must be enough
        }
    }
}
