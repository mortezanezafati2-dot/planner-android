package com.morteza.aria;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class VoiceAssistantService extends Service {

    private static final String PREFS = "aria_prefs";
    private static final String KEY_WAKE_PHRASE = "wake_phrase";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL_ID = "aria_voice";
    private static final int NOTIFICATION_ID = 7101;
    private static final float SAMPLE_RATE = 16000.0f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong sessionCounter = new AtomicLong(0L);
    private final Object speechLock = new Object();

    private SharedPreferences prefs;
    private Model model;
    private SpeechService speechService;
    private Recognizer recognizer;
    private TextToSpeech tts;
    private Runnable pendingAfterTts;

    private volatile boolean ttsReady = false;
    private volatile boolean modelReady = false;
    private volatile boolean listening = false;
    private volatile boolean commandMode = false;
    private volatile boolean transitioning = false;
    private volatile boolean destroyed = false;

    private String wakePhrase = "آریا";
    private long lastWakeAt = 0L;
    private PowerManager.WakeLock wakeLock;

    private enum Mode {
        WAKE,
        COMMAND
    }

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
        initTts();
        updateNotification("در حال بارگذاری موتور فارسی…");

        StorageService.unpack(
                this,
                "model-fa",
                "model-fa",
                loadedModel -> {
                    if (destroyed) {
                        try {
                            loadedModel.close();
                        } catch (Exception ignored) {
                        }
                        return;
                    }

                    model = loadedModel;
                    modelReady = true;
                    updateNotification("مدل فارسی آماده است");
                    if (prefs.getBoolean(KEY_ENABLED, true)) {
                        startMode(Mode.WAKE, 250);
                    }
                },
                exception -> {
                    modelReady = false;
                    updateNotification("خطای بارگذاری مدل فارسی");
                    speak("مدل فارسی آماده نشد. برنامه را دوباره باز کن.", null);
                }
        );
    }

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false;
                return;
            }

            try {
                int result = tts.setLanguage(new Locale("fa", "IR"));
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts.setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                    );
                }

                tts.setSpeechRate(0.95f);
                ttsReady = true;
            } catch (Exception ignored) {
                ttsReady = false;
            }
        });

        if (tts != null) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    finishTtsCallback(utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    finishTtsCallback(utteranceId);
                }

                private void finishTtsCallback(String utteranceId) {
                    mainHandler.post(() -> {
                        Runnable next = null;
                        synchronized (speechLock) {
                            if (pendingAfterTts != null) {
                                next = pendingAfterTts;
                                pendingAfterTts = null;
                            }
                        }
                        if (next != null && !destroyed) {
                            next.run();
                        }
                    });
                }
            });
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wakePhrase = safeWakePhrase();

        if (modelReady && prefs.getBoolean(KEY_ENABLED, true)
                && !listening && !transitioning) {
            startMode(Mode.WAKE, 150);
        }

        return START_STICKY;
    }

    private boolean isStopping() {
        return isDestroyed() || !prefs.getBoolean(KEY_ENABLED, true) && !isServiceExplicitlyRunning();
    }

    private boolean isDestroyed() {
        return false;
    }

    private boolean isServiceExplicitlyRunning() {
        return true;
    }

    private String safeWakePhrase() {
        String value = prefs.getString(KEY_WAKE_PHRASE, "آریا");
        value = value == null ? "" : value.trim();
        return value.isEmpty() ? "آریا" : value;
    }

    private void startMode(Mode mode, long delayMs) {
        mainHandler.postDelayed(() -> {
            if (!modelReady || model == null
                    || !prefs.getBoolean(KEY_ENABLED, true)
                    || transitioning || listening) {
                return;
            }

            transitioning = true;
            final long sessionId = sessionCounter.incrementAndGet();

            engineExecutor.execute(() -> {
                stopEngineInternal();

                if (!prefs.getBoolean(KEY_ENABLED, true)) {
                    mainHandler.post(() -> transitioning = false);
                    return;
                }

                try {
                    Recognizer nextRecognizer;

                    if (mode == Mode.WAKE) {
                        nextRecognizer = new Recognizer(
                                model,
                                SAMPLE_RATE,
                                "[\"آریا\",\"اریا\",\"هریا\",\"[unk]\"]"
                        );
                    } else {
                        nextRecognizer = new Recognizer(model, SAMPLE_RATE);
                    }

                    SpeechService nextSpeechService =
                            new SpeechService(nextRecognizer, SAMPLE_RATE);

                    synchronized (speechLock) {
                        recognizer = nextRecognizer;
                        speechService = nextSpeechService;
                        commandMode = mode == Mode.COMMAND;
                    }

                    RecognitionListener listener = new SessionListener(sessionId, mode);
                    boolean started = nextSpeechService.startListening(listener);

                    mainHandler.post(() -> {
                        if (sessionId != sessionCounter.get()) {
                            return;
                        }

                        listening = started;
                        transitioning = false;

                        if (started) {
                            if (mode == Mode.WAKE) {
                                wakePhrase = safeWakePhrase();
                                updateNotification(
                                        "همیشه‌فعال — منتظر «" + wakePhrase + "»"
                                );
                            } else {
                                updateNotification("منتظر فرمان شما هستم");
                            }
                        } else {
                            commandMode = false;
                            updateNotification("خطای شروع شنیدن؛ تلاش مجدد…");
                            scheduleModeRestart(1000);
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (sessionId != sessionCounter.get()) {
                            return;
                        }
                        listening = false;
                        commandMode = false;
                        transitioning = false;
                        updateNotification("خطای میکروفون؛ تلاش مجدد…");
                        scheduleModeRestart(1200);
                    });
                }
            });
        }, delayMs);
    }

    private void scheduleModeRestart(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (modelReady && prefs.getBoolean(KEY_ENABLED, true)
                    && !listening && !transitioning) {
                startMode(Mode.WAKE, 0);
            }
        }, delayMs);
    }

    private void activateFromWake(String spoken) {
        long now = System.currentTimeMillis();

        if (transitioning || now - lastWakeAt < 1500 || !isWakePhrase(spoken)) {
            return;
        }

        lastWakeAt = now;
        transitioning = true;
        listening = false;
        commandMode = false;
        final long ignoredSession = sessionCounter.incrementAndGet();

        updateNotification("آریا فعال شد");
        shutdownSpeechEngineAsync(() -> {
            if (!prefs.getBoolean(KEY_ENABLED, true)) {
                transitioning = false;
                return;
            }

            speak("بله؟", () -> {
                if (!prefs.getBoolean(KEY_ENABLED, true)) {
                    transitioning = false;
                    return;
                }

                transitioning = true;
                startMode(Mode.COMMAND, 0);
            });
        });
    }

    private void handleCommand(String spoken) {
        String text = normalize(spoken);

        if (text.isEmpty()) {
            returnToWakeWithSpeech("دوباره بگو.");
            return;
        }

        if (containsAny(text, "خاموش شو", "غیرفعال شو", "دیگه گوش نده", "بس کن")) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            shutdownSpeechEngineAsync(() -> speak("باشه، خاموش شدم.", this::stopSelf));
            return;
        }

        if (containsAny(text, "ساعت چنده", "ساعت چند", "الان ساعت", "زمان چنده")) {
            String time = new SimpleDateFormat("HH:mm", new Locale("fa", "IR"))
                    .format(new Date());

            returnToWakeWithSpeech("الان ساعت " + time + " است.");
            return;
        }

        if (containsAny(text, "سلام", "درود")) {
            returnToWakeWithSpeech("سلام. من آریا هستم و آماده‌ام.");
            return;
        }

        if (containsAny(text, "اسمت چیه", "اسم تو چیه", "کی هستی")) {
            returnToWakeWithSpeech(
                    "اسم من آریاست. با گفتن «" + safeWakePhrase() + "» صدایم کن."
            );
            return;
        }

        if (containsAny(text, "ممنون", "مرسی", "تشکر")) {
            returnToWakeWithSpeech("خواهش می‌کنم.");
            return;
        }

        returnToWakeWithSpeech(
                "گفتی: " + spoken
                        + ". در این نسخه هنوز بخش هوش گفت‌وگویی به مدل آنلاین وصل نشده است."
        );
    }

    private void returnToWakeWithSpeech(String response) {
        commandMode = false;
        transitioning = true;

        shutdownSpeechEngineAsync(() -> {
            if (!prefs.getBoolean(KEY_ENABLED, true)) {
                transitioning = false;
                return;
            }

            speak(response, () -> {
                if (prefs.getBoolean(KEY_ENABLED, true)) {
                    transitioning = true;
                    startMode(Mode.WAKE, 120);
                } else {
                    transitioning = false;
                }
            });
        });
    }

    private void shutdownSpeechEngineAsync(Runnable after) {
        listening = false;
        final long newSession = sessionCounter.incrementAndGet();

        engineExecutor.execute(() -> {
            stopEngineInternal();
            mainHandler.post(() -> {
                if (newSession != sessionCounter.get()) {
                    return;
                }
                if (after != null && !destroyed && prefs.getBoolean(KEY_ENABLED, true)) {
                    after.run();
                }
            });
        });
    }

    private void stopEngineInternal() {
        synchronized (speechLock) {
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
        }
    }

    private boolean isWakePhrase(String spoken) {
        String text = normalize(spoken);
        String phrase = normalize(wakePhrase);

        if (phrase.isEmpty() || text.isEmpty()) {
            return false;
        }

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
                mainHandler.postDelayed(after, 500);
            }
            return;
        }

        final String utteranceId = UUID.randomUUID().toString();

        synchronized (speechLock) {
            pendingAfterTts = after;
        }

        try {
            int result = tts.speak(
                    text == null ? "" : text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    utteranceId
            );

            if (result == TextToSpeech.ERROR) {
                Runnable next;
                synchronized (speechLock) {
                    next = pendingAfterTts;
                    pendingAfterTts = null;
                }
                if (next != null) {
                    mainHandler.post(next);
                }
            }
        } catch (Exception e) {
            Runnable next;
            synchronized (speechLock) {
                next = pendingAfterTts;
                pendingAfterTts = null;
            }
            if (next != null) {
                mainHandler.post(next);
            }
        }
    }

    private void handlePartial(String json, long sessionId, Mode mode) {
        if (mode != Mode.WAKE || commandMode
                || transitioning || sessionId != sessionCounter.get()) {
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

    private void handleResult(String json, long sessionId, Mode mode) {
        if (json == null || sessionId != sessionCounter.get()) {
            return;
        }

        try {
            JSONObject object = new JSONObject(json);
            String text = object.optString("text", "");

            if (mode == Mode.WAKE) {
                if (!transitioning && !commandMode && isWakePhrase(text)) {
                    activateFromWake(text);
                }
            } else if (!text.trim().isEmpty()) {
                mainHandler.post(() -> handleCommand(text));
            }
        } catch (Exception ignored) {
        }
    }

    private class SessionListener implements RecognitionListener {
        private final long sessionId;
        private final Mode mode;

        SessionListener(long sessionId, Mode mode) {
            this.sessionId = sessionId;
            this.mode = mode;
        }

        @Override
        public void onPartialResult(String hypothesis) {
            handlePartial(hypothesis, sessionId, mode);
        }

        @Override
        public void onResult(String hypothesis) {
            listening = false;
            handleResult(hypothesis, sessionId, mode);
        }

        @Override
        public void onFinalResult(String hypothesis) {
            listening = false;
            handleResult(hypothesis, sessionId, mode);
        }

        @Override
        public void onError(Exception exception) {
            if (sessionId != sessionCounter.get()) {
                return;
            }

            listening = false;

            mainHandler.post(() -> {
                if (sessionId != sessionCounter.get() || transitioning) {
                    return;
                }

                if (mode == Mode.COMMAND) {
                    returnToWakeWithSpeech("نتونستم بشنوم. دوباره بگو.");
                } else {
                    updateNotification("گوش دادن ادامه پیدا می‌کند…");
                    startMode(Mode.WAKE, 500);
                }
            });
        }

        @Override
        public void onTimeout() {
            if (sessionId != sessionCounter.get()) {
                return;
            }

            listening = false;

            mainHandler.post(() -> {
                if (sessionId != sessionCounter.get() || transitioning) {
                    return;
                }

                startMode(Mode.WAKE, 250);
            });
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

                if (!wakeLock.isHeld()) {
                    wakeLock.acquire();
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        sessionCounter.incrementAndGet();

        engineExecutor.execute(this::stopEngineInternal);
        engineExecutor.shutdown();

        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
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
