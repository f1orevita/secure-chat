package com.securechat.client;

import com.securechat.client.MainApp.ContactItem;
import com.securechat.client.MainApp.GroupItem;
import com.securechat.shared.Packet;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class MainApp extends Application {
    private SecureChatClient client;
    private Stage primaryStage;
    private int myUserId;
    private String myRole = "USER"; 
    
    // Стан чату: приватний чи груповий
    private Integer currentChatId = null;
    private boolean isCurrentChatGroup = false;

    // --- ЗМІННІ ДЛЯ ІНДИКАТОРА ДРУКУВАННЯ ---
    private Label typingLabel;
    private Timeline typingTimer;
    private boolean isTyping = false;
    // Ключ: "isGroup:chatId", Значення: Множина імен користувачів
    private final Map<String, Set<String>> activeTypingUsers = new HashMap<>();
    // --- МОДЕЛІ ДАНИХ ---
    private static class Message {
        int senderId; String senderName; String text; boolean isRead;
        // Для приватних (ім'я підтягується з кешу)
        Message(int senderId, String text, boolean isRead) { this.senderId = senderId; this.text = text; this.isRead = isRead; }
        // Для груп (ім'я приходить одразу)
        Message(int senderId, String senderName, String text) { this.senderId = senderId; this.senderName = senderName; this.text = text; this.isRead = true; }
    }
    
    public static class ContactItem {
        public final int id; public final String username; public final String status;
        public ContactItem(int id, String username, String status) { this.id = id; this.username = username; this.status = status; }
        @Override public String toString() { return username + " [" + status + "]"; }
    }

    public static class GroupItem {
        public final int id; public final String name;
        public GroupItem(int id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return "👥 " + name; }
    }

    private final Map<Integer, List<Message>> privateHistories = new HashMap<>();
    private final Map<Integer, List<Message>> groupHistories = new HashMap<>();
    private final Map<Integer, ContactItem> officialContacts = new HashMap<>();
    private final List<Integer> myBlocklist = new ArrayList<>();

    // --- UI ЕЛЕМЕНТИ ---
    private ListView<ContactItem> contactsList;
    private ListView<GroupItem> groupsList;
    private VBox messagesContainer;
    private ScrollPane chatScrollPane;
    private StackPane centerStack;
    private VBox activeChatState;
    private VBox emptyChatState;
    private Label chatHeaderLabel;
    private Button addMemberBtn;
    private Button viewMembersBtn; // Кнопка учасників
    private Button leaveGroupBtn;  // Кнопка виходу з групи

    private final String BG_DARK = "#36393f";
    private final String BG_SIDEBAR = "#2f3136";
    private final String BG_PROFILE = "#292b2f"; 
    private final String BG_INPUT = "#40444b";
    private final String TEXT_NORMAL = "#dcddde";
    private final String BRAND_BLUE = "#5865F2";

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        // --- ВСТАНОВЛЕННЯ ІКОНКИ ДОДАТКУ ---
        try {
            java.net.URL iconUrl = getClass().getResource("/icon.png");
            if (iconUrl != null) {
                primaryStage.getIcons().add(new javafx.scene.image.Image(iconUrl.toExternalForm()));
            }
        } catch (Exception ignored) {}
        this.client = new SecureChatClient();
        
        if (!client.connect()) {
            new Alert(Alert.AlertType.ERROR, "Не вдалося підключитися до сервера!").showAndWait();
            System.exit(1);
        }

        setupClientCallbacks();
        showLoginWindow();
    }

    private void setupClientCallbacks() {
        client.setCallbacks(
            this::showChatWindow,
            this::handleIncomingPrivateMessage,
            this::updateContacts,
            targetId -> {
                openPrivateChat(targetId);
                contactsList.getSelectionModel().clearSelection();
                groupsList.getSelectionModel().clearSelection();
            },
            partnerId -> {
                List<Message> hist = privateHistories.get(partnerId);
                if (hist != null) for (Message m : hist) if (m.senderId == myUserId) m.isRead = true;
                if (!isCurrentChatGroup && currentChatId != null && currentChatId.equals(partnerId)) refreshChatView();
            },
            this::showAdminStatsDialog,
            errorData -> {
                String[] parts = errorData.split(":", 2);
                if (parts.length == 2) new Alert(Alert.AlertType.ERROR, parts[1]).showAndWait();
            },
            blocklistData -> {
                myBlocklist.clear();
                if (!blocklistData.isEmpty()) for (String idStr : blocklistData.split(",")) myBlocklist.add(Integer.parseInt(idStr));
            },
            groupsData -> {
                groupsList.getItems().clear();
                if (!groupsData.isEmpty()) {
                    for (String g : groupsData.split(",")) {
                        String[] p = g.split(":");
                        if (p.length >= 2) groupsList.getItems().add(new GroupItem(Integer.parseInt(p[0]), p[1]));
                    }
                }
            },
            this::handleIncomingGroupMessage,

            membersData -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Учасники групи");
                alert.setHeaderText(null);
                alert.setContentText("У групі знаходяться: \n" + membersData);
                alert.showAndWait();
            },

            typingStartData -> {
                String[] parts = typingStartData.split(":", 3);
                if (parts.length == 3) {
                    String key = parts[0] + ":" + parts[1];
                    activeTypingUsers.computeIfAbsent(key, k -> new HashSet<>()).add(parts[2]);
                    updateTypingLabel();
                }
            },
            typingStopData -> {
                String[] parts = typingStopData.split(":", 3);
                if (parts.length == 3) {
                    String key = parts[0] + ":" + parts[1];
                    Set<String> typists = activeTypingUsers.get(key);
                    if (typists != null) {
                        typists.remove(parts[2]);
                        updateTypingLabel();
                    }
                }
            },
            // 14-й параметр: Редагування повідомлення
            data -> {
                String[] parts = data.split(":", 4);
                boolean isGroup = Boolean.parseBoolean(parts[0]);
                int chatId = Integer.parseInt(parts[1]);
                String oldDecrypted = client.decrypt(parts[2]);
                String newDecrypted = client.decrypt(parts[3]);
                
                List<Message> history = isGroup ? groupHistories.get(chatId) : privateHistories.get(chatId);
                if (history != null) {
                    for (int i = history.size() - 1; i >= 0; i--) {
                        if (history.get(i).text.equals(oldDecrypted)) {
                            history.get(i).text = newDecrypted;
                            break;
                        }
                    }
                }
                if (currentChatId != null && currentChatId == chatId && isCurrentChatGroup == isGroup) refreshChatView();
            },
            // 15-й параметр: Видалення повідомлення
            data -> {
                String[] parts = data.split(":", 3);
                boolean isGroup = Boolean.parseBoolean(parts[0]);
                int chatId = Integer.parseInt(parts[1]);
                String oldDecrypted = client.decrypt(parts[2]);
                
                List<Message> history = isGroup ? groupHistories.get(chatId) : privateHistories.get(chatId);
                if (history != null) {
                    for (int i = history.size() - 1; i >= 0; i--) {
                        if (history.get(i).text.equals(oldDecrypted)) {
                            history.get(i).text = "[DELETED]"; // ЗМІНЕНО: Замість remove ставимо маркер
                            break;
                        }
                    }
                }
                if (currentChatId != null && currentChatId == chatId && isCurrentChatGroup == isGroup) refreshChatView();
            }
        );
    }

    private void showLoginWindow() {
        primaryStage.setTitle("SecureChat - Вхід");
        VBox root = new VBox(15);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        root.setStyle("-fx-background-color: " + BG_DARK + ";");

        Label titleLabel = new Label("SecureChat");
        titleLabel.setTextFill(Color.web(TEXT_NORMAL));
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 28));

        TextField loginField = new TextField();
        loginField.setPromptText("Логін");
        loginField.setMaxWidth(250);
        loginField.setStyle("-fx-background-color: " + BG_INPUT + "; -fx-text-fill: " + TEXT_NORMAL + ";");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Пароль");
        passwordField.setMaxWidth(250);
        passwordField.setStyle("-fx-background-color: " + BG_INPUT + "; -fx-text-fill: " + TEXT_NORMAL + ";");

        Button loginBtn = new Button("Увійти");
        loginBtn.setMaxWidth(250);
        loginBtn.setStyle("-fx-background-color: " + BRAND_BLUE + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        loginBtn.setOnAction(e -> {
            if (!loginField.getText().isEmpty() && !passwordField.getText().isEmpty())
                client.sendPacket(Packet.LOGIN_REQUEST, loginField.getText() + ":" + client.hashPassword(passwordField.getText()));
        });

        Button registerBtn = new Button("Реєстрація");
        registerBtn.setMaxWidth(250);
        registerBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + BRAND_BLUE + "; -fx-cursor: hand; -fx-border-color: " + BRAND_BLUE + "; -fx-border-radius: 3px;");
        registerBtn.setOnAction(e -> {
            if (!loginField.getText().isEmpty() && !passwordField.getText().isEmpty())
                client.sendPacket(Packet.REGISTER_REQUEST, loginField.getText() + ":" + client.hashPassword(passwordField.getText()));
        });

        root.getChildren().addAll(titleLabel, loginField, passwordField, loginBtn, registerBtn);
        Scene scene = new Scene(root, 350, 400);
        try { java.net.URL cssUrl = getClass().getResource("/style.css"); if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm()); } catch (Exception ignored) {}
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void showChatWindow(int myId, String role) {
        this.myUserId = myId;
        this.myRole = role; 
        primaryStage.setTitle("SecureChat");

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + BG_DARK + ";");

        // --- ЛІВА ПАНЕЛЬ ---
        VBox leftPanel = new VBox();
        leftPanel.setStyle("-fx-background-color: " + BG_SIDEBAR + ";");
        leftPanel.setPrefWidth(240);

        // 1. Приватні контакти
        VBox contactsSection = new VBox(5);
        contactsSection.setPadding(new Insets(10));
        VBox.setVgrow(contactsSection, Priority.ALWAYS);

        Label contactsLabel = new Label("ПРИВАТНІ ПОВІДОМЛЕННЯ");
        contactsLabel.setTextFill(Color.web("#8e9297"));
        contactsLabel.setFont(Font.font("Arial", FontWeight.BOLD, 10));

        contactsList = new ListView<>();
        contactsList.setStyle("-fx-background-color: " + BG_SIDEBAR + "; -fx-control-inner-background: " + BG_SIDEBAR + ";");
        VBox.setVgrow(contactsList, Priority.ALWAYS);
        contactsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                groupsList.getSelectionModel().clearSelection();
                openPrivateChat(newVal.id);
            }
        });

        Button searchUserBtn = new Button("🔍 Пошук / Профіль");
        searchUserBtn.setMaxWidth(Double.MAX_VALUE);
        searchUserBtn.setStyle("-fx-background-color: #3ba55c; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        searchUserBtn.setOnAction(e -> showUserSearchDialog());
        
        contactsSection.getChildren().addAll(contactsLabel, contactsList, searchUserBtn);

        // 2. Групові чати
        VBox groupsSection = new VBox(5);
        groupsSection.setPadding(new Insets(10));
        VBox.setVgrow(groupsSection, Priority.ALWAYS);

        Label groupsLabel = new Label("ГРУПОВІ ЧАТИ");
        groupsLabel.setTextFill(Color.web("#8e9297"));
        groupsLabel.setFont(Font.font("Arial", FontWeight.BOLD, 10));

        groupsList = new ListView<>();
        groupsList.setStyle("-fx-background-color: " + BG_SIDEBAR + "; -fx-control-inner-background: " + BG_SIDEBAR + ";");
        VBox.setVgrow(groupsList, Priority.ALWAYS);
        groupsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                contactsList.getSelectionModel().clearSelection();
                openGroupChat(newVal.id, newVal.name);
            }
        });

        Button createGroupBtn = new Button("➕ Створити групу");
        createGroupBtn.setMaxWidth(Double.MAX_VALUE);
        createGroupBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dcddde; -fx-border-color: #72767d; -fx-cursor: hand;");
        createGroupBtn.setOnAction(e -> askForInput("Введіть назву нової групи:", "", name -> client.sendPacket(Packet.CREATE_GROUP_REQUEST, name)));
        
        groupsSection.getChildren().addAll(groupsLabel, groupsList, createGroupBtn);

        // Панель профілю (Нижня)
        HBox profileBar = new HBox(10);
        profileBar.setPadding(new Insets(10));
        profileBar.setAlignment(Pos.CENTER_LEFT);
        profileBar.setStyle("-fx-background-color: " + BG_PROFILE + ";");

        VBox userInfo = new VBox();
        Label nameLabel = new Label("Мій ID: " + myUserId);
        nameLabel.setTextFill(Color.WHITE);
        nameLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        Label roleLabel = new Label(myRole);
        roleLabel.setTextFill(myRole.equals("ADMIN") ? Color.web("#ed4245") : Color.web("#8e9297"));
        roleLabel.setFont(Font.font("Arial", 12));
        userInfo.getChildren().addAll(nameLabel, roleLabel);
        HBox.setHgrow(userInfo, Priority.ALWAYS);

        Button settingsBtn = new Button("⚙");
        settingsBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #b9bbbe; -fx-font-size: 16px; -fx-cursor: hand;");
        settingsBtn.setOnAction(e -> showSettingsDialog());

        profileBar.getChildren().addAll(userInfo, settingsBtn);

        if (myRole.equals("ADMIN")) {
            Button adminBtn = new Button("🛡️");
            adminBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #ed4245; -fx-font-size: 16px; -fx-cursor: hand;");
            adminBtn.setOnAction(e -> client.sendPacket(Packet.GET_STATISTICS_REQUEST, ""));
            profileBar.getChildren().add(adminBtn);
        }

        leftPanel.getChildren().addAll(contactsSection, groupsSection, profileBar);
        root.setLeft(leftPanel);

        // --- ЦЕНТРАЛЬНА ПАНЕЛЬ ---
        centerStack = new StackPane();
        centerStack.setStyle("-fx-background-color: " + BG_DARK + ";");

        emptyChatState = new VBox();
        emptyChatState.setAlignment(Pos.CENTER);
        Label emptyLabel = new Label("Оберіть чат для початку спілкування");
        emptyLabel.setTextFill(Color.web("#72767d"));
        emptyLabel.setFont(Font.font("Arial", 16));
        emptyChatState.getChildren().add(emptyLabel);

        activeChatState = new VBox();
        activeChatState.setVisible(false);

        HBox chatHeader = new HBox(15);
        chatHeader.setAlignment(Pos.CENTER_LEFT);
        chatHeader.setPadding(new Insets(15));
        chatHeader.setStyle("-fx-background-color: " + BG_DARK + "; -fx-border-color: #202225; -fx-border-width: 0 0 1 0;");
        
        chatHeaderLabel = new Label("@ Чат");
        chatHeaderLabel.setTextFill(Color.web("white"));
        chatHeaderLabel.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        
        // Розпірка, щоб відштовхнути кнопку в правий край екрана
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        viewMembersBtn = new Button("👥 Учасники");
        viewMembersBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dcddde; -fx-border-color: #72767d; -fx-cursor: hand;");
        viewMembersBtn.setVisible(false);
        viewMembersBtn.setOnAction(e -> {
            if (currentChatId != null) client.sendPacket(Packet.GET_GROUP_MEMBERS_REQUEST, String.valueOf(currentChatId));
        });
        
        addMemberBtn = new Button("👤+ Додати учасника");
        addMemberBtn.setStyle("-fx-background-color: #3ba55c; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        addMemberBtn.setVisible(false); // Ховаємо за замовчуванням
        addMemberBtn.setOnAction(e -> askForInput("Введіть Username для додавання в групу:", "", username -> {
            if (currentChatId != null) client.sendPacket(Packet.ADD_MEMBER_TO_GROUP, currentChatId + ":" + username);
        }));

        leaveGroupBtn = new Button("🚪 Вийти");
        leaveGroupBtn.setStyle("-fx-background-color: #ed4245; -fx-text-fill: white; -fx-cursor: hand;");
        leaveGroupBtn.setVisible(false);
        leaveGroupBtn.setOnAction(e -> {
            if (currentChatId != null) {
                client.sendPacket(Packet.LEAVE_GROUP_REQUEST, String.valueOf(currentChatId));
                emptyChatState.setVisible(true);
                activeChatState.setVisible(false);
                currentChatId = null;
            }
        });

        chatHeader.getChildren().addAll(chatHeaderLabel, spacer, viewMembersBtn, addMemberBtn, leaveGroupBtn);

        messagesContainer = new VBox(15);
        messagesContainer.setPadding(new Insets(20));
        messagesContainer.setStyle("-fx-background-color: " + BG_DARK + ";");
        messagesContainer.setAlignment(Pos.BOTTOM_LEFT); 
        
        chatScrollPane = new ScrollPane(messagesContainer);
        chatScrollPane.setFitToWidth(true);
        chatScrollPane.setFitToHeight(true); 
        chatScrollPane.setStyle("-fx-background: " + BG_DARK + "; -fx-border-color: " + BG_DARK + ";");
        VBox.setVgrow(chatScrollPane, Priority.ALWAYS);

        messagesContainer.heightProperty().addListener((observable, oldValue, newValue) -> chatScrollPane.setVvalue(1.0));

        // --- ЛЕЙБЛ ДЛЯ ТАЙПІНГУ (ДОДАЄМО ЦЕЙ БЛОК) ---
        typingLabel = new Label();
        typingLabel.setTextFill(Color.web("#8e9297"));
        typingLabel.setFont(Font.font("Arial", FontPosture.ITALIC, 12));
        typingLabel.setPadding(new Insets(5, 20, 0, 20));
        typingLabel.setVisible(false);
        // ---------------------------------------------

        HBox inputPanel = new HBox(10);
        inputPanel.setPadding(new Insets(20));
        inputPanel.setStyle("-fx-background-color: " + BG_DARK + ";");

        TextField messageField = new TextField();
        messageField.setPromptText("Написати повідомлення...");
        messageField.setStyle("-fx-background-color: " + BG_INPUT + "; -fx-text-fill: " + TEXT_NORMAL + "; -fx-background-radius: 8px; -fx-padding: 10px;");
        HBox.setHgrow(messageField, Priority.ALWAYS);

        typingTimer = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
            isTyping = false;
            if (currentChatId != null) client.sendPacket(Packet.TYPING_STOP, isCurrentChatGroup + ":" + currentChatId);
        }));

        messageField.textProperty().addListener((obs, oldText, newText) -> {
            if (currentChatId == null) return;
            if (!newText.isEmpty()) {
                if (!isTyping) {
                    isTyping = true;
                    client.sendPacket(Packet.TYPING_START, isCurrentChatGroup + ":" + currentChatId);
                }
                typingTimer.playFromStart(); // Перезапускаємо таймер
            } else {
                isTyping = false;
                typingTimer.stop();
                client.sendPacket(Packet.TYPING_STOP, isCurrentChatGroup + ":" + currentChatId);
            }
        });

        Runnable sendMessageAction = () -> {
            String text = messageField.getText().trim();
            if (!text.isEmpty() && currentChatId != null) {
                if (isCurrentChatGroup) {
                    handleIncomingGroupMessage(currentChatId, myUserId, "Ви", text);
                    client.sendPacket(Packet.SEND_GROUP_MESSAGE, currentChatId + ":" + client.encrypt(text));
                } else {
                    handleIncomingPrivateMessage(currentChatId, myUserId, text, false);
                    client.sendPacket(Packet.SEND_MESSAGE, currentChatId + ":" + client.encrypt(text));
                }
                messageField.clear();
            }
        };
        messageField.setOnAction(e -> sendMessageAction.run());

        // --- КНОПКА ВІДПРАВКИ КАРТИНКИ ---
        Button attachBtn = new Button("📎");
        attachBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dcddde; -fx-font-size: 18px; -fx-cursor: hand;");
        attachBtn.setOnAction(e -> {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("Оберіть картинку");
            fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Зображення", "*.png", "*.jpg", "*.jpeg"));
            
            java.io.File selectedFile = fileChooser.showOpenDialog(primaryStage);
            if (selectedFile != null) {
                try {
                    byte[] fileContent = java.nio.file.Files.readAllBytes(selectedFile.toPath());
                    if (fileContent.length > 2 * 1024 * 1024) { // Ліміт 2 МБ, щоб не лягла пам'ять
                        // ВИПРАВЛЕНО: Прибрали зайвий параметр "Увага"
                        new Alert(Alert.AlertType.WARNING, "Файл занадто великий! Максимум 2 МБ.").showAndWait();
                        return;
                    }
                    String base64Image = java.util.Base64.getEncoder().encodeToString(fileContent);
                    String imagePayload = "[IMG]:" + base64Image;
                    
                    if (currentChatId != null) {
                        if (isCurrentChatGroup) {
                            handleIncomingGroupMessage(currentChatId, myUserId, "Ви", imagePayload);
                            client.sendPacket(Packet.SEND_GROUP_MESSAGE, currentChatId + ":" + client.encrypt(imagePayload));
                        } else {
                            handleIncomingPrivateMessage(currentChatId, myUserId, imagePayload, false);
                            client.sendPacket(Packet.SEND_MESSAGE, currentChatId + ":" + client.encrypt(imagePayload));
                        }
                    }
                } catch (Exception ex) {
                    // ВИПРАВЛЕНО: Прибрали зайвий параметр "Помилка"
                    new Alert(Alert.AlertType.ERROR, "Не вдалося завантажити картинку").showAndWait();
                }
            }
        });

        // Додаємо скріпку і поле вводу на панель
        inputPanel.getChildren().addAll(attachBtn, messageField);
        activeChatState.getChildren().addAll(chatHeader, chatScrollPane, typingLabel, inputPanel);
        
        centerStack.getChildren().addAll(emptyChatState, activeChatState);
        root.setCenter(centerStack);

        Scene chatScene = new Scene(root, 950, 650);
        try { java.net.URL cssUrl = getClass().getResource("/style.css"); if (cssUrl != null) chatScene.getStylesheets().add(cssUrl.toExternalForm()); } catch (Exception ignored) {}

        primaryStage.setScene(chatScene);

        // Запитуємо всі необхідні дані при вході
        client.sendPacket(Packet.GET_CONTACTS_REQUEST, "");
        client.sendPacket(Packet.GET_USER_GROUPS_REQUEST, "");
        client.flushHistory();
    }

    // --- ЛОГІКА ВІДКРИТТЯ ТА ВІДОБРАЖЕННЯ ЧАТІВ ---

    private void openPrivateChat(int partnerId) {
        isCurrentChatGroup = false;
        currentChatId = partnerId;
        chatHeaderLabel.setText("@ " + client.getUsername(partnerId));
        addMemberBtn.setVisible(false);
        viewMembersBtn.setVisible(false);
        leaveGroupBtn.setVisible(false);
        emptyChatState.setVisible(false);
        activeChatState.setVisible(true);
        
        if (!privateHistories.containsKey(partnerId)) {
            privateHistories.put(partnerId, new ArrayList<>());
            refreshPrivateSidebar();
        }
        client.sendPacket(Packet.MESSAGE_READ_CONFIRM, String.valueOf(partnerId));
        refreshChatView();
    }

    private void openGroupChat(int groupId, String groupName) {
        isCurrentChatGroup = true;
        currentChatId = groupId;
        chatHeaderLabel.setText("👥 " + groupName);
        addMemberBtn.setVisible(true);
        viewMembersBtn.setVisible(true);
        leaveGroupBtn.setVisible(true);
        
        emptyChatState.setVisible(false);
        activeChatState.setVisible(true);
        refreshChatView();
    }

    private void refreshChatView() {
        if (currentChatId == null) return;
        messagesContainer.getChildren().clear();
        
        List<Message> history = isCurrentChatGroup 
            ? groupHistories.getOrDefault(currentChatId, new ArrayList<>())
            : privateHistories.getOrDefault(currentChatId, new ArrayList<>());
            
        for (Message msg : history) renderMessageBubble(msg);
    }

    private void handleIncomingPrivateMessage(int chatPartnerId, int senderId, String text, boolean isRead) {
        Message newMsg = new Message(senderId, text, isRead);
        boolean isNewChat = !privateHistories.containsKey(chatPartnerId);
        privateHistories.computeIfAbsent(chatPartnerId, k -> new ArrayList<>()).add(newMsg);
        
        if (isNewChat) refreshPrivateSidebar();
        
        if (!isCurrentChatGroup && currentChatId != null && currentChatId == chatPartnerId) {
            renderMessageBubble(newMsg);
            if (senderId != myUserId) client.sendPacket(Packet.MESSAGE_READ_CONFIRM, String.valueOf(chatPartnerId));
        }
    }

    private void handleIncomingGroupMessage(int groupId, int senderId, String senderName, String text) {
        Message newMsg = new Message(senderId, senderName, text);
        groupHistories.computeIfAbsent(groupId, k -> new ArrayList<>()).add(newMsg);
        
        if (isCurrentChatGroup && currentChatId != null && currentChatId == groupId) {
            renderMessageBubble(newMsg);
        }
    }

    private void renderMessageBubble(Message msg) {
        VBox bubble = new VBox(5);
        String authorName = (msg.senderId == myUserId) ? "Ви" : (msg.senderName != null ? msg.senderName : client.getUsername(msg.senderId));
        
        Label authorLabel = new Label(authorName);
        authorLabel.setTextFill(msg.senderId == myUserId ? Color.web(BRAND_BLUE) : Color.web("#ed4245"));
        authorLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        
        HBox textAndStatus = new HBox(5);
        textAndStatus.setAlignment(Pos.BOTTOM_LEFT);

        boolean isEdited = false;
        boolean isDeleted = false;
        String displayText = msg.text;

        if (displayText != null && displayText.equals("[DELETED]")) {
            isDeleted = true;
            displayText = "🚫 Це повідомлення було видалено";
        } else if (displayText != null && displayText.startsWith("[EDITED]")) {
            isEdited = true;
            displayText = displayText.substring(8);
        }

        // ПЕРЕВІРКА НА КАРТИНКУ
        boolean isImage = displayText != null && !isDeleted && displayText.startsWith("[IMG]:");

        if (isImage) {
            try {
                String base64Data = displayText.substring(6);
                byte[] imageBytes = java.util.Base64.getDecoder().decode(base64Data);
                javafx.scene.image.Image img = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(imageBytes));
                javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView(img);
                imageView.setFitWidth(200); // Компактний розмір у чаті
                imageView.setPreserveRatio(true);
                textAndStatus.getChildren().add(imageView);
            } catch (Exception e) {
                Label errorLabel = new Label("[Пошкоджене зображення]");
                errorLabel.setTextFill(Color.web("#ed4245"));
                textAndStatus.getChildren().add(errorLabel);
            }
        } else {
            Label textLabel = new Label(displayText);
            textLabel.setTextFill(isDeleted ? Color.web("#72767d") : Color.web(TEXT_NORMAL));
            textLabel.setFont(Font.font("Arial", isDeleted ? FontPosture.ITALIC : FontPosture.REGULAR, 14));
            textLabel.setWrapText(true);
            textAndStatus.getChildren().add(textLabel);
        }

        if (isEdited && !isDeleted && !isImage) {
            Label editedLabel = new Label("(редаговано)");
            editedLabel.setTextFill(Color.web("#72767d"));
            editedLabel.setFont(Font.font("Arial", FontPosture.ITALIC, 10));
            editedLabel.setPadding(new Insets(0, 0, 2, 5));
            textAndStatus.getChildren().add(editedLabel);
        }

        if (msg.senderId == myUserId && !isCurrentChatGroup && !isDeleted) {
            Label statusLabel = new Label(msg.isRead ? "✔✔" : "✔");
            statusLabel.setTextFill(msg.isRead ? Color.web("#3ba55c") : Color.web("#72767d"));
            statusLabel.setFont(Font.font("Arial", FontWeight.BOLD, 12));
            statusLabel.setPadding(new Insets(0, 0, 0, 5));
            textAndStatus.getChildren().add(statusLabel);
        }
        
        bubble.getChildren().addAll(authorLabel, textAndStatus);

        if (msg.senderId == myUserId && !isDeleted) {
            bubble.setStyle("-fx-padding: 5px; -fx-background-color: rgba(255, 255, 255, 0.03); -fx-background-radius: 5px; -fx-cursor: hand;");
            bubble.setOnMouseEntered(e -> bubble.setStyle("-fx-padding: 5px; -fx-background-color: rgba(255, 255, 255, 0.08); -fx-background-radius: 5px; -fx-cursor: hand;"));
            bubble.setOnMouseExited(e -> bubble.setStyle("-fx-padding: 5px; -fx-background-color: rgba(255, 255, 255, 0.03); -fx-background-radius: 5px; -fx-cursor: hand;"));

            // --- СТИЛЬНЕ ТЕМНЕ МЕНЮ ---
            ContextMenu contextMenu = new ContextMenu();
            contextMenu.setStyle("-fx-background-color: #2f3136; -fx-border-color: #202225; -fx-border-radius: 4px; -fx-background-radius: 4px;");

            // Використовуємо кастомні лейбли, щоб гарантувати правильний колір тексту
            Label editLbl = new Label("✏️ Редагувати");
            editLbl.setTextFill(Color.web("#dcddde"));
            MenuItem editItem = new MenuItem("", editLbl);

            Label deleteLbl = new Label("🗑️ Видалити");
            deleteLbl.setTextFill(Color.web("#ed4245"));
            MenuItem deleteItem = new MenuItem("", deleteLbl);

            final String finalDisplayText = displayText;

            editItem.setOnAction(e -> {
                // ПЕРЕДАЄМО СТАРИЙ ТЕКСТ У ВІКНО ВВОДУ
                askForInput("Редагувати повідомлення:", finalDisplayText, newText -> {
                    if (newText.trim().isEmpty() || newText.equals(finalDisplayText)) return;
                    String newRaw = "[EDITED]" + newText;
                    String payload = isCurrentChatGroup + ":" + currentChatId + ":" + client.encrypt(msg.text) + ":" + client.encrypt(newRaw);
                    client.sendPacket(Packet.EDIT_MESSAGE_REQUEST, payload);
                    msg.text = newRaw; 
                    refreshChatView();
                });
            });

            deleteItem.setOnAction(e -> {
                String payload = isCurrentChatGroup + ":" + currentChatId + ":" + client.encrypt(msg.text) + ":" + client.encrypt("[DELETED]");
                client.sendPacket(Packet.DELETE_MESSAGE_REQUEST, payload);
                msg.text = "[DELETED]";
                refreshChatView();
            });

            if (!isImage) {
                contextMenu.getItems().add(editItem);
            }
            contextMenu.getItems().add(deleteItem);
            
            bubble.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                    contextMenu.show(bubble, e.getScreenX(), e.getScreenY());
                } else if (contextMenu.isShowing()) {
                    contextMenu.hide();
                }
            });
        }
        messagesContainer.getChildren().add(bubble);
    }

    private void updateTypingLabel() {
        if (currentChatId == null) {
            typingLabel.setVisible(false);
            return;
        }
        String key = isCurrentChatGroup + ":" + currentChatId;
        Set<String> typists = activeTypingUsers.getOrDefault(key, new HashSet<>());
        if (typists.isEmpty()) {
            typingLabel.setVisible(false);
        } else {
            typingLabel.setVisible(true);
            String names = String.join(", ", typists);
            typingLabel.setText(names + (typists.size() > 1 ? " друкують..." : " друкує..."));
        }
    }

    // --- ОНОВЛЕННЯ СПИСКІВ ---
    private void updateContacts(String data) {
        officialContacts.clear();
        if (data != null && !data.trim().isEmpty()) {
            for (String c : data.split(",")) {
                if (!c.trim().isEmpty()) {
                    String[] parts = c.split(":");
                    if (parts.length >= 3) officialContacts.put(Integer.parseInt(parts[0]), new ContactItem(Integer.parseInt(parts[0]), parts[1], parts[2]));
                }
            }
        }
        refreshPrivateSidebar();
    }

    private void refreshPrivateSidebar() {
        contactsList.getItems().clear();
        for (Integer partnerId : privateHistories.keySet()) {
            if (officialContacts.containsKey(partnerId)) contactsList.getItems().add(officialContacts.get(partnerId));
            else contactsList.getItems().add(new ContactItem(partnerId, client.getUsername(partnerId), "Не в контактах"));
        }
        for (ContactItem contact : officialContacts.values()) {
            if (!privateHistories.containsKey(contact.id)) contactsList.getItems().add(contact);
        }
    }

    // --- ДІАЛОГИ ---
    private void showSettingsDialog() {
        Stage dialog = new Stage();
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.setTitle("Налаштування профілю");
        VBox root = new VBox(20); root.setPadding(new Insets(20)); root.setStyle("-fx-background-color: " + BG_SIDEBAR + ";");

        VBox changeLoginBox = new VBox(10); changeLoginBox.setStyle("-fx-border-color: #4f545c; -fx-border-radius: 5px; -fx-padding: 10px;");
        Label l1 = new Label("Змінити Username"); l1.setTextFill(Color.WHITE); l1.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        TextField newLoginField = new TextField(); newLoginField.setPromptText("Новий Username");
        PasswordField pwdField1 = new PasswordField(); pwdField1.setPromptText("Поточний пароль");
        Button changeBtn = new Button("Зберегти"); changeBtn.setStyle("-fx-background-color: #3ba55c; -fx-text-fill: white;");
        changeBtn.setOnAction(e -> {
            if(!newLoginField.getText().isEmpty() && !pwdField1.getText().isEmpty()) {
                client.sendPacket(Packet.CHANGE_LOGIN_REQUEST, newLoginField.getText() + ":" + client.hashPassword(pwdField1.getText())); dialog.close();
            }
        });
        changeLoginBox.getChildren().addAll(l1, newLoginField, pwdField1, changeBtn);

        VBox deleteBox = new VBox(10); deleteBox.setStyle("-fx-border-color: #ed4245; -fx-border-radius: 5px; -fx-padding: 10px;");
        Label l2 = new Label("Небезпечна зона"); l2.setTextFill(Color.web("#ed4245")); l2.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        PasswordField pwdField2 = new PasswordField(); pwdField2.setPromptText("Введіть пароль для підтвердження");
        Button deleteBtn = new Button("Видалити акаунт назавжди"); deleteBtn.setStyle("-fx-background-color: #ed4245; -fx-text-fill: white;");
        deleteBtn.setOnAction(e -> {
            if(!pwdField2.getText().isEmpty()) { client.sendPacket(Packet.DELETE_ACCOUNT_REQUEST, client.hashPassword(pwdField2.getText())); dialog.close(); }
        });
        deleteBox.getChildren().addAll(l2, pwdField2, deleteBtn);

        root.getChildren().addAll(changeLoginBox, deleteBox);
        dialog.setScene(new Scene(root)); dialog.showAndWait();
    }

    private void showAdminStatsDialog(String statsData) {
        Stage dialog = new Stage(); dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL); dialog.setTitle("Панель Адміністратора");
        VBox root = new VBox(20); root.setPadding(new Insets(20)); root.setStyle("-fx-background-color: " + BG_SIDEBAR + ";");

        String[] stats = statsData.split(":");
        Label l1 = new Label("Статистика Сервера"); l1.setTextFill(Color.WHITE); l1.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        Label l2 = new Label("Онлайн підключень: " + stats[0]); l2.setTextFill(Color.web("#3ba55c"));
        Label l3 = new Label("Всього акаунтів: " + stats[1]); l3.setTextFill(Color.web("#72767d"));

        VBox banBox = new VBox(10); banBox.setStyle("-fx-border-color: #ed4245; -fx-border-radius: 5px; -fx-padding: 10px;");
        Label l4 = new Label("Заблокувати користувача"); l4.setTextFill(Color.web("#ed4245")); l4.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        TextField banIdField = new TextField(); banIdField.setPromptText("Введіть ID користувача");
        Button banBtn = new Button("БАН"); banBtn.setStyle("-fx-background-color: #ed4245; -fx-text-fill: white;");
        banBtn.setOnAction(e -> { if(!banIdField.getText().isEmpty()) { client.sendPacket(Packet.BLOCK_USER, banIdField.getText().trim()); banIdField.clear(); } });
        banBox.getChildren().addAll(l4, banIdField, banBtn);

        root.getChildren().addAll(l1, l2, l3, banBox);
        dialog.setScene(new Scene(root)); dialog.showAndWait();
    }

    private void showUserSearchDialog() {
        Stage dialog = new Stage();
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.setTitle("Пошук користувача");
        VBox root = new VBox(15); root.setPadding(new Insets(20)); root.setStyle("-fx-background-color: " + BG_SIDEBAR + ";"); root.setPrefWidth(300);

        HBox searchBox = new HBox(10);
        TextField searchField = new TextField(); searchField.setPromptText("Введіть логін..."); HBox.setHgrow(searchField, Priority.ALWAYS);
        Button searchBtn = new Button("Знайти"); searchBtn.setStyle("-fx-background-color: " + BRAND_BLUE + "; -fx-text-fill: white;");
        searchBox.getChildren().addAll(searchField, searchBtn);

        VBox profileBox = new VBox(10); profileBox.setVisible(false); profileBox.setAlignment(Pos.CENTER); profileBox.setStyle("-fx-background-color: #202225; -fx-padding: 15px; -fx-background-radius: 8px;");
        Label nameLabel = new Label(); nameLabel.setTextFill(Color.WHITE); nameLabel.setFont(Font.font("Arial", FontWeight.BOLD, 18));
        Button writeBtn = new Button("💬 Написати"); writeBtn.setMaxWidth(Double.MAX_VALUE);
        Button contactBtn = new Button(); contactBtn.setMaxWidth(Double.MAX_VALUE);
        Button blockBtn = new Button(); blockBtn.setMaxWidth(Double.MAX_VALUE);
        profileBox.getChildren().addAll(nameLabel, writeBtn, contactBtn, blockBtn);

        searchBtn.setOnAction(e -> {
            String query = searchField.getText().trim();
            if (query.isEmpty()) return;

            // Тимчасово підміняємо ТІЛЬКИ один потрібний колбек для пошуку
            client.onUserChecked = targetId -> {
                profileBox.setVisible(true);
                nameLabel.setText("@" + query);
                
                writeBtn.setOnAction(ev -> { openPrivateChat(targetId); dialog.close(); });

                boolean inContacts = officialContacts.containsKey(targetId);
                contactBtn.setText(inContacts ? "❌ Видалити з контактів" : "➕ Додати в контакти");
                contactBtn.setOnAction(ev -> {
                    if (inContacts) client.sendPacket(Packet.REMOVE_CONTACT_REQUEST, String.valueOf(targetId));
                    else client.sendPacket(Packet.ADD_CONTACT_REQUEST, query);
                    dialog.close();
                });

                boolean isBlocked = myBlocklist.contains(targetId);
                blockBtn.setText(isBlocked ? "🔓 Розблокувати" : "🚫 Заблокувати");
                blockBtn.setStyle(isBlocked ? "-fx-background-color: #3ba55c; -fx-text-fill: white;" : "-fx-background-color: #ed4245; -fx-text-fill: white;");
                blockBtn.setOnAction(ev -> {
                    if (isBlocked) client.sendPacket(Packet.UNBLOCK_USER_PERSONAL_REQUEST, String.valueOf(targetId));
                    else client.sendPacket(Packet.BLOCK_USER_PERSONAL_REQUEST, String.valueOf(targetId));
                    dialog.close();
                });
            };
            
            client.sendPacket(Packet.CHECK_USER_REQUEST, query);
            
            // Відновлюємо стандартний колбек через секунду
            new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ex) {}
                Platform.runLater(() -> {
                    client.onUserChecked = targetId -> { openPrivateChat(targetId); contactsList.getSelectionModel().clearSelection(); groupsList.getSelectionModel().clearSelection(); };
                });
            }).start();
        });

        root.getChildren().addAll(searchBox, profileBox);
        dialog.setScene(new Scene(root)); dialog.showAndWait();
    }

    private void askForInput(String title, String defaultValue, Consumer<String> action) {
        Stage dialog = new Stage();
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialog.setTitle(title);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: " + BG_SIDEBAR + ";");
        root.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(title);
        titleLabel.setTextFill(Color.WHITE);
        titleLabel.setFont(Font.font("Arial", FontWeight.BOLD, 14));

        TextField inputField = new TextField(defaultValue);
        inputField.setStyle("-fx-background-color: " + BG_INPUT + "; -fx-text-fill: " + TEXT_NORMAL + "; -fx-background-radius: 4px; -fx-padding: 8px;");
        inputField.setPrefWidth(300);

        // Обробка натискання Enter
        inputField.setOnAction(e -> {
            if (!inputField.getText().trim().isEmpty()) {
                action.accept(inputField.getText());
                dialog.close();
            }
        });

        HBox buttonsBox = new HBox(10);
        buttonsBox.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("Скасувати");
        cancelBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #dcddde; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> dialog.close());

        Button okBtn = new Button("ОК");
        okBtn.setStyle("-fx-background-color: " + BRAND_BLUE + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 4px;");
        okBtn.setOnAction(e -> {
            if (!inputField.getText().trim().isEmpty()) {
                action.accept(inputField.getText());
                dialog.close();
            }
        });

        buttonsBox.getChildren().addAll(cancelBtn, okBtn);
        root.getChildren().addAll(titleLabel, inputField, buttonsBox);

        dialog.setScene(new Scene(root));
        
        // Щоб фокус одразу був на полі вводу, і старий текст виділявся
        dialog.setOnShown(e -> {
            inputField.requestFocus();
            if (!defaultValue.isEmpty()) {
                inputField.selectAll();
            }
        });
        
        dialog.showAndWait();
    }

    public static void main(String[] args) { launch(args); }
}