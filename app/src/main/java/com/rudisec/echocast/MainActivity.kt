package com.rudisec.echocast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.rudisec.echocast.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            showPermissionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setSupportActionBar(binding.toolbar)
            supportActionBar?.title = ""

            prefs = Preferences(this)
            DebugLog.verbose = prefs.verboseLogging
            DebugLog.i("Main", "onCreate v${BuildConfig.VERSION_NAME}")

            loadLogo()
            setupViewPager()
            checkPermissions()

            // Diagnostics: long-press the logo.
            binding.logoImageView.isLongClickable = true
            binding.logoImageView.setOnLongClickListener {
                startActivity(Intent(this, DebugActivity::class.java))
                true
            }
        } catch (e: Exception) {
            DebugLog.e("Main", "Error in onCreate", e)
            Toast.makeText(this, "Error: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        handleViewIntent(intent)
    }

    /**
     * Automation entry point (Tasker / MacroDroid / adb):
     *
     *   am start -a android.intent.action.VIEW
     *      -d "file:///sdcard/Download/greeting.ogg" -t "audio/star"
     *      -p com.rudisec.echocast --grant-read-uri-permission
     *
     * (replace the -t value with a real audio mime type)
     * The file is copied into the app folder, added to the soundboard and
     * played immediately.
     */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri: Uri = intent.data ?: return
        val mime = intent.type
        if (mime != null && !mime.startsWith("audio/")) {
            DebugLog.w("Main", "VIEW ignored, mime=$mime")
            return
        }

        try {
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "audio"
            val local = copyToAppStorage(uri, name) ?: run {
                Toast.makeText(this, getString(R.string.view_intent_failed), Toast.LENGTH_LONG).show()
                return
            }
            val item = AudioItem.create(Uri.fromFile(local), name.substringBeforeLast("."))
            AudioPlaylist(this).addItem(item)
            sendBroadcast(Intent("com.rudisec.echocast.AUDIO_ADDED").setPackage(packageName))
            PlaybackService.play(this, item, prefs.playMode)
            DebugLog.i("Main", "VIEW play '${item.name}' from $uri")
        } catch (e: Exception) {
            DebugLog.e("Main", "VIEW intent failed", e)
            Toast.makeText(this, getString(R.string.view_intent_failed), Toast.LENGTH_LONG).show()
        } finally {
            // Consume the intent so a rotation does not replay it.
            intent.action = null
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()

    private fun copyToAppStorage(uri: Uri, name: String): File? = runCatching {
        val ext = name.substringAfterLast(".", "mp3")
        val base = name.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9 _-]"), "")
            .ifBlank { "audio" }
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "${base}_${System.currentTimeMillis()}.$ext")
        val input: InputStream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot open $uri")
        input.use { source ->
            FileOutputStream(file).use { output -> source.copyTo(output) }
        }
        file
    }.onFailure { DebugLog.e("Main", "copy failed for $uri", it) }.getOrNull()

    private fun loadLogo() {
        try {
            val inputStream: InputStream = assets.open("Logo.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap != null) {
                binding.logoImageView.setImageBitmap(bitmap)
            } else {
                binding.logoImageView.visibility = android.view.View.GONE
            }
            inputStream.close()
        } catch (e: Exception) {
            DebugLog.w("Main", "logo not loaded: ${e.message}")
            binding.logoImageView.visibility = android.view.View.GONE
        }
    }

    private fun setupViewPager() {
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_audio_player)
                1 -> getString(R.string.tab_ai)
                else -> ""
            }
        }.attach()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    showPermissionDialog()
                } else {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permissions_required)
            .setMessage(R.string.notification_permission_required)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> AudioPlayerFragment()
                1 -> AiFragment()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
}
