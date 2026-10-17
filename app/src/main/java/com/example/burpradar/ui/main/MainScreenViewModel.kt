package com.example.burpradar.ui.main

import android.app.Application
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.burpradar.capture.CameraRecorder
import com.example.burpradar.db.BurpDatabase
import com.example.burpradar.db.BurpEvent
import com.example.burpradar.data.SettingsManager
import com.example.burpradar.detector.BurpDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val database = BurpDatabase.getDatabase(application)
    private val burpDao = database.burpEventDao()
    private val settings = SettingsManager(application)
    private val cameraRecorder = CameraRecorder(application)
    private var burpDetector: BurpDetector? = null

    private var continuousRecordingJob: Job? = null
    private var activeTempFile: File? = null

    private val _sessionCount = MutableStateFlow(0)
    private val _detectorStatus = MutableStateFlow("Stopped")
    private val _isRecordingState = MutableStateFlow(false)
    private val _isRunning = MutableStateFlow(false)

    private val _confidenceThreshold = MutableStateFlow(settings.confidenceThreshold)
    private val _clipDurationMs = MutableStateFlow(settings.clipDurationMs)
    private val _maxClips = MutableStateFlow(settings.maxClips)
    private val _previewEnabled = MutableStateFlow(settings.previewEnabled)
    private val _lensFacing = MutableStateFlow(settings.lensFacing)

    val eventsFlow: Flow<List<BurpEvent>> = burpDao.getAllEventsFlow()

    val uiState: StateFlow<RadarUiState> = combine(
        _sessionCount,
        _detectorStatus,
        _isRecordingState,
        _isRunning,
        _confidenceThreshold,
        _clipDurationMs,
        _maxClips,
        _previewEnabled,
        _lensFacing,
        eventsFlow.onStart { emit(emptyList()) }
    ) { array ->
        val events = array[9] as List<BurpEvent>
        RadarUiState(
            sessionCount = array[0] as Int,
            detectorStatus = array[1] as String,
            isRecording = array[2] as Boolean,
            isRunning = array[3] as Boolean,
            confidenceThreshold = array[4] as Float,
            clipDurationMs = array[5] as Long,
            maxClips = array[6] as Int,
            previewEnabled = array[7] as Boolean,
            lensFacing = array[8] as Int,
            eventsList = events,
            allTimeCount = events.size
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        RadarUiState()
    )

    companion object {
        private const val TAG = "MainScreenViewModel"
    }

    fun setConfidenceThreshold(value: Float) {
        settings.confidenceThreshold = value
        _confidenceThreshold.value = value
    }

    fun setClipDuration(value: Long) {
        settings.clipDurationMs = value
        _clipDurationMs.value = value
    }

    fun setMaxClips(value: Int) {
        settings.maxClips = value
        _maxClips.value = value
    }

    fun setPreviewEnabled(value: Boolean, lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        settings.previewEnabled = value
        _previewEnabled.value = value
        // Re-bind camera if running to apply preview toggle
        if (_isRunning.value) {
            cameraRecorder.stopRecording()
            cameraRecorder.bindCamera(lifecycleOwner, previewView, value, _lensFacing.value) {
                if (!_isRecordingState.value) {
                    startContinuousRecordingLoop()
                }
            }
        }
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        cameraRecorder.bindCamera(lifecycleOwner, previewView, _previewEnabled.value, _lensFacing.value)
    }

    fun toggleCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        val currentLens = _lensFacing.value
        val newLens = if (currentLens == 1) 0 else 1
        settings.lensFacing = newLens
        _lensFacing.value = newLens
        // Stop current continuous recording before unbinding/rebinding
        cameraRecorder.stopRecording()
        // Re-bind camera to apply dynamic lens toggle
        cameraRecorder.bindCamera(lifecycleOwner, previewView, _previewEnabled.value, newLens) {
            if (_isRunning.value && !_isRecordingState.value) {
                startContinuousRecordingLoop()
            }
        }
    }

    fun startRadar(lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        if (_isRunning.value) return
        
        Log.d(TAG, "Starting Radar Sentinel...")
        _isRunning.value = true
        _sessionCount.value = 0

        // 1. Initialize and bind camera
        cameraRecorder.bindCamera(lifecycleOwner, previewView, _previewEnabled.value, _lensFacing.value) {
            // Once camera is bound, start audio classification AND the continuous rolling video buffer
            startAudioClassification()
            startContinuousRecordingLoop()
        }
    }

    private fun startContinuousRecordingLoop() {
        continuousRecordingJob?.cancel()
        continuousRecordingJob = viewModelScope.launch {
            while (this.isActive && _isRunning.value) {
                if (!_isRecordingState.value) {
                    val cacheDir = File(getApplication<Application>().cacheDir, "burp_temp").apply { mkdirs() }
                    val tempFile = File(cacheDir, "rolling_${System.currentTimeMillis()}.mp4")
                    activeTempFile = tempFile
                    
                    val recordStartLatch = Job()
                    cameraRecorder.startContinuousRecording(tempFile) { savedFile ->
                        recordStartLatch.complete()
                    }
                    
                    var elapsedSec = 0
                    while (elapsedSec < 30 && !_isRecordingState.value && _isRunning.value) {
                        delay(1000)
                        elapsedSec++
                    }
                    
                    if (!_isRecordingState.value && _isRunning.value) {
                        cameraRecorder.stopRecording()
                        recordStartLatch.join()
                        if (tempFile.exists()) {
                            tempFile.delete()
                        }
                    } else {
                        recordStartLatch.join()
                        break
                    }
                } else {
                    delay(1000)
                }
            }
        }
    }

    private fun startAudioClassification() {
        burpDetector = BurpDetector(
            context = getApplication(),
            confidenceThresholdProvider = { _confidenceThreshold.value },
            onBurpDetected = { confidence ->
                handleBurpDetected(confidence)
            },
            onDetectorStatusChanged = { status ->
                _detectorStatus.value = status
            }
        ).apply {
            start()
        }
    }

    private fun handleBurpDetected(confidence: Float) {
        if (_isRecordingState.value || !cameraRecorder.isRecording) {
            Log.d(TAG, "Audio classifier detected burp, but sentinel is already recording or camera not active. Skipping.")
            return
        }

        _sessionCount.value += 1
        _isRecordingState.value = true
        _detectorStatus.value = "RECORDING EVIDENCE!"

        val detectionTimeMs = System.currentTimeMillis() - cameraRecorder.recordingStartTimeMs
        val clipDuration = _clipDurationMs.value
        val preRoll = clipDuration / 2
        val startMs = maxOf(0L, detectionTimeMs - preRoll)
        val endMs = detectionTimeMs + (clipDuration - (detectionTimeMs - startMs))

        val tempFile = activeTempFile ?: return

        viewModelScope.launch {
            val currentDuration = System.currentTimeMillis() - cameraRecorder.recordingStartTimeMs
            val waitTime = endMs - currentDuration
            if (waitTime > 0) {
                delay(waitTime)
            }
            
            cameraRecorder.stopRecording()
            
            withContext(Dispatchers.IO) {
                var retryCount = 0
                while (!tempFile.exists() && retryCount < 10) {
                    delay(100)
                    retryCount++
                }
                
                if (tempFile.exists()) {
                    val videoDir = File(getApplication<Application>().cacheDir, "burp_videos").apply { mkdirs() }
                    val outputFile = File(videoDir, "burp_${System.currentTimeMillis()}.mp4")
                    
                    Log.d(TAG, "Extracting sub-clip from ${tempFile.absolutePath} starting at ${startMs}ms to ${endMs}ms")
                    cameraRecorder.extractVideoClip(tempFile, outputFile, startMs, endMs)
                    
                    if (outputFile.exists() && outputFile.length() > 0) {
                        saveEvent(outputFile, confidence)
                    } else {
                        Log.e(TAG, "Failed to extract video clip. Output file is missing or empty.")
                    }
                    
                    tempFile.delete()
                } else {
                    Log.e(TAG, "Temporary rolling file does not exist: ${tempFile.absolutePath}")
                }
            }
            
            _isRecordingState.value = false
            _detectorStatus.value = "Active & Listening"
            
            startContinuousRecordingLoop()
        }
    }

    private suspend fun saveEvent(file: File, confidence: Float) {
        val event = BurpEvent(
            timestamp = System.currentTimeMillis(),
            videoPath = file.absolutePath,
            durationMs = _clipDurationMs.value,
            confidence = confidence
        )
        withContext(Dispatchers.IO) {
            burpDao.insertEvent(event)
            pruneOldClips()
        }
    }

    private suspend fun pruneOldClips() {
        val limit = _maxClips.value
        val allEvents = burpDao.getAllEventsAscending() // Oldest first
        if (allEvents.size > limit) {
            val toDeleteCount = allEvents.size - limit
            for (i in 0 until toDeleteCount) {
                val eventToDelete = allEvents[i]
                Log.d(TAG, "Pruning old event ID: ${eventToDelete.id}")
                
                // 1. Delete video file
                val file = File(eventToDelete.videoPath)
                if (file.exists()) {
                    val success = file.delete()
                    Log.d(TAG, "Deleted pruned video file: ${eventToDelete.videoPath}, success: $success")
                }
                
                // 2. Delete database record
                burpDao.deleteEvent(eventToDelete.id)
            }
        }
    }

    fun stopRadar() {
        if (!_isRunning.value) return
        Log.d(TAG, "Stopping Radar Sentinel...")
        _isRunning.value = false
        _isRecordingState.value = false
        
        continuousRecordingJob?.cancel()
        continuousRecordingJob = null
        
        burpDetector?.release()
        burpDetector = null
        
        cameraRecorder.stopRecording()
        cameraRecorder.unbind()
        
        activeTempFile?.let { file ->
            if (file.exists()) {
                file.delete()
            }
        }
        activeTempFile = null
        
        _detectorStatus.value = "Stopped"
    }

    fun deleteEvent(event: BurpEvent) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(event.videoPath)
            if (file.exists()) {
                file.delete()
            }
            burpDao.deleteEvent(event.id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val allEvents = burpDao.getAllEventsAscending()
            for (event in allEvents) {
                val file = File(event.videoPath)
                if (file.exists()) {
                    file.delete()
                }
            }
            burpDao.deleteAllEvents()
        }
    }

    override fun onCleared() {
        super.onCleared()
        burpDetector?.release()
        cameraRecorder.shutdown()
        
        val tempDir = File(getApplication<Application>().cacheDir, "burp_temp")
        if (tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }
}

data class RadarUiState(
    val sessionCount: Int = 0,
    val allTimeCount: Int = 0,
    val detectorStatus: String = "Stopped",
    val isRecording: Boolean = false,
    val isRunning: Boolean = false,
    val confidenceThreshold: Float = 0.40f,
    val clipDurationMs: Long = 5000L,
    val maxClips: Int = 50,
    val previewEnabled: Boolean = true,
    val lensFacing: Int = 1,
    val eventsList: List<BurpEvent> = emptyList()
)
