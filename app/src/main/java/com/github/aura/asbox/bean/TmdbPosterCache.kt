package com.github.aura.asbox.bean

interface TmdbPosterCache {

    fun tmdbCachedPoster(name: String?, year: Int, altName: String?): String?
}
