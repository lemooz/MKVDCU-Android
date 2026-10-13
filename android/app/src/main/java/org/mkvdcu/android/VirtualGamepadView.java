package org.mkvdcu.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

/** Transparent touch controller that feeds the port's virtual XInput device. */
final class VirtualGamepadView extends View {
    private static final int DPAD_UP = 0x0001;
    private static final int DPAD_DOWN = 0x0002;
    private static final int DPAD_LEFT = 0x0004;
    private static final int DPAD_RIGHT = 0x0008;
    private static final int START = 0x0010;
    private static final int BACK = 0x0020;
    private static final int LB = 0x0100;
    private static final int RB = 0x0200;
    private static final int A = 0x1000;
    private static final int B = 0x2000;
    private static final int X = 0x4000;
    private static final int Y = 0x8000;
    private static final int STICK = -1;
    private static final int TOGGLE = -2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Integer> pointers = new SparseArray<>();
    private boolean visible = true;
    private float stickX;
    private float stickY;
    private float stickRadius;

    VirtualGamepadView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(true);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        drawToggle(canvas, w, h);
        if (!visible) return;

        stickX = w * 0.145f;
        stickY = h * 0.775f;
        stickRadius = Math.min(w * 0.095f, h * 0.235f);
        drawStick(canvas);

        final float r = Math.max(42.0f, h * 0.075f);
        drawButton(canvas, w * 0.902f, h * 0.755f, r, "A", A);
        drawButton(canvas, w * 0.842f, h * 0.875f, r, "B", B);
        drawButton(canvas, w * 0.782f, h * 0.755f, r, "X", X);
        drawButton(canvas, w * 0.842f, h * 0.635f, r, "Y", Y);

        drawPill(canvas, w * 0.690f, h * 0.755f, w * 0.765f, h * 0.755f, "START", START);
        drawPill(canvas, w * 0.690f, h * 0.865f, w * 0.765f, h * 0.865f, "BACK", BACK);
        drawPill(canvas, w * 0.790f, h * 0.505f, w * 0.845f, h * 0.505f, "LB", LB);
        drawPill(canvas, w * 0.855f, h * 0.505f, w * 0.910f, h * 0.505f, "RB", RB);
    }

    private void drawToggle(Canvas c, float w, float h) {
        RectF rect = new RectF(w - 104, 16, w - 16, 58);
        paint.setColor(0xB5222730);
        c.drawRoundRect(rect, 18, 18, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0xBFFFFFFF);
        c.drawRoundRect(rect, 18, 18, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(18);
        c.drawText(visible ? "PAD  ×" : "PAD  +", rect.centerX(), rect.centerY() + 6, paint);
    }

    private void drawStick(Canvas c) {
        paint.setColor(0x55202730);
        c.drawCircle(stickX, stickY, stickRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(0xBFFFFFFF);
        c.drawCircle(stickX, stickY, stickRadius, paint);
        paint.setStyle(Paint.Style.FILL);
        float x = stickX + stickAxisX() * stickRadius * 0.48f;
        float y = stickY - stickAxisY() * stickRadius * 0.48f;
        paint.setColor(0xB5445362);
        c.drawCircle(x, y, stickRadius * 0.42f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0xEFFFFFFF);
        c.drawCircle(x, y, stickRadius * 0.42f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCCFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(15);
        c.drawText("L", stickX, stickY + stickRadius + 22, paint);
    }

    private float stickAxisX() {
        for (int i = 0; i < pointers.size(); i++) {
            if (pointers.valueAt(i) == STICK) {
                float value = (lastStickTouchX - stickX) / stickRadius;
                return Math.max(-1, Math.min(1, value));
            }
        }
        return 0;
    }

    private float stickAxisY() {
        for (int i = 0; i < pointers.size(); i++) {
            if (pointers.valueAt(i) == STICK) {
                float value = (stickY - lastStickTouchY) / stickRadius;
                return Math.max(-1, Math.min(1, value));
            }
        }
        return 0;
    }

    private float lastStickTouchX;
    private float lastStickTouchY;

    private void drawButton(Canvas c, float x, float y, float r, String label, int bit) {
        paint.setColor(isPressed(bit) ? 0xDDA5D6D9 : 0x77303B47);
        c.drawCircle(x, y, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(0xCCFFFFFF);
        c.drawCircle(x, y, r, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isPressed(bit) ? 0xFF162126 : Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(r * 0.66f);
        c.drawText(label, x, y + r * 0.23f, paint);
    }

    private void drawPill(Canvas c, float x, float y, float endX, float centerY,
                          String label, int bit) {
        RectF rect = new RectF(x, centerY - 22, endX, centerY + 22);
        paint.setColor(isPressed(bit) ? 0xDDA5D6D9 : 0x77303B47);
        c.drawRoundRect(rect, 16, 16, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0xCCFFFFFF);
        c.drawRoundRect(rect, 16, 16, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isPressed(bit) ? 0xFF162126 : Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(16);
        c.drawText(label, rect.centerX(), rect.centerY() + 6, paint);
    }

    private boolean isPressed(int bit) {
        for (int i = 0; i < pointers.size(); i++) {
            if (pointers.valueAt(i) == bit) return true;
        }
        return false;
    }

    private int targetAt(float x, float y) {
        final float w = getWidth();
        final float h = getHeight();
        if (x >= w - 112 && y <= 68) return TOGGLE;
        if (!visible) return 0;
        final float r = Math.max(42.0f, h * 0.075f) * 1.22f;
        if (inside(x, y, w * 0.902f, h * 0.755f, r)) return A;
        if (inside(x, y, w * 0.842f, h * 0.875f, r)) return B;
        if (inside(x, y, w * 0.782f, h * 0.755f, r)) return X;
        if (inside(x, y, w * 0.842f, h * 0.635f, r)) return Y;
        if (inside(x, y, w * 0.728f, h * 0.755f, 54)) return START;
        if (inside(x, y, w * 0.728f, h * 0.865f, 54)) return BACK;
        if (inside(x, y, w * 0.817f, h * 0.505f, 46)) return LB;
        if (inside(x, y, w * 0.882f, h * 0.505f, 46)) return RB;
        if (inside(x, y, stickX, stickY, stickRadius * 1.25f)) return STICK;
        return 0;
    }

    private static boolean inside(float x, float y, float cx, float cy, float radius) {
        float dx = x - cx;
        float dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    private void updateVirtualState() {
        int buttons = 0;
        for (int i = 0; i < pointers.size(); i++) {
            int target = pointers.valueAt(i);
            if (target > 0) buttons |= target;
        }
        float x = stickAxisX();
        float y = stickAxisY();
        if (x < -0.35f) buttons |= DPAD_LEFT;
        if (x > 0.35f) buttons |= DPAD_RIGHT;
        if (y < -0.35f) buttons |= DPAD_DOWN;
        if (y > 0.35f) buttons |= DPAD_UP;
        GameActivity.setVirtualGamepadState(buttons, (int) (x * 32767), (int) (y * 32767));
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int id = event.getPointerId(index);
            int target = targetAt(event.getX(index), event.getY(index));
            if (target == TOGGLE) {
                visible = !visible;
                invalidate();
                return true;
            }
            if (target == 0) return false;
            pointers.put(id, target);
            if (target == STICK) {
                lastStickTouchX = event.getX(index);
                lastStickTouchY = event.getY(index);
            }
            updateVirtualState();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int slot = pointers.indexOfKey(event.getPointerId(i));
                if (slot >= 0 && pointers.valueAt(slot) == STICK) {
                    lastStickTouchX = event.getX(i);
                    lastStickTouchY = event.getY(i);
                }
            }
            updateVirtualState();
            return pointers.size() > 0;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP ||
                action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_CANCEL) {
                pointers.clear();
            } else {
                pointers.delete(event.getPointerId(index));
            }
            updateVirtualState();
            return true;
        }
        return pointers.size() > 0;
    }
}
