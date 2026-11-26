package com.github.aura.asbox.data

import com.github.aura.asbox.util.KVKeys
import com.github.aura.asbox.util.HistoryHelper
import com.github.aura.asbox.util.KV

internal object CurrentSubscription {

    fun cid(): String {
        val apiUrl = KV.get(KVKeys.API_URL, "")
        val lineSource = KV.get(KVKeys.API_LINE_SOURCE, "")
        if (lineSource != null && lineSource.isNotEmpty() && HistoryHelper.isApiLineUrl(apiUrl)) {
            return lineSource
        }
        return apiUrl
    }
}
