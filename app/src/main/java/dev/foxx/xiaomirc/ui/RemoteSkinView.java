package dev.foxx.xiaomirc.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import dev.foxx.xiaomirc.hid.RemoteKey;

/**
 * Drawn XMRM-ML face. Hit targets use the same coordinates as the artwork,
 * so a button lights under the finger that sends its HID usage.
 */
public final class RemoteSkinView extends View {
    public interface Listener {
        void onKey(RemoteKey key, boolean down);
    }

    private static final float DESIGN_W = 1000f;
    private static final int TEXT = 0xFFF4F4F6;
    private static final int INK = 0xFF16161A;
    private static final int BTN = 0xFF2E2E34;
    private static final int NETFLIX = 0xFFE50914;
    private static final int PRIME = 0xFF00A8E1;
    private static final int YELLOW = 0xFFFDD835;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final java.util.ArrayList<Spot> spots = new java.util.ArrayList<>();
    private final RectF dpad = new RectF();
    private float designHeight;
    private Listener listener;
    private RemoteKey pressed;
    private boolean advertising;
    private boolean ready;

    public RemoteSkinView(Context context) {
        super(context);
        init();
    }

    public RemoteSkinView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setLinkState(boolean advertising, boolean ready) {
        this.advertising = advertising;
        this.ready = ready;
        invalidate();
    }

    private void init() {
        setClickable(true);
        float y = 188f;
        y = row(y, 156f, spot(RemoteKey.INPUT, "INPUT"), circle(RemoteKey.POWER, ""), spot(RemoteKey.SETTINGS, "SET"));
        y = row(y, 148f, digit(RemoteKey.N1, "1"), digit(RemoteKey.N2, "2"), digit(RemoteKey.N3, "3"));
        y = row(y, 148f, digit(RemoteKey.N4, "4"), digit(RemoteKey.N5, "5"), digit(RemoteKey.N6, "6"));
        y = row(y, 148f, digit(RemoteKey.N7, "7"), digit(RemoteKey.N8, "8"), digit(RemoteKey.N9, "9"));
        y = row(y, 148f, spot(RemoteKey.AUDIO, "AUDIO"), digit(RemoteKey.N0, "0"), spot(RemoteKey.INFO, "INFO"));
        y = row(y, 132f,
                spot(RemoteKey.NETFLIX, "NETFLIX", NETFLIX, TEXT),
                spot(RemoteKey.PRIME, "prime", PRIME, TEXT),
                spot(RemoteKey.YOUTUBE, "YouTube", 0xFFF7F7F8, INK));
        y = row(y, 124f, spot(RemoteKey.APPS, "APPS"), spot(RemoteKey.TV_PLUS, "TV+"), spot(RemoteKey.MUTE, "MUTE"));
        y = row(y, 118f, spot(RemoteKey.VOICE, "VOICE", 0xFF1565C0, TEXT));
        y = placeDpad(y);
        y = row(y, 128f, spot(RemoteKey.BACK, "BACK"), spot(RemoteKey.HOME, "HOME"), spot(RemoteKey.MENU, "MENU"));
        y = row(y, 128f, spot(RemoteKey.VOLUME_DOWN, "VOL −"), spot(RemoteKey.VOLUME_UP, "VOL +"));
        y = row(y, 124f, spot(RemoteKey.CHANNEL_DOWN, "CH −"), spot(RemoteKey.GUIDE, "GUIDE"), spot(RemoteKey.CHANNEL_UP, "CH +"));
        y = row(y, 112f,
                circle(RemoteKey.RED, "R", 0xFFE53935, TEXT),
                circle(RemoteKey.GREEN, "G", 0xFF43A047, TEXT),
                circle(RemoteKey.YELLOW, "Y", YELLOW, INK),
                circle(RemoteKey.BLUE, "B", 0xFF1E88E5, TEXT));
        y = row(y, 120f, spot(RemoteKey.PLAY, "PLAY"), spot(RemoteKey.EXIT, "EXIT"), spot(RemoteKey.TEXT, "TEXT"), spot(RemoteKey.TV, "TV"));
        designHeight = y + 36f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = (int) (360f * getResources().getDisplayMetrics().density);
        }
        int height = Math.round(width * (designHeight / DESIGN_W));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float scale = getWidth() / DESIGN_W;
        canvas.save();
        canvas.scale(scale, scale);
        RectF body = new RectF(18f, 10f, DESIGN_W - 18f, designHeight - 12f);
        paint.setShader(new LinearGradient(
                0f, 0f, 0f, designHeight, 0xFF32323A, 0xFF101014, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(body, 78f, 78f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(0xFF45454E);
        canvas.drawRoundRect(body, 78f, 78f, paint);
        paint.setStyle(Paint.Style.FILL);
        drawHeader(canvas);
        for (Spot spot : spots) {
            drawSpot(canvas, spot);
        }
        drawDpad(canvas);
        canvas.restore();
    }

    private void drawHeader(Canvas canvas) {
        float cx = DESIGN_W / 2f;
        int led = ready ? 0xFF66BB6A : (advertising ? 0xFFFFB300 : 0xFF5C5C66);
        paint.setColor(led);
        canvas.drawCircle(cx, 46f, 9f, paint);
        paint.setColor(TEXT);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(52f);
        canvas.drawText("XMRM-ML", cx, 112f, paint);
        paint.setTypeface(Typeface.SANS_SERIF);
        paint.setColor(0xFFB7B7C0);
        paint.setTextSize(24f);
        canvas.drawText("XIAOMI  ·  L43MA-SEU", cx, 150f, paint);
    }

    private void drawSpot(Canvas canvas, Spot spot) {
        boolean down = spot.key == pressed;
        RectF rect = spot.rect;
        paint.setColor(down ? lighten(spot.fill) : spot.fill);
        if (spot.circle) {
            float radius = Math.min(rect.width(), rect.height()) / 2f - 2f;
            canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint);
        } else {
            float radius = Math.min(32f, rect.height() / 2f);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        if (spot.key == RemoteKey.POWER) {
            drawPowerGlyph(canvas, rect);
            return;
        }
        drawLabel(canvas, rect, spot.label, spot.text, spot.circle);
    }

    private void drawLabel(Canvas canvas, RectF rect, String label, int color, boolean circle) {
        if (label == null || label.isEmpty()) {
            return;
        }
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        float size = rect.height() * (label.length() > 6 ? 0.28f : 0.34f);
        paint.setTextSize(size);
        float max = (circle ? rect.width() * 0.62f : rect.width() * 0.86f);
        float measured = paint.measureText(label);
        if (measured > max && measured > 0f) {
            paint.setTextSize(size * max / measured);
        }
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(label, rect.centerX(), baseline, paint);
    }

    private void drawPowerGlyph(Canvas canvas, RectF rect) {
        float cx = rect.centerX();
        float cy = rect.centerY() + rect.height() * 0.04f;
        float radius = rect.width() * 0.16f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(6f, rect.width() * 0.045f));
        paint.setColor(pressed == RemoteKey.POWER ? 0xFFFF8A80 : 0xFFE53935);
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 130f, 280f, false, paint);
        canvas.drawLine(cx, cy - radius * 1.35f, cx, cy - radius * 0.15f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawDpad(Canvas canvas) {
        float cx = dpad.centerX();
        float cy = dpad.centerY();
        float radius = dpad.width() / 2f;
        paint.setColor(0xFF26262C);
        canvas.drawCircle(cx, cy, radius, paint);
        if (pressed == RemoteKey.UP || pressed == RemoteKey.RIGHT
                || pressed == RemoteKey.DOWN || pressed == RemoteKey.LEFT) {
            paint.setColor(0x55FFFFFF);
            float start = pressed == RemoteKey.RIGHT ? 315f
                    : pressed == RemoteKey.DOWN ? 45f
                    : pressed == RemoteKey.LEFT ? 135f
                    : 225f;
            canvas.drawArc(dpad, start, 90f, true, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(0xFF4A4A54);
        canvas.drawCircle(cx, cy, radius * 0.58f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(TEXT);
        drawArrow(canvas, cx, cy - radius * 0.76f, 0);
        drawArrow(canvas, cx, cy + radius * 0.76f, 1);
        drawArrow(canvas, cx - radius * 0.76f, cy, 2);
        drawArrow(canvas, cx + radius * 0.76f, cy, 3);
        paint.setColor(pressed == RemoteKey.OK ? 0xFFF4F4F6 : 0xFF3C3C44);
        canvas.drawCircle(cx, cy, radius * 0.30f, paint);
        paint.setColor(pressed == RemoteKey.OK ? INK : TEXT);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(radius * 0.22f);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        canvas.drawText("OK", cx, cy - (metrics.ascent + metrics.descent) / 2f, paint);
    }

    private void drawArrow(Canvas canvas, float x, float y, int direction) {
        float s = 26f;
        arrow.reset();
        if (direction == 0) {
            arrow.moveTo(x, y - s);
            arrow.lineTo(x - s, y + s * 0.55f);
            arrow.lineTo(x + s, y + s * 0.55f);
        } else if (direction == 1) {
            arrow.moveTo(x, y + s);
            arrow.lineTo(x - s, y - s * 0.55f);
            arrow.lineTo(x + s, y - s * 0.55f);
        } else if (direction == 2) {
            arrow.moveTo(x - s, y);
            arrow.lineTo(x + s * 0.55f, y - s);
            arrow.lineTo(x + s * 0.55f, y + s);
        } else {
            arrow.moveTo(x + s, y);
            arrow.lineTo(x - s * 0.55f, y - s);
            arrow.lineTo(x - s * 0.55f, y + s);
        }
        arrow.close();
        canvas.drawPath(arrow, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float scale = getWidth() / DESIGN_W;
        if (scale <= 0f) {
            return false;
        }
        float x = event.getX() / scale;
        float y = event.getY() / scale;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                RemoteKey key = hit(x, y);
                if (key == null) {
                    return false;
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                pressed = key;
                fire(key, true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (pressed != null && hit(x, y) != pressed) {
                    fire(pressed, false);
                    pressed = null;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressed != null) {
                    fire(pressed, false);
                    pressed = null;
                    invalidate();
                }
                return true;
            default:
                return false;
        }
    }

    private RemoteKey hit(float x, float y) {
        if (dpad.contains(x, y)) {
            float dx = x - dpad.centerX();
            float dy = y - dpad.centerY();
            float ok = dpad.width() * 0.16f;
            if (dx * dx + dy * dy <= ok * ok) {
                return RemoteKey.OK;
            }
            if (Math.abs(dx) > Math.abs(dy)) {
                return dx > 0f ? RemoteKey.RIGHT : RemoteKey.LEFT;
            }
            return dy > 0f ? RemoteKey.DOWN : RemoteKey.UP;
        }
        for (int i = spots.size() - 1; i >= 0; i--) {
            Spot spot = spots.get(i);
            if (spot.contains(x, y)) {
                return spot.key;
            }
        }
        return null;
    }

    private void fire(RemoteKey key, boolean down) {
        if (down) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            playSoundEffect(SoundEffectConstants.CLICK);
        }
        if (listener != null) {
            listener.onKey(key, down);
        }
    }

    private float row(float top, float height, Spot... items) {
        float gap = 18f;
        float side = 58f;
        float avail = DESIGN_W - side * 2f;
        float width = (avail - gap * (items.length - 1)) / items.length;
        float x = side;
        for (Spot spot : items) {
            spot.rect = new RectF(x, top, x + width, top + height);
            spots.add(spot);
            x += width + gap;
        }
        return top + height + gap;
    }

    private float placeDpad(float top) {
        float size = 620f;
        float left = (DESIGN_W - size) / 2f;
        dpad.set(left, top, left + size, top + size);
        return top + size + 24f;
    }

    private static Spot digit(RemoteKey key, String label) {
        return spot(key, label, 0xFF3A3A42, TEXT);
    }

    private static Spot spot(RemoteKey key, String label) {
        return spot(key, label, BTN, TEXT);
    }

    private static Spot spot(RemoteKey key, String label, int fill, int text) {
        Spot spot = new Spot();
        spot.key = key;
        spot.label = label;
        spot.fill = fill;
        spot.text = text;
        return spot;
    }

    private static Spot circle(RemoteKey key, String label) {
        return circle(key, label, 0xFF24242A, TEXT);
    }

    private static Spot circle(RemoteKey key, String label, int fill, int text) {
        Spot spot = spot(key, label, fill, text);
        spot.circle = true;
        return spot;
    }

    private static int lighten(int color) {
        return Color.rgb(
                Math.min(255, Color.red(color) + 42),
                Math.min(255, Color.green(color) + 42),
                Math.min(255, Color.blue(color) + 42));
    }

    private static final class Spot {
        RemoteKey key;
        String label;
        int fill;
        int text;
        boolean circle;
        RectF rect;

        boolean contains(float x, float y) {
            if (circle) {
                float dx = x - rect.centerX();
                float dy = y - rect.centerY();
                float radius = Math.min(rect.width(), rect.height()) / 2f;
                return dx * dx + dy * dy <= radius * radius;
            }
            return rect.contains(x, y);
        }
    }
}
