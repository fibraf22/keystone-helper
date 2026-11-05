package com.example.keystonehelper;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements SensorEventListener {

    private static final String FOCUS_PACKAGE = "com.hysd.vafocus";
    private static final String FOCUS_ACTIVITY = "com.hysd.vafocus.MainActivity";
    private static final String KEYSTONE_PROP = "persist.sys.keystone.vertical";

    // Filtro passa-basso per isolare la gravita' dal rumore (0..1, piu' alto = piu' lento/stabile)
    private static final float ALPHA = 0.85f;
    // Invia un nuovo valore solo se l'angolo cambia di almeno questi gradi
    private static final int MIN_DELTA_DEGREES = 1;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private TextView tvStatus;
    private TextView tvAngle;
    private Button btnKeystone;

    private boolean keystoneOn = false;
    private boolean firstSample = true;
    private float gx, gy, gz;
    private int lastSentAngle = Integer.MIN_VALUE;

    // Tutto cio' che riguarda la shell di root gira su questo unico thread, in ordine
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Process suProcess;          // usato solo dal thread dell'executor
    private DataOutputStream suStream;  // usato solo dal thread dell'executor

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#101418"));
        root.setPadding(dp(48), dp(32), dp(48), dp(32));

        TextView title = new TextView(this);
        title.setText("KEYSTONE HELPER");
        title.setTextSize(40);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(0, 0, 0, 24));

        Button btnFocus = makeButton("AVVIA AUTOFOCUS NATIVO");
        btnFocus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchAutofocus();
            }
        });
        root.addView(btnFocus, matchWrap(0, 0, 0, 24));

        btnKeystone = makeButton("ATTIVA SENSORI TRAPEZIO");
        btnKeystone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (keystoneOn) {
                    stopKeystone("Sensori trapezio disattivati");
                } else {
                    startKeystone();
                }
            }
        });
        root.addView(btnKeystone, matchWrap(0, 0, 0, 32));

        tvAngle = new TextView(this);
        tvAngle.setText("Angolo: --");
        tvAngle.setTextSize(32);
        tvAngle.setTextColor(Color.parseColor("#FFD54F"));
        tvAngle.setGravity(Gravity.CENTER);
        root.addView(tvAngle, matchWrap(0, 0, 0, 12));

        tvStatus = new TextView(this);
        tvStatus.setText("Trapezio: OFF");
        tvStatus.setTextSize(26);
        tvStatus.setTextColor(Color.LTGRAY);
        tvStatus.setGravity(Gravity.CENTER);
        root.addView(tvStatus, matchWrap(0, 0, 0, 0));

        setContentView(root);
        btnFocus.requestFocus();
    }

    // ---------------------------------------------------------------- AUTOFOCUS

    private void launchAutofocus() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(FOCUS_PACKAGE, FOCUS_ACTIVITY));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("App autofocus non trovata: " + FOCUS_PACKAGE);
        } catch (SecurityException e) {
            toast("Activity autofocus non accessibile (non esportata)");
        }
    }

    // ---------------------------------------------------------------- TRAPEZIO

    private void startKeystone() {
        if (accelerometer == null) {
            toast("Accelerometro non disponibile su questo dispositivo");
            return;
        }
        keystoneOn = true;
        updateKeystoneUi();
        tvStatus.setText("Trapezio: richiesta permessi root...");

        // Apre la shell root in background; solo se riesce registra il sensore
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    suProcess = Runtime.getRuntime().exec("su");
                    suStream = new DataOutputStream(suProcess.getOutputStream());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!keystoneOn) return; // nel frattempo disattivato
                            firstSample = true;
                            lastSentAngle = Integer.MIN_VALUE;
                            sensorManager.registerListener(MainActivity.this, accelerometer,
                                    SensorManager.SENSOR_DELAY_NORMAL);
                            tvStatus.setText("Trapezio: ON");
                        }
                    });
                } catch (IOException e) {
                    suProcess = null;
                    suStream = null;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            stopKeystone("Root non disponibile (comando su fallito)");
                        }
                    });
                }
            }
        });
    }

    private void stopKeystone(String message) {
        keystoneOn = false;
        sensorManager.unregisterListener(this);
        updateKeystoneUi();
        tvStatus.setText("Trapezio: OFF");
        toast(message);

        // Chiude la shell root in coda dopo eventuali comandi gia' inviati
        executor.execute(new Runnable() {
            @Override
            public void run() {
                closeSu();
            }
        });
    }

    private void closeSu() {
        try {
            if (suStream != null) {
                suStream.writeBytes("exit\n");
                suStream.flush();
                suStream.close();
            }
        } catch (IOException ignored) {
        }
        if (suProcess != null) {
            suProcess.destroy();
        }
        suStream = null;
        suProcess = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!keystoneOn || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        // Filtro passa-basso: stima della componente di gravita'
        if (firstSample) {
            gx = event.values[0];
            gy = event.values[1];
            gz = event.values[2];
            firstSample = false;
        } else {
            gx = ALPHA * gx + (1 - ALPHA) * event.values[0];
            gy = ALPHA * gy + (1 - ALPHA) * event.values[1];
            gz = ALPHA * gz + (1 - ALPHA) * event.values[2];
        }

        // Inclinazione sul piano Y-Z in gradi: 0 = piano orizzontale (gravita' su Z)
        final int angle = (int) Math.round(Math.toDegrees(Math.atan2(gy, gz)));
        tvAngle.setText("Angolo: " + angle + "\u00B0");

        if (lastSentAngle == Integer.MIN_VALUE
                || Math.abs(angle - lastSentAngle) >= MIN_DELTA_DEGREES) {
            lastSentAngle = angle;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    sendKeystone(angle);
                }
            });
        }
    }

    private void sendKeystone(int angle) {
        if (suStream == null) return;
        try {
            suStream.writeBytes("setprop " + KEYSTONE_PROP + " " + angle + "\n");
            suStream.flush();
        } catch (IOException e) {
            closeSu();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (keystoneOn) {
                        stopKeystone("Errore nella shell root: trapezio disattivato");
                    }
                }
            });
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Non necessario
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
        keystoneOn = false;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                closeSu();
            }
        });
        executor.shutdown();
    }

    // ---------------------------------------------------------------- UI HELPERS

    private void updateKeystoneUi() {
        btnKeystone.setText(keystoneOn
                ? "DISATTIVA SENSORI TRAPEZIO (ON)"
                : "ATTIVA SENSORI TRAPEZIO");
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(28);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setPadding(dp(24), dp(28), dp(24), dp(28));

        // Evidenziazione ben visibile per la navigazione col telecomando
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, box("#1565C0", "#FFD54F", 6));
        states.addState(new int[]{android.R.attr.state_pressed}, box("#0D47A1", "#FFFFFF", 6));
        states.addState(new int[]{}, box("#37474F", "#607D8B", 2));
        b.setBackground(states);
        return b;
    }

    private GradientDrawable box(String fill, String stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(fill));
        d.setStroke(dp(strokeDp), Color.parseColor(stroke));
        d.setCornerRadius(dp(12));
        return d;
    }

    private LinearLayout.LayoutParams matchWrap(int l, int t, int r, int bDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(l), dp(t), dp(r), dp(bDp));
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
