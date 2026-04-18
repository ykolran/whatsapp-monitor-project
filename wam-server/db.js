const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

const dataDir = path.join(__dirname, 'data');
if (!fs.existsSync(dataDir)) fs.mkdirSync(dataDir, { recursive: true });

const db = new Database(path.join(dataDir, 'whatsapp_mirror.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id           TEXT PRIMARY KEY,
    username     TEXT NOT NULL UNIQUE,
    token        TEXT NOT NULL UNIQUE,
    created_at   INTEGER DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS conversations (
    id            TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    remote_id     TEXT NOT NULL,
    contact_name  TEXT NOT NULL,
    is_group      INTEGER DEFAULT 0,
    message_count INTEGER DEFAULT 0,
    created_at    INTEGER DEFAULT (unixepoch()),
    updated_at    INTEGER DEFAULT (unixepoch()),
    UNIQUE(user_id, remote_id)
  );

  CREATE TABLE IF NOT EXISTS messages (
    id                 TEXT PRIMARY KEY,
    conversation_id    TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id            TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender             TEXT NOT NULL,
    text               TEXT NOT NULL,
    timestamp          INTEGER NOT NULL,
    is_history         INTEGER DEFAULT 0,
    history_summary_id TEXT,
    received_at        INTEGER DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS summaries (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    text            TEXT NOT NULL,
    sentiment       TEXT,
    is_history      INTEGER DEFAULT 0,
    created_at      INTEGER DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS images (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id TEXT,
    filename        TEXT NOT NULL,
    path            TEXT NOT NULL,
    has_children    INTEGER DEFAULT 0,
    matched_names   TEXT,
    thumbnail_path  TEXT,
    received_at     INTEGER DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS enrolled_faces (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    child_name  TEXT NOT NULL,
    embedding   TEXT NOT NULL,
    created_at  INTEGER DEFAULT (unixepoch())
  );

  CREATE INDEX IF NOT EXISTS idx_messages_conv    ON messages(conversation_id, timestamp);
  CREATE INDEX IF NOT EXISTS idx_messages_history ON messages(conversation_id, is_history);
  CREATE INDEX IF NOT EXISTS idx_images_user      ON images(user_id, has_children);
  CREATE INDEX IF NOT EXISTS idx_conv_user        ON conversations(user_id, updated_at);
`);

module.exports = db;
