package com.info.impaxo;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketManager {

    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService sendExecutor;
    
    // Çoklu İstemci Yönetimi
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    
    // Silah Modu için (Tekil bağlantı)
    private Socket gunSocket;
    private PrintWriter gunWriter;

    public interface OnDataReceivedListener {
        void onClientConnected(String clientId);
        void onClientDisconnected(String clientId);
        void onAimUpdate(String clientId, float yaw, float pitch);
        void onShootReceived(String clientId);
        void onLaserToggle(String clientId, boolean isOn);
        void onCalibrationStart(String clientId);
        void onError(String errorMessage);
    }

    public interface OnConnectListener {
        void onSuccess();
        void onFailure(String error);
        void onDisconnected();
        void onMessageReceived(String message);
    }

    // ---------------- HEDEF MODU (SUNUCU - ÇOKLU BAĞLANTI) ----------------
    public void startServer(int port, OnDataReceivedListener listener) {
        stop();
        isRunning = true;
        
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(port));

                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    // Her yeni bağlantı için bir Handler oluştur
                    new Thread(new ClientHandler(socket, listener)).start();
                }
            } catch (Exception e) {
                if (isRunning && listener != null) {
                    mainHandler.post(() -> listener.onError("Sunucu Hatası: " + e.getMessage()));
                }
            }
        }).start();
    }

    private class ClientHandler implements Runnable {
        private Socket socket;
        private OnDataReceivedListener listener;
        private String clientId = "Bilinmeyen_Cihaz";
        private long lastAimUiPostTime = 0;
        private static final long UI_THROTTLE_MS = 30;

        private PrintWriter writer;

        public ClientHandler(Socket socket, OnDataReceivedListener listener) {
            this.socket = socket;
            this.listener = listener;
            try {
                this.writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
            } catch (IOException e) { e.printStackTrace(); }
        }

        public void sendMessage(String message) {
            sendExecutor.execute(() -> {
                if (writer != null) {
                    writer.println(message);
                }
            });
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                String line;
                while (isRunning && (line = reader.readLine()) != null) {
                    if (line.startsWith("ID:")) {
                        // Cihaz kendini tanıtıyor (Google ismi veya Model adı)
                        this.clientId = line.substring(3);
                        clients.put(clientId, this);
                        if (listener != null) mainHandler.post(() -> listener.onClientConnected(clientId));
                    } else if (line.startsWith("AIM:")) {
                        parseAim(line, listener);
                    } else if (line.equals("SHOOT")) {
                        if (listener != null) mainHandler.post(() -> listener.onShootReceived(clientId));
                    } else if (line.equals("CALIB:START")) {
                        if (listener != null) mainHandler.post(() -> listener.onCalibrationStart(clientId));
                    } else if (line.startsWith("LASER:")) {
                        boolean isOn = line.substring(6).equals("ON");
                        if (listener != null) mainHandler.post(() -> listener.onLaserToggle(clientId, isOn));
                    }
                }
            } catch (Exception e) {
                // Hata durumunda kopma
            } finally {
                if (clientId != null) {
                    clients.remove(clientId);
                    if (listener != null) mainHandler.post(() -> listener.onClientDisconnected(clientId));
                }
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        private void parseAim(String data, OnDataReceivedListener listener) {
            long now = System.currentTimeMillis();
            if (now - lastAimUiPostTime >= UI_THROTTLE_MS) {
                lastAimUiPostTime = now;
                String[] parts = data.substring(4).split(",");
                if (parts.length == 2) {
                    try {
                        float yaw = Float.parseFloat(parts[0]);
                        float pitch = Float.parseFloat(parts[1]);
                        if (listener != null) mainHandler.post(() -> listener.onAimUpdate(clientId, yaw, pitch));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
    }

    // ---------------- SİLAH MODU (MÜŞTERİ) ----------------
    public void connectToServer(String ip, int port, String myIdentity, OnConnectListener listener) {
        stop();
        isRunning = true;
        sendExecutor = Executors.newSingleThreadExecutor();

        new Thread(() -> {
            try {
                String cleanIp = ip.replace("/", "").trim();
                gunSocket = new Socket();
                gunSocket.connect(new InetSocketAddress(cleanIp, port), 4000);
                gunSocket.setTcpNoDelay(true);
                
                synchronized (this) {
                    gunWriter = new PrintWriter(gunSocket.getOutputStream(), true);
                    // İLK ADIM: Kendini tanıt
                    gunWriter.println("ID:" + myIdentity);
                }

                if (listener != null) mainHandler.post(listener::onSuccess);

                BufferedReader reader = new BufferedReader(new InputStreamReader(gunSocket.getInputStream()));
                String serverLine;
                while (isRunning && (serverLine = reader.readLine()) != null) {
                    String finalLine = serverLine;
                    if (listener != null) mainHandler.post(() -> listener.onMessageReceived(finalLine));
                }
            } catch (Exception e) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onFailure("Bağlantı Başarısız: " + e.getMessage()));
                }
            } finally {
                notifyDisconnect(listener);
            }
        }).start();
    }

    public void sendAimData(float yaw, float pitch, OnConnectListener disconnectListener) {
        if (gunWriter != null && sendExecutor != null && !sendExecutor.isShutdown()) {
            sendExecutor.execute(() -> {
                try {
                    gunWriter.println("AIM:" + yaw + "," + pitch);
                    if (gunWriter.checkError()) notifyDisconnect(disconnectListener);
                } catch (Exception e) {
                    notifyDisconnect(disconnectListener);
                }
            });
        }
    }

    public void sendShootSignal(OnConnectListener disconnectListener) {
        if (gunWriter != null && sendExecutor != null && !sendExecutor.isShutdown()) {
            sendExecutor.execute(() -> {
                try {
                    gunWriter.println("SHOOT");
                    if (gunWriter.checkError()) notifyDisconnect(disconnectListener);
                } catch (Exception e) {
                    notifyDisconnect(disconnectListener);
                }
            });
        }
    }

    public void sendLaserSignal(boolean isOn, OnConnectListener disconnectListener) {
        sendCommand("LASER:" + (isOn ? "ON" : "OFF"), disconnectListener);
    }

    public void sendCalibrationStart(OnConnectListener disconnectListener) {
        sendCommand("CALIB:START", disconnectListener);
    }

    public void sendCommand(String command, OnConnectListener disconnectListener) {
        if (gunWriter != null && sendExecutor != null && !sendExecutor.isShutdown()) {
            sendExecutor.execute(() -> {
                try {
                    gunWriter.println(command);
                    if (gunWriter.checkError()) notifyDisconnect(disconnectListener);
                } catch (Exception e) {
                    notifyDisconnect(disconnectListener);
                }
            });
        }
    }

    private void notifyDisconnect(OnConnectListener listener) {
        stop();
        if (listener != null) mainHandler.post(listener::onDisconnected);
    }

    public synchronized void stop() {
        isRunning = false;
        if (sendExecutor != null) sendExecutor.shutdownNow();
        clients.clear();
        try {
            if (gunWriter != null) gunWriter.close();
            if (gunSocket != null) gunSocket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    public void broadcastMessage(String message) {
        for (ClientHandler client : clients.values()) {
            client.sendMessage(message);
        }
    }

    public void sendMessageToClient(String clientId, String message) {
        ClientHandler handler = clients.get(clientId);
        if (handler != null) {
            handler.sendMessage(message);
        }
    }
}
