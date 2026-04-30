const express = require("express");
const bodyParser = require("body-parser");
const basicAuth = require("express-basic-auth");
const db = require("./license-db");
const PORT = process.env.PORT || 3000;
const path = require("path")
const fs = require("fs");
const multer = require("multer");
const app = express();

const VERSION_FILE = path.join(__dirname, "app-version.json");
const DOWNLOADS_DIR = path.join(__dirname, "downloads");

function readVersionConfig() {
  try {
    const raw = fs.readFileSync(VERSION_FILE, "utf8");
    return JSON.parse(raw);
  } catch (e) {
    console.error("Failed to read version file:", e);
    // default fallback
    return {
      versionCode: 1,
      versionName: "1.0.0",
      apkFilename: "",
      changelog: ""
    };
  }
}

function writeVersionConfig(cfg) {
  fs.writeFileSync(VERSION_FILE, JSON.stringify(cfg, null, 2), "utf8");
}

function listApkFiles() {
  try {
    const files = fs.readdirSync(DOWNLOADS_DIR);
    return files.filter((f) => f.toLowerCase().endsWith(".apk"));
  } catch (e) {
    console.error("Failed to list APKs:", e);
    return [];
  }
}

function normalizeWatchType(t) {
  if (!t) return "unknown";
  const s = String(t).toLowerCase();
  if (s.includes("dub")) return "dub";
  if (s.includes("sub")) return "sub";
  if (s.includes("raw") || s.includes("original")) return "raw";
  return s;
}

app.use(bodyParser.json());
app.use(bodyParser.urlencoded({ extended: true }));

app.use(
  "/downloads",
  express.static(path.join(__dirname, "downloads"))
);

// ===== PUBLIC API: License check =====

app.post("/api/license/check", (req, res) => {
  const { licenseKey, deviceId, appVersion } = req.body || {};

  if (!licenseKey || !deviceId) {
    return res.status(400).json({
      valid: false,
      status: "error",
      message: "Missing licenseKey or deviceId"
    });
  }

  db.get(
    "SELECT * FROM licenses WHERE license_key = ?",
    [licenseKey],
    (err, row) => {
      if (err) {
        console.error("DB error:", err);
        return res.status(500).json({
          valid: false,
          status: "error",
          message: "Server error"
        });
      }

      if (!row) {
        return res.json({
          valid: false,
          status: "not_found",
          message: "License not found"
        });
      }

      if (row.status === "revoked") {
        return res.json({
          valid: false,
          status: "revoked",
          message: "License revoked"
        });
      }

      // Expiry check
      if (row.expires_at) {
        const now = new Date();
        const expires = new Date(row.expires_at);
        if (now > expires) {
          return res.json({
            valid: false,
            status: "expired",
            message: "License expired"
          });
        }
      }

      // Bind or verify device
      if (!row.device_id) {
        // First device to use this key → bind it
        db.run(
          "UPDATE licenses SET device_id = ? WHERE id = ?",
          [deviceId, row.id],
          (err2) => {
            if (err2) {
              console.error("DB error binding device:", err2);
            }
          }
        );
      } else if (row.device_id !== deviceId) {
        return res.json({
          valid: false,
          status: "device_mismatch",
          message: "License already bound to another device"
        });
      }

      // All good
      return res.json({
        valid: true,
        status: "ok",
        message: "License valid",
        expiresAt: row.expires_at
      });
    }
  );
});

// Log a playback start event (called by the app when it starts playing)
app.post("/api/analytics/play", (req, res) => {
  const body = req.body || {};
  const licenseKey = body.licenseKey || null;
  const deviceId = body.deviceId || null;
  const title = body.title || "";
  const episodeLabel = body.episodeLabel || "";
  const watchType = normalizeWatchType(body.watchType || "");
  const createdAt = new Date().toISOString();

  db.run(
    `INSERT INTO play_events
      (license_key, device_id, title, episode_label, watch_type, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
    [licenseKey, deviceId, title, episodeLabel, watchType, createdAt],
    (err) => {
      if (err) {
        console.error("Failed to insert play_event:", err);
        return res.status(500).json({ error: "DB error" });
      }
      res.json({ success: true });
    }
  );
});

// Admin GUI Stuff
const ADMIN_USER = "admin";
const ADMIN_PASS = "ZEQMPXEF1";

app.use(
  "/admin",
  basicAuth({
    users: { [ADMIN_USER]: ADMIN_PASS },
    challenge: true,
  })
);

// Serve admin static files
app.use(
  "/admin",
  express.static(path.join(__dirname, "admin"))
);

// Admin JSON API (protected by basicAuth on /admin-api)
app.use(
  "/admin-api",
  basicAuth({
    users: { [ADMIN_USER]: ADMIN_PASS },
    challenge: true,
  })
);

// List / search licenses
app.get("/admin-api/licenses", (req, res) => {
  const q = (req.query.q || "").trim();
  const params = [];
  let sql = "SELECT * FROM licenses";

  if (q) {
    sql += " WHERE user_email LIKE ? OR license_key LIKE ?";
    params.push(`%${q}%`, `%${q}%`);
  }

  sql += " ORDER BY created_at DESC";

  db.all(sql, params, (err, rows) => {
    if (err) {
      console.error("DB error:", err);
      return res.status(500).json({ error: "DB error" });
    }
    res.json({ licenses: rows });
  });
});

// Create license
app.post("/admin-api/licenses/create", (req, res) => {
  const email = req.body.email || null;
  const days = parseInt(req.body.days, 10);
  const now = new Date();
  const createdAt = now.toISOString();
  let expiresAt = null;
  if (!isNaN(days) && days > 0) {
    const exp = new Date(now.getTime() + days * 24 * 60 * 60 * 1000);
    expiresAt = exp.toISOString();
  }

  const key =
    Math.random().toString(36).slice(2, 10).toUpperCase() +
    "-" +
    Math.random().toString(36).slice(2, 10).toUpperCase();

  db.run(
    "INSERT INTO licenses (license_key, user_email, status, device_id, created_at, expires_at) VALUES (?, ?, 'active', NULL, ?, ?)",
    [key, email, createdAt, expiresAt],
    function (err) {
      if (err) {
        console.error("DB insert error:", err);
        return res.status(500).json({ error: "DB error" });
      }
      res.json({
        success: true,
        id: this.lastID,
        license_key: key,
      });
    }
  );
});

// Revoke license
app.post("/admin-api/licenses/revoke", (req, res) => {
  const id = parseInt(req.body.id, 10);
  if (!id) return res.status(400).json({ error: "Missing id" });

  db.run("UPDATE licenses SET status = 'revoked' WHERE id = ?", [id], (err) => {
    if (err) {
      console.error("DB revoke error:", err);
      return res.status(500).json({ error: "DB error" });
    }
    res.json({ success: true });
  });
});

// Extend license expiry
app.post("/admin-api/licenses/extend", (req, res) => {
  const id = parseInt(req.body.id, 10);
  const days = parseInt(req.body.days, 10);
  if (!id || isNaN(days) || days <= 0) {
    return res.status(400).json({ error: "Invalid id or days" });
  }

  db.get("SELECT expires_at FROM licenses WHERE id = ?", [id], (err, row) => {
    if (err) {
      console.error("DB error:", err);
      return res.status(500).json({ error: "DB error" });
    }

    const now = new Date();
    let base = now;
    if (row && row.expires_at) {
      const existing = new Date(row.expires_at);
      if (!isNaN(existing.getTime()) && existing > now) {
        base = existing;
      }
    }

    const newExpires = new Date(
      base.getTime() + days * 24 * 60 * 60 * 1000
    ).toISOString();

    db.run(
      "UPDATE licenses SET expires_at = ? WHERE id = ?",
      [newExpires, id],
      (err2) => {
        if (err2) {
          console.error("DB error updating expiry:", err2);
          return res.status(500).json({ error: "DB error" });
        }
        res.json({ success: true, expires_at: newExpires });
      }
    );
  });
});

// Reset device binding
app.post("/admin-api/licenses/reset-device", (req, res) => {
  const id = parseInt(req.body.id, 10);
  if (!id) return res.status(400).json({ error: "Missing id" });

  db.run("UPDATE licenses SET device_id = NULL WHERE id = ?", [id], (err) => {
    if (err) {
      console.error("DB reset device error:", err);
      return res.status(500).json({ error: "DB error" });
    }
    res.json({ success: true });
  });
});

// Reset license key (generate a new one, keep same row)
app.post("/admin-api/licenses/reset-key", (req, res) => {
  const id = parseInt(req.body.id, 10);
  if (!id) return res.status(400).json({ error: "Missing id" });

  const newKey =
    Math.random().toString(36).slice(2, 10).toUpperCase() +
    "-" +
    Math.random().toString(36).slice(2, 10).toUpperCase();

  db.run(
    "UPDATE licenses SET license_key = ?, device_id = NULL WHERE id = ?",
    [newKey, id],
    (err) => {
      if (err) {
        console.error("DB reset key error:", err);
        return res.status(500).json({ error: "DB error" });
      }
      res.json({ success: true, license_key: newKey });
    }
  );
});

// Delete license row completely
app.post("/admin-api/licenses/delete", (req, res) => {
  const id = parseInt(req.body.id, 10);
  if (!id) {
    return res.status(400).json({ error: "Missing id" });
  }

  db.run("DELETE FROM licenses WHERE id = ?", [id], (err) => {
    if (err) {
      console.error("DB delete error:", err);
      return res.status(500).json({ error: "DB error" });
    }
    res.json({ success: true });
  });
});

// Simple license analytics
app.get("/admin-api/analytics/licenses", (req, res) => {
  const result = {
    totals: {},
    createdPerDay: []
  };

  // We’ll run a few queries in series
  db.get("SELECT COUNT(*) AS c FROM licenses", [], (err, rowTotal) => {
    if (err) {
      console.error("Analytics total error:", err);
      return res.status(500).json({ error: "DB error" });
    }
    result.totals.total = rowTotal.c;

    db.get("SELECT COUNT(*) AS c FROM licenses WHERE status = 'active'", [], (err2, rowActive) => {
      if (err2) {
        console.error("Analytics active error:", err2);
        return res.status(500).json({ error: "DB error" });
      }
      result.totals.active = rowActive.c;

      db.get("SELECT COUNT(*) AS c FROM licenses WHERE status = 'revoked'", [], (err3, rowRevoked) => {
        if (err3) {
          console.error("Analytics revoked error:", err3);
          return res.status(500).json({ error: "DB error" });
        }
        result.totals.revoked = rowRevoked.c;

        db.get("SELECT COUNT(*) AS c FROM licenses WHERE device_id IS NOT NULL", [], (err4, rowBound) => {
          if (err4) {
            console.error("Analytics bound error:", err4);
            return res.status(500).json({ error: "DB error" });
          }
          result.totals.boundDevices = rowBound.c;

          // created per day (last 30 days)
          const sqlCreatedPerDay = `
            SELECT substr(created_at, 1, 10) AS day, COUNT(*) AS c
            FROM licenses
            GROUP BY day
            ORDER BY day DESC
            LIMIT 30
          `;
          db.all(sqlCreatedPerDay, [], (err5, rows) => {
            if (err5) {
              console.error("Analytics createdPerDay error:", err5);
              return res.status(500).json({ error: "DB error" });
            }
            result.createdPerDay = rows || [];
            res.json(result);
          });
        });
      });
    });
  });
});

// Playback analytics
app.get("/admin-api/analytics/plays", (req, res) => {
  const result = {
    totals: {},
    playsPerDay: [],
    topTitles: [],
    watchTypes: []
  };

  // total plays
  db.get("SELECT COUNT(*) AS c FROM play_events", [], (err, rowTotal) => {
    if (err) {
      console.error("Analytics plays total error:", err);
      return res.status(500).json({ error: "DB error" });
    }
    result.totals.totalPlays = rowTotal.c;

    // plays per day (last 30 days)
    const sqlPerDay = `
      SELECT substr(created_at, 1, 10) AS day, COUNT(*) AS c
      FROM play_events
      GROUP BY day
      ORDER BY day DESC
      LIMIT 30
    `;
    db.all(sqlPerDay, [], (err2, rowsPerDay) => {
      if (err2) {
        console.error("Analytics plays per day error:", err2);
        return res.status(500).json({ error: "DB error" });
      }
      result.playsPerDay = rowsPerDay || [];

      // top titles
      const sqlTopTitles = `
        SELECT title, COUNT(*) AS c
        FROM play_events
        GROUP BY title
        ORDER BY c DESC
        LIMIT 10
      `;
      db.all(sqlTopTitles, [], (err3, rowsTitles) => {
        if (err3) {
          console.error("Analytics top titles error:", err3);
          return res.status(500).json({ error: "DB error" });
        }
        result.topTitles = rowsTitles || [];

        // watch type breakdown
        const sqlWatchTypes = `
          SELECT watch_type, COUNT(*) AS c
          FROM play_events
          GROUP BY watch_type
          ORDER BY c DESC
        `;
        db.all(sqlWatchTypes, [], (err4, rowsTypes) => {
          if (err4) {
            console.error("Analytics watch types error:", err4);
            return res.status(500).json({ error: "DB error" });
          }
          result.watchTypes = rowsTypes || [];
          res.json(result);
        });
      });
    });
  });
});

app.get("/admin-api/version", (req, res) => {
  const cfg = readVersionConfig();
  const apkFiles = listApkFiles();
  res.json({
    version: cfg,
    apkFiles
  });
});

app.post("/admin-api/version", (req, res) => {
  const body = req.body || {};
  const versionCode = parseInt(body.versionCode, 10);
  const versionName = body.versionName || "";
  const apkFilename = body.apkFilename || "";
  const changelog = body.changelog || "";

  if (isNaN(versionCode) || versionCode <= 0) {
    return res.status(400).json({ error: "Invalid versionCode" });
  }
  if (!versionName) {
    return res.status(400).json({ error: "versionName required" });
  }

  const cfg = {
    versionCode,
    versionName,
    apkFilename,
    changelog
  };

  try {
    writeVersionConfig(cfg);
    res.json({ success: true, version: cfg });
  } catch (e) {
    console.error("Failed to write version file:", e);
    res.status(500).json({ error: "Failed to save version config" });
  }
});

app.get("/admin-api/apks", (req, res) => {
  res.json({ files: listApkFiles() });
});

// ===== Admin: scanner controls & cache browser =====

app.get("/admin-api/scanner/status", async (_req, res) => {
  try {
    res.json(await contentCache.getScanStatus());
  } catch (err) {
    console.error("scanner/status error:", err);
    res.status(500).json({ error: "status_failed" });
  }
});

app.post("/admin-api/scanner/pause", (_req, res) => {
  contentCache.setScannerPaused(true);
  res.json({ paused: true });
});

app.post("/admin-api/scanner/resume", (_req, res) => {
  contentCache.setScannerPaused(false);
  res.json({ paused: false });
});

// GET /admin-api/cache/list?query=foo&type=movie&page=1&limit=50
app.get("/admin-api/cache/list", async (req, res) => {
  const limit = Math.min(parseInt(req.query.limit, 10) || 50, 200);
  const page  = Math.max(parseInt(req.query.page, 10) || 1, 1);
  try {
    const data = await contentCache.listCached({
      query:          (req.query.query || "").trim() || null,
      streamableOnly: req.query.all !== "1",
      type:           (req.query.type  || "").trim() || null,
      page,
      limit,
    });
    res.json(data);
  } catch (err) {
    console.error("cache/list error:", err);
    res.status(500).json({ error: "list_failed" });
  }
});

const upload = multer({
  dest: DOWNLOADS_DIR
});

app.post("/admin-api/upload-apk", upload.single("apk"), (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: "No file uploaded" });
  }

  const originalName = req.file.originalname || "app.apk";
  const safeName = originalName.replace(/[^a-zA-Z0-9_.-]/g, "_");
  const targetPath = path.join(DOWNLOADS_DIR, safeName);

  fs.rename(req.file.path, targetPath, (err) => {
    if (err) {
      console.error("Failed to move uploaded file:", err);
      return res.status(500).json({ error: "Failed to store APK" });
    }

    res.json({
      success: true,
      filename: safeName
    });
  });
});

// Import functions from ashi.js
const {
  searchResults,
  extractStreamUrl,
  extractDetails,
  extractEpisodes
} = require("./ashi");

// TMDB proxy + cache (used for v2.0 search & details).
const tmdb = require("./tmdb");

// Verified-streamable browse cache (popular, trending, genres, etc.).
const contentCache = require("./content-cache");

// Allow JSON responses
app.use(express.json());

/**
 * 1) Search endpoint
 *    Call:  GET /search?query=naruto
 */
app.get("/search", async (req, res) => {
  const query = req.query.query || "";
  if (!query.trim()) {
    return res.status(400).json({ error: "Missing ?query=" });
  }

  try {
    // searchResults returns a JSON STRING, so we parse it
    const jsonString = await searchResults(query);
    const data = JSON.parse(jsonString);
    res.json(data);
  } catch (err) {
    console.error("search error:", err);
    res.status(500).json({ error: "search_failed" });
  }
});

// GET /details?url=Animekai:https://animekai.to/anime/...
app.get("/details", async (req, res) => {
  const url = req.query.url || "";
  if (!url.trim()) {
    return res.status(400).json({ error: "Missing ?url=" });
  }

  try {
    const jsonString = await extractDetails(url);
    // extractDetails returns a JSON string of an array with one object
    // [{ description, aliases, airdate }]
    const arr = JSON.parse(jsonString);
    const first = arr[0] || {};
    res.json(first);
  } catch (err) {
    console.error("details error:", err);
    res.json({
      description: "Unknown",
      aliases: "Unknown",
      airdate: "Unknown"
    });
  }
});

// GET /episodes?url=Animekai:https://animekai.to/anime/...
app.get("/episodes", async (req, res) => {
  const url = req.query.url || "";
  if (!url.trim()) {
    return res.status(400).json({ error: "Missing ?url=" });
  }

  try {
    const jsonString = await extractEpisodes(url);
    // extractEpisodes returns JSON string of [{ number, href, ... }]
    const arr = JSON.parse(jsonString);
    res.json(arr);
  } catch (err) {
    console.error("episodes error:", err);
    res.json([]);
  }
});


/**
 * 2) Stream URL endpoint
 *    Call: GET /stream?url=...
 *    url is the href returned by /search.
 */
app.get("/stream", async (req, res) => {
  const url = req.query.url || "";
  if (!url.trim()) {
    return res.status(400).json({ error: "Missing ?url=" });
  }

  try {
    const result = await extractStreamUrl(url);
    let data = result;

    // extractStreamUrl returns a JSON STRING for Animekai and 1Movies,
    // so if it's a string we need to handle three cases:
    //   1) A failure sentinel ("https://error.org" / "error") that the
    //      scraper returns when its underlying fetch chain blew up. We
    //      pass that through as a clean 502+JSON so the client surfaces
    //      "no playable stream" rather than a bare 500.
    //   2) Valid JSON we can parse and forward.
    //   3) Anything else — log & 502.
    if (typeof result === "string") {
      const trimmed = result.trim();
      if (
        trimmed === "https://error.org" ||
        trimmed === "error" ||
        trimmed === ""
      ) {
        return res.status(502).json({
          error: "stream_unavailable",
          message: "Source could not produce a stream for this title."
        });
      }
      try {
        data = JSON.parse(trimmed);
      } catch (e) {
        console.error("Failed to parse stream JSON:", e, "result:", trimmed);
        // Same shape as above so the client doesn't have to special-case
        // parse-failures vs explicit error sentinels.
        return res.status(502).json({
          error: "stream_unparseable",
          message: "Source returned a malformed response."
        });
      }
    }

    return res.json(data);
  } catch (err) {
    console.error("stream error:", err);
    return res.status(500).json({ error: "stream_failed" });
  }
});


// ===== TMDB-driven v2.0 endpoints =====
//
// These power the new metadata-first browse/details flow on the client.
// Streams are still resolved through the /search + /stream scraper path
// above; TMDB only drives discovery.

app.get("/tmdb/search", async (req, res) => {
  const query = (req.query.query || "").trim();
  if (!query) {
    return res.status(400).json({ error: "Missing ?query=" });
  }
  try {
    const data = await tmdb.searchTitles(query);
    res.json(data);
  } catch (err) {
    console.error("tmdb search error:", err);
    res.status(500).json({ error: "tmdb_search_failed", message: err.message });
  }
});

// type = "tv" or "movie"
app.get("/tmdb/title/:type/:id", async (req, res) => {
  const { type, id } = req.params;
  try {
    const data = await tmdb.getTitle(type, id);
    res.json(data);
  } catch (err) {
    console.error("tmdb title error:", err);
    res.status(500).json({ error: "tmdb_title_failed", message: err.message });
  }
});

app.get("/tmdb/season/:id/:season", async (req, res) => {
  const { id, season } = req.params;
  try {
    const data = await tmdb.getSeason(id, season);
    res.json(data);
  } catch (err) {
    console.error("tmdb season error:", err);
    res.status(500).json({ error: "tmdb_season_failed", message: err.message });
  }
});

// ===== Scraper match cache =====
//
// Shared across all users: once any client resolves a TMDB ID to a
// scraper series URL the result is stored here so every subsequent
// play (by any user) skips the fuzzy search entirely.
//
// Keys follow the convention used by TmdbStreamBridge:
//   "movie:<tmdbId>"       – movies
//   "tv:<tmdbId>:s<season>" – TV seasons (different pages per season on
//                             Animekai; same page, different episodes on
//                             1Movies — either way works)

app.get("/api/match-cache", (req, res) => {
  const key = (req.query.key || "").trim();
  if (!key) return res.status(400).json({ error: "Missing ?key=" });

  db.get(
    "SELECT scraper_href, scraper_image FROM scraper_match_cache WHERE cache_key = ?",
    [key],
    (err, row) => {
      if (err) {
        console.error("match-cache GET error:", err);
        return res.status(500).json({ error: "db_error" });
      }
      if (!row) return res.status(404).json({ error: "not_found" });
      res.json({ href: row.scraper_href, image: row.scraper_image });
    }
  );
});

app.post("/api/match-cache", (req, res) => {
  const { key, href, image } = req.body || {};
  if (!key || !href) return res.status(400).json({ error: "Missing key or href" });

  const now = new Date().toISOString();
  db.run(
    `INSERT INTO scraper_match_cache (cache_key, scraper_href, scraper_image, updated_at)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(cache_key) DO UPDATE SET
       scraper_href  = excluded.scraper_href,
       scraper_image = excluded.scraper_image,
       updated_at    = excluded.updated_at`,
    [key, href, image || "", now],
    (err) => {
      if (err) {
        console.error("match-cache POST error:", err);
        return res.status(500).json({ error: "db_error" });
      }
      res.json({ success: true });
    }
  );
});

app.delete("/api/match-cache", (req, res) => {
  const key = (req.query.key || "").trim();
  if (!key) return res.status(400).json({ error: "Missing ?key=" });

  db.run(
    "DELETE FROM scraper_match_cache WHERE cache_key = ?",
    [key],
    (err) => {
      if (err) {
        console.error("match-cache DELETE error:", err);
        return res.status(500).json({ error: "db_error" });
      }
      res.json({ success: true });
    }
  );
});

// ===== Verified-streamable browse API =====
//
// All endpoints return only titles that have been confirmed playable on the
// scraper.  Category positions follow TMDB's own ranking (popularity, trending
// score, etc.).  Pagination uses ?page= (1-based) and an optional ?limit=
// (default 20, max 100).

function browseParams(req) {
  const limit  = Math.min(parseInt(req.query.limit  || "20", 10), 100);
  const page   = Math.max(parseInt(req.query.page   || "1",  10), 1);
  const offset = (page - 1) * limit;
  const type   = (req.query.type || "").toLowerCase();
  return { limit, offset, type: type === "movie" || type === "tv" ? type : null };
}

// GET /api/browse/popular?type=movie|tv&page=1&limit=20
app.get("/api/browse/popular", async (req, res) => {
  const { limit, offset, type } = browseParams(req);
  try {
    const category = type === "tv" ? "popular_tv" : "popular_movie";
    res.json(await contentCache.getCategory(category, limit, offset));
  } catch (err) {
    console.error("browse/popular error:", err);
    res.status(500).json({ error: "browse_failed" });
  }
});

// GET /api/browse/trending?type=movie|tv&page=1&limit=20
app.get("/api/browse/trending", async (req, res) => {
  const { limit, offset, type } = browseParams(req);
  try {
    const category = type === "tv" ? "trending_tv" : "trending_movie";
    res.json(await contentCache.getCategory(category, limit, offset));
  } catch (err) {
    console.error("browse/trending error:", err);
    res.status(500).json({ error: "browse_failed" });
  }
});

// GET /api/browse/new?type=movie|tv&page=1&limit=20
app.get("/api/browse/new", async (req, res) => {
  const { limit, offset, type } = browseParams(req);
  try {
    const category = type === "tv" ? "new_tv" : "new_movie";
    res.json(await contentCache.getCategory(category, limit, offset));
  } catch (err) {
    console.error("browse/new error:", err);
    res.status(500).json({ error: "browse_failed" });
  }
});

// GET /api/browse/top-rated?type=movie|tv&page=1&limit=20
app.get("/api/browse/top-rated", async (req, res) => {
  const { limit, offset, type } = browseParams(req);
  try {
    const category = type === "tv" ? "top_rated_tv" : "top_rated_movie";
    res.json(await contentCache.getCategory(category, limit, offset));
  } catch (err) {
    console.error("browse/top-rated error:", err);
    res.status(500).json({ error: "browse_failed" });
  }
});

// GET /api/browse/genre?name=Action&type=movie|tv&page=1&limit=20
app.get("/api/browse/genre", async (req, res) => {
  const { limit, offset, type } = browseParams(req);
  const genre = (req.query.name || "").trim();
  if (!genre) return res.status(400).json({ error: "Missing ?name=" });
  try {
    res.json(await contentCache.getByGenre(genre, type, limit, offset));
  } catch (err) {
    console.error("browse/genre error:", err);
    res.status(500).json({ error: "browse_failed" });
  }
});

// GET /api/browse/genres?type=movie|tv  — list of all genre names with content
app.get("/api/browse/genres", async (req, res) => {
  const type = (req.query.type || "").toLowerCase();
  const validType = type === "movie" || type === "tv" ? type : null;
  try {
    res.json(await contentCache.getGenres(validType));
  } catch (err) {
    console.error("browse/genres error:", err);
    res.status(500).json({ error: "browse_failed" });
  }
});

// GET /api/browse/genres?type=movie|tv  — list of all genre names with content
app.get("/api/browse/status", async (_req, res) => {
  try {
    res.json(await contentCache.getScanStatus());
  } catch (err) {
    console.error("browse/status error:", err);
    res.status(500).json({ error: "status_failed" });
  }
});

// GET /api/browse/filter — multi-filter browse across all cached streamable titles.
//
// All params are optional and AND-combined:
//   genre       — genre name, e.g. "Horror"
//   type        — "movie" | "tv" | "anime"
//   year_from   — earliest release year (inclusive)
//   year_to     — latest release year (inclusive)
//   age_rating  — exact cert string, e.g. "R", "TV-MA", "PG-13"
//   min_rating  — minimum vote_average (0–10), e.g. "7.5"
//   tag         — keyword substring match, e.g. "psychological" matches
//                 "psychological thriller", "psychological horror", etc.
//   sort        — "rating_desc" (default) | "rating_asc" | "year_desc" | "year_asc"
//   page        — 1-based page number (default 1)
//   limit       — results per page (default 20, max 100)
app.get("/api/browse/filter", async (req, res) => {
  const { limit, offset } = browseParams(req);
  try {
    const results = await contentCache.getFiltered({
      genre:     (req.query.genre      || "").trim() || null,
      type:      (req.query.type       || "").toLowerCase() || null,
      yearFrom:  req.query.year_from   || null,
      yearTo:    req.query.year_to     || null,
      ageRating: (req.query.age_rating || "").trim() || null,
      minRating: req.query.min_rating  || null,
      tag:       (req.query.tag        || "").trim() || null,
      sort:      req.query.sort        || "rating_desc",
      limit,
      offset,
    });
    res.json(results);
  } catch (err) {
    console.error("browse/filter error:", err);
    res.status(500).json({ error: "browse_failed" });
  }
});

// POST /api/browse/cache
//
// Self-warming cache. Client calls this after the scraper bridge has
// successfully resolved a stream for a TMDB title that wasn't in the
// cache yet (i.e. it was found via the search-fallback path). Server
// fetches TMDB metadata, writes a verified row, and the title shows up
// in cache-backed search from now on.
//
// Idempotent — if the title is already cached, this returns 200 fast
// without doing any work, so the client can fire-and-forget on every
// successful resolve without worrying about duplicate work.
//
// Body: { tmdbId: number, tmdbType: "tv"|"movie", scraperHref?: string,
//         scraperImage?: string }
app.post("/api/browse/cache", async (req, res) => {
  const body = req.body || {};
  const tmdbId   = parseInt(body.tmdbId, 10);
  const tmdbType = (body.tmdbType || "").toLowerCase();
  if (!Number.isFinite(tmdbId) || tmdbId <= 0 ||
      (tmdbType !== "tv" && tmdbType !== "movie")) {
    return res.status(400).json({ error: "Invalid tmdbId or tmdbType" });
  }

  // Fire-and-forget on the server side too — respond immediately so the
  // client's playback flow isn't blocked by our TMDB metadata pull.
  res.json({ ok: true });
  contentCache.registerVerified(
    tmdbId,
    tmdbType,
    body.scraperHref || null,
    body.scraperImage || null,
  ).catch(err => {
    console.error("registerVerified background error:", err.message);
  });
});

// GET /api/browse/search?query=...&limit=20
//
// Fuzzy search via TMDB, post-filtered to only titles the verify scanner
// has confirmed are actually streamable. Same response shape as
// /tmdb/search so the client can swap in painlessly.
app.get("/api/browse/search", async (req, res) => {
  const query = (req.query.query || "").trim();
  if (!query) return res.status(400).json({ error: "Missing ?query=" });
  const limit = Math.min(parseInt(req.query.limit, 10) || 20, 50);
  try {
    const results = await contentCache.searchStreamable(query, limit);
    res.json({ results });
  } catch (err) {
    console.error("browse/search error:", err);
    res.status(500).json({ error: "search_failed" });
  }
});

app.listen(PORT, () => {
  console.log(`Ashi server listening on port ${PORT}`);
  contentCache.start();
});

// Versioning
app.get("/api/app/version", (req, res) => {
  const cfg = readVersionConfig();
  const apkFiles = listApkFiles();

  // If the configured file is missing, fall back to the first APK (if any)
  let apkFilename = cfg.apkFilename;
  if (!apkFilename || !apkFiles.includes(apkFilename)) {
    apkFilename = apkFiles[0] || "";
  }

  const baseUrl = "https://api.anttheantster.uk"; // or http:// if no HTTPS

  res.json({
    versionCode: cfg.versionCode || 1,
    versionName: cfg.versionName || "1.0.0",
    apkUrl: apkFilename ? `${baseUrl}/downloads/${apkFilename}` : "",
    changelog: cfg.changelog || ""
  });
});

