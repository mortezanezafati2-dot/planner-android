package com.morteza.aria;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.text.SimpleDateFormat;
import java.text.Normalizer;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class VoiceAssistantService extends Service implements RecognitionListener {

    private static final String PREFS = "aria_prefs";
    private static final String KEY_WAKE_PHRASE = "wake_phrase";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL_ID = "aria_voice";
    private static final int NOTIFICATION_ID = 7101;
    private static final float SAMPLE_RATE = 16000.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private TextToSpeech tts;
    private PowerManager.WakeLock wakeLock;

    private boolean ttsReady = false;
    private boolean modelReady = false;
    private boolean listening = false;
    private boolean commandMode = false;
    private boolean transitioning = false;
    private boolean stopping = false;
    private long lastWakeAt = 0L;

    private String wakePhrase = "آریا";

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wakePhrase = safeWakePhrase();

        createNotificationChannel();
        startForegroundCompat("در حال آماده‌سازی آریا…");
        acquireWakeLock();

        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("fa", "IR"));
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(0.95f);
                ttsReady = true;
                updateNotification("آماده — منتظر «" + safeWakePhrase() + "»");
                maybeStartWakeMode();
            } else {
                updateNotification("موتور پاسخ صوتی آماده نشد");
            }
        });

        updateNotification("در حال بارگذاری مدل فارسی…");

        StorageService.unpack(
                this,
                "model-fa",
                "model-fa",
                loadedModel -> {
                    model = loadedModel;
                    modelReady = true;
                    updateNotification("مدل فارسی آماده است");
                    maybeStartWakeMode();
                },
                exception -> {
                    modelReady = false;
                    updateNotification("خطا در مدل فارسی");
                    speakWhenReady("مدل فارسی بارگذاری نشد. برنامه را دوباره باز کن.", null);
                }
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wakePhrase = safeWakePhrase();

        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        maybeStartWakeMode();
        return START_STICKY;
    }

    private void maybeStartWakeMode() {
        if (!modelReady || !ttsReady || stopping ||
                !prefs.getBoolean(KEY_ENABLED, true) ||
                listening || transitioning || commandMode) {
            return;
        }

        handler.postDelayed(this::startWakeMode, 100);
    }

    private String safeWakePhrase() {
        String value = prefs.getString(KEY_WAKE_PHRASE, "آریا");
        value = value == null ? "" : value.trim();
        return value.isEmpty() ? "آریا" : value;
    }

    private void startWakeMode() {
        if (!modelReady || model == null || stopping ||
                !prefs.getBoolean(KEY_ENABLED, true) ||
                listening || transitioning || commandMode) {
            return;
        }

        wakePhrase = safeWakePhrase();
        commandMode = false;
        transitioning = false;

        if (!createRecognizerAndSpeechService(true)) {
            scheduleWakeRestart(1200);
            return;
        }

        if (speechService.startListening(this)) {
            listening = true;
            updateNotification("فعال — منتظر «" + wakePhrase + "»");
        } else {
            listening = false;
            restartRecognizer(800);
        }
    }

    private void startCommandMode() {
        if (stopping || !prefs.getBoolean(KEY_ENABLED, true) || model == null) {
            return;
        }

        commandMode = true;
        transitioning = false;

        // A fresh recognizer is deliberately used for commands. The wake-word
        // grammar must never leak into the command recognizer.
        if (!createRecognizerAndSpeechService(false)) {
            commandMode = false;
            speakWhenReady("مشکل در شنیدن فرمان پیش آمد.", this::returnToWakeMode);
            return;
        }

        if (speechService.startListening(this, 7000)) {
            listening = true;
            updateNotification("شنیدم — منتظر فرمان شما هستم");
        } else {
            listening = false;
            commandMode = false;
            speakWhenReady("دوباره بگو.", this::returnToWakeMode);
        }
    }

    private boolean createRecognizerAndSpeechService(boolean wakeMode) {
        destroySpeechObjects();

        try {
            recognizer = wakeMode
                    ? new Recognizer(model, SAMPLE_RATE,
                        "[\"آریا\",\"اریا\",\"هریا\",\"آریا جان\",\"[unk]\"]")
                    : new Recognizer(model, SAMPLE_RATE);

            speechService = new SpeechService(recognizer, SAMPLE_RATE);
            return true;
        } catch (Exception e) {
            destroySpeechObjects();
            return false;
        }
    }

    private void destroySpeechObjects() {
        if (speechService != null) {
            try {
                speechService.cancel();
            } catch (Exception ignored) {
            }
            try {
                speechService.shutdown();
            } catch (Exception ignored) {
            }
            speechService = null;
        }

        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }

        listening = false;
    }

    private void restartRecognizer(long delayMs) {
        destroySpeechObjects();
        commandMode = false;
        transitioning = false;
        scheduleWakeRestart(delayMs);
    }

    private void scheduleWakeRestart(long delayMs) {
        handler.postDelayed(() -> {
            if (!stopping && prefs.getBoolean(KEY_ENABLED, true)) {
                maybeStartWakeMode();
            }
        }, delayMs);
    }

    private void activateFromWake(String spoken) {
        long now = System.currentTimeMillis();

        if (now - lastWakeAt < 1200 || transitioning || stopping ||
                !prefs.getBoolean(KEY_ENABLED, true)) {
            return;
        }

        if (!isWakePhrase(spoken)) {
            return;
        }

        lastWakeAt = now;
        transitioning = true;
        listening = false;

        if (speechService != null) {
            try {
                speechService.cancel();
            } catch (Exception ignored) {
            }
        }

        updateNotification("آریا فعال شد");

        // Most importantly: acknowledgement is guaranteed to happen after
        // TTS is actually initialized, then command recognition starts fresh.
        speakWhenReady("بله؟", this::startCommandMode);
    }

    private void handleCommand(String spoken) {
        String text = normalize(spoken);

        if (text.isEmpty()) {
            commandMode = false;
            speakWhenReady("صدات رو واضح نشنیدم. دوباره بگو.", this::returnToWakeMode);
            return;
        }

        if (containsAny(text, "خاموش شو", "غیرفعال شو", "دیگه گوش نده", "بس کن")) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            commandMode = false;
            speakWhenReady("باشه، خاموش شدم.", this::stopSelf);
            return;
        }

        if (containsAny(text, "ساعت چنده", "ساعت چند", "الان ساعت", "زمان چنده")) {
            String time = new SimpleDateFormat("HH:mm", new Locale("fa", "IR"))
                    .format(new Date());
            commandMode = false;
            speakWhenReady("الان ساعت " + time + " است.", this::returnToWakeMode);
            return;
        }

        if (containsAny(text, "سلام", "درود")) {
            commandMode = false;
            speakWhenReady("سلام. من آریا هستم و آماده‌ام.", this::returnToWakeMode);
            return;
        }

        if (containsAny(text, "اسمت چیه", "اسم تو چیه", "کی هستی")) {
            commandMode = false;
            speakWhenReady("اسم من آریاست. هر وقت گفتی «" +
                    safeWakePhrase() + "» جواب می‌دم.", this::returnToWakeMode);
            return;
        }

        if (containsAny(text, "ممنون", "مرسی", "تشکر")) {
            commandMode = false;
            speakWhenReady("خواهش می‌کنم.", this::returnToWakeMode);
            return;
        }

        // Do not pretend that arbitrary speech has been answered by an AI.
        // This version explicitly reports unsupported commands instead.
        commandMode = false;
        speakWhenReady("فرمان «" + spoken +
                "» را دریافت کردم، اما برای این فرمان هنوز عملی تعریف نشده است.",
                this::returnToWakeMode);
    }

    private void returnToWakeMode() {
        if (stopping || !prefs.getBoolean(KEY_ENABLED, true)) {
            return;
        }

        destroySpeechObjects();
        commandMode = false;
        transitioning = false;
        handler.postDelayed(this::startWakeMode, 250);
    }

    private boolean isWakePhrase(String spoken) {
        String text = normalize(spoken);
        String phrase = normalize(wakePhrase);

        if (text.equals(phrase) ||
                text.startsWith(phrase + " ") ||
                text.endsWith(" " + phrase) ||
                text.contains(" " + phrase + " ")) {
            return true;
        }

        String[] tokens = text.split(" ");
        String[] accepted = {phrase, "اریا", "هریا"};

        for (String token : tokens) {
            for (String target : accepted) {
                if (!target.isEmpty() && levenshtein(token, target) <= 1) {
                    return true;
                }
            }
        }

        return false;
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    private String normalize(String input) {
        if (input == null) return "";

        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ي', 'ی')
                .replace('ى', 'ی')
                .replace('ك', 'ک')
                .replace("ۀ", "ه")
                .replaceAll("[ًٌٍَُِّْـ]", "")
                .replaceAll("[^\p{L}\p{N}\s]", " ")
                .replaceAll("\s+", " ")
                .trim();
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(normalize(value))) return true;
        }
        return false;
    }

    private void speakWhenReady(String text, Runnable after) {
        if (tts == null || !ttsReady) {
            handler.postDelayed(() -> speakWhenReady(text, after), 150);
            return;
        }

        final String id = UUID.randomUUID().toString();

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}

            @Override public void onDone(String utteranceId) {
                if (id.equals(utteranceId) && after != null) {
                    handler.post(after);
                }
            }

            @Override public void onError(String utteranceId) {
                if (id.equals(utteranceId) && after != null) {
                    handler.post(after);
                }
            }
        });

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    private void handlePartial(String json) {
        if (commandMode || transitioning || json == null) return;

        try {
            JSONObject object = new JSONObject(json);
            String partial = object.optString("partial", "");
            if (isWakePhrase(partial)) {
                activateFromWake(partial);
            }
        } catch (Exception ignored) {
        }
    }

    private void handleFinal(String json) {
        if (json == null || transitioning) return;

        try {
            JSONObject object = new JSONObject(json);
            String text = object.optString("text", "");

            if (commandMode) {
                if (!text.trim().isEmpty()) {
                    listening = false;
                    commandMode = false;
                    destroySpeechObjects();
                    handleCommand(text);
                }
            } else if (isWakePhrase(text)) {
                activateFromWake(text);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        handlePartial(hypothesis);
    }

    @Override
    public void onResult(String hypothesis) {
        // onResult is NOT the end of the microphone stream in Vosk.
        // It is an accepted speech segment, so never restart here.
        handleFinal(hypothesis);
    }

    @Override
    public void onFinalResult(String hypothesis) {
        listening = false;
        handleFinal(hypothesis);

        if (!transitioning && !commandMode && !stopping &&
                prefs.getBoolean(KEY_ENABLED, true)) {
            scheduleWakeRestart(150);
        }
    }

    @Override
    public void onError(Exception exception) {
        listening = false;

        if (stopping) return;

        destroySpeechObjects();

        if (commandMode) {
            commandMode = false;
            speakWhenReady("نتونستم صدات رو بشنوم. دوباره بگو.", this::returnToWakeMode);
        } else {
            updateNotification("مشکل موقت میکروفون؛ تلاش دوباره…");
            scheduleWakeRestart(900);
        }
    }

    @Override
    public void onTimeout() {
        listening = false;
        destroySpeechObjects();

        if (!stopping && prefs.getBoolean(KEY_ENABLED, true)) {
            if (commandMode) {
                commandMode = false;
                speakWhenReady("فرمانی نشنیدم.", this::returnToWakeMode);
            } else {
                scheduleWakeRestart(150);
            }
        }
    }

    private void startForegroundCompat(String content) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(content),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(content));
        }
    }

    private Notification buildNotification(String content) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("آریا فعال است")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(content));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "دستیار صوتی",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("سرویس همیشه‌فعال آریا");

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Aria::VoiceAssistant"
                );
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        destroySpeechObjects();

        if (model != null) {
            try {
                model.close();
            } catch (Exception ignored) {
            }
            model = null;
        }

        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
