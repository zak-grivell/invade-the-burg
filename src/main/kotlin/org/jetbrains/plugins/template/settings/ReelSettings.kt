package org.jetbrains.plugins.template.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.net.URI

internal const val DEFAULT_BREAK_URL = "https://www.instagram.com/reels/"

data class FavoriteWebsite(var name: String = "", var url: String = "") {
    override fun toString(): String = name
}

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
        var url: String = DEFAULT_BREAK_URL
        var favorites: MutableList<FavoriteWebsite> = mutableListOf(FavoriteWebsite("Instagram Reels", DEFAULT_BREAK_URL))
    }
    private var options = Options()
    var url: String
        get() = options.url
        set(value) { options.url = normalizeBreakUrl(value) }
    var favorites: List<FavoriteWebsite>
        get() = options.favorites.map { it.copy() }
        set(value) { options.favorites = normalizeFavorites(value).toMutableList() }
    override fun getState(): Options = options
    override fun loadState(state: Options) {
        options = Options().apply {
            url = runCatching { normalizeBreakUrl(state.url) }.getOrDefault(DEFAULT_BREAK_URL)
            favorites = state.favorites.mapNotNull {
                runCatching { normalizeFavorites(listOf(it)).single() }.getOrNull()
            }.distinctBy { it.url }.toMutableList()
        }
    }
}
