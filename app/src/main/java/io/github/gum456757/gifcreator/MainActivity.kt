package io.github.gum456757.gifcreator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: android.view.View
    private val downloadBridge = DownloadBridge()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var mainFrameFailed = false
    private var pendingDownload: PendingDownload? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback
            fileCallback = null
            val uris = if (result.resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            }
            callback?.onReceiveValue(uris)
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingDownload
            pendingDownload = null
            if (!granted || pending == null) {
                if (pending != null) toast(R.string.storage_denied)
                return@registerForActivityResult
            }
            publish(pending.bytes, pending.fileName, pending.mime)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web)
        refresh = findViewById(R.id.refresh)
        progress = findViewById(R.id.progress)
        errorPanel = findViewById(R.id.errorPanel)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupWebView()
        refresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.teal))
        refresh.setOnRefreshListener { webView.reload() }
        refresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        findViewById<android.view.View>(R.id.retryButton).setOnClickListener {
            errorPanel.isVisible = false
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.navy))
        webView.addJavascriptInterface(downloadBridge, "GifDownload")
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppChromeClient()
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("blob:") -> {
                    val quotedUrl = org.json.JSONObject.quote(url)
                    val quotedName = org.json.JSONObject.quote(guessFileName(contentDisposition, mimeType))
                    webView.evaluateJavascript(
                        "window.__gifSaveBlobUrl && window.__gifSaveBlobUrl($quotedUrl, $quotedName)",
                        null
                    )
                }
                url.startsWith("http://") || url.startsWith("https://") -> enqueueRemoteDownload(url, contentDisposition, mimeType)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            offscreenPreRaster = true
            userAgentString = "$userAgentString SmartGifApp/1.0"
        }

        installEarlyHook()
    }

    private fun installEarlyHook() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = assets.open("download-hook.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            script,
            setOf("https://gum456757.github.io")
        )
    }

    private fun injectHookFallback() {
        val script = assets.open("download-hook.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript(script, null)
    }

    private fun showError() {
        refresh.isRefreshing = false
        progress.isVisible = false
        errorPanel.isVisible = true
    }

    private fun enqueueRemoteDownload(url: String, contentDisposition: String?, mimeType: String?) {
        val fileName = guessFileName(contentDisposition, mimeType)
        val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType(mimeType ?: "application/octet-stream")
            addRequestHeader("User-Agent", webView.settings.userAgentString)
        }
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            manager.enqueue(request)
        } catch (error: Exception) {
            Log.w(TAG, "Remote download failed", error)
            toast(R.string.save_failed)
        }
    }

    private fun guessFileName(contentDisposition: String?, mimeType: String?): String {
        val fromHeader = contentDisposition
            ?.substringAfter("filename=", "")
            ?.trim()
            ?.trim('"')
            .orEmpty()
        val fallback = if (mimeType?.contains("gif") == true) "animation.gif" else "download"
        return safeFileName(fromHeader.ifBlank { fallback })
    }

    private fun safeFileName(raw: String): String {
        val cleaned = raw.substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .take(80)
        if (cleaned.isBlank()) return "animation.gif"
        return if (cleaned.contains('.')) cleaned else "$cleaned.gif"
    }

    private fun publish(bytes: ByteArray, fileName: String, mime: String) {
        if (isDestroyed) return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = PendingDownload(bytes, fileName, mime)
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val uri = try {
            writeDownload(bytes, fileName, mime)
        } catch (_: IOException) {
            null
        }
        if (uri == null) {
            toast(R.string.save_failed)
            return
        }
        Snackbar.make(webView, R.string.saved, Snackbar.LENGTH_LONG)
            .setAction(R.string.open) { openSaved(uri, mime) }
            .show()
    }

    private fun writeDownload(bytes: ByteArray, fileName: String, mime: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri
        } else {
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!directory.exists() && !directory.mkdirs()) return null
            val file = uniqueFile(directory, fileName)
            file.outputStream().use { it.write(bytes) }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var index = 1
        while (true) {
            val next = File(directory, "$stem ($index)$extension")
            if (!next.exists()) return next
            index += 1
        }
    }

    private fun openSaved(uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.open)))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.open_failed)
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.open_failed)
        }
    }

    private fun toast(message: Int) {
        if (isDestroyed) return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private inner class AppWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            mainFrameFailed = false
            errorPanel.isVisible = false
            progress.isVisible = true
            progress.progress = 8
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            refresh.isRefreshing = false
            progress.isVisible = false
            if (!mainFrameFailed) errorPanel.isVisible = false
            injectHookFallback()
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                mainFrameFailed = true
                showError()
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            val scheme = uri.scheme.orEmpty()
            if (scheme == "blob" || scheme == "data") return false
            if (scheme == "https" && uri.host == ALLOWED_HOST) return false
            if (request.isForMainFrame) openExternal(uri)
            return true
        }
    }

    private inner class AppChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress.progress = newProgress
            progress.isVisible = newProgress in 1..99
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val intent = try {
                fileChooserParams?.createIntent()
            } catch (_: Exception) {
                null
            } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                filePathCallback?.onReceiveValue(null)
                toast(R.string.chooser_missing)
                false
            }
        }
    }

    private data class PendingDownload(val bytes: ByteArray, val fileName: String, val mime: String)

    inner class DownloadBridge {
        private val lock = Any()
        private val buffer = ByteArrayOutputStream()
        private var fileName = "animation.gif"
        private var mime = "image/gif"
        private var rejected = false

        @JavascriptInterface
        fun start(name: String, mimeType: String, size: Int) {
            synchronized(lock) {
                rejected = size > MAX_BYTES
                buffer.reset()
                fileName = safeFileName(name)
                mime = mimeType.ifBlank { "image/gif" }
            }
            if (size > MAX_BYTES) mainHandler.post { toast(R.string.too_large) }
        }

        @JavascriptInterface
        fun chunk(base64: String) {
            synchronized(lock) {
                if (rejected || base64.isEmpty()) return
                buffer.write(Base64.decode(base64, Base64.DEFAULT))
            }
        }

        @JavascriptInterface
        fun finish() {
            val payload = synchronized(lock) {
                if (rejected) return
                val bytes = buffer.toByteArray()
                buffer.reset()
                Triple(bytes, fileName, mime)
            }
            if (payload.first.isEmpty()) {
                mainHandler.post { toast(R.string.save_failed) }
                return
            }
            mainHandler.post { publish(payload.first, payload.second, payload.third) }
        }

        @JavascriptInterface
        fun fail(message: String) {
            Log.w(TAG, "GIF download failed: $message")
            mainHandler.post { toast(R.string.save_failed) }
        }

        @JavascriptInterface
        fun saveDataUrl(name: String, dataUrl: String) {
            val comma = dataUrl.indexOf(',')
            if (comma <= 0) {
                mainHandler.post { toast(R.string.save_failed) }
                return
            }
            val meta = dataUrl.substring(5, comma)
            val payload = dataUrl.substring(comma + 1)
            val mimeType = meta.substringBefore(';').ifBlank { "image/gif" }
            val bytes = try {
                if (meta.contains("base64")) Base64.decode(payload, Base64.DEFAULT) else payload.toByteArray()
            } catch (_: IllegalArgumentException) {
                mainHandler.post { toast(R.string.save_failed) }
                return
            }
            if (bytes.size > MAX_BYTES) {
                mainHandler.post { toast(R.string.too_large) }
                return
            }
            val target = safeFileName(name)
            mainHandler.post { publish(bytes, target, mimeType) }
        }
    }

    companion object {
        private const val TAG = "SmartGif"
        private const val HOME_URL = "https://gum456757.github.io/gif-creator/"
        private const val ALLOWED_HOST = "gum456757.github.io"
        private const val MAX_BYTES = 64 * 1024 * 1024
    }
}
