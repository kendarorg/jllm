package org.kendar.llm.web;

import org.kendar.llm.harness.identity.User;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.web.commons.JllmProperties;
import org.kendar.llm.web.commons.auth.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * With {@code jllm.web.use-real-password=true}: every user still on the default password gets a random one at startup.
 * Only the administrators' passwords are logged, once; other users are told to ask an administrator for a reset.
 * Users linked to an identity provider and users with a password already are left alone.
 */
public class RealPasswordInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RealPasswordInitializer.class);
    private final UserRepository users;
    private final boolean enabled;

    public RealPasswordInitializer(UserRepository users, JllmProperties jllm) {
        this.users = users;
        this.enabled = jllm.web() != null && jllm.web().realPasswords();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        for (User u : users.all()) {
            if (!u.usesDefaultPassword()) continue;
            String password = PasswordHasher.generate();
            users.setPassword(u.id(), PasswordHasher.hash(password));
            if (u.admin()) {
                log.warn("Generated password for administrator '{}': {} (shown once; change it after signing in)",
                        u.id(), password);
            } else {
                log.warn("Generated a random password for user '{}'; an administrator can reset it", u.id());
            }
        }
    }
}
