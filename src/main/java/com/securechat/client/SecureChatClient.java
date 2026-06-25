package com.securechat.client;

import com.securechat.client.SecureChatClient.GroupMessageCallback;
import com.securechat.client.SecureChatClient.MessageCallback;
import com.securechat.shared.Packet;
import javafx.application.Platform;
import javafx.scene.control.Alert;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.net.URL;

public class SecureChatClient {
    private static final String SERVER_ADDRESS = "127.0.0.1";
    private static final int SERVER_PORT = 8080;
    private static final byte[] AES_KEY = "SecureChatKey123".getBytes(StandardCharsets.UTF_8);

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int myId = -1; 
    
    private final Map<Integer, String> usernameCache = new HashMap<>();

    // --- ІНТЕРФЕЙСИ КОЛБЕКІВ ---
    public interface MessageCallback {
        void onMessage(int chatPartnerId, int senderId, String text, boolean isRead);
    }
    
    public interface GroupMessageCallback {
        void onMessage(int groupId, int senderId, String senderName, String text);
    }
    
    // --- ЗМІННІ КОЛБЕКІВ ---
    private BiConsumer<Integer, String> onLoginSuccess;
    private MessageCallback onMessageReceived; 
    private Consumer<String> onContactsReceived; 
    public Consumer<Integer> onUserChecked;
    private Consumer<Integer> onMessagesRead; 
    private Consumer<String> onStatsReceived; 
    private Consumer<String> onErrorMessage; 
    private Consumer<String> onBlocklistReceived; 
    private Consumer<String> onGroupsReceived;
    private GroupMessageCallback onGroupMessageReceived;
    private Consumer<String> onGroupMembersReceived;
    private Consumer<String> onTypingStartReceived;
    private Consumer<String> onTypingStopReceived;
    public Consumer<String> onMessageEditedReceived;
    public Consumer<String> onMessageDeletedReceived;
    
    private final List<String> historyBuffer = new ArrayList<>();

    public void setCallbacks(BiConsumer<Integer, String> onLogin, MessageCallback onMsg, Consumer<String> onContacts,
                             Consumer<Integer> onUserChecked, Consumer<Integer> onMessagesRead, Consumer<String> onStats,
                             Consumer<String> onError, Consumer<String> onBlocklist,
                             Consumer<String> onGroups, GroupMessageCallback onGroupMsg, Consumer<String> onGroupMembers,
                             Consumer<String> onTypingStart, Consumer<String> onTypingStop,
                             Consumer<String> onMessageEdited, Consumer<String> onMessageDeleted) {
        this.onLoginSuccess = onLogin;
        this.onMessageReceived = onMsg;
        this.onContactsReceived = onContacts;
        this.onUserChecked = onUserChecked;
        this.onMessagesRead = onMessagesRead;
        this.onStatsReceived = onStats;
        this.onErrorMessage = onError;
        this.onBlocklistReceived = onBlocklist;
        this.onGroupsReceived = onGroups;
        this.onGroupMessageReceived = onGroupMsg;
        this.onGroupMembersReceived = onGroupMembers;
        this.onTypingStartReceived = onTypingStart;
        this.onTypingStopReceived = onTypingStop;
        this.onMessageEditedReceived = onMessageEdited;
        this.onMessageDeletedReceived = onMessageDeleted;
    }

    public String getUsername(int id) {
        return usernameCache.getOrDefault(id, "ID " + id);
    }

    public boolean connect() {
        try {
            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            Thread listenerThread = new Thread(this::listenForPackets);
            listenerThread.setDaemon(true);
            listenerThread.start();
            return true;
        } catch (IOException e) { return false; }
    }

    private void listenForPackets() {
        try {
            while (true) {
                int packetType = in.readInt();
                int payloadLength = in.readInt();
                if (payloadLength >= 0) {
                    byte[] payload = new byte[payloadLength];
                    if (payloadLength > 0) in.readFully(payload);
                    handleIncomingPacket(packetType, payload);
                }
            }
        } catch (IOException e) { System.out.println("З'єднання розірвано."); }
    }

    private void handleIncomingPacket(int packetType, byte[] payload) {
        String data = new String(payload, StandardCharsets.UTF_8);
        switch (packetType) {
            case Packet.PONG: break;
            case Packet.REGISTER_SUCCESS:
                showAlert(Alert.AlertType.INFORMATION, "Успіх", "Реєстрація успішна! Тепер ви можете увійти.");
                break;
            case Packet.REGISTER_ERROR:
            case Packet.LOGIN_ERROR:
                showAlert(Alert.AlertType.ERROR, "Помилка", data);
                break;
            case Packet.LOGIN_SUCCESS:
                String[] loginParts = data.split(":", 2);
                myId = Integer.parseInt(loginParts[0]);
                String role = loginParts.length > 1 ? loginParts[1] : "USER";
                if (onLoginSuccess != null) Platform.runLater(() -> onLoginSuccess.accept(myId, role));
                break;
            case Packet.CHANGE_LOGIN_SUCCESS:
                showAlert(Alert.AlertType.INFORMATION, "Успіх", "Логін змінено. Будь ласка, увійдіть заново.");
                break;
            case Packet.CHANGE_LOGIN_ERROR:
            case Packet.DELETE_ACCOUNT_ERROR:
                showAlert(Alert.AlertType.ERROR, "Помилка", data);
                break;
            case Packet.DELETE_ACCOUNT_SUCCESS:
                Platform.runLater(() -> { showAlert(Alert.AlertType.INFORMATION, "Прощавай", "Акаунт успішно видалено."); System.exit(0); });
                break;
            case Packet.DISCONNECT_KICK:
                Platform.runLater(() -> { showAlert(Alert.AlertType.ERROR, "БАН", "Ваш акаунт було заблоковано адміністратором."); System.exit(0); });
                break;
            case Packet.GET_STATISTICS_RESPONSE:
                if (onStatsReceived != null) Platform.runLater(() -> onStatsReceived.accept(data));
                break;
            case Packet.ERROR_MESSAGE:
                if (onErrorMessage != null) Platform.runLater(() -> onErrorMessage.accept(data));
                break;
            case Packet.GET_BLOCKLIST_RESPONSE:
                if (onBlocklistReceived != null) Platform.runLater(() -> onBlocklistReceived.accept(data));
                break;
            case Packet.NEW_MESSAGE:
                String[] nmParts = data.split(":", 3);
                if (nmParts.length >= 3) {
                    int senderId = Integer.parseInt(nmParts[0]);
                    usernameCache.put(senderId, nmParts[1]); 
                    String decrypted = decrypt(nmParts[2]);
                    if (senderId != myId) playNotificationSound();
                    if (onMessageReceived != null) Platform.runLater(() -> onMessageReceived.onMessage(senderId, senderId, decrypted, false));
                }
                break;
            case Packet.HISTORY_SYNC:
                String[] hsParts = data.split(":", 6); 
                if (hsParts.length >= 6) {
                    int senderId = Integer.parseInt(hsParts[0]);
                    usernameCache.put(senderId, hsParts[1]);
                    int receiverId = Integer.parseInt(hsParts[2]);
                    usernameCache.put(receiverId, hsParts[3]);
                    boolean isRead = hsParts[4].equals("1");
                    String decrypted = decrypt(hsParts[5]);
                    
                    int chatPartnerId = (senderId == myId) ? receiverId : senderId;
                    if (onMessageReceived != null) {
                        Platform.runLater(() -> onMessageReceived.onMessage(chatPartnerId, senderId, decrypted, isRead));
                    } else {
                        historyBuffer.add(chatPartnerId + ":" + senderId + ":" + (isRead ? "1" : "0") + ":" + decrypted);
                    }
                }
                break;
            case Packet.MESSAGE_READ_CONFIRM:
                if (onMessagesRead != null) Platform.runLater(() -> onMessagesRead.accept(Integer.parseInt(data)));
                break;
            case Packet.GET_CONTACTS_RESPONSE:
                if (!data.trim().isEmpty()) {
                    for (String c : data.split(",")) {
                        String[] p = c.split(":");
                        if (p.length >= 3) usernameCache.put(Integer.parseInt(p[0]), p[1]);
                    }
                }
                if (onContactsReceived != null) Platform.runLater(() -> onContactsReceived.accept(data));
                break;
            case Packet.USER_STATUS_UPDATE:
                sendPacket(Packet.GET_CONTACTS_REQUEST, ""); 
                break;
            case Packet.CONTACT_OPERATION_RESPONSE:
                showAlert(Alert.AlertType.INFORMATION, "Система", data);
                break;
            case Packet.CHECK_USER_RESPONSE:
                if (data.equals("ERROR")) {
                    showAlert(Alert.AlertType.ERROR, "Помилка", "Користувача з таким логіном не знайдено!");
                } else {
                    String[] p = data.split(":", 2);
                    if (p.length == 2) {
                        int targetId = Integer.parseInt(p[0]);
                        usernameCache.put(targetId, p[1]);
                        if (onUserChecked != null) Platform.runLater(() -> onUserChecked.accept(targetId));
                    }
                }
                break;
            // --- ГРУПИ ---
            case Packet.GET_USER_GROUPS_RESPONSE:
                if (onGroupsReceived != null) Platform.runLater(() -> onGroupsReceived.accept(data));
                break;
            case Packet.GET_GROUP_MEMBERS_RESPONSE: 
                if (onGroupMembersReceived != null) Platform.runLater(() -> onGroupMembersReceived.accept(data)); 
                break;
            case Packet.NEW_GROUP_MESSAGE:
                String[] gmParts = data.split(":", 4);
                if (gmParts.length == 4) {
                    int groupId = Integer.parseInt(gmParts[0]);
                    int senderId = Integer.parseInt(gmParts[1]);
                    String senderName = gmParts[2];
                    String decrypted = decrypt(gmParts[3]);
                    if (senderId != myId) playNotificationSound();
                    if (onGroupMessageReceived != null) Platform.runLater(() -> onGroupMessageReceived.onMessage(groupId, senderId, senderName, decrypted));
                }
                break;
            case Packet.TYPING_START: if (onTypingStartReceived != null) Platform.runLater(() -> onTypingStartReceived.accept(data)); break;
            case Packet.TYPING_STOP:  if (onTypingStopReceived != null) Platform.runLater(() -> onTypingStopReceived.accept(data)); break;
            case Packet.MESSAGE_EDITED:
                if (onMessageEditedReceived != null) Platform.runLater(() -> onMessageEditedReceived.accept(data));
                break;
            case Packet.MESSAGE_DELETED:
                if (onMessageDeletedReceived != null) Platform.runLater(() -> onMessageDeletedReceived.accept(data));
                break;
        }
    }

    public void flushHistory() {
        for (String record : historyBuffer) {
            String[] p = record.split(":", 4);
            if (p.length == 4 && onMessageReceived != null) {
                Platform.runLater(() -> onMessageReceived.onMessage(Integer.parseInt(p[0]), Integer.parseInt(p[1]), p[3], p[2].equals("1")));
            }
        }
        historyBuffer.clear();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    public void sendPacket(int packetType, String payloadString) {
        try {
            byte[] payload = payloadString.getBytes(StandardCharsets.UTF_8);
            out.writeInt(packetType);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        } catch (IOException e) { System.err.println("Помилка відправки: " + e.getMessage()); }
    }

    public String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    public String encrypt(String rawText) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(AES_KEY, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            return Base64.getEncoder().encodeToString(cipher.doFinal(rawText.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return null; }
    }

    public String decrypt(String encryptedText) {
        try {
            SecretKeySpec secretKey = new SecretKeySpec(AES_KEY, "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedText)), StandardCharsets.UTF_8);
        } catch (Exception e) { return "[Помилка розшифрування]"; }
    }

    private void playNotificationSound() {
        try {
            // Шукаємо файл у папці resources
            URL soundUrl = getClass().getResource("/notification.wav");
            if (soundUrl != null) {
                AudioInputStream audioIn = AudioSystem.getAudioInputStream(soundUrl);
                Clip clip = AudioSystem.getClip();
                clip.open(audioIn);
                clip.start(); // Програємо звук
            }
        } catch (Exception e) {
            // Якщо файлу немає або він битий — просто ігноруємо, програма не впаде
        }
    }
}