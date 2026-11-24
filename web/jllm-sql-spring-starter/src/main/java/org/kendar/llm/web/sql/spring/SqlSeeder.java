package org.kendar.llm.web.sql.spring;

import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.RoleSandbox;
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

    /**
     * Safe when several instances start on one database: a loser of the race finds the rows of the winner (a duplicate
     * key is not a failure).
     *
     * @return whether this call wrote the defaults
     */
    public static boolean seed(UserRepository users, RoleRepository roles) {
        // the admin role row always exists (schema); only other roles or any user mean the database is in use
        if (!users.all().isEmpty() || roles.all().stream().anyMatch(r -> !r.fixed())) return false;
        try {
            roles.save(Role.admin("ask", Map.of(), RoleSandbox.NONE));
            roles.save(new Role("poweru", List.of("run_js", "bash", "read_file", "write_file", "edit_file", "ls", "glob",
                    "grep", "grep_code", "web_search", "wikipedia_search", "web_fetch", "web_download"), false, "ask",
                    Map.of(), RoleSandbox.NONE));
            roles.save(new Role("guest", List.of("run_js", "web_search", "wikipedia_search", "web_fetch", "web_download"),
                    false, "ask", Map.of("bash", ToolPermission.DENY), RoleSandbox.NONE));
            boolean created = false;
            created |= createIfAbsent(users, "admin", "Admin");
            created |= createIfAbsent(users, "poweru", "Power user");
            created |= createIfAbsent(users, "guest", "Guest");
            if (!created) return false;
        } catch (RuntimeException e) {
            // another instance seeding at the same moment: its rows are there, ours collided
            if (users.exists("admin") && users.exists("poweru") && users.exists("guest")) return false;
            throw e;
        }
        log.warn("Empty database: created the users admin, poweru and guest (default passwords equal to the user name; "
                + "set jllm.web.use-real-password=true to get random ones)");
        return true;
    }

    private static boolean createIfAbsent(UserRepository users, String id, String name) {
        if (users.exists(id)) return false;
        try {
            users.create(id, name, id, null, null);
            return true;
        } catch (IllegalStateException e) { // "exists": created by another instance in between
            if (users.exists(id)) return false;
            throw e;
        }
    }
}
