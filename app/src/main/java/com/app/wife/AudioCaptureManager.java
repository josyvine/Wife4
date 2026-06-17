package com.wife.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

public class AudioCaptureManager {
    private static final String TAG = "AudioCapture";

    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static volatile AudioCaptureManager instance;

    private final Context context;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordThread;

    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;

    public static AudioCaptureManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AudioCaptureManager.class) {
                if (instance == null) {
                    instance = new AudioCaptureManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public AudioCaptureManager(Context context) {
        this.context = context;
    }

    @SuppressLint("MissingPermission")
    public synchronized void startCapture(final OutputStream outputStream) {
        WifeLogger.log(TAG, "startCapture() invoked. Checking active recording status...");
        if (isRecording) {
            WifeLogger.log(TAG, "startCapture() aborted: Capture thread is already active.");
            return;
        }

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        WifeLogger.log(TAG, "Resolved min buffer size for AudioRecord: " + minBufferSize + " bytes. Initializing AudioRecord driver...");

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufferSize
            );
        } catch (Exception initEx) {
            WifeLogger.log(TAG, "Exception thrown inside AudioRecord constructor: " + initEx.getMessage(), initEx);
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state is not initialized.");
            WifeLogger.log(TAG, "AudioRecord state check failed: STATE_UNINITIALIZED. Capture cannot start.");
            return;
        }

        // Enable hardware Echo Cancellation and Noise Suppression if available
        int audioSessionId = audioRecord.getAudioSessionId();
        WifeLogger.log(TAG, "AudioRecord successfully initialized. Session ID resolved: " + audioSessionId + " | Assessing native sound filters...");
        enableAudioEffects(audioSessionId);

        WifeLogger.log(TAG, "Starting native microphone capture stream.");
        try {
            audioRecord.startRecording();
        } catch (Exception startEx) {
            WifeLogger.log(TAG, "Failed starting microphone capture stream: " + startEx.getMessage(), startEx);
            return;
        }

        isRecording = true;

        recordThread = new Thread(() -> {
            WifeLogger.log(TAG, "Audio capture streaming thread spawned. Starting read-loop on AudioRecord buffer.");
            byte[] buffer = new byte[minBufferSize];
            long totalBytesCaptured = 0;
            try {
                while (isRecording) {
                    int readBytes = audioRecord.read(buffer, 0, buffer.length);
                    if (readBytes > 0) {
                        outputStream.write(buffer, 0, readBytes);
                        totalBytesCaptured += readBytes;
                    } else if (readBytes < 0) {
                        WifeLogger.log(TAG, "AudioRecord read error encountered. Status Code: " + readBytes);
                    }
                }
                WifeLogger.log(TAG, "Exited audio capture streaming read-loop. Total PCM bytes transmitted: " + totalBytesCaptured);
            } catch (Exception e) {
                Log.e(TAG, "Audio capture streaming error: " + e.getMessage());
                WifeLogger.log(TAG, "Audio capture streaming loop encountered an exception: " + e.getMessage(), e);
            }
        });
        recordThread.start();
        Log.d(TAG, "Audio capture started at session " + audioSessionId);
        WifeLogger.log(TAG, "Audio capture thread started successfully.");
    }

    @SuppressLint("MissingPermission")
    public synchronized void startFileRecording(final File outputFile) {
        WifeLogger.log(TAG, "startFileRecording() invoked. Target File: " + outputFile.getAbsolutePath());
        if (isRecording) {
            WifeLogger.log(TAG, "startFileRecording() aborted: Capture thread is already active.");
            return;
        }

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        WifeLogger.log(TAG, "Resolved min buffer size for AudioRecord: " + minBufferSize + " bytes. Initializing AudioRecord driver...");

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufferSize
            );
        } catch (Exception initEx) {
            WifeLogger.log(TAG, "Exception thrown inside AudioRecord constructor: " + initEx.getMessage(), initEx);
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord state is not initialized.");
            WifeLogger.log(TAG, "AudioRecord state check failed: STATE_UNINITIALIZED. Recording cannot start.");
            return;
        }

        int audioSessionId = audioRecord.getAudioSessionId();
        enableAudioEffects(audioSessionId);

        WifeLogger.log(TAG, "Starting native microphone recording stream.");
        try {
            audioRecord.startRecording();
        } catch (Exception startEx) {
            WifeLogger.log(TAG, "Failed starting microphone recording stream: " + startEx.getMessage(), startEx);
            return;
        }

        isRecording = true;

        recordThread = new Thread(() -> {
            WifeLogger.log(TAG, "WAV recording thread spawned.");
            File tempPcmFile = new File(outputFile.getParent(), "temp_" + System.currentTimeMillis() + ".pcm");
            long totalAudioLen = 0;

            try {
                // 1. Record raw PCM samples from MIC input
                try (FileOutputStream os = new FileOutputStream(tempPcmFile)) {
                    byte[] buffer = new byte[minBufferSize];
                    while (isRecording) {
                        int readBytes = audioRecord.read(buffer, 0, buffer.length);
                        if (readBytes > 0) {
                            os.write(buffer, 0, readBytes);
                            totalAudioLen += readBytes;
                        }
                    }
                    os.flush();
                }

                // 2. Wrap recorded PCM data in standard 44-byte WAV header format
                long totalDataLen = totalAudioLen + 36;
                long byteRate = SAMPLE_RATE * 2; // (sampleRate * channels * bitsPerSample / 8) -> 8000 * 1 * 16 / 8 = 16000
                
                try (FileInputStream in = new FileInputStream(tempPcmFile);
                     FileOutputStream out = new FileOutputStream(outputFile)) {
                    
                    writeWavHeader(out, totalAudioLen, totalDataLen, SAMPLE_RATE, 1, byteRate);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }

                // Delete temporary raw PCM buffer file
                if (tempPcmFile.exists()) {
                    tempPcmFile.delete();
                }
                
                WifeLogger.log(TAG, "WAV voice note captured successfully: " + outputFile.getAbsolutePath() + " | Size: " + outputFile.length() + " bytes");

            } catch (Exception e) {
                Log.e(TAG, "WAV recording stream failed: " + e.getMessage());
                WifeLogger.log(TAG, "WAV recording thread encountered an exception: " + e.getMessage(), e);
            }
        });
        recordThread.start();
        Log.d(TAG, "WAV capture active at session " + audioSessionId);
    }

    private void enableAudioEffects(int audioSessionId) {
        if (AcousticEchoCanceler.isAvailable()) {
            WifeLogger.log(TAG, "Hardware AcousticEchoCanceler is supported. Attempting binding...");
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                    WifeLogger.log(TAG, "AcousticEchoCanceler enabled successfully.");
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed enabling AcousticEchoCanceler: " + e.getMessage(), e);
            }
        } else {
            WifeLogger.log(TAG, "Hardware AcousticEchoCanceler is not supported on this device.");
        }

        if (NoiseSuppressor.isAvailable()) {
            WifeLogger.log(TAG, "Hardware NoiseSuppressor is supported. Attempting binding...");
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                    WifeLogger.log(TAG, "NoiseSuppressor enabled successfully.");
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed enabling NoiseSuppressor: " + e.getMessage(), e);
            }
        } else {
            WifeLogger.log(TAG, "Hardware NoiseSuppressor is not supported on this device.");
        }
    }

    private void writeWavHeader(FileOutputStream out, long totalAudioLen, long totalDataLen, long longSampleRate, int channels, long byteRate) throws IOException {
        byte[] header = new byte[44];
        header[0] = 'R'; // RIFF chunk descriptor
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; // WAVE format descriptor
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f'; // 'fmt ' sub-chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16; // 16 for PCM format data size
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // PCM linear format code = 1
        header[21] = 0;
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * 2); // Block alignment
        header[33] = 0;
        header[34] = 16; // Bits per sample = 16 bit depth
        header[35] = 0;
        header[36] = 'd'; // 'data' sub-chunk
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
        out.write(header, 0, 44);
    }

    public synchronized void stopCapture() {
        WifeLogger.log(TAG, "stopCapture() invoked. Halting capture loops and releasing audio hardware...");
        isRecording = false;
        if (recordThread != null) {
            WifeLogger.log(TAG, "Interrupting active audio capture thread.");
            recordThread.interrupt();
            recordThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    WifeLogger.log(TAG, "Stopping native AudioRecord capture stream.");
                    audioRecord.stop();
                }
                WifeLogger.log(TAG, "Releasing native AudioRecord resource buffers.");
                audioRecord.release();
            } catch (Exception e) {
                e.printStackTrace();
                WifeLogger.log(TAG, "Error stopping or releasing AudioRecord resource: " + e.getMessage(), e);
            }
            audioRecord = null;
        }
        if (echoCanceler != null) {
            try {
                WifeLogger.log(TAG, "Releasing hardware AcousticEchoCanceler.");
                echoCanceler.release();
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error releasing AcousticEchoCanceler: " + e.getMessage(), e);
            }
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try {
                WifeLogger.log(TAG, "Releasing hardware NoiseSuppressor.");
                noiseSuppressor.release();
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error releasing NoiseSuppressor: " + e.getMessage(), e);
            }
            noiseSuppressor = null;
        }
        Log.d(TAG, "Audio capture stopped.");
        WifeLogger.log(TAG, "Audio capture halted cleanly.");
    }
}