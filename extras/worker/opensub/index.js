/**
 * OwnTV OpenSubtitles proxy (Cloudflare Worker).
 *
 * Companion to the TMDB proxy in ../tmdb/index.js — same idea, different upstream:
 * the OpenSubtitles REST API (https://api.opensubtitles.com/api/v1/...).
 *
 * What it does:
 *  1. Accepts the same path/query shape as OpenSubtitles' /api/v1/... API,
 *     for GET, POST and DELETE (login/download are POST; logout is DELETE).
 *  2. Injects the application consumer key from the Worker secret
 *     `OPENSUB_API_KEY` as the `Api-Key` header, plus an identifying
 *     `User-Agent` — the key never ships in the app APK and never appears
 *     in this repo.
 *  3. Forwards the client's `Authorization: Bearer` header untouched
 *     (only /download and /infos/user need it; search is unauthenticated).
 *  4. Routes to either api.opensubtitles.com or vip-api.opensubtitles.com,
 *     chosen by the client via the `X-OS-Host` header. The choice is
 *     allowlisted to exactly those two hosts — this Worker cannot be used
 *     as an open proxy. Default is the standard host.
 *  5. Edge-caches ONLY GET /api/v1/subtitles search responses, for a short
 *     TTL. Login, logout, download, and user-info responses are never
 *     cached (they are per-user and/or consume quota).
 */

/** Edge-cache TTL for subtitle search JSON. Short — new subs appear daily. */
const SEARCH_CACHE_TTL_SECONDS = 6 * 60 * 60; // 6 hours

/** The only upstreams this Worker will ever talk to. */
const ALLOWED_HOSTS = ["api.opensubtitles.com", "vip-api.opensubtitles.com"];
const DEFAULT_HOST = "api.opensubtitles.com";

/** Sent upstream; OpenSubtitles requires an identifying "AppName vX.Y" User-Agent. */
const USER_AGENT = "OwnTV v1.0";

export default {
  async fetch(req, env) {
    if (!["GET", "POST", "DELETE"].includes(req.method)) {
      return new Response("Method not allowed", { status: 405 });
    }

    const url = new URL(req.url);

    // Only the OpenSubtitles v1 API surface is proxied.
    if (!url.pathname.startsWith("/api/v1/")) {
      return new Response("Not found", { status: 404 });
    }

    // Upstream host selection (VIP routing per login-returned base_url),
    // strictly allowlisted.
    const requestedHost = req.headers.get("X-OS-Host") || DEFAULT_HOST;
    if (!ALLOWED_HOSTS.includes(requestedHost)) {
      return new Response("Forbidden upstream host", { status: 403 });
    }

    // Cache only anonymous GET subtitle searches. Everything user-specific
    // (login, logout, download, /infos/user) bypasses the cache entirely.
    const cacheable =
      req.method === "GET" && url.pathname === "/api/v1/subtitles";

    const cache = caches.default;
    // Cache key ignores the Authorization header (search needs none) but
    // includes the upstream host so standard/VIP responses never mix.
    const cacheKey = new Request(
      "https://" + requestedHost + url.pathname + url.search,
      { method: "GET" }
    );
    if (cacheable) {
      const cached = await cache.match(cacheKey);
      if (cached) return cached;
    }

    // Forward with the server-side consumer key injected.
    const upstream =
      "https://" + requestedHost + url.pathname + url.search;
    const headers = new Headers();
    headers.set("Api-Key", env.OPENSUB_API_KEY);
    headers.set("User-Agent", USER_AGENT);
    headers.set("Accept", "application/json");
    const auth = req.headers.get("Authorization");
    if (auth) headers.set("Authorization", auth);
    const contentType = req.headers.get("Content-Type");
    if (contentType) headers.set("Content-Type", contentType);

    // Buffer the body instead of streaming it: a streamed body goes upstream
    // chunked without a Content-Length, which stricter OpenSubtitles endpoints
    // (/download) reject with 400 even though /login tolerates it.
    const body = req.method === "POST" ? await req.text() : undefined;

    const resp = await fetch(upstream, {
      method: req.method,
      headers,
      body,
    });

    // Pass errors through uncached so a transient failure doesn't stick.
    if (!resp.ok || !cacheable) {
      return new Response(resp.body, { status: resp.status, headers: resp.headers });
    }

    const out = new Response(resp.body, resp);
    out.headers.set("Cache-Control", `public, max-age=${SEARCH_CACHE_TTL_SECONDS}`);
    out.headers.set("Access-Control-Allow-Origin", "*");
    await cache.put(cacheKey, out.clone());
    return out;
  },
};
