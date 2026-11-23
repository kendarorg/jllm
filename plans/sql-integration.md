# SQL integration plan

Goal: `jllm-sql-spring-starter` (web group). On the classpath the web app uses SQL stores; absent, the existing file
stores. Planning only. No compat shims, no dual paths other than starter present/absent (plus `jllm.store`).
The importer of an existing `webauth/` folder is **deferred and optional (step 11)**, not part of the core work.

## Findings

- File stores today:
  - `HarnessFactory.harness(...)` (web-commons) takes nullable `SettingsSource`, `ProjectMcpTrust`, `ChatSessionStore`,
    `SubAgentRunLog`, `MnemonicStore`, `StateStore`, `PluginStorages`; null means the file default
    (`FileChatSessionStore`, `FileMnemonicStore`, `FileStateStore`, `FileSettingsSource`, `FileProjectMcpTrust`).
  - `JllmWebAutoConfiguration.agentHarness` already passes `ObjectProvider<...>` beans of those types. The chat, state,
    mnemonic, settings and trust stores need no change in web-commons or the starter: SQL beans only have to exist.
  - `jllm-web` `WebAuthConfiguration` builds `FileUserRepository`, `FileRoleRepository`, `FileSkillRepository`,
    `InMemoryLoginSessionRepository` under `@ConditionalOnProperty(jllm.store=file, matchIfMissing)`. Its
    `harnessIdentity` already takes `ObjectProvider<TokenStore>` (null means `<webauth>/tokens` files).
- Ordering problem: `WebAuthConfiguration` is a component-scanned `@Configuration`, processed BEFORE auto-configurations.
  `@ConditionalOnMissingBean` there never sees the starter's beans. The file beans must move into an
  `@AutoConfiguration` ordered `after` the SQL one.
- `jllm-sql`: `SqlStores.open(SqlConfig, TokenCipher)` returns a record with users, roles, skills, login sessions,
  tokens, settings, mcpTrust, chats, state, mnemonic, subAgentRuns. `SqlConfig.fromProperties(props, defaultDir)`;
  no url means SQLite `jllm.db` in `defaultDir`. `TokenCipher.fromEnv(Function)` reads `JLLM_TOKEN_KEY` (+`_OLD`),
  returns null when unset (tokens in clear), throws on an invalid key.
- `RealPasswordInitializer` and `ApiInterceptor` only use the `UserRepository` / `WebAuthentication` interfaces:
  compatible. Still needs a test against `SqlUserRepository`.
- An empty SQL DB means nobody can sign in (file mode ships `release/template/webauth` with admin/poweru/guest).
  Seeding is required (step 5).
- Release: `jllm-web.jar` is a Spring Boot ZIP-layout jar (PropertiesLauncher, `-Dloader.path=lib-ext`) built from
  `jllm-web` deps; `release.sh` / `release.ps1` copy one jar. `jllm-web` has no dependency on `jllm-sql`.

## Steps

1. **Module `web/jllm-sql-spring-starter`** (add to `web/pom.xml` after `jllm-sql`).
   - pom mirrors `jllm-web-spring-starter` (parent `../../pom.xml`, compiler `parameters=true`, scm/url); deps:
     `jllm-sql`, `spring-boot-autoconfigure`; test: `spring-boot-starter-test`.
   - `org.kendar.llm.web.sql.spring.JllmSqlAutoConfiguration`:
     `@AutoConfiguration(beforeName = "org.kendar.llm.web.FileStoresAutoConfiguration")` (string, no compile
     dependency on `jllm-web`), `@ConditionalOnProperty(name="jllm.store", havingValue="sql", matchIfMissing=true)`.
   - Beans: `SqlStores` (`destroyMethod="close"`; `SqlConfig.fromProperties(env::getProperty, dir)`, cipher from
     `TokenCipher.fromEnv(...)` built inside, never published since it can be null), then one bean per store type:
     `UserRepository`, `RoleRepository`, `SkillRepository`, `LoginSessionRepository`, `TokenStore`, `SettingsSource`,
     `ProjectMcpTrust`, `ChatSessionStore`, `StateStore`, `MnemonicStore`, `SubAgentRunLog`.
   - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
   - Test: step 8.
2. **Move file stores into an auto-configuration (jllm-web).** Replace the file beans of `WebAuthConfiguration` by
   `FileStoresAutoConfiguration` (`@AutoConfiguration(afterName="org.kendar.llm.web.sql.spring.JllmSqlAutoConfiguration")`,
   registered in new `jllm-web/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`;
   `JllmWebApplication` already excludes auto-configurations from the scan).
   - File beans: `@ConditionalOnMissingBean(<Type>.class)`; remove the per-bean `jllm.store=file` conditions.
   - `WebAuthConfiguration` keeps only store-agnostic beans (`Permissions`, `RealPasswordInitializer`, `LoginThrottle`,
     OIDC, APIs, `CookieAuthentication`, `ToolCatalogView`, `harnessIdentity`, which keeps `ObjectProvider<TokenStore>`).
   - Files: `jllm-web/.../WebAuthConfiguration.java`, new `FileStoresAutoConfiguration.java`, the imports file.
   - Test: step 8; existing `jllm-web` tests (no starter) must pass unchanged.
3. **`jllm.store` switch.** Values `file|sql`; any other value fails startup naming the key. `jllm.store=file` turns the SQL
   config off, so the file beans win. `jllm.store=sql` without the starter fails at startup with a clear message
   (check in `FileStoresAutoConfiguration`).
4. **Audit for file leakage when SQL is active** (`HarnessFactory.chatHistoryRoots`, `FilePluginStorage`, sandbox/upload
   roots). Workspace files stay on disk by design; change code only if a store is found. Files: `HarnessFactory.java`,
   `ChatApi`, `FilesApi`.
5. **Seed defaults.** In the starter, an `ApplicationRunner` ordered before `RealPasswordInitializer`
   (`Ordered.HIGHEST_PRECEDENCE`): when users and roles are both empty and `jllm.sql.seed` is not `false`, create the
   roles and admin/poweru/guest exactly as in `release/template/webauth` (read the template first). Test: seeded once,
   not re-seeded; with `jllm.web.use-real-password=true` seeded users get random passwords.
6. **Known gaps from the jllm-sql author.**
   - Repository interfaces live in `jllm-harness` identity, not web-commons: leave (used by `AgentHarness`/`HarnessIdentity`;
     moving them inverts the dependency for no gain). Docs and the memory note should say so.
   - `users -> roles` FK: done in V1 (ON DELETE RESTRICT, seeded admin row); `AdminApi` role delete answers 409.
   - `MAX_OWN` enforced in `MeApi` (check then insert, racy): acceptable for per-user interactive use; no change.
   - Chat messages as one `messages TEXT` per chat, rewritten per save: add a measuring test in `SqlStoresTest`
     (5k-message chat saved 100 times, log time). Change to a per-message table only if it is a problem; not in the
     first merge. Q5.
   - MySQL DDL never run: `MysqlOptionalTest` exists. The user runs it against a real MySQL; docs say "experimental"
     until then. Not run by the agent (needs a server).
7. **Packaging (`release/`).** Preferred: SQL jars go into `lib-ext/` of the single `jllm-web.jar` (`jllm-sql-spring-starter`,
   `jllm-sql`, HikariCP, sqlite-jdbc, mysql-connector-j), so "starter on the classpath" needs no second web jar.
   Alternative: a Maven profile producing `jllm-web-sql.jar`. Q2.
   Files: `release/release.sh`, `release/release.ps1` (copy/resolve the jars; keep the GraalJS-in-jar guard),
   `release/template/run-web.sh`, `run-web.cmd`, `release/template/application.properties` (commented `jllm.store`,
   `jllm.sql.url/user/password/pool`, note on `JLLM_TOKEN_KEY`), `release/setup/*`. Check sqlite-jdbc natives cover
   Windows, Linux, macOS.
8. **Tests.**
   - starter `JllmSqlAutoConfigurationTest` (`ApplicationContextRunner`): default gives `Sql*` for every store type;
     `jllm.store=file` gives none; `jllm.sql.url=jdbc:sqlite:<@TempDir>/x.db` honored; invalid `JLLM_TOKEN_KEY` fails
     (env supplied through an overridable `Function<String,String>` bean defaulting to `System::getenv`).
   - `jllm-web` `FileStoresAutoConfigurationTest`: without starter, file beans; app-supplied `UserRepository` replaces the
     file one; `jllm.store=sql` without starter fails.
   - SQL boot test (starter test-scoped, see Q6): login as seeded admin, create a chat, restart the context, chat still
     there; `RealPasswordInitializer` and `ApiInterceptor` over SQL users; token round trip with and without cipher.
   - Cross-platform: `@TempDir`, no hard-coded separators, close the database on context shutdown (Windows file locks).
9. **Docs.** `docs/enterprise/deployment.md` (stores; replace "MySQL store not shipped" and its link),
   `docs/reference/usage.md` (`jllm.store`, `jllm.sql.*`; `jllm.webauth-dir` is file-only), `docs/guides/extending.md`,
   `docs/enterprise/security-model.md` (`JLLM_TOKEN_KEY`: SQL tokens encrypted when set, clear otherwise),
   `docs/reference/overview.md`, `web/jllm-web-spring-starter/README.md`, new `web/jllm-sql-spring-starter/README.md`,
   `jllm-web/README.md` ("possible extension: MySQL store" is now real).
10. **Verify.** `mvn -B clean install` with `JAVA_HOME=~/.jdks/corretto-21.0.9`; no LLM/Docker; never during a model eval;
    also a Linux/macOS CI pass. No commit.
11. **Deferred, optional importer** of an existing `webauth/` (users, permissions, skills) plus `chats/` and `tokens/`
    into SQL. Not needed now. If wanted later: `--import-files <webauth-dir>` in the starter, reading through the
    `File*` repositories and writing to `SqlStores`, idempotent.

## Open questions

1. Default SQLite location: start folder, `jllm.webauth-dir`, or a `data/` dir?
2. Packaging: SQL jars in `lib-ext/` of one `jllm-web.jar`, or a separate `jllm-web-sql.jar`?
3. Plugin storage (`PluginStorages`) stays on files when SQL is active: acceptable?
4. Add the `users -> roles` FK (V2 migration plus role-delete handling), or keep the check in code?
5. Chat messages column: leave for now, or move to a per-message table before first release?
6. Where do SQL boot tests live: `jllm-web` with a test profile, or a new module (existing `jllm-web` tests must stay on files)?
7. Seeding a fresh SQL DB: same admin/poweru/guest defaults as the file template, or require an explicit admin password?

## Status (implemented, steps 1-10; step 11 stays deferred)

Done: `web/jllm-sql-spring-starter` (`JllmSqlAutoConfiguration`, `SqlSeeder`), `FileStoresAutoConfiguration` in jllm-web
(store check: `file|sql`, `sql` without the starter fails), release packaging, tests, docs. Decisions applied: Q1 default
SQLite file `<jllm.webauth-dir>/jllm.db` else `data/jllm.db`; Q2 SQL jars in `lib-ext/sql` (not `lib-ext/*.jar`: `fetch-graaljs`
empties that); Q3 plugin storage stays on files; Q4 no FK, check stays in code; Q5 messages column kept, measuring test
`SqlStoresTest.largeChatSaveTiming` (about 1.5 ms per save of a 5k-message chat on SQLite); Q6 SQL boot tests in jllm-web
(`SqlBootTest`; `src/test/resources/application.properties` sets `jllm.store=file`); Q7 seed admin/poweru/guest.
Deviation: seeding happens when the `SqlStores` bean is created, not in an `ApplicationRunner`, so roles exist before
`OidcClient` validates role names.
Additions requested later: dialect from the JDBC url only (`Dialect.of`, tested); PostgreSQL as a third dialect
(`db/postgres`, optional `PostgresOptionalTest`); JDBC drivers for MySQL (GPL) and PostgreSQL are not bundled: pinned in the
root pom + SHA-256 manifests (`db/drivers/<db>.manifest`), fetched by `scripts/setup` / `fetch-sql-driver` into
`.downloaded-libs/<db>/<version>` (checkout) or `lib-ext` (release), loaded by `DriverLoader` (only the driver of the url);
the GraalJS set moved from `.graal/` to `.downloaded-libs/graal/<version>` (no fallback; old `.graal/` is stale); no
JPA/Hibernate/Spring Data. MySQL/PostgreSQL stay experimental until run against real servers
(`JLLM_TEST_MYSQL_URL`, `JLLM_TEST_PG_URL`).
