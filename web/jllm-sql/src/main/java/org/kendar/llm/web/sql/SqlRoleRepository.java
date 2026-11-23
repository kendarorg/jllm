package org.kendar.llm.web.sql;

import org.kendar.llm.harness.identity.Role;
import org.kendar.llm.harness.identity.RoleRepository;
import org.kendar.llm.harness.permission.ToolPermission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** {@link RoleRepository} on {@code roles}, {@code role_tools} and {@code role_permissions}. */
public final class SqlRoleRepository implements RoleRepository {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");

    private final Database db;

    public SqlRoleRepository(Database db) {
        this.db = db;
    }

    @Override
    public Optional<Role> find(String name) {
        if (!valid(name)) return Optional.empty();
        return db.tx(tx -> load(tx, name));
    }

    @Override
    public List<Role> all() {
        return db.tx(tx -> {
            List<Role> out = new ArrayList<>();
            for (String n : tx.query("SELECT name FROM roles ORDER BY name", rs -> rs.getString(1))) {
                load(tx, n).ifPresent(out::add);
            }
            return out;
        });
    }

    @Override
    public void save(Role role) {
        if (!valid(role.name())) throw new IllegalArgumentException("Invalid role name: " + role.name());
        db.run(tx -> {
            tx.upsert("roles", List.of("name"), List.of("read_only", "approval"), role.name(), role.readOnly(),
                    role.approval());
            tx.update("DELETE FROM role_tools WHERE role = ?", role.name());
            tx.update("DELETE FROM role_permissions WHERE role = ?", role.name());
            int ord = 0;
            for (String t : role.tools()) {
                tx.update("INSERT INTO role_tools (role, ord, pattern) VALUES (?, ?, ?)", role.name(), ord++, t);
            }
            ord = 0;
            for (var e : role.toolPermissions().entrySet()) {
                tx.update("INSERT INTO role_permissions (role, ord, pattern, permission) VALUES (?, ?, ?, ?)",
                        role.name(), ord++, e.getKey(), e.getValue().name().toLowerCase(java.util.Locale.ROOT));
            }
        });
    }

    @Override
    public boolean delete(String name) {
        if (!valid(name)) return false;
        return db.tx(tx -> {
            tx.update("DELETE FROM role_tools WHERE role = ?", name);
            tx.update("DELETE FROM role_permissions WHERE role = ?", name);
            return tx.update("DELETE FROM roles WHERE name = ?", name) > 0;
        });
    }

    private static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static Optional<Role> load(Tx tx, String name) {
        return tx.one("SELECT read_only, approval FROM roles WHERE name = ?",
                rs -> new Object[]{rs.getInt(1) != 0, rs.getString(2)}, name).map(r -> {
            List<String> tools = tx.query("SELECT pattern FROM role_tools WHERE role = ? ORDER BY ord",
                    rs -> rs.getString(1), name);
            Map<String, ToolPermission> perms = new LinkedHashMap<>();
            tx.query("SELECT pattern, permission FROM role_permissions WHERE role = ? ORDER BY ord",
                    rs -> new String[]{rs.getString(1), rs.getString(2)}, name)
                    .forEach(p -> perms.put(p[0], ToolPermission.parse(p[1])));
            return new Role(name, tools, (Boolean) r[0], (String) r[1], perms);
        });
    }
}
