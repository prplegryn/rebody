#version 100
precision mediump float;

uniform sampler2D uTexSampler;
uniform float uSplitTop;
uniform float uStretchScale;
varying vec2 vTexSamplingCoord;

void main() {
  float screenYFromTop = 1.0 - vTexSamplingCoord.y;
  float sourceYFromTop = screenYFromTop;

  if (screenYFromTop > uSplitTop) {
    float lowerDistance = screenYFromTop - uSplitTop;
    sourceYFromTop = uSplitTop + lowerDistance / max(uStretchScale, 0.05);
  }

  float sourceTexY = clamp(1.0 - sourceYFromTop, 0.0, 1.0);
  gl_FragColor = texture2D(uTexSampler, vec2(vTexSamplingCoord.x, sourceTexY));
}
