package com.info.impaxo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.annotation.Nullable;

public class GunView extends View {

    private Bitmap bodyBitmap, triggerBitmap, slideBitmap;
    private RectF bodyRect = new RectF();
    private Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint laserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF laserSwitchRect = new RectF();
    private Paint switchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    
    // =========================================================================
    // 🛡️ KULLANICI ÖZEL AYAR BÖLGESİ (DOKUNULMAZ)
    // Bu sayısal değerler sadece kullanıcı tarafından elle düzenlenmelidir.
    // =========================================================================
    private float gunOverallScale = 1.7f;
    private float gunXOffset = 160f;
    private float gunYOffset = 0f;

    private float slideZoneLeft = 0.05f, slideZoneTop = 0.15f, slideZoneRight = 0.2f, slideZoneBottom = 0.8f;
    private float triggerZoneLeft = 0.45f, triggerZoneTop = 0.3f, triggerZoneRight = 0.85f, triggerZoneBottom = 0.7f;
    private float laserSwitchLeft = 0.23f, laserSwitchTop = 0.38f, laserSwitchRight = 0.33f, laserSwitchBottom = 0.45f;

    private float triggerAnchorXRatio = 0.55f;  // bu sayı aslında aşağıdan y eksenini gösteriyor
    private float triggerAnchorYRatio = 0.55f;  // bu sayı aslında soldan x eksenini gösteriyor
    private float triggerScale = 0.11f;

    private float triggerPullDistance = 150f;
    private float slidePullDistance = 120f;

    private float barrelAnchorXRatio = 1.0f;  // Namlu ucu ileri-geri (1.0f tam uç noktadır)
    private float barrelAnchorYRatio = 0.19f; // Namlu ucu yukarı-aşağı
    // =========================================================================

    private RectF slideZoneRect = new RectF();
    private RectF triggerZoneRect = new RectF();

    // ÇOKLU DOKUNMA TAKİBİ
    private int triggerPointerId = -1;
    private int slidePointerId = -1;
    private float triggerStartY = 0f;
    private float slideStartY = 0f;

    // DURUM
    private float triggerRotation = 0f;
    private float slideOffset = 0f;
    private boolean triggerThresholdReached = false, slideThresholdReached = false;
    private boolean isTriggerDragging = false, isSlideDragging = false;
    private boolean showFlash = false;
    private boolean laserEnabled = false;
    private int laserColor = Color.RED;

    private OnGunEventListener listener;

    public interface OnGunEventListener {
        void onTriggerPulled(float pullRatio);
        void onFire();
        void onSlidePulled();
        void onLaserToggled(boolean isOn);
    }

    public GunView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(context); }
    public GunView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(context); }

    private void init(Context context) {
        // YENİ ANDROID SÜRÜMLERİNDE ÇİZİM HATALARINI ÖNLEMEK İÇİN
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        loadResources(context);
        flashPaint.setStyle(Paint.Style.FILL);
        laserPaint.setStrokeWidth(8f);
    }

    private void loadResources(Context context) {
        try {
            bodyBitmap = drawableToBitmap(context.getResources().getDrawable(R.drawable.gun_body, context.getTheme()));
            triggerBitmap = drawableToBitmap(context.getResources().getDrawable(R.drawable.gun_trigger, context.getTheme()));
            slideBitmap = drawableToBitmap(context.getResources().getDrawable(R.drawable.gun_slide, context.getTheme()));
        } catch (Exception e) { e.printStackTrace(); }
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) return ((BitmapDrawable) drawable).getBitmap();
        int w = Math.max(1, drawable.getIntrinsicWidth()), h = Math.max(1, drawable.getIntrinsicHeight());
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bitmap;
    }

    public void setLaserEnabled(boolean enabled) { this.laserEnabled = enabled; invalidate(); }
    public void setLaserColor(int color) { this.laserColor = color; invalidate(); }
    public void setListener(OnGunEventListener listener) { this.listener = listener; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // TASARIM MODUNDA (PREVIEW) HASSAS AYAR REHBERİ
        if (isInEditMode()) {
            canvas.save();
            canvas.rotate(-90, w / 2f, h / 2f);
            
            // Sahte Gövde Boyutları (Gerçek oranlara yakın)
            float drawW = w * gunOverallScale, drawH = drawW * 0.5f;
            float left = (w - drawW) / 2f + gunYOffset;
            float top = (h - drawH) / 2f + gunXOffset;
            RectF mockBody = new RectF(left, top, left + drawW, top + drawH);
            
            Paint p = new Paint();
            p.setColor(Color.DKGRAY);
            canvas.drawRect(mockBody, p);

            // sH (Slide Height) simülasyonu
            float mockSH = mockBody.height() * 0.2f;

            // 1. TETİK REHBER NOKTASI (KIRMIZI)
            float tAnchorX = mockBody.left + (mockBody.width() * triggerAnchorXRatio) - (0.65f * mockSH);
            float tAnchorY = mockBody.top + (mockBody.height() * triggerAnchorYRatio) - (1.50f * mockSH);
            p.setColor(Color.RED);
            canvas.drawCircle(tAnchorX, tAnchorY, 15f, p);
            
            // 2. NAMLU/LAZER ÇIKIŞ NOKTASI (MAVİ)
            float barrelX = mockBody.left + (mockBody.width() * barrelAnchorXRatio);
            float barrelY = mockBody.top + (mockBody.height() * barrelAnchorYRatio);
            p.setColor(Color.CYAN);
            canvas.drawCircle(barrelX, barrelY, 12f, p);

            // 3. DOKUNMATİK BÖLGELERİ (YARI SAYDAM KUTULAR)
            canvas.restore(); // Gerçek ekran koordinatları
            Paint overlayPaint = new Paint();
            
            // 3. DOKUNMATİK BÖLGELERİ (YARI SAYDAM KUTULAR)
            canvas.restore(); // Gerçek ekran koordinatları
            Paint areaPaint = new Paint();
            
            // Sürgü Alanı (SARI)
            slideZoneRect.set(w * slideZoneLeft, h * slideZoneTop, w * slideZoneRight, h * slideZoneBottom);
            areaPaint.setColor(Color.YELLOW);
            areaPaint.setAlpha(60); 
            canvas.drawRect(slideZoneRect, areaPaint);
            
            // Tetik Alanı (PEMBE)
            triggerZoneRect.set(w * triggerZoneLeft, h * triggerZoneTop, w * triggerZoneRight, h * triggerZoneBottom);
            overlayPaint.setColor(Color.rgb(255, 20, 147));
            overlayPaint.setAlpha(60);
            canvas.drawRect(triggerZoneRect, overlayPaint);
            
            // Etiketler
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(35f);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(Color.YELLOW);
            canvas.drawText("SÜRGÜ", slideZoneRect.centerX(), slideZoneRect.centerY(), p);
            
            p.setColor(Color.rgb(255, 20, 147));
            canvas.drawText("TETİK", triggerZoneRect.centerX(), triggerZoneRect.centerY(), p);

            // Lazer Anahtarı (YEŞİL BÖLGE REHBERİ)
            laserSwitchRect.set(w * laserSwitchLeft, h * laserSwitchTop, w * laserSwitchRight, h * laserSwitchBottom);
            overlayPaint.setColor(Color.GREEN);
            overlayPaint.setAlpha(40);
            canvas.drawRect(laserSwitchRect, overlayPaint);
            
            // GERÇEK SWITCH GÖRSELİ (Biraz Padding ile)
            drawLaserSwitch(canvas, w, h);
            
            return;
        }

        if (bodyBitmap == null) return;

        float bodyRatio = (float) bodyBitmap.getHeight() / bodyBitmap.getWidth();
        float drawW, drawH;
        if ((float) w / h > bodyRatio) { drawH = h * gunOverallScale; drawW = drawH / bodyRatio; }
        else { drawW = w * gunOverallScale; drawH = drawW * bodyRatio; }
        
        float left = (w - drawW) / 2f + gunYOffset;
        float top = (h - drawH) / 2f + gunXOffset;
        bodyRect.set(left, top, left + drawW, top + drawH);

        canvas.save();
        canvas.rotate(-90, w / 2f, h / 2f);
        
        float scaleFactor = bodyRect.width() / bodyBitmap.getWidth();
        float sH = (slideBitmap != null) ? slideBitmap.getHeight() * scaleFactor : 0;

        // 1. TETİK (EN ALTA VE DOĞRU KOORDİNATLARDA)
        if (triggerBitmap != null) {
            float tAnchorX = bodyRect.left + (bodyRect.width() * triggerAnchorXRatio) - (0.65f * sH);
            float tAnchorY = bodyRect.top + (bodyRect.height() * triggerAnchorYRatio) - (1.50f * sH);
            
            float tW = drawW * triggerScale;
            float tH = tW * ((float) triggerBitmap.getHeight() / triggerBitmap.getWidth());
            
            canvas.save();
            canvas.translate(tAnchorX, tAnchorY);
            canvas.rotate(triggerRotation);
            canvas.translate(-tW / 2f, -tH * 0.1f);
            canvas.drawBitmap(triggerBitmap, null, new RectF(0, 0, tW, tH), null);
            canvas.restore();
        }

        // 2. GÖVDE (ORTA)
        canvas.drawBitmap(bodyBitmap, null, bodyRect, null);

        // 3. SÜRGÜ (ÜSTE VE HAREKETLİ)
        if (slideBitmap != null) {
            canvas.save();
            canvas.translate(-slideOffset, 0); 
            float sW = slideBitmap.getWidth() * scaleFactor;
            float sLeft = bodyRect.left + sH;
            float sTop = bodyRect.top - (0.42f * sH);
            RectF sRect = new RectF(sLeft, sTop, sLeft + sW, sTop + sH);
            canvas.drawBitmap(slideBitmap, null, sRect, null); 
            canvas.restore();
        }

        // LAZER HÜZMESİ VE FLASH
        if (laserEnabled) drawLaserBeam(canvas, sH);
        if (showFlash) drawMuzzleFlash(canvas, scaleFactor, sH);
        
        canvas.restore();

        // ŞIK LAZER ANAHTARI ÇİZİMİ (GERÇEK OYUN)
        drawLaserSwitch(canvas, w, h);
    }

    private void drawLaserSwitch(Canvas canvas, int w, int h) {
        laserSwitchRect.set(w * laserSwitchLeft, h * laserSwitchTop, w * laserSwitchRight, h * laserSwitchBottom);
        
        // PADDING (KENAR BOŞLUĞU) EKLE
        float padding = w * 0.02f;
        RectF drawingRect = new RectF(laserSwitchRect.left + padding, laserSwitchRect.top + padding, laserSwitchRect.right - padding, laserSwitchRect.bottom - padding);

        float rectW = drawingRect.width();
        float rectH = drawingRect.height();
        float cornerRadius = Math.min(rectW, rectH) / 2f;

        // Anahtar Gövdesi
        switchPaint.setColor(Color.parseColor("#333333"));
        switchPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(drawingRect, cornerRadius, cornerRadius, switchPaint);
        
        // Anahtar Başlığı (Knob)
        float knobRadius = Math.min(rectW, rectH) * 0.4f;
        float knobX = laserEnabled ? drawingRect.right - knobRadius - (padding/2f) : drawingRect.left + knobRadius + (padding/2f);
        
        // Knob Gölgesi
        switchPaint.setColor(Color.BLACK);
        switchPaint.setAlpha(100);
        canvas.drawCircle(knobX, drawingRect.centerY() + 4f, knobRadius, switchPaint);

        // Knob Rengi
        switchPaint.setAlpha(255);
        switchPaint.setColor(laserEnabled ? Color.GREEN : Color.RED);
        canvas.drawCircle(knobX, drawingRect.centerY(), knobRadius, switchPaint);

        // Yazı (ON/OFF)
        switchPaint.setColor(Color.WHITE);
        switchPaint.setTextSize(Math.min(rectW, rectH) * 0.4f);
        switchPaint.setTextAlign(Paint.Align.CENTER);
        float textY = drawingRect.centerY() + (switchPaint.getTextSize()/3f);
        canvas.drawText(laserEnabled ? "ON" : "OFF", drawingRect.centerX(), textY, switchPaint);
    }

    private void drawLaserBeam(Canvas canvas, float sH) {
        float barrelX = bodyRect.left + (bodyRect.width() * barrelAnchorXRatio);
        float barrelY = bodyRect.top + (bodyRect.height() * barrelAnchorYRatio);
        laserPaint.setColor(laserColor); laserPaint.setAlpha(150); laserPaint.setShadowLayer(20, 0, 0, laserColor);
        canvas.drawLine(barrelX, barrelY, barrelX + 2000, barrelY, laserPaint);
        laserPaint.setAlpha(255); canvas.drawCircle(barrelX, barrelY, 6f, laserPaint);
        laserPaint.clearShadowLayer();
    }

    private void drawMuzzleFlash(Canvas canvas, float scale, float sH) {
        float barrelX = bodyRect.left + (bodyRect.width() * barrelAnchorXRatio);
        float barrelY = bodyRect.top + (bodyRect.height() * barrelAnchorYRatio);
        RadialGradient gradient = new RadialGradient(barrelX, barrelY, 150f * scale, new int[]{Color.WHITE, Color.YELLOW, Color.argb(0, 255, 165, 0)}, null, Shader.TileMode.CLAMP);
        flashPaint.setShader(gradient);
        canvas.drawCircle(barrelX, barrelY, 150f * scale, flashPaint);
        flashPaint.setShader(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isInEditMode()) return false;
        
        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);
        int w = getWidth();
        int h = getHeight();

        // Dokunmatik kutuları güncelle
        slideZoneRect.set(w * slideZoneLeft, h * slideZoneTop, w * slideZoneRight, h * slideZoneBottom);
        triggerZoneRect.set(w * triggerZoneLeft, h * triggerZoneTop, w * triggerZoneRight, h * triggerZoneBottom);
        laserSwitchRect.set(w * laserSwitchLeft, h * laserSwitchTop, w * laserSwitchRight, h * laserSwitchBottom);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                float xDown = event.getX(pointerIndex);
                float yDown = event.getY(pointerIndex);

                if (laserSwitchRect.contains(xDown, yDown)) {
                    laserEnabled = !laserEnabled;
                    if (listener != null) listener.onLaserToggled(laserEnabled);
                    invalidate();
                } else if (slideZoneRect.contains(xDown, yDown) && slidePointerId == -1) {
                    slidePointerId = pointerId;
                    slideStartY = yDown;
                    isSlideDragging = true;
                    slideThresholdReached = false;
                } else if (triggerZoneRect.contains(xDown, yDown) && triggerPointerId == -1) {
                    triggerPointerId = pointerId;
                    triggerStartY = yDown;
                    isTriggerDragging = true;
                    triggerThresholdReached = false;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pId = event.getPointerId(i);
                    float yMove = event.getY(i);

                    if (pId == triggerPointerId) {
                        float deltaY = yMove - triggerStartY;
                        if (deltaY < 0) deltaY = 0;
                        float pullRatio = Math.min(1.0f, deltaY / triggerPullDistance);
                        triggerRotation = pullRatio * 35f;
                        if (listener != null) listener.onTriggerPulled(pullRatio);
                        if (pullRatio > 0.8f) triggerThresholdReached = true;
                    } else if (pId == slidePointerId) {
                        float deltaY = yMove - slideStartY;
                        if (deltaY < 0) deltaY = 0;
                        slideOffset = Math.min(slidePullDistance, deltaY);
                        if (slideOffset > (slidePullDistance * 0.8f)) slideThresholdReached = true;
                    }
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pointerId == triggerPointerId) {
                    if (triggerThresholdReached && listener != null) listener.onFire();
                    isTriggerDragging = false;
                    triggerPointerId = -1;
                    animateBack();
                } else if (pointerId == slidePointerId) {
                    if (slideThresholdReached && listener != null) listener.onSlidePulled();
                    isSlideDragging = false;
                    slidePointerId = -1;
                    animateBack();
                }
                return true;
        }
        return false;
    }

    private void animateBack() {
        postDelayed(new Runnable() {
            @Override public void run() {
                boolean changed = false;
                if (triggerRotation > 0) { triggerRotation -= 5f; if (triggerRotation < 0) triggerRotation = 0; changed = true; }
                if (slideOffset > 0) { slideOffset -= 20f; if (slideOffset < 0) slideOffset = 0; changed = true; }
                if (changed) { invalidate(); postDelayed(this, 16); }
            }
        }, 16);
    }
    
    public void playRecoil() {
        showFlash = true; invalidate(); postDelayed(() -> { showFlash = false; invalidate(); }, 50);
        slideOffset = 60f; invalidate(); animateBack();
        this.animate().translationX(50f).setDuration(50).withEndAction(() -> {
            this.animate().translationX(0f).setDuration(200).start();
        }).start();
    }
}
