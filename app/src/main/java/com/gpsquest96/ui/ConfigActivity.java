package com.gpsquest96.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.gpsquest96.R;
import com.gpsquest96.emulator.SaveManager;
import com.gpsquest96.utils.LocaleHelper;

/**
 * Actividad de ajustes generales.
 * Idioma (10) + modo GPS/Sofá + clima + vibración + ruta de ROMs.
 * Todo persiste en SharedPreferences; el modo GPS no se altera aquí,
 * solo se guarda la preferencia que MainActivity aplica al abrir.
 */
public class ConfigActivity extends Activity {

    private static final String PREFS = "gpsquest96_prefs";
    private static final String KEY_MODE = "control_mode";
    private static final String KEY_WEATHER = "weather_enabled";
    private static final String KEY_VIBRATION = "vibration_enabled";

    private static final String[] LANG_CODES =
            {"es", "en", "fr", "de", "pt", "it", "ja", "ko", "zh", "ru"};
    private static final String[] LANG_NAMES =
            {"Español", "English", "Français", "Deutsch", "Português",
             "Italiano", "日本語", "한국어", "中文", "Русский"};

    private Spinner spinnerLanguage;
    private Spinner spinnerMode;
    private Switch switchWeather;
    private Switch switchVibration;
    private TextView tvRomPath;
    private Button btnStart;

    private String selectedLanguage = "es";
    private String selectedMode = "gps";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        initViews();
        setupLanguageSpinner();
        setupModeSpinner();
        setupSwitches();
        setupRomPath();
        setupButton();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void initViews() {
        spinnerLanguage = findViewById(R.id.spinner_language);
        spinnerMode = findViewById(R.id.spinner_mode);
        switchWeather = findViewById(R.id.switch_weather);
        switchVibration = findViewById(R.id.switch_vibration);
        tvRomPath = findViewById(R.id.tv_rom_path);
        btnStart = findViewById(R.id.btn_start);
    }

    private void setupLanguageSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                LANG_NAMES
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(adapter);

        selectedLanguage = LocaleHelper.getLanguage(this);
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equals(selectedLanguage)) {
                spinnerLanguage.setSelection(i);
                break;
            }
        }

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedLanguage = LANG_CODES[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupModeSpinner() {
        String[] modes = {
                getString(R.string.mode_gps_real),
                getString(R.string.mode_sofa)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                modes
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(adapter);

        selectedMode = prefs().getString(KEY_MODE, "gps");
        spinnerMode.setSelection("sofa".equals(selectedMode) ? 1 : 0);

        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedMode = (position == 0) ? "gps" : "sofa";
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupSwitches() {
        switchWeather.setChecked(prefs().getBoolean(KEY_WEATHER, true));
        switchVibration.setChecked(prefs().getBoolean(KEY_VIBRATION, true));
    }

    private void setupRomPath() {
        String romPath = new SaveManager(this).getRomPath();
        tvRomPath.setText(romPath != null ? romPath : getString(R.string.config_rom_empty));
    }

    private void setupButton() {
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Guardar idioma y preferencias
                LocaleHelper.setLocale(ConfigActivity.this, selectedLanguage);
                prefs().edit()
                        .putString(KEY_MODE, selectedMode)
                        .putBoolean(KEY_WEATHER, switchWeather.isChecked())
                        .putBoolean(KEY_VIBRATION, switchVibration.isChecked())
                        .apply();

                // Navegar a MainActivity (reutiliza la instancia: sin mapas duplicados)
                Intent intent = new Intent(ConfigActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            }
        });
    }
}
