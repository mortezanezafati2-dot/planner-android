package com.morteza.aria;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceAssistantService extends Service implements RecognitionListener {

    private static final String PREFS = "aria_prefs";
    private static final String KEY_WAKE_PHRASE = "wake_phrase";
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

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wakePhrase = prefs.getString(KEY_WAKE_PHRASE, "آریا").trim();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

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

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);

            recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR");
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

            handler.postDelayed(this::startWakeListening, 700);
        } else {
            speak("موتور تشخیص گفتار روی این گوشی در دسترس نیست.", null);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wakePhrase = prefs.getString(KEY_WAKE_PHRASE, "آریا").trim();
        if (wakePhrase.isEmpty()) {
            wakePhrase = "آریا";
        }
        if (!listening && recognizer != null && !transitioning) {
            handler.postDelayed(this::startWakeListening, 250);
        }
        return START_STICKY;
    }

    private void startWakeListening() {
        if (recognizer == null || transitioning || listening) {
            return;
        }

        commandMode = false;
        listening = true;
        try {
            recognizer.cancel();
            recognizer.startListening(recognizerIntent);
        } catch (Exception e) {
            listening = false;
            scheduleWakeRestart(1000);
        }
    }

    private void scheduleWakeRestart(long delayMs) {
        if (transitioning) {
            return;
        }
        handler.postDelayed(() -> {
            if (!transitioning && !listening && recognizer != null) {
                startWakeListening();
            }
        }, delayMs);
    }

    private void activateFromWake() {
        long now = System.currentTimeMillis();
        if (transitioning || now - lastWakeAt < 1500) {
            return;
        }
        lastWakeAt = now;
        transitioning = true;
        listening = false;

        try {
            recognizer.cancel();
        } catch (Exception ignored) {
        }

        speak("بله؟", () -> {
            commandMode = true;
            transitioning = false;
            handler.postDelayed(this::startCommandListening, 250);
        });
    }

    private void startCommandListening() {
        if (recognizer == null || listening) {
            return;
        }

        wakePhrase = prefs.getString(KEY_WAKE_PHRASE, "آریا").trim();
        commandMode = true;
        listening = true;

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
            speak("دوباره بگو.", this::startWakeListening);
            return;
        }

        if (containsAny(text, "خاموش شو", "غیرفعال شو", "دیگه گوش نده", "بس کن")) {
            prefs.edit().putBoolean("enabled", false).apply();
            speak("باشه، خاموش شدم.", this::stopSelf);
            return;
        }

        if (containsAny(text, "ساعت چنده", "ساعت چند", "الان ساعت")) {
            String time = new SimpleDateFormat("HH:mm", new Locale("fa", "IR")).format(new Date());
            speak("الان ساعت " + time + " است.", this::startWakeListening);
            return;
        }

        if (containsAny(text, "سلام", "درود")) {
            speak("سلام. من آریا هستم و آماده‌ام.", this::startWakeListening);
            return;
        }

        if (containsAny(text, "اسمت چیه", "اسم تو چیه", "کی هستی")) {
            speak("اسم من آریاست. با گفتن «" + wakePhrase + "» صدایم کن.", this::startWakeListening);
            return;
        }

        if (containsAny(text, "ممنون", "مرسی", "تشکر")) {
            speak("خواهش می‌کنم.", this::startWakeListening);
            return;
        }

        speak("گفتی: " + spoken + ". در این نسخه، بخش گفت‌وگوی هوشمند هنوز به مغز آنلاین وصل نشده است.",
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
        String s = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ي', 'ی')
                .replace('ى', 'ی')
                .replace('ك', 'ک')
                .replace("ۀ", "ه")
                .replaceAll("[ًٌٍَُِّْـ]", "")
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    private boolean isWakePhrase(String text) {
        String normalized = normalize(text);
        String phrase = normalize(wakePhrase);
        if (phrase.isEmpty()) {
            return false;
        }
        return normalized.equals(phrase)
                || normalized.startsWith(phrase + " ")
                || normalized.startsWith("هی " + phrase + " ");
    }

    private void processRecognition(ArrayList<String> matches, boolean partial) {
        if (matches == null || matches.isEmpty()) {
            return;
        }

        if (!commandMode) {
            for (String candidate : matches) {
                if (isWakePhrase(candidate)) {
                    activateFromWake();
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
                handler.postDelayed(after, 500);
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

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("آریا فعال است")
                .setContentText("منتظر عبارت «" + wakePhrase + "»")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
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
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onReadyForSpeech(android.os.Bundle params) {
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onResults(android.os.Bundle results) {
        listening = false;
        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        processRecognition(matches, false);

        if (!transitioning && !commandMode) {
            scheduleWakeRestart(250);
        }
    }

    @Override
    public void onPartialResults(android.os.Bundle partialResults) {
        ArrayList<String> matches =
                partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        processRecognition(matches, true);
    }

    @Override
    public void onError(int error) {
        listening = false;

        if (transitioning) {
            return;
        }

        if (commandMode) {
            commandMode = false;
            speak("نتونستم بشنوم. دوباره بگو.", this::startWakeListening);
        } else {
            scheduleWakeRestart(700);
        }
    }

    @Override
    public void onEvent(int eventType, android.os.Bundle params) {
    }

    @Override
    public void onDestroy() {
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
