package com.info.impaxo;

import android.media.ToneGenerator;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class TargetServerThread extends Thread {
    private static final String TAG = "ImpaxoLog";
    private static final int PORT = 8888;
    private ServerSocket serverSocket;
    private boolean isRunning = true;

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(PORT);
            Log.d(TAG, "Sunucu başlatıldı, Port " + PORT + " dinleniyor...");

            while (isRunning && !Thread.currentThread().isInterrupted()) {
                Log.d(TAG, "Silah cihazın bağlanması bekleniyor...");
                Socket socket = serverSocket.accept();
                Log.d(TAG, "Silah bağlandı! IP: " + socket.getInetAddress().getHostAddress());

                // Bağlanan istemciden gelen verileri sürekli dinle
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String message;

                while ((message = reader.readLine()) != null) {
                    Log.d(TAG, "Gelen Veri: " + message);

                    if (message.equals("FIRE")) {
                        Log.d(TAG, "Ateş emri alındı, ses çalınıyor!");
                        playSound();
                    }
                }

                Log.d(TAG, "Silah bağlantısı kesildi.");
                socket.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Sunucu Hatası: " + e.getMessage());
        } finally {
            stopServer();
        }
    }

    public void stopServer() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                Log.d(TAG, "Sunucu kapatıldı ve port serbest bırakıldı.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Sunucu kapatılırken hata: " + e.getMessage());
        }
    }

    private void playSound() {
        // Ses çalma kodlarınız
        try {
            android.media.ToneGenerator toneGen = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100);
            toneGen.startTone(ToneGenerator.TONE_CDMA_CONFIRM, 150);
        } catch (Exception e) {
            Log.e(TAG, "Ses çalma hatası: " + e.getMessage());
        }
    }
}