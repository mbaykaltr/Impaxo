package com.info.impaxo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class TargetView extends View {

    public enum GameMode { CLASSIC, MOLE, POLYGON, BIRD }
    private GameMode currentMode = GameMode.CLASSIC;

    private Paint ringPaint, textPaint, scorePaint, targetPaint;
    private Bitmap[] brokenBitmaps = new Bitmap[6];
    private Bitmap moleHole, moleActive, polyTarget, birdUp, birdDown;
    private Random random = new Random();
    
    // MOD DEĞİŞKENLERİ
    private List<PointF> moleHoles = new ArrayList<>();
    private int activeMoleIndex = -1;
    private long lastMoleChangeTime = 0;
    
    private PointF polyPos = new PointF();
    private boolean polyVisible = false;
    private long lastPolyChangeTime = 0;

    private PointF birdPos = new PointF();
    private boolean birdWingUp = true;
    private long lastBirdMoveTime = 0;

    private static class BulletHole {
        PointF position; int color; float size; int brokenIndex; float rotation;
        BulletHole(PointF pos, int col, float sz, int index, float rot) {
            this.position = pos; this.color = col; this.size = sz; this.brokenIndex = index; this.rotation = rot;
        }
    }

    private List<BulletHole> bulletHoles = new ArrayList<>();
    private Map<String, PointF> aimPoints = new ConcurrentHashMap<>();
    private Map<String, Integer> playerScores = new ConcurrentHashMap<>();
    private Map<String, Boolean> laserStates = new ConcurrentHashMap<>();
    private Map<String, Integer> playerCalibrationStep = new ConcurrentHashMap<>();
    
    public enum GameState { WAITING, PLAYING, FINISHED, CALIBRATING }
    private GameState currentState = GameState.WAITING;
    private int gameTimeLeft = 60;
    private long gameStartTime = 0;
    private String winnerId = "";
    private float targetSpeedMultiplier = 1.0f;
    private int totalHitsThisGame = 0;
    
    public interface OnGameEventListener {
        void onGameStarted();
        void onGameFinished(String winner, Map<String, Integer> scores);
    }
    private OnGameEventListener gameEventListener;
    public void setGameEventListener(OnGameEventListener listener) { this.gameEventListener = listener; }

    private float sensitivity = 1.2f;
    private HitSoundManager hitSoundManager;
    private int[] availableColors = {Color.YELLOW, Color.GREEN, Color.CYAN, Color.MAGENTA, Color.rgb(255, 165, 0), Color.RED};

    public TargetView(Context context) { super(context); init(); }
    public TargetView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(30f); textPaint.setFakeBoldText(true);
        scorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scorePaint.setColor(Color.WHITE); scorePaint.setTextSize(40f); scorePaint.setFakeBoldText(true);
        scorePaint.setShadowLayer(5f, 2f, 2f, Color.BLACK);
        hitSoundManager = new HitSoundManager(getContext());
        loadBitmaps();
    }

    private void loadBitmaps() {
        for (int i = 0; i < 6; i++) {
            int id = getResources().getIdentifier("target_broken_" + (i + 1), "drawable", getContext().getPackageName());
            if (id != 0) brokenBitmaps[i] = BitmapFactory.decodeResource(getResources(), id);
        }
        moleHole = BitmapFactory.decodeResource(getResources(), R.drawable.target_mole_hole);
        moleActive = BitmapFactory.decodeResource(getResources(), R.drawable.target_mole_active);
        polyTarget = BitmapFactory.decodeResource(getResources(), R.drawable.target_human_silhouette);
        birdUp = BitmapFactory.decodeResource(getResources(), R.drawable.target_bird_wing_up);
        birdDown = BitmapFactory.decodeResource(getResources(), R.drawable.target_bird_wing_down);
    }

    public void setGameMode(GameMode mode) {
        this.currentMode = mode;
        this.bulletHoles.clear();
        this.activeMoleIndex = -1;
        this.polyVisible = false;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.rgb(20, 25, 30));
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // Zamanlayıcı Mantığı
        if (currentState == GameState.PLAYING) {
            long elapsed = (System.currentTimeMillis() - gameStartTime) / 1000;
            gameTimeLeft = Math.max(0, 60 - (int) elapsed);
            if (gameTimeLeft == 0) stopGame();
        }

        switch (currentMode) {
            case CLASSIC: drawClassicTarget(canvas, w, h); break;
            case MOLE: drawMoleGame(canvas, w, h); break;
            case POLYGON: drawPolygonGame(canvas, w, h); break;
            case BIRD: drawBirdGame(canvas, w, h); break;
        }

        if (!playerCalibrationStep.isEmpty()) {
            drawCalibrationTargets(canvas, w, h);
        }

        for (BulletHole hole : bulletHoles) drawBrokenHole(canvas, hole);
        drawLasers(canvas);
        drawScoreboard(canvas);
        drawGameUI(canvas);
        postInvalidateOnAnimation();
    }

    private void drawClassicTarget(Canvas canvas, int w, int h) {
        float cx = w / 2f, cy = h / 2f, maxR = Math.min(cx, cy) - 100;
        int[] colors = {Color.WHITE, Color.BLACK, Color.BLUE, Color.RED, Color.YELLOW};
        for (int i = 0; i < 5; i++) {
            ringPaint.setColor(colors[i]); ringPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, maxR * (1.0f - i * 0.2f), ringPaint);
        }
    }

    private void drawMoleGame(Canvas canvas, int w, int h) {
        if (moleHoles.isEmpty()) {
            for (int i = 0; i < 6; i++) moleHoles.add(new PointF(random.nextInt(w - 200) + 100, random.nextInt(h - 200) + 100));
        }
        long now = System.currentTimeMillis();
        if (now - lastMoleChangeTime > (1500 / targetSpeedMultiplier)) {
            activeMoleIndex = random.nextInt(moleHoles.size());
            lastMoleChangeTime = now;
        }
        for (int i = 0; i < moleHoles.size(); i++) {
            Bitmap b = (i == activeMoleIndex) ? moleActive : moleHole;
            if (b != null) canvas.drawBitmap(b, null, new RectF(moleHoles.get(i).x-80, moleHoles.get(i).y-80, moleHoles.get(i).x+80, moleHoles.get(i).y+80), null);
        }
    }

    private void drawPolygonGame(Canvas canvas, int w, int h) {
        long now = System.currentTimeMillis();
        if (!polyVisible && now - lastPolyChangeTime > (2000 / targetSpeedMultiplier)) {
            polyPos.set(random.nextInt(w - 200) + 100, random.nextInt(h - 200) + 100);
            polyVisible = true; lastPolyChangeTime = now;
        } else if (polyVisible && now - lastPolyChangeTime > (3000 / targetSpeedMultiplier)) {
            polyVisible = false; lastPolyChangeTime = now;
        }
        if (polyVisible && polyTarget != null) {
            canvas.drawBitmap(polyTarget, null, new RectF(polyPos.x-100, polyPos.y-150, polyPos.x+100, polyPos.y+150), null);
        }
    }

    private void drawBirdGame(Canvas canvas, int w, int h) {
        long now = System.currentTimeMillis();
        if (now - lastBirdMoveTime > 50) {
            birdPos.x += (10 * targetSpeedMultiplier);
            if (birdPos.x > w + 100) { birdPos.x = -100; birdPos.y = random.nextInt(h - 300) + 150; }
            if (now % 300 < 150) birdWingUp = !birdWingUp;
            lastBirdMoveTime = now;
        }
        Bitmap b = birdWingUp ? birdUp : birdDown;
        if (b != null) canvas.drawBitmap(b, null, new RectF(birdPos.x-80, birdPos.y-80, birdPos.x+80, birdPos.y+80), null);
    }

    private void drawCalibrationTargets(Canvas canvas, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(40f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);

        for (Map.Entry<String, Integer> entry : playerCalibrationStep.entrySet()) {
            int step = entry.getValue();
            PointF pos = getCalibrationTargetPos(step, w, h);
            if (pos == null) continue;

            int col = getPlayerColor(entry.getKey());
            p.setColor(col);
            canvas.drawCircle(pos.x, pos.y, 30f, p);
            p.setColor(Color.WHITE);
            canvas.drawText(String.valueOf(step), pos.x, pos.y + 15f, p);
        }
    }

    private PointF getCalibrationTargetPos(int step, int w, int h) {
        switch (step) {
            case 1: return new PointF(100f, 100f); // Sol Üst
            case 2: return new PointF(w - 100f, 100f); // Sağ Üst
            case 3: return new PointF(w / 2f, h - 100f); // Alt Orta
            default: return null;
        }
    }

    private void drawLasers(Canvas canvas) {
        for (Map.Entry<String, PointF> entry : aimPoints.entrySet()) {
            if (laserStates.getOrDefault(entry.getKey(), false)) {
                int col = getPlayerColor(entry.getKey());
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(col);
                p.setAlpha(100); canvas.drawCircle(entry.getValue().x, entry.getValue().y, 25f, p);
                p.setAlpha(255); p.setShadowLayer(15f, 0, 0, col);
                canvas.drawCircle(entry.getValue().x, entry.getValue().y, 12f, p);
                textPaint.setColor(col); canvas.drawText(entry.getKey(), entry.getValue().x + 30, entry.getValue().y - 30, textPaint);
            }
        }
    }

    private void drawBrokenHole(Canvas canvas, BulletHole hole) {
        Bitmap b = brokenBitmaps[hole.brokenIndex];
        if (b != null) {
            canvas.save(); canvas.translate(hole.position.x, hole.position.y); canvas.rotate(hole.rotation);
            Paint p = new Paint(); p.setAlpha(200);
            float dW = hole.size, dH = dW * ((float) b.getHeight() / b.getWidth());
            canvas.drawBitmap(b, null, new RectF(-dW/2, -dH/2, dW/2, dH/2), p);
            p.setColor(hole.color); p.setAlpha(120); canvas.drawCircle(0, 0, dW/4f, p);
            canvas.restore();
        }
    }

    private void drawScoreboard(Canvas canvas) {
        float x = 50, y = 80;
        canvas.drawText("🏆 PUAN TABLOSU", x, y, scorePaint);
        y += 60;
        for (Map.Entry<String, Integer> entry : playerScores.entrySet()) {
            Paint p = new Paint(scorePaint); p.setColor(getPlayerColor(entry.getKey())); p.setTextSize(35f);
            canvas.drawText(entry.getKey() + ": " + entry.getValue(), x, y, p);
            y += 50;
        }
    }

    public int checkCalibrationHit(String clientId) {
        Integer step = playerCalibrationStep.get(clientId);
        if (step == null) return 0;

        PointF aim = aimPoints.get(clientId);
        if (aim == null) return 0;

        PointF targetPos = getCalibrationTargetPos(step, getWidth(), getHeight());
        if (targetPos == null) return 0;

        double dist = Math.sqrt(Math.pow(aim.x - targetPos.x, 2) + Math.pow(aim.y - targetPos.y, 2));
        if (dist < 80) {
            int currentStep = step;
            if (currentStep < 3) {
                playerCalibrationStep.put(clientId, currentStep + 1);
            } else {
                playerCalibrationStep.remove(clientId);
            }
            if (hitSoundManager != null) hitSoundManager.playHitSound(Math.abs(clientId.hashCode()));
            return currentStep;
        }
        return 0;
    }

    public void startPlayerCalibration(String clientId) {
        playerCalibrationStep.put(clientId, 1);
        postInvalidate();
    }

    public void addBulletHole(String clientId) {
        if (currentState != GameState.PLAYING) return;

        PointF aim = aimPoints.get(clientId);
        if (aim == null) return;
        
        int points = 0;
        switch (currentMode) {
            case CLASSIC:
                float cx = getWidth()/2f, cy = getHeight()/2f, maxR = Math.min(cx, cy) - 100;
                double dist = Math.sqrt(Math.pow(aim.x-cx,2)+Math.pow(aim.y-cy,2));
                if (dist < maxR * 0.1) points = 10; else if (dist < maxR * 0.4) points = 7; else if (dist < maxR) points = 3;
                break;
            case MOLE:
                if (activeMoleIndex != -1) {
                    PointF m = moleHoles.get(activeMoleIndex);
                    if (Math.sqrt(Math.pow(aim.x-m.x,2)+Math.pow(aim.y-m.y,2)) < 100) { points = 15; activeMoleIndex = -1; }
                }
                break;
            case POLYGON:
                if (polyVisible && Math.sqrt(Math.pow(aim.x-polyPos.x,2)+Math.pow(aim.y-polyPos.y,2)) < 120) { points = 20; polyVisible = false; }
                break;
            case BIRD:
                if (Math.sqrt(Math.pow(aim.x-birdPos.x,2)+Math.pow(aim.y-birdPos.y,2)) < 100) { points = 25; birdPos.x = -200; }
                break;
        }

        if (points > 0) {
            playerScores.put(clientId, playerScores.getOrDefault(clientId, 0) + points);
            totalHitsThisGame++;
            if (totalHitsThisGame % 5 == 0) targetSpeedMultiplier += 0.1f;

            if (hitSoundManager != null) hitSoundManager.playHitSound(Math.abs(clientId.hashCode()));
            bulletHoles.add(new BulletHole(new PointF(aim.x, aim.y), getPlayerColor(clientId), 50f, random.nextInt(6), random.nextFloat()*360));
        } else if (hitSoundManager != null) {
            hitSoundManager.playMissSound(Math.abs(clientId.hashCode()));
        }
        postInvalidate();
    }

    private int getPlayerColor(String clientId) { return availableColors[Math.abs(clientId.hashCode()) % availableColors.length]; }
    public void updateAim(String clientId, float yaw, float pitch) {
        float x = (getWidth()/2f) + (yaw * sensitivity), y = (getHeight()/2f) + (pitch * sensitivity);
        aimPoints.put(clientId, new PointF(x, y)); postInvalidate();
    }
    public void removePlayer(String clientId) { aimPoints.remove(clientId); playerScores.remove(clientId); laserStates.remove(clientId); postInvalidate(); }
    public void setLaserState(String clientId, boolean isOn) { laserStates.put(clientId, isOn); postInvalidate(); }
    public void clearHoles() { bulletHoles.clear(); playerScores.clear(); postInvalidate(); }

    public void startGame() {
        currentState = GameState.PLAYING;
        gameStartTime = System.currentTimeMillis();
        gameTimeLeft = 60;
        totalHitsThisGame = 0;
        targetSpeedMultiplier = 1.0f;
        clearHoles();
        winnerId = "";
        if (gameEventListener != null) gameEventListener.onGameStarted();
        postInvalidate();
    }

    public void stopGame() {
        if (currentState != GameState.PLAYING) return;
        currentState = GameState.FINISHED;
        calculateWinner();
        if (gameEventListener != null) gameEventListener.onGameFinished(winnerId, playerScores);
        postInvalidate();
    }

    private void calculateWinner() {
        String topPlayer = "Yok";
        int maxScore = -1;
        for (Map.Entry<String, Integer> entry : playerScores.entrySet()) {
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                topPlayer = entry.getKey();
            }
        }
        winnerId = topPlayer;
    }

    private void drawGameUI(Canvas canvas) {
        // Zamanlayıcı Çizimi
        textPaint.setTextSize(60f);
        textPaint.setColor(android.graphics.Color.WHITE);
        String timeStr = "Süre: " + gameTimeLeft + "s";
        canvas.drawText(timeStr, 50, 100, textPaint);

        // Seviye/Hız Bilgisi
        textPaint.setTextSize(40f);
        String levelStr = "Hız: x" + String.format("%.1f", targetSpeedMultiplier);
        canvas.drawText(levelStr, 50, 160, textPaint);

        // Oyun Bitti Ekranı
        if (currentState == GameState.FINISHED) {
            Paint overlayPaint = new Paint();
            overlayPaint.setColor(android.graphics.Color.argb(200, 0, 0, 0));
            canvas.drawRect(0, 0, getWidth(), getHeight(), overlayPaint);

            textPaint.setTextSize(100f);
            textPaint.setColor(android.graphics.Color.YELLOW);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("OYUN BİTTİ", getWidth()/2f, getHeight()/2f - 100, textPaint);

            textPaint.setTextSize(70f);
            textPaint.setColor(android.graphics.Color.WHITE);
            canvas.drawText("KAZANAN: " + winnerId, getWidth()/2f, getHeight()/2f + 50, textPaint);
            
            textPaint.setTextSize(40f);
            canvas.drawText("Yeni oyun için mod seçin", getWidth()/2f, getHeight()/2f + 150, textPaint);
            textPaint.setTextAlign(Paint.Align.LEFT); // Reset alignment
        }
    }
}
