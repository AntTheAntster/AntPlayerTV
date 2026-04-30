/* ── AntPlayer Admin — v2.0 redesign ───────────────────────────────────────
 *
 * Single-page-ish admin: tabs swap visible panes; each tab loads its data
 * lazily on first visit (and on demand via Refresh buttons). Chart.js
 * instances are tracked so re-renders destroy and recreate cleanly —
 * the old admin had charts vanish on tab switches because of orphaned
 * Chart instances on canvases.
 *
 * All endpoints live under /admin-api and are protected by basic auth at
 * the Express layer; the browser will already be authenticated by the
 * time this script runs.
 * ──────────────────────────────────────────────────────────────────────── */

const ACCENT       = "#8B5CF6";
const ACCENT_DEEP  = "#6D28D9";
const ACCENT_SOFT  = "#B39DDB";
const TEXT_DIM     = "#B0B0BE";
const SURFACE_GRID = "rgba(255, 255, 255, 0.05)";

// Chart.js global defaults so every chart inherits the theme.
if (window.Chart) {
  Chart.defaults.color = TEXT_DIM;
  Chart.defaults.borderColor = SURFACE_GRID;
  Chart.defaults.font.family = "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
  Chart.defaults.font.size = 12;
}

const state = {
  charts:           {},          // id → Chart instance
  loadedTabs:       new Set(),   // tabs that have fetched data at least once
  cachePage:        1,
  cacheTotal:       0,
  cacheLastQuery:   "",
  scannerPolling:   null,
};

// ── Helpers ────────────────────────────────────────────────────────────────

const $  = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class")     node.className = v;
    else if (k === "html") node.innerHTML = v;
    else if (k === "on")   for (const [ev, fn] of Object.entries(v)) node.addEventListener(ev, fn);
    else node.setAttribute(k, v);
  }
  for (const c of [].concat(children)) {
    if (c == null) continue;
    node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
  }
  return node;
}

async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(opts.headers || {}) },
    ...opts,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`${res.status} ${res.statusText}: ${text}`);
  }
  return res.json();
}

function toast(msg, isError = false) {
  const t = $("#toast");
  t.textContent = msg;
  t.classList.toggle("error", isError);
  t.classList.add("show");
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => t.classList.remove("show"), 2400);
}

function fmtDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "—";
  return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

function fmtDateTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "—";
  return d.toLocaleString(undefined, { dateStyle: "short", timeStyle: "short" });
}

// ── Tab navigation ─────────────────────────────────────────────────────────

function switchTab(name) {
  $$(".tab").forEach(t => t.classList.toggle("active", t.dataset.tab === name));
  $$(".tab-pane").forEach(p => p.classList.toggle("active", p.id === `tab-${name}`));

  if (state.loadedTabs.has(name)) return;
  state.loadedTabs.add(name);
  switch (name) {
    case "overview":  loadOverview();   break;
    case "licenses":  loadLicenses();   break;
    case "cache":     loadCache();      break;
    case "analytics": loadAnalytics();  break;
    case "version":   loadVersion();    break;
  }
}

$("#tabs").addEventListener("click", e => {
  const tab = e.target.closest(".tab");
  if (tab) switchTab(tab.dataset.tab);
});

// ── Overview ──────────────────────────────────────────────────────────────

async function loadOverview() {
  await refreshScannerStatus();
  // Poll the status panel every 5s while Overview is the active tab.
  if (state.scannerPolling) clearInterval(state.scannerPolling);
  state.scannerPolling = setInterval(() => {
    const isOverviewActive = $("#tab-overview").classList.contains("active");
    if (isOverviewActive) refreshScannerStatus();
  }, 5000);
}

async function refreshScannerStatus() {
  let s, lic;
  try {
    [s, lic] = await Promise.all([
      api("/admin-api/scanner/status"),
      api("/admin-api/analytics/licenses"),
    ]);
  } catch (e) {
    console.error(e);
    return;
  }

  $("#statCached").textContent     = s.totalCached.toLocaleString();
  $("#statStreamable").textContent = s.totalStreamable.toLocaleString();
  $("#statEnriched").textContent   = s.totalEnriched.toLocaleString();
  $("#statLicenses").textContent   = (lic.totals.active || 0).toLocaleString();

  $("#statCachedMeta").textContent     = `${s.categoryEntries.toLocaleString()} category positions indexed`;
  $("#statStreamableMeta").textContent = pct(s.totalStreamable, s.totalCached);
  $("#statEnrichedMeta").textContent   = pct(s.totalEnriched, s.totalStreamable);
  $("#statLicensesMeta").textContent   = `${lic.totals.boundDevices || 0} bound devices`;

  $("#scannerVerify").textContent = s.scanInProgress   ? "running" : "idle";
  $("#scannerEnrich").textContent = s.enrichInProgress ? "running" : "idle";
  $("#scannerCats").textContent   = s.categoryEntries.toLocaleString();

  const pill = $("#scannerPill");
  pill.classList.remove("ok", "warn", "busy");
  if (s.paused) {
    pill.classList.add("warn");
    pill.textContent = "paused";
  } else if (s.scanInProgress || s.enrichInProgress) {
    pill.classList.add("busy");
    pill.textContent = "scanning";
  } else {
    pill.classList.add("ok");
    pill.textContent = "idle";
  }

  $("#scannerPauseBtn").disabled  = s.paused;
  $("#scannerResumeBtn").disabled = !s.paused;
}

function pct(num, den) {
  if (!den) return "0% of cached";
  return `${Math.round((num / den) * 100)}% of cached`;
}

$("#scannerPauseBtn").addEventListener("click", async () => {
  try {
    await api("/admin-api/scanner/pause", { method: "POST" });
    toast("Scanner paused");
    refreshScannerStatus();
  } catch (e) { toast(e.message, true); }
});

$("#scannerResumeBtn").addEventListener("click", async () => {
  try {
    await api("/admin-api/scanner/resume", { method: "POST" });
    toast("Scanner resumed");
    refreshScannerStatus();
  } catch (e) { toast(e.message, true); }
});

$("#scannerRefreshBtn").addEventListener("click", refreshScannerStatus);

// ── Licenses ───────────────────────────────────────────────────────────────

async function loadLicenses(query = "") {
  let data;
  try {
    const q = query ? `?q=${encodeURIComponent(query)}` : "";
    data = await api(`/admin-api/licenses${q}`);
  } catch (e) { toast(e.message, true); return; }

  const tbody = $("#licenseTable tbody");
  tbody.innerHTML = "";
  for (const row of (data.licenses || [])) {
    tbody.appendChild(renderLicenseRow(row));
  }
}

function renderLicenseRow(row) {
  const statusTag = row.status === "revoked"
    ? el("span", { class: "tag danger" }, "revoked")
    : el("span", { class: "tag ok" }, "active");

  const tr = el("tr", {}, [
    el("td", {}, String(row.id)),
    el("td", { style: "font-family: ui-monospace, monospace; font-size: 12px;" }, row.license_key || "—"),
    el("td", {}, row.user_email || "—"),
    el("td", {}, statusTag),
    el("td", { style: "font-family: ui-monospace, monospace; font-size: 11px; color: var(--text-dim);" },
      row.device_id || el("span", { class: "tag muted" }, "unbound")),
    el("td", {}, fmtDate(row.created_at)),
    el("td", {}, row.expires_at ? fmtDate(row.expires_at) : "never"),
    el("td", { class: "actions" }, [
      btn("Revoke",   "danger", () => actLic("revoke",        { id: row.id })),
      btn("+30 days", "ghost",  () => actLic("extend",        { id: row.id, days: 30 })),
      btn("New key",  "ghost",  () => actLic("reset-key",     { id: row.id })),
      btn("Reset dev","ghost",  () => actLic("reset-device",  { id: row.id })),
      btn("Delete",   "danger", () => actLic("delete",        { id: row.id })),
    ]),
  ]);
  return tr;
}

function btn(label, kind, onClick) {
  return el("button", { class: `btn ${kind || ""}`.trim(), on: { click: onClick } }, label);
}

async function actLic(action, body) {
  try {
    await api(`/admin-api/licenses/${action}`, { method: "POST", body: JSON.stringify(body) });
    toast(`License ${action}`);
    loadLicenses($("#licenseSearchInput").value.trim());
  } catch (e) { toast(e.message, true); }
}

$("#licenseSearchBtn").addEventListener("click",
  () => loadLicenses($("#licenseSearchInput").value.trim()));
$("#licenseClearBtn").addEventListener("click", () => {
  $("#licenseSearchInput").value = "";
  loadLicenses("");
});
$("#licenseSearchInput").addEventListener("keydown", e => {
  if (e.key === "Enter") loadLicenses($("#licenseSearchInput").value.trim());
});
$("#createBtn").addEventListener("click", async () => {
  const email = $("#createEmail").value.trim();
  const days  = $("#createDays").value;
  try {
    const data = await api("/admin-api/licenses/create", {
      method: "POST",
      body: JSON.stringify({ email, days })
    });
    toast(`Created ${data.license_key}`);
    $("#createEmail").value = "";
    $("#createDays").value = "";
    loadLicenses();
  } catch (e) { toast(e.message, true); }
});

// ── Cache browser ──────────────────────────────────────────────────────────

async function loadCache() {
  await fetchCachePage();
}

async function fetchCachePage() {
  const query = $("#cacheQuery").value.trim();
  const type  = $("#cacheType").value;
  const all   = $("#cacheAll").checked ? "1" : "";
  const params = new URLSearchParams({
    page: state.cachePage,
    limit: 50,
  });
  if (query) params.set("query", query);
  if (type)  params.set("type",  type);
  if (all)   params.set("all",   all);

  let data;
  try {
    data = await api(`/admin-api/cache/list?${params.toString()}`);
  } catch (e) { toast(e.message, true); return; }

  state.cacheTotal = data.total;
  state.cacheLastQuery = query;

  const grid = $("#cacheGrid");
  grid.innerHTML = "";
  if (!data.items.length) {
    grid.appendChild(el("div", {
      class: "panel-hint",
      style: "grid-column: 1 / -1; text-align: center; padding: 40px;"
    }, "No cached content matches that filter."));
  } else {
    for (const item of data.items) grid.appendChild(renderCacheCard(item));
  }

  $("#cacheMeta").textContent =
    `${data.total.toLocaleString()} matching titles · showing page ${data.page} (${data.items.length} on this page)`;
  $("#cachePageLabel").textContent = `Page ${data.page}`;
  $("#cachePrevBtn").disabled = data.page <= 1;
  $("#cacheNextBtn").disabled = data.page * data.limit >= data.total;
}

function renderCacheCard(item) {
  const poster = item.posterUrl
    ? el("div", { class: "cache-poster", style: `background-image: url('${item.posterUrl}')` })
    : el("div", { class: "cache-poster empty" }, "no poster");

  const meta = el("div", { class: "cache-meta-row" }, [
    item.year || "—",
    el("span", { class: "dot" }, "•"),
    item.tmdbType === "tv" ? "TV" : "Movie",
    item.isAnime ? el("span", { class: "dot" }, "•") : null,
    item.isAnime ? "Anime" : null,
  ]);

  const tags = el("div", { class: "cache-meta-row" }, [
    item.isStreamable
      ? el("span", { class: "tag ok" }, "streamable")
      : el("span", { class: "tag warn" }, "unverified"),
    item.ageRating ? el("span", { class: "tag muted" }, item.ageRating) : null,
    typeof item.voteAverage === "number"
      ? el("span", { class: "tag muted" }, `★ ${item.voteAverage.toFixed(1)}`)
      : null,
  ]);

  const verified = el("div", {
    class: "cache-meta-row",
    style: "color: var(--text-mute); font-size: 10.5px;"
  }, `Verified ${fmtDateTime(item.lastVerified)}`);

  return el("div", { class: "cache-card", title: item.title }, [
    poster,
    el("div", { class: "cache-body" }, [
      el("div", { class: "cache-title" }, item.title),
      meta, tags, verified,
    ]),
  ]);
}

$("#cacheRefreshBtn").addEventListener("click", () => { state.cachePage = 1; fetchCachePage(); });
$("#cacheQuery").addEventListener("keydown", e => {
  if (e.key === "Enter") { state.cachePage = 1; fetchCachePage(); }
});
$("#cacheType").addEventListener("change", () => { state.cachePage = 1; fetchCachePage(); });
$("#cacheAll").addEventListener("change",  () => { state.cachePage = 1; fetchCachePage(); });
$("#cachePrevBtn").addEventListener("click", () => { if (state.cachePage > 1) { state.cachePage--; fetchCachePage(); } });
$("#cacheNextBtn").addEventListener("click", () => { state.cachePage++; fetchCachePage(); });

// ── Analytics ─────────────────────────────────────────────────────────────

async function loadAnalytics() {
  let lic, plays;
  try {
    [lic, plays] = await Promise.all([
      api("/admin-api/analytics/licenses"),
      api("/admin-api/analytics/plays"),
    ]);
  } catch (e) { toast(e.message, true); return; }

  $("#anaLicTotal").textContent   = (lic.totals.total || 0).toLocaleString();
  $("#anaLicActive").textContent  = (lic.totals.active || 0).toLocaleString();
  $("#anaLicRevoked").textContent = (lic.totals.revoked || 0).toLocaleString();
  $("#anaLicBound").textContent   = (lic.totals.boundDevices || 0).toLocaleString();

  // Reverse so dates plot oldest → newest left-to-right.
  const licDays  = (lic.createdPerDay || []).slice().reverse();
  const playDays = (plays.playsPerDay || []).slice().reverse();

  renderLineChart("licensesPerDayChart",
    licDays.map(d => d.day), licDays.map(d => d.c), "Licenses");

  renderLineChart("playsPerDayChart",
    playDays.map(d => d.day), playDays.map(d => d.c), "Plays");

  renderBarChart("topTitlesChart",
    (plays.topTitles || []).map(d => d.title || "—"),
    (plays.topTitles || []).map(d => d.c),
    "Plays");

  renderDonutChart("watchTypesChart",
    (plays.watchTypes || []).map(d => d.watch_type || "unknown"),
    (plays.watchTypes || []).map(d => d.c));
}

function destroyChart(id) {
  if (state.charts[id]) { state.charts[id].destroy(); delete state.charts[id]; }
}

function renderLineChart(canvasId, labels, values, title) {
  destroyChart(canvasId);
  const ctx = $("#" + canvasId).getContext("2d");
  // Soft purple gradient under the line for the modern fill look.
  const gradient = ctx.createLinearGradient(0, 0, 0, 240);
  gradient.addColorStop(0, "rgba(139, 92, 246, 0.45)");
  gradient.addColorStop(1, "rgba(139, 92, 246, 0.02)");
  state.charts[canvasId] = new Chart(ctx, {
    type: "line",
    data: {
      labels,
      datasets: [{
        label: title,
        data: values,
        borderColor: ACCENT,
        backgroundColor: gradient,
        fill: true,
        tension: 0.35,
        borderWidth: 2,
        pointRadius: 3,
        pointBackgroundColor: ACCENT,
        pointHoverRadius: 5,
      }],
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { grid: { color: SURFACE_GRID }, ticks: { maxRotation: 0, autoSkip: true } },
        y: { grid: { color: SURFACE_GRID }, beginAtZero: true, ticks: { precision: 0 } },
      },
    },
  });
}

function renderBarChart(canvasId, labels, values, title) {
  destroyChart(canvasId);
  const ctx = $("#" + canvasId).getContext("2d");
  state.charts[canvasId] = new Chart(ctx, {
    type: "bar",
    data: {
      labels,
      datasets: [{
        label: title, data: values,
        backgroundColor: ACCENT_DEEP,
        borderColor: ACCENT, borderWidth: 1,
        borderRadius: 6,
      }],
    },
    options: {
      indexAxis: "y",
      responsive: true, maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        x: { grid: { color: SURFACE_GRID }, beginAtZero: true, ticks: { precision: 0 } },
        y: { grid: { color: "transparent" } },
      },
    },
  });
}

function renderDonutChart(canvasId, labels, values) {
  destroyChart(canvasId);
  const ctx = $("#" + canvasId).getContext("2d");
  state.charts[canvasId] = new Chart(ctx, {
    type: "doughnut",
    data: {
      labels,
      datasets: [{
        data: values,
        backgroundColor: [ACCENT, ACCENT_DEEP, ACCENT_SOFT, "#475569", "#10B981", "#F59E0B"],
        borderColor: "transparent",
        hoverOffset: 6,
      }],
    },
    options: {
      responsive: true, maintainAspectRatio: false,
      cutout: "60%",
      plugins: {
        legend: { position: "bottom", labels: { padding: 14, usePointStyle: true } },
      },
    },
  });
}

// ── Version ───────────────────────────────────────────────────────────────

async function loadVersion() {
  let data;
  try { data = await api("/admin-api/version"); }
  catch (e) { toast(e.message, true); return; }

  $("#versionCode").value = data.version.versionCode || "";
  $("#versionName").value = data.version.versionName || "";
  $("#changelog").value   = data.version.changelog   || "";

  const select = $("#apkFilename");
  select.innerHTML = "";
  for (const file of data.apkFiles) {
    const opt = document.createElement("option");
    opt.value = file; opt.textContent = file;
    if (file === data.version.apkFilename) opt.selected = true;
    select.appendChild(opt);
  }
}

$("#uploadApkBtn").addEventListener("click", async () => {
  const input = $("#apkFileInput");
  if (!input.files.length) return toast("Pick a file first", true);
  const fd = new FormData();
  fd.append("apk", input.files[0]);
  try {
    const res = await fetch("/admin-api/upload-apk", { method: "POST", body: fd });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    toast(`Uploaded ${data.filename}`);
    input.value = "";
    loadVersion();
  } catch (e) { toast(e.message || "Upload failed", true); }
});

$("#saveVersionBtn").addEventListener("click", async () => {
  const body = {
    versionCode: parseInt($("#versionCode").value, 10),
    versionName: $("#versionName").value.trim(),
    apkFilename: $("#apkFilename").value,
    changelog:   $("#changelog").value,
  };
  try {
    await api("/admin-api/version", { method: "POST", body: JSON.stringify(body) });
    toast("Version saved");
  } catch (e) { toast(e.message, true); }
});

// ── Boot ───────────────────────────────────────────────────────────────────

document.addEventListener("DOMContentLoaded", () => {
  switchTab("overview");
});
