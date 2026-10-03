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
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.text.SimpleDateFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class VoiceAssistantService extends Service implements RecognitionListener {

    private static final String PREFS = "aria_prefs";
    private static final String KEY_WAKE_PHRASE = "wake_phrase";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL_ID = "aria_voice";
    private static final int NOTIFICATION_ID = 7101;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean listening = false;
    private boolean commandMode = false;
    private boolean transitioning = false;
    private long lastWakeAt = 0L;
    private String wakePhrase = "آریا";
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!transitioning && prefs != null && prefs.getBoolean(KEY_ENABLED, false)
                    && recognizer != null && !listening) {
                startWakeListening();
            }
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wakePhrase = safeWakePhrase();

        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification("در حال آماده‌سازی میکروفون…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی میکروفون…"));
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

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("موتور تشخیص گفتار پیدا نشد.");
            speak("موتور تشخیص گفتار روی این گوشی در دسترس نیست.", null);
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(this);

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

        // Do NOT force offline recognition: Persian offline packs are not guaranteed.
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);

        if (Build.VERSION.SDK_INT >= 23) {
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    1200L
            );
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    700L
            );
            recognizerIntent.putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    700L
            );
        }

        updateNotification("منتظر «" + wakePhrase + "»");
        handler.postDelayed(this::startWakeListening, 500);
        handler.postDelayed(watchdog, 5000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wakePhrase = safeWakePhrase();
        if (!listening && recognizer != null && !transitioning) {
            handler.postDelayed(this::startWakeListening, 200);
        }
        return START_STICKY;
    }

    private String safeWakePhrase() {
        String value = prefs == null ? "آریا" : prefs.getString(KEY_WAKE_PHRASE, "آریا");
        value = value == null ? "" : value.trim();
        return value.isEmpty() ? "آریا" : value;
    }

    private void startWakeListening() {
        if (recognizer == null || transitioning || listening
                || !prefs.getBoolean(KEY_ENABLED, true)) {
            return;
        }

        commandMode = false;
        listening = true;
        updateNotification("گوش می‌دهم برای «" + wakePhrase + "»");

        try {
            recognizer.cancel();
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            listening = false;
            updateNotification("خطای شروع میکروفون؛ تلاش مجدد…");
            scheduleWakeRestart(1200);
        }
    }

    private void scheduleWakeRestart(long delayMs) {
        if (transitioning || !prefs.getBoolean(KEY_ENABLED, true)) {
            return;
        }
        handler.postDelayed(() -> {
            if (!transitioning && !listening && recognizer != null) {
                startWakeListening();
            }
        }, delayMs);
    }

    private void activateFromWake(String spoken) {
        long now = System.currentTimeMillis();
        if (transitioning || now - lastWakeAt < 1200) {
            return;
        }
        lastWakeAt = now;
        transitioning = true;
        listening = false;

        try {
            recognizer.cancel();
        } catch (Exception ignored) {
        }

        String normalized = normalize(spoken);
        String phrase = normalize(wakePhrase);
        String remaining = normalized.startsWith(phrase)
                ? normalized.substring(phrase.length()).trim()
                : "";

        if (!remaining.isEmpty()) {
            commandMode = true;
            transitioning = false;
            handleCommand(spoken.substring(
                    Math.min(spoken.length(), wakePhrase.length())
            ).trim());
            return;
        }

        updateNotification("آریا فعال شد");
        speak("بله؟", () -> {
            commandMode = true;
            transitioning = false;
            handler.postDelayed(this::startCommandListening, 180);
        });
    }

    private void startCommandListening() {
        if (recognizer == null || listening || !prefs.getBoolean(KEY_ENABLED, true)) {
            return;
        }

        wakePhrase = safeWakePhrase();
        commandMode = true;
        listening = true;
        updateNotification("منتظر فرمان شما هستم");

        try {
            recognizer.cancel();
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            listening = false;
            commandMode = false;
            speak("دوباره بگو.", this::startWakeListening);
        }
    }

    private void handleCommand(String spoken) {
        String text = normalize(spoken);
        if (text.isEmpty()) {
            commandMode = false;
            speak("دوباره بگو.", this::startWakeListening);
            return;
        }

        if (containsAny(text, "خاموش شو", "غیرفعال شو", "دیگه گوش نده", "بس کن")) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            speak("باشه، خاموش شدم.", this::stopSelf);
            return;
        }

        if (containsAny(text, "ساعت چنده", "ساعت چند", "الان ساعت")) {
            String time = new SimpleDateFormat("HH:mm", new Locale("fa", "IR"))
                    .format(new Date());
            commandMode = false;
            speak("الان ساعت " + time + " است.", this::startWakeListening);
            return;
        }

        if (containsAny(text, "سلام", "درود")) {
            commandMode = false;
            speak("سلام. من آریا هستم و آماده‌ام.", this::startWakeListening);
            return;
        }

        if (containsAny(text, "اسمت چیه", "اسم تو چیه", "کی هستی")) {
            commandMode = false;
            speak("اسم من آریاست. با گفتن «" + wakePhrase + "» صدایم کن.",
                    this::startWakeListening);
            return;
        }

        if (containsAny(text, "ممنون", "مرسی", "تشکر")) {
            commandMode = false;
            speak("خواهش می‌کنم.", this::startWakeListening);
            return;
        }

        commandMode = false;
        speak("گفتی: " + spoken +
                ". در این نسخه، بخش گفت‌وگوی هوشمند هنوز به مغز آنلاین وصل نشده است.",
                this::startWakeListening);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(normalize(value))) {
                return true;
            }
        }
        return false;
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

    private boolean isWakePhrase(String text) {
        String normalized = normalize(text);
        String phrase = normalize(wakePhrase);

        if (phrase.isEmpty()) {
            return false;
        }

        if (normalized.equals(phrase)
                || normalized.startsWith(phrase + " ")
                || normalized.contains(" " + phrase + " ")) {
            return true;
        }

        // Common Persian speech-recognition variant: «اریا» instead of «آریا».
        String[] tokens = normalized.split(" ");
        for (String token : tokens) {
            if (levenshtein(token, phrase) <= 1) {
                return true;
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

    private void processRecognition(ArrayList<String> matches, boolean partial) {
        if (matches == null || matches.isEmpty() || transitioning) {
            return;
        }

        if (!commandMode) {
            for (String candidate : matches) {
                if (isWakePhrase(candidate)) {
                    activateFromWake(candidate);
                    return;
                }
            }
        } else if (!partial) {
            handleCommand(matches.get(0));
        }
    }

    private void speak(String text, Runnable after) {
        if (tts == null || !ttsReady) {
            if (after != null) {
                handler.postDelayed(after, 700);
            }
            return;
        }

        String utteranceId = UUID.randomUUID().toString();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                if (utteranceId.equals(id) && after != null) {
                    handler.post(after);
                }
            }

            @Override
            public void onError(String id) {
                if (utteranceId.equals(id) && after != null) {
                    handler.post(after);
                }
            }
        });

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
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
        NotificationManager manager = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);
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
            channel.setDescription("سرویس شنیدن عبارت بیدارباش آریا");
            NotificationManager manager = getSystemService(NotificationManager.class);
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

    @Override public void onReadyForSpeech(android.os.Bundle params) {
        updateNotification("میکروفون آماده است؛ منتظر «" + wakePhrase + "»");
    }

    @Override public void onBeginningOfSpeech() {
        updateNotification("صدایتان را می‌شنوم…");
    }

    @Override public void onRmsChanged(float rmsdB) {
    }

    @Override public void onBufferReceived(byte[] buffer) {
    }

    @Override public void onEndOfSpeech() {
    }

    @Override public void onResults(android.os.Bundle results) {
        listening = false;
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        processRecognition(matches, false);

        if (!transitioning && !commandMode) {
            scheduleWakeRestart(180);
        }
    }

    @Override public void onPartialResults(android.os.Bundle partialResults) {
        ArrayList<String> matches =
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        processRecognition(matches, true);
    }

    @Override public void onError(int error) {
        listening = false;

        if (transitioning) {
            return;
        }

        updateNotification("گوش دادن دوباره راه‌اندازی می‌شود…");

        if (commandMode) {
            commandMode = false;
            speak("نتونستم بشنوم. دوباره بگو.", this::startWakeListening);
        } else {
            scheduleWakeRestart(500);
        }
    }

    @Override public void onEvent(int eventType, android.os.Bundle params) {
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
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

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
