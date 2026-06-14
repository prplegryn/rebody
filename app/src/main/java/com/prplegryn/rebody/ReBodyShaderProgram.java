package com.prplegryn.rebody;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import java.io.IOException;

@UnstableApi
final class ReBodyShaderProgram extends BaseGlShaderProgram {
  private final GlProgram glProgram;
  private final ReBodyVideoEffect.ParametersProvider parametersProvider;

  ReBodyShaderProgram(
      Context context, boolean useHdr, ReBodyVideoEffect.ParametersProvider parametersProvider)
      throws VideoFrameProcessingException {
    super(useHdr, 1);
    this.parametersProvider = parametersProvider;
    try {
      glProgram =
          new GlProgram(
              context,
              R.raw.rebody_vertex_shader_es2,
              R.raw.rebody_fragment_shader_es2);
    } catch (IOException | GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }

    glProgram.setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
    float[] identityMatrix = GlUtil.create4x4IdentityMatrix();
    glProgram.setFloatsUniform("uTransformationMatrix", identityMatrix);
    glProgram.setFloatsUniform("uTexTransformationMatrix", identityMatrix);
  }

  @Override
  public Size configure(int inputWidth, int inputHeight) {
    return new Size(inputWidth, inputHeight);
  }

  @Override
  public void drawFrame(int inputTexId, long presentationTimeUs)
      throws VideoFrameProcessingException {
    try {
      glProgram.use();
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
      glProgram.setFloatUniform("uSplitTop", clamp(parametersProvider.getSplitTopRatio(), 0.02f, 0.98f));
      glProgram.setFloatUniform("uStretchScale", clamp(parametersProvider.getStretchScale(), 0.2f, 3f));
      glProgram.bindAttributesAndUniforms();
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e, presentationTimeUs);
    }
  }

  @Override
  public void release() throws VideoFrameProcessingException {
    super.release();
    try {
      glProgram.delete();
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }
}
