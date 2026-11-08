package com.homecam.blackbox;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.io.File;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            ListPreference storage = findPreference(Prefs.KEY_STORAGE);
            if (storage != null) {
                File sd = StorageHelper.sdRoot(requireContext());
                File in = StorageHelper.internalRoot(requireContext());
                String sdLabel = sd != null
                        ? "SD카드 (여유 " + StorageHelper.formatBytes(StorageHelper.usableBytes(sd)) + ")"
                        : "SD카드 (미장착)";
                storage.setEntries(new CharSequence[]{
                        "내장 저장소 (여유 " + StorageHelper.formatBytes(StorageHelper.usableBytes(in)) + ")",
                        sdLabel});
                storage.setEntryValues(new CharSequence[]{"internal", "sd"});
            }

            EditTextPreference maxGb = findPreference(Prefs.KEY_MAX_GB);
            if (maxGb != null) {
                maxGb.setOnBindEditTextListener(et ->
                        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
                maxGb.setSummaryProvider(p -> {
                    String v = ((EditTextPreference) p).getText();
                    return v == null || v.trim().isEmpty() || v.trim().equals("0")
                            ? "자동 (남은 공간 전체 사용)" : v + " GB";
                });
            }

            Preference battery = findPreference("battery_opt");
            if (battery != null) {
                battery.setOnPreferenceClickListener(p -> {
                    requestIgnoreBatteryOptimizations();
                    return true;
                });
            }

            updateEstimate();
        }

        // SharedPreferences 는 리스너를 약한 참조로 보관하므로 필드로 유지
        private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changeListener =
                (sp, key) -> {
                    if (getContext() == null) return;
                    updateEstimate();
                    if (key != null && !key.equals(Prefs.KEY_WANT_RECORDING)
                            && new Prefs(requireContext()).wantRecording()) {
                        Toast.makeText(requireContext(),
                                "변경 사항은 녹화를 중지 후 다시 시작하면 적용됩니다", Toast.LENGTH_SHORT).show();
                    }
                };

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(changeListener);
            updateEstimate();
        }

        @Override
        public void onPause() {
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(changeListener);
            super.onPause();
        }

        private void updateEstimate() {
            Preference est = findPreference("estimate");
            if (est == null || getContext() == null) return;
            Prefs p = new Prefs(requireContext());
            long cap = StorageHelper.capacityBytes(requireContext());
            double hours = cap / p.bytesPerSecond() / 3600.0;
            double gbPerDay = p.bytesPerSecond() * 86400 / 1e9;
            double hours32 = 29e9 / p.bytesPerSecond() / 3600.0; // 32GB 카드 실사용 약 29GB 기준
            est.setSummary("현재 용량(" + StorageHelper.formatBytes(cap) + ") 기준 " + StorageHelper.formatHours(hours)
                    + "\n하루 약 " + String.format(java.util.Locale.US, "%.1f", gbPerDay) + "GB 사용"
                    + "\n32GB 기준 " + StorageHelper.formatHours(hours32));
        }

        @SuppressLint("BatteryLife")
        private void requestIgnoreBatteryOptimizations() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            PowerManager pm = (PowerManager) requireContext().getSystemService(POWER_SERVICE);
            String pkg = requireContext().getPackageName();
            if (pm.isIgnoringBatteryOptimizations(pkg)) {
                Toast.makeText(requireContext(), "이미 배터리 최적화에서 제외되어 있습니다", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg)));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }
    }
}
