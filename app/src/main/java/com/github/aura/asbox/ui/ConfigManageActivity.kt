package com.github.aura.asbox.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import com.github.aura.asbox.R
import com.github.aura.asbox.ui.base.BaseActivity
import com.github.aura.asbox.io.cancelLocalConfig
import com.github.aura.asbox.io.handleLocalConfigBatchResult
import com.github.aura.asbox.io.handleLocalConfigResult
import com.github.aura.asbox.io.handleLocalSourceFolderResult
import com.github.aura.asbox.io.handleLocalSourceTreeResult
import com.github.aura.asbox.io.importLocalSource
import com.github.aura.asbox.io.isLocalSpiderName
import com.github.aura.asbox.io.startLocalConfig
import com.github.aura.asbox.io.startLocalConfigBatch
import com.github.aura.asbox.io.startLocalSourceFolderImport
import com.github.aura.asbox.ui.components.SheetHostScaffold
import com.github.aura.asbox.api.AppBootstrap
import com.github.aura.asbox.ui.page.ConfigManageScreen
import com.github.aura.asbox.ui.page.ConfigManageViewModel
import com.github.aura.asbox.ui.theme.AVBoxTheme
import com.github.aura.asbox.ui.theme.enableTransparentEdgeToEdge
import com.github.aura.asbox.util.AppLog
import com.github.aura.asbox.util.PermissionHelper
import com.github.aura.asbox.util.SpiderStorageNotice

class ConfigManageActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ConfigManageActivity::class.java))
        }
    }

    private val configVm by lazy {
        ViewModelProvider(this, ConfigManageViewModel.defaultFactory())[ConfigManageViewModel::class.java]
    }

    private val localConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) settleUnreachableSource(uri) }
            } else {
                cancelLocalConfig()
            }
        }

    private val sourceTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleLocalSourceTreeResult(this, uri)
        }

    fun launchLocalConfig(allowSpider: Boolean, onResult: (name: String, api: String) -> Unit) {
        startLocalConfig(localConfigLauncher, allowSpider) { name, api -> onResult(name, api) }
    }

    private val localConfigBatchLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            handleLocalConfigBatchResult(this, uris)
        }

    private val sourceFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            handleLocalSourceFolderResult(this, uri)
        }

    /** 片源页签批量导入：多选文件，逐个进片源线；onEach 返回已存在与否，onDone 汇总跳过数。 */
    fun launchLocalConfigBatch(
        allowSpider: Boolean,
        onEach: (name: String, api: String) -> Boolean,
        onDone: (skipped: Int) -> Unit,
    ) {
        startLocalConfigBatch(localConfigBatchLauncher, allowSpider, onEach, onDone)
    }

    /** 片源页签批量导入：选整个文件夹，收直属的 .py/.js 子文件。 */
    fun launchSourceFolderImport(
        onEach: (name: String, api: String) -> Boolean,
        onDone: (skipped: Int) -> Unit,
    ) {
        startLocalSourceFolderImport(sourceFolderLauncher, onEach, onDone)
    }

    private fun pickedDisplayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
        } catch (ignored: Throwable) {
        }
        return name ?: uri.lastPathSegment ?: ""
    }

    private fun launchSourceTree() {
        SpiderStorageNotice.suppress()
        sourceTreeLauncher.launch(null)
    }

    private fun settleUnreachableSource(uri: Uri) {
        if (PermissionHelper.isStorageGranted(this)) {
            launchSourceTree()
            return
        }
        PermissionHelper.requestStorage(this) { granted, _ ->
            if (granted.isNullOrEmpty()) {
                launchSourceTree()
            } else {
                handleLocalConfigResult(this, uri) { needTree -> if (needTree) launchSourceTree() }
            }
        }
    }

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppBootstrap.start()
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leavePage()
            }
        })
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    ConfigManageScreen(onNavigateBack = { leavePage() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        importLocalSource(
            this,
            uri,
            onImported = { name, api ->
                // 外部打开的文件按种类分流：.py/.js 片源进「片源」线，本地接口等其他文件仍进点播。
                val existed = if (isLocalSpiderName(pickedDisplayName(uri))) {
                    configVm.addLocalLineSource(name, api)
                } else {
                    configVm.addLocalSource(name, api)
                }
                if (existed) {
                    Toast.makeText(this, R.string.toast_local_source_already_imported, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_local_source_imported, name), Toast.LENGTH_SHORT).show()
                }
            },
            onFailed = { unsupported ->
                val tip = if (unsupported) {
                    R.string.toast_local_source_unsupported
                } else {
                    R.string.toast_local_config_read_failed
                }
                Toast.makeText(this, getString(tip), Toast.LENGTH_SHORT).show()
            },
        )
    }

    private fun inForeignTask(): Boolean {
        val tasks = try {
            (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.appTasks
        } catch (th: Throwable) {
            AppLog.e("ConfigManageActivity", th)
            null
        } ?: return false
        return tasks.none { it.taskInfo?.taskId == taskId }
    }

    private fun leavePage() {
        if (inForeignTask()) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
        finish()
    }
}
