package com.translator.vsl.handler;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

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
    public static void yuvToRgb(Image image, Bitmap output) {
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
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        Canvas canvas = new Canvas(output);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
    }
}

