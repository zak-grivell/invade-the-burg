package com.github.zakgrivell.invadetheburg.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.net.URI
import com.github.zakgrivell.invadetheburg.ui.DEFAULT_RECAP_VIDEO
import com.github.zakgrivell.invadetheburg.ui.normalizeRecapVideo

internal const val DEFAULT_BREAK_URL = "https://www.instagram.com/reels/"

enum class RecapMode(private val label: String) {
    ALWAYS("Always"), NEVER("Never"), ASK("Ask");
    override fun toString() = label
}

data class FavoriteWebsite(var name: String = "", var url: String = "") {
    override fun toString(): String = name
}

internal fun websitePresets(): List<FavoriteWebsite> = listOf(
    FavoriteWebsite("X", "https://x.com/"),
    FavoriteWebsite("Instagram Reels", DEFAULT_BREAK_URL),
    FavoriteWebsite("TikTok", "https://www.tiktok.com/"),
    FavoriteWebsite("LeetCode", "https://leetcode.com/problemset/"),
    FavoriteWebsite("Duolingo", "https://www.duolingo.com/learn")
)

private fun websiteKey(url: String): String {
    val uri = URI(url)
    return uri.host.lowercase().removePrefix("www.") + (if (uri.port == -1) "" else ":" + uri.port) +
        uri.rawPath.orEmpty().trimEnd('/') + (uri.rawQuery?.let { "?$it" } ?: "") +
        (uri.rawFragment?.let { "#$it" } ?: "")
}

internal fun websiteChoices(favorites: List<FavoriteWebsite>): List<FavoriteWebsite> =
    (favorites + websitePresets()).distinctBy { websiteKey(it.url) }.map { it.copy() }

internal fun normalizeFavorites(values: List<FavoriteWebsite>): List<FavoriteWebsite> = values.map {
    require(it.name.isNotBlank()) { "Give each favourite a name." }
    FavoriteWebsite(it.name.trim(), normalizeBreakUrl(it.url))
}.also { favorites ->
    require(favorites.map { it.url }.distinct().size == favorites.size) { "Each favourite must have a different URL." }
}

internal fun normalizeBreakUrl(value: String): String {
    val url = value.trim()
    val uri = try { URI(url) } catch (_: Exception) {
        throw IllegalArgumentException("Enter a valid HTTP or HTTPS URL.")
    }
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
        "Enter a complete HTTP or HTTPS URL, for example https://leetcode.com/problemset/."
    }
    return url
}

@Service(Service.Level.APP)
@State(name = "ReelBreakSettings", storages = [Storage("reel-break.xml")])
class ReelSettings : PersistentStateComponent<ReelSettings.Options> {
    class Options {
        var recapVideoSource: String = DEFAULT_RECAP_VIDEO
        var recapMode: RecapMode = RecapMode.ASK
        var narrationVoiceId: String = ""
        var url: String = DEFAULT_BREAK_URL
        var favorites: MutableList<FavoriteWebsite> = websitePresets().toMutableList()
        var useEtaToTimeBreaks: Boolean = true
    }
    private var options = Options()
    var recapVideoSource: String
        get() {
            if (options.recapVideoSource != DEFAULT_RECAP_VIDEO) return options.recapVideoSource
            val localDefault = java.nio.file.Path.of(System.getProperty("user.home"), ".config", "reel-break", "recap-video-source")
            return runCatching { java.nio.file.Files.readString(localDefault).trim().takeIf(String::isNotBlank) }.getOrNull()
                ?: options.recapVideoSource
        }
        set(value) { options.recapVideoSource = normalizeRecapVideo(value) }
    var recapMode: RecapMode
        get() = options.recapMode
        set(value) { options.recapMode = value }
    var narrationVoiceId: String
        get() = options.narrationVoiceId
        set(value) { options.narrationVoiceId = value.trim() }
    var url: String
        get() = options.url
        set(value) { options.url = normalizeBreakUrl(value) }
    /** When off, breaks behave exactly as before ETA existed: no skipping, no hint, no badge. */
    var useEtaToTimeBreaks: Boolean
        get() = options.useEtaToTimeBreaks
        set(value) { options.useEtaToTimeBreaks = value }
    var favorites: List<FavoriteWebsite>
        get() = options.favorites.map { it.copy() }
        set(value) { options.favorites = normalizeFavorites(value).toMutableList() }
    val availableWebsites: List<FavoriteWebsite> get() = websiteChoices(favorites)
    override fun getState(): Options = options
    override fun loadState(state: Options) {
        options = Options().apply {
            recapVideoSource = state.recapVideoSource.ifBlank { DEFAULT_RECAP_VIDEO }
            recapMode = state.recapMode
            narrationVoiceId = state.narrationVoiceId.trim()
            useEtaToTimeBreaks = state.useEtaToTimeBreaks
            url = runCatching { normalizeBreakUrl(state.url) }.getOrDefault(DEFAULT_BREAK_URL)
            favorites = state.favorites.mapNotNull {
                runCatching { normalizeFavorites(listOf(it)).single() }.getOrNull()
            }.distinctBy { it.url }.toMutableList()
        }
    }
}
