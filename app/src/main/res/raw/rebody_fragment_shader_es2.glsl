#version 100
precision mediump float;

uniform sampler2D uTexSampler;
uniform float uSplitTop;
uniform float uStretchScale;
uniform float uOutputHeightRatio;
uniform vec3 uBlankColor;
varying vec2 vTexSamplingCoord;

void main() {
  float outputYFromTop = (1.0 - vTexSamplingCoord.y) * uOutputHeightRatio;
  float contentEnd = uSplitTop + (1.0 - uSplitTop) * uStretchScale;
  if (outputYFromTop > contentEnd) {
    gl_FragColor = vec4(uBlankColor, 1.0);
    return;
  }

  float sourceYFromTop = outputYFromTop;

  if (outputYFromTop > uSplitTop) {
    float lowerDistance = outputYFromTop - uSplitTop;
    sourceYFromTop = uSplitTop + lowerDistance / max(uStretchScale, 0.05);
  }

  float sourceTexY = clamp(1.0 - sourceYFromTop, 0.0, 1.0);
  gl_FragColor = texture2D(uTexSampler, vec2(vTexSamplingCoord.x, sourceTexY));
}
