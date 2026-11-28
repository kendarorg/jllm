-- jllm-sql schema, MySQL 8+ dialect (utf8mb4, binary collation: patterns, headers and paths are case sensitive).
CREATE TABLE roles (
    name      VARCHAR(64) NOT NULL PRIMARY KEY,
    read_only TINYINT(1) NOT NULL DEFAULT 0,
    approval  VARCHAR(16) NOT NULL DEFAULT 'ask',
    settings TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE role_tools (
    role    VARCHAR(64) NOT NULL,
    ord     INT NOT NULL,
    pattern VARCHAR(512) NOT NULL,
    PRIMARY KEY (role, ord),
    FOREIGN KEY (role) REFERENCES roles(name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE role_permissions (
    role       VARCHAR(64) NOT NULL,
    ord        INT NOT NULL,
    pattern    VARCHAR(512) NOT NULL,
    permission VARCHAR(16) NOT NULL,
    PRIMARY KEY (role, ord),
    FOREIGN KEY (role) REFERENCES roles(name) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- The fixed admin role always has its row: users reference roles (ON DELETE RESTRICT) and admin users need it.
INSERT INTO roles (name, read_only, approval) VALUES ('admin', 0, 'ask');
INSERT INTO role_tools (role, ord, pattern) VALUES ('admin', 0, '*');
CREATE TABLE users (
    id            VARCHAR(64) NOT NULL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    role          VARCHAR(64) NOT NULL,
    password_hash VARCHAR(512) NULL,
    external_id   VARCHAR(512) NULL,
    email         VARCHAR(320) NULL,
    settings      TEXT NULL,
    INDEX idx_users_external (external_id),
    INDEX idx_users_email (email),
    FOREIGN KEY (role) REFERENCES roles(name) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE user_credentials (
    user_id VARCHAR(64) NOT NULL,
    source  VARCHAR(255) NOT NULL,
    header  VARCHAR(255) NOT NULL,
    value   VARCHAR(1024) NOT NULL,
    PRIMARY KEY (user_id, source, header),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE user_disabled_tools (
    user_id VARCHAR(64) NOT NULL,
    ord     INT NOT NULL,
    pattern VARCHAR(512) NOT NULL,
    PRIMARY KEY (user_id, ord),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE user_disabled_skills (
    user_id VARCHAR(64) NOT NULL,
    ord     INT NOT NULL,
    name    VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id, ord),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE skills (
    owner_id    VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    description TEXT NOT NULL,
    content     MEDIUMTEXT NOT NULL,
    PRIMARY KEY (owner_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE login_sessions (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL,
    not_after  BIGINT NULL,
    INDEX idx_sessions_user (user_id),
    INDEX idx_sessions_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE oauth_tokens (
    principal_id VARCHAR(64) NOT NULL,
    name         VARCHAR(255) NOT NULL,
    payload      MEDIUMTEXT NOT NULL,
    version      BIGINT NOT NULL,
    PRIMARY KEY (principal_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE settings_layer (
    kind   VARCHAR(16) NOT NULL PRIMARY KEY,
    origin VARCHAR(1024) NOT NULL,
    doc    MEDIUMTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE settings_section (
    name VARCHAR(128) NOT NULL PRIMARY KEY,
    doc  MEDIUMTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE instructions (
    scope    VARCHAR(16) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    name     VARCHAR(64) NOT NULL,
    content  MEDIUMTEXT NOT NULL,
    PRIMARY KEY (scope, owner_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agents (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    doc  MEDIUMTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE chats (
    chat_id                 VARCHAR(255) NOT NULL PRIMARY KEY,
    agent_id                VARCHAR(255) NULL,
    title                   TEXT NULL,
    mode                    VARCHAR(64) NULL,
    tool_groups             TEXT NULL,
    input_tokens            BIGINT NOT NULL,
    output_tokens           BIGINT NOT NULL,
    cache_read_tokens       BIGINT NOT NULL,
    cache_creation_tokens   BIGINT NOT NULL,
    created_at              BIGINT NOT NULL,
    updated_at              BIGINT NOT NULL,
    message_count           INT NOT NULL,
    qualifier_input_tokens  BIGINT NOT NULL,
    qualifier_output_tokens BIGINT NOT NULL,
    principal               VARCHAR(255) NULL,
    chat_tools              MEDIUMTEXT NULL,
    tainted_tool_uses       MEDIUMTEXT NULL,
    INDEX idx_chats_updated (updated_at),
    INDEX idx_chats_principal (principal, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE chat_log (
    chat_id  VARCHAR(255) NOT NULL PRIMARY KEY,
    messages LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE chat_state (
    chat_id VARCHAR(255) NOT NULL,
    name    VARCHAR(128) NOT NULL,
    doc     MEDIUMTEXT NOT NULL,
    PRIMARY KEY (chat_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE mnemonic_state (
    chat_id   VARCHAR(255) NOT NULL,
    agent_key VARCHAR(128) NOT NULL,
    doc       LONGTEXT NOT NULL,
    PRIMARY KEY (chat_id, agent_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE subagent_runs (
    id                    VARCHAR(128) NOT NULL PRIMARY KEY,
    chat_id               VARCHAR(255) NOT NULL,
    tool_use_id           VARCHAR(255) NULL,
    prompt                MEDIUMTEXT NULL,
    messages_json         LONGTEXT NULL,
    answer                MEDIUMTEXT NULL,
    status                VARCHAR(32) NULL,
    input_tokens          BIGINT NOT NULL,
    output_tokens         BIGINT NOT NULL,
    cache_read_tokens     BIGINT NOT NULL,
    cache_creation_tokens BIGINT NOT NULL,
    created_at            BIGINT NOT NULL,
    INDEX idx_runs_chat (chat_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE plugin_docs (
    plugin_id  VARCHAR(64) NOT NULL,
    owner      VARCHAR(128) NOT NULL,
    collection VARCHAR(64) NOT NULL,
    doc_id     VARCHAR(255) NOT NULL,
    doc        MEDIUMTEXT NOT NULL,
    PRIMARY KEY (plugin_id, owner, collection, doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- The schema version (Schema.VERSION), written by the code; upgrades run the missing steps.
CREATE TABLE schema_version (version INT NOT NULL PRIMARY KEY);
