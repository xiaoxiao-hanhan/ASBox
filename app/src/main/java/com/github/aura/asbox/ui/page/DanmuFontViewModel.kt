package com.github.aura.asbox.ui.page

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.aura.asbox.R
import android.os.Environment
import com.github.aura.asbox.util.AppContextHolder
import com.github.aura.asbox.util.KV
import com.github.aura.asbox.util.KVKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DanmuFontItem(
    val name: String,
    val path: String,
)

data class DanmuFontPreset(
    val nameRes: Int,
    val fileName: String,
    val url: String,
)

data class DanmuFontUiState(
    val familyIndex: Int = 0,
    val fontPath: String = "",
    val localFonts: List<DanmuFontItem> = emptyList(),
    val downloaded: Set<String> = emptySet(),
    val busyFile: String? = null,
)

class DanmuFontViewModel : ViewModel() {

    companion object {
        val FAMILIES = listOf("", "sans-serif", "serif", "monospace")

        val PRESETS = listOf(
            DanmuFontPreset(
                R.string.danmu_font_preset_noto,
                "NotoSansCJKsc-Regular.otf",
                "https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Regular.otf",
            ),
            DanmuFontPreset(
                R.string.danmu_font_preset_noto_bold,
                "NotoSansCJKsc-Bold.otf",
                "https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Bold.otf",
            ),
            DanmuFontPreset(
                R.string.danmu_font_preset_lxgw,
                "LXGWWenKai-Regular.ttf",
                "https://github.com/lxgw/LxgwWenKai/raw/main/fonts/TTF/LXGWWenKai-Regular.ttf",
            ),
        )
    }

    private val _state = MutableStateFlow(DanmuFontUiState())
    val state: StateFlow<DanmuFontUiState> = _state.asStateFlow()

    private val _toastEvent = MutableStateFlow<String?>(null)
    val toastEvent = _toastEvent.asStateFlow()

    init {
        refresh()
    }

    private fun str(resId: Int): String = AppContextHolder.context()?.getString(resId) ?: ""

    fun clearToast() {
        _toastEvent.value = null
    }

    fun fontsDir(): File = File(Environment.getExternalStorageDirectory(), "AVBox/fonts")

    fun refresh() {
        val dir = fontsDir()
        val fonts = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".ttf", true) || f.name.endsWith(".otf", true))
        }?.sortedBy { it.name.lowercase() }?.map { DanmuFontItem(it.name, it.absolutePath) }
            ?: emptyList()
        val family = KV.get(KVKeys.DANMU_FONT_FAMILY, "")
        _state.value = DanmuFontUiState(
            familyIndex = FAMILIES.indexOf(family).takeIf { it >= 0 } ?: 0,
            fontPath = KV.get(KVKeys.DANMU_FONT_PATH, ""),
            localFonts = fonts,
            downloaded = fonts.map { it.name }.toSet(),
            busyFile = _state.value.busyFile,
        )
    }

    fun selectFamily(index: Int) {
        val family = FAMILIES.getOrElse(index) { "" }
        KV.put(KVKeys.DANMU_FONT_FAMILY, family)
        KV.delete(KVKeys.DANMU_FONT_PATH)
        _toastEvent.value = str(R.string.danmu_font_applied)
        refresh()
    }

    fun selectLocal(item: DanmuFontItem) {
        KV.put(KVKeys.DANMU_FONT_PATH, item.path)
        _toastEvent.value = str(R.string.danmu_font_applied)
        refresh()
    }

    fun deleteLocal(item: DanmuFontItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    File(item.path).delete()
                } catch (e: Exception) {
                    // ignore
                }
                if (KV.get(KVKeys.DANMU_FONT_PATH, "") == item.path) {
                    KV.delete(KVKeys.DANMU_FONT_PATH)
                }
            }
            refresh()
        }
    }

    fun importFont(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val context = AppContextHolder.context() ?: return@withContext false
                    val dir = fontsDir()
                    dir.mkdirs()
                    var displayName = "font_${System.currentTimeMillis()}.ttf"
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) {
                                cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let {
                                    displayName = it
                                }
                            }
                        }
                    }
                    val target = File(dir, displayName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    } ?: return@withContext false
                    KV.put(KVKeys.DANMU_FONT_PATH, target.absolutePath)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            _toastEvent.value = str(
                if (ok) R.string.danmu_font_applied else R.string.danmu_font_download_fail,
            )
            refresh()
        }
    }

    fun download(preset: DanmuFontPreset) {
        if (_state.value.busyFile != null) return
        _state.value = _state.value.copy(busyFile = preset.fileName)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val dir = fontsDir()
                    dir.mkdirs()
                    val target = File(dir, preset.fileName)
                    val conn = URL(preset.url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "AVBox")
                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        return@withContext false
                    }
                    conn.inputStream.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    conn.disconnect()
                    KV.put(KVKeys.DANMU_FONT_PATH, target.absolutePath)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            _state.value = _state.value.copy(busyFile = null)
            _toastEvent.value = str(
                if (ok) R.string.danmu_font_download_ok else R.string.danmu_font_download_fail,
            )
            refresh()
        }
    }
}
