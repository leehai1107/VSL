package com.translator.vsl.handler;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CalculateUtils {

    /**
     * Softmax function
     */
    public static float[] softmax(float[] floatArray) {
        float total = 0f;
        float[] result = new float[floatArray.length];
        for (int i = 0; i < floatArray.length; i++) {
            result[i] = (float) Math.exp(floatArray[i]);
            total += result[i];
        }

        for (int i = 0; i < result.length; i++) {
            result[i] /= total;
        }
        return result;
    }

    /**
     * Convert ImageProxy to Bitmap
     * Input format YUV420
     */
    public static Bitmap yuvToRgb(Image image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);
        byte[] imageBytes = out.toByteArray();

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }


    /**
     * Init variables of body parts
     * 0 - nose
     * 1 - left eye (inner)
     * 2 - left eye
     * 3 - left eye (outer)
     * 4 - right eye (inner)
     * 5 - right eye
     * 6 - right eye (outer)
     * 7 - left ear
     * 8 - right ear
     * 9 - mouth (left)
     * 10 - mouth (right)
     * 11 - left shoulder
     * 12 - right shoulder
     * 13 - left elbow
     * 14 - right elbow
     * 15 - left wrist
     * 16 - right wrist
     * 17 - left pinky
     * 18 - right pinky
     * 19 - left index
     * 20 - right index
     * 21 - left thumb
     * 22 - right thumb
     * 23 - left hip
     * 24 - right hip
     * 25 - left knee
     * 26 - right knee
     * 27 - left ankle
     * 28 - right ankle
     * 29 - left heel
     * 30 - right heel
     * 31 - left foot index
     * 32 - right foot index
     */


    private static final int LEFT_SHOULDER = 11;
    private static final int RIGHT_SHOULDER = 12;
    private static final int LEFT_ELBOW = 13;
    private static final int RIGHT_ELBOW = 14;
    private static final int LEFT_WRIST = 15;
    private static final int RIGHT_WRIST = 16;

    private static final String TAG = "CalculateUtils";


    public static boolean[] checkHandStatus(
            PoseLandmarkerResult result,
            float threshold) {

        String leftStatus = "down";
        String rightStatus = "down";

        boolean[] handStatus = {false, false}; // {leftHand, rightHand}


        float[] leftShoulder;
        float[] rightShoulder;

        float[] leftElbow;
        float[] rightElbow;

        float[] leftWrist;
        float[] rightWrist;

        float visibilityThreshold = 0.6f;

        if(!result.landmarks().isEmpty()){
            List<NormalizedLandmark> data = result.landmarks().get(0);

            leftShoulder = new float[]{
                    data.get(LEFT_SHOULDER).x(),
                    data.get(LEFT_SHOULDER).y(),
                    data.get(LEFT_SHOULDER).visibility().orElse(0.0f)
            };
            leftElbow = new float[]{
                    data.get(LEFT_ELBOW).x(),
                    data.get(LEFT_ELBOW).y(),
                    data.get(LEFT_ELBOW).visibility().orElse(0.0f)
            };
            leftWrist = new float[]{
                    data.get(LEFT_WRIST).x(),
                    data.get(LEFT_WRIST).y(),
                    data.get(LEFT_WRIST).visibility().orElse(0.0f)
            };


            rightShoulder = new float[]{
                    data.get(RIGHT_SHOULDER).x(),
                    data.get(RIGHT_SHOULDER).y(),
                    data.get(RIGHT_SHOULDER).visibility().orElse(0.0f)
            };

            rightElbow = new float[]{
                    data.get(RIGHT_ELBOW).x(),
                    data.get(RIGHT_ELBOW).y(),
                    data.get(RIGHT_ELBOW).visibility().orElse(0.0f)
            };
            rightWrist = new float[]{
                    data.get(RIGHT_WRIST).x(),
                    data.get(RIGHT_WRIST).y(),
                    data.get(RIGHT_WRIST).visibility().orElse(0.0f)
        };

            // Calculate angles
            float leftAngle = calculateAngle(leftShoulder, leftElbow, leftWrist);
            float rightAngle = calculateAngle(rightShoulder, rightElbow, rightWrist);

            // Check if left hand up or down
            if (leftAngle < threshold
                    && leftWrist[2] > visibilityThreshold
                    && leftStatus.equals("down")
            ) {
                leftStatus = "up";
                handStatus[0] = true;
                Log.d(TAG, "Left hand is up");
            }else {
                leftStatus = "down";
                handStatus[0] = false;
                Log.d(TAG, "Left hand is down");
            }

            // Check if right hand up or down
            if (rightAngle < threshold
                    && rightWrist[2] > visibilityThreshold
                    && rightStatus.equals("down")
            ) {
                rightStatus = "up";
                handStatus[1] = true;
                Log.d(TAG, "Right hand is up");
            }else {
                rightStatus = "down";
                handStatus[1] = false;
                Log.d(TAG, "Right hand is down");
            }

        }else {
            Log.d(TAG, "No hand detected");
            handStatus[0] = false;
            handStatus[1] = false;
        };

        return handStatus;
    }

    /**
     *
     * @param a
     * @param b
     * @param c
     * @return
     */


    public static float calculateAngle(float[] a, float[] b, float[] c) {
        // Convert the points to arrays
        float[] vectorA = Arrays.copyOf(a, a.length);
        float[] vectorB = Arrays.copyOf(b, b.length);
        float[] vectorC = Arrays.copyOf(c, c.length);

        // Calculate the angle in radians
        double radians = Math.atan2(vectorC[1] - vectorB[1], vectorC[0] - vectorB[0])
                - Math.atan2(vectorA[1] - vectorB[1], vectorA[0] - vectorB[0]);

        // Convert to degrees and take the absolute value
        double angle = Math.abs(Math.toDegrees(radians));

        // Normalize the angle
        if (angle > 180.0) {
            angle = 360.0 - angle;
        }

        return (float) angle;
    }

    }




