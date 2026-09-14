package com.example.cleanrecovery.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.download.YtDlpDownloadService;
import com.example.cleanrecovery.download.YtDlpMedia;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import java.io.File;
import java.util.*;

/** Link batches -> per-item format selection -> sequential foreground downloads. */
public final class UniversalDownloadActivity extends Activity {
    public static final String EXTRA_URL = "extra_url";
    private EditText input;
    private Button resolve, start, update, retry;
    private ImageButton pause, cancel;
    private ImageButton paste, clear;
    private LinearLayout qualities, entries;
    private View status, actions;
    private TextView message, progressText;
    private ProgressBar progress;
    private String renderedKey = "";
    private final YtDlpDownloadService.Listener listener = this::render;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); YtDlpDownloadService.restore(this);
        SystemUiHelper.apply(this); setContentView(R.layout.activity_universal_download);
        input = findViewById(R.id.universal_url_input);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true); input.setHorizontallyScrolling(true); input.setHint("粘贴链接或分享文案");
        paste = findViewById(R.id.universal_paste_button); clear = findViewById(R.id.universal_clear_button);
        if (Build.VERSION.SDK_INT >= 26) {
            paste.setTooltipText("粘贴链接"); clear.setTooltipText("清空输入");
        } else {
            for (ImageButton action : new ImageButton[]{paste, clear}) action.setOnLongClickListener(v -> {
                Toast.makeText(this, v.getContentDescription(), Toast.LENGTH_SHORT).show(); return true;
            });
        }
        paste.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            android.content.ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) { Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show(); return; }
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            int from = Math.max(0, input.getSelectionStart()), to = Math.max(from, input.getSelectionEnd());
            input.getText().replace(from, to, text == null ? "" : text);
            input.setSelection(input.length());
        });
        clear.setOnClickListener(v -> input.setText(""));
        resolve = findViewById(R.id.universal_download_button); resolve.setText("解析链接");
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { updateInputActions(); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        start = findViewById(R.id.universal_start_download_button); start.setText("下载所选条目");
        pause = findViewById(R.id.universal_pause_button); cancel = findViewById(R.id.universal_cancel_button);
        qualities = findViewById(R.id.universal_quality_card); status = findViewById(R.id.universal_status_card);
        actions = findViewById(R.id.universal_action_buttons); message = findViewById(R.id.universal_status_message);
        progressText = findViewById(R.id.universal_progress_text); progress = findViewById(R.id.universal_progress_bar);
        findViewById(R.id.universal_speed_text).setVisibility(View.GONE);
        findViewById(R.id.universal_status_title).setVisibility(View.GONE);
        for (int id : new int[]{R.id.universal_quality_video_group, R.id.universal_quality_audio_group, R.id.universal_quality_video_title, R.id.universal_quality_audio_title}) findViewById(id).setVisibility(View.GONE);
        entries = new LinearLayout(this); entries.setOrientation(LinearLayout.VERTICAL);
        qualities.addView(entries, qualities.indexOfChild(start));
        findViewById(R.id.universal_back_button).setOnClickListener(v -> finish());
        resolve.setOnClickListener(v -> resolve()); start.setOnClickListener(v -> download());
        pause.setOnClickListener(v -> {
            if (YtDlpDownloadService.state().phase == YtDlpDownloadService.Phase.PAUSED) command(YtDlpDownloadService.DOWNLOAD, null);
            else command(YtDlpDownloadService.PAUSE, null);
        });
        cancel.setOnClickListener(v -> command(YtDlpDownloadService.CANCEL, null));
        update = secondary("更新引擎");
        update.setOnClickListener(v -> command(YtDlpDownloadService.UPDATE, null)); ((LinearLayout) status).addView(update);
        retry = secondary("重试失败项");
        retry.setOnClickListener(v -> command(YtDlpDownloadService.RETRY_RESOLVE, null)); ((LinearLayout) status).addView(retry);
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null && Intent.ACTION_SEND.equals(getIntent().getAction())) url = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (url == null && getIntent().getData() != null) url = getIntent().getDataString();
        if (url != null && saved == null && !YtDlpDownloadService.state().busy()) { input.setText(url); resolve(); }
        else if (saved == null) input.setText(YtDlpDownloadService.state().url);
    }
    private void resolve() {
        try {
            List<String> urls = YtDlpMedia.normalizeUrls(input.getText().toString());
            String text = android.text.TextUtils.join("\n", urls); input.setText(text);
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
            command(YtDlpDownloadService.RESOLVE, text);
        } catch (IllegalArgumentException error) { showError(error.getMessage()); }
    }
    private void download() {
        File configured = new com.example.cleanrecovery.ui.browser.BrowserPrefs(this).downloadDirFile();
        boolean privateDirectory = configured != null && (inside(configured, getFilesDir()) || inside(configured, getCacheDir())
                || inside(configured, getExternalFilesDir(null)));
        if (!privateDirectory && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            showError("请授予文件访问权限，然后点击下载所选条目");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception ignored) { showError("请在系统设置中授予所有文件访问权限"); }
            return;
        }
        if (!privateDirectory && Build.VERSION.SDK_INT <= 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 31); return;
        }
        command(YtDlpDownloadService.DOWNLOAD, null);
    }
    private boolean inside(File directory, File root) {
        if (root == null) return false;
        try { return directory.getCanonicalPath().equals(root.getCanonicalPath()) || directory.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator); }
        catch (Exception ignored) { return false; }
    }
    private void command(String action, String value) {
        Intent intent = new Intent(this, YtDlpDownloadService.class).setAction(action);
        if (YtDlpDownloadService.RESOLVE.equals(action)) intent.putExtra("url", value);
        try {
            if (YtDlpDownloadService.CANCEL.equals(action) || YtDlpDownloadService.PAUSE.equals(action)) startService(intent);
            else {
                ContextCompat.startForegroundService(this, intent); resolve.setEnabled(false); start.setEnabled(false);
            }
        } catch (Exception error) { showError("无法启动下载服务：" + error.getMessage()); }
    }
    private void render() {
        YtDlpDownloadService.State state = YtDlpDownloadService.state(); boolean busy = state.busy();
        if (busy && qualityDialog != null) qualityDialog.dismiss();
        updateInputActions(); input.setEnabled(!busy); paste.setEnabled(!busy); update.setEnabled(!busy);
        resolve.setText(state.phase == YtDlpDownloadService.Phase.RESOLVING ? "正在解析…" : "解析链接");
        boolean hasError = state.phase == YtDlpDownloadService.Phase.ERROR;
        boolean pending = false;
        StringBuilder key = new StringBuilder(state.phase.name());
        for (YtDlpDownloadService.Job job : state.jobs) {
            key.append(job.url).append(System.identityHashCode(job.media)).append(job.error).append(job.path);
            hasError |= !job.error.isEmpty();
            pending |= job.media != null && job.choice != null && job.path == null;
        }
        start.setEnabled(!busy && pending); start.setVisibility(pending && !busy && state.phase != YtDlpDownloadService.Phase.PAUSED ? View.VISIBLE : View.GONE);
        int selectedCount = 0;
        for (YtDlpDownloadService.Job job : state.jobs) if (job.media != null && job.choice != null && job.path == null) selectedCount++;
        start.setText(selectedCount > 1 ? "下载（" + selectedCount + "）" : "开始下载");
        update.setVisibility(hasError ? View.VISIBLE : View.GONE);
        retry.setVisibility(hasError && !state.jobs.isEmpty() ? View.VISIBLE : View.GONE); retry.setEnabled(!busy);

        status.setVisibility(state.message.isEmpty() || state.phase == YtDlpDownloadService.Phase.READY && !hasError ? View.GONE : View.VISIBLE);
        message.setText(busy ? state.phase == YtDlpDownloadService.Phase.RESOLVING ? "正在解析链接…"
                : state.phase == YtDlpDownloadService.Phase.DOWNLOADING ? state.message : "正在处理…"
                : hasError ? "部分链接未完成，请重试" : state.phase == YtDlpDownloadService.Phase.PAUSED ? "已暂停" : state.message);
        progress.setVisibility(busy || state.phase == YtDlpDownloadService.Phase.PAUSED ? View.VISIBLE : View.GONE); progress.setIndeterminate(state.progress < 0);
        if (state.progress >= 0) progress.setProgress(state.progress);
        com.example.cleanrecovery.download.YtDlpTransferProgress transfer = state.transfer;
        progressText.setText(transfer != null && (busy || state.phase == YtDlpDownloadService.Phase.PAUSED)
                ? android.text.format.Formatter.formatShortFileSize(this, transfer.downloaded) + " / "
                + (transfer.total > 0 ? (transfer.estimated ? "约 " : "") + android.text.format.Formatter.formatShortFileSize(this, transfer.total) : "总大小未知")
                + (state.progress >= 0 ? "  ·  " + state.progress + "%" : "") : "");
        boolean paused = state.phase == YtDlpDownloadService.Phase.PAUSED;
        actions.setVisibility(busy || paused ? View.VISIBLE : View.GONE);
        pause.setVisibility(state.phase == YtDlpDownloadService.Phase.DOWNLOADING || paused ? View.VISIBLE : View.GONE);
        pause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        pause.setContentDescription(paused ? "继续下载" : "暂停下载"); cancel.setEnabled(state.phase != YtDlpDownloadService.Phase.STOPPING);
        if (Build.VERSION.SDK_INT >= 26) { pause.setTooltipText(pause.getContentDescription()); cancel.setTooltipText("取消下载"); }
        qualities.setVisibility(state.jobs.isEmpty() ? View.GONE : View.VISIBLE);
        if (!renderedKey.equals(key.toString())) { renderedKey = key.toString(); renderJobs(state.jobs, busy); }
    }
    private void renderJobs(List<YtDlpDownloadService.Job> jobs, boolean busy) {
        entries.removeAllViews();
        for (YtDlpDownloadService.Job job : jobs) {
            TextView title = new TextView(this); title.setText(job.media == null ? job.url : job.media.title);
            title.setTextColor(getColor(R.color.text_primary)); title.setTextSize(15); title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(0, dp(16), 0, dp(10)); entries.addView(title);
            if (job.path != null) {
                Button open = secondary("打开文件");
                open.setOnClickListener(v -> openFile(job.path)); entries.addView(open);
                continue;
            }
            if (!job.error.isEmpty()) {
                Button details = secondary("查看失败原因"); details.setTextColor(getColor(R.color.accent_red));
                details.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setTitle("未能完成")
                        .setMessage(job.error).setPositiveButton("知道了", null).show()); entries.addView(details);
                Button browse = secondary("打开原链接"); browse.setEnabled(!busy);
                browse.setOnClickListener(v -> startActivity(new Intent(this, BrowserActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(job.url)))); entries.addView(browse);
            }
            if (job.media == null) continue;
            Button selection = secondary((job.choice == null ? "选择画质" : (job.choice.audioOnly ? "音频 · " : "") + friendlyLabel(job.choice.label)) + "  ›");
            selection.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            selection.setPadding(dp(14), 0, dp(14), 0); selection.setTag(job);
            selection.setContentDescription("选择画质：" + (job.media == null ? job.url : job.media.title));
            selection.setEnabled(!busy); selection.setOnClickListener(v -> showQuality(job)); entries.addView(selection);
        }
    }
    private android.app.Dialog qualityDialog;
    private void showQuality(YtDlpDownloadService.Job job) {
        if (job.media == null || YtDlpDownloadService.state().busy()) return;
        android.app.Dialog dialog = new android.app.Dialog(this);
        qualityDialog = dialog; dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(8)); card.setBackgroundResource(R.drawable.bg_download_card);
        TextView heading = new TextView(this); heading.setText("选择画质"); heading.setTextSize(16);
        heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setTextColor(getColor(R.color.text_primary));
        heading.setPadding(dp(8), dp(8), dp(8), dp(16)); card.addView(heading);
        RadioGroup group = new RadioGroup(this);
        int selected = -1;
        for (List<YtDlpMedia.Choice> choices : Arrays.asList(job.media.videos, job.media.audios)) for (YtDlpMedia.Choice choice : choices) {
            RadioButton option = radio((choice.audioOnly ? "音频 · " : "") + friendlyLabel(choice.label), choice);
            group.addView(option);
            if (job.choice != null && job.choice.selector.equals(choice.selector)) selected = option.getId();
        }
        group.check(selected);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(group);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        Button close = secondary("取消"); close.setOnClickListener(v -> dialog.dismiss()); card.addView(close);
        dialog.setContentView(card); dialog.setOnDismissListener(d -> { if (qualityDialog == dialog) qualityDialog = null; });
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setOnClickListener(option -> {
            if (!YtDlpDownloadService.state().busy()) {
                job.choice = (YtDlpMedia.Choice) option.getTag(); dialog.dismiss(); renderedKey = ""; render();
            }
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = android.view.Gravity.BOTTOM; params.y = dp(12); params.dimAmount = .2f;
            window.setAttributes(params); window.setLayout(getResources().getDisplayMetrics().widthPixels - dp(16), -2);
        }
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * .55f);
        scroll.post(() -> { if (scroll.getHeight() > maxHeight) { scroll.getLayoutParams().height = maxHeight; scroll.requestLayout(); } });
    }
    private RadioButton radio(String label, YtDlpMedia.Choice choice) {
        RadioButton button = new RadioButton(this); button.setId(View.generateViewId()); button.setText(label); button.setTag(choice);
        button.setTextColor(getColor(R.color.text_primary)); button.setTextSize(14); button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(6), dp(8), dp(6)); button.setBackgroundResource(R.drawable.bg_download_choice);
        button.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{getColor(R.color.text_primary), getColor(R.color.text_muted)}));
        RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(-1, -2); params.bottomMargin = dp(6); button.setLayoutParams(params);
        return button;
    }
    private void updateInputActions() {
        boolean enabled = !YtDlpDownloadService.state().busy() && !input.getText().toString().trim().isEmpty();
        resolve.setEnabled(enabled); resolve.setAlpha(enabled ? 1f : .45f); clear.setEnabled(enabled);
        boolean empty = input.getText().toString().trim().isEmpty();
        paste.setVisibility(empty ? View.VISIBLE : View.GONE); clear.setVisibility(empty ? View.GONE : View.VISIBLE);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Button secondary(String text) {
        Button button = new Button(this); button.setText(text); button.setTextSize(14); button.setAllCaps(false);
        button.setTextColor(getColor(R.color.text_secondary)); button.setBackgroundResource(R.drawable.bg_button_secondary);
        button.setMinHeight(dp(48)); button.setStateListAnimator(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(6); button.setLayoutParams(params);
        return button;
    }
    private String friendlyLabel(String label) {
        return label.replace(" · null", "").replace(" · none", "").replaceAll("avc1[^ ·]*|h264", "H.264").replaceAll("av01[^ ·]*", "AV1")
                .replaceAll("vp09[^ ·]*|vp9", "VP9").replaceAll("hev1[^ ·]*|hvc1[^ ·]*|hevc|bytevc1", "H.265");
    }
    private void openFile(String path) {
        FileBrowserActivity.open(this, new File(path).getParentFile());
    }
    private void showError(String text) { status.setVisibility(View.VISIBLE); message.setText(text); progress.setVisibility(View.GONE); }
    @Override protected void onStart() { super.onStart(); YtDlpDownloadService.listen(listener); render(); }
    @Override protected void onDestroy() { if (qualityDialog != null) qualityDialog.dismiss(); super.onDestroy(); }
    @Override protected void onStop() { YtDlpDownloadService.unlisten(listener); super.onStop(); }
}
