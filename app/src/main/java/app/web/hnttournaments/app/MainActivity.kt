package app.web.hnttournaments.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val startUrl = "https://hnt-tournaments.web.app/"
    private val allowedHost = "hnt-tournaments.web.app"
    private val keepInsideApp = false

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val clipData = data?.clipData
            var uris: Array<Uri>? = null
            
            if (clipData != null) {
                uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else if (data?.data != null) {
                uris = arrayOf(data.data!!)
            }
            fileCallback?.onReceiveValue(uris)
        } else {
            fileCallback?.onReceiveValue(null)
        }
        fileCallback = null
    }

    private fun isInternal(host: String?): Boolean {
        if (host == null) return false
        return host == allowedHost || host.endsWith("." + allowedHost)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(Color.parseColor("#4F63FF"))

           // Pull-to-refresh ko band karne ke liye
        swipeRefresh = SwipeRefreshLayout(this)
        swipeRefresh.isEnabled = false // 👈 Ye line refresh ko band karegi
        
        web = WebView(this)
        swipeRefresh.addView(web)
        rootLayout.addView(swipeRefresh, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        
        // Scroll karte waqt aane wale blue/white glow ko hatane ke liye
        web.overScrollMode = android.view.View.OVER_SCROLL_NEVER
        setContentView(rootLayout)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = userAgentString.replace("Android", "Mobile")
        }

        web.addJavascriptInterface(WebAppInterface(), "AndroidDownloader")

        

        web.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {}
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val url = uri.toString()
                val scheme = uri.scheme

                // 1. Google Login ko Chrome Custom Tab mein kholo
                if (url.contains("accounts.google.com") || url.contains("hnt-tournaments.firebaseapp.com")) {
                    try {
                        val builder = CustomTabsIntent.Builder()
                        val customTabsIntent = builder.build()
                        customTabsIntent.launchUrl(this@MainActivity, uri)
                    } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    return true 
                }

                // 2. Internal links WebView mein kholo
                if (scheme == "http" || scheme == "https") {
                    if (keepInsideApp || isInternal(uri.host)) return false
                }

                // 3. Baqi external links browser mein kholo
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                
                web.evaluateJavascript("""
                    (function() {
                        if (window.hasDownloaderInstalled) return;
                        window.hasDownloaderInstalled = true;
                        document.addEventListener('click', function(e) {
                            var target = e.target.closest('a');
                            if (target && target.hasAttribute('download') && target.href.startsWith('blob:')) {
                                e.preventDefault();
                                fetch(target.href)
                                    .then(r => r.blob())
                                    .then(blob => {
                                        var reader = new FileReader();
                                        reader.onload = function() {
                                            var base64 = reader.result.split(',')[1];
                                            var filename = target.getAttribute('download') || 'tooldeck_download';
                                            AndroidDownloader.downloadBase64(filename, base64);
                                        };
                                        reader.readAsDataURL(blob);
                                    });
                            }
                        });
                    })();
                """, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadUrl("file:///android_asset/offline.html")
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                
                if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                return try {
                    chooser.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        // --- DEEP LINK CHECK (Yeh naya add kiya hai) ---
        if (intent?.data != null) {
            web.loadUrl(intent.data.toString())
        } else if (savedInstanceState == null) {
            web.loadUrl(startUrl)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

      // --- NAYA FUNCTION: Chrome se wapas aane par trigger hota hai ---
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val data = intent?.data
        if (data != null) {
            // Agar custom scheme (cashfire://) se aaya hai, toh website dobara load karo
            if (data.scheme == "cashfire") {
                web.loadUrl("https://hnt-tournaments.web.app/")
            } else {
                // Agar koi aur link hai toh wo load karo
                web.loadUrl(data.toString())
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun downloadBase64(filename: String, base64Data: String) {
            try {
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        val outputStream = contentResolver.openOutputStream(uri)
                        outputStream?.write(decodedBytes)
                        outputStream?.close()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val file = File(downloadsDir, filename)
                    val fos = FileOutputStream(file)
                    fos.write(decodedBytes)
                    fos.close()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
