package com.wife.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoCaptureManager {
    private static final String TAG = "VideoCaptureManager";

    private final Context context;
    private final ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private boolean isCapturing = false;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;

    // Static listener to relay local camera frame bitmaps back to our active UI layout
    private static volatile LocalFrameListener localFrameListener;

    public interface LocalFrameListener {
        void onLocalFrameCaptured(Bitmap bitmap);
    }

    public static void setLocalFrameListener(LocalFrameListener listener) {
        localFrameListener = listener;
    }

    public VideoCaptureManager(Context context) {
        this.context = context;
        this.cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @SuppressLint("UnsafeOptInUsageError")
    public void startCapture(final LifecycleOwner lifecycleOwner, final OutputStream outputStream) {
        WifeLogger.log(TAG, "startCapture() invoked. Checking active camera capture status...");
        if (isCapturing) {
            WifeLogger.log(TAG, "startCapture() aborted: Camera capture is already active.");
            return;
        }
        isCapturing = true;

        WifeLogger.log(TAG, "Transitioning to main executor to initialize CameraX providers...");
        ContextCompat.getMainExecutor(context).execute(() -> {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(context).get();
                WifeLogger.log(TAG, "CameraX ProcessCameraProvider successfully retrieved.");
                
                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();
                WifeLogger.log(TAG, "CameraSelector built successfully. Lens Direction Index: " + lensFacing);

                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(320, 240))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                WifeLogger.log(TAG, "ImageAnalysis configured with resolution 320x240 (Keep Only Latest).");

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    try {
                        if (!isCapturing) {
                            WifeLogger.log(TAG, "Analyzer callback triggered but isCapturing is false. Releasing frame.");
                            imageProxy.close();
                            return;
                        }

                        Image img = imageProxy.getImage();
                        if (img != null) {
                            byte[] jpegData = convertYuvToJpeg(imageProxy);
                            if (jpegData != null) {
                                // If a local listener is registered, decode the JPEG locally and dispatch it to the PIP view
                                LocalFrameListener listener = localFrameListener;
                                if (listener != null) {
                                    try {
                                        Bitmap localBitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                                        if (localBitmap != null) {
                                            listener.onLocalFrameCaptured(localBitmap);
                                        }
                                    } catch (Exception decodeEx) {
                                        WifeLogger.log(TAG, "Failed to decode captured preview frame: " + decodeEx.getMessage(), decodeEx);
                                    }
                                }

                                // Write JPEG size as integer (4 bytes), then the actual byte payload
                                ByteBuffer sizeBuf = ByteBuffer.allocate(4);
                                sizeBuf.putInt(jpegData.length);
                                
                                synchronized (outputStream) {
                                    outputStream.write(sizeBuf.array());
                                    outputStream.write(jpegData);
                                    outputStream.flush();
                                }
                            } else {
                                WifeLogger.log(TAG, "Analyzer: Stride-aware YUV conversion returned a null JPEG payload. Frame skipped.");
                            }
                        } else {
                            WifeLogger.log(TAG, "Analyzer: ImageProxy wrapped image was null. Frame skipped.");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Frame analysis stream failed: " + e.getMessage());
                        WifeLogger.log(TAG, "Frame analyzer loop or stream output encountered an exception: " + e.getMessage(), e);
                    } finally {
                        imageProxy.close();
                    }
                });

                WifeLogger.log(TAG, "Unbinding previous camera configurations from provider.");
                cameraProvider.unbindAll();
                
                WifeLogger.log(TAG, "Binding CameraX lifecycle to current LifecycleOwner.");
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis);
                Log.d(TAG, "CameraX analyzer configured and bound.");
                WifeLogger.log(TAG, "CameraX pipeline successfully bound to active UI context.");

            } catch (Exception e) {
                Log.e(TAG, "CameraX initialization failed: " + e.getMessage());
                WifeLogger.log(TAG, "Failed initializing CameraX components: " + e.getMessage(), e);
            }
        });
    }

    public synchronized void switchCamera(final LifecycleOwner lifecycleOwner, final OutputStream outputStream) {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_FRONT) ? 
                CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
        WifeLogger.log(TAG, "switchCamera() invoked. Toggling target lens facing index to: " + lensFacing);
        if (isCapturing) {
            WifeLogger.log(TAG, "Re-binding camera capture with updated lens configuration.");
            stopCapture();
            startCapture(lifecycleOwner, outputStream);
        }
    }

    public synchronized void stopCapture() {
        WifeLogger.log(TAG, "stopCapture() invoked. Halting camera capture and unbinding providers...");
        isCapturing = false;
        if (cameraProvider != null) {
            WifeLogger.log(TAG, "Unbinding all CameraX pipeline use cases.");
            cameraProvider.unbindAll();
        }
        WifeLogger.log(TAG, "Camera capture unbind processes finalized.");
    }

    private byte[] convertYuvToJpeg(ImageProxy image) {
        int width = image.getWidth();
        int height = image.getHeight();

        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        yBuffer.rewind();
        uBuffer.rewind();
        vBuffer.rewind();

        // NV21 requires exactly width * height for Y, and width * height / 2 for interleaved VU
        byte[] nv21 = new byte[width * height + (width * height / 2)];

        // Copy Y plane respecting row strides and pixel strides
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int pos = 0;

        for (int row = 0; row < height; row++) {
            yBuffer.position(row * yRowStride);
            for (int col = 0; col < width; col++) {
                nv21[pos++] = yBuffer.get();
                if (yPixelStride > 1 && col < width - 1) {
                    yBuffer.position(yBuffer.position() + yPixelStride - 1);
                }
            }
        }

        // Interleave V and U plane parameters (NV21 chroma expects V, then U)
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        int chromaHeight = height / 2;
        int chromaWidth = width / 2;

        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                int vPos = row * vRowStride + col * vPixelStride;
                int uPos = row * uRowStride + col * uPixelStride;

                // Defensive check to avoid buffer out of bounds
                if (vPos < vBuffer.capacity() && uPos < uBuffer.capacity()) {
                    nv21[pos++] = vBuffer.get(vPos); // V goes first
                    nv21[pos++] = uBuffer.get(uPos); // U goes second
                }
            }
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 70, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Nv21 compression to JPEG failed: " + e.getMessage());
            WifeLogger.log(TAG, "Stride-aware conversion NV21-to-JPEG compression failed: " + e.getMessage(), e);
            return null;
        }
    }
}