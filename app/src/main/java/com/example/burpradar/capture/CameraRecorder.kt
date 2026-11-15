package com.example.burpradar.capture

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraRecorder(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var finalizeDeferred: CompletableDeferred<File?>? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    var isRecording = false
        private set

    @Volatile
    var recordingStartTimeMs: Long = 0L
        private set

    companion object {
        private const val TAG = "CameraRecorder"
        private const val VIDEO_DIR_NAME = "burp_videos"
    }

    init {
        // Initialize process camera provider early
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get ProcessCameraProvider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        previewEnabled: Boolean,
        lensFacing: Int,
        onInitialized: () -> Unit = {}
    ) {
        val provider = cameraProvider
        if (provider == null) {
            // Retry binding when provider is ready
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    bindCamera(lifecycleOwner, previewView, previewEnabled, lensFacing, onInitialized)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get ProcessCameraProvider on retry", e)
                }
            }, ContextCompat.getMainExecutor(context))
            return
        }

        try {
            provider.unbindAll()

            // Setup VideoCapture with Recorder (Target SD Quality to conserve resources)
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            val captureInstance = VideoCapture.withOutput(recorder)
            videoCapture = captureInstance

            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            if (previewEnabled && previewView != null) {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    captureInstance
                )
            } else {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    captureInstance
                )
            }
            onInitialized()
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startContinuousRecording(
        outputFile: File,
        onRecordComplete: (File?) -> Unit
    ) {
        val capture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture is null. Cannot record.")
            onRecordComplete(null)
            return
        }

        if (isRecording) {
            Log.w(TAG, "Already recording. Ignoring request.")
            return
        }

        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        val deferred = CompletableDeferred<File?>()
        finalizeDeferred = deferred

        isRecording = true
        recordingStartTimeMs = System.currentTimeMillis()
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recordingStartTimeMs = System.currentTimeMillis()
                        Log.d(TAG, "Continuous video recording started.")
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        activeRecording = null
                        if (!event.hasError() || (outputFile.exists() && outputFile.length() > 1024L)) {
                            Log.d(TAG, "Continuous video recording finalized: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                            deferred.complete(outputFile)
                            onRecordComplete(outputFile)
                        } else {
                            Log.e(TAG, "Continuous video recording error: ${event.error}, cause: ${event.cause?.message}")
                            if (outputFile.exists()) outputFile.delete()
                            deferred.complete(null)
                            onRecordComplete(null)
                        }
                    }
                }
            }
    }

    fun extractVideoClip(sourceFile: File, outputFile: File, startMs: Long, endMs: Long): Boolean {
        if (!sourceFile.exists() || sourceFile.length() <= 1024L) {
            Log.e(TAG, "Source file missing or empty (${sourceFile.length()} bytes): ${sourceFile.absolutePath}")
            return false
        }

        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(sourceFile.absolutePath)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrackIndex == -1) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }

            val vFormat = videoFormat ?: run {
                Log.e(TAG, "No video track found in ${sourceFile.absolutePath}")
                return false
            }

            val requestedDurationUs = maxOf(1_000_000L, (endMs - startMs) * 1000L)
            var targetStartUs = maxOf(0L, startMs * 1000L)

            if (vFormat.containsKey(MediaFormat.KEY_DURATION)) {
                val totalDurationUs = vFormat.getLong(MediaFormat.KEY_DURATION)
                if (totalDurationUs > 0L && targetStartUs + 500_000L >= totalDurationUs) {
                    targetStartUs = maxOf(0L, totalDurationUs - requestedDurationUs)
                }
            }

            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(targetStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (videoExtractor.sampleTime < 0L) {
                videoExtractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val actualStartUs = maxOf(0L, videoExtractor.sampleTime)
            val actualEndUs = maxOf(endMs * 1000L, actualStartUs + requestedDurationUs)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(vFormat.getInteger(MediaFormat.KEY_ROTATION))
            }

            val dstVideoTrack = muxer.addTrack(vFormat)
            var dstAudioTrack = -1

            if (audioTrackIndex != -1 && audioFormat != null) {
                val aExtractor = MediaExtractor()
                aExtractor.setDataSource(sourceFile.absolutePath)
                aExtractor.selectTrack(audioTrackIndex)
                aExtractor.seekTo(actualStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                if (aExtractor.sampleTime < 0L || aExtractor.sampleTime > actualEndUs) {
                    aExtractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
                if (aExtractor.sampleTime >= 0L) {
                    dstAudioTrack = muxer.addTrack(audioFormat)
                    audioExtractor = aExtractor
                } else {
                    aExtractor.release()
                }
            }

            muxer.start()

            val bufferSize = 1024 * 1024 // 1MB buffer
            val dstBuf = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoStartPts: Long? = null
            var lastVideoPts = -1L
            var videoSamplesWritten = 0

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(dstBuf, 0)
                if (bufferInfo.size < 0) break

                val sampleTime = videoExtractor.sampleTime
                if (sampleTime < 0L || sampleTime > actualEndUs) break

                if (videoStartPts == null) videoStartPts = sampleTime
                var pts = maxOf(0L, sampleTime - videoStartPts)
                if (pts <= lastVideoPts) {
                    pts = lastVideoPts + 100L
                }
                lastVideoPts = pts
                bufferInfo.presentationTimeUs = pts

                var codecFlags = 0
                val sampleFlags = videoExtractor.sampleFlags
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                    codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }
                bufferInfo.flags = codecFlags

                muxer.writeSampleData(dstVideoTrack, dstBuf, bufferInfo)
                videoSamplesWritten++
                videoExtractor.advance()
            }

            if (dstAudioTrack != -1 && audioExtractor != null) {
                var audioStartPts: Long? = null
                var lastAudioPts = -1L
                while (true) {
                    bufferInfo.offset = 0
                    bufferInfo.size = audioExtractor.readSampleData(dstBuf, 0)
                    if (bufferInfo.size < 0) break

                    val sampleTime = audioExtractor.sampleTime
                    if (sampleTime < 0L || sampleTime > actualEndUs) break

                    if (audioStartPts == null) audioStartPts = sampleTime
                    var pts = maxOf(0L, sampleTime - audioStartPts)
                    if (pts <= lastAudioPts) {
                        pts = lastAudioPts + 100L
                    }
                    lastAudioPts = pts
                    bufferInfo.presentationTimeUs = pts

                    var codecFlags = 0
                    val sampleFlags = audioExtractor.sampleFlags
                    if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                    }
                    bufferInfo.flags = codecFlags

                    muxer.writeSampleData(dstAudioTrack, dstBuf, bufferInfo)
                    audioExtractor.advance()
                }
            }

            if (videoSamplesWritten == 0) {
                Log.e(TAG, "No video samples written during extraction")
                if (outputFile.exists()) outputFile.delete()
                return false
            }

            muxer.stop()
            val success = outputFile.exists() && outputFile.length() > 1024L
            Log.d(TAG, "Extracted clip success=$success, videoSamples=$videoSamplesWritten, size=${outputFile.length()} bytes")
            if (!success && outputFile.exists()) {
                outputFile.delete()
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error during video clip extraction", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return false
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing muxer", e)
            }
            try {
                audioExtractor?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing audioExtractor", e)
            }
            videoExtractor.release()
        }
    }

    fun exportToPublicMovies(videoFile: File): Boolean {
        if (!videoFile.exists() || videoFile.length() <= 1024L) return false
        return try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BurpRadar")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false
            resolver.openOutputStream(uri)?.use { outStream ->
                videoFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }
            Log.d(TAG, "Exported video to public Movies/BurpRadar: ${videoFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export video to public Movies/BurpRadar", e)
            false
        }
    }

    fun stopRecording() {
        val rec = activeRecording
        if (isRecording || rec != null) {
            rec?.stop()
            isRecording = false
            activeRecording = null
            Log.d(TAG, "Stop recording requested.")
        }
    }

    suspend fun stopRecordingAndAwaitFinalize(): File? {
        val deferred = finalizeDeferred
        stopRecording()
        return if (deferred != null) {
            withTimeoutOrNull(5000L) {
                deferred.await()
            }
        } else {
            null
        }
    }

    fun unbind() {
        stopRecording()
        cameraProvider?.unbindAll()
        videoCapture = null
    }

    fun shutdown() {
        unbind()
        cameraExecutor.shutdown()
    }
}
