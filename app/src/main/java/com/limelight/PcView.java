package com.limelight;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.files.FileTransferActivity;
import com.limelight.files.FileTransferClient;
import com.limelight.files.FileTransferManager;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.DeviceBrowserPrefs;
import com.limelight.ui.SideLanternTheme;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.DeviceNameHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * SideLantern device browser screen:
 * Grouped vertical list / wide-screen 2-column grid of rounded device cards with
 * Online / Connecting / Offline collapsible groups, All/Online-only filter, and manual add.
 */
public class PcView extends Activity {
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private boolean onlineOnly = false;
    private final Set<Integer> collapsedGroups = new HashSet<>();
    private final Map<String, ComputerDetails> computersByUuid = new LinkedHashMap<>();
    private final Map<String, Bitmap> desktopCoverCache = new LinkedHashMap<>();

    private ScrollView scrollView;
    private LinearLayout listStack;
    private TextView filterButton;
    private String lastSignature;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder) binder);

            new Thread() {
                @Override
                public void run() {
                    localBinder.waitForReady();
                    managerBinder = localBinder;
                    DeviceNameHelper.initialize(PcView.this, localBinder.getUniqueId());
                    startComputerUpdates();
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (completeOnCreateCalled) {
            lastSignature = null;
            initializeViews();
        }
    }

    private void initializeViews() {
        int bg = SideLanternTheme.background(this);
        int primary = SideLanternTheme.textPrimary(this);
        int blue = SideLanternTheme.accentBlue(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(bg);

        listStack = new LinearLayout(this);
        listStack.setOrientation(LinearLayout.VERTICAL);
        listStack.setPadding(dp(18), dp(18), dp(18), dp(32));
        scrollView.addView(listStack, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        UiHelper.notifyNewRootView(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !SideLanternTheme.isDarkMode(this)) {
            getWindow().getDecorView().setSystemUiVisibility(
                    getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        lastSignature = null;
        renderDeviceList();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        inForeground = true;

        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();
                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);
                    runOnUiThread(() -> completeOnCreate());
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {}

                @Override
                public void onDrawFrame(GL10 gl10) {}
            });
            setContentView(surfaceView);
        } else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;
        shortcutHelper = new ShortcutHelper(this);
        UiHelper.setLocale(this);
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
        initializeViews();
    }

    private void startComputerUpdates() {
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(() -> updateComputer(details));
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }
            freezeUpdates = true;
            managerBinder.stopPolling();
            if (wait) {
                managerBinder.waitForPollingStopped();
            }
            runningPolling = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiHelper.showDecoderCrashDialog(this);
        inForeground = true;
        startComputerUpdates();
        if (managerBinder != null) {
            managerBinder.refreshDiscovery();
        }
        if (completeOnCreateCalled) {
            lastSignature = null;
            renderDeviceList();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Dialog.closeDialogs();
    }

    private void updateComputer(ComputerDetails details) {
        if (details == null || details.uuid == null) return;
        computersByUuid.put(details.uuid, details);
        loadCachedCoverAsync(details);
        renderDeviceList();
    }

    private void loadCachedCoverAsync(final ComputerDetails computer) {
        if (computer == null || computer.uuid == null || desktopCoverCache.containsKey(computer.uuid)) {
            return;
        }
        new Thread(() -> {
            Bitmap bmp = loadDesktopArtworkFromDisk(computer.uuid);
            if (bmp != null) {
                runOnUiThread(() -> {
                    desktopCoverCache.put(computer.uuid, bmp);
                    lastSignature = null;
                    renderDeviceList();
                });
            }
        }).start();
    }

    private Bitmap loadDesktopArtworkFromDisk(String uuid) {
        try {
            String raw = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuid));
            List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(raw));
            NvApp desktop = DeviceBrowserPrefs.findDesktopApp(this, uuid, apps);
            if (desktop == null) return null;
            File art = CacheHelper.openPath(false, getCacheDir(), "boxart", uuid, desktop.getAppId() + ".png");
            if (art == null || !art.isFile()) return null;
            Bitmap bmp = BitmapFactory.decodeFile(art.getAbsolutePath());
            if (bmp == null) return null;
            // Ignore placeholder 130x180 / 628x888 GFE/Sunshine stub boxart
            if ((bmp.getWidth() == 130 && bmp.getHeight() == 180)
                    || (bmp.getWidth() == 628 && bmp.getHeight() == 888)) {
                return null;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void renderDeviceList() {
        if (listStack == null) return;
        List<ComputerDetails> sorted = new ArrayList<>(computersByUuid.values());
        Collections.sort(sorted, (a, b) -> {
            String na = a.name != null ? a.name : "";
            String nb = b.name != null ? b.name : "";
            return na.compareToIgnoreCase(nb);
        });

        int widthDp = getResources().getConfiguration().screenWidthDp;
        int columns = widthDp >= 720 ? 2 : 1;
        StringBuilder sig = new StringBuilder();
        sig.append(onlineOnly).append('-').append(collapsedGroups).append('-').append(columns);
        for (ComputerDetails c : sorted) {
            sig.append('|').append(c.uuid).append(':').append(c.name)
                    .append(':').append(c.state).append(':').append(c.pairState)
                    .append(':').append(desktopCoverCache.containsKey(c.uuid));
        }
        String nextSig = sig.toString();
        if (nextSig.equals(lastSignature)) {
            return;
        }
        lastSignature = nextSig;

        int scrollY = scrollView != null ? scrollView.getScrollY() : 0;
        listStack.removeAllViews();

        int primary = SideLanternTheme.textPrimary(this);
        int secondary = SideLanternTheme.textSecondary(this);
        int blue = SideLanternTheme.accentBlue(this);

        // Header row: "My Devices" + Filter pill button
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingLp.bottomMargin = dp(16);

        TextView titleTv = new TextView(this);
        titleTv.setText(R.string.sl_devices_title);
        titleTv.setTextColor(primary);
        titleTv.setTextSize(28);
        titleTv.setTypeface(null, Typeface.BOLD);
        heading.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        filterButton = new TextView(this);
        filterButton.setText(onlineOnly ? R.string.sl_devices_online_only : R.string.sl_devices_all);
        filterButton.setTextColor(blue);
        filterButton.setTextSize(14);
        filterButton.setTypeface(null, Typeface.BOLD);
        filterButton.setBackground(SideLanternTheme.pillButtonBg(this));
        filterButton.setPadding(dp(14), dp(9), dp(14), dp(9));
        filterButton.setOnClickListener(v -> showFilterDialog());
        heading.addView(filterButton);
        listStack.addView(heading, headingLp);

        int onlineCount = 0;
        for (int group = 0; group < 3; group++) {
            if (onlineOnly && group != 0) continue;
            ComputerDetails.State targetState = group == 0 ? ComputerDetails.State.ONLINE
                    : (group == 1 ? ComputerDetails.State.UNKNOWN : ComputerDetails.State.OFFLINE);
            List<ComputerDetails> groupHosts = new ArrayList<>();
            for (ComputerDetails c : sorted) {
                if (c.state == targetState) {
                    groupHosts.add(c);
                }
            }
            if (group == 0) onlineCount = groupHosts.size();
            if (groupHosts.isEmpty() && group != 0) continue;

            final int groupIdx = group;
            boolean collapsed = collapsedGroups.contains(groupIdx);
            int headerRes = group == 0 ? R.string.sl_devices_online_count
                    : (group == 1 ? R.string.sl_devices_connecting_count : R.string.sl_devices_offline_count);

            LinearLayout sectionHeader = new LinearLayout(this);
            sectionHeader.setOrientation(LinearLayout.HORIZONTAL);
            sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
            sectionHeader.setPadding(dp(2), dp(8), dp(2), dp(8));
            TextView sectionTitle = new TextView(this);
            sectionTitle.setText(getString(headerRes, groupHosts.size()));
            sectionTitle.setTextColor(secondary);
            sectionTitle.setTextSize(14);
            sectionTitle.setTypeface(null, Typeface.BOLD);
            sectionHeader.addView(sectionTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView chevron = new TextView(this);
            chevron.setText(collapsed ? "▾" : "▴");
            chevron.setTextColor(secondary);
            chevron.setTextSize(15);
            sectionHeader.addView(chevron);
            sectionHeader.setOnClickListener(v -> {
                if (collapsedGroups.contains(groupIdx)) collapsedGroups.remove(groupIdx);
                else collapsedGroups.add(groupIdx);
                lastSignature = null;
                renderDeviceList();
            });
            listStack.addView(sectionHeader);

            if (collapsed) continue;

            for (int i = 0; i < groupHosts.size(); i += columns) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(12);
                for (int col = 0; col < columns; col++) {
                    LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                            0, dp(columns == 2 ? 124 : 108), 1f);
                    if (col > 0) cellLp.leftMargin = dp(14);
                    if (i + col < groupHosts.size()) {
                        row.addView(createDeviceCard(groupHosts.get(i + col)), cellLp);
                    } else {
                        row.addView(new View(this), cellLp);
                    }
                }
                listStack.addView(row, rowLp);
            }
        }

        if (sorted.isEmpty() || (onlineOnly && onlineCount == 0)) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText(onlineOnly ? R.string.sl_devices_none_online : R.string.sl_devices_none);
            emptyTv.setTextColor(secondary);
            emptyTv.setTextSize(15);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(dp(16), dp(28), dp(16), dp(16));
            listStack.addView(emptyTv);

            Button addBtn = new Button(this);
            addBtn.setAllCaps(false);
            addBtn.setText(R.string.sl_devices_add);
            addBtn.setTextColor(blue);
            addBtn.setBackground(SideLanternTheme.pillButtonBg(this));
            addBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
            addBtn.setOnClickListener(v -> startActivity(new Intent(PcView.this, AddComputerManually.class)));
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addLp.gravity = Gravity.CENTER_HORIZONTAL;
            listStack.addView(addBtn, addLp);
        }

        if (scrollView != null) {
            scrollView.post(() -> scrollView.scrollTo(0, scrollY));
        }
    }

    private View createDeviceCard(final ComputerDetails computer) {
        int surface = SideLanternTheme.surface(this);
        int primary = SideLanternTheme.textPrimary(this);
        int secondary = SideLanternTheme.textSecondary(this);
        int blue = SideLanternTheme.accentBlue(this);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(SideLanternTheme.roundedRect(surface, dp(14)));
        card.setClipToOutline(true);

        // Left cover (35% width)
        FrameLayout coverBox = new FrameLayout(this);
        coverBox.setBackground(SideLanternTheme.coverPlaceholderGradient());

        Bitmap coverBmp = desktopCoverCache.get(computer.uuid);
        if (coverBmp != null) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(coverBmp);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            coverBox.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_computer);
            icon.setColorFilter(Color.argb(195, 255, 255, 255));
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
            coverBox.addView(icon, iconLp);
        }

        // Top-left status badge
        LinearLayout statusPill = createStatusPill(computer);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        pillLp.setMargins(dp(7), dp(7), dp(7), dp(7));
        coverBox.addView(statusPill, pillLp);

        card.addView(coverBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.35f));

        // Right info
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(16), dp(12), dp(14), dp(12));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView nameTv = new TextView(this);
        nameTv.setText(computer.name);
        nameTv.setTextColor(primary);
        nameTv.setTextSize(18);
        nameTv.setTypeface(null, Typeface.BOLD);
        nameTv.setMaxLines(1);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(nameTv);

        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setPadding(0, dp(6), 0, 0);

        ImageView sysIcon = new ImageView(this);
        sysIcon.setImageResource(R.drawable.ic_computer);
        sysIcon.setColorFilter(blue);
        LinearLayout.LayoutParams sysLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        sysLp.rightMargin = dp(6);
        subRow.addView(sysIcon, sysLp);

        TextView pairTv = new TextView(this);
        pairTv.setText(computer.pairState == PairState.PAIRED ? R.string.sl_paired : R.string.sl_pair_device);
        pairTv.setTextColor(secondary);
        pairTv.setTextSize(13);
        subRow.addView(pairTv);
        textCol.addView(subRow);

        info.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(secondary);
        arrow.setTextSize(22);
        arrow.setPadding(dp(8), 0, 0, 0);
        info.addView(arrow);

        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.65f));

        card.setOnClickListener(v -> onDeviceCardClicked(computer));
        card.setOnLongClickListener(v -> {
            showHostActions(computer);
            return true;
        });
        return card;
    }

    private LinearLayout createStatusPill(ComputerDetails computer) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(SideLanternTheme.roundedRect(0xB8000000, dp(12)));
        pill.setPadding(dp(7), dp(3), dp(8), dp(3));

        View dot = new View(this);
        int dotColor = computer.state == ComputerDetails.State.ONLINE ? SideLanternTheme.STATUS_GREEN
                : (computer.state == ComputerDetails.State.OFFLINE ? SideLanternTheme.STATUS_GRAY : SideLanternTheme.STATUS_ORANGE);
        dot.setBackground(SideLanternTheme.roundedRect(dotColor, dp(4)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(5);
        pill.addView(dot, dotLp);

        TextView tv = new TextView(this);
        tv.setText(computer.state == ComputerDetails.State.ONLINE ? R.string.sl_online
                : (computer.state == ComputerDetails.State.OFFLINE ? R.string.sl_offline : R.string.sl_connecting));
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11);
        pill.addView(tv);
        return pill;
    }

    private void showFilterDialog() {
        String[] options = new String[]{
                getString(R.string.sl_devices_all),
                getString(R.string.sl_devices_online_only),
                getString(R.string.sl_devices_add)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.sl_devices_filter)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        onlineOnly = false;
                        lastSignature = null;
                        renderDeviceList();
                    } else if (which == 1) {
                        onlineOnly = true;
                        lastSignature = null;
                        renderDeviceList();
                    } else if (which == 2) {
                        startActivity(new Intent(PcView.this, AddComputerManually.class));
                    }
                })
                .setNegativeButton(R.string.sl_cancel, null)
                .show();
    }

    private void onDeviceCardClicked(ComputerDetails computer) {
        if (computer.pairState == PairState.PAIRED) {
            doAppList(computer, false, false);
        } else if (computer.state == ComputerDetails.State.ONLINE) {
            doPair(computer);
        } else {
            showHostActions(computer);
        }
    }

    private void showHostActions(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE
                && computer.pairState == PairState.PAIRED
                && computer.serverCert != null) {
            FileTransferManager ft = FileTransferClient.managerForHost(this, computer);
            if (ft != null && !ft.isCapabilitiesProbed()) {
                ft.refreshCapabilities(() -> runOnUiThread(() -> {
                    if (!isFinishing()) presentHostActions(computer);
                }));
                return;
            }
            if (ft != null && ft.getCapabilityError() != null) {
                ft.refreshCapabilities(null);
            }
        }
        presentHostActions(computer);
    }

    private void presentHostActions(final ComputerDetails computer) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (computer.state != ComputerDetails.State.ONLINE) {
            labels.add(getString(R.string.pcview_menu_send_wol));
            actions.add(() -> doWakeOnLan(computer));
        } else if (computer.pairState != PairState.PAIRED) {
            labels.add(getString(R.string.pcview_menu_pair_pc));
            actions.add(() -> doPair(computer));
        } else {
            if (computer.runningGameId != 0) {
                labels.add(getString(R.string.applist_menu_resume));
                actions.add(() -> {
                    if (managerBinder != null) {
                        ServerHelper.doStart(PcView.this, new NvApp("app", computer.runningGameId, false), computer, managerBinder);
                    }
                });
                labels.add(getString(R.string.applist_menu_quit));
                actions.add(() -> {
                    if (managerBinder != null) {
                        UiHelper.displayQuitConfirmationDialog(PcView.this,
                                () -> ServerHelper.doQuit(PcView.this, computer, new NvApp("app", 0, false), managerBinder, null),
                                null);
                    }
                });
            }
            FileTransferManager ft = FileTransferClient.managerForHost(this, computer);
            if (ft != null && ft.getCapabilities() != null) {
                labels.add(getString(R.string.sl_ft_title));
                actions.add(() -> {
                    Intent i = new Intent(PcView.this, FileTransferActivity.class);
                    i.putExtra(FileTransferActivity.EXTRA_HOST_UUID, computer.uuid);
                    i.putExtra(FileTransferActivity.EXTRA_HOST_NAME, computer.name);
                    startActivity(i);
                });
            }
            labels.add(getString(R.string.pcview_menu_app_list));
            actions.add(() -> doAppList(computer, false, true));
        }

        if (computer.nvidiaServer) {
            labels.add(getString(R.string.pcview_menu_eol));
            actions.add(() -> HelpLauncher.launchGameStreamEolFaq(PcView.this));
        }

        labels.add(getString(R.string.pcview_menu_test_network));
        actions.add(() -> ServerHelper.doNetworkTest(PcView.this));

        labels.add(getString(R.string.pcview_menu_details));
        actions.add(() -> Dialog.displayDialog(PcView.this, getString(R.string.title_details), computer.toString(), false));

        if (computer.state == ComputerDetails.State.ONLINE && computer.pairState == PairState.PAIRED) {
            labels.add(getString(R.string.pcview_menu_unpair_pc));
            actions.add(() -> doUnpair(computer));
        }

        labels.add(getString(R.string.pcview_menu_delete_pc));
        actions.add(() -> {
            if (ActivityManager.isUserAMonkey()) return;
            UiHelper.displayDeletePcConfirmationDialog(PcView.this, computer, () -> {
                if (managerBinder != null) {
                    removeComputer(computer);
                }
            }, null);
        });

        String statusStr = computer.state == ComputerDetails.State.ONLINE ? getString(R.string.sl_online)
                : (computer.state == ComputerDetails.State.OFFLINE ? getString(R.string.sl_offline) : getString(R.string.sl_connecting));
        new AlertDialog.Builder(this)
                .setTitle(computer.name + " - " + statusStr)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.sl_cancel, null)
                .show();
    }

    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            NvHTTP httpConn;
            String message;
            boolean success = false;
            try {
                stopComputerUpdates(true);

                httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                        PlatformBinding.getCryptoProvider(PcView.this));
                if (httpConn.getPairState() == PairState.PAIRED) {
                    message = null;
                    success = true;
                } else {
                    final String pinStr = PairingManager.generatePinString();
                    Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                            getResources().getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                                    getResources().getString(R.string.pair_pairing_help), false);

                    PairingManager pm = httpConn.getPairingManager();
                    PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr);
                    if (pairState == PairState.PIN_WRONG) {
                        message = getResources().getString(R.string.pair_incorrect_pin);
                    } else if (pairState == PairState.FAILED) {
                        message = computer.runningGameId != 0
                                ? getResources().getString(R.string.pair_pc_ingame)
                                : getResources().getString(R.string.pair_fail);
                    } else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                        message = getResources().getString(R.string.pair_already_in_progress);
                    } else if (pairState == PairState.PAIRED) {
                        message = null;
                        success = true;
                        managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();
                        managerBinder.invalidateStateForComputer(computer.uuid);
                    } else {
                        message = null;
                    }
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                e.printStackTrace();
                message = e.getMessage();
            }

            Dialog.closeDialogs();

            final String toastMessage = message;
            final boolean toastSuccess = success;
            runOnUiThread(() -> {
                if (toastMessage != null) {
                    Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                }
                if (toastSuccess) {
                    doAppList(computer, true, false);
                } else {
                    startComputerUpdates();
                }
            });
        }).start();
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }
        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String message;
            try {
                WakeOnLanSender.sendWolPacket(computer);
                message = getResources().getString(R.string.wol_waking_msg);
            } catch (IOException e) {
                message = getResources().getString(R.string.wol_fail);
            }
            final String toastMessage = message;
            runOnUiThread(() -> Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                NvHTTP httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                        PlatformBinding.getCryptoProvider(PcView.this));
                if (httpConn.getPairState() == PairState.PAIRED) {
                    httpConn.unpair();
                    message = httpConn.getPairState() == PairState.NOT_PAIRED
                            ? getResources().getString(R.string.unpair_success)
                            : getResources().getString(R.string.unpair_fail);
                } else {
                    message = getResources().getString(R.string.unpair_error);
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                message = e.getMessage();
            }
            final String toastMessage = message;
            runOnUiThread(() -> Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);
        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);
        DeviceBrowserPrefs.clearForHost(this, details.uuid);
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();
        shortcutHelper.disableComputerShortcut(details, getResources().getString(R.string.scut_deleted_pc));
        computersByUuid.remove(details.uuid);
        desktopCoverCache.remove(details.uuid);
        lastSignature = null;
        renderDeviceList();
    }

    private int dp(float v) {
        return SideLanternTheme.dp(this, v);
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}
