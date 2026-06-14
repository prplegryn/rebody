package com.prplegryn.rebody;

import android.content.Context;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

@UnstableApi
public final class ReBodyVideoEffect implements GlEffect {
  public interface ParametersProvider {
    float getSplitTopRatio();

    float getStretchScale();
  }

  private final ParametersProvider parametersProvider;
  private final boolean cropOutput;

  public ReBodyVideoEffect(ParametersProvider parametersProvider) {
    this(parametersProvider, false);
  }

  public ReBodyVideoEffect(ParametersProvider parametersProvider, boolean cropOutput) {
    this.parametersProvider = parametersProvider;
    this.cropOutput = cropOutput;
  }

  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    return new ReBodyShaderProgram(context, useHdr, parametersProvider, cropOutput);
  }

  @Override
  public boolean isNoOp(int inputWidth, int inputHeight) {
    return false;
  }
}
