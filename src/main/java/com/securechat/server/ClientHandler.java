package com.securechat.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import com.securechat.shared.Packet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final ConcurrentHashMap<Integer, ClientHandler> activeClients;
    private DataInputStream in;
    private DataOutputStream out;
    private Integer userId = null; // Унікальний ID після авторизації
    private String role = "USER"; // Зберігатиме роль під час сесії
    private long lastHeartbeat = System.currentTimeMillis(); // Час останнього сигналу від клієнта
    // --- ПАТЕРН COMMAND: Функціональний інтерфейс для обробки пакетів ---
    @FunctionalInterface
    private interface PacketAction {
        void handle(byte[] payload) throws IOException;
    }
    private final Map<Integer, PacketAction> packetHandlers = new HashMap<>();

    public ClientHandler(Socket socket, ConcurrentHashMap<Integer, ClientHandler> activeClients) {
        this.socket = socket;
        this.activeClients = activeClients;
        try {
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            System.err.println("Помилка ініціалізації: " + e.getMessage());
        }
        initHandlers();
    }

    // Метод, який "навчає" сервер розуміти пакети
    private void initHandlers() {
        packetHandlers.put(Packet.REGISTER_REQUEST, this::handleRegistration);
        packetHandlers.put(Packet.LOGIN_REQUEST, this::handleLogin);
        packetHandlers.put(Packet.SEND_MESSAGE, this::handleSendMessage);
        packetHandlers.put(Packet.CHANGE_LOGIN_REQUEST, this::handleChangeLogin);
        packetHandlers.put(Packet.BLOCK_USER, this::handleBlockUser);
        packetHandlers.put(Packet.DELETE_ACCOUNT_REQUEST, this::handleDeleteAccount);
        packetHandlers.put(Packet.SEARCH_USER_REQUEST, this::handleSearchUser);
        packetHandlers.put(Packet.ADD_CONTACT_REQUEST, this::handleAddContact);
        packetHandlers.put(Packet.GET_CONTACTS_REQUEST, this::handleGetContacts);
        packetHandlers.put(Packet.GET_STATISTICS_REQUEST, this::handleGetStatistics);
        packetHandlers.put(Packet.PING, this::handlePing);
        packetHandlers.put(Packet.CHECK_USER_REQUEST, this::handleCheckUser);
        packetHandlers.put(Packet.MESSAGE_READ_CONFIRM, this::handleReadConfirm);
        packetHandlers.put(Packet.BLOCK_USER_PERSONAL_REQUEST, this::handleBlockPersonal);
        packetHandlers.put(Packet.UNBLOCK_USER_PERSONAL_REQUEST, this::handleUnblockPersonal);
        packetHandlers.put(Packet.REMOVE_CONTACT_REQUEST, this::handleRemoveContact);
        packetHandlers.put(Packet.GET_BLOCKLIST_REQUEST, this::handleGetBlocklist);
        packetHandlers.put(Packet.CREATE_GROUP_REQUEST, this::handleCreateGroup);
        packetHandlers.put(Packet.SEND_GROUP_MESSAGE, this::handleSendGroupMessage);
        packetHandlers.put(Packet.GET_USER_GROUPS_REQUEST, this::handleGetGroups);
        packetHandlers.put(Packet.ADD_MEMBER_TO_GROUP, this::handleAddMemberToGroup);
        packetHandlers.put(Packet.LEAVE_GROUP_REQUEST, this::handleLeaveGroup);
        packetHandlers.put(Packet.GET_GROUP_MEMBERS_REQUEST, this::handleGetGroupMembers);
        packetHandlers.put(Packet.TYPING_START, this::handleTypingStart);
        packetHandlers.put(Packet.TYPING_STOP, this::handleTypingStop);
    }

    @Override
    public void run() {
        try {
            // Безперервне прослуховування вхідних пакетів
            while (true) {
                // 1. Читання заголовка: Тип пакета
                int packetType = in.readInt();
                
                // 2. Читання заголовка: Довжина тіла (payload)
                int payloadLength = in.readInt();

                // >= 0, тому що пакети GET_CONTACTS_REQUEST, PING та PONG мають нульову довжину
                if (payloadLength >= 0) { 
                    byte[] payload = new byte[payloadLength];
                    if (payloadLength > 0) {
                        in.readFully(payload); // Читаємо тіло, тільки якщо є що читати
                    }
                    // Обробка пакета залежно від його типу
                    handlePacket(packetType, payload);
                }
            }
        } catch (IOException e) {
            System.out.println("Клієнт відключився або сталася помилка мережі.");
        } finally {
            disconnect();
        }
    }

    private void handlePacket(int packetType, byte[] payload) {
        try {
            // Дістаємо потрібний метод обробки зі словника за кодом пакета
            PacketAction action = packetHandlers.get(packetType);
            
            if (action != null) {
                // Якщо такий пакет знайомий — виконуємо відповідний метод
                action.handle(payload);
            } else {
                System.out.println("Отримано невідомий тип пакета: " + packetType);
            }
        } catch (IOException e) {
            System.err.println("Помилка при обробці пакета " + packetType + ": " + e.getMessage());
        }
    }

    private void handleRegistration(byte[] payload) throws IOException {
        // 1. Декодуємо байти в рядок
        String data = new String(payload, StandardCharsets.UTF_8);
        
        // 2. Розбиваємо рядок на логін та пароль (очікуємо формат "username:passwordHash")
        String[] parts = data.split(":", 2);

        if (parts.length == 2) {
            String username = parts[0];
            String passwordHash = parts[1];

            // 3. Звертаємось до БД для створення запису
            boolean success = DatabaseManager.registerUser(username, passwordHash);

            if (success) {
                // 4. Якщо успішно — відправляємо REGISTER_SUCCESS
                sendPacket(Packet.REGISTER_SUCCESS, "Реєстрація успішна".getBytes(StandardCharsets.UTF_8));
                System.out.println("Зареєстровано нового користувача: " + username);
            } else {
                // 5. Якщо логін зайнятий — відправляємо REGISTER_ERROR
                sendPacket(Packet.REGISTER_ERROR, "Логін вже існує".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            // Некоректний формат даних від клієнта
            sendPacket(Packet.REGISTER_ERROR, "Неправильний формат даних".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleLogin(byte[] payload) throws IOException {
        String data = new String(payload, StandardCharsets.UTF_8);
        String[] parts = data.split(":", 2);

        if (parts.length == 2) {
            String username = parts[0];
            String passwordHash = parts[1];

            // Звертаємось до БД для перевірки
            Integer id = DatabaseManager.loginUser(username, passwordHash);

            if (id != null) {
                this.userId = id;
                this.role = DatabaseManager.getUserRole(this.userId); // Отримуємо роль з БД
                activeClients.put(this.userId, this);

                // --- Оновлення статусу ---
                DatabaseManager.updateUserStatus(this.userId, "ONLINE");
                broadcastStatusChange("ONLINE");
                // Відправляємо клієнту не тільки ID, але й його Роль (USER або ADMIN)
                String successData = this.userId + ":" + this.role;
                sendPacket(Packet.LOGIN_SUCCESS, successData.getBytes(StandardCharsets.UTF_8));
                System.out.println("Користувач успішно увійшов: " + username + " (ID: " + this.userId + ")");
                
                // --- Підвантаження історії ---
                List<String> history = DatabaseManager.getUserHistory(this.userId);
                for (String msgRecord : history) {
                    sendPacket(Packet.HISTORY_SYNC, msgRecord.getBytes(StandardCharsets.UTF_8));
                }
                System.out.println("Відправлено " + history.size() + " повідомлень історії для ID " + this.userId);
                // -----------------------------------------
                handleGetBlocklist(new byte[0]);
                // НОВЕ: Відправляємо історію всіх груп, у яких є користувач
                for (String groupMsgRecord : DatabaseManager.getGroupHistory(this.userId)) {
                    sendPacket(Packet.NEW_GROUP_MESSAGE, groupMsgRecord.getBytes(StandardCharsets.UTF_8));
                }
                
            } else {
                sendPacket(Packet.LOGIN_ERROR, "Невірний логін або пароль".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            sendPacket(Packet.LOGIN_ERROR, "Неправильний формат даних".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleChangeLogin(byte[] payload) throws IOException {
        // Перевіряємо, чи користувач авторизований
        if (this.userId == null) {
            sendPacket(Packet.CHANGE_LOGIN_ERROR, "Ви не авторизовані".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String data = new String(payload, StandardCharsets.UTF_8);
        String[] parts = data.split(":", 2);

        if (parts.length == 2) {
            String newUsername = parts[0];
            String passwordHash = parts[1];

            // Викликаємо метод БД
            int result = DatabaseManager.changeUsername(this.userId, newUsername, passwordHash);

            // Відправляємо відповідний пакет залежно від результату
            if (result == 1) {
                sendPacket(Packet.CHANGE_LOGIN_SUCCESS, "Логін успішно змінено".getBytes(StandardCharsets.UTF_8));
                System.out.println("Користувач (ID: " + this.userId + ") змінив логін на " + newUsername);
            } else if (result == 0) {
                sendPacket(Packet.CHANGE_LOGIN_ERROR, "Невірний пароль".getBytes(StandardCharsets.UTF_8));
            } else if (result == -1) {
                sendPacket(Packet.CHANGE_LOGIN_ERROR, "Цей логін вже зайнятий".getBytes(StandardCharsets.UTF_8));
            } else {
                sendPacket(Packet.CHANGE_LOGIN_ERROR, "Внутрішня помилка сервера".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            sendPacket(Packet.CHANGE_LOGIN_ERROR, "Неправильний формат даних".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleSearchUser(byte[] payload) throws IOException {
        if (this.userId == null) return;
        
        String searchQuery = new String(payload, StandardCharsets.UTF_8).trim();
        String result = DatabaseManager.searchUsers(searchQuery);
        
        sendPacket(Packet.SEARCH_USER_RESPONSE, result.getBytes(StandardCharsets.UTF_8));
    }

    private void handleCheckUser(byte[] payload) throws IOException {
        if (this.userId == null) return;
        String targetUsername = new String(payload, StandardCharsets.UTF_8).trim();
        Integer targetId = DatabaseManager.getIdByUsername(targetUsername);
        
        if (targetId != null) {
            sendPacket(Packet.CHECK_USER_RESPONSE, (targetId + ":" + targetUsername).getBytes(StandardCharsets.UTF_8));
        } else {
            sendPacket(Packet.CHECK_USER_RESPONSE, "ERROR".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleAddContact(byte[] payload) throws IOException {
        if (this.userId == null) return;
        String targetUsername = new String(payload, StandardCharsets.UTF_8).trim();
        Integer contactId = DatabaseManager.getIdByUsername(targetUsername);
        
        if (contactId == null) {
            sendPacket(Packet.CONTACT_OPERATION_RESPONSE, "Користувача з таким логіном не знайдено".getBytes(StandardCharsets.UTF_8));
            return;
        }
        
        boolean success = DatabaseManager.addContact(this.userId, contactId);
        if (success) {
            sendPacket(Packet.CONTACT_OPERATION_RESPONSE, "Користувача успішно додано".getBytes(StandardCharsets.UTF_8));
            handleGetContacts(new byte[0]);
        } else {
            sendPacket(Packet.CONTACT_OPERATION_RESPONSE, "Не вдалося додати (вже є в списку або це ви)".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleGetContacts(byte[] payload) throws IOException {
        if (this.userId == null) return;
        
        String contacts = DatabaseManager.getUserContacts(this.userId);
        System.out.println("Сервер відправляє список контактів: " + contacts); // Додайте це для дебагу
        sendPacket(Packet.GET_CONTACTS_RESPONSE, contacts.getBytes(StandardCharsets.UTF_8));
    }

    private void handleDeleteAccount(byte[] payload) throws IOException {
        // Перевіряємо, чи користувач авторизований
        if (this.userId == null) {
            sendPacket(Packet.DELETE_ACCOUNT_ERROR, "Ви не авторизовані".getBytes(StandardCharsets.UTF_8));
            return;
        }

        // Видаляємо з БД (це автоматично підчистить приватні повідомлення 
        // та залишить sender_id = NULL у групових)
        boolean isDeleted = DatabaseManager.deleteUserAccount(this.userId);

        if (isDeleted) {
            System.out.println("Користувач ID " + this.userId + " видалив свій акаунт.");
            sendPacket(Packet.DELETE_ACCOUNT_SUCCESS, "Акаунт та приватну історію успішно видалено".getBytes(StandardCharsets.UTF_8));
            
            // Відключаємо користувача
            disconnect(); 
        } else {
            sendPacket(Packet.DELETE_ACCOUNT_ERROR, "Помилка при видаленні акаунта".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void handleBlockUser(byte[] payload) throws IOException {
        // Перевірка прав: тільки ADMIN може блокувати
        if (!"ADMIN".equals(this.role)) {
            System.out.println("Спроба несанкціонованого доступу до команди блокування від ID: " + this.userId);
            return;
        }

        String data = new String(payload, StandardCharsets.UTF_8);
        try {
            int targetId = Integer.parseInt(data); // Отримуємо ID порушника

            // 1. Оновлюємо статус в БД на 'BANNED'
            boolean isBanned = DatabaseManager.banUser(targetId);

            if (isBanned) {
                System.out.println("Адміністратор (ID: " + this.userId + ") заблокував користувача (ID: " + targetId + ")");

                // 2. Шукаємо активний потік порушника
                ClientHandler targetHandler = activeClients.get(targetId);

                // 3. Якщо порушник онлайн, відправляємо йому повідомлення і розриваємо з'єднання
                if (targetHandler != null) {
                    targetHandler.sendPacket(Packet.DISCONNECT_KICK, 
                        "З'єднання розірвано. Ваш акаунт заблоковано".getBytes(StandardCharsets.UTF_8));
                    
                    // Примусово закриваємо сокет порушника (зупиняє його потік)
                    targetHandler.disconnect();
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Невірний формат ID для блокування: " + data);
        }
    }

    private void handleSendMessage(byte[] payload) throws IOException {
        // Перевіряємо, чи користувач взагалі авторизований
        if (this.userId == null) {
            System.out.println("Неавторизований клієнт намагається відправити повідомлення.");
            return;
        }

        String data = new String(payload, StandardCharsets.UTF_8);
        
        // Розбиваємо рядок на ID отримувача та зашифрований текст (формат "receiverId:encryptedText")
        String[] parts = data.split(":", 2);

        if (parts.length == 2) {
            try {
                int receiverId = Integer.parseInt(parts[0]);
                String encryptedText = parts[1];

                // 1. Перевірка на бан від адміна
                if (DatabaseManager.isUserBanned(receiverId)) {
                    sendPacket(Packet.ERROR_MESSAGE, (receiverId + ":Цей акаунт заблоковано адміністрацією.").getBytes(StandardCharsets.UTF_8));
                    return;
                }

                // 2. Перевірка на особистий чорний список
                if (DatabaseManager.isUserBlocked(this.userId, receiverId)) {
                    sendPacket(Packet.ERROR_MESSAGE, (receiverId + ":Неможливо надіслати. Користувач у чорному списку.").getBytes(StandardCharsets.UTF_8));
                    return;
                }

                // 1. Записуємо повідомлення в базу даних
                DatabaseManager.saveMessage(this.userId, receiverId, encryptedText);

                // 2. Шукаємо сокет отримувача в пулі активних з'єднань
                ClientHandler receiverHandler = activeClients.get(receiverId);

                // 3. Якщо користувач онлайн, пересилаємо йому пакет
                if (receiverHandler != null) {
                    // ЗМІНЕНО: Тепер ми додаємо ім'я відправника до пакета
                    String senderUsername = DatabaseManager.getUsernameById(this.userId);
                    String forwardData = this.userId + ":" + senderUsername + ":" + encryptedText;
                    
                    receiverHandler.sendPacket(Packet.NEW_MESSAGE, forwardData.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Повідомлення успішно переслано від ID " + this.userId + " до ID " + receiverId);
                
                } else {
                    // Користувач офлайн. Повідомлення вже в БД, він отримає його при наступному вході.
                    System.out.println("Користувач ID " + receiverId + " офлайн. Повідомлення збережено в БД.");
                }
            } catch (NumberFormatException e) {
                System.err.println("Невірний формат ID отримувача: " + parts[0]);
            }
        } else {
            System.err.println("Неправильний формат пакета SEND_MESSAGE.");
        }
    }

    // Метод для сповіщення контактів про зміну статусу
    private void broadcastStatusChange(String status) {
        if (this.userId == null) return;
        
        // Формат пакета: "id_користувача:статус" (наприклад, "12:ONLINE")
        String payloadData = this.userId + ":" + status;
        byte[] payload = payloadData.getBytes(StandardCharsets.UTF_8);
        
        // Отримуємо список тих, хто має цього юзера в контактах
        List<Integer> followers = DatabaseManager.getFollowers(this.userId);
        
        for (Integer followerId : followers) {
            // Перевіряємо, чи підписник зараз онлайн
            ClientHandler followerHandler = activeClients.get(followerId);
            if (followerHandler != null) {
                try {
                    followerHandler.sendPacket(Packet.USER_STATUS_UPDATE, payload);
                } catch (IOException e) {
                    System.err.println("Не вдалося відправити оновлення статусу користувачу " + followerId);
                }
            }
        }
    }

    private void handleReadConfirm(byte[] payload) throws IOException {
        if (this.userId == null) return;
        try {
            int partnerId = Integer.parseInt(new String(payload, StandardCharsets.UTF_8).trim());
            
            // Позначаємо в БД, що повідомлення від partnerId ДО НАС прочитані
            DatabaseManager.markMessagesAsRead(partnerId, this.userId);
            
            // Якщо співрозмовник онлайн, пересилаємо йому пакет, щоб у нього намалювалися сині галочки ✔✔
            ClientHandler partnerHandler = activeClients.get(partnerId);
            if (partnerHandler != null) {
                partnerHandler.sendPacket(Packet.MESSAGE_READ_CONFIRM, String.valueOf(this.userId).getBytes(StandardCharsets.UTF_8));
            }
        } catch (NumberFormatException e) {
            System.err.println("Невірний формат ID для підтвердження прочитання");
        }
    }

    private void handleGetStatistics(byte[] payload) throws IOException {
        // Перевірка прав: тільки ADMIN може бачити статистику
        if (!"ADMIN".equals(this.role)) {
            System.out.println("Спроба несанкціонованого доступу до статистики від ID: " + this.userId);
            return; 
        }

        // Збираємо дані
        int activeConnections = activeClients.size();
        int totalUsers = DatabaseManager.getTotalUsersCount();

        // Формуємо payload: "активні:всього"
        String statsData = activeConnections + ":" + totalUsers;
        
        // Відправляємо відповідь адміністратору
        sendPacket(Packet.GET_STATISTICS_RESPONSE, statsData.getBytes(StandardCharsets.UTF_8));
        System.out.println("Адміністратор (ID: " + this.userId + ") отримав статистику сервера.");
    }

    private void handlePing(byte[] payload) throws IOException {
        // Оновлюємо час останнього пульсу
        this.lastHeartbeat = System.currentTimeMillis();
        
        // Відразу відправляємо PONG назад (payload може бути порожнім)
        sendPacket(Packet.PONG, new byte[0]);
    }

    public synchronized void sendPacket(int packetType, byte[] payload) throws IOException {
        out.writeInt(packetType);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    public void disconnect() {
        try {
            if (userId != null) {
                // Видаляємо з пулу активних з'єднань
                activeClients.remove(userId);
                
                // --- Оновлення статусу при виході ---
                DatabaseManager.updateUserStatus(userId, "OFFLINE");
                broadcastStatusChange("OFFLINE");
                System.out.println("Користувач ID " + userId + " вийшов з мережі.");
                // ------------------------------------------------
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Помилка при закритті сокета: " + e.getMessage());
        }
    }

    private void handleBlockPersonal(byte[] payload) throws IOException {
        if (this.userId == null) return;
        try {
            int targetId = Integer.parseInt(new String(payload, StandardCharsets.UTF_8).trim());
            if (DatabaseManager.blockUserPersonal(this.userId, targetId)) {
                // Видаляємо з контактів при блокуванні
                DatabaseManager.removeContact(this.userId, targetId);
                sendPacket(Packet.CONTACT_OPERATION_RESPONSE, "Користувача додано до чорного списку".getBytes(StandardCharsets.UTF_8));
                handleGetContacts(new byte[0]);
                handleGetBlocklist(new byte[0]);
            }
        } catch (NumberFormatException e) {}
    }

    private void handleUnblockPersonal(byte[] payload) throws IOException {
        if (this.userId == null) return;
        try {
            int targetId = Integer.parseInt(new String(payload, StandardCharsets.UTF_8).trim());
            if (DatabaseManager.unblockUserPersonal(this.userId, targetId)) {
                sendPacket(Packet.CONTACT_OPERATION_RESPONSE, "Користувача розблоковано".getBytes(StandardCharsets.UTF_8));
                handleGetBlocklist(new byte[0]);
            }
        } catch (NumberFormatException e) {}
    }

    private void handleRemoveContact(byte[] payload) throws IOException {
        if (this.userId == null) return;
        try {
            int targetId = Integer.parseInt(new String(payload, StandardCharsets.UTF_8).trim());
            if (DatabaseManager.removeContact(this.userId, targetId)) {
                sendPacket(Packet.CONTACT_OPERATION_RESPONSE, "Видалено з контактів".getBytes(StandardCharsets.UTF_8));
                handleGetContacts(new byte[0]);
            }
        } catch (NumberFormatException e) {}
    }

    private void handleGetBlocklist(byte[] payload) throws IOException {
        if (this.userId == null) return;
        String blocklist = DatabaseManager.getBlocklist(this.userId);
        sendPacket(Packet.GET_BLOCKLIST_RESPONSE, blocklist.getBytes(StandardCharsets.UTF_8));
    }

    private void handleCreateGroup(byte[] payload) throws IOException {
        if (this.userId == null) return;
        String groupName = new String(payload, StandardCharsets.UTF_8).trim();
        int groupId = DatabaseManager.createGroup(groupName, this.userId);
        if (groupId != -1) {
            sendPacket(Packet.CONTACT_OPERATION_RESPONSE, ("Групу '" + groupName + "' створено").getBytes(StandardCharsets.UTF_8));
            handleGetGroups(new byte[0]);
        }
    }

    private void handleGetGroups(byte[] payload) throws IOException {
        if (this.userId == null) return;
        sendPacket(Packet.GET_USER_GROUPS_RESPONSE, DatabaseManager.getUserGroups(this.userId).getBytes(StandardCharsets.UTF_8));
    }

    private void handleSendGroupMessage(byte[] payload) throws IOException {
        if (this.userId == null) return;
        String[] parts = new String(payload, StandardCharsets.UTF_8).split(":", 2);
        
        if (parts.length == 2) {
            try {
                int groupId = Integer.parseInt(parts[0]);
                String encryptedText = parts[1];
                
                // 1. Зберігаємо повідомлення в БД
                DatabaseManager.saveGroupMessage(groupId, this.userId, encryptedText);
                
                // 2. Отримуємо всіх учасників цієї групи
                List<Integer> members = DatabaseManager.getGroupMembers(groupId);
                
                // 3. Формуємо пакет для розсилки (Формат: groupId:senderId:senderUsername:encryptedText)
                String senderUsername = DatabaseManager.getUsernameById(this.userId);
                String forwardData = groupId + ":" + this.userId + ":" + senderUsername + ":" + encryptedText;
                byte[] forwardPayload = forwardData.getBytes(StandardCharsets.UTF_8);
                
                // 4. Розсилаємо всім учасникам, які зараз онлайн
                for (Integer memberId : members) {
                    // Не відправляємо повідомлення самому собі
                    if (memberId.equals(this.userId)) continue;
                    
                    ClientHandler memberHandler = activeClients.get(memberId);
                    if (memberHandler != null) {
                        memberHandler.sendPacket(Packet.NEW_GROUP_MESSAGE, forwardPayload);
                    }
                }
            } catch (NumberFormatException e) {
                System.err.println("Невірний формат ID групи");
            }
        }
    }

    private void handleAddMemberToGroup(byte[] payload) throws IOException {
        if (this.userId == null) return;
        String data = new String(payload, StandardCharsets.UTF_8).trim();
        String[] parts = data.split(":", 2);
        
        if (parts.length == 2) {
            try {
                int groupId = Integer.parseInt(parts[0]);
                String targetUsername = parts[1].trim();
                
                Integer targetId = DatabaseManager.getIdByUsername(targetUsername);
                
                if (targetId != null) {
                    DatabaseManager.addMemberToGroup(groupId, targetId);
                    sendPacket(Packet.CONTACT_OPERATION_RESPONSE, ("Користувача " + targetUsername + " додано до групи!").getBytes(StandardCharsets.UTF_8));
                    
                    // Якщо користувач онлайн, миттєво оновлюємо йому список груп
                    ClientHandler targetHandler = activeClients.get(targetId);
                    if (targetHandler != null) {
                        try { targetHandler.handleGetGroups(new byte[0]); } catch (IOException ex) {}
                    }
                } else {
                    sendPacket(Packet.CONTACT_OPERATION_RESPONSE, "Користувача не знайдено або він заблокований".getBytes(StandardCharsets.UTF_8));
                }
            } catch (NumberFormatException e) {
                System.err.println("Помилка формату ID групи");
            }
        }
    }

    private void handleLeaveGroup(byte[] payload) throws IOException {
        if (this.userId == null) return;
        try {
            int groupId = Integer.parseInt(new String(payload, StandardCharsets.UTF_8).trim());
            DatabaseManager.removeMemberFromGroup(groupId, this.userId);
            sendPacket(Packet.CONTACT_OPERATION_RESPONSE, "Ви покинули групу".getBytes(StandardCharsets.UTF_8));
            handleGetGroups(new byte[0]); // Оновлюємо список груп зліва
        } catch (NumberFormatException e) {}
    }

    private void handleGetGroupMembers(byte[] payload) throws IOException {
        if (this.userId == null) return;
        try {
            int groupId = Integer.parseInt(new String(payload, StandardCharsets.UTF_8).trim());
            String members = DatabaseManager.getGroupMembersFormatted(groupId);
            sendPacket(Packet.GET_GROUP_MEMBERS_RESPONSE, members.getBytes(StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {}
    }

    private void handleTypingEvent(byte[] payload, int packetType) throws IOException {
        if (this.userId == null) return;
        String[] parts = new String(payload, StandardCharsets.UTF_8).split(":", 2);
        
        if (parts.length == 2) {
            boolean isGroup = Boolean.parseBoolean(parts[0]);
            int targetId = Integer.parseInt(parts[1]); // ID партнера або групи
            String senderName = DatabaseManager.getUsernameById(this.userId);
            
            if (isGroup) {
                // Пересилаємо всім учасникам групи
                String forwardData = "true:" + targetId + ":" + senderName;
                byte[] forwardPayload = forwardData.getBytes(StandardCharsets.UTF_8);
                for (Integer memberId : DatabaseManager.getGroupMembers(targetId)) {
                    if (!memberId.equals(this.userId)) { // Самому собі не шлемо
                        ClientHandler h = activeClients.get(memberId);
                        if (h != null) h.sendPacket(packetType, forwardPayload);
                    }
                }
            } else {
                // Пересилаємо приватному співрозмовнику
                String forwardData = "false:" + this.userId + ":" + senderName;
                ClientHandler h = activeClients.get(targetId);
                if (h != null) h.sendPacket(packetType, forwardData.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void handleTypingStart(byte[] payload) throws IOException { handleTypingEvent(payload, Packet.TYPING_START); }
    private void handleTypingStop(byte[] payload) throws IOException { handleTypingEvent(payload, Packet.TYPING_STOP); }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }
}