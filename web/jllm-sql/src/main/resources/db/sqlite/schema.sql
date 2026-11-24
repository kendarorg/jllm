-- jllm-sql schema, SQLite dialect. Idempotent: two instances may apply it together. Timestamps: epoch milliseconds (sessions) or epoch nanoseconds (chats).
CREATE TABLE IF NOT EXISTS roles (
    name      TEXT PRIMARY KEY,
    read_only INTEGER NOT NULL DEFAULT 0,
    approval  TEXT NOT NULL DEFAULT 'ask',
    settings TEXT
);
CREATE TABLE IF NOT EXISTS role_tools (
    role    TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
    ord     INTEGER NOT NULL,
    pattern TEXT NOT NULL,
    PRIMARY KEY (role, ord)
);
CREATE TABLE IF NOT EXISTS role_permissions (
    role       TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
    ord        INTEGER NOT NULL,
    pattern    TEXT NOT NULL,
    permission TEXT NOT NULL,
    PRIMARY KEY (role, ord)
);
-- The fixed admin role always has its row: users reference roles (ON DELETE RESTRICT) and admin users need it.
INSERT OR IGNORE INTO roles (name, read_only, approval) VALUES ('admin', 0, 'ask');
INSERT OR IGNORE INTO role_tools (role, ord, pattern) VALUES ('admin', 0, '*');
CREATE TABLE IF NOT EXISTS users (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    role          TEXT NOT NULL REFERENCES roles(name) ON DELETE RESTRICT,
    password_hash TEXT,
    external_id   TEXT,
    email         TEXT,
    settings      TEXT
);
CREATE INDEX IF NOT EXISTS idx_users_external ON users(external_id);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE TABLE IF NOT EXISTS user_credentials (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source  TEXT NOT NULL,
    header  TEXT NOT NULL,
    value   TEXT NOT NULL,
    PRIMARY KEY (user_id, source, header)
);
CREATE TABLE IF NOT EXISTS user_disabled_tools (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ord     INTEGER NOT NULL,
    pattern TEXT NOT NULL,
    PRIMARY KEY (user_id, ord)
);
CREATE TABLE IF NOT EXISTS user_disabled_skills (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ord     INTEGER NOT NULL,
    name    TEXT NOT NULL,
    PRIMARY KEY (user_id, ord)
);
CREATE TABLE IF NOT EXISTS skills (
    owner_id    TEXT NOT NULL,
    name        TEXT NOT NULL,
    description TEXT NOT NULL,
    content     TEXT NOT NULL,
    PRIMARY KEY (owner_id, name)
);
CREATE TABLE IF NOT EXISTS login_sessions (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON login_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON login_sessions(expires_at);
CREATE TABLE IF NOT EXISTS oauth_tokens (
    principal_id TEXT NOT NULL,
    name         TEXT NOT NULL,
    payload      TEXT NOT NULL,
    version      INTEGER NOT NULL,
    PRIMARY KEY (principal_id, name)
);
CREATE TABLE IF NOT EXISTS settings_layer (
    kind   TEXT PRIMARY KEY,
    origin TEXT NOT NULL,
    doc    TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS mcp_trust (
    project_key TEXT NOT NULL,
    name        TEXT NOT NULL,
    sha256      TEXT NOT NULL,
    trusted     INTEGER NOT NULL,
    PRIMARY KEY (project_key, name)
);
CREATE TABLE IF NOT EXISTS chats (
    chat_id                 TEXT PRIMARY KEY,
    agent_id                TEXT,
    title                   TEXT,
    mode                    TEXT,
    tool_groups             TEXT,
    input_tokens            INTEGER NOT NULL,
    output_tokens           INTEGER NOT NULL,
    cache_read_tokens       INTEGER NOT NULL,
    cache_creation_tokens   INTEGER NOT NULL,
    created_at              INTEGER NOT NULL,
    updated_at              INTEGER NOT NULL,
    message_count           INTEGER NOT NULL,
    qualifier_input_tokens  INTEGER NOT NULL,
    qualifier_output_tokens INTEGER NOT NULL,
    principal               TEXT,
    chat_tools              TEXT,
    tainted_tool_uses       TEXT
);
CREATE INDEX IF NOT EXISTS idx_chats_updated ON chats(updated_at);
CREATE INDEX IF NOT EXISTS idx_chats_principal ON chats(principal, updated_at);
CREATE TABLE IF NOT EXISTS chat_log (
    chat_id  TEXT PRIMARY KEY,
    messages TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS chat_state (
    chat_id TEXT NOT NULL,
    name    TEXT NOT NULL,
    doc     TEXT NOT NULL,
    PRIMARY KEY (chat_id, name)
);
CREATE TABLE IF NOT EXISTS mnemonic_state (
    chat_id   TEXT NOT NULL,
    agent_key TEXT NOT NULL,
    doc       TEXT NOT NULL,
    PRIMARY KEY (chat_id, agent_key)
);
CREATE TABLE IF NOT EXISTS subagent_runs (
    id                    TEXT PRIMARY KEY,
    chat_id               TEXT NOT NULL,
    tool_use_id           TEXT,
    prompt                TEXT,
    messages_json         TEXT,
    answer                TEXT,
    status                TEXT,
    input_tokens          INTEGER NOT NULL,
    output_tokens         INTEGER NOT NULL,
    cache_read_tokens     INTEGER NOT NULL,
    cache_creation_tokens INTEGER NOT NULL,
    created_at            INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_runs_chat ON subagent_runs(chat_id, created_at);
CREATE TABLE IF NOT EXISTS plugin_docs (
    plugin_id  TEXT NOT NULL,
    owner      TEXT NOT NULL,
    collection TEXT NOT NULL,
    doc_id     TEXT NOT NULL,
    doc        TEXT NOT NULL,
    PRIMARY KEY (plugin_id, owner, collection, doc_id)
);
-- Records the schema version; nothing reads it.
CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL PRIMARY KEY);
INSERT OR IGNORE INTO schema_version (version) VALUES (1);
