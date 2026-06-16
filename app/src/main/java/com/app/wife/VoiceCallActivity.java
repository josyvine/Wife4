package com.wife.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wife.app.databinding.ActivityVoiceCallBinding;
import com.google.gson.JsonObject;

public class VoiceCallActivity extends AppCompatActivity implements CallSignalingManager.SignalingEventListener {

    private static final String TAG = "VoiceCallActivity";

    private ActivityVoiceCallBinding binding;
    private VoiceCallManager voiceCallManager;
    private String peerIp;
    private String peerName;
    private boolean isInbound;
    private boolean isMuted = false;
    private boolean isSpeaker = true;

    private int callDurationSeconds = 0;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    private MediaPlayer ringtonePlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Configure window flags to wake the screen backlight, keep it on, and bypass locks
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED 
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        binding = ActivityVoiceCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log(TAG, "onCreate() invoked. Initializing VoiceCallActivity components.");

        voiceCallManager = VoiceCallManager.getInstance(this);

        // Fetch intent boundaries
        peerIp = getIntent().getStringExtra(Constants.EXTRA_PEER_IP);
        peerName = getIntent().getStringExtra(Constants.EXTRA_PEER_NAME);
        isInbound = getIntent().getBooleanExtra("IS_INBOUND", false);

        if (peerIp == null || peerIp.isEmpty()) {
            peerIp = ConnectionManager.getInstance(this).getPeerIpAddress();
        }
        if (peerName == null || peerName.isEmpty()) {
            peerName = "Mesh Peer";
        }

        WifeLogger.log(TAG, "Resolved voice call intent. Peer IP: " + peerIp + " | Name: " + peerName + " | Inbound: " + isInbound);

        binding.tvVoicePeerName.setText(peerName);

        // Load custom peer profile photo if passed in the intent
        String base64Image = getIntent().getStringExtra("PEER_PROFILE_PHOTO");
        if (base64Image != null && !base64Image.isEmpty()) {
            try {
                WifeLogger.log(TAG, "Custom peer profile photo string discovered in intent extras. Attempting decoding...");
                byte[] decodedByte = Base64.decode(base64Image, Base64.DEFAULT);
                Bitmap decodedBitmap = BitmapFactory.decodeByteArray(decodedByte, 0, decodedByte.length);
                if (decodedBitmap != null) {
                    binding.ivVoiceAvatar.setImageBitmap(decodedBitmap);
                    WifeLogger.log(TAG, "Successfully decoded and mapped peer profile photo.");
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed decoding peer profile photo base64 payload: " + e.getMessage(), e);
            }
        } else {
            WifeLogger.log(TAG, "No custom peer profile photo found in intent extras. Standard fallback icon remains.");
        }

        setupClickListeners();
        configureCallingState();

        // Start playing ringtone if it's an incoming call
        if (isInbound) {
            startRingtone();
        }
    }

    private void startRingtone() {
        WifeLogger.log(TAG, "Initializing incoming call ringtone playback.");
        try {
            ringtonePlayer = MediaPlayer.create(this, R.raw.wife_ringtone);
            if (ringtonePlayer != null) {
                ringtonePlayer.setLooping(true);
                ringtonePlayer.start();
                WifeLogger.log(TAG, "Branded ringtone is now playing.");
            } else {
                WifeLogger.log(TAG, "Failed creating MediaPlayer instance for wife_ringtone.");
            }
        } catch (Exception e) {
            WifeLogger.log(TAG, "Error playing custom branded ringtone: " + e.getMessage(), e);
        }
    }

    private void stopRingtone() {
        if (ringtonePlayer != null) {
            try {
                WifeLogger.log(TAG, "Stopping and releasing local ringtone MediaPlayer.");
                if (ringtonePlayer.isPlaying()) {
                    ringtonePlayer.stop();
                }
                ringtonePlayer.release();
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error cleanly releasing ringtone player: " + e.getMessage(), e);
            }
            ringtonePlayer = null;
        }
    }

    private void setupClickListeners() {
        binding.fabMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            WifeLogger.log(TAG, "User toggled mic mute state. Value: " + isMuted);
            voiceCallManager.muteMicrophone(isMuted);
            binding.fabMute.setImageResource(isMuted ? 
                    android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_btn_speak_now);
            Toast.makeText(this, isMuted ? "Microphone muted" : "Microphone active", Toast.LENGTH_SHORT).show();
        });

        binding.fabSpeaker.setOnClickListener(v -> {
            isSpeaker = !isSpeaker;
            WifeLogger.log(TAG, "User toggled speakerphone state. Value: " + isSpeaker);
            voiceCallManager.setSpeakerphoneEnabled(isSpeaker);
            binding.fabSpeaker.setImageResource(isSpeaker ? 
                    android.R.drawable.ic_lock_silent_mode_off : android.R.drawable.ic_lock_silent_mode);
            Toast.makeText(this, isSpeaker ? "Speaker active" : "Earpiece active", Toast.LENGTH_SHORT).show();
        });

        binding.fabAcceptVoice.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User accepted inbound call.");
            acceptCall();
        });

        binding.fabDeclineVoice.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User declined or ended call.");
            declineOrEndCall();
        });
    }

    private void configureCallingState() {
        WifeLogger.log(TAG, "Registering CallingState listener with CallSignalingManager.");
        CallSignalingManager.getInstance(this).registerListener(this);

        if (isInbound) {
            WifeLogger.log(TAG, "State configured as: INBOUND_REQUEST.");
            binding.tvVoiceCallState.setText("Inbound Voice Call...");
            binding.fabAcceptVoice.setVisibility(View.VISIBLE);
        } else {
            WifeLogger.log(TAG, "State configured as: OUTBOUND_CALL. Dispatching signal request to: " + peerIp);
            binding.tvVoiceCallState.setText("Calling...");
            binding.fabAcceptVoice.setVisibility(View.GONE);
            // Send request over control signal
            CallSignalingManager.getInstance(this).sendSignal(peerIp, Constants.SIGNAL_CALL_REQUEST);
        }
    }

    private void acceptCall() {
        stopRingtone();
        binding.fabAcceptVoice.setVisibility(View.GONE);
        binding.tvVoiceCallState.setText("Connecting...");

        WifeLogger.log(TAG, "Accepting Call. Relaying accept signaling packet to: " + peerIp);
        CallSignalingManager.getInstance(this).sendSignal(peerIp, Constants.SIGNAL_CALL_ACCEPT);

        // Launch server listener
        WifeLogger.log(TAG, "Opening voice server socket listener.");
        voiceCallManager.listenForIncomingCall();
        startCallServiceAndTimer();
    }

    private void declineOrEndCall() {
        stopRingtone();
        WifeLogger.log(TAG, "declineOrEndCall() invoked. Processing signal termination...");
        if (isInbound && binding.fabAcceptVoice.getVisibility() == View.VISIBLE) {
            WifeLogger.log(TAG, "Call declined before connection. Dispatching REJECT signal to: " + peerIp);
            CallSignalingManager.getInstance(this).sendSignal(peerIp, Constants.SIGNAL_CALL_REJECT);
        } else {
            WifeLogger.log(TAG, "Call ended during active stream. Dispatching END signal to: " + peerIp);
            CallSignalingManager.getInstance(this).sendSignal(peerIp, Constants.SIGNAL_CALL_END);
        }
        hangUp();
    }

    private void startCallServiceAndTimer() {
        WifeLogger.log(TAG, "Starting VoiceCallForegroundService foreground worker.");
        // Start Foreground Service for continuous calling background state
        Intent serviceIntent = new Intent(this, VoiceCallForegroundService.class);
        startService(serviceIntent);

        binding.tvVoiceCallState.setText("Volume Active");
        binding.tvVoiceDuration.setVisibility(View.VISIBLE);

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                callDurationSeconds++;
                binding.tvVoiceDuration.setText(Utils.formatDuration(callDurationSeconds));
                timerHandler.postDelayed(this, 1000);
            }
        };
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void hangUp() {
        WifeLogger.log(TAG, "hangUp() invoked. Stopping services and releasing active calling managers.");
        stopRingtone();
        stopCallServiceAndTimer();
        voiceCallManager.endCall();

        WifeLogger.log(TAG, "Calculated call duration: " + callDurationSeconds + " seconds. Inserting log record to Call Dao.");

        // Save Call Record in Db
        CallEntity entity = new CallEntity(peerName, "Voice", callDurationSeconds, System.currentTimeMillis());
        try {
            RoomDatabaseManager.getInstance(this).callDao().insert(entity);
            WifeLogger.log(TAG, "Call duration log record written successfully to SQLite DB.");
        } catch (Exception e) {
            WifeLogger.log(TAG, "Failed writing call log record to database: " + e.getMessage(), e);
        }

        WifeLogger.log(TAG, "Finalizing VoiceCallActivity.");
        finish();
    }

    private void stopCallServiceAndTimer() {
        WifeLogger.log(TAG, "stopCallServiceAndTimer() invoked. Unregistering SignalingEventListener and terminating foreground service.");
        CallSignalingManager.getInstance(this).unregisterListener(this);
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
        stopService(new Intent(this, VoiceCallForegroundService.class));
    }

    @Override
    public void onSignalReceived(String action, String senderIp, JsonObject payload) {
        WifeLogger.log(TAG, "onSignalReceived callback triggered. Action: " + action + " | Sender IP: " + senderIp);
        runOnUiThread(() -> {
            switch (action) {
                case Constants.SIGNAL_CALL_ACCEPT:
                    stopRingtone();
                    WifeLogger.log(TAG, "Signal matched: ACCEPT. Starting outbound audio stream parameters.");
                    binding.tvVoiceCallState.setText("Connected");
                    // Initiator launches active client stream to peer
                    voiceCallManager.startCall(peerIp);
                    startCallServiceAndTimer();
                    break;
                case Constants.SIGNAL_CALL_REJECT:
                    stopRingtone();
                    WifeLogger.log(TAG, "Signal matched: REJECT. Teardown active activity context.");
                    Toast.makeText(this, "Call rejected by peer.", Toast.LENGTH_SHORT).show();
                    hangUp();
                    break;
                case Constants.SIGNAL_CALL_END:
                    stopRingtone();
                    WifeLogger.log(TAG, "Signal matched: END. Teardown active activity context.");
                    Toast.makeText(this, "Call terminated.", Toast.LENGTH_SHORT).show();
                    hangUp();
                    break;
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WifeLogger.log(TAG, "onDestroy() invoked. Stopping active ringtones.");
        stopRingtone();
    }

    @Override
    public void onBackPressed() {
        WifeLogger.log(TAG, "User triggered native system back button press.");
        declineOrEndCall();
        super.onBackPressed();
    }
}