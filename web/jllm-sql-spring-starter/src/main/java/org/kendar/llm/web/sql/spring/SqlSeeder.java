package org.kendar.llm.web.sql.spring;

import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.RoleRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.permission.ToolPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The roles and users of {@code release/template/webauth} (admin, poweru, guest; every user on the default password),
 * written into a database that has no users and no roles. Without them nobody could sign in.
 */
public final class SqlSeeder {

    private static final Logger log = LoggerFactory.getLogger(SqlSeeder.class);

    private SqlSeeder() {
    }

    /** @return whether the defaults were written */
    public static boolean seed(UserRepository users, RoleRepository roles) {
        if (!users.all().isEmpty() || !roles.all().isEmpty()) return false;
        roles.save(Role.admin("ask", Map.of()));
        roles.save(new Role("poweru", List.of("run_js", "bash", "read_file", "write_file", "edit_file", "ls", "glob",
                "grep", "grep_code", "web_search", "wikipedia_search", "web_fetch", "web_download"), false, "ask",
                Map.of()));
        roles.save(new Role("guest", List.of("run_js", "web_search", "wikipedia_search", "web_fetch", "web_download"),
                false, "ask", Map.of("bash", ToolPermission.DENY)));
        users.create("admin", "Admin", "admin", null, null);
        users.create("poweru", "Power user", "poweru", null, null);
        users.create("guest", "Guest", "guest", null, null);
        log.warn("Empty database: created the users admin, poweru and guest (default passwords equal to the user name; "
                + "set jllm.web.use-real-password=true to get random ones)");
        return true;
    }
}
