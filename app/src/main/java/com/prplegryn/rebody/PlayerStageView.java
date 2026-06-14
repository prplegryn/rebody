package com.prplegryn.rebody;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class PlayerStageView extends View {
  interface Listener {
    void onDividerHeightChanged(float heightDp, boolean fromUser);

    void onLineAlphaChanged(float alpha);
  }

  private static final float DEFAULT_LINE_ALPHA = 0.9f;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private Listener listener;
  private float dividerHeightDp = -1f;
  private float lineAlpha = DEFAULT_LINE_ALPHA;
  private float stretchScale = 1f;
  private boolean draggingHandle;
  private boolean locked;
  private ValueAnimator alphaAnimator;

  PlayerStageView(Context context) {
    super(context);
    setContentDescription("分界线");
  }

  void setListener(Listener listener) {
    this.listener = listener;
  }

  void setDividerHeightDp(float dividerHeightDp, boolean notify) {
    this.dividerHeightDp = dividerHeightDp;
    invalidate();
    if (notify && listener != null) {
      listener.onDividerHeightChanged(getDividerHeightDp(), false);
    }
  }

  float getDividerHeightDp() {
    return pxToDp(getDividerY());
  }

  float getDividerRatio() {
    return clamp(getDividerY() / Math.max(1f, getHeight()), 0.02f, 0.98f);
  }

  void setLineAlpha(float lineAlpha) {
    this.lineAlpha = clamp(lineAlpha, 0f, DEFAULT_LINE_ALPHA);
    invalidate();
  }

  void setStretchScale(float stretchScale) {
    this.stretchScale = stretchScale;
    invalidate();
  }

  void setLocked(boolean locked) {
    this.locked = locked;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (getWidth() <= 0 || getHeight() <= 0) {
      return;
    }

    int dividerY = getDividerY();
    if (Math.abs(stretchScale - 1f) > 0.01f) {
      paint.setColor(Color.argb(34, 226, 92, 88));
      rect.set(0f, dividerY, getWidth(), getHeight());
      canvas.drawRect(rect, paint);
    }

    float lineHeight = Math.max(1f, dp(1));
    paint.setColor(Color.argb(Math.round(255f * lineAlpha), 0, 0, 0));
    rect.set(0f, dividerY - lineHeight * 0.5f, getWidth(), dividerY + lineHeight * 0.5f);
    canvas.drawRect(rect, paint);

    drawLeftAlphaButton(canvas, dividerY);
    drawRightDragHandle(canvas, dividerY);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (locked || !isEnabled()) {
      return false;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (leftButtonRect(getDividerY()).contains(event.getX(), event.getY())) {
          return true;
        }
        if (rightHandleTouchRect(getDividerY()).contains(event.getX(), event.getY())) {
          draggingHandle = true;
          getParent().requestDisallowInterceptTouchEvent(true);
          updateDividerFromY(event.getY(), true);
          return true;
        }
        return false;
      case MotionEvent.ACTION_MOVE:
        if (draggingHandle) {
          updateDividerFromY(event.getY(), true);
          return true;
        }
        return false;
      case MotionEvent.ACTION_UP:
        if (draggingHandle) {
          draggingHandle = false;
          getParent().requestDisallowInterceptTouchEvent(false);
          updateDividerFromY(event.getY(), true);
          performClick();
          return true;
        }
        if (leftButtonRect(getDividerY()).contains(event.getX(), event.getY())) {
          cycleLineAlpha();
          performClick();
          return true;
        }
        return false;
      case MotionEvent.ACTION_CANCEL:
        draggingHandle = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      default:
        return super.onTouchEvent(event);
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  private void drawLeftAlphaButton(Canvas canvas, int dividerY) {
    RectF button = leftButtonRect(dividerY);
    float radius = dp(8);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.argb(235, 255, 252, 246));
    canvas.drawRoundRect(button, radius, radius, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1));
    paint.setColor(Color.rgb(176, 164, 146));
    canvas.drawRoundRect(button, radius, radius, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.rgb(42, 111, 104));
    float cy = button.centerY();
    float x0 = button.centerX() - dp(8);
    for (int i = 0; i < 3; i++) {
      canvas.drawCircle(x0 + i * dp(8), cy, dp(2), paint);
    }
  }

  private void drawRightDragHandle(Canvas canvas, int dividerY) {
    RectF handle = rightHandleRect(dividerY);
    float radius = dp(8);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.argb(235, 255, 252, 246));
    canvas.drawRoundRect(handle, radius, radius, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1));
    paint.setColor(Color.rgb(176, 164, 146));
    canvas.drawRoundRect(handle, radius, radius, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.rgb(42, 111, 104));
    float cy = handle.centerY();
    float cx = handle.centerX();
    canvas.drawRoundRect(cx - dp(5), cy - dp(1), cx + dp(5), cy + dp(1), dp(1), dp(1), paint);
    canvas.drawRoundRect(cx - dp(5), cy - dp(5), cx + dp(5), cy - dp(3), dp(1), dp(1), paint);
    canvas.drawRoundRect(cx - dp(5), cy + dp(3), cx + dp(5), cy + dp(5), dp(1), dp(1), paint);
  }

  private void cycleLineAlpha() {
    float target;
    if (lineAlpha > 0.5f) {
      target = 0f;
    } else if (lineAlpha < 0.05f) {
      target = 0.1f;
    } else {
      target = DEFAULT_LINE_ALPHA;
    }
    if (alphaAnimator != null) {
      alphaAnimator.cancel();
    }
    alphaAnimator = ValueAnimator.ofFloat(lineAlpha, target);
    alphaAnimator.setDuration(220);
    alphaAnimator.setInterpolator(new DecelerateInterpolator());
    alphaAnimator.addUpdateListener(
        animator -> {
          lineAlpha = (float) animator.getAnimatedValue();
          invalidate();
          if (listener != null) {
            listener.onLineAlphaChanged(lineAlpha);
          }
        });
    alphaAnimator.start();
  }

  private void updateDividerFromY(float y, boolean fromUser) {
    int minY = dp(18);
    int maxY = Math.max(minY, getHeight() - dp(18));
    dividerHeightDp = pxToDp(clamp(y, minY, maxY));
    invalidate();
    if (listener != null) {
      listener.onDividerHeightChanged(dividerHeightDp, fromUser);
    }
  }

  private int getDividerY() {
    if (getHeight() <= 0) {
      return 0;
    }
    float px = dividerHeightDp < 0f ? getHeight() * 0.5f : dp(dividerHeightDp);
    return Math.round(clamp(px, dp(18), Math.max(dp(18), getHeight() - dp(18))));
  }

  private RectF leftButtonRect(int dividerY) {
    float width = dp(42);
    float height = dp(24);
    return new RectF(dp(8), dividerY - height * 0.5f, dp(8) + width, dividerY + height * 0.5f);
  }

  private RectF rightHandleRect(int dividerY) {
    float width = dp(28);
    float height = dp(26);
    float right = getWidth() - dp(8);
    return new RectF(right - width, dividerY - height * 0.5f, right, dividerY + height * 0.5f);
  }

  private RectF rightHandleTouchRect(int dividerY) {
    RectF handle = rightHandleRect(dividerY);
    handle.inset(-dp(14), -dp(18));
    return handle;
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private float pxToDp(float value) {
    return value / getResources().getDisplayMetrics().density;
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }
}
