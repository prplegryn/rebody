package com.prplegryn.rebody;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ScrubbingModeParameters;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

@OptIn(markerClass = UnstableApi.class)
public final class MainActivity extends Activity implements ReBodyVideoEffect.ParametersProvider {
  private static final int REQUEST_VIDEO = 1001;
  private static final int REQUEST_OUTPUT_DIR = 1002;
  private static final String PREFS = "rebody";
  private static final String KEY_DIVIDER_DP = "divider_dp";
  private static final String KEY_STRETCH = "stretch";
  private static final String KEY_OUTPUT_TREE = "output_tree";
  private static final String KEY_LINE_ALPHA = "line_alpha";
  private static final String[] STEP_LABELS = {"帧", "1s", "3s", "5s"};
  private static final float MIN_STRETCH = 0.2f;
  private static final float MAX_STRETCH = 3f;
  private static final float NEUTRAL_STRETCH_FRACTION = 0.7f;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ProgressHolder progressHolder = new ProgressHolder();

  private SharedPreferences prefs;
  private Typeface interTypeface;
  private Typeface interMediumTypeface;
  private FrameLayout stageArea;
  private RoundedFrameLayout playerShell;
  private PlayerView playerView;
  private PlayerStageView stageOverlay;
  private SmoothSliderView progressSlider;
  private SmoothSliderView stretchSlider;
  private EditText dividerHeightInput;
  private TextView playButton;
  private TextView stepButton;
  private TextView stretchValueText;
  private TextView exportButton;
  private TextView fileButton;
  private TextView outputDirButton;
  private TextView backButton;
  private TextView forwardButton;
  private TextView minusButton;
  private TextView plusButton;

  private ExoPlayer player;
  private Transformer transformer;
  private Uri currentVideoUri;
  private Uri outputTreeUri;
  private float videoAspect = 9f / 16f;
  private int inputVideoWidth = Format.NO_VALUE;
  private int inputVideoHeight = Format.NO_VALUE;
  private int inputVideoBitrate = Format.NO_VALUE;
  private float inputVideoFrameRate = Format.NO_VALUE;
  private float dividerHeightDp = -1f;
  private float stretchScale = 1f;
  private int stepMode;
  private long frameStepMs = 33L;
  private boolean exporting;
  private boolean scrubbing;
  private boolean pendingTextEditUpdate;
  private boolean previewRefreshQueued;
  private String exportText = "导出";
  private File pendingExportFile;

  private final Runnable progressUpdater =
      new Runnable() {
        @Override
        public void run() {
          if (player == null || exporting) {
            mainHandler.postDelayed(this, 120);
            return;
          }
          long duration = player.getDuration();
          if (!scrubbing && duration > 0 && duration != C.TIME_UNSET) {
            progressSlider.setProgressFraction(player.getCurrentPosition() / (float) duration);
          }
          mainHandler.postDelayed(this, 120);
        }
      };

  private final Runnable previewRefresh =
      () -> {
        previewRefreshQueued = false;
        refreshPreviewFrame();
      };

  private final Runnable exportProgressUpdater =
      new Runnable() {
        @Override
        public void run() {
          if (!exporting || transformer == null) {
            return;
          }
          int state = transformer.getProgress(progressHolder);
          if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            setExportTextAnimated(progressHolder.progress + "%");
          }
          mainHandler.postDelayed(this, 260);
        }
      };

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    configureWindow();
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    interTypeface = Typeface.createFromAsset(getAssets(), "fonts/Inter.ttf");
    interMediumTypeface = Typeface.create(interTypeface, Typeface.BOLD);
    dividerHeightDp = prefs.getFloat(KEY_DIVIDER_DP, -1f);
    stretchScale = prefs.getFloat(KEY_STRETCH, 1f);
    String outputTree = prefs.getString(KEY_OUTPUT_TREE, null);
    if (outputTree != null) {
      outputTreeUri = Uri.parse(outputTree);
    }
    setContentView(buildContentView());
    createPlayer();
    mainHandler.post(progressUpdater);
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (player != null) {
      player.pause();
    }
  }

  @Override
  protected void onDestroy() {
    mainHandler.removeCallbacksAndMessages(null);
    if (transformer != null) {
      transformer.cancel();
    }
    if (player != null) {
      player.release();
      player = null;
    }
    super.onDestroy();
  }

  @Override
  public float getSplitTopRatio() {
    return stageOverlay == null ? 0.5f : stageOverlay.getDividerRatio();
  }

  @Override
  public float getStretchScale() {
    return stretchScale;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode != RESULT_OK || data == null) {
      return;
    }
    if (requestCode == REQUEST_VIDEO) {
      Uri uri = data.getData();
      if (uri != null) {
        persistUriPermission(uri, data, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        loadVideo(uri);
      }
    } else if (requestCode == REQUEST_OUTPUT_DIR) {
      Uri uri = data.getData();
      if (uri != null) {
        persistUriPermission(
            uri,
            data,
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        outputTreeUri = uri;
        prefs.edit().putString(KEY_OUTPUT_TREE, uri.toString()).apply();
        toast("输出目录已设置");
      }
    }
  }

  private void configureWindow() {
    Window window = getWindow();
    if (Build.VERSION.SDK_INT >= 30) {
      window.setDecorFitsSystemWindows(true);
    }
    window.setStatusBarColor(Color.rgb(249, 245, 234));
    window.setNavigationBarColor(Color.rgb(249, 245, 234));
    if (Build.VERSION.SDK_INT >= 23) {
      window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }
  }

  private View buildContentView() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER_HORIZONTAL);
    root.setBackgroundColor(Color.rgb(249, 245, 234));
    root.setPadding(dp(14), dp(10), dp(14), dp(12));
    root.setFitsSystemWindows(true);

    stageArea = new FrameLayout(this);
    stageArea.setBackgroundColor(Color.rgb(249, 245, 234));
    LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-1, 0, 1f);
    root.addView(stageArea, stageParams);

    playerShell = new RoundedFrameLayout(this, dp(18));
    playerShell.setBackgroundColor(Color.rgb(246, 218, 212));
    FrameLayout.LayoutParams shellParams = new FrameLayout.LayoutParams(dp(220), dp(340), Gravity.CENTER);
    stageArea.addView(playerShell, shellParams);

    playerView = (PlayerView) LayoutInflater.from(this).inflate(R.layout.player_view, playerShell, false);
    playerShell.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

    stageOverlay = new PlayerStageView(this);
    stageOverlay.setDividerHeightDp(dividerHeightDp, false);
    stageOverlay.setLineAlpha(prefs.getFloat(KEY_LINE_ALPHA, 0.9f));
    stageOverlay.setStretchScale(stretchScale);
    stageOverlay.setListener(
        new PlayerStageView.Listener() {
          @Override
          public void onDividerHeightChanged(float heightDp, boolean fromUser) {
            dividerHeightDp = heightDp;
            prefs.edit().putFloat(KEY_DIVIDER_DP, dividerHeightDp).apply();
            updateDividerHeightInput();
            requestPreviewRefresh();
          }

          @Override
          public void onLineAlphaChanged(float alpha) {
            prefs.edit().putFloat(KEY_LINE_ALPHA, alpha).apply();
          }
        });
    playerShell.addView(stageOverlay, new FrameLayout.LayoutParams(-1, -1));

    stageArea.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updatePlayerShellLayout());
    stageOverlay.post(
        () -> {
          if (dividerHeightDp < 0f) {
            dividerHeightDp = stageOverlay.getDividerHeightDp();
          }
          updateDividerHeightInput();
        });

    LinearLayout controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.VERTICAL);
    controls.setGravity(Gravity.CENTER_HORIZONTAL);
    controls.setPadding(0, dp(8), 0, 0);
    root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

    controls.addView(buildDividerHeightRow(), new LinearLayout.LayoutParams(-1, dp(42)));

    progressSlider = new SmoothSliderView(this);
    progressSlider.setEnabled(false);
    progressSlider.setListener(
        new SmoothSliderView.Listener() {
          @Override
          public void onScrubStart(float fraction) {
            if (player == null || currentVideoUri == null) {
              return;
            }
            scrubbing = true;
            player.setScrubbingModeEnabled(true);
            seekToFraction(fraction);
          }

          @Override
          public void onScrubMove(float fraction) {
            seekToFraction(fraction);
          }

          @Override
          public void onScrubEnd(float fraction) {
            seekToFraction(fraction);
            if (player != null) {
              player.setScrubbingModeEnabled(false);
            }
            scrubbing = false;
          }
        });
    controls.addView(progressSlider, new LinearLayout.LayoutParams(-1, dp(44)));

    controls.addView(buildPlaybackRow(), new LinearLayout.LayoutParams(-1, dp(52)));
    controls.addView(buildFileExportRow(), new LinearLayout.LayoutParams(-1, dp(52)));
    controls.addView(buildStretchSliderRow(), new LinearLayout.LayoutParams(-1, dp(44)));

    updateControlsEnabled();
    return root;
  }

  private LinearLayout buildDividerHeightRow() {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER);
    row.setOrientation(LinearLayout.HORIZONTAL);

    TextView label = new TextView(this);
    label.setText("高度");
    label.setTextSize(13);
    label.setTextColor(Color.rgb(62, 55, 45));
    label.setTypeface(interMediumTypeface);
    row.addView(label);

    dividerHeightInput = new EditText(this);
    dividerHeightInput.setSingleLine(true);
    dividerHeightInput.setGravity(Gravity.CENTER);
    dividerHeightInput.setTextSize(14);
    dividerHeightInput.setTypeface(interMediumTypeface);
    dividerHeightInput.setTextColor(Color.rgb(25, 28, 32));
    dividerHeightInput.setSelectAllOnFocus(true);
    dividerHeightInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    dividerHeightInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
    dividerHeightInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6)});
    dividerHeightInput.setPadding(dp(10), 0, dp(10), 0);
    dividerHeightInput.setBackground(inputBackground());
    dividerHeightInput.setOnEditorActionListener(
        (v, actionId, event) -> {
          if (actionId == EditorInfo.IME_ACTION_DONE) {
            applyDividerHeightInput();
            hideKeyboard(v);
            v.clearFocus();
            return true;
          }
          return false;
        });
    dividerHeightInput.setOnFocusChangeListener(
        (v, hasFocus) -> {
          if (hasFocus) {
            pendingTextEditUpdate = false;
          } else {
            applyDividerHeightInput();
          }
        });
    LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(dp(84), dp(34));
    inputParams.leftMargin = dp(8);
    row.addView(dividerHeightInput, inputParams);

    Space gap = new Space(this);
    row.addView(gap, new LinearLayout.LayoutParams(dp(18), 1));

    TextView stretchLabel = new TextView(this);
    stretchLabel.setText("伸缩");
    stretchLabel.setTextSize(13);
    stretchLabel.setTextColor(Color.rgb(62, 55, 45));
    stretchLabel.setTypeface(interMediumTypeface);
    row.addView(stretchLabel);

    stretchValueText = new TextView(this);
    stretchValueText.setGravity(Gravity.CENTER);
    stretchValueText.setTextSize(14);
    stretchValueText.setIncludeFontPadding(false);
    stretchValueText.setTypeface(interMediumTypeface);
    stretchValueText.setTextColor(Color.rgb(25, 28, 32));
    stretchValueText.setBackground(inputBackground());
    stretchValueText.setContentDescription("精确伸缩值");
    LinearLayout.LayoutParams stretchValueParams = new LinearLayout.LayoutParams(dp(76), dp(34));
    stretchValueParams.leftMargin = dp(8);
    row.addView(stretchValueText, stretchValueParams);
    updateStretchValueText();
    return row;
  }

  private SmoothSliderView buildStretchSliderRow() {
    stretchSlider = new SmoothSliderView(this);
    stretchSlider.setContentDescription("伸缩值");
    stretchSlider.setNeutralFraction(NEUTRAL_STRETCH_FRACTION);
    stretchSlider.setProgressFraction(stretchScaleToFraction(stretchScale));
    stretchSlider.setListener(
        new SmoothSliderView.Listener() {
          @Override
          public void onScrubStart(float fraction) {
            setStretchFromSlider(fraction);
          }

          @Override
          public void onScrubMove(float fraction) {
            setStretchFromSlider(fraction);
          }

          @Override
          public void onScrubEnd(float fraction) {
            setStretchFromSlider(fraction);
          }
        });
    return stretchSlider;
  }

  private LinearLayout buildPlaybackRow() {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setOrientation(LinearLayout.HORIZONTAL);

    LinearLayout left = new LinearLayout(this);
    left.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
    left.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

    backButton = makeButton("<<", 50, "后退");
    bindRepeating(backButton, () -> seekBy(-currentStepMs()));
    left.addView(backButton);
    left.addView(space(8));

    playButton = makeButton(">", 50, "播放或暂停");
    playButton.setOnClickListener(v -> togglePlayback());
    left.addView(playButton);
    left.addView(space(8));

    forwardButton = makeButton(">>", 50, "前进");
    bindRepeating(forwardButton, () -> seekBy(currentStepMs()));
    left.addView(forwardButton);
    left.addView(space(8));

    stepButton = makeButton(STEP_LABELS[stepMode], 44, "步进幅度");
    stepButton.setMinWidth(dp(44));
    stepButton.setOnClickListener(
        v -> {
          stepMode = (stepMode + 1) % STEP_LABELS.length;
          stepButton.setText(STEP_LABELS[stepMode]);
        });
    left.addView(stepButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

    LinearLayout right = new LinearLayout(this);
    right.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
    right.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(right, new LinearLayout.LayoutParams(-2, -1));

    minusButton = makeButton("-", 48, "缩小下方区域");
    bindRepeating(minusButton, () -> adjustStretch(-0.01f), 36);
    right.addView(minusButton);
    right.addView(space(8));

    plusButton = makeButton("+", 48, "拉伸下方区域");
    bindRepeating(plusButton, () -> adjustStretch(0.01f), 36);
    right.addView(plusButton);

    return row;
  }

  private LinearLayout buildFileExportRow() {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setOrientation(LinearLayout.HORIZONTAL);

    LinearLayout left = new LinearLayout(this);
    left.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
    left.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(left, new LinearLayout.LayoutParams(-2, -1));

    fileButton = makeButton("视频", 64, "选择视频");
    fileButton.setOnClickListener(v -> openVideoPicker());
    left.addView(fileButton);
    left.addView(space(8));

    outputDirButton = makeButton("目录", 64, "选择输出目录");
    outputDirButton.setOnClickListener(v -> openOutputDirectoryPicker());
    left.addView(outputDirButton);

    Space gap = new Space(this);
    row.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));

    exportButton = makeButton(exportText, 112, "导出视频");
    exportButton.setGravity(Gravity.CENTER);
    exportButton.setOnClickListener(v -> startExport());
    LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
    row.addView(exportButton, exportParams);
    return row;
  }

  private void createPlayer() {
    player = new ExoPlayer.Builder(this).setSeekParameters(SeekParameters.EXACT).build();
    player.setSeekParameters(SeekParameters.EXACT);
    player.setScrubbingModeParameters(new ScrubbingModeParameters.Builder().build());
    player.setVideoEffects(Collections.singletonList(new ReBodyVideoEffect(this)));
    player.addListener(
        new Player.Listener() {
          @Override
          public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayButton();
          }

          @Override
          public void onPlaybackStateChanged(int playbackState) {
            updatePlayButton();
          }

          @Override
          public void onVideoSizeChanged(VideoSize videoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
              videoAspect = (videoSize.width * videoSize.pixelWidthHeightRatio) / (float) videoSize.height;
              updatePlayerShellLayout();
            }
          }

          @Override
          public void onTracksChanged(Tracks tracks) {
            updateFrameStepFromTracks(tracks);
          }
        });
    playerView.setPlayer(player);
    updatePlayButton();
  }

  private void loadVideo(Uri uri) {
    currentVideoUri = uri;
    readVideoMetadata(uri);
    updatePlayerShellLayout();
    player.setMediaItem(MediaItem.fromUri(uri));
    player.prepare();
    player.setPlayWhenReady(true);
    progressSlider.setProgressFraction(0f);
    updateControlsEnabled();
  }

  private void readVideoMetadata(Uri uri) {
    inputVideoWidth = Format.NO_VALUE;
    inputVideoHeight = Format.NO_VALUE;
    inputVideoBitrate = Format.NO_VALUE;
    inputVideoFrameRate = Format.NO_VALUE;
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      retriever.setDataSource(this, uri);
      int width = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
      int height = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
      int rotation = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
      inputVideoBitrate =
          parseInt(
              retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE),
              Format.NO_VALUE);
      if ((rotation == 90 || rotation == 270) && width > 0 && height > 0) {
        int tmp = width;
        width = height;
        height = tmp;
      }
      if (width > 0 && height > 0) {
        inputVideoWidth = width;
        inputVideoHeight = height;
        videoAspect = width / (float) height;
      }
    } catch (RuntimeException ignored) {
      videoAspect = 9f / 16f;
    } finally {
      try {
        retriever.release();
      } catch (IOException ignored) {
        // Metadata is already read; release failures are not actionable for this UI.
      }
    }
    frameStepMs = readFrameStepMs(uri);
  }

  private long readFrameStepMs(Uri uri) {
    MediaExtractor extractor = new MediaExtractor();
    try {
      extractor.setDataSource(this, uri, null);
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("video/")) {
          if (inputVideoWidth == Format.NO_VALUE && format.containsKey(MediaFormat.KEY_WIDTH)) {
            inputVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
          }
          if (inputVideoHeight == Format.NO_VALUE && format.containsKey(MediaFormat.KEY_HEIGHT)) {
            inputVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
          }
          if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            inputVideoBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE);
          }
          if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            return 33L;
          }
          int frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
          if (frameRate > 0) {
            inputVideoFrameRate = frameRate;
            return Math.max(1L, Math.round(1000f / frameRate));
          }
        }
      }
    } catch (IOException | RuntimeException ignored) {
      return 33L;
    } finally {
      extractor.release();
    }
    return 33L;
  }

  private void updateFrameStepFromTracks(Tracks tracks) {
    for (Tracks.Group group : tracks.getGroups()) {
      if (group.getType() != C.TRACK_TYPE_VIDEO || !group.isSelected()) {
        continue;
      }
      for (int i = 0; i < group.length; i++) {
        if (group.isTrackSelected(i)) {
          Format format = group.getTrackFormat(i);
          if (format.frameRate > 0f && format.frameRate != Format.NO_VALUE) {
            inputVideoFrameRate = format.frameRate;
            frameStepMs = Math.max(1L, Math.round(1000f / format.frameRate));
            return;
          }
        }
      }
    }
  }

  private void updatePlayerShellLayout() {
    if (stageArea == null || playerShell == null || stageArea.getWidth() <= 0 || stageArea.getHeight() <= 0) {
      return;
    }
    int fixedHeight = Math.max(dp(140), stageArea.getHeight() - dp(20));
    int maxWidth = Math.max(dp(160), stageArea.getWidth() - dp(24));
    int width = Math.round(fixedHeight * Math.max(0.2f, videoAspect));
    width = Math.min(width, maxWidth);

    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) playerShell.getLayoutParams();
    params.width = width;
    params.height = fixedHeight;
    params.gravity = Gravity.CENTER;
    playerShell.setLayoutParams(params);
  }

  private void updateDividerHeightInput() {
    if (dividerHeightInput == null) {
      return;
    }
    if (dividerHeightInput.hasFocus()) {
      pendingTextEditUpdate = true;
      return;
    }
    pendingTextEditUpdate = false;
    dividerHeightInput.setText(String.format(Locale.US, "%.1f", dividerHeightDp));
  }

  private void updateStretchValueText() {
    if (stretchValueText != null) {
      stretchValueText.setText(String.format(Locale.US, "%.2fx", stretchScale));
    }
  }

  private void applyDividerHeightInput() {
    if (dividerHeightInput == null) {
      return;
    }
    try {
      float value = Float.parseFloat(dividerHeightInput.getText().toString().trim());
      if (Float.isNaN(value) || Float.isInfinite(value)) {
        updateDividerHeightInput();
        return;
      }
      dividerHeightDp = Math.max(0f, value);
      prefs.edit().putFloat(KEY_DIVIDER_DP, dividerHeightDp).apply();
      stageOverlay.setDividerHeightDp(dividerHeightDp, false);
      requestPreviewRefresh();
      updateDividerHeightInput();
    } catch (NumberFormatException ignored) {
      updateDividerHeightInput();
    }
  }

  private void seekToFraction(float fraction) {
    if (player == null) {
      return;
    }
    long duration = player.getDuration();
    if (duration <= 0 || duration == C.TIME_UNSET) {
      return;
    }
    long position = Math.max(0L, Math.min(duration, Math.round(duration * fraction)));
    player.seekTo(position);
  }

  private void seekBy(long deltaMs) {
    if (player == null || currentVideoUri == null) {
      return;
    }
    long duration = player.getDuration();
    long current = player.getCurrentPosition();
    long max = duration > 0 && duration != C.TIME_UNSET ? duration : Long.MAX_VALUE;
    long next = Math.max(0L, Math.min(max, current + deltaMs));
    player.seekTo(next);
  }

  private long currentStepMs() {
    switch (stepMode) {
      case 0:
        return frameStepMs;
      case 1:
        return 1000L;
      case 2:
        return 3000L;
      case 3:
      default:
        return 5000L;
    }
  }

  private void togglePlayback() {
    if (player == null || currentVideoUri == null) {
      return;
    }
    if (player.getPlaybackState() == Player.STATE_ENDED) {
      player.seekTo(0);
    }
    if (player.isPlaying()) {
      player.pause();
    } else {
      player.play();
    }
    updatePlayButton();
  }

  private void updatePlayButton() {
    if (playButton == null || player == null) {
      return;
    }
    playButton.setText(player.isPlaying() ? "II" : ">");
  }

  private void adjustStretch(float delta) {
    setStretchScale(Math.round((stretchScale + delta) * 100f) / 100f, true);
  }

  private void setStretchFromSlider(float fraction) {
    float value;
    if (fraction <= NEUTRAL_STRETCH_FRACTION) {
      value = MIN_STRETCH + (fraction / NEUTRAL_STRETCH_FRACTION) * (1f - MIN_STRETCH);
    } else {
      value =
          1f
              + ((fraction - NEUTRAL_STRETCH_FRACTION) / (1f - NEUTRAL_STRETCH_FRACTION))
                  * (MAX_STRETCH - 1f);
    }
    setStretchScale(Math.round(value * 100f) / 100f, false);
  }

  private void setStretchScale(float value, boolean updateSlider) {
    stretchScale = Math.max(MIN_STRETCH, Math.min(MAX_STRETCH, value));
    prefs.edit().putFloat(KEY_STRETCH, stretchScale).apply();
    stageOverlay.setStretchScale(stretchScale);
    updateStretchValueText();
    if (updateSlider && stretchSlider != null) {
      stretchSlider.setProgressFraction(stretchScaleToFraction(stretchScale));
    }
    requestPreviewRefresh();
  }

  private float stretchScaleToFraction(float value) {
    float clamped = Math.max(MIN_STRETCH, Math.min(MAX_STRETCH, value));
    if (clamped <= 1f) {
      return ((clamped - MIN_STRETCH) / (1f - MIN_STRETCH)) * NEUTRAL_STRETCH_FRACTION;
    }
    return NEUTRAL_STRETCH_FRACTION
        + ((clamped - 1f) / (MAX_STRETCH - 1f)) * (1f - NEUTRAL_STRETCH_FRACTION);
  }

  private void requestPreviewRefresh() {
    if (previewRefreshQueued) {
      return;
    }
    previewRefreshQueued = true;
    mainHandler.postDelayed(previewRefresh, 16);
  }

  private void refreshPreviewFrame() {
    if (player == null || currentVideoUri == null) {
      return;
    }
    stageOverlay.invalidate();
    playerView.invalidate();
    if (player.isPlaying()) {
      return;
    }
    long position = Math.max(0L, player.getCurrentPosition());
    long duration = player.getDuration();
    if (duration > 1 && duration != C.TIME_UNSET) {
      long nudgedPosition = position < duration - 1 ? position + 1 : Math.max(0L, position - 1);
      player.seekTo(nudgedPosition);
      mainHandler.postDelayed(
          () -> {
            if (player != null && currentVideoUri != null && !player.isPlaying()) {
              player.seekTo(position);
            }
          },
          18);
    } else {
      player.seekTo(position);
    }
  }

  private void openVideoPicker() {
    Intent fileIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
    fileIntent.setType("video/*");
    fileIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    Intent chooser = Intent.createChooser(fileIntent, "选择视频");
    if (Build.VERSION.SDK_INT >= 33) {
      Intent pickerIntent = new Intent(MediaStore.ACTION_PICK_IMAGES);
      pickerIntent.setType("video/*");
      pickerIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] {pickerIntent});
    }
    startActivityForResult(chooser, REQUEST_VIDEO);
  }

  private void openOutputDirectoryPicker() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    startActivityForResult(intent, REQUEST_OUTPUT_DIR);
  }

  private void startExport() {
    if (exporting || currentVideoUri == null) {
      return;
    }
    File exportDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "exports");
    if (!exportDir.exists() && !exportDir.mkdirs()) {
      toast("无法创建导出缓存目录");
      return;
    }
    String fileName =
        "ReBody_"
            + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
            + ".mp4";
    pendingExportFile = new File(exportDir, fileName);
    if (pendingExportFile.exists() && !pendingExportFile.delete()) {
      toast("无法覆盖旧缓存文件");
      return;
    }

    setExporting(true);
    setExportTextAnimated("0%");
    int exportBitrate = estimateHighQualityExportBitrate();
    VideoEncoderSettings videoEncoderSettings =
        new VideoEncoderSettings.Builder()
            .setBitrate(exportBitrate)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .setiFrameIntervalSeconds(1f)
            .build();
    DefaultEncoderFactory encoderFactory =
        new DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .build();
    FixedParameters fixedParameters = new FixedParameters(getSplitTopRatio(), stretchScale);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(currentVideoUri))
            .setEffects(
                new Effects(
                    Collections.emptyList(),
                    Collections.singletonList(new ReBodyVideoEffect(fixedParameters, true))))
            .build();
    transformer =
        new Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .addListener(
                new Transformer.Listener() {
                  @Override
                  public void onCompleted(Composition composition, ExportResult exportResult) {
                    setExportTextAnimated("保存");
                    copyCompletedExport();
                  }

                  @Override
                  public void onError(
                      Composition composition,
                      ExportResult exportResult,
                      ExportException exportException) {
                    finishExportWithError(exportException.getMessage());
                  }
                })
            .build();
    try {
      transformer.start(editedMediaItem, pendingExportFile.getAbsolutePath());
      mainHandler.post(exportProgressUpdater);
    } catch (RuntimeException e) {
      finishExportWithError(e.getMessage());
    }
  }

  private int estimateHighQualityExportBitrate() {
    int width = inputVideoWidth > 0 ? inputVideoWidth : 1080;
    int height =
        inputVideoHeight > 0
            ? inputVideoHeight
            : Math.max(2, Math.round(width / Math.max(0.2f, videoAspect)));
    float frameRate =
        inputVideoFrameRate > 0f
            ? inputVideoFrameRate
            : frameStepMs > 0L ? 1000f / frameStepMs : 30f;
    float splitTop = getSplitTopRatio();
    float outputHeightRatio = splitTop + (1f - splitTop) * stretchScale;
    int outputHeight = Math.max(2, Math.round(height * outputHeightRatio));
    long pixelRate = Math.round(width * (double) outputHeight * frameRate);

    long sourceBasedBitrate =
        inputVideoBitrate > 0 ? Math.round(inputVideoBitrate * 3.0d) : 0L;
    long pixelBasedBitrate = Math.round(pixelRate * 0.35d);
    long minimumBitrate = Math.max(2_500_000L, Math.round(pixelRate * 0.18d));
    long targetBitrate = Math.max(Math.max(sourceBasedBitrate, pixelBasedBitrate), minimumBitrate);
    return (int) Math.max(2_500_000L, Math.min(180_000_000L, targetBitrate));
  }

  private void copyCompletedExport() {
    File completedFile = pendingExportFile;
    Uri treeUri = outputTreeUri;
    new Thread(
            () -> {
              try {
                String destination = saveExportFile(completedFile, treeUri);
                mainHandler.post(() -> finishExportSuccessfully(destination));
              } catch (IOException | RuntimeException e) {
                mainHandler.post(() -> finishExportWithError(e.getMessage()));
              }
            },
            "ReBodyExportCopy")
        .start();
  }

  private String saveExportFile(File completedFile, @Nullable Uri treeUri) throws IOException {
    if (completedFile == null || !completedFile.exists()) {
      throw new IOException("导出文件不存在");
    }
    if (treeUri == null) {
      return completedFile.getAbsolutePath();
    }
    ContentResolver resolver = getContentResolver();
    Uri parentDocument =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    Uri outputUri =
        DocumentsContract.createDocument(resolver, parentDocument, "video/mp4", completedFile.getName());
    if (outputUri == null) {
      throw new IOException("无法在输出目录创建文件");
    }
    try (InputStream input = new FileInputStream(completedFile);
        OutputStream output = resolver.openOutputStream(outputUri, "w")) {
      if (output == null) {
        throw new IOException("无法写入输出文件");
      }
      byte[] buffer = new byte[1024 * 256];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
    }
    return outputUri.toString();
  }

  private void finishExportSuccessfully(String destination) {
    transformer = null;
    mainHandler.removeCallbacks(exportProgressUpdater);
    setExportTextAnimated("完成");
    toast("已导出");
    mainHandler.postDelayed(
        () -> {
          setExporting(false);
          setExportTextAnimated("导出");
        },
        1000);
  }

  private void finishExportWithError(@Nullable String message) {
    transformer = null;
    mainHandler.removeCallbacks(exportProgressUpdater);
    toast(message == null || message.isEmpty() ? "导出失败" : message);
    setExportTextAnimated("失败");
    mainHandler.postDelayed(
        () -> {
          setExporting(false);
          setExportTextAnimated("导出");
        },
        1000);
  }

  private void setExporting(boolean exporting) {
    this.exporting = exporting;
    stageOverlay.setLocked(exporting);
    updateControlsEnabled();
  }

  private void updateControlsEnabled() {
    boolean hasVideo = currentVideoUri != null;
    boolean enabled = !exporting;
    if (progressSlider != null) {
      progressSlider.setEnabled(enabled && hasVideo);
    }
    if (stretchSlider != null) {
      stretchSlider.setEnabled(enabled && hasVideo);
    }
    if (dividerHeightInput != null) {
      dividerHeightInput.setEnabled(enabled);
    }
    if (backButton != null) {
      backButton.setEnabled(enabled && hasVideo);
      playButton.setEnabled(enabled && hasVideo);
      forwardButton.setEnabled(enabled && hasVideo);
      stepButton.setEnabled(enabled);
      minusButton.setEnabled(enabled && hasVideo);
      plusButton.setEnabled(enabled && hasVideo);
      fileButton.setEnabled(enabled);
      outputDirButton.setEnabled(enabled);
      exportButton.setEnabled(enabled && hasVideo);
    }
  }

  private TextView makeButton(String text, int minWidthDp, String description) {
    TextView button = new TextView(this);
    button.setText(text);
    button.setGravity(Gravity.CENTER);
    button.setTextSize(14);
    button.setIncludeFontPadding(false);
    button.setTypeface(interMediumTypeface);
    button.setMinWidth(dp(minWidthDp));
    button.setMinHeight(dp(44));
    button.setPadding(dp(12), 0, dp(12), 0);
    button.setFocusable(true);
    button.setClickable(true);
    button.setContentDescription(description);
    button.setTextColor(buttonTextColors());
    button.setBackground(buttonBackground());
    return button;
  }

  private void bindRepeating(TextView button, Runnable action) {
    bindRepeating(button, action, 86);
  }

  private void bindRepeating(TextView button, Runnable action, long repeatDelayMs) {
    int longPressDelay = Math.max(260, ViewConfiguration.getLongPressTimeout() - 120);
    Runnable[] repeat = new Runnable[1];
    boolean[] repeated = new boolean[1];
    repeat[0] =
        new Runnable() {
          @Override
          public void run() {
            if (!button.isEnabled() || exporting) {
              return;
            }
            repeated[0] = true;
            action.run();
            mainHandler.postDelayed(this, repeatDelayMs);
          }
        };
    button.setOnTouchListener(
        (view, event) -> {
          if (!button.isEnabled() || exporting) {
            return true;
          }
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              repeated[0] = false;
              button.setPressed(true);
              mainHandler.postDelayed(repeat[0], longPressDelay);
              return true;
            case MotionEvent.ACTION_UP:
              mainHandler.removeCallbacks(repeat[0]);
              button.setPressed(false);
              if (!repeated[0]) {
                action.run();
              }
              button.performClick();
              return true;
            case MotionEvent.ACTION_CANCEL:
              mainHandler.removeCallbacks(repeat[0]);
              button.setPressed(false);
              return true;
            default:
              return true;
          }
        });
  }

  private Space space(int widthDp) {
    Space space = new Space(this);
    space.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), 1));
    return space;
  }

  private StateListDrawable buttonBackground() {
    StateListDrawable drawable = new StateListDrawable();
    drawable.addState(new int[] {-android.R.attr.state_enabled}, rounded(Color.rgb(222, 216, 204), Color.rgb(205, 196, 180), dp(12)));
    drawable.addState(new int[] {android.R.attr.state_pressed}, rounded(Color.rgb(214, 232, 226), Color.rgb(42, 111, 104), dp(12)));
    drawable.addState(new int[] {}, rounded(Color.rgb(255, 252, 246), Color.rgb(209, 198, 178), dp(12)));
    return drawable;
  }

  private GradientDrawable inputBackground() {
    return rounded(Color.rgb(255, 252, 246), Color.rgb(209, 198, 178), dp(10));
  }

  private GradientDrawable rounded(int fill, int stroke, int radiusPx) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setShape(GradientDrawable.RECTANGLE);
    drawable.setColor(fill);
    drawable.setCornerRadius(radiusPx);
    drawable.setStroke(dp(1), stroke);
    return drawable;
  }

  private ColorStateList buttonTextColors() {
    return new ColorStateList(
        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
        new int[] {Color.rgb(129, 122, 111), Color.rgb(25, 28, 32)});
  }

  private void setExportTextAnimated(String text) {
    if (exportButton == null || text.equals(exportText)) {
      return;
    }
    exportText = text;
    if (text.endsWith("%")) {
      exportButton.animate().cancel();
      exportButton.setAlpha(1f);
      exportButton.setText(text);
      return;
    }
    exportButton.animate().cancel();
    exportButton
        .animate()
        .alpha(0f)
        .setDuration(110)
        .withEndAction(
            () -> {
              exportButton.setText(text);
              exportButton.animate().alpha(1f).setDuration(150).start();
            })
        .start();
  }

  private void persistUriPermission(Uri uri, Intent data, int requestedFlags) {
    int flags = data.getFlags() & requestedFlags;
    if (flags == 0) {
      return;
    }
    try {
      getContentResolver().takePersistableUriPermission(uri, flags);
    } catch (SecurityException ignored) {
      // Photo Picker results are already usable for the current session and may not be persistable.
    }
  }

  private void hideKeyboard(View view) {
    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null) {
      imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static int parseInt(@Nullable String value, int fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static final class FixedParameters implements ReBodyVideoEffect.ParametersProvider {
    private final float splitTopRatio;
    private final float stretchScale;

    FixedParameters(float splitTopRatio, float stretchScale) {
      this.splitTopRatio = splitTopRatio;
      this.stretchScale = stretchScale;
    }

    @Override
    public float getSplitTopRatio() {
      return splitTopRatio;
    }

    @Override
    public float getStretchScale() {
      return stretchScale;
    }
  }
}
