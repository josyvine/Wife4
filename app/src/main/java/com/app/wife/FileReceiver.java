package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.jpountz.lz4.LZ4FrameInputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FileReceiver implements Runnable {
    private static final String TAG = "FileReceiver";

    private final Context context;
    private final Socket socket;
    private final Handler mainHandler;

    public interface FileReceiveListener {
        void onProgress(String filename, int percent);
        void onComplete(String filename, String localPath);
        void onError(String error);
    }

    private static final List<FileReceiveListener> listeners = new ArrayList<>();

    public static synchronized void registerListener(FileReceiveListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static synchronized void unregisterListener(FileReceiveListener listener) {
        listeners.remove(listener);
    }

    /**
     * Backward-compatible legacy constructor.
     */
    public FileReceiver(Context context, Socket socket) {
        this.context = context;
        this.socket = socket;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Backward-compatible thread entry point.
     */
    @Override
    public void run() {
        WifeLogger.log(TAG, "Legacy FileReceiver runnable invoked. Redirecting to single socket processor.");
        try {
            socket.getChannel().configureBlocking(true);
            processPersistentStream(context, socket.getChannel());
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error executing legacy fallback: " + e.getMessage(), e);
            notifyError(e.getMessage());
        }
    }

    /**
     * Symmetrical server socket entry point called directly by the active foreground service.
     */
    public static void startServer(final Context context) {
        new Thread(() -> {
            ServerSocketChannel serverChannel = null;
            SocketChannel clientChannel = null;
            try {
                WifeLogger.log(TAG, "Opening ServerSocketChannel on port: " + Constants.OFF_PORT_FILE);
                serverChannel = ServerSocketChannel.open();
                serverChannel.socket().bind(new InetSocketAddress(Constants.OFF_PORT_FILE));
                WifeLogger.log(TAG, "ServerSocketChannel successfully bound. Entering persistent accept loop.");

                // Symmetrical Fix: The accept loop tracks the ServerSocketChannel's open state.
                // It remains active and listening regardless of individual transfer cancellations.
                while (serverChannel.isOpen()) {
                    clientChannel = serverChannel.accept();
                    clientChannel.configureBlocking(true);
                    
                    String clientIp = clientChannel.socket().getInetAddress().getHostAddress();
                    WifeLogger.log(TAG, "Accepted persistent transfer stream connection from: " + clientIp);

                    processPersistentStream(context, clientChannel);
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "ServerSocketChannel threw exception or was closed: " + e.getMessage());
                if (serverChannel != null && serverChannel.isOpen()) {
                    broadcastError(context, e.getMessage());
                }
            } finally {
                try {
                    if (clientChannel != null) clientChannel.close();
                    if (serverChannel != null) serverChannel.close();
                } catch (IOException ignored) {}
            }
        }).start();
    }

    /**
     * Persistent Multi-File Stream Processor.
     * Processes metadata headers and LZ4 decompression segments sequentially over the active SocketChannel.
     */
    private static void processPersistentStream(Context context, SocketChannel socketChannel) throws Exception {
        // Reset static cancellation state for this fresh inbound transaction
        FileTransferForegroundService.isCancelled = false;
        FileTransferForegroundService.isPaused = false;

        InputStream rawSocketIn = socketChannel.socket().getInputStream();
        NonClosingInputStream proxyIn = new NonClosingInputStream(rawSocketIn);
        int fileIndex = 0;

        while (!FileTransferForegroundService.isCancelled && socketChannel.isConnected()) {
            // 1. Read the 4-byte metadata length header
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            int bytesRead = 0;
            while (bytesRead < 4 && !FileTransferForegroundService.isCancelled) {
                int read = socketChannel.read(lenBuf);
                if (read == -1) {
                    WifeLogger.log(TAG, "Stream ended abruptly while reading metadata length.");
                    return;
                }
                bytesRead += read;
            }

            if (FileTransferForegroundService.isCancelled) {
                break;
            }

            lenBuf.flip();
            int metadataLength = lenBuf.getInt();

            // 0 metadata length indicates the persistent queue transfer session has completed naturally
            if (metadataLength == 0) {
                WifeLogger.log(TAG, "End of persistent queue stream marker received. Closing stream.");
                broadcastCompletion(context);
                break;
            }

            // 2. Read exactly the serialized JSON metadata payload
            ByteBuffer metaBuf = ByteBuffer.allocate(metadataLength);
            bytesRead = 0;
            while (bytesRead < metadataLength && !FileTransferForegroundService.isCancelled) {
                int read = socketChannel.read(metaBuf);
                if (read == -1) {
                    throw new IOException("Stream ended abruptly while reading metadata payload.");
                }
                bytesRead += read;
            }

            if (FileTransferForegroundService.isCancelled) {
                break;
            }

            metaBuf.flip();
            String metaJson = StandardCharsets.UTF_8.decode(metaBuf).toString();
            JsonObject meta = JsonParser.parseString(metaJson).getAsJsonObject();

            final String filename = meta.get("name").getAsString();
            final long fileSize = meta.get("size").getAsLong();
            long resumePosition = meta.has("lastPosition") ? meta.get("lastPosition").getAsLong() : 0;

            WifeLogger.log(TAG, "Processing incoming file: " + filename + " (" + fileSize + " bytes) | Resume Position: " + resumePosition);

            File targetDir = getTargetDirectory(context, filename);
            File fileDest = new File(targetDir, filename);

            // 3. Decompress the on-the-fly streaming payload using the LZ4 framing engine
            // Wrap in NonClosingInputStream to prevent LZ4 close from terminating the SocketChannel
            try (LZ4FrameInputStream lz4In = new LZ4FrameInputStream(proxyIn);
                 FileOutputStream fos = new FileOutputStream(fileDest, resumePosition > 0)) {

                byte[] buffer = new byte[16384]; // 16KB buffers matching the sender speed
                int read;
                long totalRead = resumePosition;
                long lastNotificationUpdateTime = System.currentTimeMillis();

                while (!FileTransferForegroundService.isCancelled) {
                    // Symmetrical Thread-Safe Pause/Resume wait monitor locks
                    synchronized (FileTransferForegroundService.pauseLock) {
                        while (FileTransferForegroundService.isPaused && !FileTransferForegroundService.isCancelled) {
                            try {
                                WifeLogger.log(TAG, "Receiver thread entering wait state due to active pause command.");
                                FileTransferForegroundService.pauseLock.wait();
                            } catch (InterruptedException e) {
                                WifeLogger.log(TAG, "Receiver pause monitor thread interrupted.");
                                Thread.currentThread().interrupt();
                            }
                        }
                    }

                    if (FileTransferForegroundService.isCancelled) {
                        break;
                    }

                    read = lz4In.read(buffer);
                    if (read == -1) {
                        break; // EOF for this specific LZ4 frame block reached
                    }

                    fos.write(buffer, 0, read);
                    totalRead += read;

                    // Throttle notification updates
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastNotificationUpdateTime >= 500) {
                        int percent = (int) ((totalRead * 100) / fileSize);
                        notifyProgress(context, filename, percent, totalRead, fileSize, fileIndex);
                        lastNotificationUpdateTime = currentTime;
                    }
                }

                fos.flush();

                if (!FileTransferForegroundService.isCancelled) {
                    WifeLogger.log(TAG, "File successfully saved: " + fileDest.getAbsolutePath());

                    // Save history record in database
                    FileEntity entity = new FileEntity(filename, fileSize, fileDest.getAbsolutePath(), System.currentTimeMillis());
                    RoomDatabaseManager.getInstance(context).fileDao().insert(entity);

                    notifyComplete(context, filename, fileDest.getAbsolutePath(), fileIndex);
                    fileIndex++;
                }
            }
        }
    }

    private static File getTargetDirectory(Context context, String filename) {
        File rootDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "wife shared");
        } else {
            rootDir = new File(Environment.getExternalStorageDirectory(), "wife shared");
        }

        String ext = "";
        int idx = filename.lastIndexOf('.');
        if (idx > 0) {
            ext = filename.substring(idx + 1).toLowerCase(Locale.US);
        }

        String subFolder;
        switch (ext) {
            case "mp3":
            case "emv":
            case "wav":
            case "ogg":
            case "m4a":
            case "aac":
                subFolder = "music";
                break;
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
            case "webp":
                subFolder = "images";
                break;
            case "mp4":
            case "mkv":
            case "avi":
            case "mov":
            case "3gp":
            case "webm":
                subFolder = "videos";
                break;
            case "pdf":
            case "txt":
            case "doc":
            case "docx":
            case "xls":
            case "xlsx":
            case "ppt":
            case "pptx":
                subFolder = "document";
                break;
            default:
                subFolder = "misc";
                break;
        }

        File targetDir = new File(rootDir, subFolder);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        return targetDir;
    }

    // --- UI/Notification Broadcast dispatchers ---

    private static void notifyProgress(Context context, final String filename, final int percent, long transferred, long total, int fileIndex) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onProgress(filename, percent);
                }
            }
        });

        // Intent broadcast to FileTransferActivity
        Intent intent = new Intent(Constants.ACTION_TRANSFER_PROGRESS);
        intent.putExtra(Constants.EXTRA_FILE_NAME, filename);
        intent.putExtra(Constants.EXTRA_BYTES_TRANSFERRED, transferred);
        intent.putExtra(Constants.EXTRA_TOTAL_BYTES, total);
        intent.putExtra(Constants.EXTRA_FILE_INDEX, fileIndex);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

        // Foreground Service Notification update
        Intent serviceIntent = new Intent(context, FileTransferForegroundService.class);
        serviceIntent.setAction("UPDATE_NOTIF");
        serviceIntent.putExtra("NOTIF_TEXT", "Receiving " + filename + " (" + percent + "%)");
        serviceIntent.putExtra("PROGRESS", percent);
        context.startService(serviceIntent);
    }

    private static void notifyComplete(Context context, final String filename, final String path, int fileIndex) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onComplete(filename, path);
                }
            }
        });

        Intent intent = new Intent(Constants.ACTION_TRANSFER_PROGRESS);
        intent.putExtra(Constants.EXTRA_FILE_NAME, filename);
        intent.putExtra(Constants.EXTRA_BYTES_TRANSFERRED, 1L); // Force complete UI redraw
        intent.putExtra(Constants.EXTRA_TOTAL_BYTES, 1L);
        intent.putExtra(Constants.EXTRA_FILE_INDEX, fileIndex);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private static void broadcastCompletion(Context context) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_COMPLETE);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private static void broadcastError(Context context, String message) {
        Intent intent = new Intent(Constants.ACTION_TRANSFER_ERROR);
        intent.putExtra(Constants.EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private static void notifyError(final String error) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (FileReceiver.class) {
                for (FileReceiveListener l : listeners) {
                    l.onError(error);
                }
            }
        });
    }

    // --- Symmetrical Non-Closing Socket Stream Wrapper ---
    private static class NonClosingInputStream extends InputStream {
        private final InputStream delegate;

        public NonClosingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            // Intercept close() commands to prevent closing the underlying persistent socket
            Log.d(TAG, "Intercepted close() request. Stream remains open.");
        }
    }
}