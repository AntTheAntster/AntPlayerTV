package uk.anttheantster.antplayertv.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat

object UpdateInstaller {

    /**
     * Starts downloading the APK from [url] using DownloadManager, then
     * opens the installer when the download completes.
     */
    fun downloadAndInstall(
        context: Context,
        url: String,
        onStarted: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (url.isBlank()) {
            onError?.invoke("Empty APK URL")
            return
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("AntPlayer TV Update")
            .setDescription("Downloading update...")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        // (Optional) If you want to restrict to Wi-Fi only, comment above and use:
        // .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)

        val downloadId = dm.enqueue(request)
        onStarted?.invoke()

        val appContext = context.applicationContext

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
                if (id != downloadId) return

                // Unregister receiver
                appContext.unregisterReceiver(this)

                val uri: Uri? = dm.getUriForDownloadedFile(downloadId)
                if (uri == null) {
                    onError?.invoke("Download failed")
                    Toast.makeText(appContext, "Update download failed", Toast.LENGTH_LONG).show()
                    return
                }

                // Launch the system package installer
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    appContext.startActivity(installIntent)
                } catch (e: Exception) {
                    onError?.invoke("Unable to start installer: ${e.message}")
                    Toast.makeText(
                        appContext,
                        "Unable to start installer. Check unknown sources settings.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
