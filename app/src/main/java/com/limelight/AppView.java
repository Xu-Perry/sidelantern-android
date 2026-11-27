package com.limelight;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.files.FileTransferActivity;
import com.limelight.files.FileTransferClient;
import com.limelight.files.FileTransferManager;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.StreamModesResponse;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.DeviceBrowserPrefs;
import com.limelight.ui.SideLanternTheme;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParserException;

/**
 * SideLantern device detail screen:
 * Top bar (back, status, name, more, settings), hero desktop card with
 * Files / Secondary only / Wireless display / More feature bar, and
 * Quick Launch application grid with per-host Manage Apps selection.
 */
public class AppView extends Activity implements FileTransferManager.Listener {
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showAllApps;
    private final Set<Integer> hiddenAppIds = new HashSet<>();
    private final List<NvApp> allApps = new ArrayList<>();
    private final Map<Integer, Bitmap> artCache = new HashMap<>();
    private CachedAppAssetLoader assetLoader;
    private FileTransferManager fileTransferManager;
    private AlertDialog activeAppMenu;

    private ScrollView scrollView;
    private LinearLayout contentStack;
    private String lastRenderSignature;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";
    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder) binder);

            new Thread() {
                @Override
                public void run() {
                    localBinder.waitForReady();
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    assetLoader = new CachedAppAssetLoader(computer, 1.0,
                            new NetworkAssetLoader(AppView.this, localBinder.getUniqueId()),
                            new MemoryAssetLoader(),
                            new DiskAssetLoader(AppView.this),
                            android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.no_app_image));

                    fileTransferManager = FileTransferClient.managerForHost(AppView.this, computer);
                    if (fileTransferManager != null) {
                        fileTransferManager.addListener(AppView.this);
                        if (computer.state == ComputerDetails.State.ONLINE
                                && computer.pairState == PairingManager.PairState.PAIRED
                                && !fileTransferManager.isCapabilitiesProbed()) {
                            fileTransferManager.refreshCapabilities(null);
                        }
                    }

                    managerBinder = localBinder;
                    populateAppListWithCache();
                    startComputerUpdates();

                    runOnUiThread(() -> {
                        if (!isFinishing()) {
                            lastRenderSignature = null;
                            renderDetail();
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        inForeground = true;
        shortcutHelper = new ShortcutHelper(this);
        UiHelper.setLocale(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        showAllApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<>())) {
            try {
                hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
            } catch (NumberFormatException ignored) {
            }
        }

        buildShellView();
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    private void buildShellView() {
        int bg = SideLanternTheme.background(this);
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(bg);

        contentStack = new LinearLayout(this);
        contentStack.setOrientation(LinearLayout.VERTICAL);
        contentStack.setPadding(dp(18), dp(12), dp(18), dp(32));
        scrollView.addView(contentStack, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
        UiHelper.notifyNewRootView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !SideLanternTheme.isDarkMode(this)) {
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        renderDetail();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        lastRenderSignature = null;
        buildShellView();
    }

    private void startComputerUpdates() {
        if (managerBinder == null || !inForeground || computer == null) {
            return;
        }
        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                if (suspendGridUpdates || details == null || !details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }
                computer = details;

                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    runOnUiThread(() -> {
                        shortcutHelper.disableComputerShortcut(details, getString(R.string.scut_not_paired));
                        Toast.makeText(AppView.this, getText(R.string.scut_not_paired), Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }

                if (details.state == ComputerDetails.State.ONLINE
                        && details.pairState == PairingManager.PairState.PAIRED
                        && fileTransferManager != null
                        && !fileTransferManager.isCapabilitiesProbed()) {
                    fileTransferManager.refreshCapabilities(null);
                }

                if (details.rawAppList != null && !details.rawAppList.equals(lastRawApplist)) {
                    lastRawApplist = details.rawAppList;
                    lastRunningAppId = details.runningGameId;
                    try {
                        List<NvApp> parsed = NvHTTP.getAppListByReader(new StringReader(details.rawAppList));
                        runOnUiThread(() -> {
                            updateAppList(parsed);
                            if (blockingLoadSpinner != null) {
                                blockingLoadSpinner.dismiss();
                                blockingLoadSpinner = null;
                            }
                        });
                    } catch (XmlPullParserException | IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    lastRunningAppId = details.runningGameId;
                    runOnUiThread(() -> renderDetail());
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) poller.stop();
        if (managerBinder != null) managerBinder.stopPolling();
        if (assetLoader != null) assetLoader.cancelForegroundLoads();
    }

    private void populateAppListWithCache() {
        try {
            lastRawApplist = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            final List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            runOnUiThread(() -> updateAppList(applist));
        } catch (IOException | XmlPullParserException e) {
            if (computer != null && computer.state == ComputerDetails.State.ONLINE) {
                runOnUiThread(() -> {
                    blockingLoadSpinner = SpinnerDialog.displayDialog(
                            this,
                            getString(R.string.applist_refresh_title),
                            getString(R.string.applist_refresh_msg),
                            true);
                });
            }
        }
    }

    private void updateAppList(List<NvApp> apps) {
        allApps.clear();
        if (apps != null) {
            allApps.addAll(apps);
            Collections.sort(allApps, (a, b) -> {
                String na = a.getAppName() != null ? a.getAppName() : "";
                String nb = b.getAppName() != null ? b.getAppName() : "";
                return na.compareToIgnoreCase(nb);
            });
            for (NvApp app : allApps) {
                shortcutHelper.enableAppShortcut(computer, app);
                loadBoxArtAsync(app);
            }
        }
        lastRenderSignature = null;
        renderDetail();
    }

    private void loadBoxArtAsync(final NvApp app) {
        if (app == null || artCache.containsKey(app.getAppId()) || computer == null) return;
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                File art = CacheHelper.openPath(false, getCacheDir(), "boxart", computer.uuid, app.getAppId() + ".png");
                if (art != null && art.isFile()) {
                    bmp = BitmapFactory.decodeFile(art.getAbsolutePath());
                } else if (computer.state == ComputerDetails.State.ONLINE && managerBinder != null && computer.activeAddress != null) {
                    NvHTTP http = new NvHTTP(computer.activeAddress, computer.httpsPort, managerBinder.getUniqueId(),
                            computer.serverCert, com.limelight.binding.PlatformBinding.getCryptoProvider(AppView.this));
                    try (java.io.InputStream in = http.getBoxArt(app)) {
                        bmp = BitmapFactory.decodeStream(in);
                        if (bmp != null && art != null) {
                            File parent = art.getParentFile();
                            if (parent != null && !parent.exists()) parent.mkdirs();
                            try (java.io.FileOutputStream out = new java.io.FileOutputStream(art)) {
                                bmp.compress(Bitmap.CompressFormat.PNG, 90, out);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            if (bmp != null) {
                if ((bmp.getWidth() == 130 && bmp.getHeight() == 180)
                        || (bmp.getWidth() == 628 && bmp.getHeight() == 888)) {
                    return;
                }
                final Bitmap loaded = bmp;
                runOnUiThread(() -> {
                    artCache.put(app.getAppId(), loaded);
                    lastRenderSignature = null;
                    renderDetail();
                });
            }
        }).start();
    }

    private void renderDetail() {
        if (contentStack == null || isFinishing()) return;
        String name = computer != null ? computer.name : getIntent().getStringExtra(NAME_EXTRA);
        ComputerDetails.State state = computer != null ? computer.state : ComputerDetails.State.UNKNOWN;
        PairingManager.PairState pairState = computer != null ? computer.pairState : PairingManager.PairState.NOT_PAIRED;
        boolean onlinePaired = state == ComputerDetails.State.ONLINE && pairState == PairingManager.PairState.PAIRED;
        boolean filesSupported = onlinePaired && fileTransferManager != null && fileTransferManager.getCapabilities() != null;
        NvApp desktopApp = DeviceBrowserPrefs.findDesktopApp(this, uuidString, allApps);
        Set<String> quickIds = DeviceBrowserPrefs.getQuickLaunchAppIds(this, uuidString, allApps, hiddenAppIds);

        List<NvApp> displayedApps = new ArrayList<>();
        for (NvApp app : allApps) {
            boolean isHidden = hiddenAppIds.contains(app.getAppId());
            if (showAllApps || (!isHidden && quickIds.contains(Integer.toString(app.getAppId())))) {
                displayedApps.add(app);
            }
        }

        int widthDp = getResources().getConfiguration().screenWidthDp;
        StringBuilder sig = new StringBuilder();
        sig.append(name).append('|').append(state).append('|').append(pairState)
                .append('|').append(filesSupported).append('|').append(showAllApps)
                .append('|').append(lastRunningAppId).append('|').append(widthDp)
                .append('|').append(desktopApp != null ? desktopApp.getAppId() : -1)
                .append('|').append(artCache.size());
        for (NvApp app : displayedApps) {
            sig.append(',').append(app.getAppId()).append(':').append(hiddenAppIds.contains(app.getAppId()));
        }
        String nextSig = sig.toString();
        if (nextSig.equals(lastRenderSignature)) return;
        lastRenderSignature = nextSig;

        int scrollY = scrollView != null ? scrollView.getScrollY() : 0;
        contentStack.removeAllViews();

        int surface = SideLanternTheme.surface(this);
        int primary = SideLanternTheme.textPrimary(this);
        int secondary = SideLanternTheme.textSecondary(this);
        int divider = SideLanternTheme.divider(this);
        int blue = SideLanternTheme.accentBlue(this);

        // Top Bar: Back, Status Pill, Host Name, More (...), Stream Settings (Gear)
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.bottomMargin = dp(16);

        TextView backBtn = new TextView(this);
        backBtn.setText("‹");
        backBtn.setTextColor(primary);
        backBtn.setTextSize(30);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setOnClickListener(v -> finish());
        topBar.addView(backBtn, new LinearLayout.LayoutParams(dp(40), dp(44)));

        LinearLayout statusPill = createStatusPill(state);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.rightMargin = dp(10);
        topBar.addView(statusPill, pillLp);

        TextView nameTv = new TextView(this);
        nameTv.setText(name != null ? name : "");
        nameTv.setTextColor(primary);
        nameTv.setTextSize(22);
        nameTv.setTypeface(null, Typeface.BOLD);
        nameTv.setMaxLines(1);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        topBar.addView(nameTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView moreBtn = new TextView(this);
        moreBtn.setText("⋯");
        moreBtn.setTextColor(primary);
        moreBtn.setTextSize(22);
        moreBtn.setGravity(Gravity.CENTER);
        moreBtn.setContentDescription(getString(R.string.sl_more));
        moreBtn.setOnClickListener(v -> showHostMoreMenu());
        topBar.addView(moreBtn, new LinearLayout.LayoutParams(dp(40), dp(44)));

        ImageButton settingsBtn = new ImageButton(this);
        settingsBtn.setImageResource(R.drawable.ic_settings);
        settingsBtn.setColorFilter(primary);
        settingsBtn.setBackgroundColor(Color.TRANSPARENT);
        settingsBtn.setContentDescription(getString(R.string.sl_settings));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(AppView.this, StreamSettings.class)));
        topBar.addView(settingsBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        contentStack.addView(topBar, topLp);

        // Hero Desktop Card
        LinearLayout heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setBackground(SideLanternTheme.roundedRect(surface, dp(14)));
        heroCard.setClipToOutline(true);

        int coverHeight = dp(Math.min(280, Math.max(170, (int) ((widthDp - 36) * 9.0f / 16.0f))));
        FrameLayout coverArea = new FrameLayout(this);
        coverArea.setBackground(SideLanternTheme.coverPlaceholderGradient());

        Bitmap desktopArt = desktopApp != null ? artCache.get(desktopApp.getAppId()) : null;
        if (desktopArt != null) {
            ImageView coverImg = new ImageView(this);
            coverImg.setImageBitmap(desktopArt);
            coverImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            coverArea.addView(coverImg, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ImageView computerImg = new ImageView(this);
            computerImg.setImageResource(R.drawable.ic_computer);
            computerImg.setColorFilter(Color.argb(190, 255, 255, 255));
            coverArea.addView(computerImg, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));
        }

        // Bottom scrim + "Enter Desktop >" label
        View scrim = new View(this);
        scrim.setBackgroundColor(0x55000000);
        coverArea.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM));

        TextView desktopLabel = new TextView(this);
        int desktopRes = desktopApp != null ? R.string.sl_desktop_enter
                : (!allApps.isEmpty() ? R.string.sl_desktop_choose : R.string.sl_desktop_unavailable);
        desktopLabel.setText(getString(desktopRes) + "  ›");
        desktopLabel.setTextColor(Color.WHITE);
        desktopLabel.setTextSize(17);
        desktopLabel.setTypeface(null, Typeface.BOLD);
        desktopLabel.setGravity(Gravity.CENTER);
        desktopLabel.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        coverArea.addView(desktopLabel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM));

        boolean canStreamDesktop = onlinePaired && !allApps.isEmpty();
        coverArea.setEnabled(canStreamDesktop);
        coverArea.setAlpha(canStreamDesktop ? 1.0f : 0.65f);
        coverArea.setOnClickListener(v -> launchDesktopEntry(StreamModesResponse.MODE_PRIMARY));
        heroCard.addView(coverArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, coverHeight));

        // 4-column feature bar: Files | Secondary only | Wireless display | More
        LinearLayout featureBar = new LinearLayout(this);
        featureBar.setOrientation(LinearLayout.HORIZONTAL);
        featureBar.setGravity(Gravity.CENTER_VERTICAL);

        featureBar.addView(createFeatureItem("📁", getString(R.string.sl_files), filesSupported, v -> openFileTransfer()));
        featureBar.addView(createVerticalDivider(divider));
        featureBar.addView(createFeatureItem("🖥", getString(R.string.sl_secondary_display), canStreamDesktop,
                v -> launchDesktopEntry(StreamModesResponse.MODE_SECONDARY)));
        featureBar.addView(createVerticalDivider(divider));
        featureBar.addView(createFeatureItem("⧉", getString(R.string.sl_wireless_display), canStreamDesktop,
                v -> launchDesktopEntry(StreamModesResponse.MODE_EXTEND)));
        featureBar.addView(createVerticalDivider(divider));
        featureBar.addView(createFeatureItem("⊞", getString(R.string.sl_more), true, v -> showHostMoreMenu()));

        heroCard.addView(featureBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.bottomMargin = dp(24);
        contentStack.addView(heroCard, heroLp);

        // Quick Launch Header Row
        LinearLayout quickHeader = new LinearLayout(this);
        quickHeader.setOrientation(LinearLayout.HORIZONTAL);
        quickHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams qhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qhLp.bottomMargin = dp(14);

        TextView quickTitle = new TextView(this);
        quickTitle.setText(showAllApps ? R.string.sl_apps_all : R.string.sl_apps_quick_launch);
        quickTitle.setTextColor(primary);
        quickTitle.setTextSize(20);
        quickTitle.setTypeface(null, Typeface.BOLD);
        quickHeader.addView(quickTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView manageBtn = new TextView(this);
        manageBtn.setText(R.string.sl_apps_manage);
        manageBtn.setTextColor(blue);
        manageBtn.setTextSize(14);
        manageBtn.setTypeface(null, Typeface.BOLD);
        manageBtn.setBackground(SideLanternTheme.pillButtonBg(this));
        manageBtn.setPadding(dp(14), dp(9), dp(14), dp(9));
        manageBtn.setOnClickListener(v -> showManageQuickLaunchDialog());
        quickHeader.addView(manageBtn);
        contentStack.addView(quickHeader, qhLp);

        // Adaptive App Grid (no "Add App" card)
        if (displayedApps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(allApps.isEmpty() ? R.string.sl_apps_none_published : R.string.sl_apps_choose_quick_launch);
            empty.setTextColor(secondary);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(24), dp(16), dp(24));
            contentStack.addView(empty);
        } else {
            int cols = Math.max(2, (widthDp - 36 + 14) / 154);
            for (int i = 0; i < displayedApps.size(); i += cols) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(14);
                for (int c = 0; c < cols; c++) {
                    LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, dp(164), 1f);
                    if (c > 0) cellLp.leftMargin = dp(12);
                    if (i + c < displayedApps.size()) {
                        row.addView(createAppCard(displayedApps.get(i + c), onlinePaired), cellLp);
                    } else {
                        row.addView(new View(this), cellLp);
                    }
                }
                contentStack.addView(row, rowLp);
            }
        }

        if (scrollView != null) {
            scrollView.post(() -> scrollView.scrollTo(0, scrollY));
        }
    }

    private LinearLayout createFeatureItem(String glyph, String title, boolean enabled, View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(8), dp(4), dp(8));
        item.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        item.setEnabled(enabled);
        item.setAlpha(enabled ? 1.0f : 0.35f);

        TextView iconTv = new TextView(this);
        iconTv.setText(glyph);
        iconTv.setTextSize(19);
        iconTv.setTextColor(SideLanternTheme.textPrimary(this));
        iconTv.setGravity(Gravity.CENTER);
        item.addView(iconTv);

        TextView labelTv = new TextView(this);
        labelTv.setText(title);
        labelTv.setTextSize(12);
        labelTv.setTextColor(SideLanternTheme.textPrimary(this));
        labelTv.setMaxLines(1);
        labelTv.setEllipsize(TextUtils.TruncateAt.END);
        labelTv.setGravity(Gravity.CENTER);
        labelTv.setPadding(0, dp(4), 0, 0);
        item.addView(labelTv);

        item.setOnClickListener(listener);
        return item;
    }

    private View createVerticalDivider(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(40));
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout createStatusPill(ComputerDetails.State state) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(SideLanternTheme.roundedRect(0xB8000000, dp(12)));
        pill.setPadding(dp(8), dp(4), dp(9), dp(4));

        View dot = new View(this);
        int dotColor = state == ComputerDetails.State.ONLINE ? SideLanternTheme.STATUS_GREEN
                : (state == ComputerDetails.State.OFFLINE ? SideLanternTheme.STATUS_GRAY : SideLanternTheme.STATUS_ORANGE);
        dot.setBackground(SideLanternTheme.roundedRect(dotColor, dp(4)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(5);
        pill.addView(dot, dotLp);

        TextView tv = new TextView(this);
        tv.setText(state == ComputerDetails.State.ONLINE ? R.string.sl_online
                : (state == ComputerDetails.State.OFFLINE ? R.string.sl_offline : R.string.sl_connecting));
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11);
        pill.addView(tv);
        return pill;
    }

    private View createAppCard(final NvApp app, boolean onlinePaired) {
        int surface = SideLanternTheme.surface(this);
        int primary = SideLanternTheme.textPrimary(this);
        boolean hidden = hiddenAppIds.contains(app.getAppId());
        boolean running = lastRunningAppId != 0 && lastRunningAppId == app.getAppId();

        FrameLayout card = new FrameLayout(this);
        card.setBackground(SideLanternTheme.roundedRect(surface, dp(12)));
        card.setClipToOutline(true);
        card.setAlpha(hidden ? 0.45f : (onlinePaired ? 1.0f : 0.6f));
        card.setEnabled(onlinePaired);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        inner.setPadding(dp(10), dp(12), dp(10), dp(10));

        ImageView img = new ImageView(this);
        Bitmap bmp = artCache.get(app.getAppId());
        if (bmp != null) {
            img.setImageBitmap(bmp);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            img.setImageResource(R.drawable.ic_play);
            img.setColorFilter(SideLanternTheme.accentBlue(this));
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
        inner.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView label = new TextView(this);
        label.setText(app.getAppName());
        label.setTextColor(primary);
        label.setTextSize(13);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(8), 0, 0);
        inner.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        card.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (running) {
            TextView runBadge = new TextView(this);
            runBadge.setText("▶");
            runBadge.setTextColor(Color.WHITE);
            runBadge.setTextSize(10);
            runBadge.setGravity(Gravity.CENTER);
            runBadge.setBackground(SideLanternTheme.roundedRect(SideLanternTheme.STATUS_GREEN, dp(11)));
            FrameLayout.LayoutParams runLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
            runLp.setMargins(0, dp(8), dp(8), 0);
            card.addView(runBadge, runLp);
        }

        card.setOnClickListener(v -> onAppClicked(app));
        card.setOnLongClickListener(v -> {
            showAppActionMenu(app);
            return true;
        });
        return card;
    }

    private void launchDesktopEntry(final String mode) {
        if (computer == null || managerBinder == null || allApps.isEmpty()) return;
        NvApp desktop = DeviceBrowserPrefs.findDesktopApp(this, uuidString, allApps);
        if (desktop != null) {
            startDesktopWithMode(desktop, mode);
            return;
        }
        String[] names = new String[allApps.size()];
        for (int i = 0; i < allApps.size(); i++) {
            names[i] = allApps.get(i).getAppName();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sl_desktop_choose)
                .setItems(names, (d, which) -> {
                    NvApp chosen = allApps.get(which);
                    DeviceBrowserPrefs.setDesktopApp(AppView.this, uuidString, chosen);
                    lastRenderSignature = null;
                    renderDetail();
                    startDesktopWithMode(chosen, mode);
                })
                .setNegativeButton(R.string.sl_cancel, null)
                .show();
    }

    private void startDesktopWithMode(NvApp desktopApp, String mode) {
        String displayName = desktopApp.getAppName();
        if (StreamModesResponse.MODE_SECONDARY.equals(mode)) {
            displayName = getString(R.string.sl_secondary_display);
        } else if (StreamModesResponse.MODE_EXTEND.equals(mode)) {
            displayName = getString(R.string.sl_wireless_display);
        }
        NvApp launchApp = new NvApp(displayName, desktopApp.getAppId(), desktopApp.isHdrSupported());
        // Desktop & display mode entries connect immediately and resume any running session without quitting it.
        ServerHelper.doStart(this, launchApp, computer, managerBinder, mode, true);
    }

    private void onAppClicked(NvApp app) {
        if (computer == null || managerBinder == null) return;
        NvApp desktop = DeviceBrowserPrefs.findDesktopApp(this, uuidString, allApps);
        boolean isDesktop = desktop != null && desktop.getAppId() == app.getAppId();
        if (lastRunningAppId != 0 && !isDesktop) {
            showAppActionMenu(app);
        } else {
            ServerHelper.doStart(this, app, computer, managerBinder, StreamModesResponse.MODE_PRIMARY, isDesktop);
        }
    }

    private void showAppActionMenu(final NvApp app) {
        if (activeAppMenu != null && activeAppMenu.isShowing()) {
            return;
        }
        final boolean isHidden = hiddenAppIds.contains(app.getAppId());
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == app.getAppId()) {
                labels.add(getString(R.string.applist_menu_resume));
                actions.add(() -> ServerHelper.doStart(AppView.this, app, computer, managerBinder));
                labels.add(getString(R.string.applist_menu_quit));
                actions.add(() -> UiHelper.displayQuitConfirmationDialog(AppView.this, () -> {
                    suspendGridUpdates = true;
                    ServerHelper.doQuit(AppView.this, computer, app, managerBinder, () -> {
                        suspendGridUpdates = false;
                        if (poller != null) poller.pollNow();
                    });
                }, null));
            } else {
                labels.add(getString(R.string.applist_menu_quit_and_start));
                actions.add(() -> UiHelper.displayQuitConfirmationDialog(AppView.this,
                        () -> ServerHelper.doStart(AppView.this, app, computer, managerBinder), null));
            }
        } else {
            labels.add(getString(R.string.applist_menu_resume));
            actions.add(() -> ServerHelper.doStart(AppView.this, app, computer, managerBinder));
        }

        if (lastRunningAppId != app.getAppId() || isHidden) {
            labels.add((isHidden ? "✓ " : "") + getString(R.string.applist_menu_hide_app));
            actions.add(() -> {
                if (isHidden) hiddenAppIds.remove(app.getAppId());
                else hiddenAppIds.add(app.getAppId());
                saveHiddenApps();
                lastRenderSignature = null;
                renderDetail();
            });
        }

        labels.add(getString(R.string.applist_menu_details));
        actions.add(() -> Dialog.displayDialog(AppView.this, getString(R.string.title_details), app.toString(), false));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && artCache.containsKey(app.getAppId())) {
            labels.add(getString(R.string.applist_menu_scut));
            actions.add(() -> {
                if (!shortcutHelper.createPinnedGameShortcut(computer, app, artCache.get(app.getAppId()))) {
                    Toast.makeText(AppView.this, getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(app.getAppName())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.sl_cancel, null)
                .create();
        dialog.setOnDismissListener(d -> activeAppMenu = null);
        activeAppMenu = dialog;
        dialog.show();
    }

    private void showManageQuickLaunchDialog() {
        List<NvApp> visibleApps = new ArrayList<>();
        for (NvApp app : allApps) {
            if (!hiddenAppIds.contains(app.getAppId())) {
                visibleApps.add(app);
            }
        }
        if (visibleApps.isEmpty()) {
            Toast.makeText(this, R.string.sl_apps_none_published, Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> selected = new HashSet<>(DeviceBrowserPrefs.getQuickLaunchAppIds(this, uuidString, allApps, hiddenAppIds));
        String[] names = new String[visibleApps.size()];
        boolean[] checked = new boolean[visibleApps.size()];
        for (int i = 0; i < visibleApps.size(); i++) {
            names[i] = visibleApps.get(i).getAppName();
            checked[i] = selected.contains(Integer.toString(visibleApps.get(i).getAppId()));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sl_apps_choose_quick_launch)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    String id = Integer.toString(visibleApps.get(which).getAppId());
                    if (isChecked) selected.add(id);
                    else selected.remove(id);
                })
                .setNegativeButton(R.string.sl_cancel, null)
                .setPositiveButton(R.string.sl_done, (dialog, which) -> {
                    showAllApps = false;
                    DeviceBrowserPrefs.setQuickLaunchAppIds(AppView.this, uuidString, selected);
                    lastRenderSignature = null;
                    renderDetail();
                })
                .show();
    }

    private void openFileTransfer() {
        if (computer == null) return;
        Intent i = new Intent(this, FileTransferActivity.class);
        i.putExtra(FileTransferActivity.EXTRA_HOST_UUID, computer.uuid);
        i.putExtra(FileTransferActivity.EXTRA_HOST_NAME, computer.name);
        startActivity(i);
    }

    private void showHostMoreMenu() {
        if (computer == null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(getString(R.string.sl_settings));
        actions.add(() -> startActivity(new Intent(AppView.this, StreamSettings.class)));

        if (computer.state != ComputerDetails.State.ONLINE) {
            labels.add(getString(R.string.pcview_menu_send_wol));
            actions.add(() -> new Thread(() -> {
                String msg;
                if (computer.macAddress == null) {
                    msg = getString(R.string.wol_no_mac);
                } else {
                    try {
                        WakeOnLanSender.sendWolPacket(computer);
                        msg = getString(R.string.wol_waking_msg);
                    } catch (IOException e) {
                        msg = getString(R.string.wol_fail);
                    }
                }
                final String finalMsg = msg;
                runOnUiThread(() -> Toast.makeText(AppView.this, finalMsg, Toast.LENGTH_LONG).show());
            }).start());
        } else {
            if (fileTransferManager != null && fileTransferManager.getCapabilities() != null) {
                labels.add(getString(R.string.sl_ft_title));
                actions.add(this::openFileTransfer);
            }
            labels.add(showAllApps ? getString(R.string.sl_apps_quick_launch) : getString(R.string.sl_apps_all));
            actions.add(() -> {
                showAllApps = !showAllApps;
                lastRenderSignature = null;
                renderDetail();
            });
        }

        if (computer.nvidiaServer) {
            labels.add(getString(R.string.pcview_menu_eol));
            actions.add(() -> HelpLauncher.launchGameStreamEolFaq(AppView.this));
        }

        labels.add(getString(R.string.pcview_menu_test_network));
        actions.add(() -> ServerHelper.doNetworkTest(AppView.this));

        labels.add(getString(R.string.pcview_menu_details));
        actions.add(() -> Dialog.displayDialog(AppView.this, getString(R.string.title_details), computer.toString(), false));

        labels.add(getString(R.string.pcview_menu_delete_pc));
        actions.add(() -> {
            if (ActivityManager.isUserAMonkey() || managerBinder == null) return;
            UiHelper.displayDeletePcConfirmationDialog(AppView.this, computer, () -> {
                managerBinder.removeComputer(computer);
                new DiskAssetLoader(AppView.this).deleteAssetsForComputer(computer.uuid);
                DeviceBrowserPrefs.clearForHost(AppView.this, computer.uuid);
                finish();
            }, null);
        });

        new AlertDialog.Builder(this)
                .setTitle(computer.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.sl_cancel, null)
                .show();
    }

    private void saveHiddenApps() {
        Set<String> set = new HashSet<>();
        for (Integer id : hiddenAppIds) set.add(id.toString());
        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, set)
                .apply();
    }

    @Override
    public void onFileTransferChanged(FileTransferManager manager) {
        runOnUiThread(() -> {
            lastRenderSignature = null;
            renderDetail();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiHelper.showDecoderCrashDialog(this);
        inForeground = true;
        startComputerUpdates();
        lastRenderSignature = null;
        renderDetail();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();
        if (fileTransferManager != null) {
            fileTransferManager.removeListener(this);
        }
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    private int dp(float v) {
        return SideLanternTheme.dp(this, v);
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
