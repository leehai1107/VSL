package com.translator.vsl.viewmodel;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.provider.MediaStore;
import android.Manifest;
import android.util.Log;


import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.google.common.util.concurrent.ListenableFuture;
import com.translator.vsl.handler.VideoTranslationHandler;
import com.translator.vsl.view.CameraActivity;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraViewModel extends ViewModel {

    private final MutableLiveData<String> timerText = new MutableLiveData<>("00:00");
    private final MutableLiveData<Boolean> captureButtonState = new MutableLiveData<>(false);
    private final MutableLiveData<Pair<String, Boolean>> toastMessage = new MutableLiveData<>();

    private final MutableLiveData<Boolean> flashEnabled = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isRecording = new MutableLiveData<>(false);

    private Recording recording = null;
    private VideoCapture<Recorder> videoCapture = null;
    private final ExecutorService service = Executors.newSingleThreadExecutor();
    private long recordingStartTime;
    private Camera camera;  // Make camera accessible
    private final Runnable timerRunnable;
    private final Handler timerHandler = new Handler();

    public LiveData<Boolean> getFlashEnabledState() {
        return flashEnabled;
    }



    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    public LiveData<Boolean> getIsRecording() {
        return isRecording;
    }

    public CameraViewModel() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
                long seconds = (elapsedMillis / 1000) % 60;
                long minutes = (elapsedMillis / (1000 * 60)) % 60;
                String time = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
                timerText.postValue(time);
                timerHandler.postDelayed(this, 1000);
            }
        };
    }

    public LiveData<String> getTimerText() {
        return timerText;
    }

    public LiveData<Boolean> getCaptureButtonState() {
        return captureButtonState;
    }

    public LiveData<Pair<String, Boolean>> getToastMessage() {
        return toastMessage;
    }

    public void startCamera(int cameraFacing, PreviewView previewView) {
        ListenableFuture<ProcessCameraProvider> processCameraProvider = ProcessCameraProvider.getInstance(previewView.getContext());
        processCameraProvider.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = processCameraProvider.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraFacing).build();

                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle((CameraActivity) previewView.getContext(), cameraSelector, preview, videoCapture);
                setCamera(camera);
            } catch (Exception e) {
                toastMessage.postValue(new Pair<>("Error starting camera: "+e.getMessage(), false));
            }
        }, ContextCompat.getMainExecutor(previewView.getContext()));
    }

    public void captureVideo( PreviewView previewView) {
        if (recording != null) {
            stopRecording();
        } else {
            startRecording(previewView);
        }
    }

    private void startRecording( PreviewView previewView) {
        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault()).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(previewView.getContext().getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues).build();

        if (ActivityCompat.checkSelfPermission(previewView.getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toastMessage.postValue(new Pair<>("Missing RECORD_AUDIO permission", false));
            return;
        }

        recordingStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);
        recording = videoCapture.getOutput().prepareRecording(previewView.getContext(), options).withAudioEnabled().start(ContextCompat.getMainExecutor(previewView.getContext()), videoRecordEvent -> {
            if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                captureButtonState.postValue(true);
                isRecording.postValue(true);  // Set recording state to true
            } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                handleRecordingFinalization((VideoRecordEvent.Finalize) videoRecordEvent, previewView);
            }
        });

    }

    private void stopRecording() {
        if (recording != null) {
            recording.stop();
            recording = null;
            timerHandler.removeCallbacks(timerRunnable);
            timerText.postValue("00:00");
            isRecording.postValue(false);  // Set recording state to false
        }
        captureButtonState.postValue(false);
        toastMessage.postValue(new Pair<>("Video is translating...", true));
    }

    private void handleRecordingFinalization(VideoRecordEvent.Finalize finalizeEvent, PreviewView previewView) {
        captureButtonState.postValue(false);
        timerHandler.removeCallbacks(timerRunnable);
        timerText.postValue("00:00");
        isRecording.postValue(false);  // Set recording state to false when finished


        try {
            translateVideo(previewView.getContext(), finalizeEvent.getOutputResults().getOutputUri());
        } catch (Exception e) {
            // Log error
            Log.e("TranslateError", "Error translating video: " + e.getMessage());
        }
    }


    //  Method to handle video translation
    public void translateVideo(Context context, Uri videoUri) {
        VideoTranslationHandler translator;
        try {
            // Todo: Add translation code here
            translator = new VideoTranslationHandler(context, "model-final-new.tflite","labels.txt");
            VideoTranslationHandler finalTranslator = translator; // Reference for closure

            translator.translateVideoAsync(context, videoUri)
                    .thenAccept(result -> {
                        Log.d("TranslationResult", "Translation: " + result);
                        toastMessage.postValue(new Pair<>("Result: " + result, true));
                        finalTranslator.close(); // Close the interpreter after task completion


                    })
                    .exceptionally(ex -> {
                        Log.e("TranslationError", "Error: " + ex.getMessage());
                        toastMessage.postValue(new Pair<>("Error: " + ex.getMessage(), true));
                        finalTranslator.close(); // Ensure resources are closed even on error
                        return null;
                    });

        } catch (IOException e) {
            // Log error
            Log.e("LoadModelErr", "Error translating video: " + e.getMessage());
            toastMessage.postValue(new Pair<>("Error loading model: " + e.getMessage(), false));
        }
    }





    public void toggleFlash() {
        if (camera == null) {
            toastMessage.postValue(new Pair<>("Camera is not initialized.", false));
            return;
        }
        if (camera.getCameraInfo().hasFlashUnit()) {
            boolean isFlashOn = camera.getCameraInfo().getTorchState().getValue() == 1;
            camera.getCameraControl().enableTorch(!isFlashOn);
            flashEnabled.setValue(!isFlashOn); // Update UI state
        } else {
            toastMessage.postValue(new Pair<>("Flash is not available at this time.", false));
        }
    }





    @Override
    protected void onCleared() {
        super.onCleared();
        service.shutdown();
        timerHandler.removeCallbacks(timerRunnable);
    }
}
