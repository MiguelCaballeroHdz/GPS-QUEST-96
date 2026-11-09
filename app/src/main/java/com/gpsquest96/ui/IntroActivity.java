package com.gpsquest96.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.Spinner;
import android.widget.Toast;

import com.gpsquest96.R;
import com.gpsquest96.emulator.SaveManager;
import com.gpsquest96.utils.LocaleHelper;

/**
 * Intro del jugador: género → nombre → edad. Guarda el perfil y va al mapa.
 */
public class IntroActivity extends Activity {

    private Spinner spinnerGender;
    private EditText editName;
    private NumberPicker pickerAge;
    private Button btnConfirm;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro);

        spinnerGender = findViewById(R.id.spinner_gender);
        editName = findViewById(R.id.edit_name);
        pickerAge = findViewById(R.id.picker_age);
        btnConfirm = findViewById(R.id.btn_confirm);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        getString(R.string.intro_boy),
                        getString(R.string.intro_girl)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGender.setAdapter(adapter);

        pickerAge.setMinValue(5);
        pickerAge.setMaxValue(99);
        pickerAge.setValue(10);
        pickerAge.setWrapSelectorWheel(false);

        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = editName.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(IntroActivity.this,
                            getString(R.string.intro_name_empty),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String gender = (spinnerGender.getSelectedItemPosition() == 1)
                        ? "GIRL" : "BOY";
                new SaveManager(IntroActivity.this).savePlayer(
                        name, pickerAge.getValue(), gender);

                Intent intent = new Intent(IntroActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            }
        });
    }
}
