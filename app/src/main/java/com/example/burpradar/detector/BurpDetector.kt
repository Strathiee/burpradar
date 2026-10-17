package com.example.burpradar.detector

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import org.tensorflow.lite.task.core.BaseOptions

class BurpDetector(
    private val context: Context,
    private val confidenceThresholdProvider: () -> Float,
    private val onBurpDetected: (confidence: Float) -> Unit,
    private val onDetectorStatusChanged: (String) -> Unit
) {
    private var classifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var tensorAudio: TensorAudio? = null
    private var detectionJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        private const val TAG = "BurpDetector"
        private const val MODEL_FILE = "yamnet.tflite"
        private const val TARGET_CLASS = "Burping, eructation"
        private const val CLASSIFICATION_INTERVAL_MS = 500L
    }

    @Synchronized
    fun start() {
        if (detectionJob != null) return

        onDetectorStatusChanged("Initializing...")
        try {
            // Configure base options (e.g. use standard CPU, default threads)
            val baseOptions = BaseOptions.builder().setNumThreads(2).build()
            
            // Build the classifier options
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(5)
                .build()

            // Create AudioClassifier from assets
            val classifierInstance = AudioClassifier.createFromFileAndOptions(context, MODEL_FILE, options)
            classifier = classifierInstance

            // Create the TensorAudio and AudioRecord with matching formats
            val tensorInstance = classifierInstance.createInputTensorAudio()
            tensorAudio = tensorInstance

            val recordInstance = classifierInstance.createAudioRecord()
            if (recordInstance.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize. Check permissions.")
            }
            audioRecord = recordInstance

            recordInstance.startRecording()
            onDetectorStatusChanged("Active & Listening")

            // Start detection loop
            detectionJob = coroutineScope.launch {
                runDetectionLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start detector", e)
            onDetectorStatusChanged("Error: ${e.localizedMessage}")
            stop()
        }
    }

    @Synchronized
    fun stop() {
        detectionJob?.cancel()
        detectionJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        tensorAudio = null
        classifier = null

        onDetectorStatusChanged("Stopped")
    }

    private suspend fun runDetectionLoop() {
        val delayTime = CLASSIFICATION_INTERVAL_MS
        while (coroutineScope.isActive) {
            val record = audioRecord ?: break
            val tensor = tensorAudio ?: break
            val classif = classifier ?: break

            try {
                // Load audio from standard AudioRecord buffer into TensorAudio
                tensor.load(record)
                
                // Classify the buffer
                val results = classif.classify(tensor)
                
                // Inspect classification categories
                var burpScore = 0.0f
                if (results.isNotEmpty()) {
                    for (classification in results) {
                        for (category in classification.categories) {
                            if (category.label == TARGET_CLASS) {
                                burpScore = category.score
                                break
                            }
                        }
                    }
                }

                val threshold = confidenceThresholdProvider()
                if (burpScore >= threshold) {
                    Log.d(TAG, "Burp detected with confidence: $burpScore (threshold: $threshold)")
                    withContext(Dispatchers.Main) {
                        onBurpDetected(burpScore)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in classification loop", e)
            }

            delay(delayTime)
        }
    }

    fun release() {
        stop()
        coroutineScope.cancel()
    }
}
