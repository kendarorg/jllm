package org.kendar.llm.web.sql;

import org.kendar.llm.harness.settings.InstructionRepository;

import java.util.List;
import java.util.Optional;

/** {@link InstructionRepository} on {@code instructions(scope, owner_id, name, content)}. */
public final class SqlInstructionRepository implements InstructionRepository {

    private final Database db;

    public SqlInstructionRepository(Database db) {
        this.db = db;
    }

    @Override
    public List<Instruction> list(Scope scope, String owner) {
        if (!validOwner(scope, owner)) return List.of();
        String o = owner == null ? "" : owner;
        return db.tx(tx -> tx.query("SELECT name, content FROM instructions WHERE scope = ? AND owner_id = ? ORDER BY name",
                rs -> new Instruction(scope, o, rs.getString(1), rs.getString(2)), scope.name(), o));
    }

    @Override
    public Optional<Instruction> find(Scope scope, String owner, String name) {
        if (!validOwner(scope, owner) || name == null || !NAME.matcher(name).matches()) return Optional.empty();
        String o = owner == null ? "" : owner;
        return db.tx(tx -> tx.one("SELECT name, content FROM instructions WHERE scope = ? AND owner_id = ? AND name = ?",
                rs -> new Instruction(scope, o, rs.getString(1), rs.getString(2)), scope.name(), o, name));
    }

    @Override
    public Instruction write(Scope scope, String owner, String name, String content) {
        InstructionRepository.check(scope, owner, name, content);
        String o = owner == null ? "" : owner;
        String c = content == null ? "" : content;
        db.run(tx -> tx.upsert("instructions", List.of("scope", "owner_id", "name"), List.of("content"),
                scope.name(), o, name, c));
        return new Instruction(scope, o, name, c);
    }

    @Override
    public boolean delete(Scope scope, String owner, String name) {
        if (!validOwner(scope, owner) || name == null || !NAME.matcher(name).matches()) return false;
        String o = owner == null ? "" : owner;
        return db.tx(tx -> tx.update("DELETE FROM instructions WHERE scope = ? AND owner_id = ? AND name = ?",
                scope.name(), o, name) > 0);
    }

    @Override
    public void deleteOwned(Scope scope, String owner) {
        if (!validOwner(scope, owner)) return;
        String o = owner == null ? "" : owner;
        db.run(tx -> tx.update("DELETE FROM instructions WHERE scope = ? AND owner_id = ?", scope.name(), o));
    }

    private static boolean validOwner(Scope scope, String owner) {
        try {
            InstructionRepository.checkOwner(scope, owner);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
