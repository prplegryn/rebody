package com.prplegryn.rebody;

import android.content.Context;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

final class RoundedFrameLayout extends FrameLayout {
  private float radiusPx;

  RoundedFrameLayout(Context context, float radiusPx) {
    super(context);
    this.radiusPx = radiusPx;
    setClipToOutline(true);
    setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), RoundedFrameLayout.this.radiusPx);
          }
        });
  }

  void setRadius(float radiusPx) {
    this.radiusPx = radiusPx;
    invalidateOutline();
  }
}
