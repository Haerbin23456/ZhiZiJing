package com.example.zhizijing.ui.result

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale

class TrainingVideoPlaybackBinder(
    private val statusText: TextView,
    private val videoView: TextureView,
    private val controlRow: View,
    private val playPauseButton: MaterialButton,
    private val seekBar: SeekBar,
    private val positionText: TextView,
    private val switchRow: View,
    private val previousButton: MaterialButton,
    private val nextButton: MaterialButton,
) {
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (mediaPlayer?.isPlaying == true) {
                progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }
    private var videos: List<File> = emptyList()
    private var selectedIndex: Int = 0
    private var prepared = false
    private var completed = false
    private var userSeeking = false
    private var currentFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackSurface: Surface? = null

    init {
        playPauseButton.setOnClickListener { togglePlayback() }
        videoView.setOnClickListener { togglePlayback() }
        previousButton.setOnClickListener { moveToVideo(selectedIndex - 1) }
        nextButton.setOnClickListener { moveToVideo(selectedIndex + 1) }
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && prepared) {
                        mediaPlayer?.seekTo(progress)
                        updatePositionText(progress, videoDuration())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    userSeeking = false
                    if (prepared) {
                        mediaPlayer?.seekTo(seekBar?.progress ?: 0)
                        updateProgress()
                    }
                }
            }
        )
        videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                playbackSurface?.release()
                playbackSurface = Surface(surfaceTexture)
                currentFile?.let { file -> prepareVideo(file) }
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                prepared = false
                completed = false
                playPauseButton.isEnabled = false
                stopProgressUpdates()
                releasePlayer()
                playbackSurface?.release()
                playbackSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }

    fun render(videoFiles: List<File>) {
        videos = videoFiles.filter { file -> file.isFile && file.exists() }
        selectedIndex = 0
        if (videos.isEmpty()) {
            stop()
            statusText.text = "暂无可回放视频"
            videoView.visibility = View.GONE
            controlRow.visibility = View.GONE
            switchRow.visibility = View.GONE
            statusText.visibility = View.VISIBLE
            return
        }

        statusText.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        controlRow.visibility = View.VISIBLE
        switchRow.visibility = if (videos.size > 1) View.VISIBLE else View.GONE
        showVideo(index = selectedIndex)
    }

    fun pause() {
        val player = mediaPlayer
        if (player?.isPlaying == true) {
            player.pause()
        }
        playPauseButton.text = if (completed) "重播" else "播放"
        stopProgressUpdates()
    }

    fun stop() {
        stopProgressUpdates()
        releasePlayer()
        prepared = false
        completed = false
        currentFile = null
        seekBar.progress = 0
        seekBar.max = 1
        seekBar.isEnabled = false
        playPauseButton.text = "播放"
        playPauseButton.isEnabled = false
        updatePositionText(0, 0)
    }

    private fun moveToVideo(index: Int) {
        if (videos.isEmpty()) return
        val normalizedIndex = when {
            index < 0 -> videos.lastIndex
            index > videos.lastIndex -> 0
            else -> index
        }
        showVideo(index = normalizedIndex)
    }

    private fun showVideo(index: Int) {
        val file = videos.getOrNull(index) ?: return
        selectedIndex = index
        prepared = false
        completed = false
        stopProgressUpdates()
        statusText.visibility = View.GONE
        previousButton.isEnabled = videos.size > 1
        nextButton.isEnabled = videos.size > 1
        playPauseButton.text = "播放"
        playPauseButton.isEnabled = false
        seekBar.progress = 0
        seekBar.max = 1
        seekBar.isEnabled = false
        updatePositionText(0, 0)
        currentFile = file
        videoView.requestFocus()
        if (videoView.isAvailable) {
            if (playbackSurface == null) {
                val surfaceTexture = videoView.surfaceTexture ?: return
                playbackSurface = Surface(surfaceTexture)
            }
            prepareVideo(file)
        }
    }

    private fun togglePlayback() {
        if (!prepared) {
            return
        }
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            playPauseButton.text = "播放"
            stopProgressUpdates()
            return
        }
        if (completed) {
            player.seekTo(0)
            completed = false
        }
        player.start()
        playPauseButton.text = "暂停"
        updateProgress()
        startProgressUpdates()
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.postDelayed(progressRunnable, PROGRESS_UPDATE_INTERVAL_MS)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun updateProgress() {
        if (!prepared || userSeeking) return
        val duration = videoDuration()
        val position = (mediaPlayer?.currentPosition ?: 0).coerceAtLeast(0)
        seekBar.max = duration.coerceAtLeast(1)
        seekBar.progress = position.coerceIn(0, seekBar.max)
        updatePositionText(position, duration)
    }

    private fun videoDuration(): Int =
        mediaPlayer?.duration?.takeIf { it > 0 } ?: seekBar.max.takeIf { it > 1 } ?: 0

    private fun updatePositionText(positionMs: Int, durationMs: Int) {
        positionText.text = "${formatTime(positionMs)} / ${formatTime(durationMs)}"
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun prepareVideo(file: File) {
        val surface = playbackSurface ?: return
        releasePlayer()
        prepared = false
        completed = false
        val player = MediaPlayer()
        mediaPlayer = player
        runCatching {
            player.apply {
                setDataSource(file.absolutePath)
                setSurface(surface)
                setOnPreparedListener { player ->
                    prepared = true
                    completed = false
                    playPauseButton.isEnabled = true
                    seekBar.isEnabled = true
                    seekBar.max = player.duration.coerceAtLeast(1)
                    updatePositionText(0, player.duration)
                    statusText.visibility = View.GONE
                }
                setOnCompletionListener {
                    completed = true
                    playPauseButton.text = "重播"
                    updateProgress()
                    stopProgressUpdates()
                }
                setOnErrorListener { _, _, _ ->
                    showPlaybackError()
                    true
                }
                prepareAsync()
            }
        }.onFailure {
            if (mediaPlayer == player) {
                mediaPlayer = null
            }
            runCatching { player.release() }
            showPlaybackError()
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.reset()
                player.release()
            }
        }
        mediaPlayer = null
    }

    private fun showPlaybackError() {
        prepared = false
        completed = false
        playPauseButton.isEnabled = false
        seekBar.isEnabled = false
        stopProgressUpdates()
        statusText.visibility = View.VISIBLE
        statusText.text = "视频暂时无法播放，请重新录制或检查文件。"
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
