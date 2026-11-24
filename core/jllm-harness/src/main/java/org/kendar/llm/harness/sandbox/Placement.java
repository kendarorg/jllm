package org.kendar.llm.harness.sandbox;

import org.kendar.llm.harness.HarnessConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Where a chat's container lives: the settings in force for it (configuration plus the policy of its user), the docker
 * host, and the identity of the container (scope key, name, volume).
 *
 * @param docker     effective container settings (profile, scope, limits, network, cleanup)
 * @param dockerHost docker host override of the user; null: the main one
 * @param key        scope key: the chat id ({@code chat} scope), the user id ({@code user} scope); CLI: project hash
 *                   (plus the chat id in {@code chat} scope)
 * @param name       container name
 * @param volume     named volume mounted at {@code /workspace}; null: an anonymous one (or the project, CLI)
 * @param project    CLI: hash of the project directory (label), else null
 * @param userId     owner user, null when unknown
 * @param userScope  one container for all the chats of the user, each in {@code /workspace/<chatId>}
 */
record Placement(HarnessConfig.Docker docker, String dockerHost, String key, String name, String volume,
                 String project, String userId, boolean userScope) {

    /** The id when it is a valid chat id (so a valid docker name part), else a hash of it. */
    static String safe(String id) {
        return id.matches("[A-Za-z0-9._-]{1,64}") && !id.contains("..") ? id : hash(id).substring(0, 16);
    }

    static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Docker settings: the hardened profile has no network unless it was granted. */
    boolean noNetwork() {
        return docker.hardened() && !"default".equalsIgnoreCase(docker.network());
    }

    /** Key of the owner of the backups: {@code chat/<id>} or {@code user/<id>}. */
    String ownerKey() {
        return (userScope ? "user/" : "chat/") + key;
    }
}
