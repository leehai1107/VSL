package com.translator.vsl.handler;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;

public class PoseLandmarkerHelper {
//    private static final String TAG = "FramesHandler";
//    private PoseLandmarker poseLandmarker;
//
//    public FramesHandler(Context context, float minPoseDetectionConfidence, float minPoseTrackingConfidence, float minPosePresenceConfidence, RunningMode runningMode) {
//
//        PoseLandmarker.PoseLandmarkerOptions.Builder optionsBuilder =
//                PoseLandmarker.PoseLandmarkerOptions.builder()
//                        .setBaseOptions(BaseOptions.builder().build())
//                        .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
//                        .setMinTrackingConfidence(minPoseTrackingConfidence)
//                        .setMinPosePresenceConfidence(minPosePresenceConfidence)
//                        .setRunningMode(runningMode);
//
//        // Initialize the PoseLandmarker
//        poseLandmarker = PoseLandmarker.createFromOptions(context, optionsBuilder.build());
//    }
//
//    public PoseLandmarkerResult processFrame(MPImage frame) {
//        return poseLandmarker.detect(frame);
//    }

    private static final String TAG = "PoseLandmarkerHelper";

    public static final int DELEGATE_CPU = 0;
    public static final int DELEGATE_GPU = 1;
    public static final float DEFAULT_POSE_DETECTION_CONFIDENCE = 0.5F;
    public static final float DEFAULT_POSE_TRACKING_CONFIDENCE = 0.5F;
    public static final float DEFAULT_POSE_PRESENCE_CONFIDENCE = 0.5F;
    public static final int MODEL_POSE_LANDMARKER_FULL = 0;
    public static final int MODEL_POSE_LANDMARKER_LITE = 1;
    public static final int MODEL_POSE_LANDMARKER_HEAVY = 2;
    public static final int OTHER_ERROR = 0;
    public static final int GPU_ERROR = 1;

    private float minPoseDetectionConfidence = DEFAULT_POSE_DETECTION_CONFIDENCE;
    private float minPoseTrackingConfidence = DEFAULT_POSE_TRACKING_CONFIDENCE;
    private float minPosePresenceConfidence = DEFAULT_POSE_PRESENCE_CONFIDENCE;
    private int currentModel = MODEL_POSE_LANDMARKER_FULL;
    private int currentDelegate = DELEGATE_CPU;
    private RunningMode runningMode = RunningMode.IMAGE;

    private final Context context;
    private final LandmarkerListener poseLandmarkerHelperListener;

    private PoseLandmarker poseLandmarker;

    public PoseLandmarkerHelper(
            float minPoseDetectionConfidence,
            float minPoseTrackingConfidence,
            float minPosePresenceConfidence,
            int currentModel,
            int currentDelegate,
            RunningMode runningMode,
            Context context,
            LandmarkerListener poseLandmarkerHelperListener) {

        this.minPoseDetectionConfidence = minPoseDetectionConfidence;
        this.minPoseTrackingConfidence = minPoseTrackingConfidence;
        this.minPosePresenceConfidence = minPosePresenceConfidence;
        this.currentModel = currentModel;
        this.currentDelegate = currentDelegate;
        this.runningMode = runningMode;
        this.context = context;
        this.poseLandmarkerHelperListener = poseLandmarkerHelperListener;

        setupPoseLandmarker();
    }

    public void clearPoseLandmarker() {
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }
        poseLandmarker = null;
    }

    public boolean isClose() {
        return poseLandmarker == null;
    }

    public void setupPoseLandmarker() {
        BaseOptions.Builder baseOptionBuilder = BaseOptions.builder();

        switch (currentDelegate) {
            case DELEGATE_CPU:
                baseOptionBuilder.setDelegate(Delegate.CPU);
                break;
            case DELEGATE_GPU:
                baseOptionBuilder.setDelegate(Delegate.GPU);
                break;
        }

        String modelName;
        switch (currentModel) {
            case MODEL_POSE_LANDMARKER_FULL:
                modelName = "pose_landmarker_full.task";
                break;
            case MODEL_POSE_LANDMARKER_LITE:
                modelName = "pose_landmarker_lite.task";
                break;
            case MODEL_POSE_LANDMARKER_HEAVY:
                modelName = "pose_landmarker_heavy.task";
                break;
            default:
                modelName = "pose_landmarker_full.task";
        }

        baseOptionBuilder.setModelAssetPath(modelName);

        if (runningMode == RunningMode.LIVE_STREAM && poseLandmarkerHelperListener == null) {
            throw new IllegalStateException("poseLandmarkerHelperListener must be set when runningMode is LIVE_STREAM.");
        }

        try {
            BaseOptions baseOptions = baseOptionBuilder.build();
            PoseLandmarker.PoseLandmarkerOptions.Builder optionsBuilder = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                    .setMinTrackingConfidence(minPoseTrackingConfidence)
                    .setMinPosePresenceConfidence(minPosePresenceConfidence)
                    .setRunningMode(runningMode);

            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder
                        .setResultListener(this::returnLivestreamResult)
                        .setErrorListener(this::returnLivestreamError);
            }

            PoseLandmarker.PoseLandmarkerOptions options = optionsBuilder.build();
            poseLandmarker = PoseLandmarker.createFromOptions(context, options);

        } catch (RuntimeException e) {
            if (poseLandmarkerHelperListener != null) {
                poseLandmarkerHelperListener.onError("Pose Landmarker failed to initialize. See error logs for details", GPU_ERROR);
            }
            Log.e(TAG, "Error initializing Pose Landmarker: " + e.getMessage());
        }
    }

    public void detectLiveStream(ImageProxy imageProxy, boolean isFrontCamera) {
        if (runningMode != RunningMode.LIVE_STREAM) {
            throw new IllegalArgumentException("detectLiveStream can only be called when runningMode is LIVE_STREAM.");
        }

        long frameTime = SystemClock.uptimeMillis();

        Bitmap bitmapBuffer = Bitmap.createBitmap(
                imageProxy.getWidth(),
                imageProxy.getHeight(),
                Bitmap.Config.ARGB_8888);

        bitmapBuffer.copyPixelsFromBuffer(imageProxy.getPlanes()[0].getBuffer());

        imageProxy.close();

        Matrix matrix = new Matrix();
        matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
        if (isFrontCamera) {
            matrix.postScale(-1f, 1f, imageProxy.getWidth(), imageProxy.getHeight());
        }

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.getWidth(), bitmapBuffer.getHeight(), matrix, true);

        MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();
        detectAsync(mpImage, frameTime);
    }

    @VisibleForTesting
    public void detectAsync(MPImage mpImage, long frameTime) {
        if (poseLandmarker != null) {
            poseLandmarker.detectAsync(mpImage, frameTime);
        }
    }

    public ResultBundle detectImage(Bitmap image) {
        if (runningMode != RunningMode.IMAGE) {
            throw new IllegalArgumentException("detectImage can only be called when runningMode is IMAGE.");
        }

        long startTime = SystemClock.uptimeMillis();

        MPImage mpImage = new BitmapImageBuilder(image).build();

        if (poseLandmarker != null) {
            PoseLandmarkerResult landmarkResult = poseLandmarker.detect(mpImage);
            long inferenceTimeMs = SystemClock.uptimeMillis() - startTime;
            return new ResultBundle(
                    List.of(landmarkResult),
                    inferenceTimeMs,
                    image.getHeight(),
                    image.getWidth());
        }

        if (poseLandmarkerHelperListener != null) {
            poseLandmarkerHelperListener.onError("Pose Landmarker failed to detect.", GPU_ERROR);
        }
        return null;
    }

    private void returnLivestreamResult(PoseLandmarkerResult result, MPImage input) {
        long finishTimeMs = SystemClock.uptimeMillis();
        long inferenceTime = finishTimeMs - result.timestampMs();

        if (poseLandmarkerHelperListener != null) {
            poseLandmarkerHelperListener.onResults(new ResultBundle(
                    List.of(result),
                    inferenceTime,
                    input.getHeight(),
                    input.getWidth()));
        }
    }

    private void returnLivestreamError(RuntimeException error) {
        if (poseLandmarkerHelperListener != null) {
            String errorMessage = error.getMessage() != null ? error.getMessage() : "An unknown error has occurred";
            poseLandmarkerHelperListener.onError(errorMessage, OTHER_ERROR); // Use the appropriate error code
        }
    }


    public interface LandmarkerListener {
        void onError(String error, int errorCode);

        void onResults(ResultBundle resultBundle);
    }

    public static class ResultBundle {
        public final List<PoseLandmarkerResult> results;
        public final long inferenceTime;
        public final int inputImageHeight;
        public final int inputImageWidth;

        public ResultBundle(List<PoseLandmarkerResult> results, long inferenceTime, int inputImageHeight, int inputImageWidth) {
            this.results = results;
            this.inferenceTime = inferenceTime;
            this.inputImageHeight = inputImageHeight;
            this.inputImageWidth = inputImageWidth;
        }
    }

}
