package org.kendar.llm.harness.auth;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.function.Function;

/** Device code flow (RFC 8628): shows a code, the user approves it elsewhere, the token is polled. */
public class DeviceCodeLogin implements OAuthLogin {

    private final String name;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String deviceEndpoint;
    private final String clientId;
    private final String scope;
    private final OAuthTokenClient tokens;
    private final Function<OAuthToken, Map<String, String>> headerBuilder;

    public DeviceCodeLogin(String name, HttpClient http, ObjectMapper mapper, String deviceEndpoint,
                           OAuthTokenClient tokens, String clientId, String scope,
                           Function<OAuthToken, Map<String, String>> headerBuilder) {
        this.name = name;
        this.http = http;
        this.mapper = mapper;
        this.deviceEndpoint = deviceEndpoint;
        this.tokens = tokens;
        this.clientId = clientId;
        this.scope = scope;
        this.headerBuilder = headerBuilder;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public OAuthToken login(UserInteraction ui) {
        return DeviceCodeFlow.run(http, mapper, deviceEndpoint, clientId, scope, tokens, c -> {
            ui.show("Open " + c.verificationUri() + " and enter the code " + c.userCode());
            if (c.verificationUriComplete() != null) ui.openUrl(c.verificationUriComplete());
        }, Thread::sleep, () -> System.currentTimeMillis() / 1000);
    }

    /** No redirect: the device code works from anywhere. */
    @Override
    public OAuthToken login(UserInteraction ui, RedirectTarget target) {
        return login(ui);
    }

    @Override
    public AuthProvider authProvider(TokenStore store, String principalId) {
        return new RefreshingAuthProvider(principalId, name, store, t -> tokens.refresh(t.refresh()), headerBuilder,
                () -> System.currentTimeMillis() / 1000);
    }
}
