package com.translator.vsl.handler;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

public class VideoUtils {

    public static Uri createVideoFromBitmaps(Context context, List<Bitmap> bitmaps, int frameRate) throws Exception {
        if (bitmaps == null || bitmaps.isEmpty()) {
            throw new IllegalArgumentException("Bitmap list is empty or null");
        }

        int width = bitmaps.get(0).getWidth();
        int height = bitmaps.get(0).getHeight();
        int bitRate = 2000000; // 2 Mbps

        File outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        File outputFile = new File(outputDir, "output_video.mp4");
        if (outputFile.exists()) outputFile.delete();

        MediaMuxer mediaMuxer = null;
        MediaCodec codec = null;

        try {
            mediaMuxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            Surface inputSurface = codec.createInputSurface();
            codec.start();

            int trackIndex = mediaMuxer.addTrack(codec.getOutputFormat());
            mediaMuxer.start();

            for (int i = 0; i < bitmaps.size(); i++) {
                Bitmap bitmap = bitmaps.get(i);
                addBitmapToSurface(inputSurface, bitmap);

                boolean isEndOfStream = i == bitmaps.size() - 1;
                drainEncoder(codec, mediaMuxer, trackIndex, isEndOfStream);
            }

        } catch (Exception e) {
            throw new RuntimeException("Error during video creation: " + e.getMessage(), e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception e) {
                    // Log codec stop error
                }
                codec.release();
            }
            if (mediaMuxer != null) {
                try {
                    mediaMuxer.stop();
                } catch (IllegalStateException e) {
                    // Handle stop error (already stopped, etc.)
                }
                mediaMuxer.release();
            }
        }

        return Uri.fromFile(outputFile);
    }

    private static void addBitmapToSurface(Surface surface, Bitmap bitmap) {
        Canvas canvas = surface.lockCanvas(null);
        canvas.drawBitmap(bitmap, 0, 0, null);
        surface.unlockCanvasAndPost(canvas);
    }

    private static void drainEncoder(MediaCodec codec, MediaMuxer mediaMuxer, int trackIndex, boolean endOfStream) {
        if (endOfStream) {
            codec.signalEndOfInputStream();
        }

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Ignore format changes
            } else if (outputBufferIndex >= 0) {
                ByteBuffer encodedData = codec.getOutputBuffer(outputBufferIndex);
                if (encodedData != null) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size != 0) {
                        mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                }
            }
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
        }
    }
}