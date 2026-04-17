const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

const dataDir = path.join(__dirname, 'data');
if (!fs.existsSync(dataDir)) fs.mkdirSync(dataDir, { recursive: true });

const db = new Database(path.join(dataDir, 'whatsapp_mirror.db'));

db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
  CREATE TABLE IF NOT EXISTS conversations (
    id            TEXT PRIMARY KEY,
    contact_name  TEXT NOT NULL,
    is_group      INTEGER DEFAULT 0,
    message_count INTEGER DEFAULT 0,
    created_at    INTEGER DEFAULT (unixepoch()),
    updated_at    INTEGER DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS messages (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(id),
    sender          TEXT NOT NULL,
    text            TEXT NOT NULL,
    timestamp       INTEGER NOT NULL,
    received_at     INTEGER DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS summaries (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(id),
    text            TEXT NOT NULL,
    intent          TEXT,
    sentiment       TEXT,
    created_at      INTEGER DEFAULT (unixepoch())
  );

  CREATE TABLE IF NOT EXISTS images (
    id              TEXT PRIMARY KEY,
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
    child_name  TEXT NOT NULL,
    embedding   TEXT NOT NULL,
    created_at  INTEGER DEFAULT (unixepoch())
  );

  CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, timestamp);
  CREATE INDEX IF NOT EXISTS idx_images_children ON images(has_children);
`);

module.exports = db;
