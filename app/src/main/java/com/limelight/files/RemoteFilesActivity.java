package com.limelight.files;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.ui.SideLanternTheme;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remote shared-folder browser for SideLantern file downloads.
 * Wide screens show shared roots and directory contents side-by-side;
 * compact screens navigate between shared locations and directories.
 */
public class RemoteFilesActivity extends Activity {
    private String hostUuid;
    private FileTransferManager manager;
    private List<FileTransferProtocol.Root> roots = Collections.emptyList();
    private FileTransferProtocol.Root selectedRoot;
    private String currentPath = "";
    private final List<FileTransferProtocol.Entry> entries = new ArrayList<>();
    private final Set<String> selectedFiles = new HashSet<>();
    private Integer nextOffset;
    private boolean truncated;
    private boolean loading;
    private String errorMessage;
    private int generation;
    private boolean wideLayout;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView selectAllBtn;
    private LinearLayout workspace;
    private ScrollView rootsScroll;
    private LinearLayout rootsContainer;
    private LinearLayout filesPane;
    private TextView pathButton;
    private LinearLayout filesContainer;
    private LinearLayout bottomBar;
    private Button downloadButton;

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
                    manager = FileTransferClient.managerForHost(RemoteFilesActivity.this, details);
                    if (manager != null && manager.getCapabilities() != null) {
                        roots = manager.getCapabilities().roots;
                    }
                    render();
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

        hostUuid = getIntent().getStringExtra(FileTransferActivity.EXTRA_HOST_UUID);
        if (hostUuid == null || hostUuid.isEmpty()) {
            finish();
            return;
        }

        wideLayout = getResources().getConfiguration().screenWidthDp >= 700;
        buildContentView();
        UiHelper.notifyNewRootView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !SideLanternTheme.isDarkMode(this)) {
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean nowWide = newConfig.screenWidthDp >= 700;
        if (nowWide != wideLayout) {
            wideLayout = nowWide;
            updatePaneLayout();
            render();
        }
    }

    private void buildContentView() {
        int bg = SideLanternTheme.background(this);
        int surface = SideLanternTheme.surface(this);
        int secSurface = SideLanternTheme.secondarySurface(this);
        int primary = SideLanternTheme.textPrimary(this);
        int blue = SideLanternTheme.accentBlue(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(surface);
        topBar.setPadding(dp(8), dp(8), dp(12), dp(8));

        ImageButton backBtn = new ImageButton(this);
        backBtn.setImageResource(android.R.drawable.ic_media_previous);
        backBtn.setColorFilter(primary);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setOnClickListener(v -> handleBack());
        topBar.addView(backBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView titleTv = new TextView(this);
        titleTv.setText(R.string.sl_ft_browse_title);
        titleTv.setTextColor(primary);
        titleTv.setTextSize(19);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setGravity(Gravity.CENTER);
        topBar.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        selectAllBtn = new TextView(this);
        selectAllBtn.setText(R.string.sl_ft_select_all);
        selectAllBtn.setTextColor(blue);
        selectAllBtn.setTextSize(14);
        selectAllBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        selectAllBtn.setOnClickListener(v -> toggleSelectAll());
        topBar.addView(selectAllBtn);

        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Workspace
        workspace = new LinearLayout(this);
        workspace.setOrientation(LinearLayout.HORIZONTAL);

        rootsScroll = new ScrollView(this);
        rootsScroll.setBackgroundColor(bg);
        rootsContainer = new LinearLayout(this);
        rootsContainer.setOrientation(LinearLayout.VERTICAL);
        rootsContainer.setPadding(dp(12), dp(12), dp(12), dp(24));
        rootsScroll.addView(rootsContainer);

        filesPane = new LinearLayout(this);
        filesPane.setOrientation(LinearLayout.VERTICAL);
        filesPane.setBackgroundColor(bg);

        pathButton = new TextView(this);
        pathButton.setTextColor(blue);
        pathButton.setTextSize(14);
        pathButton.setMaxLines(1);
        pathButton.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pathButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        pathButton.setBackgroundColor(secSurface);
        pathButton.setOnClickListener(v -> goUp());
        filesPane.addView(pathButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView filesScroll = new ScrollView(this);
        filesContainer = new LinearLayout(this);
        filesContainer.setOrientation(LinearLayout.VERTICAL);
        filesContainer.setPadding(dp(12), dp(10), dp(12), dp(24));
        filesScroll.addView(filesContainer);
        filesPane.addView(filesScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        workspace.addView(rootsScroll);
        workspace.addView(filesPane);
        updatePaneLayout();

        root.addView(workspace, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom download bar
        bottomBar = new LinearLayout(this);
        bottomBar.setBackgroundColor(surface);
        bottomBar.setPadding(dp(16), dp(10), dp(16), dp(12));
        downloadButton = new Button(this);
        downloadButton.setAllCaps(false);
        downloadButton.setTextColor(Color.WHITE);
        downloadButton.setTextSize(15);
        downloadButton.setTypeface(null, Typeface.BOLD);
        downloadButton.setBackground(SideLanternTheme.roundedRect(blue, dp(10)));
        downloadButton.setOnClickListener(v -> downloadSelected());
        bottomBar.addView(downloadButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void updatePaneLayout() {
        if (wideLayout) {
            rootsScroll.setLayoutParams(new LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.MATCH_PARENT));
            filesPane.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else {
            rootsScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            filesPane.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void handleBack() {
        if (!wideLayout && selectedRoot != null) {
            goUp();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void goUp() {
        if (!currentPath.isEmpty()) {
            int slash = currentPath.lastIndexOf('/');
            currentPath = slash >= 0 ? currentPath.substring(0, slash) : "";
            reloadDirectory();
        } else if (!wideLayout) {
            selectedRoot = null;
            generation++;
            loading = false;
            selectedFiles.clear();
            render();
        }
    }

    private void reloadDirectory() {
        if (selectedRoot == null) return;
        generation++;
        loading = false;
        entries.clear();
        selectedFiles.clear();
        nextOffset = null;
        truncated = false;
        errorMessage = null;
        loadPage(null);
    }

    private void loadPage(final Integer offset) {
        if (loading || selectedRoot == null || manager == null) return;
        loading = true;
        errorMessage = null;
        render();
        final int gen = generation;
        manager.listRoot(selectedRoot.identifier, currentPath, offset, (listing, error) -> mainHandler.post(() -> {
            if (isFinishing() || gen != generation) return;
            loading = false;
            if (error != null) {
                errorMessage = getString(FileTransferProtocol.getErrorMessageResId(error.reason));
                render();
                return;
            }
            if (listing.nextOffset != null && offset != null && listing.nextOffset <= offset) {
                errorMessage = getString(R.string.sl_ft_error_invalid_response);
                render();
                return;
            }
            Set<String> existing = new HashSet<>();
            for (FileTransferProtocol.Entry e : entries) existing.add(e.name);
            for (FileTransferProtocol.Entry e : listing.entries) {
                if (!existing.contains(e.name)) {
                    entries.add(e);
                }
            }
            Collections.sort(entries, (a, b) -> {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            nextOffset = listing.nextOffset;
            truncated = listing.truncated;
            render();
        }));
    }

    private void toggleSelectAll() {
        if (selectedRoot == null || loading) return;
        int fileCount = 0;
        for (FileTransferProtocol.Entry e : entries) {
            if (!e.directory) fileCount++;
        }
        if (selectedFiles.size() == fileCount) {
            selectedFiles.clear();
        } else {
            for (FileTransferProtocol.Entry e : entries) {
                if (!e.directory) selectedFiles.add(e.name);
            }
        }
        render();
    }

    private void downloadSelected() {
        if (manager == null || selectedRoot == null) return;
        for (FileTransferProtocol.Entry e : entries) {
            if (!e.directory && selectedFiles.contains(e.name)) {
                String path = currentPath.isEmpty() ? e.name : (currentPath + "/" + e.name);
                manager.enqueueDownload(e, selectedRoot.identifier, path);
            }
        }
        finish();
    }

    private void render() {
        int surface = SideLanternTheme.surface(this);
        int primary = SideLanternTheme.textPrimary(this);
        int secondary = SideLanternTheme.textSecondary(this);
        int blue = SideLanternTheme.accentBlue(this);

        rootsScroll.setVisibility(!wideLayout && selectedRoot != null ? View.GONE : View.VISIBLE);
        filesPane.setVisibility(!wideLayout && selectedRoot == null ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(selectedRoot == null ? View.GONE : View.VISIBLE);
        selectAllBtn.setVisibility(selectedRoot != null && !loading ? View.VISIBLE : View.INVISIBLE);

        downloadButton.setText(getString(R.string.sl_ft_download_count, selectedFiles.size()));
        downloadButton.setEnabled(!selectedFiles.isEmpty());
        downloadButton.setAlpha(selectedFiles.isEmpty() ? 0.45f : 1.0f);

        if (selectedRoot != null) {
            String pathText = "↑  " + selectedRoot.label + (currentPath.isEmpty() ? "" : (" / " + currentPath));
            pathButton.setText(pathText);
            boolean canUp = !currentPath.isEmpty() || !wideLayout;
            pathButton.setEnabled(canUp);
            pathButton.setAlpha(canUp ? 1.0f : 0.6f);
        }

        // Populate roots
        rootsContainer.removeAllViews();
        TextView sharesTitle = new TextView(this);
        sharesTitle.setText(R.string.sl_ft_shares);
        sharesTitle.setTextColor(secondary);
        sharesTitle.setTextSize(13);
        sharesTitle.setTypeface(null, Typeface.BOLD);
        sharesTitle.setPadding(dp(6), dp(4), dp(6), dp(8));
        rootsContainer.addView(sharesTitle);

        if (roots.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.sl_ft_no_shares);
            empty.setTextColor(secondary);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            rootsContainer.addView(empty);
        } else {
            for (final FileTransferProtocol.Root r : roots) {
                boolean active = selectedRoot != null && selectedRoot.identifier.equals(r.identifier);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(SideLanternTheme.roundedRect(
                        active ? SideLanternTheme.withAlpha(blue, 40) : surface, dp(10)));
                row.setPadding(dp(14), dp(14), dp(14), dp(14));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(8);
                row.setLayoutParams(lp);

                TextView label = new TextView(this);
                label.setText(r.label);
                label.setTextColor(active ? blue : primary);
                label.setTextSize(15);
                label.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
                row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView arrow = new TextView(this);
                arrow.setText(active ? "✓" : "›");
                arrow.setTextColor(active ? blue : secondary);
                arrow.setTextSize(16);
                row.addView(arrow);

                row.setOnClickListener(v -> {
                    selectedRoot = r;
                    currentPath = "";
                    reloadDirectory();
                });
                rootsContainer.addView(row);
            }
        }

        // Populate directory entries
        filesContainer.removeAllViews();
        if (selectedRoot == null) {
            filesContainer.addView(makeMessageView(roots.isEmpty() ? getString(R.string.sl_ft_no_shares) : getString(R.string.sl_ft_choose_share)));
        } else if (errorMessage != null) {
            filesContainer.addView(makeMessageView(errorMessage));
        } else if (loading && entries.isEmpty()) {
            filesContainer.addView(makeMessageView(getString(R.string.sl_ft_loading)));
        } else if (entries.isEmpty()) {
            filesContainer.addView(makeMessageView(getString(R.string.sl_ft_empty_directory)));
        } else {
            for (final FileTransferProtocol.Entry e : entries) {
                boolean checked = !e.directory && selectedFiles.contains(e.name);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(SideLanternTheme.roundedRect(
                        checked ? SideLanternTheme.withAlpha(blue, 35) : surface, dp(10)));
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(6);
                row.setLayoutParams(lp);

                TextView icon = new TextView(this);
                icon.setText(e.directory ? "📁" : "📄");
                icon.setTextSize(18);
                icon.setPadding(0, 0, dp(12), 0);
                row.addView(icon);

                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                TextView nameTv = new TextView(this);
                nameTv.setText(e.name);
                nameTv.setTextColor(primary);
                nameTv.setTextSize(15);
                nameTv.setMaxLines(1);
                nameTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                textCol.addView(nameTv);

                if (!e.directory) {
                    TextView sub = new TextView(this);
                    String sz = Formatter.formatFileSize(this, e.size);
                    String dt = DateFormat.getDateFormat(this).format(new Date(e.modifiedUnixMs));
                    sub.setText(sz + " · " + dt);
                    sub.setTextColor(secondary);
                    sub.setTextSize(12);
                    textCol.addView(sub);
                }
                row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView trailing = new TextView(this);
                trailing.setText(e.directory ? "›" : (checked ? "✓" : ""));
                trailing.setTextColor(checked ? blue : secondary);
                trailing.setTextSize(18);
                trailing.setTypeface(null, Typeface.BOLD);
                row.addView(trailing);

                row.setOnClickListener(v -> {
                    if (e.directory) {
                        currentPath = currentPath.isEmpty() ? e.name : (currentPath + "/" + e.name);
                        reloadDirectory();
                    } else {
                        if (selectedFiles.contains(e.name)) selectedFiles.remove(e.name);
                        else selectedFiles.add(e.name);
                        render();
                    }
                });
                filesContainer.addView(row);
            }

            if (nextOffset != null) {
                Button moreBtn = new Button(this);
                moreBtn.setAllCaps(false);
                moreBtn.setText(R.string.sl_ft_load_more);
                moreBtn.setTextColor(blue);
                moreBtn.setBackgroundColor(Color.TRANSPARENT);
                moreBtn.setOnClickListener(v -> loadPage(nextOffset));
                filesContainer.addView(moreBtn);
            } else if (truncated) {
                filesContainer.addView(makeMessageView(getString(R.string.sl_ft_directory_truncated)));
            }
        }
    }

    private TextView makeMessageView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(SideLanternTheme.textSecondary(this));
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(32), dp(16), dp(32));
        return tv;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    private int dp(float v) {
        return SideLanternTheme.dp(this, v);
    }
}
