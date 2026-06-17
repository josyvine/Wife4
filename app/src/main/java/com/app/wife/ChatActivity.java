package com.wife.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wife.app.databinding.ActivityChatBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity implements ChatManager.MessageListener {

    private static final String TAG = "ChatActivity";

    private ActivityChatBinding binding;
    private ChatAdapter adapter;
    private final List<MessageEntity> messagesList = new ArrayList<>();
    private RoomDatabaseManager db;
    private String selfId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WifeLogger.log(TAG, "onCreate() invoked. Constructing Chat Session UI components.");

        db = RoomDatabaseManager.getInstance(this);
        selfId = Utils.getDeviceId(this);
        WifeLogger.log(TAG, "Local Hardware Signature ID resolved: " + selfId);

        setupToolbar();
        setupRecyclerView();
        setupInputBarListeners();
        setupEmojiPanel();

        loadHistory();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarChat);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbarChat.setNavigationOnClickListener(v -> {
            WifeLogger.log(TAG, "Navigation back button clicked. Exiting Chat Session.");
            onBackPressed();
        });
    }

    private void setupRecyclerView() {
        WifeLogger.log(TAG, "Initializing ChatAdapter and binding LayoutManager to RecyclerView.");
        adapter = new ChatAdapter(this, messagesList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Always align chats from the bottom up like modern chats
        binding.rvChatHistory.setLayoutManager(layoutManager);
        binding.rvChatHistory.setAdapter(adapter);
    }

    private void setupInputBarListeners() {
        WifeLogger.log(TAG, "Registering TextWatcher and click listeners for the WhatsApp-style input bar.");

        // Monitor typing states dynamically
        binding.etChatMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String text = s.toString().trim();
                if (text.isEmpty()) {
                    // Default state: Camera icon visible, Microphone icon active
                    binding.btnCameraIcon.setVisibility(View.VISIBLE);
                    binding.ivSendVoiceIcon.setImageResource(R.drawable.mic_24px);
                } else {
                    // Typing state: Camera icon hidden (attachment slides right), Send icon active
                    binding.btnCameraIcon.setVisibility(View.GONE);
                    binding.ivSendVoiceIcon.setImageResource(R.drawable.send_24px);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Hide emoji panel if the user taps the text input area to type
        binding.etChatMessage.setOnClickListener(v -> {
            if (binding.layoutEmojiPanel.getVisibility() == View.VISIBLE) {
                WifeLogger.log(TAG, "Hiding emoji panel to make room for system soft keyboard.");
                binding.layoutEmojiPanel.setVisibility(View.GONE);
            }
        });

        // Click handler for the morphing mic / send button
        binding.cardSendVoiceContainer.setOnClickListener(v -> {
            String text = binding.etChatMessage.getText().toString().trim();
            if (!TextUtils.isEmpty(text)) {
                WifeLogger.log(TAG, "User triggered SEND message action. Outgoing text length: " + text.length());
                MessageSender.getInstance(this).sendMessage(text);
                binding.etChatMessage.setText("");
            } else {
                WifeLogger.log(TAG, "User triggered offline voice recording action.");
                Toast.makeText(this, "Voice recording interface coming soon.", Toast.LENGTH_SHORT).show();
            }
        });

        // Standard attachment button click log
        binding.btnAttachFile.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User tapped attachment paperclip button. Launching transfer pipeline.");
            Toast.makeText(this, "Select 'Transfer Files' from the home menu to share files.", Toast.LENGTH_LONG).show();
        });

        // Standard camera button click log
        binding.btnCameraIcon.setOnClickListener(v -> {
            WifeLogger.log(TAG, "User tapped camera button. Launching video calling wizard.");
            Toast.makeText(this, "Select 'Video Session' from the home menu to start a camera stream.", Toast.LENGTH_LONG).show();
        });
    }

    private void setupEmojiPanel() {
        WifeLogger.log(TAG, "Initializing local offline emoji selection panel.");

        // Toggle emoji panel visibility when clicking the sticker button
        binding.btnEmojiSticker.setOnClickListener(v -> {
            if (binding.layoutEmojiPanel.getVisibility() == View.VISIBLE) {
                binding.layoutEmojiPanel.setVisibility(View.GONE);
                WifeLogger.log(TAG, "Dismissing bottom emoji panel.");
            } else {
                hideKeyboard();
                binding.layoutEmojiPanel.setVisibility(View.VISIBLE);
                WifeLogger.log(TAG, "Displaying bottom emoji panel.");
            }
        });

        // Asynchronously load the local raw JSON emoji database
        new Thread(() -> {
            try {
                Map<String, List<EmojiLoader.EmojiDTO>> emojiMap = EmojiLoader.loadEmojisFromAssets(this);
                if (emojiMap != null && !emojiMap.isEmpty()) {
                    runOnUiThread(() -> {
                        WifeLogger.log(TAG, "Local emoji database parsed successfully. Binding categories.");
                        setupEmojiViews(emojiMap);
                    });
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "Failed to load local offline emoji database: " + e.getMessage(), e);
            }
        }).start();
    }

    private void setupEmojiViews(Map<String, List<EmojiLoader.EmojiDTO>> emojiMap) {
        List<String> categories = new ArrayList<>(emojiMap.keySet());
        
        // Fixed: Instantiated the child subclass EmojiCategoryAdapter directly to prevent compile mismatch
        EmojiCategoryAdapter categoryAdapter = new EmojiCategoryAdapter(categories, category -> {
            List<EmojiLoader.EmojiDTO> emojis = emojiMap.get(category);
            if (emojis != null) {
                setupEmojiGrid(emojis);
            }
        });
        binding.rvEmojiCategories.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.rvEmojiCategories.setAdapter(categoryAdapter);

        // Load first category by default on boot
        if (!categories.isEmpty()) {
            List<EmojiLoader.EmojiDTO> defaultEmojis = emojiMap.get(categories.get(0));
            if (defaultEmojis != null) {
                setupEmojiGrid(defaultEmojis);
            }
        }
    }

    private void setupEmojiGrid(List<EmojiLoader.EmojiDTO> emojis) {
        // Fixed: Instantiated the child subclass EmojiGridAdapter directly to prevent compile mismatch
        EmojiGridAdapter gridAdapter = new EmojiGridAdapter(emojis, emojiChar -> {
            // Insert clicked emoji character directly inside our text field cursor position
            int start = Math.max(binding.etChatMessage.getSelectionStart(), 0);
            int end = Math.max(binding.etChatMessage.getSelectionEnd(), 0);
            binding.etChatMessage.getText().replace(Math.min(start, end), Math.max(start, end),
                    emojiChar, 0, emojiChar.length());
        });
        binding.rvEmojiGrid.setLayoutManager(new GridLayoutManager(this, 7)); // Clean 7 column grid
        binding.rvEmojiGrid.setAdapter(gridAdapter);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void loadHistory() {
        WifeLogger.log(TAG, "Accessing local SQLite database to load chat logs...");
        try {
            // Query database on separate or main thread allowed
            List<MessageEntity> history = db.messageDao().getAllMessages();
            messagesList.clear();
            
            WifeLogger.log("ChatActivity", "Successfully retrieved " + history.size() + " messages from Room database.");
            
            // Reverse because we queried DESC from database for chronological ordering in list
            for (int i = history.size() - 1; i >= 0; i--) {
                messagesList.add(history.get(i));
            }
            
            adapter.notifyDataSetChanged();
            scrollToBottom();
            WifeLogger.log("ChatActivity", "Dataset loaded and adapter notifications dispatched.");
        } catch (Exception e) {
            WifeLogger.log("ChatActivity", "Failed to query local database chat history: " + e.getMessage(), e);
        }
    }

    private void scrollToBottom() {
        if (!messagesList.isEmpty()) {
            WifeLogger.log("ChatActivity", "Scrolling list view focus to position index: " + (messagesList.size() - 1));
            binding.rvChatHistory.smoothScrollToPosition(messagesList.size() - 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        WifeLogger.log("ChatActivity", "onResume() invoked. Registering ChatActivity observer to ChatManager listener list.");
        ChatManager.getInstance(this).registerMessageListener(this);

        // Clear unread notification counts and save state to clear the unread badge
        try {
            SharedPreferences prefs = getSharedPreferences("WifeSettings", MODE_PRIVATE);
            prefs.edit().putInt("unread_count", 0).apply();
            WifeLogger.log("ChatActivity", "Unread chat message count reset to 0 inside local preferences.");
        } catch (Exception e) {
            WifeLogger.log("ChatActivity", "Error resetting unread message counts: " + e.getMessage(), e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WifeLogger.log("ChatActivity", "onPause() invoked. Unregistering ChatActivity observer from ChatManager listener list.");
        ChatManager.getInstance(this).unregisterMessageListener(this);
    }

    @Override
    public void onMessageReceived(MessageEntity message) {
        WifeLogger.log("ChatActivity", "onMessageReceived callback triggered on ChatActivity. From: " + message.getSender() + " | Text: " + message.getText());
        runOnUiThread(() -> {
            try {
                messagesList.add(message);
                adapter.notifyDataSetChanged();
                scrollToBottom();
                WifeLogger.log("ChatActivity", "Real-time list update redrawn. Current list size: " + messagesList.size());
            } catch (Exception e) {
                WifeLogger.log("ChatActivity", "Error rendering real-time message bubble update: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public void onMessageUnsent(long targetTimestamp) {
        WifeLogger.log(TAG, "onMessageUnsent callback triggered. Target timestamp: " + targetTimestamp);
        runOnUiThread(() -> {
            try {
                boolean removed = false;
                for (int i = 0; i < messagesList.size(); i++) {
                    if (messagesList.get(i).getTimestamp() == targetTimestamp) {
                        messagesList.remove(i);
                        removed = true;
                        WifeLogger.log(TAG, "Successfully removed unsent message from active list dataset in real-time.");
                        break;
                    }
                }
                if (removed) {
                    adapter.notifyDataSetChanged();
                    scrollToBottom();
                }
            } catch (Exception e) {
                WifeLogger.log(TAG, "Error executing real-time unsend UI refresh: " + e.getMessage(), e);
            }
        });
    }
}