package org.kendar.llm.web.sql;

import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;

import java.util.List;
import java.util.Optional;

/** {@link SkillRepository} on {@code skills}; global skills have the empty owner (never a valid user id). */
public final class SqlSkillRepository implements SkillRepository {

    private static final String GLOBAL = "";

    private final Database db;

    public SqlSkillRepository(Database db) {
        this.db = db;
    }

    @Override
    public List<Skill> global() {
        return list(GLOBAL);
    }

    @Override
    public List<Skill> own(String userId) {
        return UserRepository.validId(userId) ? list(userId) : List.of();
    }

    @Override
    public Optional<Skill> findGlobal(String name) {
        return find(GLOBAL, name);
    }

    @Override
    public Optional<Skill> findOwn(String userId, String name) {
        return UserRepository.validId(userId) ? find(userId, name) : Optional.empty();
    }

    @Override
    public Skill writeGlobal(String name, String description, String content) {
        return write(GLOBAL, name, description, content);
    }

    @Override
    public Skill writeOwn(String userId, String name, String description, String content) {
        if (!UserRepository.validId(userId)) throw new IllegalArgumentException("Invalid user id");
        return write(userId, name, description, content);
    }

    @Override
    public boolean deleteGlobal(String name) {
        return delete(GLOBAL, name);
    }

    @Override
    public boolean deleteOwn(String userId, String name) {
        return UserRepository.validId(userId) && delete(userId, name);
    }

    @Override
    public void deleteAllOwn(String userId) {
        if (!UserRepository.validId(userId)) return;
        db.run(tx -> tx.update("DELETE FROM skills WHERE owner_id = ?", userId));
    }

    @Override
    public List<String> ownersOf(String name) {
        if (!SkillRepository.validName(name)) return List.of();
        return db.tx(tx -> tx.query("SELECT owner_id FROM skills WHERE name = ? AND owner_id <> ? ORDER BY owner_id",
                rs -> rs.getString(1), name, GLOBAL));
    }

    private List<Skill> list(String owner) {
        return db.tx(tx -> tx.query("SELECT name, description, content FROM skills WHERE owner_id = ? ORDER BY name",
                rs -> new Skill(rs.getString(1), rs.getString(2), rs.getString(3)), owner));
    }

    private Optional<Skill> find(String owner, String name) {
        if (!SkillRepository.validName(name)) return Optional.empty();
        return db.tx(tx -> tx.one("SELECT name, description, content FROM skills WHERE owner_id = ? AND name = ?",
                rs -> new Skill(rs.getString(1), rs.getString(2), rs.getString(3)), owner, name));
    }

    private Skill write(String owner, String name, String description, String content) {
        if (!SkillRepository.validName(name)) throw new IllegalArgumentException("Invalid skill name: " + name);
        SkillRepository.checkSize(description, content);
        String d = SkillRepository.cleanDescription(description);
        String c = content == null ? "" : content;
        db.run(tx -> tx.upsert("skills", List.of("owner_id", "name"), List.of("description", "content"), owner, name, d, c));
        return new Skill(name, d, c);
    }

    private boolean delete(String owner, String name) {
        if (!SkillRepository.validName(name)) return false;
        return db.tx(tx -> tx.update("DELETE FROM skills WHERE owner_id = ? AND name = ?", owner, name) > 0);
    }
}
