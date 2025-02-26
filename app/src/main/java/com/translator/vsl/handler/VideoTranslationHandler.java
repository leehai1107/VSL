package com.translator.vsl.handler;

import static java.lang.Math.min;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.label.Category;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class VideoTranslationHandler {
    private static final String TAG = "VideoTranslationHandler";
    private final Interpreter tflite;
    private static int[] INPUT_SHAPE = null;
    private static int INPUT_WIDTH = 224;  // Width of input frame
    private static int INPUT_HEIGHT = 224; // Height of input frame
    private static int INPUT_CHANNELS = 3; // RGB
    private static int INPUT_NUM_FRAMES = 5;    // Number of frames per video
    private static int FRAME_STEP = 5;    // Step between frames
    private static final String IMAGE_INPUT_NAME = "image";
    private static final String SIGNATURE_KEY = "serving_default";
    private final int outputCategoryCount;
    private static final String LOGITS_OUTPUT_NAME = "logits";
    private HashMap<String, Object> inputState;
    private final Object lock = new Object();
    private final Map<Integer, String> labelMap;
    private static final float INPUT_MEAN = 0f;
    private static final float INPUT_STD = 255f;



    public VideoTranslationHandler(Context context, String modelPath,String labelPath) throws IOException {
        // Load TFLite model
        tflite = new Interpreter(loadModelFile(context, modelPath));
        this.outputCategoryCount = tflite.getOutputTensorFromSignature(LOGITS_OUTPUT_NAME, SIGNATURE_KEY).shape()[1];
        this.inputState = initializeInput();
        this.labelMap = loadLabelMapping(context, labelPath);
        INPUT_SHAPE = tflite.getInputTensorFromSignature(IMAGE_INPUT_NAME, SIGNATURE_KEY).shape();
//             Shape: [  1   1 224 224   3]
        INPUT_NUM_FRAMES = INPUT_SHAPE[1];
        INPUT_WIDTH = INPUT_SHAPE[2];
        INPUT_HEIGHT = INPUT_SHAPE[3];
        INPUT_CHANNELS = INPUT_SHAPE[4];

    }

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(context.getAssets().openFd(modelPath).getFileDescriptor());
             FileChannel fileChannel = inputStream.getChannel()) {
            long startOffset = context.getAssets().openFd(modelPath).getStartOffset();
            long declaredLength = context.getAssets().openFd(modelPath).getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    private Map<Integer, String> loadLabelMapping(Context context, String filePath) throws IOException {
        Map<Integer, String> labelMap = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open(filePath)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 2); // Split into index and label
                if (parts.length == 2) {
                    int index = Integer.parseInt(parts[0].trim());
                    String label = parts[1].trim();
                    labelMap.put(index, label);
                }
            }
        }
        return labelMap;
    }


    /**
     * Initialize the input objects and fill them with zeros.
     Input :
     Name: serving_default_image:0
     Shape: [  1   1 224 224   3]
     Data Type: <class 'numpy.float32'>
     Quantization: (0.0, 0)
     */
    private HashMap<String, Object> initializeInput() {
        HashMap<String, Object> inputs = new HashMap<>();
        for (String inputName : tflite.getSignatureInputs(SIGNATURE_KEY)) {
            if (inputName.equals(IMAGE_INPUT_NAME)) {
                continue;
            }

            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(tflite.getInputTensorFromSignature(inputName, SIGNATURE_KEY).numBytes());
            byteBuffer.order(ByteOrder.nativeOrder());
            inputs.put(inputName, byteBuffer);
        }

        return inputs;
    }

    /**
     * Initialize the output objects to store the TFLite model outputs.
     Output :
     Name: StatefulPartitionedCall:10
     Shape: [1]
     Data Type: <class 'numpy.int32'>
     Quantization: (0.0, 0)
     */
    private HashMap<String, Object> initializeOutput() {
        HashMap<String, Object> outputs = new HashMap<>();
        for (String outputName : tflite.getSignatureOutputs(SIGNATURE_KEY)) {
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(tflite.getOutputTensorFromSignature(outputName, SIGNATURE_KEY).numBytes());
            byteBuffer.order(ByteOrder.nativeOrder());
            outputs.put(outputName, byteBuffer);
        }

        return outputs;
    }

    /**
     * Convert output logits of the model to a list of Category objects.
     */
    private List<Category> postprocessOutputLogits(ByteBuffer logitsByteBuffer) {
        float[] logits = new float[outputCategoryCount];
        logitsByteBuffer.rewind();
        logitsByteBuffer.asFloatBuffer().get(logits);

        float[] probabilities = CalculateUtils.softmax(logits);
        List<Category> categories = new ArrayList<>();
        for (int index = 0; index < probabilities.length; index++) {
            categories.add(new Category(String.valueOf(index), probabilities[index]));}
        return categories;
    }

    private TensorImage preprocessInputImage(Bitmap bitmap, int inputWidth, int inputHeight) {
        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                .add(new ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.NEAREST_NEIGHBOR))
                .add(new ResizeWithCropOrPadOp(inputHeight, inputWidth))
                .add(new NormalizeOp(INPUT_MEAN, INPUT_STD))
                .build();
        TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
        tensorImage.load(bitmap);

        return imageProcessor.process(tensorImage);
    }




    public CompletableFuture<String> translateVideoAsync(Context context, Uri videoUri) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                try {
                    List<Bitmap> videoFrames = extractFramesFromVideoAsync(context, videoUri, 20).join(); // Wait for frames
                    List<Category> categories = new ArrayList<>();

                    for (Bitmap frame : videoFrames) {
                        HashMap<String, Object> outputs = initializeOutput();

                        // Extract and preprocess video frames
                        TensorImage tensorImage = preprocessInputImage(frame,INPUT_WIDTH,INPUT_HEIGHT);
                        inputState.put(IMAGE_INPUT_NAME, tensorImage.getBuffer());

                        tflite.runSignature(inputState, outputs);

                        categories = postprocessOutputLogits((ByteBuffer) outputs.get(LOGITS_OUTPUT_NAME));

                        outputs.remove(LOGITS_OUTPUT_NAME);
                        inputState = outputs;
                    }

                    // Compare the categories sorted by score and log the one with the highest score
                    categories.sort((c1, c2) -> Float.compare(c2.getScore(), c1.getScore()));

                    // the label with the top 3 highest scores mapped to a string by use labelMap.getOrDefault, the delimeter is increased from 1 to 3
                    // Ex:  1. Abc \n  2. Def \n  3. Ghi
//                    String result = new String();
//
//                    for (int i = 0; i < 5; i++) {
//                        Category category = categories.get(i);
//                        result += (i+1) + ". " + labelMap.getOrDefault(Integer.parseInt(category.getLabel()), "Unknown") + "\n";
//                    }
//
//                    return result;

                    // Get the label index with the highest score
                    int highestLabelIndex = Integer.parseInt(categories.get(0).getLabel());

                    // Map the index to its textual description
                    return labelMap.getOrDefault(highestLabelIndex, "Unknown word");


                } catch (Exception e) {
                    Log.e(TAG, "Error translating video: " + e.getMessage());
                    return "Lỗi khi dịch: " + e.getMessage();
                }
            }
        });
    }

    public CompletableFuture<List<Bitmap>> extractFramesFromVideoAsync(Context context, Uri videoUri, int numFrames) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Bitmap> frames;
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                    retriever.setDataSource(context, videoUri);

                    frames = new ArrayList<>();
                    String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    long duration = Long.parseLong(durationStr) * 1000L; // Convert duration to microseconds

                    // Calculate interval between frames
                    long interval = duration / numFrames;

                    for (int i = 0; i < numFrames; i++) {
                        long timestamp = i * interval;
                        Bitmap frame = retriever.getFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST);
                        if (frame != null) {
                            frames.add(frame.copy(Bitmap.Config.ARGB_8888, true));
                        } else {
                            Log.w("Frame_TAG", "Frame at timestamp " + timestamp + " is null");
                        }
                    }

                    if (frames.size() < numFrames) {
                        int missingFrames = numFrames - frames.size();
                        Bitmap blankFrame = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(blankFrame);
                        canvas.drawColor(Color.BLACK); // Fill with black
                        for (int i = 0; i < missingFrames; i++) {
                            frames.add(blankFrame);
                        }
                    }


                    retriever.release();
                }
                return frames;

            } catch (Exception e) {
                Log.e(TAG, "Error extracting frames: " + e.getMessage());
                return new ArrayList<>();
            }
        });
    }


    /**
     * Reset the interpreter to the initial state.
     */
    public void reset() {
        synchronized (lock) {
            inputState = initializeInput();
        }
    }

    /**
     * Close the interpreter when it's no longer needed.
     */
    public void close() {
        tflite.close();
    }
}


