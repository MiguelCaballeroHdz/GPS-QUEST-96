package com.gpsquest96.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Joystick virtual para el Modo Sofá (RULES.md §2).
 * <p>
 * Permite al usuario desplazarse digitalmente cuando el GPS real
 * está desactivado o en zonas de riesgo (geovallas de agua).
 * <p>
 * El joystick genera valores normalizados (-1.0 a 1.0) en ejes X e Y
 * que se traducen a desplazamiento de coordenadas geográficas.
 */
public class JoystickView extends View {

    private static final float BASE_RADIUS_RATIO = 0.4f;
    private static final float STICK_RADIUS_RATIO = 0.15f;

    private Paint basePaint;
    private Paint stickPaint;

    private float centerX, centerY;
    private float baseRadius;
    private float stickRadius;
    private float stickX, stickY;

    private boolean isActive = false;

    private JoystickListener listener;

    public interface JoystickListener {
        void onMove(float x, float y);
        void onRelease();
    }

    public JoystickView(Context context) {
        super(context);
        init();
    }

    // Requerido por LayoutInflater (activity_main.xml). Misma init(), sin cambios.
    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.argb(100, 255, 255, 255));
        basePaint.setStyle(Paint.Style.FILL);

        stickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stickPaint.setColor(Color.argb(200, 200, 200, 200));
        stickPaint.setStyle(Paint.Style.FILL);

        stickX = 0;
        stickY = 0;
    }

    public void setJoystickListener(JoystickListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) * BASE_RADIUS_RATIO;
        stickRadius = Math.min(w, h) * STICK_RADIUS_RATIO;
        stickX = centerX;
        stickY = centerY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Dibujar base
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);

        // Dibujar palanca
        canvas.drawCircle(stickX, stickY, stickRadius, stickPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isActive = true;
                updateStick(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isActive) {
                    updateStick(event.getX(), event.getY());
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isActive = false;
                stickX = centerX;
                stickY = centerY;
                invalidate();
                if (listener != null) {
                    listener.onRelease();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateStick(float touchX, float touchY) {
        float dx = touchX - centerX;
        float dy = touchY - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        if (distance > baseRadius) {
            // Limitar al borde de la base
            float angle = (float) Math.atan2(dy, dx);
            stickX = centerX + (float) Math.cos(angle) * baseRadius;
            stickY = centerY + (float) Math.sin(angle) * baseRadius;
        } else {
            stickX = touchX;
            stickY = touchY;
        }

        invalidate();

        if (listener != null) {
            // Normalizar a rango [-1.0, 1.0]
            float normX = (stickX - centerX) / baseRadius;
            float normY = (stickY - centerY) / baseRadius;
            listener.onMove(normX, normY);
        }
    }
}
