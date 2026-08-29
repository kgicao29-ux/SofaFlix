# SofaFlix — reverse-engineering notes (2026-08-29)

## Site architecture

- **sofaflix.shop**: Next.js (App Router, RSC), Cloudflare, Vietnamese.
  `x-powered-by: Next.js`; SSR HTML for `/` and `/phim/<slug>`; `/the-loai/…`,
  `/quoc-gia/…`, `/az-list`, `/lich-chieu`, `/loc-phim` are client-rendered
  (no items in raw HTML) → catalog comes from phimapi.com in the browser.
- Dataset = **nguonc/phimapi** (images on phimimg.com / phimapi.com; identical
  slugs). Detail payload inside the HTML is JSON-as-string with *varying escape
  depth*: the reliable anchors after one light-unescape (`\\"`→`"`) are
  `"initialMovie"` and `"initialEpisodes"` (NOT `"movie":` — the colon stays
  escaped; `initialMovie` is the page-props key). Episodes shape:
  `[{server_name, server_data: [{name, slug, filename, link_embed, link_m3u8, __provider}]}]`
  with providers `nguonc | kkphim | ophim | vsmov` (server names repeat, e.g.
  two "Vietsub #1"; dedupe/merge by normalized episode key: "Tập 01"→"1",
  "1a" stays "1a").
- Search: no server endpoint (`/api/films` etc. return the app's 404 JSON
  `{"status":false,"msg":"hmmm!"}`); use `phimapi.com/v1/api/tim-kiem?keyword=`.
- phimapi detail `v1/api/phim/<slug>` can return `status:success` with EMPTY
  movie/episodes — do not rely on it as sole source (sofaflix SSR is primary).

## StreamC (the interesting part)

Embed chain:
1. `link_embed` = `https://embedN.streamc.xyz/embed.php?hash=<md5>` (N ∈ 1..15+).
2. Page: `<div id="player" data-obf="<base64>">`; base64 = `{"sUb":"<b64>","hD":"<hash>"}`
   (sUb decodes to `{"h":…,"t":"<64 hex>"}` — the signed path token).
3. Playlist: `GET https://<embedHost>/<sUb>?d=1` (Referer = embed page) →
   `200 application/vnd.apple.mpegurl`:
   ```
   #EXTM3U
   #ENC-AESGCM;iv=<24 hex chars>
   #EXT-X-B65:0-<N-1>
   <one giant base64 line>
   ```
4. Decrypt: `key = HMAC-SHA256(key=b"stream-derive-v1", msg=hD)`,
   AES-GCM(iv = header iv, ct = base64 body incl. trailing 16-byte tag, no AAD).
5. Plaintext = standard HLS manifest, `#EXT-X-DISCONTINUITY`, segments
   `https://singsN.amassM.top/<hD>/streamaaaKKKK.png` (PNG-masked TS).
   Segments are hotlink-gated: need `Referer: https://<embedHost>/` (403 otherwise).

How the key scheme was found: streamc's `player.js` (156 KB javascript-obfuscator
output) was executed in Node with DOM stubs; proxies on `crypto.subtle` revealed
`importKey("raw", … "stream-derive-v1" …, HMAC/SHA-256)` and the derived
AES-GCM key = HMAC over `window.videoHash` (= hD). Anti-tamper gotchas: the
script checks its own source for newlines (keep it minified), detects
non-native `console`/timer functions (mask with `function f() { [native code] }`
toString), and needs `AbortController`/`Blob` in the sandbox.

Delivery: CloudStream's player builds `DefaultDataSource.Factory` (CS3IPlayer),
which supports `data:` URIs → the decrypted manifest is base64-packed into
`data:application/vnd.apple.mpegurl;base64,…` and the ExtractorLink carries the
embed origin as Referer for the segment requests.

## Other providers

- kkphim: `link_m3u8` direct on `vN.kkphimplayerM.com/<yyyymmdd>/<id>/index.m3u8`
  or via `player.phimapi.com/player/?url=…` (the `url=` param is the m3u8).
- ophim: `vip.opstreamN.com/<date>/<id>/index.m3u8` (+ `/share/<hash>` page).
  Numbered mirrors come and go (opstream12/16 DNS-dead; SSL hostname mismatches).
- vsmov: `vN.streamvsmov.com/video/<uuid>` page, JS contains `'/master.m3u8'`
  (relative — resolve against page origin; content varies, regex best-effort).
- From datacenter IPs many of these CDNs answer 404/403; from residential
  (real device) they generally work. StreamC resolution is unaffected.

## CloudStream API notes (template: com.github.recloudstream:gradle:81b1d424d2)

- `getMainPage(page: Int, request: MainPageRequest): HomePageResponse`,
  `newHomePageResponse(HomePageList(name, items), hasNext)`.
- Search responses: `newMovieSearchResponse` / `newTvSeriesSearchResponse`.
- Episodes: `newEpisode(data) { name; season; episode; posterUrl }`.
- `newExtractorLink(source, name, url, type) { referer; quality }` — no
  `referer` named param at top level in this API version.
- JSON: `AppUtils.parseJson/toJson` (mapper handles Kotlin data classes).
- `phim-moi-cap-nhat` response has no `pagination` field → hasNext by item count.
