package com.info.impaxo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.format.Formatter;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.UiModeManager;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.SoundPool;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private TextView txtStatus, txtSensitivity, txtAmmo;
    private Button btnModeTarget, btnModeGun, btnConnectManual, btnCalibrateSmall;
    private Button btnModeClassic, btnModeMole, btnModePolygon, btnModeBird;
    private GunView gunView;
    private ImageView imgTargetStatus;
    private PreviewView cameraPreview;
    private EditText edtIpAddress;
    private LinearLayout layoutGunControls, layoutMain, layoutModeSelection;
    private View layoutGunFullView, layoutTargetModes;
    private TargetView targetView;
    private SeekBar seekSensitivity;

    private SensorManager sensorManager;
    private Sensor rotationVectorSensor;
    private Vibrator vibrator;
    private SoundPool soundPool;
    private int soundShot, soundReload, soundEmpty;

    private SocketManager socketManager;
    private TargetNsdManager targetNsdManager;
    private GunNsdManager gunNsdManager;

    private float[] rotationMatrix = new float[9];
    private float[] orientationValues = new float[3];

    private float baseYaw = 0f;
    private float basePitch = 0f;
    private boolean isGunMode = false;
    private boolean isLaserOn = false;
    private float currentSensitivity = 15f;
    private int currentAmmo = 10;
    private final int MAX_AMMO = 10;
    private boolean initialVibrationReached = false;

    private boolean isCalibrating = false;
    private int calibStep = 0;
    private float[][] calibOrientations = new float[3][2];

    private SocketManager.OnConnectListener gunConnectListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        layoutMain = findViewById(R.id.layoutMain);
        txtStatus = findViewById(R.id.txtStatus);
        btnModeTarget = findViewById(R.id.btnModeTarget);
        btnModeGun = findViewById(R.id.btnModeGun);
        btnConnectManual = findViewById(R.id.btnConnectManual);
        edtIpAddress = findViewById(R.id.edtIpAddress);
        layoutGunControls = findViewById(R.id.layoutGunControls);
        layoutModeSelection = findViewById(R.id.layoutModeSelection);
        targetView = findViewById(R.id.targetView);
        layoutTargetModes = findViewById(R.id.layoutTargetModes);

        btnModeClassic = findViewById(R.id.btnModeClassic);
        btnModeMole = findViewById(R.id.btnModeMole);
        btnModePolygon = findViewById(R.id.btnModePolygon);
        btnModeBird = findViewById(R.id.btnModeBird);

        layoutGunFullView = findViewById(R.id.layoutGunFullView);
        gunView = findViewById(R.id.gunView);
        cameraPreview = findViewById(R.id.cameraPreview);
        btnCalibrateSmall = findViewById(R.id.btnCalibrateSmall);
        seekSensitivity = findViewById(R.id.seekSensitivity);
        txtSensitivity = findViewById(R.id.txtSensitivity);
        txtAmmo = findViewById(R.id.txtAmmo);
        imgTargetStatus = findViewById(R.id.imgTargetStatus);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        initGunSounds();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        socketManager = new SocketManager();

        gunConnectListener = new SocketManager.OnConnectListener() {
            @Override
            public void onSuccess() {
                if (gunNsdManager != null) gunNsdManager.stopDiscovery();
                runOnUiThread(() -> {
                    imgTargetStatus.setAlpha(1.0f);
                    imgTargetStatus.setColorFilter(android.graphics.Color.GREEN);
                    Toast.makeText(MainActivity.this, "Hedefe Bağlandı!", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onFailure(String error) { runOnUiThread(() -> txtStatus.setText("❌ " + error)); }
            @Override public void onDisconnected() {
                runOnUiThread(() -> {
                    imgTargetStatus.setAlpha(0.3f);
                    imgTargetStatus.setColorFilter(android.graphics.Color.RED);
                    txtStatus.setText("🔴 BAĞLANTI YOK");
                });
            }
            @Override public void onMessageReceived(String message) {
                if (message.equals("GAMEOVER")) {
                    runOnUiThread(() -> {
                        if (vibrator != null) {
                            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                        Toast.makeText(MainActivity.this, "OYUN BİTTİ!", Toast.LENGTH_LONG).show();
                    });
                } else if (message.startsWith("CALIB:HIT:")) {
                    int hitStep = Integer.parseInt(message.substring(10));
                    handleCalibrationHit(hitStep);
                }
            }
        };

        gunView.setListener(new GunView.OnGunEventListener() {
            @Override
            public void onTriggerPulled(float pullRatio) {
                if (pullRatio > 0.05f && !initialVibrationReached) {
                    initialVibrationReached = true;
                    if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(10, 30));
                } else if (pullRatio <= 0.05f) { initialVibrationReached = false; }
            }
            @Override
            public void onFire() {
                if (currentAmmo > 0) performFire();
                else {
                    soundPool.play(soundEmpty, 0.5f, 0.5f, 1, 0, 1f);
                    Toast.makeText(MainActivity.this, "MERMİ BİTTİ! SÜRGÜYÜ ÇEK!", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onSlidePulled() {
                if (isGunMode) { reloadGun(); Toast.makeText(MainActivity.this, "Şarjör Yenilendi", Toast.LENGTH_SHORT).show(); }
            }
            @Override
            public void onLaserToggled(boolean isOn) {
                if (isGunMode) {
                    isLaserOn = isOn;
                    socketManager.sendLaserSignal(isLaserOn, gunConnectListener);
                }
            }
        });

        // HEDEF MODU BUTONLARI
        btnModeClassic.setOnClickListener(v -> { targetView.setGameMode(TargetView.GameMode.CLASSIC); targetView.startGame(); });
        btnModeMole.setOnClickListener(v -> { targetView.setGameMode(TargetView.GameMode.MOLE); targetView.startGame(); });
        btnModePolygon.setOnClickListener(v -> { targetView.setGameMode(TargetView.GameMode.POLYGON); targetView.startGame(); });
        btnModeBird.setOnClickListener(v -> { targetView.setGameMode(TargetView.GameMode.BIRD); targetView.startGame(); });

        targetView.setGameEventListener(new TargetView.OnGameEventListener() {
            @Override public void onGameStarted() { socketManager.broadcastMessage("START"); }
            @Override public void onGameFinished(String winner, java.util.Map<String, Integer> scores) {
                socketManager.broadcastMessage("GAMEOVER");
            }
        });

        btnModeTarget.setOnClickListener(v -> {
            isGunMode = false; unregisterSensors();
            if (gunNsdManager != null) gunNsdManager.stopDiscovery();
            layoutMain.setVisibility(View.VISIBLE);
            targetView.setVisibility(View.VISIBLE);
            layoutTargetModes.setVisibility(View.VISIBLE); // MOD MENÜSÜNÜ GÖSTER
            layoutGunControls.setVisibility(View.GONE);
            layoutGunFullView.setVisibility(View.GONE);
            String localIp = getDeviceIpAddress();
            txtStatus.setText("🎯 HEDEF MODU AKTİF\nIP: " + localIp);
            if (targetNsdManager == null) targetNsdManager = new TargetNsdManager();
            targetNsdManager.registerService(MainActivity.this, 8888);
            socketManager.startServer(8888, new SocketManager.OnDataReceivedListener() {
                @Override public void onClientConnected(String clientId) { runOnUiThread(() -> Toast.makeText(MainActivity.this, clientId + " Bağlandı!", Toast.LENGTH_SHORT).show()); }
                @Override public void onClientDisconnected(String clientId) { runOnUiThread(() -> targetView.removePlayer(clientId)); }
                @Override public void onAimUpdate(String clientId, float yaw, float pitch) { runOnUiThread(() -> targetView.updateAim(clientId, yaw, pitch)); }
                @Override public void onShootReceived(String clientId) { 
                    runOnUiThread(() -> {
                        int calibHit = targetView.checkCalibrationHit(clientId);
                        if (calibHit > 0) {
                            socketManager.sendMessageToClient(clientId, "CALIB:HIT:" + calibHit);
                        } else {
                            targetView.addBulletHole(clientId);
                        }
                    });
                }
                @Override public void onLaserToggle(String clientId, boolean isOn) { runOnUiThread(() -> targetView.setLaserState(clientId, isOn)); }
                @Override public void onCalibrationStart(String clientId) { runOnUiThread(() -> targetView.startPlayerCalibration(clientId)); }
                @Override public void onError(String errorMessage) { runOnUiThread(() -> txtStatus.setText("❌ " + errorMessage)); }
            });
        });

        btnModeGun.setOnClickListener(v -> {
            boolean hasCamera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
            if (hasCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 100);
            }
            isGunMode = true; registerSensors();
            if (targetNsdManager != null) targetNsdManager.stopService();
            
            // BAĞLANTIYI BEKLEMEDEN DİREKT EKRANA GEÇ
            layoutMain.setVisibility(View.GONE);
            layoutGunFullView.setVisibility(View.VISIBLE);
            hideSystemUI();
            startCamera();
            updateAmmoUI();
            imgTargetStatus.setAlpha(0.3f); // Başlangıçta bağlı değil
            imgTargetStatus.setColorFilter(android.graphics.Color.RED);

            layoutGunControls.setVisibility(View.VISIBLE);
            layoutTargetModes.setVisibility(View.GONE);
            txtStatus.setText("🔫 SİLAH MODU AKTİF");
            if (gunNsdManager == null) gunNsdManager = new GunNsdManager();
            gunNsdManager.startDiscovery(MainActivity.this, (ipAddress, port) -> {
                runOnUiThread(() -> {
                    String cleanIp = String.valueOf(ipAddress).replace("/", "");
                    edtIpAddress.setText(cleanIp);
                    socketManager.connectToServer(cleanIp, port, Build.MODEL, gunConnectListener);
                });
            });
        });

        btnConnectManual.setOnClickListener(v -> {
            String ip = edtIpAddress.getText().toString().trim();
            if (!ip.isEmpty()) socketManager.connectToServer(ip, 8888, Build.MODEL, gunConnectListener);
        });

        btnCalibrateSmall.setOnClickListener(v -> {
            if (isGunMode) {
                isCalibrating = true;
                calibStep = 0;
                socketManager.sendCalibrationStart(gunConnectListener);
                Toast.makeText(this, "Kalibrasyon Başladı! 1'e nişan alıp ateş et.", Toast.LENGTH_LONG).show();
            }
        });

        seekSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { currentSensitivity = progress; txtSensitivity.setText("Hassasiyet: " + currentSensitivity); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        checkTvMode();
    }

    private void checkTvMode() {
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (uiModeManager != null && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            if (layoutModeSelection != null) layoutModeSelection.setVisibility(View.GONE);
            btnModeTarget.performClick();
            Toast.makeText(this, "TV Modu: Hedef Otomatik Başlatıldı", Toast.LENGTH_LONG).show();
        }
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview);
            } catch (Exception e) { e.printStackTrace(); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private void performFire() {
        currentAmmo--; updateAmmoUI();
        if (isCalibrating && calibStep < 3) {
            calibOrientations[calibStep][0] = (float) Math.toDegrees(orientationValues[0]);
            calibOrientations[calibStep][1] = (float) Math.toDegrees(orientationValues[1]);
        }
        socketManager.sendShootSignal(gunConnectListener);
        gunView.playRecoil();
        if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        soundPool.play(soundShot, 1f, 1f, 1, 0, 1f);
    }

    private void handleCalibrationHit(int hitStep) {
        runOnUiThread(() -> {
            if (!isCalibrating) return;
            calibStep = hitStep;
            if (calibStep < 3) {
                Toast.makeText(this, (calibStep + 1) + ". noktaya ateş et!", Toast.LENGTH_SHORT).show();
            } else {
                finishCalibration();
            }
        });
    }

    private void finishCalibration() {
        isCalibrating = false;
        
        // Açısal farklar
        float yawDiff = calibOrientations[1][0] - calibOrientations[0][0]; // Sol Üst -> Sağ Üst (Yaw farkı)
        if (yawDiff > 180) yawDiff -= 360; else if (yawDiff < -180) yawDiff += 360;
        
        float pitchDiff = calibOrientations[2][1] - ((calibOrientations[0][1] + calibOrientations[1][1]) / 2f); // Üst -> Alt (Pitch farkı)

        // HedefView üzerindeki piksel mesafeleri
        // Sol Üst (100, 100) -> Sağ Üst (W-100, 100) => Mesafe = W-200
        // Üst Orta (W/2, 100) -> Alt Orta (W/2, H-100) => Mesafe = H-200
        
        // Bu hesaplama için ekran boyutunu bilmemiz lazım ama yaklaşık bir değer veya dinamik bir oran kullanabiliriz.
        // Varsayılan TargetView genişliği ve yüksekliği sunucuda biliniyor.
        // Ancak client (gun) tarafında bunu tam bilmiyoruz.
        // Varsayılan olarak 1920x1080 gibi bir oran düşünürsek:
        float pixelDistX = 1720f; // W-200 (yaklaşık)
        
        if (Math.abs(yawDiff) > 0.1f) {
            currentSensitivity = Math.abs(pixelDistX / yawDiff);
        }

        // Ofsetleri ayarla (Merkez nokta olarak 2. ve 1. noktanın ortası ile 3. noktanın ortasını baz alabiliriz)
        baseYaw = (calibOrientations[0][0] + calibOrientations[1][0]) / 2f;
        basePitch = (calibOrientations[0][1] + calibOrientations[2][1]) / 2f;

        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
        }
        
        txtSensitivity.setText("Hassasiyet: " + (int)currentSensitivity);
        seekSensitivity.setProgress((int)currentSensitivity);
        
        Toast.makeText(this, "Kalibrasyon Tamamlandı! Hassasiyet: " + (int)currentSensitivity, Toast.LENGTH_LONG).show();
    }

    private void initGunSounds() {
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        soundPool = new SoundPool.Builder().setMaxStreams(5).setAudioAttributes(attrs).build();
        soundShot = soundPool.load(this, R.raw.hit_sound_glock, 1);
        soundReload = soundPool.load(this, R.raw.charger_sound_handgun, 1);
        soundEmpty = soundPool.load(this, R.raw.miss_sound_metal2, 1);
    }

    private void updateAmmoUI() { runOnUiThread(() -> { if (txtAmmo != null) txtAmmo.setText(currentAmmo + " / " + MAX_AMMO); }); }
    private void reloadGun() { soundPool.play(soundReload, 1f, 1f, 1, 0, 1f); currentAmmo = MAX_AMMO; updateAmmoUI(); }

    private String getDeviceIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) return Formatter.formatIpAddress(wifiManager.getConnectionInfo().getIpAddress());
        } catch (Exception e) { e.printStackTrace(); }
        return "192.168.x.x";
    }

    private void registerSensors() { if (sensorManager != null && rotationVectorSensor != null) sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME); }
    private void unregisterSensors() { if (sensorManager != null) sensorManager.unregisterListener(this); }

    private long lastAimSentTime = 0;
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isGunMode) return;
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            long now = System.currentTimeMillis();
            if (now - lastAimSentTime < 25) return;
            lastAimSentTime = now;
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationValues);
            float yaw = (float) Math.toDegrees(orientationValues[0]) - baseYaw;
            float pitch = (float) Math.toDegrees(orientationValues[1]) - basePitch;
            if (yaw > 180) yaw -= 360; else if (yaw < -180) yaw += 360;
            socketManager.sendAimData(yaw * currentSensitivity, pitch * currentSensitivity, gunConnectListener);
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override protected void onResume() { super.onResume(); if (isGunMode) { registerSensors(); hideSystemUI(); } }
    @Override protected void onPause() { super.onPause(); unregisterSensors(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (socketManager != null) socketManager.stop();
        if (targetNsdManager != null) targetNsdManager.stopService();
        if (gunNsdManager != null) gunNsdManager.stopDiscovery();
        if (soundPool != null) soundPool.release();
    }
}
