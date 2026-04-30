'use strict';

const sqlite3 = require('sqlite3').verbose();
const path    = require('path');

const db = new sqlite3.Database(path.join(__dirname, 'content-cache.db'));

db.serialize(() => {
  db.run(`
    CREATE TABLE IF NOT EXISTS cached_content (
      tmdb_id        INTEGER NOT NULL,
      tmdb_type      TEXT    NOT NULL,
      title          TEXT    NOT NULL,
      original_title TEXT,
      overview       TEXT,
      poster_url     TEXT,
      backdrop_url   TEXT,
      year           TEXT,
      genres         TEXT,
      vote_average   REAL,
      vote_count     INTEGER,
      is_anime       INTEGER NOT NULL DEFAULT 0,
      scraper_href   TEXT,
      scraper_image  TEXT,
      is_streamable  INTEGER NOT NULL DEFAULT 0,
      last_verified  TEXT    NOT NULL,
      age_rating     TEXT,
      keywords       TEXT,
      PRIMARY KEY (tmdb_id, tmdb_type)
    )
  `);

  db.run(`
    CREATE TABLE IF NOT EXISTS content_categories (
      category   TEXT    NOT NULL,
      tmdb_id    INTEGER NOT NULL,
      tmdb_type  TEXT    NOT NULL,
      position   INTEGER NOT NULL,
      updated_at TEXT    NOT NULL,
      PRIMARY KEY (category, tmdb_id, tmdb_type)
    )
  `);

  db.run(`CREATE INDEX IF NOT EXISTS idx_cat_category ON content_categories (category)`);
  db.run(`CREATE INDEX IF NOT EXISTS idx_content_stream ON cached_content (is_streamable, tmdb_type)`);

  // Migrate existing databases that were created before these columns existed.
  // SQLite errors if the column is already present — the empty callback swallows it.
  db.run(`ALTER TABLE cached_content ADD COLUMN age_rating TEXT`, () => {});
  db.run(`ALTER TABLE cached_content ADD COLUMN keywords TEXT`, () => {});
});

const run = (sql, params = []) =>
  new Promise((resolve, reject) =>
    db.run(sql, params, function (err) { err ? reject(err) : resolve(this); })
  );

const get = (sql, params = []) =>
  new Promise((resolve, reject) =>
    db.get(sql, params, (err, row) => err ? reject(err) : resolve(row || null))
  );

const all = (sql, params = []) =>
  new Promise((resolve, reject) =>
    db.all(sql, params, (err, rows) => err ? reject(err) : resolve(rows || []))
  );

module.exports = { run, get, all };
