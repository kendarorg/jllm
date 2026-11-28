package org.kendar.llm.web.sql;

import org.kendar.llm.harness.auth.TokenCipher;
import org.kendar.llm.harness.auth.TokenStore;
import org.kendar.llm.harness.context.mnemonic.MnemonicStore;
import org.kendar.llm.harness.identity.LoginSessionRepository;
import org.kendar.llm.harness.plugin.PluginStorages;
import org.kendar.llm.harness.identity.RoleRepository;
import org.kendar.llm.harness.identity.SkillRepository;
import org.kendar.llm.harness.identity.UserRepository;
import org.kendar.llm.harness.session.ChatSessionStore;
import org.kendar.llm.harness.session.SubAgentRunLog;
import org.kendar.llm.harness.state.StateStore;

/**
 * Every store of one {@link Database}, ready to be handed to the application. Closing it closes the database.
 *
 * @param tokenCipher null = tokens stored in clear
 */
public record SqlStores(Database database, UserRepository users, RoleRepository roles, SkillRepository skills,
                        LoginSessionRepository sessions, TokenStore tokens, SqlSettingsSource settings,
                        SqlSettingsSink settingsSink,
                        ChatSessionStore chats, StateStore state, MnemonicStore mnemonic,
                        SubAgentRunLog subAgentRuns, PluginStorages pluginStorages,
                        SqlInstructionRepository instructions, SqlAgentRepository agents,
                        SqlConfigProvider config) implements AutoCloseable {

    public static SqlStores of(Database db, TokenCipher tokenCipher) {
        var skills = new SqlSkillRepository(db);
        var settings = new SqlSettingsSource(db);
        var plugins = new SqlPluginStorage(db);
        var instructions = new SqlInstructionRepository(db);
        var agents = new SqlAgentRepository(db);
        return new SqlStores(db, new SqlUserRepository(db), new SqlRoleRepository(db), skills,
                new SqlLoginSessionRepository(db), new SqlTokenStore(db, tokenCipher), settings,
                new SqlSettingsSink(db), new SqlChatStores.Sessions(db), new SqlChatStores.State(db),
                new SqlChatStores.Mnemonic(db), new SqlChatStores.Runs(db), plugins, instructions, agents,
                new SqlConfigProvider(settings, instructions, agents, skills, plugins, sqliteFiles(db.config())));
    }

    /** The SQLite file and its directory (what the server keeps on disk); empty for another database or memory. */
    private static java.util.List<java.nio.file.Path> sqliteFiles(SqlConfig config) {
        String url = config.jdbcUrl();
        String prefix = "jdbc:sqlite:";
        if (!url.startsWith(prefix) || url.contains(":memory:")) return java.util.List.of();
        String file = url.substring(prefix.length());
        int q = file.indexOf('?');
        if (q >= 0) file = file.substring(0, q);
        return java.util.List.of(java.nio.file.Path.of(file).toAbsolutePath().normalize());
    }

    /** Opens the database (creating the schema when missing) and builds the stores. */
    public static SqlStores open(SqlConfig config, TokenCipher tokenCipher) {
        return of(Database.open(config), tokenCipher);
    }

    @Override
    public void close() {
        database.close();
    }
}
