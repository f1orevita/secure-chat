package com.securechat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SecureChatServer {
    private static final int PORT = 8080;
    // Пул потоків для обробки клієнтів (наприклад, до 100 одночасних підключень)
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(100);
    
    // Зберігання активних з'єднань: K - ID користувача, V - обробник клієнта
    // Це дозволить перевіряти, чи користувач онлайн, та пересилати йому пакети
    private static final ConcurrentHashMap<Integer, ClientHandler> activeClients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        DatabaseManager.initializeDatabase();

        // --- Потік-санітар (Heartbeat Monitor) ---
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30000); // Перевіряємо кожні 30 секунд
                    long currentTime = System.currentTimeMillis();
                    
                    // Проходимось по всіх активних клієнтах
                    for (ClientHandler client : activeClients.values()) {
                        // Якщо клієнт мовчить більше 60 секунд (60000 мс)
                        if (currentTime - client.getLastHeartbeat() > 60000) {
                            System.out.println("Клієнт відключений по тайм-ауту (немає PING).");
                            client.disconnect(); // Викликаємо розрив з'єднання
                        }
                    }
                } catch (InterruptedException e) {
                    System.err.println("Помилка в потоці-санітарі: " + e.getMessage());
                }
            }
        });
        monitorThread.setDaemon(true); // Робимо потік фоновим
        monitorThread.start();
        // -----------------------------------------------------

        System.out.println("Сервер SecureChat запускається...");
        
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Сервер слухає порт: " + PORT);

            while (true) {
                // Очікування нового підключення
                Socket clientSocket = serverSocket.accept();
                System.out.println("Нове підключення: " + clientSocket.getInetAddress());

                // Створення обробника для клієнта та передача його в пул потоків
                ClientHandler clientHandler = new ClientHandler(clientSocket, activeClients);
                threadPool.execute(clientHandler);
            }
        } catch (IOException e) {
            System.err.println("Помилка запуску сервера: " + e.getMessage());
        }
    }
}