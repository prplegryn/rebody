package com.prplegryn.rebody;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class SmoothSliderView extends View {
  interface Listener {
    void onScrubStart(float fraction);

    void onScrubMove(float fraction);

    void onScrubEnd(float fraction);
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private Listener listener;
  private float progressFraction;
  private boolean dragging;

  SmoothSliderView(Context context) {
    super(context);
    setMinimumHeight(dp(44));
    setContentDescription("视频进度");
  }

  void setListener(Listener listener) {
    this.listener = listener;
  }

  void setProgressFraction(float progressFraction) {
    if (dragging) {
      return;
    }
    this.progressFraction = clamp(progressFraction, 0f, 1f);
    invalidate();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int desiredHeight = dp(44);
    int height = resolveSize(desiredHeight, heightMeasureSpec);
    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float centerY = getHeight() * 0.5f;
    float left = dp(4);
    float right = getWidth() - dp(4);
    float trackHeight = dp(6);
    float radius = trackHeight * 0.5f;
    float knobRadius = dp(9);
    float progressX = left + (right - left) * progressFraction;

    paint.setColor(Color.rgb(224, 217, 204));
    rect.set(left, centerY - radius, right, centerY + radius);
    canvas.drawRoundRect(rect, radius, radius, paint);

    paint.setColor(Color.rgb(42, 111, 104));
    rect.set(left, centerY - radius, progressX, centerY + radius);
    canvas.drawRoundRect(rect, radius, radius, paint);

    paint.setColor(Color.WHITE);
    canvas.drawCircle(progressX, centerY, knobRadius, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1));
    paint.setColor(Color.rgb(55, 65, 81));
    canvas.drawCircle(progressX, centerY, knobRadius, paint);
    paint.setStyle(Paint.Style.FILL);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!isEnabled()) {
      return false;
    }
    float fraction = fractionForX(event.getX());
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        progressFraction = fraction;
        invalidate();
        if (listener != null) {
          listener.onScrubStart(fraction);
        }
        return true;
      case MotionEvent.ACTION_MOVE:
        progressFraction = fraction;
        invalidate();
        if (listener != null) {
          listener.onScrubMove(fraction);
        }
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        progressFraction = fraction;
        dragging = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
        if (listener != null) {
          listener.onScrubEnd(fraction);
        }
        performClick();
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

  private float fractionForX(float x) {
    float left = dp(4);
    float right = getWidth() - dp(4);
    return clamp((x - left) / Math.max(1f, right - left), 0f, 1f);
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }
}
