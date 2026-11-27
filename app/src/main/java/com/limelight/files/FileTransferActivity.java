package com.limelight.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.ui.SideLanternTheme;
import com.limelight.utils.UiHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Bidirectional file transfer UI for a paired SideLantern host:
 * send photos/videos, send files, download computer files, active queue, and history.
 */
public class FileTransferActivity extends Activity implements FileTransferManager.Listener {
    public static final String EXTRA_HOST_UUID = "HostUuid";
    public static final String EXTRA_HOST_NAME = "HostName";

    private static final int REQ_PICK_PHOTOS = 1001;
    private static final int REQ_PICK_FILES = 1002;
    private static final int REQ_EXPORT_FILE = 1003;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_RECEIVED = 1;
    private static final int FILTER_SENT = 2;

    private String hostUuid;
    private String hostName;
    private ComputerDetails host;
    private FileTransferManager manager;
    private int historyFilter = FILTER_ALL;
    private File pendingExportFile;
    private boolean reloadScheduled;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView titleView;
    private TextView statusView;
    private LinearLayout sendPhotosAction;
    private LinearLayout sendFilesAction;
    private LinearLayout browseAction;
    private TextView activeHeader;
    private LinearLayout activeContainer;
    private TextView historyHeader;
    private TextView[] filterButtons = new TextView[3];
    private LinearLayout historyContainer;

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder local =
                    (ComputerManagerService.ComputerManagerBinder) binder;
            new Thread(() -> {
                local.waitForReady();
                managerBinder = local;
                ComputerDetails details = local.getComputer(hostUuid);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (details == null) {
                        finish();
                        return;
                    }
                    host = details;
                    if (details.name != null && !details.name.isEmpty()) {
                        titleView.setText(details.name);
                    }
                    attachManager(FileTransferClient.managerForHost(FileTransferActivity.this, details));
                });
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            managerBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        UiHelper.setLocale(this);

        hostUuid = getIntent().getStringExtra(EXTRA_HOST_UUID);
        hostName = getIntent().getStringExtra(EXTRA_HOST_NAME);
        if (hostUuid == null || hostUuid.isEmpty()) {
            finish();
            return;
        }

        buildContentView();
        UiHelper.notifyNewRootView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !SideLanternTheme.isDarkMode(this)) {
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    private void attachManager(FileTransferManager mgr) {
        if (manager != null) {
            manager.removeListener(this);
        }
        manager = mgr;
        if (manager != null) {
            manager.addListener(this);
            render();
            manager.refreshCapabilities(() -> mainHandler.post(this::render));
        }
    }

    private void buildContentView() {
        int bg = SideLanternTheme.background(this);
        int surface = SideLanternTheme.surface(this);
        int secSurface = SideLanternTheme.secondarySurface(this);
        int primary = SideLanternTheme.textPrimary(this);
        int secondary = SideLanternTheme.textSecondary(this);
        int blue = SideLanternTheme.accentBlue(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        // Top navigation bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(surface);
        topBar.setPadding(dp(8), dp(8), dp(8), dp(8));

        ImageButton closeBtn = new ImageButton(this);
        closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeBtn.setColorFilter(primary);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setContentDescription(getString(R.string.sl_ft_close));
        closeBtn.setOnClickListener(v -> finish());
        topBar.addView(closeBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        titleView = new TextView(this);
        titleView.setText(hostName != null ? hostName : getString(R.string.sl_ft_title));
        titleView.setTextColor(primary);
        titleView.setTextSize(19);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(titleView, titleLp);

        ImageButton refreshBtn = new ImageButton(this);
        refreshBtn.setImageResource(android.R.drawable.ic_popup_sync);
        refreshBtn.setColorFilter(primary);
        refreshBtn.setBackgroundColor(Color.TRANSPARENT);
        refreshBtn.setOnClickListener(v -> {
            if (manager != null) {
                manager.refreshCapabilities(() -> mainHandler.post(this::render));
            }
        });
        topBar.addView(refreshBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(32));

        // Top action panel (3 circular buttons)
        LinearLayout actionCard = new LinearLayout(this);
        actionCard.setOrientation(LinearLayout.VERTICAL);
        actionCard.setBackgroundColor(secSurface);
        actionCard.setPadding(dp(12), dp(16), dp(12), dp(16));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        sendPhotosAction = createActionCircle(android.R.drawable.ic_menu_gallery, getString(R.string.sl_ft_send_photos), v -> pickPhotos());
        sendFilesAction = createActionCircle(android.R.drawable.ic_menu_upload, getString(R.string.sl_ft_send_files), v -> pickFiles());
        browseAction = createActionCircle(android.R.drawable.ic_menu_save, getString(R.string.sl_ft_browse), v -> openRemoteBrowser());
        actionRow.addView(sendPhotosAction, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actionRow.addView(sendFilesAction, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actionRow.addView(browseAction, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actionCard.addView(actionRow);

        statusView = new TextView(this);
        statusView.setTextColor(secondary);
        statusView.setTextSize(13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(12), dp(10), dp(12), 0);
        actionCard.addView(statusView);
        content.addView(actionCard);

        // Active transfers section
        activeHeader = new TextView(this);
        activeHeader.setTextColor(secondary);
        activeHeader.setTextSize(14);
        activeHeader.setTypeface(null, Typeface.BOLD);
        activeHeader.setPadding(dp(18), dp(18), dp(18), dp(8));
        content.addView(activeHeader);

        activeContainer = new LinearLayout(this);
        activeContainer.setOrientation(LinearLayout.VERTICAL);
        activeContainer.setPadding(dp(14), 0, dp(14), 0);
        content.addView(activeContainer);

        // History header row
        LinearLayout historyHeaderRow = new LinearLayout(this);
        historyHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        historyHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        historyHeaderRow.setPadding(dp(18), dp(20), dp(14), dp(8));

        historyHeader = new TextView(this);
        historyHeader.setTextColor(secondary);
        historyHeader.setTextSize(14);
        historyHeader.setTypeface(null, Typeface.BOLD);
        historyHeaderRow.addView(historyHeader, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clearBtn = new TextView(this);
        clearBtn.setText(getString(R.string.sl_ft_clear_history));
        clearBtn.setTextColor(blue);
        clearBtn.setTextSize(14);
        clearBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        clearBtn.setOnClickListener(v -> confirmClearHistory());
        historyHeaderRow.addView(clearBtn);
        content.addView(historyHeaderRow);

        // Filter bar (All / Received / Sent)
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setBackground(SideLanternTheme.roundedRect(secSurface, dp(10)));
        filterBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filterLp.setMargins(dp(16), 0, dp(16), dp(10));

        String[] labels = new String[]{
                getString(R.string.sl_ft_all),
                getString(R.string.sl_ft_received),
                getString(R.string.sl_ft_sent)
        };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView tab = new TextView(this);
            tab.setText(labels[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(13);
            tab.setPadding(dp(8), dp(8), dp(8), dp(8));
            tab.setOnClickListener(v -> {
                historyFilter = idx;
                render();
            });
            filterButtons[i] = tab;
            filterBar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        content.addView(filterBar, filterLp);

        historyContainer = new LinearLayout(this);
        historyContainer.setOrientation(LinearLayout.VERTICAL);
        historyContainer.setPadding(dp(14), 0, dp(14), 0);
        content.addView(historyContainer);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private LinearLayout createActionCircle(int iconRes, String label, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(6), dp(6), dp(6), dp(6));

        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(SideLanternTheme.roundedRect(SideLanternTheme.surface(this), dp(34)));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(SideLanternTheme.accentBlue(this));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER);
        circle.addView(icon, iconLp);
        item.addView(circle, new LinearLayout.LayoutParams(dp(68), dp(68)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(SideLanternTheme.textPrimary(this));
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(2);
        tv.setPadding(dp(2), dp(8), dp(2), 0);
        item.addView(tv);

        item.setOnClickListener(listener);
        return item;
    }

    private void render() {
        if (isFinishing()) return;
        int surface = SideLanternTheme.surface(this);
        int primary = SideLanternTheme.textPrimary(this);
        int secondary = SideLanternTheme.textSecondary(this);

        for (int i = 0; i < 3; i++) {
            boolean selected = historyFilter == i;
            filterButtons[i].setBackground(selected ? SideLanternTheme.roundedRect(surface, dp(8)) : null);
            filterButtons[i].setTextColor(selected ? primary : secondary);
            filterButtons[i].setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }

        if (manager == null) {
            setActionEnabled(sendPhotosAction, false);
            setActionEnabled(sendFilesAction, false);
            setActionEnabled(browseAction, false);
            statusView.setText(R.string.sl_ft_checking);
            statusView.setVisibility(View.VISIBLE);
            return;
        }

        FileTransferProtocol.Capabilities caps = manager.getCapabilities();
        FileTransferProtocol.FileTransferException capErr = manager.getCapabilityError();
        boolean ready = caps != null && caps.enabled && capErr == null;
        setActionEnabled(sendPhotosAction, ready && caps.upload);
        setActionEnabled(sendFilesAction, ready && caps.upload);
        setActionEnabled(browseAction, ready && !caps.roots.isEmpty());

        String statusText;
        if (capErr != null) {
            statusText = getString(FileTransferProtocol.getErrorMessageResId(capErr.reason));
        } else if (!manager.isCapabilitiesProbed()) {
            statusText = getString(R.string.sl_ft_checking);
        } else if (!ready) {
            statusText = getString(R.string.sl_ft_error_file_transfer_disabled);
        } else if (!caps.upload) {
            statusText = getString(R.string.sl_ft_error_upload_disabled);
        } else if (caps.roots.isEmpty()) {
            statusText = getString(R.string.sl_ft_no_shares);
        } else {
            statusText = "";
        }
        statusView.setText(statusText);
        statusView.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);

        List<FileTransferManager.Task> active = new ArrayList<>();
        List<FileTransferManager.Task> history = new ArrayList<>();
        for (FileTransferManager.Task task : manager.getTasks()) {
            boolean finished = task.state == FileTransferManager.State.COMPLETED
                    || task.state == FileTransferManager.State.CANCELLED;
            if (!finished) {
                active.add(task);
            } else if (historyFilter == FILTER_ALL
                    || (task.state == FileTransferManager.State.COMPLETED
                    && ((historyFilter == FILTER_RECEIVED && !task.upload)
                    || (historyFilter == FILTER_SENT && task.upload)))) {
                history.add(task);
            }
        }
        Collections.sort(history, (a, b) -> Long.compare(b.dateMs, a.dateMs));

        activeHeader.setText(getString(R.string.sl_ft_active_count, active.size()));
        activeHeader.setVisibility(active.isEmpty() ? View.GONE : View.VISIBLE);
        activeContainer.removeAllViews();
        for (FileTransferManager.Task t : active) {
            activeContainer.addView(createTaskCard(t));
        }

        historyHeader.setText(getString(R.string.sl_ft_history_count, history.size()));
        historyContainer.removeAllViews();
        if (history.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.sl_ft_empty_history);
            empty.setTextColor(secondary);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(28), dp(16), dp(28));
            historyContainer.addView(empty);
        } else {
            for (FileTransferManager.Task t : history) {
                historyContainer.addView(createTaskCard(t));
            }
        }
    }

    private View createTaskCard(final FileTransferManager.Task task) {
        int surface = SideLanternTheme.surface(this);
        int primary = SideLanternTheme.textPrimary(this);
        int secondary = SideLanternTheme.textSecondary(this);
        int blue = SideLanternTheme.accentBlue(this);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(SideLanternTheme.roundedRect(surface, dp(12)));
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        TextView badge = new TextView(this);
        badge.setText(task.upload ? "↑" : "↓");
        badge.setTextSize(16);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        int badgeColor = task.state == FileTransferManager.State.COMPLETED
                ? SideLanternTheme.STATUS_GREEN : blue;
        badge.setBackground(SideLanternTheme.roundedRect(badgeColor, dp(18)));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        badgeLp.rightMargin = dp(12);
        card.addView(badge, badgeLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView nameTv = new TextView(this);
        nameTv.setText(task.name);
        nameTv.setTextColor(primary);
        nameTv.setTextSize(15);
        nameTv.setMaxLines(1);
        nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        body.addView(nameTv);

        boolean finished = task.state == FileTransferManager.State.COMPLETED
                || task.state == FileTransferManager.State.CANCELLED;
        TextView detailTv = new TextView(this);
        detailTv.setTextColor(secondary);
        detailTv.setTextSize(12);
        detailTv.setPadding(0, dp(3), 0, 0);
        detailTv.setText(formatTaskDetail(task, finished));
        body.addView(detailTv);

        if (!finished && task.state != FileTransferManager.State.PREPARING) {
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(1000);
            int progress = task.size > 0 ? (int) Math.min(1000L, (task.offset * 1000L) / task.size) : 0;
            pb.setProgress(progress);
            LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
            pbLp.topMargin = dp(6);
            body.addView(pb, pbLp);
        }

        card.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!finished) {
            if (task.state != FileTransferManager.State.PREPARING) {
                boolean canPause = task.state == FileTransferManager.State.RUNNING
                        || task.state == FileTransferManager.State.QUEUED;
                Button toggleBtn = new Button(this);
                toggleBtn.setAllCaps(false);
                toggleBtn.setTextSize(12);
                toggleBtn.setText(canPause ? R.string.sl_ft_pause
                        : (task.state == FileTransferManager.State.FAILED ? R.string.sl_ft_retry : R.string.sl_ft_resume));
                toggleBtn.setTextColor(blue);
                toggleBtn.setBackgroundColor(Color.TRANSPARENT);
                toggleBtn.setMinWidth(0);
                toggleBtn.setMinimumWidth(0);
                toggleBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
                toggleBtn.setOnClickListener(v -> {
                    if (canPause) manager.pauseTask(task.identifier);
                    else manager.resumeTask(task.identifier);
                });
                card.addView(toggleBtn);
            }
            Button cancelBtn = new Button(this);
            cancelBtn.setAllCaps(false);
            cancelBtn.setTextSize(12);
            cancelBtn.setText(R.string.sl_ft_cancel);
            cancelBtn.setTextColor(SideLanternTheme.DANGER_RED);
            cancelBtn.setBackgroundColor(Color.TRANSPARENT);
            cancelBtn.setMinWidth(0);
            cancelBtn.setMinimumWidth(0);
            cancelBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
            cancelBtn.setOnClickListener(v -> manager.cancelTask(task.identifier));
            card.addView(cancelBtn);
        } else if (task.state == FileTransferManager.State.COMPLETED && !task.upload) {
            card.setOnClickListener(v -> showReceivedFileActions(task));
        }

        return card;
    }

    private String formatTaskDetail(FileTransferManager.Task task, boolean finished) {
        String stateText = getStateLabel(task);
        if (task.state == FileTransferManager.State.PREPARING) {
            return stateText;
        }
        String sizeStr = Formatter.formatFileSize(this, task.size);
        if (finished) {
            String dateStr = DateFormat.getDateFormat(this).format(new Date(task.dateMs));
            String tail = task.state == FileTransferManager.State.CANCELLED
                    ? stateText
                    : getString(task.upload ? R.string.sl_ft_sent : R.string.sl_ft_received);
            return dateStr + " · " + sizeStr + " · " + tail;
        }
        String offsetStr = Formatter.formatFileSize(this, task.offset);
        String line = stateText + " · " + offsetStr + " / " + sizeStr;
        if (task.error != null) {
            line += "\n" + getString(FileTransferProtocol.getErrorMessageResId(task.error.reason));
        }
        return line;
    }

    private String getStateLabel(FileTransferManager.Task task) {
        switch (task.state) {
            case PREPARING: return getString(R.string.sl_ft_state_preparing);
            case QUEUED: return getString(R.string.sl_ft_state_queued);
            case RUNNING: return getString(task.upload ? R.string.sl_ft_state_sending : R.string.sl_ft_state_receiving);
            case PAUSED: return getString(R.string.sl_ft_state_paused);
            case FAILED: return getString(R.string.sl_ft_state_failed);
            case COMPLETED: return getString(R.string.sl_ft_state_completed);
            case CANCELLED: return getString(R.string.sl_ft_state_cancelled);
            default: return "";
        }
    }

    private void showReceivedFileActions(final FileTransferManager.Task task) {
        if (task.localFile == null || !task.localFile.isFile()) {
            Toast.makeText(this, R.string.sl_ft_error_local_file_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[]{
                getString(R.string.sl_ft_preview),
                getString(R.string.sl_ft_save_files),
                getString(R.string.sl_ft_share)
        };
        new AlertDialog.Builder(this)
                .setTitle(task.name)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) previewFile(task.localFile);
                    else if (which == 1) exportFile(task.localFile);
                    else if (which == 2) shareFile(task.localFile);
                })
                .setNegativeButton(R.string.sl_cancel, null)
                .show();
    }

    private void previewFile(File file) {
        Uri uri = SideLanternFileProvider.getUriForFile(this, file);
        if (uri == null) return;
        String mime = getContentResolver().getType(uri);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime != null ? mime : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            shareFile(file);
        }
    }

    private void exportFile(File file) {
        pendingExportFile = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        startActivityForResult(intent, REQ_EXPORT_FILE);
    }

    private void shareFile(File file) {
        Uri uri = SideLanternFileProvider.getUriForFile(this, file);
        if (uri == null) return;
        String mime = getContentResolver().getType(uri);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mime != null ? mime : "*/*");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.sl_ft_share)));
    }

    private void pickPhotos() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQ_PICK_PHOTOS);
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQ_PICK_FILES);
    }

    private void openRemoteBrowser() {
        if (host == null) return;
        Intent intent = new Intent(this, RemoteFilesActivity.class);
        intent.putExtra(EXTRA_HOST_UUID, host.uuid);
        intent.putExtra(EXTRA_HOST_NAME, host.name);
        startActivity(intent);
    }

    private void confirmClearHistory() {
        if (manager == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.sl_ft_clear_history)
                .setMessage(R.string.sl_ft_clear_history_confirm)
                .setNegativeButton(R.string.sl_cancel, null)
                .setPositiveButton(R.string.sl_ft_clear, (d, w) -> manager.clearHistory())
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if ((requestCode == REQ_PICK_PHOTOS || requestCode == REQ_PICK_FILES) && manager != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (final Uri uri : uris) {
                String name = resolveDisplayName(uri);
                manager.enqueueUploadStream(() -> {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("Cannot open URI");
                    return in;
                }, name, err -> {
                    if (err != null) {
                        mainHandler.post(() -> Toast.makeText(
                                FileTransferActivity.this,
                                FileTransferProtocol.getErrorMessageResId(err.reason),
                                Toast.LENGTH_LONG).show());
                    }
                });
            }
        } else if (requestCode == REQ_EXPORT_FILE && pendingExportFile != null && data.getData() != null) {
            final File src = pendingExportFile;
            final Uri dst = data.getData();
            pendingExportFile = null;
            new Thread(() -> {
                boolean ok = false;
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = getContentResolver().openOutputStream(dst)) {
                    if (out != null) {
                        byte[] buf = new byte[32768];
                        int r;
                        while ((r = in.read(buf)) >= 0) {
                            out.write(buf, 0, r);
                        }
                        ok = true;
                    }
                } catch (IOException ignored) {
                }
                final boolean success = ok;
                mainHandler.post(() -> Toast.makeText(
                        FileTransferActivity.this,
                        success ? R.string.sl_ft_saved : R.string.sl_ft_error_file_io_error,
                        Toast.LENGTH_SHORT).show());
            }).start();
        }
    }

    private String resolveDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        if (!FileTransferProtocol.isValidName(name)) {
            name = uri.getLastPathSegment();
            if (name != null) {
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
                if (slash >= 0 && slash + 1 < name.length()) {
                    name = name.substring(slash + 1);
                }
            }
        }
        if (!FileTransferProtocol.isValidName(name)) {
            name = "file_" + System.currentTimeMillis();
        }
        return name;
    }

    private void setActionEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.4f);
    }

    @Override
    public void onFileTransferChanged(FileTransferManager mgr) {
        if (reloadScheduled) return;
        reloadScheduled = true;
        mainHandler.postDelayed(() -> {
            reloadScheduled = false;
            render();
        }, 120);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (manager != null) {
            render();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (manager != null) {
            manager.removeListener(this);
        }
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    private int dp(float v) {
        return SideLanternTheme.dp(this, v);
    }
}
