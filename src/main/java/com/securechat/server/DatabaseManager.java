package com.securechat.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    // Шлях до файлу бази даних. Файл securechat.db з'явиться в корені проєкту.
    private static final String URL = "jdbc:sqlite:securechat.db";

    // Метод для отримання з'єднання з БД
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    // Ініціалізація бази даних: створення таблиць
    public static void initializeDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Вмикаємо підтримку зовнішніх ключів у SQLite (це важливо для каскадного видалення)
            stmt.execute("PRAGMA foreign_keys = ON;");

            // 1. Таблиця користувачів
            String createUsersTable = "CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "username TEXT UNIQUE NOT NULL," +
                    "password_hash TEXT NOT NULL," +
                    "role TEXT NOT NULL DEFAULT 'USER'," +
                    "status TEXT NOT NULL DEFAULT 'OFFLINE');";
            stmt.execute(createUsersTable);

            // Таблиця списку контактів
            String createContactsTable = "CREATE TABLE IF NOT EXISTS contacts (" +
                    "user_id INTEGER NOT NULL," +
                    "contact_id INTEGER NOT NULL," +
                    "PRIMARY KEY (user_id, contact_id)," +
                    "FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(contact_id) REFERENCES users(id) ON DELETE CASCADE);";
            stmt.execute(createContactsTable);

            // НОВА ТАБЛИЦЯ: Особистий чорний список
            String createBlockTable = "CREATE TABLE IF NOT EXISTS personal_blocks (" +
                    "user_id INTEGER NOT NULL," +
                    "blocked_id INTEGER NOT NULL," +
                    "PRIMARY KEY (user_id, blocked_id)," +
                    "FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(blocked_id) REFERENCES users(id) ON DELETE CASCADE);";
            stmt.execute(createBlockTable);

            // 2. Таблиця приватних повідомлень
            String createMessagesTable = "CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "sender_id INTEGER NOT NULL," +
                    "receiver_id INTEGER NOT NULL," +
                    "encrypted_content TEXT NOT NULL," +
                    "is_read INTEGER DEFAULT 0," + 
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(sender_id) REFERENCES users(id)," +
                    "FOREIGN KEY(receiver_id) REFERENCES users(id));";
            stmt.execute(createMessagesTable);

            // 3. Таблиця для списку групових чатів
            String createGroupsTable = "CREATE TABLE IF NOT EXISTS chat_groups (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "group_name TEXT NOT NULL," +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                    ");";
            stmt.execute(createGroupsTable);

            // 4. Таблиця учасників груп (зв'язок багато-до-багатьох)
            String createGroupMembersTable = "CREATE TABLE IF NOT EXISTS group_members (" +
                    "group_id INTEGER NOT NULL," +
                    "user_id INTEGER NOT NULL," +
                    "PRIMARY KEY (group_id, user_id)," +
                    "FOREIGN KEY(group_id) REFERENCES chat_groups(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE" +
                    ");";
            stmt.execute(createGroupMembersTable);

            // 5. Таблиця повідомлень у групах
            String createGroupMessagesTable = "CREATE TABLE IF NOT EXISTS group_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "group_id INTEGER NOT NULL," +
                    "sender_id INTEGER," + // Може бути NULL
                    "encrypted_content TEXT NOT NULL," +
                    "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(group_id) REFERENCES chat_groups(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(sender_id) REFERENCES users(id) ON DELETE SET NULL" +
                    ");";
            stmt.execute(createGroupMessagesTable);

            System.out.println("Базу даних SQLite успішно ініціалізовано (включно з підготовкою до групових чатів).");
        } catch (SQLException e) {
            System.err.println("Помилка ініціалізації бази даних: " + e.getMessage());
        }
    }

    // Метод для видалення акаунта користувача
    public static boolean deleteUserAccount(int userId) {
        // Транзакція: видаляємо приватні повідомлення і самого користувача разом
        String deletePrivateMessages = "DELETE FROM messages WHERE sender_id = ? OR receiver_id = ?";
        String deleteUser = "DELETE FROM users WHERE id = ?";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false); // Починаємо транзакцію

            try (PreparedStatement msgStmt = conn.prepareStatement(deletePrivateMessages);
                 PreparedStatement userStmt = conn.prepareStatement(deleteUser)) {
                
                // 1. Видаляємо всю приватну історію
                msgStmt.setInt(1, userId);
                msgStmt.setInt(2, userId);
                msgStmt.executeUpdate();

                // 2. Видаляємо акаунт
                userStmt.setInt(1, userId);
                int rows = userStmt.executeUpdate();

                if (rows > 0) {
                    conn.commit(); // Застосовуємо зміни
                    return true;
                } else {
                    conn.rollback(); // Відкат, якщо користувача не знайдено
                    return false;
                }
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException e) {
            System.err.println("Помилка при видаленні акаунта: " + e.getMessage());
            return false;
        }
    }

    // Метод для реєстрації нового користувача
    public static boolean registerUser(String username, String passwordHash) {
        // SQL-запит для вставки. ID згенерується автоматично (AUTOINCREMENT)
        String sql = "INSERT INTO users (username, password_hash) VALUES (?, ?)";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            pstmt.setString(2, passwordHash);
            pstmt.executeUpdate();
            return true; // Реєстрація успішна
            
        } catch (SQLException e) {
            // Якщо такий username вже існує, база даних викине помилку (бо поле UNIQUE)
            System.err.println("Помилка реєстрації (можливо, дублікат логіну): " + e.getMessage());
            return false;
        }
    }

    // Метод для авторизації користувача
    public static Integer loginUser(String username, String passwordHash) {
        String sql = "SELECT id FROM users WHERE username = ? AND password_hash = ? AND status != 'BANNED'";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            pstmt.setString(2, passwordHash);
            
            var resultSet = pstmt.executeQuery();
            
            // Якщо є результат, значить логін і пароль вірні
            if (resultSet.next()) {
                return resultSet.getInt("id"); // Повертаємо унікальний ID
            }
            
        } catch (SQLException e) {
            System.err.println("Помилка під час логіну: " + e.getMessage());
        }
        
        return null; // Повертаємо null, якщо авторизація не вдалася
    }

    // Метод для зміни логіну з перевіркою пароля
    public static int changeUsername(int userId, String newUsername, String passwordHash) {
        // Оновлюємо логін ТІЛЬКИ якщо збігається ID та хеш пароля
        String sql = "UPDATE users SET username = ? WHERE id = ? AND password_hash = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, newUsername);
            pstmt.setInt(2, userId);
            pstmt.setString(3, passwordHash);
            
            int affectedRows = pstmt.executeUpdate();
            
            if (affectedRows > 0) {
                return 1; // Успішно оновлено
            } else {
                return 0; // Жодного рядка не оновлено (невірний пароль)
            }
            
        } catch (SQLException e) {
            // Якщо логін вже існує, SQLite видасть помилку "UNIQUE constraint failed"
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                return -1; // Логін вже зайнятий
            }
            System.err.println("Помилка при зміні логіну: " + e.getMessage());
            return -2; // Інша помилка БД
        }
    }

    // Метод для збереження зашифрованого повідомлення в історію
    public static boolean saveMessage(int senderId, int receiverId, String encryptedContent) {
        String sql = "INSERT INTO messages (sender_id, receiver_id, encrypted_content) VALUES (?, ?, ?)";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, senderId);
            pstmt.setInt(2, receiverId);
            pstmt.setString(3, encryptedContent);
            
            pstmt.executeUpdate();
            return true;
            
        } catch (SQLException e) {
            System.err.println("Помилка збереження повідомлення в БД: " + e.getMessage());
            return false;
        }
    }

    // Пошук користувачів за частиною логіна (повертає рядок "id:username,id:username")
    public static String searchUsers(String searchQuery) {
        StringBuilder result = new StringBuilder();
        String sql = "SELECT id, username FROM users WHERE username LIKE ? LIMIT 20"; // Обмежуємо до 20 результатів
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, "%" + searchQuery + "%");
            var rs = pstmt.executeQuery();
            
            while (rs.next()) {
                if (result.length() > 0) result.append(",");
                result.append(rs.getInt("id")).append(":").append(rs.getString("username"));
            }
        } catch (SQLException e) {
            System.err.println("Помилка пошуку користувачів: " + e.getMessage());
        }
        return result.toString();
    }

    // Додавання користувача до списку контактів
    public static boolean addContact(int userId, int contactId) {
        if (userId == contactId) return false; 
        
        String sql = "INSERT INTO contacts (user_id, contact_id) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, userId);
            pstmt.setInt(2, contactId);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false; // Помилка, якщо вже є в контактах або ID не існує
        }
    }

    // Отримання списку контактів (повертає "id:username:status,id:username:status")
    public static String getUserContacts(int userId) {
        StringBuilder result = new StringBuilder();
        String sql = "SELECT u.id, u.username, u.status FROM users u " +
                     "JOIN contacts c ON u.id = c.contact_id WHERE c.user_id = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, userId);
            var rs = pstmt.executeQuery();
            
            while (rs.next()) {
                if (result.length() > 0) result.append(",");
                result.append(rs.getInt("id")).append(":")
                      .append(rs.getString("username")).append(":")
                      .append(rs.getString("status"));
            }
        } catch (SQLException e) {
            System.err.println("Помилка завантаження контактів: " + e.getMessage());
        }
        return result.toString();
    }

    // Метод для оновлення статусу користувача
    public static void updateUserStatus(int userId, String status) {
        String sql = "UPDATE users SET status = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status);
            pstmt.setInt(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Помилка оновлення статусу: " + e.getMessage());
        }
    }

    // Отримання списку ID тих, хто має даного користувача у своїх контактах
    public static List<Integer> getFollowers(int userId) {
        List<Integer> followers = new ArrayList<>();
        // Шукаємо тих, хто додав нашого користувача (contact_id) до свого списку (user_id)
        String sql = "SELECT user_id FROM contacts WHERE contact_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, userId);
            var rs = pstmt.executeQuery();
            
            while (rs.next()) {
                followers.add(rs.getInt("user_id"));
            }
        } catch (SQLException e) {
            System.err.println("Помилка отримання підписників: " + e.getMessage());
        }
        return followers;
    }

    // Метод для отримання історії повідомлень користувача
    public static List<String> getUserHistory(int userId) {
        List<String> history = new ArrayList<>();
        String sql = "SELECT m.sender_id, s.username AS sender_name, " +
                     "m.receiver_id, r.username AS receiver_name, m.encrypted_content, m.is_read " +
                     "FROM messages m " +
                     "JOIN users s ON m.sender_id = s.id " +
                     "JOIN users r ON m.receiver_id = r.id " +
                     "WHERE m.sender_id = ? OR m.receiver_id = ? ORDER BY m.timestamp ASC";
        
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setInt(2, userId);
            var rs = pstmt.executeQuery();
            
            while (rs.next()) {
                int sender = rs.getInt("sender_id");
                String senderName = rs.getString("sender_name");
                int receiver = rs.getInt("receiver_id");
                String receiverName = rs.getString("receiver_name");
                String content = rs.getString("encrypted_content");
                int isRead = rs.getInt("is_read");
                
                // Формат: senderId:senderName:receiverId:receiverName:isRead:encryptedContent
                history.add(sender + ":" + senderName + ":" + receiver + ":" + receiverName + ":" + isRead + ":" + content);
            }
        } catch (SQLException e) { System.err.println("Помилка історії: " + e.getMessage()); }
        return history;
    }

    // Метод для отримання ролі користувача (USER або ADMIN)
    public static String getUserRole(int userId) {
        String sql = "SELECT role FROM users WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, userId);
            var rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("role");
            }
        } catch (SQLException e) {
            System.err.println("Помилка отримання ролі: " + e.getMessage());
        }
        return "USER"; // За замовчуванням повертаємо звичайного користувача
    }

    // Метод для отримання загальної кількості зареєстрованих користувачів
    public static int getTotalUsersCount() {
        String sql = "SELECT COUNT(*) AS total FROM users";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery(sql)) {
            
            if (rs.next()) {
                return rs.getInt("total");
            }
        } catch (SQLException e) {
            System.err.println("Помилка отримання статистики з БД: " + e.getMessage());
        }
        return 0;
    }

    // Отримати ID за логіном
    public static Integer getIdByUsername(String username) {
        // ЗМІНЕНО: Ігноруємо забанених
        String sql = "SELECT id FROM users WHERE username = ? AND status != 'BANNED'";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            var rs = pstmt.executeQuery();
            if (rs.next()) return rs.getInt("id");
        } catch (SQLException e) {}
        return null;
    }

    // Отримати логін за ID
    public static String getUsernameById(int id) {
        String sql = "SELECT username FROM users WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            var rs = pstmt.executeQuery();
            if (rs.next()) return rs.getString("username");
        } catch (SQLException e) {}
        return "Unknown";
    }

    // Метод для блокування користувача (Flow адміністратора)
    public static boolean banUser(int targetUserId) {
        String sql = "UPDATE users SET status = 'BANNED' WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, targetUserId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
            
        } catch (SQLException e) {
            System.err.println("Помилка блокування користувача: " + e.getMessage());
            return false;
        }
    }

    // Метод для позначення повідомлень як прочитаних
    public static void markMessagesAsRead(int senderId, int receiverId) {
        String sql = "UPDATE messages SET is_read = 1 WHERE sender_id = ? AND receiver_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, senderId);
            pstmt.setInt(2, receiverId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Помилка оновлення статусу прочитано: " + e.getMessage());
        }
    }

    // --- НОВІ МЕТОДИ ДЛЯ ЧОРНОГО СПИСКУ ТА КОНТАКТІВ ---

    public static boolean blockUserPersonal(int userId, int blockedId) {
        if (userId == blockedId) return false;
        String sql = "INSERT OR IGNORE INTO personal_blocks (user_id, blocked_id) VALUES (?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId); pstmt.setInt(2, blockedId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }

    public static boolean unblockUserPersonal(int userId, int blockedId) {
        String sql = "DELETE FROM personal_blocks WHERE user_id = ? AND blocked_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId); pstmt.setInt(2, blockedId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }

    public static boolean removeContact(int userId, int contactId) {
        String sql = "DELETE FROM contacts WHERE user_id = ? AND contact_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId); pstmt.setInt(2, contactId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }

    // Перевірка, чи заблоковано можливість писати один одному (в будь-який бік)
    public static boolean isUserBlocked(int userA, int userB) {
        String sql = "SELECT 1 FROM personal_blocks WHERE (user_id = ? AND blocked_id = ?) OR (user_id = ? AND blocked_id = ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userA); pstmt.setInt(2, userB);
            pstmt.setInt(3, userB); pstmt.setInt(4, userA);
            return pstmt.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    // Перевірка глобального бану адміном
    public static boolean isUserBanned(int userId) {
        String sql = "SELECT status FROM users WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            var rs = pstmt.executeQuery();
            if (rs.next()) return "BANNED".equals(rs.getString("status"));
        } catch (SQLException e) {}
        return false;
    }

    public static String getBlocklist(int userId) {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT blocked_id FROM personal_blocks WHERE user_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            var rs = pstmt.executeQuery();
            while(rs.next()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(rs.getInt("blocked_id"));
            }
        } catch (SQLException e) {}
        return sb.toString();
    }

    // Створити групу і одразу додати творця учасником
    public static int createGroup(String groupName, int ownerId) {
        String sqlGroup = "INSERT INTO chat_groups (group_name) VALUES (?)";
        String sqlMember = "INSERT INTO group_members (group_id, user_id) VALUES (?, ?)";
        
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmtGroup = conn.prepareStatement(sqlGroup, Statement.RETURN_GENERATED_KEYS)) {
                stmtGroup.setString(1, groupName);
                stmtGroup.executeUpdate();
                var keys = stmtGroup.getGeneratedKeys();
                if (keys.next()) {
                    int groupId = keys.getInt(1);
                    try (PreparedStatement stmtMember = conn.prepareStatement(sqlMember)) {
                        stmtMember.setInt(1, groupId);
                        stmtMember.setInt(2, ownerId);
                        stmtMember.executeUpdate();
                    }
                    conn.commit();
                    return groupId;
                }
            } catch (SQLException e) { conn.rollback(); throw e; }
        } catch (SQLException e) { System.err.println("Помилка створення групи: " + e.getMessage()); }
        return -1;
    }

    // Додати учасника в групу
    public static void addMemberToGroup(int groupId, int userId) {
        String sql = "INSERT OR IGNORE INTO group_members (group_id, user_id) VALUES (?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, groupId); pstmt.setInt(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {}
    }

    // Отримати список груп користувача
    public static String getUserGroups(int userId) {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT g.id, g.group_name FROM chat_groups g JOIN group_members gm ON g.id = gm.group_id WHERE gm.user_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            var rs = pstmt.executeQuery();
            while(rs.next()) {
                if (sb.length() > 0) sb.append(",");
                sb.append(rs.getInt("id")).append(":").append(rs.getString("group_name"));
            }
        } catch (SQLException e) {}
        return sb.toString();
    }

    // Збереження повідомлення групи
    public static boolean saveGroupMessage(int groupId, int senderId, String encryptedContent) {
        String sql = "INSERT INTO group_messages (group_id, sender_id, encrypted_content) VALUES (?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, groupId);
            pstmt.setInt(2, senderId);
            pstmt.setString(3, encryptedContent);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("Помилка збереження групового повідомлення: " + e.getMessage());
            return false;
        }
    }

    // Отримання списку ID учасників групи
    public static List<Integer> getGroupMembers(int groupId) {
        List<Integer> members = new ArrayList<>();
        String sql = "SELECT user_id FROM group_members WHERE group_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, groupId);
            var rs = pstmt.executeQuery();
            while (rs.next()) {
                members.add(rs.getInt("user_id"));
            }
        } catch (SQLException e) {
            System.err.println("Помилка отримання учасників групи: " + e.getMessage());
        }
        return members;
    }

    // Отримання історії повідомлень для всіх груп, у яких є користувач
    public static List<String> getGroupHistory(int userId) {
        List<String> history = new ArrayList<>();
        // Робимо JOIN, щоб дістати повідомлення ТІЛЬКИ з тих груп, де user_id є учасником
        // LEFT JOIN users потрібен на випадок, якщо відправник видалив акаунт (sender_id = NULL)
        String sql = "SELECT m.group_id, m.sender_id, u.username AS sender_name, m.encrypted_content " +
                     "FROM group_messages m " +
                     "JOIN group_members gm ON m.group_id = gm.group_id " +
                     "LEFT JOIN users u ON m.sender_id = u.id " +
                     "WHERE gm.user_id = ? ORDER BY m.timestamp ASC";
                     
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            var rs = pstmt.executeQuery();
            
            while (rs.next()) {
                int groupId = rs.getInt("group_id");
                int senderId = rs.getInt("sender_id"); // Буде 0, якщо sender_id = NULL
                String senderName = rs.getString("sender_name");
                if (senderName == null) senderName = "Видалений акаунт";
                String content = rs.getString("encrypted_content");
                
                // Формат пакета: groupId:senderId:senderUsername:encryptedText
                history.add(groupId + ":" + senderId + ":" + senderName + ":" + content);
            }
        } catch (SQLException e) {
            System.err.println("Помилка отримання історії груп: " + e.getMessage());
        }
        return history;
    }

    // Отримати гарно відформатований список імен учасників групи
    public static String getGroupMembersFormatted(int groupId) {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT u.username FROM users u JOIN group_members gm ON u.id = gm.user_id WHERE gm.group_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, groupId);
            var rs = pstmt.executeQuery();
            while(rs.next()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(rs.getString("username"));
            }
        } catch (SQLException e) {}
        return sb.toString();
    }
    
    // Видалити користувача з групи
    public static void removeMemberFromGroup(int groupId, int userId) {
        String sql = "DELETE FROM group_members WHERE group_id = ? AND user_id = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, groupId); pstmt.setInt(2, userId);
            pstmt.executeUpdate();
        } catch (SQLException e) {}
    }
}