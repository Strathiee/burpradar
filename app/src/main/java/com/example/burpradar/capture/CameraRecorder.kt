package com.example.burpradar.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.nio.ByteBuffer
import java.util.HashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraRecorder(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var isRecording = false
        private set

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

        isRecording = true
        recordingStartTimeMs = System.currentTimeMillis()
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "Continuous video recording started.")
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        activeRecording = null
                        if (!event.hasError()) {
                            Log.d(TAG, "Continuous video recording saved: ${outputFile.absolutePath}")
                            onRecordComplete(outputFile)
                        } else {
                            Log.e(TAG, "Continuous video recording error: ${event.error}")
                            if (outputFile.exists()) outputFile.delete()
                            onRecordComplete(null)
                        }
                    }
                }
            }
    }

    fun extractVideoClip(sourceFile: File, outputFile: File, startMs: Long, endMs: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(sourceFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set data source for extractor", e)
            return
        }

        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val trackCount = extractor.trackCount
            val trackMap = HashMap<Int, Int>()
            
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val dstTrack = muxer.addTrack(format)
                    trackMap[i] = dstTrack
                }
            }
            
            muxer.start()
            
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            
            val bufferSize = 1024 * 1024 // 1MB buffer
            val dstBuf = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            
            var videoStartPts: Long? = null
            var audioStartPts: Long? = null
            
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(dstBuf, 0)
                
                if (bufferInfo.size < 0) {
                    break
                }
                
                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) {
                    break
                }
                
                val trackIndex = extractor.sampleTrackIndex
                val dstTrack = trackMap[trackIndex]
                if (dstTrack != null) {
                    val format = extractor.getTrackFormat(trackIndex)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    
                    if (mime.startsWith("video/")) {
                        if (videoStartPts == null) videoStartPts = sampleTime
                        bufferInfo.presentationTimeUs = sampleTime - videoStartPts
                    } else {
                        if (audioStartPts == null) audioStartPts = sampleTime
                        bufferInfo.presentationTimeUs = sampleTime - audioStartPts
                    }
                    
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(dstTrack, dstBuf, bufferInfo)
                }
                extractor.advance()
            }
            
            muxer.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error during video clip extraction", e)
        } finally {
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing muxer", e)
            }
            extractor.release()
        }
    }

    fun stopRecording() {
        if (isRecording) {
            activeRecording?.stop()
            isRecording = false
            activeRecording = null
            Log.d(TAG, "Stop recording requested.")
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
