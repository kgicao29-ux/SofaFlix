# SofaFlix CloudStream Extension

CloudStream provider for **sofaflix.shop** (phim vietsub HD — phim lẻ, phim bộ,
hoạt hình, TV shows).

- **Repo**: `https://raw.githubusercontent.com/kgicao29-ux/SofaFlix/master/repo.json`
- **Artifact**: `release/SofaFlix.cs3` (+ matching `release/plugins.json`)

## How it works

**sofaflix.shop** is a Next.js shell over the *nguonc/phimapi* dataset:

| Layer | Source |
|---|---|
| Catalog / browse / search | `phimapi.com` JSON (identical slugs; list pages on sofaflix are client-rendered) |
| Detail + episodes | `sofaflix.shop/phim/<slug>` prerendered HTML — RSC payload carries `initialMovie` + `initialEpisodes` (all servers & links) |
| Streams | resolved on-device per provider (below) |

### Stream providers (per-episode candidates, in priority order)

1. **StreamC** (`embed[1-15].streamc.xyz`, provider `nguonc`) — solved natively:
   - embed page → `data-obf` = base64 `{sUb, hD}`
   - `GET https://<host>/<sUb>?d=1` → encrypted playlist `#ENC-AESGCM;iv=<24hex>` + base64 body
   - `key = HMAC-SHA256("stream-derive-v1", hD)`
   - AES-GCM decrypt → real HLS manifest (segments `*.png` on `sings*.amass*.top`, hotlink-gated by `Referer: <embed origin>`)
   - delivered to the player as a `data:application/vnd.apple.mpegurl;base64,…`
     URI (CloudStream's `DefaultDataSource` handles the data scheme; segment URLs
     inside are absolute and authorized by the link's Referer).
2. **kkphim / ophim** — direct `.m3u8` emitted as-is (CDNs: `phim1280.tv`,
   `kkphimplayerN`, `opstreamN`). Fresh titles play; very old files may be pruned.
3. **vsmov** — player page scraped for its JS m3u8 (relative paths resolved).

Everything runs in-process (OkHttp + `javax.crypto`) — no WebView, no external
services. Verified live during development: StreamC decrypted on embed3/11/12/13/15,
segments returned `206` with the embed Referer.

## Files

```
SofaFlix/src/main/kotlin/com/sofaflix/cloudstream/
  SofaFlixPlugin.kt    — plugin entry
  SofaFlixProvider.kt  — provider (catalog, detail parsers, StreamC resolver)
release/               — SofaFlix.cs3 + plugins.json (matched pair)
.github/workflows/build.yml — publishes builds branch on push
```

## Building

```bash
./gradlew --no-daemon make makePluginsJson
# → SofaFlix/build/SofaFlix.cs3, build/plugins.json
```

Deploy: push `release/` files (or let CI publish) to the `builds` branch of
`kgicao29-ux/SofaFlix`. `plugins.json` `fileHash`/`fileSize` must match the .cs3
exactly — regenerate them together (`makePluginsJson` does this).
