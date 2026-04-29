package com.deniscerri.ytdl.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.core.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SetupActivity : BaseActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var stepText: TextView

    companion object {
        const val SETUP_DONE_KEY = "setup_complete"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val ytdlpBin = File(
            File(File(noBackupFilesDir, RuntimeManager.BASENAME), RuntimeManager.ytdlpDirName),
            RuntimeManager.ytdlpBin
        )

        // Setup already done and yt-dlp script exists — go straight to app
        if (prefs.getBoolean(SETUP_DONE_KEY, false) && ytdlpBin.exists()) {
            goToMain()
            return
        }

        // Reset flag if binary missing
        prefs.edit().putBoolean(SETUP_DONE_KEY, false).apply()

        setContentView(R.layout.activity_setup)
        statusText  = findViewById(R.id.setup_status)
        progressBar = findViewById(R.id.setup_progress)
        progressText = findViewById(R.id.setup_progress_text)
        stepText    = findViewById(R.id.setup_step)

        startSetup()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main flow
    // ─────────────────────────────────────────────────────────────────────────

    private fun startSetup() {
        lifecycleScope.launch {
            try {
                // Only download the yt-dlp SCRIPT (Python script — not a native binary)
                // Python is bundled inside the APK's jniLibs as libpython.so
                // PackageBase.init() handles extracting it from nativeLibraryDir automatically
                downloadYtDlpScript()

                PreferenceManager.getDefaultSharedPreferences(this@SetupActivity)
                    .edit().putBoolean(SETUP_DONE_KEY, true).apply()

                goToMain()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text  = "Setup failed:\n${e.message}"
                    stepText.text    = "Check your internet connection and restart."
                    progressBar.visibility  = View.GONE
                    progressText.visibility = View.GONE
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download yt-dlp Python SCRIPT from GitHub
    //
    // This is just a Python script file — NOT a native binary.
    // Android SDK 28+ blocks executing downloaded native binaries,
    // but Python scripts are just text files — fully allowed.
    // Python itself is bundled in the APK as libpython.so (jniLibs).
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun downloadYtDlpScript() {
        updateUI("Fetching latest yt-dlp…", "", 0)

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        // 1. Get latest release info
        val releaseJson = withContext(Dispatchers.IO) {
            val response = client.newCall(
                Request.Builder()
                    .url("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).execute()
            if (!response.isSuccessful) throw Exception("GitHub API error: HTTP ${response.code}")
            response.body!!.string()
        }

        val json        = JSONObject(releaseJson)
        val tagName     = json.getString("tag_name")
        val releaseName = json.optString("name", tagName)

        // 2. Find the plain "yt-dlp" asset — this is the Python script (no extension)
        //    NOT yt-dlp_linux / yt-dlp_linux_aarch64 — those are native binaries (blocked on Android)
        val assets = json.getJSONArray("assets")
        var downloadUrl: String? = null
        var totalSize = 0L

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            // Exact match — "yt-dlp" with no suffix = the Python script
            if (asset.getString("name") == "yt-dlp") {
                downloadUrl = asset.getString("browser_download_url")
                totalSize   = asset.getLong("size")
                break
            }
        }

        if (downloadUrl == null)
            throw Exception("yt-dlp script not found in release assets")

        updateUI("Downloading yt-dlp script…", "Version: $tagName", 0)

        // 3. Download to final path
        val ytdlpDir = File(
            File(noBackupFilesDir, RuntimeManager.BASENAME),
            RuntimeManager.ytdlpDirName
        ).apply { mkdirs() }

        val ytdlpBin = File(ytdlpDir, RuntimeManager.ytdlpBin)

        downloadWithRetry(client, downloadUrl, totalSize, ytdlpBin)

        // 4. Save version — same keys as YTDLUpdater so Settings screen shows correct version
        PreferenceManager.getDefaultSharedPreferences(this).edit().apply {
            putString("dlpVersion", tagName)
            putString("dlpVersionName", releaseName)
            apply()
        }

        updateUI("yt-dlp ready ✓", "Version: $tagName", 100)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download with 3 retries + progress
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun downloadWithRetry(
        client: OkHttpClient,
        url: String,
        totalSize: Long,
        dest: File
    ) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                withContext(Dispatchers.IO) {
                    val response = client.newCall(
                        Request.Builder().url(url).build()
                    ).execute()

                    if (!response.isSuccessful)
                        throw Exception("HTTP ${response.code}")

                    var downloaded = 0L
                    response.body!!.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                downloaded += read
                                val pct = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else 0
                                withContext(Dispatchers.Main) {
                                    progressBar.progress = pct
                                    progressText.text    = "$pct%"
                                }
                            }
                        }
                    }

                    if (dest.length() == 0L)
                        throw Exception("Downloaded file is empty")
                }
                return // success
            } catch (e: Exception) {
                lastError = e
                dest.delete()
                if (attempt < 2) {
                    withContext(Dispatchers.Main) {
                        statusText.text      = "Retrying… (attempt ${attempt + 2}/3)"
                        progressBar.progress = 0
                        progressText.text    = "0%"
                    }
                    delay(2000)
                }
            }
        }
        throw lastError ?: Exception("Download failed after 3 attempts")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun updateUI(status: String, step: String, progress: Int) {
        withContext(Dispatchers.Main) {
            statusText.text      = status
            stepText.text        = step
            progressBar.progress = progress
            progressText.text    = "$progress%"
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
