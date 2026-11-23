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
import org.kendar.llm.harness.settings.ProjectMcpTrust;
import org.kendar.llm.harness.state.StateStore;

/**
 * Every store of one {@link Database}, ready to be handed to the application. Closing it closes the database.
 *
 * @param tokenCipher null = tokens stored in clear
 */
public record SqlStores(Database database, UserRepository users, RoleRepository roles, SkillRepository skills,
                        LoginSessionRepository sessions, TokenStore tokens, SqlSettingsSource settings,
                        ProjectMcpTrust mcpTrust, ChatSessionStore chats, StateStore state, MnemonicStore mnemonic,
                        SubAgentRunLog subAgentRuns, PluginStorages pluginStorages) implements AutoCloseable {

    public static SqlStores of(Database db, TokenCipher tokenCipher) {
        return new SqlStores(db, new SqlUserRepository(db), new SqlRoleRepository(db), new SqlSkillRepository(db),
                new SqlLoginSessionRepository(db), new SqlTokenStore(db, tokenCipher), new SqlSettingsSource(db),
                new SqlProjectMcpTrust(db), new SqlChatStores.Sessions(db), new SqlChatStores.State(db),
                new SqlChatStores.Mnemonic(db), new SqlChatStores.Runs(db), new SqlPluginStorage(db));
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
