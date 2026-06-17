package com.wife.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;

public class MessageReceiver implements Runnable {
    private static final String TAG = "MessageReceiver";

    private final Context context;
    private final Socket socket;
    private final boolean isControl;

    public MessageReceiver(Context context, Socket socket, boolean isControl) {
        this.context = context;
        this.socket = socket;
        this.isControl = isControl;
    }

    @Override
    public void run() {
        String clientIp = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "Unknown IP";
        WifeLogger.log(TAG, "MessageReceiver thread started. Socket Category: " + (isControl ? "Control" : "Text") + " | Client IP: " + clientIp);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    WifeLogger.log(TAG, "Received an empty string payload line. Skipping processing.");
                    continue;
                }
                WifeLogger.log(TAG, "Received raw payload line: " + line);

                try {
                    JsonObject jsonObject = JsonParser.parseString(line).getAsJsonObject();
                    String type = jsonObject.has("type") ? jsonObject.get("type").getAsString() : "";
                    WifeLogger.log(TAG, "Parsed JSON packet successfully. Resolved type key: " + type);

                    if (isControl) {
                        handleControlMessage(type, jsonObject);
                    } else {
                        handleTextMessage(type, jsonObject);
                    }
                } catch (Exception parseException) {
                    WifeLogger.log(TAG, "Error parsing incoming JSON packet: " + parseException.getMessage(), parseException);
                }
            }
            WifeLogger.log(TAG, "End of input stream reached for socket from: " + clientIp);
        } catch (Exception e) {
            WifeLogger.log(TAG, "Socket stream read error or exception thrown: " + e.getMessage(), e);
        } finally {
            try {
                if (socket != null && !socket.isClosed()) {
                    WifeLogger.log(TAG, "Closing active connection socket client cleanly.");
                    socket.close();
                }
            } catch (Exception ex) {
                WifeLogger.log(TAG, "Error closing connection socket client: " + ex.getMessage(), ex);
            }
            WifeLogger.log(TAG, "MessageReceiver thread execution finalized for client: " + clientIp);
        }
    }

    private void handleControlMessage(String valType, JsonObject json) {
        String peerIp = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "Unknown IP";
        WifeLogger.log(TAG, "handleControlMessage() invoked. Action code: " + valType + " | Origin IP: " + peerIp);

        // Dynamically update and register client IP address on any control message received
        ConnectionManager.getInstance(context).updatePeerIpFromAccept(peerIp);
        
        // Check for Call Signals
        if (valType.startsWith("CALL_") || valType.startsWith("VIDEO_CALL_")) {
            WifeLogger.log(TAG, "Forwarding calling signal to CallSignalingManager: " + valType);
            CallSignalingManager.getInstance(context).handleReceivedSignal(valType, json, peerIp);
            return;
        }

        // Check for Heartbeat
        if ("heartbeat".equals(valType)) {
            WifeLogger.log(TAG, "Control event matched: 'heartbeat'. Relaying payload to HeartbeatManager.");
            HeartbeatManager.getInstance(context).onHeartbeatReceived(peerIp);
            return;
        }

        if ("handshake".equals(valType)) {
            Log.d(TAG, "Handshake received, client ip updated.");
            WifeLogger.log(TAG, "Control event matched: 'handshake'. Handshake payload processed successfully.");
            // ConnectionManager.getInstance(context).updatePeerIpFromAccept(peerIp); // Redundant now as it is executed on method entry
        }

        // Check for Unsend/Global Delete Signal
        if ("unsend".equals(valType)) {
            WifeLogger.log(TAG, "Control event matched: 'unsend'. Extracting target message parameters.");
            try {
                long targetTimestamp = json.get("timestamp").getAsLong();
                WifeLogger.log(TAG, "Target message timestamp resolved: " + targetTimestamp + ". Executing db purge.");
                
                // 1. Purge from local SQLite database using your newly added DAO query
                RoomDatabaseManager.getInstance(context).messageDao().deleteByTimestamp(targetTimestamp);
                WifeLogger.log(TAG, "Unsent message successfully purged from local database.");

                // 2. Notify the active Chat screen UI to remove the message bubble instantly
                ChatManager.getInstance(context).notifyMessageUnsent(targetTimestamp);
                WifeLogger.log(TAG, "Dispatched unsend notification to active ChatManager observers.");
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed to process incoming unsend control signal: " + e.getMessage(), e);
            }
        }
    }

    private void handleTextMessage(String valType, JsonObject json) {
        WifeLogger.log(TAG, "handleTextMessage() invoked. Data category matches: " + valType);
        if ("message".equals(valType)) {
            try {
                String id = json.get("id").getAsString();
                String sender = json.get("sender").getAsString();
                long time = json.get("time").getAsLong();
                String text = json.get("text").getAsString();

                WifeLogger.log(TAG, "Deserialized text payload. Msg ID: " + id + " | Sender Device: " + sender + " | Text Length: " + text.length());

                // Store in Database
                MessageEntity entity = new MessageEntity(sender, Utils.getDeviceId(context), text, time);
                RoomDatabaseManager.getInstance(context).messageDao().insert(entity);
                WifeLogger.log(TAG, "Incoming text message written successfully to local SQLite Database.");

                // Notify Active Chat Screen via Local Singleton / Broadcast
                ChatManager.getInstance(context).notifyMessageReceived(entity);
                WifeLogger.log(TAG, "Dispatched notification of text message reception to active ChatManager observers.");

                // Check if the Chat Screen is closed/inactive to trigger local alarms and badging
                boolean isChatOpen = ChatManager.getInstance(context).hasListeners();
                WifeLogger.log(TAG, "Is ChatActivity currently active in foreground: " + isChatOpen);
                
                if (!isChatOpen) {
                    WifeLogger.log(TAG, "Chat screen is closed. Incrementing unread badge counts inside preferences.");
                    SharedPreferences prefs = context.getSharedPreferences("WifeSettings", Context.MODE_PRIVATE);
                    int unread = prefs.getInt("unread_count", 0);
                    prefs.edit().putInt("unread_count", unread + 1).apply();

                    // Retrieve custom peer display name or fall back to Sender ID
                    String senderDisplayName = json.has("senderName") ? json.get("senderName").getAsString() : "Offline Peer";
                    WifeLogger.log(TAG, "Triggering high-priority system notification alert for message.");
                    sendSystemNotification(senderDisplayName, text);
                }

            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed to parse or process incoming text message packet: " + e.getMessage(), e);
            }
        } else {
            WifeLogger.log(TAG, "Unmatched text server packet type ignored: " + valType);
        }
    }

    private void sendSystemNotification(String senderName, String messageText) {
        try {
            String channelId = "WifeChatChannel";
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) return;

            // Create notification channel on Android Oreo (API 26) and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "Wife Offline Chat",
                        NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Local Offline Chat Notifications");
                channel.enableVibration(true);
                notificationManager.createNotificationChannel(channel);
            }

            Intent chatIntent = new Intent(context, ChatActivity.class);
            chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    chatIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                    .setContentTitle(senderName)
                    .setContentText(messageText)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setAutoCancel(true);

            notificationManager.notify(2002, builder.build());
            WifeLogger.log(TAG, "System chat alert notification dispatched successfully.");
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed creating or dispatching system chat alert: " + e.getMessage(), e);
        }
    }
}