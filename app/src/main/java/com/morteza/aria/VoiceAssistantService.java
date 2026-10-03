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
import org.vosk.LibVosk;
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

    private boolean ttsReady = false;
    private boolean modelReady = false;
    private boolean listening = false;
    private boolean commandMode = false;
    private boolean transitioning = false;
    private boolean wakeTriggered = false;

    private String wakePhrase = "آریا";
    private long lastWakeAt = 0L;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wakePhrase = safeWakePhrase();

        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification("در حال آماده‌سازی…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی…"));
        }

        acquireWakeLock();

        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                int result = tts.setLanguage(new Locale("fa", "IR"));
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(0.95f);
            }
        });

        updateNotification("در حال بارگذاری موتور فارسی…");

        StorageService.unpack(
                this,
                "model-fa",
                "model-fa",
                loadedModel -> {
                    model = loadedModel;
                    modelReady = true;
                    updateNotification("مدل فارسی آماده است");
                    handler.postDelayed(this::startWakeMode, 250);
                },
                exception -> {
                    updateNotification("خطای بارگذاری مدل فارسی");
                    speak("مدل فارسی آماده نشد. برنامه را دوباره باز کن.", null);
                }
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wakePhrase = safeWakePhrase();

        if (modelReady && !listening && !transitioning
                && prefs.getBoolean(KEY_ENABLED, true)) {
            handler.postDelayed(this::startWakeMode, 150);
        }
        return START_STICKY;
    }

    private String safeWakePhrase() {
        String value = prefs.getString(KEY_WAKE_PHRASE, "آریا");
        value = value == null ? "" : value.trim();
        return value.isEmpty() ? "آریا" : value;
    }

    private void startWakeMode() {
        if (!modelReady || model == null || listening || transitioning
                || !prefs.getBoolean(KEY_ENABLED, true)) {
            return;
        }

        commandMode = false;
        wakeTriggered = false;
        wakePhrase = safeWakePhrase();

        try {
            if (speechService != null) {
                speechService.cancel();
                speechService.shutdown();
                speechService = null;
            }

            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }

            recognizer = new Recognizer(
                    model,
                    SAMPLE_RATE,
                    "[\"آریا\", \"اریا\", \"هریا\", \"[unk]\"]"
            );

            speechService = new SpeechService(recognizer, SAMPLE_RATE);
            listening = speechService.startListening(this);

            updateNotification("همیشه‌فعال — منتظر «" + wakePhrase + "»");
        } catch (Exception e) {
            listening = false;
            updateNotification("خطای میکروفون؛ تلاش مجدد…");
            scheduleRestart(1500);
        }
    }

    private void activateFromWake(String spoken) {
        long now = System.currentTimeMillis();

        if (wakeTriggered || transitioning || now - lastWakeAt < 1500) {
            return;
        }

        if (!isWakePhrase(spoken)) {
            return;
        }

        lastWakeAt = now;
        wakeTriggered = true;
        transitioning = true;
        listening = false;

        if (speechService != null) {
            speechService.cancel();
        }

        updateNotification("آریا فعال شد");

        speak("بله؟", () -> {
            if (!prefs.getBoolean(KEY_ENABLED, true)) {
                return;
            }

            commandMode = true;
            transitioning = false;

            try {
                recognizer.setGrammar("[]");
                recognizer.reset();
            } catch (Exception ignored) {
            }

            startListeningAgain("منتظر فرمان شما هستم");
        });
    }

    private void startListeningAgain(String notification) {
        if (speechService == null || listening
                || !prefs.getBoolean(KEY_ENABLED, true)) {
            return;
        }

        try {
            listening = speechService.startListening(this);
            updateNotification(notification);
        } catch (Exception e) {
            listening = false;
            commandMode = false;
            scheduleRestart(1000);
        }
    }

    private void scheduleRestart(long delayMs) {
        handler.postDelayed(() -> {
            if (modelReady && !transitioning && !listening
                    && prefs.getBoolean(KEY_ENABLED, true)) {
                startWakeMode();
            }
        }, delayMs);
    }

    private String remainingAfterWake(String spoken) {
        String normalized = normalize(spoken);
        String phrase = normalize(wakePhrase);

        if (normalized.startsWith(phrase)) {
            return normalized.substring(phrase.length()).trim();
        }
        return "";
    }

    private void handleCommand(String spoken) {
        String text = normalize(spoken);

        if (text.isEmpty()) {
            commandMode = false;
            speak("دوباره بگو.", this::returnToWakeMode);
            return;
        }

        if (containsAny(text, "خاموش شو", "غیرفعال شو", "دیگه گوش نده", "بس کن")) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            speak("باشه، خاموش شدم.", this::stopSelf);
            return;
        }

        if (containsAny(text, "ساعت چنده", "ساعت چند", "الان ساعت", "زمان چنده")) {
            String time = new SimpleDateFormat("HH:mm", new Locale("fa", "IR"))
                    .format(new Date());
            commandMode = false;
            speak("الان ساعت " + time + " است.", this::returnToWakeMode);
            return;
        }

        if (containsAny(text, "سلام", "درود")) {
            commandMode = false;
            speak("سلام. من آریا هستم و آماده‌ام.", this::returnToWakeMode);
            return;
        }

        if (containsAny(text, "اسمت چیه", "اسم تو چیه", "کی هستی")) {
            commandMode = false;
            speak("اسم من آریاست. با گفتن «" + wakePhrase + "» صدایم کن.",
                    this::returnToWakeMode);
            return;
        }

        if (containsAny(text, "ممنون", "مرسی", "تشکر")) {
            commandMode = false;
            speak("خواهش می‌کنم.", this::returnToWakeMode);
            return;
        }

        commandMode = false;
        speak(
                "گفتی: " + spoken +
                        ". در این نسخه هنوز بخش هوش گفت‌وگویی به مدل آنلاین وصل نشده است.",
                this::returnToWakeMode
        );
    }

    private void returnToWakeMode() {
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            return;
        }

        transitioning = false;
        commandMode = false;
        listening = false;

        if (speechService != null) {
            speechService.cancel();
        }

        handler.postDelayed(this::startWakeMode, 200);
    }

    private boolean isWakePhrase(String spoken) {
        String text = normalize(spoken);
        String phrase = normalize(wakePhrase);

        if (text.equals(phrase)
                || text.contains(" " + phrase + " ")
                || text.startsWith(phrase + " ")
                || text.endsWith(" " + phrase)) {
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

        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }

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
        if (input == null) {
            return "";
        }

        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ي', 'ی')
                .replace('ى', 'ی')
                .replace('ك', 'ک')
                .replace("ۀ", "ه")
                .replaceAll("[ًٌٍَُِّْـ]", "")
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(normalize(value))) {
                return true;
            }
        }
        return false;
    }

    private void speak(String text, Runnable after) {
        if (tts == null || !ttsReady) {
            if (after != null) {
                handler.postDelayed(after, 700);
            }
            return;
        }

        final String id = UUID.randomUUID().toString();

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }

            @Override
            public void onDone(String utteranceId) {
                if (id.equals(utteranceId) && after != null) {
                    handler.post(after);
                }
            }

            @Override
            public void onError(String utteranceId) {
                if (id.equals(utteranceId) && after != null) {
                    handler.post(after);
                }
            }
        });

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    private void handlePartial(String json) {
        if (commandMode || transitioning || json == null) {
            return;
        }

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
        if (json == null || transitioning) {
            return;
        }

        try {
            JSONObject object = new JSONObject(json);
            String text = object.optString("text", "");

            if (!commandMode) {
                if (isWakePhrase(text)) {
                    activateFromWake(text);
                }
            } else if (!text.trim().isEmpty()) {
                commandMode = false;
                handleCommand(text);
            }
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String content) {
        Intent openIntent = new Intent(this, MainActivity.class);

        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                openIntent,
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

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
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
    public void onPartialResult(String hypothesis) {
        handlePartial(hypothesis);
    }

    @Override
    public void onResult(String hypothesis) {
        listening = false;
        handleFinal(hypothesis);

        if (!transitioning && !commandMode && !wakeTriggered) {
            scheduleRestart(250);
        }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        listening = false;

        if (!transitioning && commandMode) {
            handleFinal(hypothesis);
        }
    }

    @Override
    public void onError(Exception exception) {
        listening = false;

        if (transitioning) {
            return;
        }

        updateNotification("گوش دادن ادامه پیدا می‌کند…");

        if (commandMode) {
            commandMode = false;
            speak("نتونستم بشنوم. دوباره بگو.", this::returnToWakeMode);
        } else {
            scheduleRestart(700);
        }
    }

    @Override
    public void onTimeout() {
        listening = false;

        if (!transitioning && !commandMode) {
            scheduleRestart(250);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (speechService != null) {
            try {
                speechService.stop();
                speechService.shutdown();
            } catch (Exception ignored) {
            }
        }

        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Exception ignored) {
            }
        }

        if (model != null) {
            try {
                model.close();
            } catch (Exception ignored) {
            }
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
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
