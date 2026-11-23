-- jllm-sql schema, PostgreSQL dialect (text columns compare case sensitive). Timestamps: epoch milliseconds (sessions) or epoch nanoseconds (chats).
CREATE TABLE roles (
    name      TEXT PRIMARY KEY,
    read_only INTEGER NOT NULL DEFAULT 0,
    approval  TEXT NOT NULL DEFAULT 'ask'
);
CREATE TABLE role_tools (
    role    TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
    ord     INTEGER NOT NULL,
    pattern TEXT NOT NULL,
    PRIMARY KEY (role, ord)
);
CREATE TABLE role_permissions (
    role       TEXT NOT NULL REFERENCES roles(name) ON DELETE CASCADE,
    ord        INTEGER NOT NULL,
    pattern    TEXT NOT NULL,
    permission TEXT NOT NULL,
    PRIMARY KEY (role, ord)
);
-- The fixed admin role always has its row: users reference roles (ON DELETE RESTRICT) and admin users need it.
INSERT INTO roles (name, read_only, approval) VALUES ('admin', 0, 'ask');
INSERT INTO role_tools (role, ord, pattern) VALUES ('admin', 0, '*');
CREATE TABLE users (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    role          TEXT NOT NULL REFERENCES roles(name) ON DELETE RESTRICT,
    password_hash TEXT,
    external_id   TEXT,
    email         TEXT
);
CREATE INDEX idx_users_external ON users(external_id);
CREATE INDEX idx_users_email ON users(email);
CREATE TABLE user_credentials (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source  TEXT NOT NULL,
    header  TEXT NOT NULL,
    value   TEXT NOT NULL,
    PRIMARY KEY (user_id, source, header)
);
CREATE TABLE user_disabled_tools (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ord     INTEGER NOT NULL,
    pattern TEXT NOT NULL,
    PRIMARY KEY (user_id, ord)
);
CREATE TABLE user_disabled_skills (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ord     INTEGER NOT NULL,
    name    TEXT NOT NULL,
    PRIMARY KEY (user_id, ord)
);
CREATE TABLE skills (
    owner_id    TEXT NOT NULL,
    name        TEXT NOT NULL,
    description TEXT NOT NULL,
    content     TEXT NOT NULL,
    PRIMARY KEY (owner_id, name)
);
CREATE TABLE login_sessions (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX idx_sessions_user ON login_sessions(user_id);
CREATE INDEX idx_sessions_expires ON login_sessions(expires_at);
CREATE TABLE oauth_tokens (
    principal_id TEXT NOT NULL,
    name         TEXT NOT NULL,
    payload      TEXT NOT NULL,
    version      BIGINT NOT NULL,
    PRIMARY KEY (principal_id, name)
);
CREATE TABLE settings_layer (
    kind   TEXT PRIMARY KEY,
    origin TEXT NOT NULL,
    doc    TEXT NOT NULL
);
CREATE TABLE mcp_trust (
    project_key TEXT NOT NULL,
    name        TEXT NOT NULL,
    sha256      TEXT NOT NULL,
    trusted     INTEGER NOT NULL,
    PRIMARY KEY (project_key, name)
);
CREATE TABLE chats (
    chat_id                 TEXT PRIMARY KEY,
    agent_id                TEXT,
    title                   TEXT,
    mode                    TEXT,
    tool_groups             TEXT,
    input_tokens            BIGINT NOT NULL,
    output_tokens           BIGINT NOT NULL,
    cache_read_tokens       BIGINT NOT NULL,
    cache_creation_tokens   BIGINT NOT NULL,
    created_at              BIGINT NOT NULL,
    updated_at              BIGINT NOT NULL,
    message_count           BIGINT NOT NULL,
    qualifier_input_tokens  BIGINT NOT NULL,
    qualifier_output_tokens BIGINT NOT NULL,
    principal               TEXT,
    chat_tools              TEXT,
    tainted_tool_uses       TEXT
);
CREATE INDEX idx_chats_updated ON chats(updated_at);
CREATE INDEX idx_chats_principal ON chats(principal, updated_at);
CREATE TABLE chat_log (
    chat_id  TEXT PRIMARY KEY,
    messages TEXT NOT NULL
);
CREATE TABLE chat_state (
    chat_id TEXT NOT NULL,
    name    TEXT NOT NULL,
    doc     TEXT NOT NULL,
    PRIMARY KEY (chat_id, name)
);
CREATE TABLE mnemonic_state (
    chat_id   TEXT NOT NULL,
    agent_key TEXT NOT NULL,
    doc       TEXT NOT NULL,
    PRIMARY KEY (chat_id, agent_key)
);
CREATE TABLE subagent_runs (
    id                    TEXT PRIMARY KEY,
    chat_id               TEXT NOT NULL,
    tool_use_id           TEXT,
    prompt                TEXT,
    messages_json         TEXT,
    answer                TEXT,
    status                TEXT,
    input_tokens          BIGINT NOT NULL,
    output_tokens         BIGINT NOT NULL,
    cache_read_tokens     BIGINT NOT NULL,
    cache_creation_tokens BIGINT NOT NULL,
    created_at            BIGINT NOT NULL
);
CREATE INDEX idx_runs_chat ON subagent_runs(chat_id, created_at);
CREATE TABLE plugin_docs (
    plugin_id  TEXT NOT NULL,
    owner      TEXT NOT NULL,
    collection TEXT NOT NULL,
    doc_id     TEXT NOT NULL,
    doc        TEXT NOT NULL,
    PRIMARY KEY (plugin_id, owner, collection, doc_id)
);
