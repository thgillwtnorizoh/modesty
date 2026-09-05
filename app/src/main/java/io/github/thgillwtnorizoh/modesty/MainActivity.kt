package io.github.thgillwtnorizoh.modesty

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.thgillwtnorizoh.modesty.core.io.WavDecoder
import io.github.thgillwtnorizoh.modesty.core.io.WavEncoding
import io.github.thgillwtnorizoh.modesty.core.waveform.InMemoryWaveformCache
import io.github.thgillwtnorizoh.modesty.core.waveform.WaveformPyramidBuilder
import io.github.thgillwtnorizoh.modesty.ui.WaveformView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var metadataText: TextView
    private lateinit var waveformView: WaveformView

    private val worker = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)
    private var selectedUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedUri = savedInstanceState?.getString(STATE_SELECTED_URI)
        setContentView(buildContent())

        selectedUri?.let { loadWav(Uri.parse(it)) }
    }

    private fun buildContent(): ScrollView {
        val padding = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "Modesty"
            textSize = 30f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Foundation brick 2\nReal WAV in. Real waveform out."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(Button(this).apply {
            text = "Open WAV"
            setOnClickListener { chooseWav() }
        })

        statusText = TextView(this).apply {
            text = "Choose a WAV file to inspect."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(statusText)

        metadataText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(metadataText)

        waveformView = WaveformView(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320),
            )
        }
        root.addView(waveformView)

        root.addView(TextView(this).apply {
            text = "Brick 2 WAV support: PCM 8/16/24/32-bit and IEEE float 32/64-bit.\nPlayback and editing are intentionally not here yet."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        return ScrollView(this).apply {
            addView(
                root,
                ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun chooseWav() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "application/octet-stream"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_WAV)
    }

    @Deprecated("Legacy Activity callback keeps Brick 2 dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_WAV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant only temporary access. Brick 2 can still read the selected file now.
        }

        selectedUri = uri.toString()
        loadWav(uri)
    }

    private fun loadWav(uri: Uri) {
        val generation = loadGeneration.incrementAndGet()
        statusText.text = "Reading WAV and building waveform…"
        metadataText.text = ""
        waveformView.clearWaveform()

        worker.execute {
            try {
                val decoder = WavDecoder {
                    contentResolver.openInputStream(uri)
                        ?: error("Android could not open this document")
                }
                val metadata = decoder.metadata
                val pyramid = try {
                    WaveformPyramidBuilder.build(decoder)
                } finally {
                    decoder.close()
                }

                val sourceId = uri.toString()
                val cache = InMemoryWaveformCache().put(sourceId, pyramid)
                val displayName = queryDisplayName(uri)
                val details = buildString {
                    append(displayName)
                    append('\n')
                    append(metadata.info.sampleRate.hz)
                    append(" Hz • ")
                    append(metadata.info.channelCount)
                    append(if (metadata.info.channelCount == 1) " channel • " else " channels • ")
                    append(metadata.bitsPerSample)
                    append("-bit ")
                    append(if (metadata.encoding == WavEncoding.PCM_INTEGER) "PCM" else "float")
                    append('\n')
                    append(formatDuration(metadata.info.totalFrames, metadata.info.sampleRate.hz))
                    append(" • ")
                    append(String.format(Locale.US, "%.2f MiB audio data", metadata.dataSizeBytes / 1048576.0))
                }

                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    statusText.text = "Waveform ready."
                    metadataText.text = details
                    waveformView.setWaveform(
                        cache = cache,
                        sourceId = sourceId,
                        totalFrames = pyramid.totalFrames,
                        channelCount = pyramid.channelCount,
                    )
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (generation != loadGeneration.get() || isDestroyed) return@runOnUiThread
                    statusText.text = "Could not read this WAV."
                    metadataText.text = error.message ?: error.javaClass.simpleName
                    waveformView.clearWaveform()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "Selected WAV"
    }

    private fun formatDuration(frames: Long, sampleRate: Int): String {
        val seconds = frames.toDouble() / sampleRate
        val minutesPart = (seconds / 60.0).toInt()
        val secondsPart = seconds - minutesPart * 60.0
        return String.format(Locale.US, "%d:%06.3f", minutesPart, secondsPart)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        selectedUri?.let { outState.putString(STATE_SELECTED_URI, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadGeneration.incrementAndGet()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_OPEN_WAV = 2001
        private const val STATE_SELECTED_URI = "selected_wav_uri"
    }
}
