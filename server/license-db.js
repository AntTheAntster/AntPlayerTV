const sqlite3 = require("sqlite3").verbose();
const path = require("path");

const db = new sqlite3.Database(
  path.join(__dirname, "license.db")
);

db.serialize(() => {
  db.run(`
    CREATE TABLE IF NOT EXISTS licenses (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      license_key TEXT UNIQUE NOT NULL,
      user_email TEXT,
      status TEXT NOT NULL DEFAULT 'active', -- 'active', 'revoked'
      device_id TEXT,                        -- bound after first use
      created_at TEXT NOT NULL,
      expires_at TEXT                        -- nullable, ISO string
    )
  `);
  db.run(`
    CREATE TABLE IF NOT EXISTS scraper_match_cache (
      cache_key  TEXT PRIMARY KEY,  -- e.g. "tv:1234:s1" or "movie:5678"
      scraper_href  TEXT NOT NULL,
      scraper_image TEXT NOT NULL DEFAULT '',
      updated_at TEXT NOT NULL
    )
  `);
  db.run(
  `CREATE TABLE IF NOT EXISTS play_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    license_key TEXT,
    device_id TEXT,
    title TEXT,
    episode_label TEXT,
    watch_type TEXT,
    created_at TEXT
  )`,
  (err) => {
    if (err) {
      console.error("Failed to create play_events table:", err);
    } else {
      console.log("play_events table ready");
    }
  }
);

});

module.exports = db;
