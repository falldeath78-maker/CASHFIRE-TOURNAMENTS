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
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var credentialManager: CredentialManager
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

        swipeRefresh = SwipeRefreshLayout(this)
        swipeRefresh.isEnabled = false // Pull-to-refresh disabled
        web = WebView(this)
        swipeRefresh.addView(web)
        rootLayout.addView(swipeRefresh, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        setContentView(rootLayout)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        web.overScrollMode = android.view.View.OVER_SCROLL_NEVER
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
        web.addJavascriptInterface(GoogleAuthInterface(), "AndroidGoogleAuth")

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

                // Google/Firebase auth URLs ko Chrome mein launch na karein.
                // APK ka Google login native Credential Manager bridge se hona chahiye.
                // Agar koi auth URL accidentally navigate ho, use WebView ke andar hi rakhein.
                if (uri.host == "accounts.google.com" ||
                    uri.host == "hnt-tournaments.firebaseapp.com" ||
                    (uri.host == "hnt-tournaments.web.app" && uri.path?.startsWith("/__/auth/handler") == true)) {
                    return false
                }

                // Internal links WebView mein kholo
                if (scheme == "http" || scheme == "https") {
                    if (keepInsideApp || isInternal(uri.host)) return false
                }

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

        // Deep Link Check
        if (intent?.data != null) {
            web.loadUrl(intent.data.toString())
        } else if (savedInstanceState == null) {
            web.loadUrl(startUrl)
        } else {
            web.restoreState(savedInstanceState)
        }

        initGoogleSignIn()
    }

    private fun initGoogleSignIn() {
        credentialManager = CredentialManager.create(this)
    }

    inner class GoogleAuthInterface {
        @JavascriptInterface
        fun signInWithGoogle(webClientId: String) {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false) 
                .setServerClientId(webClientId) 
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = credentialManager.getCredential(
                        context = this@MainActivity,
                        request = request
                    )
                    handleSignInResult(result)
                } catch (e: GetCredentialException) {
                    runOnUiThread {
                        web.evaluateJavascript("onGoogleSignInError('${e.message}')", null)
                    }
                }
            }
        }
    }

    private fun handleSignInResult(result: androidx.credentials.GetCredentialResponse) {
        val credential = result.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                runOnUiThread {
                    web.evaluateJavascript("onGoogleSignInSuccess('$idToken')", null)
                }
            } catch (e: GoogleIdTokenParsingException) {
                runOnUiThread {
                    web.evaluateJavascript("onGoogleSignInError('Token parsing failed')", null)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val data = intent?.data
        if (data != null) {
            if (data.scheme == "cashfire") {
                web.loadUrl("https://hnt-tournaments.web.app/")
            } else {
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
