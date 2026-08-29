package com.sofaflix.cloudstream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SofaFlix (sofaflix.shop) — Vietnamese movie/series site.
 *
 * Architecture (reverse-engineered 2026-08-29):
 *  - The site is a Next.js shell over the nguonc/phimapi dataset. List pages are
 *    client-rendered, but detail pages are prerendered with the full episode data
 *    embedded in the RSC payload as double-escaped JSON:
 *        "movie":{...},"initialEpisodes":[{server_name, server_data:[{
 *            name, slug, filename, link_embed, link_m3u8, __provider}]}]
 *  - Catalog browsing + search therefore go through the public phimapi.com JSON
 *    (identical slugs), while detail/episodes are read from sofaflix.shop SSR.
 *
 * Stream providers per episode:
 *  - nguonc  : embed[1-3]?.streamc.xyz/embed.php?hash=<md5> — resolved NATIVELY:
 *        page carries <div id="player" data-obf="<b64>"> = {"sUb":"<b64 {h,t}>","hD":hash}
 *        GET  https://<embedHost>/<sUb>?d=1   (Referer: embed page)
 *        →  #EXTM3U / #ENC-AESGCM;iv=<24hex> / base64 ciphertext
 *        key = HMAC-SHA256("stream-derive-v1", hD)
 *        AES-GCM decrypt(iv, ct) → real HLS manifest (segments *.png on
 *        sings*.amass*.top, hotlink-gated by Referer = embed origin)
 *        → handed to the player as a data: URI (CloudStream's DefaultDataSource
 *          supports the data scheme; segment URLs inside are absolute and the
 *          link's Referer authorizes them).
 *  - kkphim / ophim : direct .m3u8 URLs (CDNs: phim1280.tv / kkphimplayerN /
 *        opstreamN) — emitted as-is (fresh titles work; old files get pruned).
 *  - vsmov  : v1.streamvsmov.com page with a JS `file: 'https://…m3u8'`.
 */
class SofaFlixProvider : MainAPI() {
    override var mainUrl = "https://sofaflix.shop"
    override var name = "SofaFlix"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasMainPage = true

    private val api = "https://phimapi.com"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // ------------------------------------------------------------------ //
    // Catalog (phimapi JSON — same dataset as sofaflix)
    // ------------------------------------------------------------------ //

    override val mainPage = mainPageOf(
        "$api/v1/api/danh-sach/phim-moi-cap-nhat" to "Mới cập nhật",
        "$api/v1/api/danh-sach/phim-le" to "Phim Lẻ",
        "$api/v1/api/danh-sach/phim-bo" to "Phim Bộ",
        "$api/v1/api/danh-sach/hoat-hinh" to "Hoạt Hình",
        "$api/v1/api/danh-sach/tv-shows" to "TV Shows",
        "$api/v1/api/the-loai/hanh-dong" to "Hành Động",
        "$api/v1/api/the-loai/phieu-luu" to "Phiêu Lưu",
        "$api/v1/api/the-loai/tinh-cam" to "Tình Cảm",
        "$api/v1/api/the-loai/kinh-di" to "Kinh Dị",
        "$api/v1/api/the-loai/hai-huoc" to "Hài Hước",
        "$api/v1/api/the-loai/vien-tuong" to "Viễn Tưởng",
        "$api/v1/api/the-loai/bi-an" to "Bí Ẩn",
        "$api/v1/api/the-loai/chinh-kich" to "Chính Kịch",
        "$api/v1/api/the-loai/hoat-hinh" to "Anime",
        "$api/v1/api/the-loai/vo-thuat" to "Võ Thuật",
        "$api/v1/api/the-loai/tam-ly" to "Tâm Lý",
        "$api/v1/api/the-loai/gia-dinh" to "Gia Đình",
        "$api/v1/api/the-loai/hoc-duong" to "Học Đường",
        "$api/v1/api/the-loai/co-trang" to "Cổ Trang",
        "$api/v1/api/quoc-gia/trung-quoc" to "Trung Quốc",
        "$api/v1/api/quoc-gia/han-quoc" to "Hàn Quốc",
        "$api/v1/api/quoc-gia/nhat-ban" to "Nhật Bản",
        "$api/v1/api/quoc-gia/thai-lan" to "Thái Lan",
        "$api/v1/api/quoc-gia/my" to "Âu Mỹ",
        "$api/v1/api/quoc-gia/viet-nam" to "Việt Nam",
    )

    private fun headers(ref: String? = null): Map<String, String> =
        buildMap {
            put("User-Agent", userAgent)
            if (ref != null) put("Referer", ref)
        }

    private fun fixImg(u: String?): String? {
        if (u.isNullOrBlank()) return null
        return if (u.startsWith("http")) u else "$api/${u.removePrefix("/")}"
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiItem(
        val name: String? = null,
        val slug: String? = null,
        val origin_name: String? = null,
        val thumb_url: String? = null,
        val poster_url: String? = null,
        val year: Int? = null,
        val type: String? = null,
        val episode_current: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiListResponse(
        val status: Boolean? = null,
        val data: ApiPage? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiPage(
        val items: List<ApiItem>? = null,
        val pagination: ApiPagination? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApiPagination(val totalPages: Int? = null)

    private fun itemToSearch(it: ApiItem): SearchResponse? {
        val slug = it.slug ?: return null
        val title = it.name ?: it.origin_name ?: return null
        val type = when (it.type) {
            "single" -> TvType.Movie
            "series", "tvshows" -> TvType.TvSeries
            else ->
                if ((it.episode_current ?: "").contains("Full", true)) TvType.Movie
                else TvType.TvSeries
        }
        val poster = fixImg(it.poster_url ?: it.thumb_url)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, "$mainUrl/phim/$slug") {
                this.posterUrl = poster
                this.year = it.year
            }
        } else {
            newTvSeriesSearchResponse(title, "$mainUrl/phim/$slug") {
                this.posterUrl = poster
                this.year = it.year
            }
        }
    }

    private suspend inline fun <reified T : Any> getJson(url: String): T? =
        runCatching { parseJson<T>(app.get(url, headers = headers(mainUrl)).text) }.getOrNull()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains('?')) '&' else '?'
        val res = getJson<ApiListResponse>("${request.data}${sep}page=$page")
            ?: throw ErrorLoadingException("Không tải được danh sách")
        val items = res.data?.items.orEmpty().mapNotNull { itemToSearch(it) }
        val hasNext = res.data?.pagination?.totalPages?.let { page < it } ?: (items.size >= 20)
        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$api/v1/api/tim-kiem?page=1&limit=24&keyword=" +
            URLEncoder.encode(query, "UTF-8")
        val res = getJson<ApiListResponse>(url) ?: return emptyList()
        return res.data?.items.orEmpty().mapNotNull { itemToSearch(it) }
    }

    // ------------------------------------------------------------------ //
    // Detail (sofaflix SSR → RSC payload → double-escaped JSON)
    // ------------------------------------------------------------------ //

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SofaMovie(
        val name: String? = null,
        val origin_name: String? = null,
        val slug: String? = null,
        val year: Int? = null,
        val type: String? = null,
        val content: String? = null,
        val thumb_url: String? = null,
        val poster_url: String? = null,
        val time: String? = null,
        val episode_current: String? = null,
        val genre: List<IdName>? = null,
        val country: List<IdName>? = null,
        val actor: List<String>? = null,
        val director: List<String>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class IdName(val id: Int? = null, val name: String? = null, val slug: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SofaEp(
        val name: String? = null,
        val slug: String? = null,
        val filename: String? = null,
        val link_embed: String? = null,
        val link_m3u8: String? = null,
        val __provider: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SofaServer(
        val server_name: String? = null,
        val server_data: List<SofaEp>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SofaWrap(val movie: SofaMovie? = null)

    /** phimapi detail fallback (same dataset & shape) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PhimApiDetail(
        val status: Boolean? = null,
        val movie: SofaMovie? = null,
        val episodes: List<SofaServer>? = null,
    )

    /** payload packed into Episode.data and handed back to loadLinks */
    private data class EpPayload(
        val name: String,
        val candidates: List<Candidate>,
    )

    private data class Candidate(
        val provider: String?,
        val embed: String?,
        val m3u8: String?,
    )

    private fun unescapeFlight(h: String): String =
        h.replace("\\\\\"", " ").replace("\\\"", "\"")

    /** bracket-match a JSON object/array starting at `start` (index of '{' or '[') */
    private fun bracketMatch(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        val open = s[start]
        val close = if (open == '[') ']' else '}'
        var k = start
        while (k < s.length) {
            val c = s[k]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return k
                    }
                }
            }
            k++
        }
        return -1
    }

    private fun extractDetail(html: String): Pair<SofaMovie?, List<SofaServer>> {
        val d = unescapeFlight(html)
        val epsKey = d.indexOf("\"initialEpisodes\":")
        var servers: List<SofaServer> = emptyList()
        if (epsKey >= 0) {
            val arrStart = d.indexOf('[', epsKey)
            if (arrStart >= 0) {
                val end = bracketMatch(d, arrStart)
                if (end > arrStart) {
                    val chunk = d.substring(arrStart, end + 1)
                    servers = runCatching { parseJson<List<SofaServer>>(chunk) }
                        .recoverCatching { parseJson<List<SofaServer>>(unescapeFlight(chunk)) }
                        .getOrDefault(emptyList())
                }
            }
        }
        var movie: SofaMovie? = null
        val mKey = (if (epsKey >= 0) d.lastIndexOf("\"initialMovie\"", epsKey) else d.indexOf("\"initialMovie\""))
            .let { if (it >= 0) it else if (epsKey >= 0) d.lastIndexOf("\"movie\"", epsKey) else d.indexOf("\"movie\"") }
        if (mKey >= 0) {
            val objStart = d.indexOf('{', mKey)
            if (objStart >= 0) {
                val end = bracketMatch(d, objStart)
                if (end > objStart) {
                    val chunk = d.substring(objStart, end + 1)
                    movie = runCatching { parseJson<SofaWrap>(chunk).movie }
                        .recoverCatching { parseJson<SofaWrap>(unescapeFlight(chunk)).movie }
                        .getOrNull()
                        ?: runCatching { parseJson<SofaMovie>(chunk) }.getOrNull()
                }
            }
        }
        return movie to servers
    }

    /** normalized episode key: "Tập 01" → "1", "1a" → "1a", "Full" → "full" */
    private fun epKey(name: String): String {
        val t = name.trim().replace(Regex("tập", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("ep", RegexOption.IGNORE_CASE), " ").trim()
        val m = Regex("^(\\d+)\\s*([ab])?$", RegexOption.IGNORE_CASE).find(t)
        return if (m != null) {
            val num = m.groupValues[1].trimStart('0').ifEmpty { "0" }
            num + m.groupValues[2].lowercase()
        } else t.lowercase()
    }

    private val providerPriority = mapOf("nguonc" to 0, "kkphim" to 1, "vsmov" to 2, "ophim" to 3)

    /** gather every server's link for one episode: by normalized key, index fallback */
    private fun mergeCandidates(servers: List<SofaServer>, idx: Int, key: String? = null): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (s in servers) {
            val data = s.server_data ?: continue
            val ep = if (key != null) {
                data.firstOrNull { !it.name.isNullOrBlank() && epKey(it.name!!) == key }
                    ?: data.getOrNull(idx)
            } else {
                data.getOrNull(idx)
            } ?: continue
            val embed = ep.link_embed?.takeIf { it.startsWith("http") }
            val m3u8 = ep.link_m3u8?.takeIf { it.startsWith("http") && it.contains(".m3u8") }
            if (embed == null && m3u8 == null) continue
            out.add(Candidate(ep.__provider ?: s.server_name, embed, m3u8))
        }
        return out.sortedBy { providerPriority[it.provider] ?: 9 }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = runCatching { app.get(url, headers = headers(mainUrl)).text }.getOrNull()
        var (movie, servers) = html?.let { extractDetail(it) } ?: (null to emptyList())

        // Fallback: phimapi detail (same dataset) if the SSR payload is missing
        val slug = url.removeSuffix("/").substringAfterLast('/')
        if (movie == null && servers.isEmpty()) {
            getJson<PhimApiDetail>("$api/v1/api/phim/$slug")?.let { alt ->
                if (alt.status == true) {
                    movie = alt.movie
                    servers = alt.episodes ?: emptyList()
                }
            }
        }
        if (movie == null) throw ErrorLoadingException("Không đọc được trang phim")

        val title = movie.name ?: movie.origin_name ?: slug
        val poster = fixImg(movie.poster_url ?: movie.thumb_url)
        val plot = movie.content?.replace(Regex("<[^>]+>"), "")?.trim()
        val tags = (movie.genre?.mapNotNull { it.name } ?: emptyList()) +
            (movie.country?.mapNotNull { it.name } ?: emptyList())
        val isSeries = servers.any { (it.server_data?.size ?: 0) > 1 } ||
            movie.type == "series" ||
            (
                (movie.episode_current ?: "").contains("Đang", true) &&
                    !(movie.episode_current ?: "").contains("Full", true)
                )

        if (!isSeries) {
            val candidates = mergeCandidates(servers, 0)
            return newMovieLoadResponse(title, url, TvType.Movie, EpPayload("Full", candidates).toJson()) {
                this.posterUrl = poster
                this.year = movie.year
                this.plot = plot
                this.tags = tags
                this.comingSoon = false
            }
        }

        // series: canonical episode list from the longest server
        val canonical = servers.maxByOrNull { it.server_data?.size ?: 0 }?.server_data.orEmpty()
            .filter { !it.name.isNullOrBlank() }
        val seen = HashSet<String>()
        val episodes = canonical.mapIndexedNotNull { idx, ep ->
            val nm = ep.name!!.trim()
            val key = epKey(nm)
            if (!seen.add(key) && key != "full") return@mapIndexedNotNull null
            val epNum = Regex("(\\d+)").find(nm)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            newEpisode(EpPayload(nm, mergeCandidates(servers, idx, key)).toJson()) {
                this.name = nm
                this.season = 1
                this.episode = epNum
                this.posterUrl = poster
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = movie.year
            this.plot = plot
            this.tags = tags
        }
    }

    // ------------------------------------------------------------------ //
    // Stream resolution
    // ------------------------------------------------------------------ //

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = runCatching { parseJson<EpPayload>(data) }.getOrNull() ?: return false
        var emitted = false

        for (c in payload.candidates) {
            runCatching {
                // ---- 1) StreamC (nguonc): native decrypt → data: URI ----
                if (c.embed != null && Regex("streamc\\.[a-z]+/embed\\.php").containsMatchIn(c.embed)) {
                    resolveStreamc(c.embed)?.let { (playlist, refererOrigin) ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "SofaFlix ${c.provider ?: "Vietsub"} • StreamC",
                                url = playlist,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = refererOrigin
                            },
                        )
                        emitted = true
                    }
                }

                // ---- 2) direct m3u8 (kkphim / ophim) ----
                if (c.m3u8 != null) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "SofaFlix ${c.provider ?: "Vietsub"}",
                            url = c.m3u8,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = mainUrl
                        },
                    )
                    emitted = true
                }

                // ---- 3) vsmov page → JS m3u8 ----
                if (c.embed != null && c.m3u8 == null &&
                    Regex("streamvsmov\\.com").containsMatchIn(c.embed)
                ) {
                    val page = app.get(c.embed, headers = headers(mainUrl)).text
                    val vOrigin = Regex("^(https?://[^/]+)").find(c.embed)?.groupValues?.get(1)
                    Regex("['\"]([^'\"]+\\.m3u8[^'\"]*)['\"]").find(page)?.groupValues?.get(1)
                        ?.let { m -> if (m.startsWith("/") && vOrigin != null) "$vOrigin$m" else m }
                        ?.let { m ->
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "SofaFlix ${c.provider ?: "Vietsub"} • vsmov",
                                url = m,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = c.embed
                            },
                        )
                        emitted = true
                    }
                }
            }
        }
        return emitted
    }

    /**
     * StreamC native resolver.
     * embed → data-obf {sUb,hD} → GET host/<sUb>?d=1 → #ENC-AESGCM;iv + b64 ct
     * → key = HMAC-SHA256("stream-derive-v1", hD) → AES-GCM decrypt → m3u8 text
     * → returned as a data: URI with the embed origin as Referer.
     */
    private suspend fun resolveStreamc(embedUrl: String): Pair<String, String>? {
        val host = Regex("^(https?://[^/]+)").find(embedUrl)?.groupValues?.get(1) ?: return null
        val page = runCatching { app.get(embedUrl, headers = headers(mainUrl)).text }.getOrNull() ?: return null
        val obf = Regex("data-obf=\"([^\"]+)\"").find(page)?.groupValues?.get(1) ?: return null

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Obf(val sUb: String? = null, val hD: String? = null)

        val outer = runCatching {
            parseJson<Obf>(String(Base64.decode(obf, Base64.DEFAULT)))
        }.getOrNull() ?: return null
        val sub = outer.sUb ?: return null
        val hd = outer.hD ?: return null

        val enc = runCatching {
            app.get("$host/$sub?d=1", headers = headers(embedUrl)).text
        }.getOrNull() ?: return null
        val ivHex = Regex("iv=([0-9a-fA-F]+)").find(enc)?.groupValues?.get(1) ?: return null
        val b64 = enc.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString("")
        if (b64.isBlank()) return null

        val iv = hexToBytes(ivHex)
        val ct = Base64.decode(b64, Base64.DEFAULT)
        if (iv.size != 12 || ct.size < 16) return null

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("stream-derive-v1".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val key = mac.doFinal(hd.toByteArray(Charsets.UTF_8))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = runCatching { cipher.doFinal(ct) }.getOrNull() ?: return null
        val m3u8 = String(plain, Charsets.UTF_8)
        if (!m3u8.startsWith("#EXTM3U")) return null

        val dataUri = "data:application/vnd.apple.mpegurl;base64," +
            Base64.encodeToString(plain, Base64.NO_WRAP)
        return dataUri to host
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}
