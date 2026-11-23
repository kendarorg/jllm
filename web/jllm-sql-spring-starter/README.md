# jllm-sql-spring-starter

With this starter on the class path the web application keeps everything in SQL instead of files: users, roles, skills,
sign-in sessions, OAuth tokens, harness settings, MCP project trust, chats (with their state, mnemonic state and
sub-agent runs). Without it the file stores of `jllm-web` apply. Built on `jllm-sql` (plain JDBC + HikariCP, a small
`Tx` helper and versioned migrations: **no JPA, Hibernate or Spring Data anywhere**, and none is a dependency of
`jllm-sql` or of this starter).

```
jllm-harness  <-  jllm-sql  <-  jllm-sql-spring-starter  ->  (jllm-web: FileStoresAutoConfiguration runs after it)
```

The repository interfaces (`UserRepository`, `RoleRepository`, `SkillRepository`, `LoginSessionRepository`) live in
`jllm-harness` (`org.kendar.llm.harness.identity`), next to the harness that uses them; the stores of chats, state,
settings and tokens are harness interfaces too.

## Switching

`jllm.store` is `sql` or `file`; any other value stops the start naming the key. Not set: SQL when the starter is on the
class path, else files. `jllm.store=file` turns the SQL configuration off. `jllm.store=sql` without the starter fails at
startup. The release zip ships the starter (and `jllm-sql`, HikariCP, SQLite) in `lib-ext/sql`, so it uses SQL by
default: set `jllm.store=file` to keep `webauth/` files. An existing `webauth/` is not imported (an optional importer
is not part of this module).

## The database: chosen by the JDBC url only

There is no dialect property. The scheme of `jllm.sql.url` selects it:

| url | database | driver |
|---|---|---|
| none (default) | SQLite file `jllm.db` in `jllm.webauth-dir` when that is set, else `data/jllm.db` | bundled |
| `jdbc:sqlite:<file>` | SQLite | bundled |
| `jdbc:mysql:...` (`jdbc:mariadb:` is rejected) | MySQL 8+, **experimental** until run against a real server | fetched, GPL |
| `jdbc:postgresql:...` | PostgreSQL, **experimental** until run against a real server | fetched, BSD |

Any other scheme is rejected at startup. Properties: `jllm.sql.url`, `jllm.sql.user`, `jllm.sql.password` (use
`${ENV}`), `jllm.sql.pool` (SQLite 1, others 10), `jllm.sql.seed` (default true), `jllm.sql.driver-dir`.

Schema: `db/<dialect>/V<n>__*.sql` listed in `migrations.txt`, applied at startup under a lock (MySQL `GET_LOCK`,
PostgreSQL advisory lock); never edit an applied script, add the next one. All three dialects have the same tables
(`DialectTest`). `users.role` is a NOT NULL foreign key to `roles(name)` (`ON DELETE RESTRICT`): the `admin` role has a
seeded row, a user needs an existing role, and deleting a role in use throws `RoleInUseException` (the admin API answers
409 naming the users, with the files store too). There is no migration history yet: the schema is edited in `V1`, so an
existing development database must be deleted. Not done: per-message chat rows (`messages` is one text per chat; `SqlStoresTest.largeChatSaveTiming` logs the cost of
saving a 5,000-message chat 100 times: about 1.5 ms per save on SQLite).

The chat list takes a `ChatVisibility` (`ChatSessionStore.list(visibility)`): the filter is a `WHERE` on `chats.principal`
(everything, owned, one owner, nothing), so a user's listing never loads other users' chats; the index
`(principal, updated_at)` is in `V1` for each dialect.

## JDBC drivers

SQLite is bundled. MySQL (GPLv2 with the Universal FOSS Exception) and PostgreSQL (BSD-2-Clause) drivers are never
bundled: their versions are pinned in the root pom (`mysql.version`, `postgresql.version`) and their SHA-256 in
`jllm-sql/src/main/resources/db/drivers/<db>.manifest` (a test checks both agree).

- Source checkout: `scripts/setup` (`.sh`, `.ps1`, `.cmd`) fetches all drivers into the git-ignored
  `.downloaded-libs/<db>/<version>/`, next to the GraalJS set (`.downloaded-libs/graal/<version>`). `--sql
  mysql|postgres|all|none` (default `all`). Or only the drivers: `scripts/fetch-sql-driver [mysql|postgres|all]`.
- Release zip: `setup.sh` / `setup.ps1` do the same into `lib-ext/` (manifests in `lib-ext/manifests/`, copied to
  `lib-ext/`); `--sql` / `-Sql` selects. `fetch-sql-driver.sh | .cmd | .ps1` later. `fetch-graaljs` leaves the driver
  jars alone, and the driver fetch leaves the GraalJS ones alone.
- MySQL asks y/N to accept its terms (`--accept-license` / `-AcceptLicense` accepts silently; no terminal and no flag
  fails). A download whose SHA-256 differs is deleted and the script fails.
- At run time only the driver of the url is looked for: the class path, then `jllm.sql.driver-dir`, else `lib-ext`, then
  `.downloaded-libs/<db>/<version>`. The jar must match the manifest SHA-256 (else it is refused). Other drivers in those
  folders are ignored. A missing driver stops the start with a message naming the jar and the script to run.

## Tokens and seeding

OAuth tokens are encrypted when `JLLM_TOKEN_KEY` (and optionally `JLLM_TOKEN_KEY_OLD`, for rotation) is set, else stored
in clear; an invalid key stops the start. A new database (no users and no roles besides `admin`) gets the roles and users of
`release/template/webauth` (admin, poweru, guest; password equal to the user name) unless `jllm.sql.seed=false`;
`jllm.web.use-real-password=true` replaces those passwords with random ones at startup. Seeding happens when the stores
are created, before anything reads the roles.

## Not on SQL

Workspace files (the chat directories, uploads, the sandbox) stay on disk by design. The plugin storage
(`PluginStorages`: the workflows' flows and runs, and every other plugin's documents) is in SQL, table `plugin_docs`;
with `jllm.store=file` it is files under `.jllm/plugin-data`. The tables are part of the initial schema: a development
database created before them must be deleted.

## Tests

`JllmSqlAutoConfigurationTest` (every store type; `jllm.store=file`; url; seeding; invalid key), `jllm-sql`: `SqlStoresTest`,
`DialectTest`, `DriverLoaderTest`, `FetchSqlDriverScriptTest` (the fetch script), `SqlSeederTest`; `jllm-web`: `FileStoresAutoConfigurationTest`, `SqlBootTest` (the starter is a test
dependency of `jllm-web`; `src/test/resources/application.properties` keeps the other tests on files).
`MysqlOptionalTest` and `PostgresOptionalTest` run only with `JLLM_TEST_MYSQL_URL|USER|PASSWORD` or
`JLLM_TEST_PG_URL|USER|PASSWORD` set (an empty disposable database); skipped otherwise, no Docker is started.
