package com.abrah.nightmare

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.abrah.nightmare.canvas.ResultsStore
import com.abrah.nightmare.canvas.defaultWorkflow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Temporary importer for the side-by-side fork test APK only.
 *
 * The user explicitly shares one MP4 from another app. Android grants this
 * activity read access to that content URI; the bytes are copied into this
 * fork's private cache and then ResultsStore makes its own private Results copy.
 * No storage permission, broad file access, or network access is added.
 */
class ForkVideoImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action != Intent.ACTION_SEND ||
            intent.type?.startsWith("video/") != true ||
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
        ) {
            finishWithMessage("Share one video to import it into Nightmare Fork Test.")
            return
        }
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        if (uri == null) {
            finishWithMessage("The shared video did not include a readable file.")
            return
        }

        Thread {
            val message = runCatching { importVideo(uri) }
                .fold(
                    onSuccess = { "Video imported into this fork's private Results." },
                    onFailure = { "Video import failed: ${it.message ?: it.javaClass.simpleName}" },
                )
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun importVideo(uri: Uri) {
        val resolver = contentResolver
        val mime = resolver.getType(uri) ?: intent.type.orEmpty()
        if (!mime.equals("video/mp4", ignoreCase = true)) {
            throw IllegalArgumentException("For this test, share an MP4 video (video/mp4).")
        }

        val displayName = resolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        if (displayName.isNotBlank() && !displayName.endsWith(".mp4", ignoreCase = true)) {
            throw IllegalArgumentException("The shared file is not named as an MP4.")
        }

        if (checkUriPermission(
                uri,
                android.os.Process.myPid(),
                android.os.Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Android did not grant this app read access to the shared video.")
        }

        val staging = File(cacheDir, "fork-video-import-${UUID.randomUUID()}.mp4")
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Android could not open the shared video.")
            input.use { source ->
                FileOutputStream(staging).use { target ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_VIDEO_BYTES) {
                            throw IllegalArgumentException("The test importer limit is 4 GB.")
                        }
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                }
            }
            if (!staging.isFile || staging.length() < 1024L) {
                throw IllegalArgumentException("The shared video is empty or incomplete.")
            }

            val retriever = MediaMetadataRetriever()
            val poster: Bitmap
            try {
                retriever.setDataSource(staging.absolutePath)
                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                poster = retriever.getFrameAtTime(
                    0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: throw IllegalArgumentException("Android could not decode a video frame.")
                if (duration <= 0L || poster.width <= 0 || poster.height <= 0) {
                    poster.recycle()
                    throw IllegalArgumentException("The shared file is not a valid playable video.")
                }
            } finally {
                retriever.release()
            }

            try {
                val required = staging.length() + 128L * 1024L * 1024L
                if (cacheDir.usableSpace < required) {
                    throw IllegalStateException("Not enough free space to safely copy the video into private Results.")
                }
                val store = ResultsStore(File(filesDir, "results"))
                val result = store.keep(
                    bitmap = poster,
                    imageId = null,
                    workflow = defaultWorkflow(),
                    types = emptyMap(),
                    seed = null,
                    model = null,
                    prompt = "Imported test video: ${displayName.ifBlank { "shared MP4" }}",
                    video = staging,
                )
                val savedClip = store.clipFile(result.id)
                if (savedClip == null || !savedClip.isFile || savedClip.length() != staging.length()) {
                    store.delete(result.id)
                    throw IllegalStateException("The private Results video copy did not verify.")
                }
            } finally {
                poster.recycle()
            }
        } finally {
            staging.delete()
        }
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val MAX_VIDEO_BYTES = 4L * 1024L * 1024L * 1024L
    }
}
