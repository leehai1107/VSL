package com.translator.vsl.viewmodel;

import static com.translator.vsl.handler.VideoUtils.createVideoFromBitmaps;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.provider.MediaStore;
import android.Manifest;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
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
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.translator.vsl.handler.CalculateUtils;
import com.translator.vsl.handler.PoseLandmarkerHelper;
import com.translator.vsl.handler.VideoTranslationHandler;
import com.translator.vsl.view.CameraActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CameraViewModel extends AndroidViewModel {

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
    private TextToSpeech tts;
    private PoseLandmarkerHelper framesHandler;
    private ImageAnalysis imageAnalysis;
    private Context context;

    public LiveData<Boolean> getFlashEnabledState() {
        return flashEnabled;
    }



    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    public LiveData<Boolean> getIsRecording() {
        return isRecording;
    }

    public CameraViewModel(@NonNull Application application) {
        super(application);
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

                // Initialize ImageAnalysis if not already initialized
                if (imageAnalysis == null) {
                    imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                }

                context = previewView.getContext();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(cameraFacing).build();

                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(
                        (CameraActivity) previewView.getContext(),
                        cameraSelector,
                        preview,
                        imageAnalysis,
                        videoCapture);
                setCamera(camera);
            } catch (Exception e) {
                Log.e("CameraError", "Error starting camera: " + e.getMessage());
                toastMessage.postValue(new Pair<>("Error starting camera: " + e.getMessage(), false));
            }
        }, ContextCompat.getMainExecutor(previewView.getContext()));
    }


    public void captureVideoWithOptions( PreviewView previewView,boolean isRealTime) {
        if (recording != null) {
            stopRecording();
        } else if (isRealTime) {
            startRecordingRealTime(previewView);
        }else {
            startRecordingNormal(previewView);
        }
    }


    private void startRecordingNormal( PreviewView previewView) {
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
                handleRecordingFinalizationNormal((VideoRecordEvent.Finalize) videoRecordEvent, previewView);
            }
        });

    }



    // Updated startRecording method
    private void startRecordingRealTime(PreviewView previewView) {
        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.getDefault()).format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video");

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(previewView.getContext()), this::analyzeFrame);


        framesHandler = new PoseLandmarkerHelper(
                0.5f,
                0.5f,
                0.5f,
                1,
                1,
                RunningMode.IMAGE,
                previewView.getContext(),
                listener
                );


        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(previewView.getContext().getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues).build();

        if (ActivityCompat.checkSelfPermission(previewView.getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toastMessage.postValue(new Pair<>("Missing RECORD_AUDIO permission", false));
            return;
        }

        recordingStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);


        // Start video capture with frame analysis
        recording = videoCapture.getOutput().prepareRecording(previewView.getContext(), options).withAudioEnabled(true)
                .start(ContextCompat.getMainExecutor(previewView.getContext()), videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                        captureButtonState.postValue(true);
                        isRecording.postValue(true);  // Set recording state to true
                    } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                        handleRecordingFinalizationRealTime((VideoRecordEvent.Finalize) videoRecordEvent, previewView);
                    }
                });
    }

    // List to store frames for the current segment
    private final List<Bitmap> frameBuffer = new ArrayList<>();
    private boolean isCropping = false;

    // Analyze each frame
    private void analyzeFrame(ImageProxy imageProxy) {
        // Convert the ImageProxy to a format compatible with MediaPipe (e.g., Bitmap)
        Bitmap bitmap = rotateBitmap(imageProxyToBitmap(imageProxy),90);

        PoseLandmarkerHelper.ResultBundle resultBundle = framesHandler.detectImage(bitmap);

        if(!resultBundle.results.isEmpty()) {
           boolean[] handStatus =  CalculateUtils.checkHandStatus(
                   resultBundle.results.get(0),160);
           Log.d("DataOfHand: [0,0] || [1,1] || [0,1] || [1,0]", Arrays.toString(handStatus));
           // Todo: Crop the video in real-time then call translate function to translate the video

            if (!Arrays.equals(handStatus, new boolean[]{false, false})) {
                // Start or continue cropping
                isCropping = true;
                frameBuffer.add(bitmap);
            } else if (isCropping) {
                // Stop cropping and process the segment
                isCropping = false;
                processVideoSegment();
                frameBuffer.clear();
            }

        }else {
            Log.d("Skip", "No hand detected");
        }


        // Close the image to allow the analyzer to proceed
        imageProxy.close();
    }

    private void processVideoSegment() {
        if (frameBuffer.isEmpty()) return;

        // Save frames to a video file
        try {
            Uri videoPath = createVideoFromBitmaps(context,frameBuffer,30);

            translateVideo(context,videoPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    PoseLandmarkerHelper.LandmarkerListener listener = new PoseLandmarkerHelper.LandmarkerListener() {
        @Override
        public void onError(String error, int errorCode) {
            // Handle the error here
            Log.e("PoseLandmarkerHelper", "Error: " + error + ", Code: " + errorCode);
        }

        @Override
        public void onResults(PoseLandmarkerHelper.ResultBundle resultBundle) {
            // Handle the results here
            Log.d("PoseLandmarkerHelper", "Results received: " + resultBundle.results.size() + " poses detected");
        }
    };



    // Convert ImageProxy to Bitmap
    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        // Conversion logic (depends on your use case)
        // For example, use YUV_420_888 to RGB conversion
        // ...
        return CalculateUtils.yuvToRgb(Objects.requireNonNull(imageProxy.getImage()));
    }

    public Bitmap rotateBitmap(Bitmap bitmap, float degrees) {
        // Create a new Matrix for the rotation
        Matrix matrix = new Matrix();
        // Set the rotation
        matrix.postRotate(degrees);

        // Create the new rotated Bitmap

        return Bitmap.createBitmap(
                bitmap,
                0, 0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );
    }


    private void handleRecordingFinalizationRealTime(VideoRecordEvent.Finalize finalizeEvent, PreviewView previewView) {
        captureButtonState.postValue(false);
        timerHandler.removeCallbacks(timerRunnable);
        timerText.postValue("00:00");
        isRecording.postValue(false);  // Set recording state to false when finished

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(previewView.getContext()), ImageProxy::close);
    }

    private void handleRecordingFinalizationNormal(VideoRecordEvent.Finalize finalizeEvent, PreviewView previewView) {
        captureButtonState.postValue(false);
        timerHandler.removeCallbacks(timerRunnable);
        timerText.postValue("00:00");
        isRecording.postValue(false);  // Set recording state to false when finished

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(previewView.getContext()), ImageProxy::close);

        try {
            translateVideo(previewView.getContext(), finalizeEvent.getOutputResults().getOutputUri());
        } catch (Exception e) {
            // Log error
            Log.e("TranslateError", "Error translating video: " + e.getMessage());
        }
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
    }

    public void initializeTextToSpeech(Context context) {
         tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                Locale vietnamese = new Locale("vi");
                int result = tts.setLanguage(vietnamese);

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Vietnamese not supported or missing data.");
                    // Prompt user to install missing data
                    Intent installIntent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    context.startActivity(installIntent);
                } else {
                    Log.d("TTS", "Vietnamese locale set successfully.");
                    // Continue with TTS usage
                }
            } else {
                Log.e("TTS", "Initialization failed.");
            }
        });

        // Clean up resources (e.g., in onDestroy)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        }));
    }

    public void translateVideo(Context context, Uri videoUri) {
        initializeTextToSpeech(context);

        if (isInternetAvailable(context)) {
            // Call the API to translate the video
            toastMessage.postValue(new Pair<>("Đang dịch trực tuyến, vui lòng đợi...", true));
            callTranslationApi(context, videoUri);
        } else {
            // Fallback to .tflite model for offline translation
            toastMessage.postValue(new Pair<>("Không có kết nối mạng, đang dịch ngoại tuyến ...", true));
            callTranslationModel(context, videoUri);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        }));
    }

    private void callTranslationModel(Context context, Uri videoUri) {
        try {

            VideoTranslationHandler translator = new VideoTranslationHandler(context, "model-final-new.tflite", "label400.txt");

            translator.translateVideoAsync(context, videoUri)
                    .thenAccept(result -> {
                        Log.d("TranslationResult", "Translation: " + result);

                        // Update UI with translation result
                        toastMessage.postValue(new Pair<>("Có thể ý bạn là từ sau? \n" + result, true));

                        // Convert the translation result to speech
                        tts.speak(result, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());

                        translator.close();
                    })
                    .exceptionally(ex -> {
                        Log.e("TranslationError", "Error: " + ex.getMessage());
                        toastMessage.postValue(new Pair<>("Đã xảy ra lỗi khi dịch: \n " + ex.getMessage(), true));
                        translator.close();
                        return null;
                    });

        } catch (IOException e) {
            Log.e("LoadModelErr", "Error translating video: " + e.getMessage());
            toastMessage.postValue(new Pair<>("Đã xảy ra lỗi khi dịch: \n " + e.getMessage(), false));
        }
    }

    // Utility method to check internet connectivity
    private boolean isInternetAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null &&
                        (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            }
        }
        return false;
    }

    // Method to call the translation API
    private void callTranslationApi(Context context, Uri videoUri) {
        String apiUrl = "http://192.168.101.6:8000/spoter";
        String angleThreshold = "110";
        String topK = "3";

        try {
            // Convert videoUri to file
            File videoFile = getFileFromUri(context, videoUri);
            MultipartBody.Builder multipartBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            multipartBuilder.addFormDataPart("video_file", videoFile.getName(),
                    RequestBody.create(MediaType.parse("video/mp4"), videoFile));
            multipartBuilder.addFormDataPart("return_type", "json");
            multipartBuilder.addFormDataPart("angle_threshold", angleThreshold);
            multipartBuilder.addFormDataPart("top_k", topK);

            RequestBody requestBody = multipartBuilder.build();

            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(requestBody)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("APIError", "Error calling API: " + e.getMessage());
                    toastMessage.postValue(new Pair<>("Không thể kết nối tới máy chủ: \n " + e.getMessage(), true));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        String responseBody = response.body().string();
                        Log.d("APIResponse", "Response: " + responseBody);

                        // Parse the response and use it as needed
                        // For example, display the top prediction
                        // Parse JSON response if necessary
                        JSONObject jsonObject = null;
                        try {
                            jsonObject = new JSONObject(responseBody);
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                        JSONArray predictions = null;
                        try {
                            predictions = jsonObject.getJSONObject("results_merged").getJSONArray("prediction");
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                        JSONObject firstPrediction = null;
                        try {
                            firstPrediction = predictions.getJSONArray(0).getJSONObject(0);
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }

                        String gloss = null;
                        try {
                            gloss = firstPrediction.getString("gloss");
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                        toastMessage.postValue(new Pair<>("Có thể ý bạn là từ sau? \n" + gloss, true));

                        // Convert the translation result to speech
                        tts.speak(gloss, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
                    } else {
                        Log.e("APIError", "API response not successful: " + response.code());
                        toastMessage.postValue(new Pair<>("Lỗi khi gọi tới máy chủ:\n Mã lỗi  " + response.code(), true));
                    }
                }
            });

        } catch (Exception e) {
            Log.e("APIRequestError", "Error preparing API request: " + e.getMessage());
            toastMessage.postValue(new Pair<>("Đã xảy ra lỗi khi gọi tới máy chủ: \n " + e.getMessage(), true));
        }
    }

    private File getFileFromUri(Context context, Uri uri) throws IOException {
        // Create a temporary file in the app's cache directory
        File tempFile = new File(context.getCacheDir(), "temp_video.mp4");

        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(tempFile)) {
            if (inputStream == null) {
                throw new FileNotFoundException("Unable to open input stream from URI.");
            }

            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        return tempFile;
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
