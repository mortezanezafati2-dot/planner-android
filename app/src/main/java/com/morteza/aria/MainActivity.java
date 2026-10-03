package com.morteza.aria;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 42;
    private static final String PREFS = "aria_prefs";
    private static final String KEY_WAKE_PHRASE = "wake_phrase";
    private static final String KEY_ENABLED = "enabled";

    private SharedPreferences prefs;
    private EditText wakePhraseInput;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("دستیار صوتی آریا");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView description = new TextView(this);
        description.setText("صفحه را خاموش کن؛ آریا در پس‌زمینه منتظر عبارت بیدارباش می‌ماند.");
        description.setTextSize(16);
        description.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descLp = matchWrap();
        descLp.topMargin = dp(14);
        root.addView(description, descLp);

        TextView label = new TextView(this);
        label.setText("عبارت بیدارباش");
        label.setTextSize(17);
        LinearLayout.LayoutParams labelLp = matchWrap();
        labelLp.topMargin = dp(28);
        root.addView(label, labelLp);

        wakePhraseInput = new EditText(this);
        wakePhraseInput.setSingleLine(true);
        wakePhraseInput.setText(prefs.getString(KEY_WAKE_PHRASE, "آریا"));
        wakePhraseInput.setHint("مثلاً آریا");
        wakePhraseInput.setSelectAllOnFocus(false);
        root.addView(wakePhraseInput, matchWrap());

        Button startButton = new Button(this);
        startButton.setText("فعال کردن دستیار");
        LinearLayout.LayoutParams startLp = matchWrap();
        startLp.topMargin = dp(20);
        root.addView(startButton, startLp);

        Button stopButton = new Button(this);
        stopButton.setText("خاموش کردن دستیار");
        root.addView(stopButton, matchWrap());

        Button batteryButton = new Button(this);
        batteryButton.setText("باز کردن تنظیمات باتری");
        LinearLayout.LayoutParams batteryLp = matchWrap();
        batteryLp.topMargin = dp(12);
        root.addView(batteryButton, batteryLp);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.topMargin = dp(24);
        root.addView(statusText, statusLp);

        TextView note = new TextView(this);
        note.setText("نسخهٔ اول برای بیدارباش و مکالمهٔ صوتی است. برای پایداری بیشتر، برنامه را در تنظیمات باتری در حالت خواب قرار نده.");
        note.setTextSize(14);
        LinearLayout.LayoutParams noteLp = matchWrap();
        noteLp.topMargin = dp(18);
        root.addView(note, noteLp);

        setContentView(root);

        startButton.setOnClickListener(v -> enableAssistant());
        stopButton.setOnClickListener(v -> disableAssistant());
        batteryButton.setOnClickListener(v -> openBatterySettings());

        updateStatus();
    }

    private void enableAssistant() {
        String phrase = wakePhraseInput.getText().toString().trim();
        if (phrase.isEmpty()) {
            wakePhraseInput.setText("آریا");
            phrase = "آریا";
        }

        prefs.edit()
                .putString(KEY_WAKE_PHRASE, phrase)
                .putBoolean(KEY_ENABLED, true)
                .apply();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_PERMISSIONS
                );
            } else {
                requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_PERMISSIONS
                );
            }
            return;
        }

        startAssistantService();
    }

    private void startAssistantService() {
        Intent intent = new Intent(this, VoiceAssistantService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusText.setText("وضعیت: فعال — عبارت «" +
                prefs.getString(KEY_WAKE_PHRASE, "آریا") + "» را صدا بزن.");
    }

    private void disableAssistant() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply();
        stopService(new Intent(this, VoiceAssistantService.class));
        statusText.setText("وضعیت: خاموش");
    }

    private void updateStatus() {
        boolean enabled = prefs.getBoolean(KEY_ENABLED, false);
        if (enabled) {
            statusText.setText("وضعیت: فعال — آمادهٔ شنیدن «" +
                    prefs.getString(KEY_WAKE_PHRASE, "آریا") + "»");
        } else {
            statusText.setText("وضعیت: خاموش");
        }
    }

    private void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAssistantService();
        } else {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            statusText.setText("وضعیت: بدون اجازهٔ میکروفون، دستیار فعال نمی‌شود.");
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
