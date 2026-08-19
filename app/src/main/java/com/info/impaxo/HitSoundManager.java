package com.info.impaxo;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class HitSoundManager {

    private SoundPool soundPool;
    private List<Integer> hitSoundIds = new ArrayList<>();
    private List<Integer> missSoundIds = new ArrayList<>();
    private AudioManager audioManager;

    public HitSoundManager(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(audioAttributes)
                .build();

        loadSounds(context);
    }

    private void loadSounds(Context context) {
        Field[] fields = R.raw.class.getFields();
        for (Field field : fields) {
            String name = field.getName();
            try {
                int resId = field.getInt(null);
                if (name.startsWith("hit_sound")) {
                    hitSoundIds.add(soundPool.load(context, resId, 1));
                    Log.d("ImpaxoLog", "Vuruş sesi yüklendi: " + name);
                } else if (name.startsWith("miss_sound")) {
                    missSoundIds.add(soundPool.load(context, resId, 1));
                    Log.d("ImpaxoLog", "Iskalama sesi yüklendi: " + name);
                }
            } catch (Exception e) {
                Log.e("ImpaxoLog", "Ses yükleme hatası: " + name, e);
            }
        }
        
        // Fallback
        if (hitSoundIds.isEmpty()) {
            int resId = context.getResources().getIdentifier("hit_sound", "raw", context.getPackageName());
            if (resId != 0) hitSoundIds.add(soundPool.load(context, resId, 1));
        }
    }

    public void playHitSound(int playerIndex) {
        playRandomizedSound(hitSoundIds, playerIndex);
    }

    public void playMissSound(int playerIndex) {
        playRandomizedSound(missSoundIds, playerIndex);
    }

    private void playRandomizedSound(List<Integer> soundList, int playerIndex) {
        if (soundList == null || soundList.isEmpty()) return;

        int soundId = soundList.get(playerIndex % soundList.size());
        float randomPitch = 0.95f + (float)Math.random() * 0.1f;
        soundPool.play(soundId, 1.0f, 1.0f, 1, 0, randomPitch);
    }

    public void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}
