package com.github.aura.asbox.ui.page

import androidx.lifecycle.ViewModel
import com.github.aura.asbox.R
import com.github.aura.asbox.util.AppContextHolder
import com.github.aura.asbox.util.DanmuSettingsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DanmuColorUiState(
    val randomColor: Boolean = false,
    val palette: List<String> = emptyList(),
)

class DanmuColorViewModel : ViewModel() {

    private val _state = MutableStateFlow(DanmuColorUiState())
    val state: StateFlow<DanmuColorUiState> = _state.asStateFlow()

    private val _toastEvent = MutableStateFlow<String?>(null)
    val toastEvent = _toastEvent.asStateFlow()

    init {
        refresh()
    }

    private fun str(resId: Int): String = AppContextHolder.context()?.getString(resId) ?: ""

    fun clearToast() {
        _toastEvent.value = null
    }

    fun refresh() {
        _state.value = DanmuColorUiState(
            randomColor = DanmuSettingsState.config.randomColor,
            palette = DanmuSettingsState.palette(),
        )
    }

    fun setRandomColor(enabled: Boolean) {
        DanmuSettingsState.randomColor = enabled
        refresh()
    }

    fun addColor(hex: String) {
        val normalized = normalizeHex(hex)
        if (normalized == null) {
            _toastEvent.value = str(R.string.danmu_color_invalid)
            return
        }
        val current = DanmuSettingsState.palette()
        if (current.contains(normalized)) return
        DanmuSettingsState.setPalette(current + normalized)
        refresh()
    }

    fun removeColor(hex: String) {
        val current = DanmuSettingsState.palette().filter { it != hex }
        DanmuSettingsState.setPalette(current)
        refresh()
    }

    fun resetPalette() {
        DanmuSettingsState.setPalette(emptyList())
        refresh()
    }

    fun applyGradient(startHex: String, endHex: String, steps: Int = 16) {
        val start = normalizeHex(startHex)
        val end = normalizeHex(endHex)
        if (start == null || end == null) {
            _toastEvent.value = str(R.string.danmu_color_invalid)
            return
        }
        val s = channels(start)
        val e = channels(end)
        val colors = (0 until steps).map { i ->
            val t = if (steps <= 1) 0f else i.toFloat() / (steps - 1)
            val r = (s[0] + (e[0] - s[0]) * t).toInt()
            val g = (s[1] + (e[1] - s[1]) * t).toInt()
            val b = (s[2] + (e[2] - s[2]) * t).toInt()
            "#%02X%02X%02X".format(r, g, b)
        }
        DanmuSettingsState.setPalette(colors)
        refresh()
    }

    fun exportText(): String = DanmuSettingsState.palette().joinToString(",")

    fun importPalette(text: String) {
        val found = Regex("#[0-9a-fA-F]{6}").findAll(text)
            .map { it.value.uppercase() }
            .distinct()
            .toList()
        if (found.isEmpty()) {
            _toastEvent.value = str(R.string.danmu_color_import_fail)
            return
        }
        DanmuSettingsState.setPalette(found)
        _toastEvent.value = str(R.string.danmu_color_imported)
        refresh()
    }

    private fun normalizeHex(hex: String): String? {
        var value = hex.trim()
        if (!value.startsWith("#")) value = "#$value"
        return if (DanmuSettingsState.isValidHex(value)) value.uppercase() else null
    }

    private fun channels(hex: String): IntArray {
        val v = hex.removePrefix("#")
        return intArrayOf(
            v.substring(0, 2).toInt(16),
            v.substring(2, 4).toInt(16),
            v.substring(4, 6).toInt(16),
        )
    }
}
