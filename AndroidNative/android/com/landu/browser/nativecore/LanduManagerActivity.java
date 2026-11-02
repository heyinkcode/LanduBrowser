package com.landu.browser.nativecore;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ContentResolver;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.chromium.chrome.base.R;

public final class LanduManagerActivity extends Activity {
    private static final int BLUE = 0xff087bff;
    private static final int TEXT = 0xff202632;
    private static final int MUTED = 0xff667085;
    private static final int BORDER = 0xffe3e7ef;
    private static final int BUTTON_HEIGHT = 48;
    private static final long PRESS_DURATION = 180;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EnvironmentCatalog catalog;
    private EnvironmentLeaseStore leases;
    private EnvironmentCatalog.Snapshot snapshot;
    private EnvironmentLeaseStore.Observation observation;
    private final java.util.Map<EnvironmentIdentity, TextView> statusLabels = new java.util.HashMap<>();
    private final java.util.Map<EnvironmentIdentity, Button> launchButtons = new java.util.HashMap<>();
    private TextView activeStatus;
    private Button closeCurrent;
    private Button saveEnvironment;
    private Button cancelEditor;
    private boolean resumed;
    private boolean checkingStatus;
    private long uiGeneration;
    private final Runnable statusPoll = this::pollStatus;
    private NativeSessionClient sessionClient;
    private LinearLayout content;
    private ScrollView mainScroll;
    private LinearLayout fixedActions;
    private LinearLayout actionBody;
    private boolean compactActions;
    private TextView feedback;
    private boolean working;
    private boolean editing;
    private EnvironmentDefinition original;
    private long editorRevision;
    private int selectedCpu;
    private int selectedMemory;
    private int selectedDnt;
    private Button dntChoice;
    private EditText nameField;
    private EditText urlField;
    private EditText notesField;
    private EditText tagsField;
    private String selectedGroupId = "";
    private Button editorGroupChoice;
    private LinearLayout environmentRows;
    private EditText searchField;
    private Button groupFilterChoice;
    private String filterGroupId;
    private String listQuery = "";
    private int listPage;
    private static final int LIST_PAGE_SIZE = 20;
    private final Runnable filterRefresh = () -> {
        if (resumed && !editing && environmentRows != null && !isDestroyed()) renderEnvironmentCards();
    };
    private AlertDialog groupNameDialog;
    private EditText groupNameField;
    private String groupNameIdentity;
    private long groupNameRevision;
    private static final int REQUEST_EXPORT_CONFIG = 3101;
    private static final int REQUEST_IMPORT_CONFIG = 3102;
    private int pendingDocumentRequest;
    private int queuedDocumentRequest;
    private Uri queuedDocumentUri;
    private String pendingImportUri;
    private String documentJobId;
    private boolean documentBusy;
    private boolean updateImportedExisting;
    private AlertDialog importPreviewDialog;
    private Switch importUpdateChoice;
    private final EnvironmentTransferController.Observer documentObserver = this::documentCompleted;
    private Switch fontIsolationChoice;
    private Switch canvasNoiseChoice;
    private Switch audioNoiseChoice;
    private Switch muteAudioChoice;
    private Switch clientRectsNoiseChoice;
    private int selectedMediaMode;
    private Button mediaModeChoice;
    private Switch blockMediaChoice;
    private EditText[] mediaLabels;
    private LinearLayout mediaLabelFields;
    private EditText proxyField;
    private Button proxyProtocolChoice;
    private String proxyProtocol = "socks5";
    private Switch proxyAuthenticationChoice;
    private Switch replaceProxyCredentialsChoice;
    private LinearLayout proxyCredentialFields;
    private EditText proxyUsernameField;
    private EditText proxyPasswordField;
    private Switch webRtcChoice;
    private EditText languagesField;
    private EditText timeZoneField;
    private Button regionChoice;
    private Button languagePolicyChoice;
    private Button timeZonePolicyChoice;
    private EditText preferredLanguagesField;
    private EnvironmentRegionalPolicy.LanguageMode languageMode;
    private EnvironmentRegionalPolicy.TimeZoneMode timeZoneMode;
    private EnvironmentRegionalPolicy.LocationMode locationMode;
    private Switch coordinatesFromIpChoice;
    private static final java.util.List<EnvironmentRegionalPolicy.LanguageMode> LANGUAGE_POLICIES = java.util.List.of(
            EnvironmentRegionalPolicy.LanguageMode.EXIT_IP, EnvironmentRegionalPolicy.LanguageMode.SYSTEM, EnvironmentRegionalPolicy.LanguageMode.FIXED);
    private static final java.util.List<EnvironmentRegionalPolicy.TimeZoneMode> ZONE_POLICIES = java.util.List.of(
            EnvironmentRegionalPolicy.TimeZoneMode.EXIT_IP, EnvironmentRegionalPolicy.TimeZoneMode.SYSTEM, EnvironmentRegionalPolicy.TimeZoneMode.CUSTOM);
    private static final java.util.List<EnvironmentRegionalPolicy.LocationMode> LOCATION_POLICIES = java.util.List.of(
            EnvironmentRegionalPolicy.LocationMode.EXIT_IP, EnvironmentRegionalPolicy.LocationMode.PROMPT,
            EnvironmentRegionalPolicy.LocationMode.BLOCKED, EnvironmentRegionalPolicy.LocationMode.CUSTOM);
    private int selectedGeolocationMode;
    private Button geolocationChoice;
    private LinearLayout geolocationCustomFields;
    private EditText latitudeField;
    private EditText longitudeField;
    private EditText accuracyField;
    private Switch automaticUaChoice;
    private Switch uaMobileChoice;
    private Switch uaWow64Choice;
    private LinearLayout uaCustomFields;
    private EditText[] uaFields;
    private Switch deviceRafChoice;
    private EditText rafRateField;
    private Switch followPhoneIdentity;
    private EditText androidVersionField;
    private EditText androidModelField;
    private Switch followGpuIdentity;
    private EditText[] gpuFields;
    private LinearLayout gpuCustomFields;
    private Bundle restoredDraft;
    private String externalLink;
    private android.window.OnBackInvokedCallback backCallback;
    private AccountDialogState accountDialogState;
    private static final int REQUEST_IMPORT_COOKIES = 3111;
    private static final int REQUEST_EXPORT_COOKIES = 3112;
    private int pendingCookieRequest;
    private String pendingCookieOwner;
    private long pendingCookieCreatedAt;
    private Uri queuedCookieUri;
    private CookieTransferClient cookieClient;
    private AlertDialog cookiePreviewDialog;
    private boolean restoredCookieTransferUnknown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        restoredDraft = state;
        if (state != null) {
            restoredCookieTransferUnknown = state.getBoolean("cookieTransferPending", false);
            pendingCookieRequest = state.getInt("pendingCookieRequest", 0);
            pendingCookieOwner = state.getString("pendingCookieOwner");
            pendingCookieCreatedAt = state.getLong("pendingCookieCreatedAt", 0);
            String cookieUri = state.getString("queuedCookieUri");
            if (cookieUri != null) queuedCookieUri = Uri.parse(cookieUri);
            if (pendingCookieRequest != REQUEST_IMPORT_COOKIES && pendingCookieRequest != REQUEST_EXPORT_COOKIES) {
                pendingCookieRequest = 0; pendingCookieOwner = null; queuedCookieUri = null;
            }
            pendingDocumentRequest = state.getInt("pendingDocumentRequest", 0);
            if (pendingDocumentRequest != REQUEST_EXPORT_CONFIG && pendingDocumentRequest != REQUEST_IMPORT_CONFIG) pendingDocumentRequest = 0;
            queuedDocumentRequest = state.getInt("queuedDocumentRequest", 0);
            String queued = state.getString("queuedDocumentUri");
            if (queued != null) queuedDocumentUri = Uri.parse(queued);
            pendingImportUri = state.getString("pendingImportUri");
            documentJobId = state.getString("documentJobId");
            updateImportedExisting = state.getBoolean("updateImportedExisting", false);
        }
        if (state != null) externalLink = state.getString("externalLink");
        acceptExternalLink(getIntent());
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = () -> { if (editing) leaveEditor(); else finish(); };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        LinearLayout shell = vertical();
        shell.setBackgroundColor(0xfff6f8fc);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            shell.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                compactActions = keyboard.bottom > bars.bottom
                        && getResources().getConfiguration().orientation
                                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                updateActionPadding();
                return insets;
            });
        }
        ScrollView scroll = new ScrollView(this);
        mainScroll = scroll;
        scroll.setFillViewport(true);
        content = vertical();
        content.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(content);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        fixedActions = vertical();
        fixedActions.setBackgroundColor(Color.WHITE);
        fixedActions.setVisibility(View.GONE);
        shell.addView(fixedActions, new LinearLayout.LayoutParams(-1, -2));
        setContentView(shell);
        shell.post(this::applySystemBarStyle);
        content.addView(text("岚渡浏览器", 26, 500, TEXT));
        content.addView(text("正在读取本机环境…", 14, 300, MUTED));
        runWork(() -> {
            Path control = getNoBackupFilesDir().toPath().resolve("landu-control");
            catalog = new EnvironmentCatalog(control);
            leases = new EnvironmentLeaseStore(control);
            return new ListSnapshot(catalog.read(), leases.observe());
        }, result -> {
            ListSnapshot loaded = (ListSnapshot) result;
            snapshot = loaded.catalog;
            observation = loaded.observation;
            sessionClient = new NativeSessionClient(this, leases, worker);
            restoreOrList();
        });
    }

    private void restoreOrList() {
        Bundle draft = restoredDraft;
        restoredDraft = null;
        if (draft != null) {
            listQuery = draft.getString("listQuery", "");
            filterGroupId = draft.getString("filterGroupId");
            listPage = Math.max(0, draft.getInt("listPage", 0));
        }
        if (draft != null && draft.getBoolean("editing")) {
            try {
                String id = draft.getString("identity");
                EnvironmentDefinition environment = id == null ? null : snapshot.find(EnvironmentIdentity.parse(id));
                showEditor(environment);
                editorRevision = draft.getLong("editorRevision", snapshot.revision);
                nameField.setText(draft.getString("name", ""));
                urlField.setText(draft.getString("url", ""));
                notesField.setText(draft.getString("notes", ""));
                tagsField.setText(draft.getString("tags", tagsField.getText().toString()));
                selectedGroupId = draft.getString("selectedGroupId", selectedGroupId);
                if (selectedGroupId == null) selectedGroupId = "";
                updateEditorGroupLabel();
                selectedCpu = draft.getInt("cpu", selectedCpu);
                selectedMemory = draft.getInt("memory", selectedMemory);
                selectedDnt = draft.getInt("doNotTrack", selectedDnt);
                if (selectedDnt < FingerprintSettings.DNT_DEFAULT || selectedDnt > FingerprintSettings.DNT_DISABLED) {
                    selectedDnt = FingerprintSettings.DNT_DEFAULT;
                }
                updateDntLabel();
                fontIsolationChoice.setChecked(draft.getBoolean("regionalFontIsolation", fontIsolationChoice.isChecked()));
                canvasNoiseChoice.setChecked(draft.getBoolean("canvasNoise", canvasNoiseChoice.isChecked()));
                audioNoiseChoice.setChecked(draft.getBoolean("audioNoise", audioNoiseChoice.isChecked()));
                muteAudioChoice.setChecked(draft.getBoolean("muteAudio", muteAudioChoice.isChecked()));
                clientRectsNoiseChoice.setChecked(draft.getBoolean("clientRectsNoise", clientRectsNoiseChoice.isChecked()));
                selectedMediaMode = draft.getInt("mediaMode", selectedMediaMode);
                if (selectedMediaMode < 0 || selectedMediaMode > 2) selectedMediaMode = EnvironmentMediaSettings.HIDDEN_LABELS;
                blockMediaChoice.setChecked(draft.getBoolean("blockMedia", blockMediaChoice.isChecked()));
                for (int i = 0; i < mediaLabels.length; ++i) mediaLabels[i].setText(draft.getString("mediaLabel" + i, mediaLabels[i].getText().toString()));
                updateMediaFields();
                proxyProtocol = draft.getString("proxyProtocol", proxyProtocol);
                if (!(proxyProtocol.equals("http") || proxyProtocol.equals("https") || proxyProtocol.equals("socks5"))) proxyProtocol = "socks5";
                proxyProtocolChoice.setText(proxyProtocolLabel());
                proxyField.setText(draft.getString("proxy", proxyField.getText().toString()));
                proxyAuthenticationChoice.setChecked(draft.getBoolean("proxyAuthentication", proxyAuthenticationChoice.isChecked()));
                replaceProxyCredentialsChoice.setChecked(draft.getBoolean("replaceProxyCredentials", replaceProxyCredentialsChoice.isChecked()));
                webRtcChoice.setChecked(draft.getBoolean("restrictWebRtcUdp", webRtcChoice.isChecked()));
                languagesField.setText(draft.getString("languages", languagesField.getText().toString()));
                timeZoneField.setText(draft.getString("timeZone", timeZoneField.getText().toString()));
                languageMode = draftPolicy(draft, "languagePolicy", languageMode);
                timeZoneMode = draftPolicy(draft, "zonePolicy", timeZoneMode);
                locationMode = draftPolicy(draft, "geoPolicy", locationMode);
                preferredLanguagesField.setText(draft.getString("preferredLanguages", preferredLanguagesField.getText().toString()));
                updateRegionalPolicyFields();
                selectedGeolocationMode = draft.getInt("geolocationMode", selectedGeolocationMode);
                if (selectedGeolocationMode < EnvironmentGeolocationSettings.SYSTEM
                        || selectedGeolocationMode > EnvironmentGeolocationSettings.BLOCKED) {
                    selectedGeolocationMode = EnvironmentGeolocationSettings.BLOCKED;
                }
                latitudeField.setText(draft.getString("latitude", latitudeField.getText().toString()));
                longitudeField.setText(draft.getString("longitude", longitudeField.getText().toString()));
                accuracyField.setText(draft.getString("accuracy", accuracyField.getText().toString()));
                updateGeolocationFields();
                androidVersionField.setText(draft.getString("androidVersion", androidVersionField.getText().toString()));
                androidModelField.setText(draft.getString("androidModel", androidModelField.getText().toString()));
                followPhoneIdentity.setChecked(draft.getBoolean("followPhoneIdentity", followPhoneIdentity.isChecked()));
                updateIdentityFields();
                String[] uaDraft = draft.getStringArray("userAgentFields");
                if (uaDraft != null && uaDraft.length == uaFields.length) for (int i = 0; i < uaFields.length; i++) uaFields[i].setText(uaDraft[i]);
                uaMobileChoice.setChecked(draft.getBoolean("uaMobile", uaMobileChoice.isChecked()));
                uaWow64Choice.setChecked(draft.getBoolean("uaWow64", uaWow64Choice.isChecked()));
                automaticUaChoice.setChecked(draft.getBoolean("automaticUa", automaticUaChoice.isChecked()));
                updateUserAgentFields();
                rafRateField.setText(draft.getString("rafRate", rafRateField.getText().toString()));
                deviceRafChoice.setChecked(draft.getBoolean("deviceRaf", deviceRafChoice.isChecked()));
                updateRafFields();
                String[] gpuDraft = draft.getStringArray("gpuMetadataFields");
                if (gpuDraft != null && gpuDraft.length == gpuFields.length) {
                    for (int i = 0; i < gpuFields.length; i++) gpuFields[i].setText(gpuDraft[i]);
                }
                followGpuIdentity.setChecked(draft.getBoolean("followGpuIdentity", followGpuIdentity.isChecked()));
                updateGpuFields();
                updateHardwareLabels();
                return;
            } catch (IOException | IllegalArgumentException ignored) { }
        }
        showList();
        if (draft != null && draft.getBoolean("groupNameDialog")) {
            showGroupNameDialog(draft.getString("groupNameIdentity"),
                    draft.getString("groupName", ""), draft.getLong("groupNameRevision", snapshot.revision));
        }
        resumeDocumentWork();
    }

    private void heading(String title, String description) {
        closeAccountDialog();
        View focused = content.findFocus();
        if (focused instanceof EditText) {
            focused.clearFocus();
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) controller.hide(WindowInsets.Type.ime());
            } else {
                android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }
        content.removeAllViews();
        fixedActions.removeAllViews();
        actionBody = null;
        fixedActions.setVisibility(View.GONE);
        closeCurrent = null;
        saveEnvironment = null;
        cancelEditor = null;
        content.addView(text(title, 26, 500, TEXT));
        TextView subtitle = text(description, 14, 300, MUTED);
        subtitle.setPadding(0, dp(8), 0, dp(16));
        content.addView(subtitle);
        feedback = text("", 14, 400, 0xffb42318);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setMinHeight(dp(28));
        content.addView(feedback);
    }

    private void showList() {
        if (proxyPasswordField != null) proxyPasswordField.setText("");
        if (proxyUsernameField != null) proxyUsernameField.setText("");
        uiGeneration++;
        boolean returning = editing;
        editing = false;
        original = null;
        statusLabels.clear();
        launchButtons.clear();
        heading("岚渡浏览器", "本机环境 · 数据保存在你的手机");
        activeStatus = text("正在读取运行状态…", 14, 400, MUTED);
        content.addView(activeStatus, block(-2, 0, 16));
        if (externalLink != null) {
            LinearLayout navigation = card();
            navigation.addView(text("选择环境打开链接", 18, 500, TEXT));
            TextView link = text(externalLink, 14, 300, MUTED);
            link.setMaxLines(3);
            link.setEllipsize(android.text.TextUtils.TruncateAt.END);
            navigation.addView(link, block(-2, 8, 12));
            navigation.addView(button("取消打开链接", false, () -> { externalLink = null; showList(); }), block(BUTTON_HEIGHT, 0, 0));
            content.addView(navigation, block(-2, 0, 16));
        }
        Button create = button("＋ 新建环境", true, () -> showEditor(null));
        content.addView(create, block(BUTTON_HEIGHT, 0, 16));
        if (filterGroupId != null && !filterGroupId.isEmpty() && !groupExists(filterGroupId)) filterGroupId = null;
        groupFilterChoice = button(filterGroupId == null ? "全部分组" : snapshot.groupName(filterGroupId), false, this::chooseFilterGroup);
        groupFilterChoice.setSingleLine(true);
        groupFilterChoice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(actionRow(groupFilterChoice, button("管理分组", false, this::manageGroups),
                button("更多", false, this::showListActions)), block(BUTTON_HEIGHT, 0, 12));
        searchField = field(content, "搜索环境", "名称、编号、标签或备注", listQuery, false, false);
        searchField.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(160)});
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                listQuery = value.toString();
                listPage = 0;
                main.removeCallbacks(filterRefresh);
                if (!editing) main.postDelayed(filterRefresh, 180);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        environmentRows = vertical();
        content.addView(environmentRows, block(-2, 0, 0));
        closeCurrent = button("关闭当前环境", false, this::stopCurrent);
        setFixedActions(closeCurrent);
        content.addView(button("关于岚渡浏览器", false, this::showAbout), block(BUTTON_HEIGHT, 8, 8));
        renderEnvironmentCards();
        scheduleStatusPoll();
        enterPage(returning ? -1 : 0);
        if (returning) resumeDocumentWork();
    }

    private void renderEnvironmentCards() {
        main.removeCallbacks(filterRefresh);
        environmentRows.removeAllViews();
        statusLabels.clear();
        launchButtons.clear();
        java.util.List<EnvironmentDefinition> filtered = snapshot.filter(filterGroupId, listQuery);
        int pages = Math.max(1, (filtered.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        listPage = Math.max(0, Math.min(listPage, pages - 1));
        environmentRows.addView(text("共 " + filtered.size() + " 个环境 · 第 " + (listPage + 1) + " / " + pages + " 页",
                14, 300, MUTED), block(-2, 0, 12));
        if (snapshot.environments.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("创建你的第一个环境", 18, 500, TEXT));
            empty.addView(text("为不同账号建立独立浏览空间。环境名称和启动页都可以稍后修改。", 15, 300, MUTED));
            environmentRows.addView(empty, block(-2, 0, 12));
        } else if (filtered.isEmpty()) {
            environmentRows.addView(text("没有匹配的环境，请调整关键词或分组。", 16, 300, MUTED), block(-2, 8, 16));
        }
        int start = listPage * LIST_PAGE_SIZE;
        for (EnvironmentDefinition environment : filtered.subList(start, Math.min(filtered.size(), start + LIST_PAGE_SIZE))) {
            LinearLayout row = card();
            row.addView(text(String.format(java.util.Locale.ROOT, "%03d", environment.organization.number)
                    + " · " + environment.name, 18, 500, TEXT));
            TextView classification = text(snapshot.groupName(environment.organization.groupId)
                    + (environment.organization.tags.isEmpty() ? "" : " · " + environment.organization.tags), 14, 300, MUTED);
            classification.setMaxLines(2);
            classification.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(classification, block(-2, 6, 0));
            TextView status = text("", 14, 400, MUTED);
            statusLabels.put(environment.identity, status);
            row.addView(status, block(-2, 6, 2));
            TextView address = text(environment.startPage.equals("about:blank") ? "空白启动页" : environment.startPage, 14, 300, MUTED);
            address.setMaxLines(2);
            address.setEllipsize(android.text.TextUtils.TruncateAt.END);
            address.setPadding(0, dp(6), 0, dp(12));
            row.addView(address);
            TextView network = text(environment.network.summary() + "\n实际出口：启动前检测", 14, 300, MUTED);
            network.setMaxLines(5);
            network.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(network, block(-2, 0, 12));
            loadExitSummary(environment, network);
            if (environment.regionalPolicy.needsExitLookup()) {
                row.addView(text("自动策略：语言 " + (environment.regionalPolicy.languageMode == EnvironmentRegionalPolicy.LanguageMode.EXIT_IP ? "按出口 IP" : "手动/设备")
                        + " · 时区 " + (environment.regionalPolicy.timeZoneMode == EnvironmentRegionalPolicy.TimeZoneMode.EXIT_IP ? "按出口 IP" : "手动/设备")
                        + " · 定位 " + (environment.regionalPolicy.coordinatesFromIp() ? "按出口 IP" : "手动/询问/禁止"), 14, 300, MUTED), block(-2, 0, 12));
            } else if (!environment.region.languages.isEmpty() || !environment.region.timeZone.isEmpty()) {
                EnvironmentRegionSettings.Preset preset = environment.region.preset();
                String region = "语言与时区：" + (preset == null ? "自定义" : preset.label)
                        + " · " + (environment.region.languages.isEmpty() ? "设备语言" : environment.region.primaryLanguage())
                        + " · " + (environment.region.timeZone.isEmpty() ? "设备时区" : environment.region.timeZone);
                TextView regional = text(region, 14, 300, MUTED);
                regional.setMaxLines(2);
                row.addView(regional, block(-2, 0, 12));
            }
            Button edit = button("编辑", false, () -> showEditor(environment));
            Button launch = button("启动", true, () -> launch(environment));
            launchButtons.put(environment.identity, launch);
            Button recycle = button("回收", false, () -> confirmRecycle(environment));
            recycle.setContentDescription("移入回收站：" + environment.name);
            row.addView(actionRow(edit, launch, recycle));
            environmentRows.addView(row, block(-2, 0, 12));
        }
        if (pages > 1) {
            Button previous = button("上一页", false, () -> changeListPage(listPage - 1));
            Button next = button("下一页", false, () -> changeListPage(listPage + 1));
            previous.setEnabled(listPage > 0); next.setEnabled(listPage + 1 < pages);
            previous.setAlpha(previous.isEnabled() ? 1 : .5f); next.setAlpha(next.isEnabled() ? 1 : .5f);
            environmentRows.addView(actionRow(previous, next), block(BUTTON_HEIGHT, 4, 8));
        }
        updateStatusControls();
    }

    private void changeListPage(int page) {
        listPage = page;
        renderEnvironmentCards();
        final LinearLayout rows = environmentRows;
        final long generation = uiGeneration;
        rows.post(() -> {
            if (editing || isDestroyed() || generation != uiGeneration || rows != environmentRows) return;
            // 翻页后回到结果起点；系统关闭动画时直接定位。
            if (ValueAnimator.areAnimatorsEnabled()) mainScroll.smoothScrollTo(0, rows.getTop());
            else mainScroll.scrollTo(0, rows.getTop());
        });
    }

    private LinearLayout actionRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), 1);
            if (i + 1 < buttons.length) item.setMarginEnd(dp(8));
            row.addView(buttons[i], item);
        }
        return row;
    }

    private boolean groupExists(String id) {
        if (id.isEmpty()) return true;
        for (EnvironmentGroup group : snapshot.groups) if (group.id.equals(id)) return true;
        return false;
    }

    private void chooseFilterGroup() {
        String[] labels = new String[snapshot.groups.size() + 2];
        labels[0] = "全部分组"; labels[1] = "未分组";
        for (int i = 0; i < snapshot.groups.size(); i++) labels[i + 2] = snapshot.groups.get(i).name;
        new AlertDialog.Builder(this).setTitle("筛选分组").setItems(labels, (dialog, index) -> {
            filterGroupId = index == 0 ? null : index == 1 ? "" : snapshot.groups.get(index - 2).id;
            listPage = 0;
            groupFilterChoice.setText(labels[index]);
            groupFilterChoice.setContentDescription("筛选分组：" + labels[index]);
            renderEnvironmentCards();
        }).setNegativeButton("取消", null).show();
    }

    private void chooseEditorGroup() {
        String[] labels = new String[snapshot.groups.size() + 1];
        labels[0] = "未分组";
        for (int i = 0; i < snapshot.groups.size(); i++) labels[i + 1] = snapshot.groups.get(i).name;
        new AlertDialog.Builder(this).setTitle("归属分组").setItems(labels, (dialog, index) -> {
            selectedGroupId = index == 0 ? "" : snapshot.groups.get(index - 1).id;
            updateEditorGroupLabel();
        }).setNegativeButton("取消", null).show();
    }

    private void updateEditorGroupLabel() {
        editorGroupChoice.setText(snapshot.groupName(selectedGroupId));
        editorGroupChoice.setContentDescription("归属分组：" + snapshot.groupName(selectedGroupId));
    }

    private void manageGroups() {
        String[] labels = new String[snapshot.groups.size() + 1];
        labels[0] = "＋ 新建分组";
        for (int i = 0; i < snapshot.groups.size(); i++) labels[i + 1] = snapshot.groups.get(i).name;
        new AlertDialog.Builder(this).setTitle("管理分组").setItems(labels, (dialog, index) -> {
            if (index == 0) { showGroupNameDialog(null, "", snapshot.revision); return; }
            EnvironmentGroup group = snapshot.groups.get(index - 1);
            long expected = snapshot.revision;
            new AlertDialog.Builder(this).setTitle(group.name)
                    .setItems(new String[] {"重命名", "移除分组"}, (menu, action) -> {
                        if (action == 0) showGroupNameDialog(group.id, group.name, expected);
                        else new AlertDialog.Builder(this).setTitle("移除分组？")
                                .setMessage("“" + group.name + "”中的环境将移到未分组。环境和浏览数据会保留。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("移除分组", (confirm, which) -> runWork(
                                        () -> catalog.removeGroup(expected, group.id), result -> {
                                            snapshot = (EnvironmentCatalog.Snapshot) result;
                                            if (group.id.equals(filterGroupId)) filterGroupId = null;
                                            showList();
                                            feedback.setTextColor(0xff168660);
                                            feedback.setText("分组已移除，环境已移到未分组");
                                        })).show();
                    }).setNegativeButton("取消", null).show();
        }).setNegativeButton("关闭", null).show();
    }

    private void showGroupNameDialog(String identity, String name, long expectedRevision) {
        if (working || isDestroyed()) return;
        if (groupNameDialog != null && groupNameDialog.isShowing()) return;
        if (identity != null && !groupExists(identity)) { showError("分组已被移除，请刷新列表"); return; }
        groupNameIdentity = identity;
        groupNameRevision = expectedRevision;
        LinearLayout form = vertical();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        groupNameField = field(form, "分组名称", "例如：跨境业务", name, false, false);
        groupNameField.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(60)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(identity == null ? "新建分组" : "重命名分组")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        groupNameDialog = dialog;
        dialog.setOnDismissListener(closed -> { if (groupNameDialog == dialog) groupNameDialog = null; });
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (working) return;
            final EnvironmentGroup group;
            try {
                group = identity == null ? EnvironmentGroup.create(groupNameField.getText().toString())
                        : new EnvironmentGroup(identity, groupNameField.getText().toString());
                for (EnvironmentGroup existing : snapshot.groups) {
                    if (!existing.id.equals(group.id) && existing.nameKey().equals(group.nameKey())) {
                        throw new IllegalArgumentException("分组名称已存在");
                    }
                }
            } catch (IllegalArgumentException error) {
                groupNameField.setError(error.getMessage()); groupNameField.requestFocus(); return;
            }
            dialog.dismiss();
            runWork(() -> catalog.saveGroup(expectedRevision, group), result -> {
                snapshot = (EnvironmentCatalog.Snapshot) result;
                showList();
                feedback.setTextColor(0xff168660);
                feedback.setText(identity == null ? "分组已创建" : "分组名称已更新");
            });
        }));
        dialog.show();
    }

    private void showListActions() {
        new AlertDialog.Builder(this).setTitle("环境管理").setItems(new String[] {"刷新列表", "重新编号", "关闭当前环境", "导出环境配置", "导入环境配置", "回收站（" + snapshot.recycledEnvironments.size() + "）"},
                (dialog, action) -> {
                    if (action == 0) runWork(() -> new ListSnapshot(catalog.read(), leases.observe()), result -> {
                        ListSnapshot loaded = (ListSnapshot) result;
                        snapshot = loaded.catalog; observation = loaded.observation; showList();
                    });
                    else if (action == 1) {
                        long expected = snapshot.revision;
                        new AlertDialog.Builder(this).setTitle("重新编号？")
                                .setMessage("按全部环境（含回收站）的保存顺序从 1 开始编号。环境标识、分组和浏览数据保持不变。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("重新编号", (confirm, which) -> runWork(() -> catalog.renumber(expected), result -> {
                                    snapshot = (EnvironmentCatalog.Snapshot) result; showList();
                                    feedback.setTextColor(0xff168660); feedback.setText("环境编号已更新");
                                })).show();
                    } else if (action == 2) stopCurrent();
                    else if (action == 3) chooseConfigurationExport();
                    else if (action == 4) chooseConfigurationImport();
                    else showRecycleBin(0);
                }).setNegativeButton("取消", null).show();
    }

    private void confirmRecycle(EnvironmentDefinition environment) {
        if (working || isDestroyed()) return;
        long expected = snapshot.revision;
        new AlertDialog.Builder(this).setTitle("移入回收站？")
                .setMessage("“" + environment.name + "”将从环境列表移除。登录状态、指纹与浏览数据都会保留，可在“更多 → 回收站”恢复。请先关闭运行中或待启动的环境。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移入回收站", (dialog, which) -> runWork(
                        () -> catalog.recycle(expected, environment.identity), result -> {
                            snapshot = (EnvironmentCatalog.Snapshot) result;
                            showList();
                            feedback.setTextColor(0xff168660);
                            feedback.setText("已移入回收站，可随时恢复");
                        })).show();
    }

    private void showRecycleBin(int requestedPage) {
        if (working || isDestroyed()) return;
        java.util.List<EnvironmentDefinition> entries = snapshot.recycledEnvironments;
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("回收站")
                    .setMessage("回收站为空。移入这里的环境会保留浏览数据，可随时恢复。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        int pages = (entries.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE;
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int start = page * LIST_PAGE_SIZE;
        int count = Math.min(LIST_PAGE_SIZE, entries.size() - start);
        String[] labels = new String[count + (page > 0 ? 1 : 0) + (page + 1 < pages ? 1 : 0)];
        for (int i = 0; i < count; i++) {
            EnvironmentDefinition environment = entries.get(start + i);
            labels[i] = String.format(java.util.Locale.ROOT, "%03d", environment.organization.number)
                    + " · " + environment.name;
        }
        if (page > 0) labels[count] = "上一页";
        if (page + 1 < pages) labels[labels.length - 1] = "下一页";
        long expected = snapshot.revision;
        new AlertDialog.Builder(this).setTitle("回收站 · " + entries.size() + " 个 · " + (page + 1) + "/" + pages)
                .setItems(labels, (dialog, index) -> {
                    if (index >= count) { showRecycleBin(index == count && page > 0 ? page - 1 : page + 1); return; }
                    EnvironmentDefinition environment = entries.get(start + index);
                    new AlertDialog.Builder(this).setTitle(environment.name)
                            .setMessage("恢复后将回到原分组，继续使用原来的指纹、登录状态和浏览数据。")
                            .setNegativeButton("取消", (closed, which) -> showRecycleBin(page))
                            .setPositiveButton("恢复环境", (closed, which) -> runWork(
                                    () -> catalog.restore(expected, environment.identity), result -> {
                                        snapshot = (EnvironmentCatalog.Snapshot) result;
                                        showList();
                                        feedback.setTextColor(0xff168660);
                                        feedback.setText("环境已恢复");
                                    })).show();
                }).setNegativeButton("关闭", null).show();
    }

    private void chooseConfigurationExport() {
        new AlertDialog.Builder(this).setTitle("导出全部环境配置")
                .setMessage("包含全部环境（含回收站）的名称、分组、标签和已支持的指纹、网络设置。不包含 Cookie、密码、历史记录或其他浏览数据。")
                .setNegativeButton("取消", null).setPositiveButton("选择保存位置", (dialog, which) -> {
                    String date = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
                    Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "landu-config-" + date + ".bin")
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    launchDocumentPicker(REQUEST_EXPORT_CONFIG, picker);
                }).show();
    }

    private void chooseConfigurationImport() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launchDocumentPicker(REQUEST_IMPORT_CONFIG, picker);
    }

    private void launchDocumentPicker(int request, Intent intent) {
        if (working || pendingDocumentRequest != 0 || documentJobId != null) return;
        hideKeyboard();
        pendingDocumentRequest = request;
        try { startActivityForResult(intent, request); }
        catch (android.content.ActivityNotFoundException | SecurityException error) {
            pendingDocumentRequest = 0;
            showError("无法打开系统文件选择器，请稍后重试");
        }
    }

    @Override
    protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request == pendingCookieRequest && (request == REQUEST_IMPORT_COOKIES || request == REQUEST_EXPORT_COOKIES)) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && "content".equals(data.getData().getScheme()) && data.getData().toString().length() <= 8192) {
                queuedCookieUri = data.getData();
                consumeCookieSelection();
            } else { pendingCookieRequest = 0; pendingCookieOwner = null; queuedCookieUri = null; }
            return;
        }
        if (request != pendingDocumentRequest || (request != REQUEST_EXPORT_CONFIG && request != REQUEST_IMPORT_CONFIG)) return;
        pendingDocumentRequest = 0;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme()) || uri.toString().length() > 8192) {
            showError("请选择系统文件选择器提供的文件"); return;
        }
        queuedDocumentRequest = request;
        queuedDocumentUri = uri;
        consumeDocumentSelection();
    }

    private void consumeDocumentSelection() {
        if (working || catalog == null || snapshot == null || queuedDocumentUri == null) return;
        if (AndroidConfigurationJobs.get().currentId() != null) { resumeDocumentWork(); return; }
        final Uri uri = queuedDocumentUri;
        final int request = queuedDocumentRequest;
        queuedDocumentUri = null; queuedDocumentRequest = 0;
        final ContentResolver resolver = getApplicationContext().getContentResolver();
        final EnvironmentCatalog localCatalog = catalog;
        final EnvironmentLeaseStore localLeases = leases;
        if (request == REQUEST_EXPORT_CONFIG) {
            beginDocumentTask(EnvironmentTransferController.Kind.EXPORT, () -> {
                byte[] bytes = localCatalog.exportConfiguration();
                EnvironmentCatalog.Snapshot exported = EnvironmentCatalog.decodeConfiguration(bytes);
                try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("无法打开选定的保存位置");
                    output.write(bytes); output.flush();
                }
                return new int[] {exported.allEnvironments.size(), exported.groups.size()};
            });
        } else if (request == REQUEST_IMPORT_CONFIG) {
            pendingImportUri = uri.toString();
            updateImportedExisting = false;
            beginDocumentTask(EnvironmentTransferController.Kind.PREVIEW, () -> {
                final EnvironmentCatalog.Snapshot imported;
                try (InputStream input = resolver.openInputStream(uri)) {
                    imported = EnvironmentCatalog.readConfiguration(input);
                }
                return new DocumentPreview(localCatalog.read(), localLeases.observe(), imported);
            });
        }
    }

    private void beginDocumentTask(EnvironmentTransferController.Kind kind, EnvironmentTransferController.Task task) {
        if (working || isDestroyed()) return;
        try {
            documentJobId = AndroidConfigurationJobs.get().start(kind, task);
            documentBusy = true;
            working = true;
            uiGeneration++;
            feedback.setTextColor(MUTED); feedback.setText("正在处理文件…");
            updateStatusControls();
            AndroidConfigurationJobs.get().attach(documentJobId, documentObserver);
        } catch (RuntimeException error) { showError(error.getMessage()); }
    }

    private void resumeDocumentWork() {
        if (working && !documentBusy) return;
        EnvironmentTransferController jobs = AndroidConfigurationJobs.get();
        String current = jobs.currentId();
        if (current != null) {
            documentJobId = current;
            documentBusy = true;
            working = true;
            feedback.setTextColor(MUTED); feedback.setText("正在处理文件…");
            updateStatusControls();
            jobs.attach(current, documentObserver);
        } else {
            boolean interrupted = documentJobId != null;
            if (documentBusy) working = false;
            documentJobId = null;
            documentBusy = false;
            if (queuedDocumentUri == null && pendingImportUri != null) {
                queuedDocumentRequest = REQUEST_IMPORT_CONFIG;
                queuedDocumentUri = Uri.parse(pendingImportUri);
            }
            consumeDocumentSelection();
            if (interrupted && !working) showError("上次文件操作的结果未能恢复，请核对后再操作");
        }
    }

    private void documentCompleted(EnvironmentTransferController.Outcome outcome) {
        if (!resumed || isDestroyed() || !outcome.id.equals(documentJobId)) return;
        documentBusy = false;
        working = false;
        if (outcome.kind == EnvironmentTransferController.Kind.PREVIEW && outcome.error == null) {
            feedback.setText("");
            if (importPreviewDialog != null && importPreviewDialog.isShowing()) { updateStatusControls(); return; }
            DocumentPreview preview = (DocumentPreview) outcome.value;
            hideKeyboard();
            snapshot = preview.current; observation = preview.observation;
            showList();
            showImportPreview(outcome.id, preview);
            return;
        }
        AndroidConfigurationJobs.get().consume(outcome.id);
        documentJobId = null; pendingImportUri = null;
        if (outcome.error != null) { showError(outcome.error); updateStatusControls(); consumeDocumentSelection(); return; }
        if (outcome.kind == EnvironmentTransferController.Kind.IMPORT) {
            EnvironmentCatalog.ImportResult result = (EnvironmentCatalog.ImportResult) outcome.value;
            snapshot = result.snapshot; showList();
            feedback.setText(getString(R.string.landu_import_complete, result.added, result.updated,
                    result.skipped, result.groupsAdded,
                    getString(result.updated > 0 ? R.string.landu_import_next_launch : R.string.landu_period)));
        } else {
            int[] count = (int[]) outcome.value;
            feedback.setText(getString(R.string.landu_export_complete, count[0], count[1]));
        }
        feedback.setTextColor(0xff168660); updateStatusControls(); scheduleStatusPoll();
        consumeDocumentSelection();
    }

    private void showImportPreview(String id, DocumentPreview preview) {
        java.util.Set<EnvironmentIdentity> existing = new java.util.HashSet<>();
        for (EnvironmentDefinition environment : preview.current.allEnvironments) existing.add(environment.identity);
        int duplicate = 0;
        for (EnvironmentDefinition environment : preview.imported.allEnvironments) if (existing.contains(environment.identity)) duplicate++;
        LinearLayout form = vertical(); form.setPadding(dp(20), dp(8), dp(20), 0);
        form.addView(text("文件包含 " + preview.imported.allEnvironments.size() + " 个环境（回收站 " + preview.imported.recycledEnvironments.size() + " 个）、" + preview.imported.groups.size()
                + " 个分组；其中 " + duplicate + " 个环境在本机已有相同标识。", 16, 300, TEXT), block(-2, 0, 12));
        form.addView(text("默认保留本机已有配置，仅新增缺少的环境。分组重名时复用本机分组，新环境使用本机空闲编号。回收站中的同标识环境需先恢复才能覆盖；导入不会将本机正常环境移入回收站。此操作不迁移浏览数据。导入或覆盖的认证代理需在此手机重新填写账号密码。",
                14, 300, MUTED), block(-2, 0, 12));
        importUpdateChoice = toggle("更新同标识环境的配置", updateImportedExisting);
        importUpdateChoice.setOnCheckedChangeListener((view, checked) -> updateImportedExisting = checked);
        form.addView(importUpdateChoice, block(-2, 0, 8));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("确认导入配置").setView(form)
                .setNegativeButton("取消", (closed, which) -> cancelImportPreview(id))
                .setPositiveButton("合并导入", (closed, which) -> {
                    boolean update = importUpdateChoice.isChecked();
                    if (!AndroidConfigurationJobs.get().consume(id)) { showError("导入预览已失效，请重新选择文件"); return; }
                    final EnvironmentCatalog localCatalog = catalog;
                    documentJobId = null; pendingImportUri = null;
                    beginDocumentTask(EnvironmentTransferController.Kind.IMPORT,
                            () -> localCatalog.importConfiguration(preview.current.revision, preview.imported, update));
                }).create();
        importPreviewDialog = dialog;
        dialog.setOnCancelListener(closed -> cancelImportPreview(id));
        dialog.setOnDismissListener(closed -> { if (importPreviewDialog == dialog) importPreviewDialog = null; });
        dialog.show();
    }

    private void cancelImportPreview(String id) {
        if (!AndroidConfigurationJobs.get().consume(id)) return;
        documentJobId = null; pendingImportUri = null; updateImportedExisting = false;
        runWork(() -> new ListSnapshot(catalog.read(), leases.observe()), value -> {
            ListSnapshot latest = (ListSnapshot) value;
            snapshot = latest.catalog; observation = latest.observation; showList();
        });
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        android.view.inputmethod.InputMethodManager input =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (focused != null) {
            if (input != null) input.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
    }

    private static final class DocumentPreview {
        final EnvironmentCatalog.Snapshot current;
        final EnvironmentLeaseStore.Observation observation;
        final EnvironmentCatalog.Snapshot imported;
        DocumentPreview(EnvironmentCatalog.Snapshot current, EnvironmentLeaseStore.Observation observation,
                EnvironmentCatalog.Snapshot imported) {
            this.current = current; this.observation = observation; this.imported = imported;
        }
    }

    private void showEditor(EnvironmentDefinition environment) {
        if (working) return;
        uiGeneration++;
        main.removeCallbacks(statusPoll);
        editing = true;
        original = environment;
        editorRevision = snapshot.revision;
        selectedGroupId = environment == null ? (filterGroupId == null ? "" : filterGroupId) : environment.organization.groupId;
        heading(environment == null ? "新建环境" : "编辑环境", "每个环境使用独立的数据目录");
        content.addView(button("返回环境列表", false, this::leaveEditor), block(BUTTON_HEIGHT, 0, 16));
        LinearLayout form = card();
        nameField = field(form, "环境名称", "例如：日常工作", environment == null ? "" : environment.name, false, false);
        urlField = field(form, "启动页", "留空打开空白页", environment == null || environment.startPage.equals("about:blank") ? "" : environment.startPage, true, false);
        form.addView(text("归属分组", 16, 400, TEXT), block(-2, 4, 8));
        editorGroupChoice = button("", false, this::chooseEditorGroup);
        editorGroupChoice.setSingleLine(true);
        editorGroupChoice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        form.addView(editorGroupChoice, block(BUTTON_HEIGHT, 0, 16));
        updateEditorGroupLabel();
        tagsField = field(form, "标签", "例如：主力、养号（可选）", environment == null ? "" : environment.organization.tags, false, false);
        tagsField.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(160)});
        notesField = field(form, "备注", "用途说明（可选）", environment == null ? "" : environment.notes, false, true);
        content.addView(form, block(-2, 0, 20));
        LinearLayout account = card();
        account.addView(text("账号与验证码", 18, 500, TEXT));
        account.addView(text("账号、密码和 2FA 密钥加密保存在此手机，不包含在环境配置备份中。", 14, 300, MUTED), block(-2, 8, 12));
        if (environment == null) {
            account.addView(text("保存环境后，可在编辑页面管理账号和验证码。", 14, 300, MUTED));
        } else {
            account.addView(button("管理账号与验证码", false, () -> showAccountDialog(environment)), block(BUTTON_HEIGHT, 0, 0));
        }
        content.addView(account, block(-2, 0, 20));
        LinearLayout cookies = card();
        cookies.addView(text("Cookie 会话", 18, 500, TEXT));
        cookies.addView(text("先启动此环境，再返回管理页导入或导出普通模式 Cookie（含 HttpOnly）。导入按名称、域名、路径及分区覆盖对应项，不清空其他 Cookie。导出文件含登录凭证，请妥善保管。", 14, 300, MUTED), block(-2, 8, 12));
        if (environment == null) {
            cookies.addView(text("保存并启动环境后可用。", 14, 300, MUTED));
        } else {
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), 1);
            first.setMarginEnd(dp(8));
            actions.addView(button("导入 Cookie", false, () -> chooseCookieDocument(environment, true)), first);
            actions.addView(button("导出 Cookie", false, () -> chooseCookieDocument(environment, false)),
                    new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), 1));
            cookies.addView(actions);
        }
        content.addView(cookies, block(-2, 0, 20));
        LinearLayout network = card();
        network.addView(text("网络出口", 18, 500, TEXT));
        network.addView(text("不填代理时跟随手机网络，支持系统 VPN；分应用名单和国内直连规则由 VPN 客户端控制。填写代理则使用此环境的独立代理。修改后需关闭环境再启动。", 14, 300, MUTED), block(-2, 8, 12));
        String savedProxy = environment == null ? "" : environment.network.proxyUri;
        proxyProtocol = savedProxy.isEmpty() ? "socks5" : java.net.URI.create(savedProxy).getScheme();
        network.addView(text("代理协议", 14, 400, TEXT), block(-2, 0, 6));
        proxyProtocolChoice = button(proxyProtocolLabel(), false, () -> {
            String[] protocols = {"http", "https", "socks5"};
            int selected = java.util.Arrays.asList(protocols).indexOf(proxyProtocol);
            new AlertDialog.Builder(this).setTitle("代理协议")
                    .setSingleChoiceItems(new String[]{"HTTP", "HTTPS", "SOCKS5"}, selected, (dialog, index) -> {
                        proxyProtocol = protocols[index];
                        proxyProtocolChoice.setText(proxyProtocolLabel());
                        dialog.dismiss();
                    }).setNegativeButton("取消", null).show();
        });
        network.addView(proxyProtocolChoice, block(BUTTON_HEIGHT, 0, 12));
        proxyField = field(network, "代理地址（可选）", "主机或 IP:端口，无需填写协议前缀",
                savedProxy.isEmpty() ? "" : savedProxy.substring(savedProxy.indexOf("://") + 3), true, false);
        proxyField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                String input = value.toString();
                int separator = input.indexOf("://");
                if (separator <= 0) return;
                String protocol = input.substring(0, separator).trim().toLowerCase(java.util.Locale.ROOT);
                if (!(protocol.equals("http") || protocol.equals("https") || protocol.equals("socks5"))) return;
                proxyProtocol = protocol;
                proxyProtocolChoice.setText(proxyProtocolLabel());
                value.delete(0, separator + 3);
            }
        });
        boolean hasProxyCredentials = environment != null && !environment.network.credentialReference.isEmpty();
        proxyAuthenticationChoice = toggle("使用代理账号密码",
                environment != null && environment.network.requiresAuthentication);
        network.addView(proxyAuthenticationChoice, block(-2, 0, 8));
        replaceProxyCredentialsChoice = toggle("填写或更新账号密码", !hasProxyCredentials);
        network.addView(replaceProxyCredentialsChoice, block(-2, 0, 8));
        proxyCredentialFields = vertical();
        proxyUsernameField = field(proxyCredentialFields, "代理账号", "填写代理服务提供的账号", "", false, false);
        proxyUsernameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        proxyUsernameField.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(255)});
        proxyPasswordField = field(proxyCredentialFields, "代理密码", "填写代理密码（允许空密码）", "", false, false);
        proxyPasswordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        proxyPasswordField.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(1024)});
        for (EditText input : new EditText[] {proxyUsernameField, proxyPasswordField}) {
            input.setSaveEnabled(false);
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        proxyCredentialFields.addView(text("账号密码只加密保存在此手机，配置备份不包含凭据。旋转或离开页面后需重新输入未保存的凭据。HTTP、HTTPS 和 SOCKS5 均使用此处填写的账号密码。", 14, 300, MUTED));
        network.addView(proxyCredentialFields, block(-2, 0, 12));
        proxyAuthenticationChoice.setOnCheckedChangeListener((button, checked) -> updateProxyCredentialsFields());
        replaceProxyCredentialsChoice.setOnCheckedChangeListener((button, checked) -> updateProxyCredentialsFields());
        updateProxyCredentialsFields();
        webRtcChoice = toggle("限制 WebRTC 的非代理 UDP", environment != null && environment.network.restrictWebRtcUdp);
        network.addView(webRtcChoice, block(-2, 0, 8));
        network.addView(text("代理环境可开启此选项，限制 WebRTC 通过非代理 UDP 连接。", 14, 300, MUTED));
        content.addView(network, block(-2, 0, 20));
        LinearLayout region = card();
        region.addView(text("网页语言与时区", 18, 500, TEXT));
        region.addView(text("与电脑版一致：可按实际出口 IP 自动设置，也可使用固定值。不会切换 VPN 或代理；保存后需关闭环境再启动。", 14, 300, MUTED), block(-2, 8, 12));
        EnvironmentRegionalPolicy regionalPolicy = environment == null ? EnvironmentRegionalPolicy.desktopDefaults() : environment.regionalPolicy;
        languageMode = regionalPolicy.languageMode;
        timeZoneMode = regionalPolicy.timeZoneMode;
        locationMode = regionalPolicy.locationMode;
        region.addView(text("浏览器语言", 14, 400, TEXT));
        languagePolicyChoice = button("", false, this::chooseLanguagePolicy);
        region.addView(languagePolicyChoice, block(BUTTON_HEIGHT, 8, 12));
        preferredLanguagesField = field(region, "首选语言（IP 检测回退）", "例如：en-US,en", regionalPolicy.preferredLanguages, true, false);
        regionChoice = button("", false, this::chooseRegion);
        region.addView(regionChoice, block(BUTTON_HEIGHT, 0, 12));
        languagesField = field(region, "网页语言（按优先级）", "例如：en-US,en",
                environment == null ? "" : environment.region.languages, true, false);
        region.addView(text("出口时区", 14, 400, TEXT));
        timeZonePolicyChoice = button("", false, this::chooseTimeZonePolicy);
        region.addView(timeZonePolicyChoice, block(BUTTON_HEIGHT, 8, 12));
        timeZoneField = field(region, "自定义时区（IANA 名称）", "例如：America/Los_Angeles",
                environment == null ? "" : environment.region.timeZone, true, false);
        TextWatcher regionWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) { updateRegionLabel(); }
        };
        languagesField.addTextChangedListener(regionWatcher);
        timeZoneField.addTextChangedListener(regionWatcher);
        updateRegionalPolicyFields();
        content.addView(region, block(-2, 0, 20));
        LinearLayout location = card();
        location.addView(text("网页地理位置", 18, 500, TEXT));
        EnvironmentGeolocationSettings geo = environment == null
                ? EnvironmentGeolocationSettings.system() : environment.geolocation;
        selectedGeolocationMode = geo.mode;
        geolocationChoice = button("", false, this::chooseGeolocationMode);
        location.addView(geolocationChoice, block(BUTTON_HEIGHT, 8, 12));
        coordinatesFromIpChoice = toggle("基于 IP 自动生成坐标", locationMode == EnvironmentRegionalPolicy.LocationMode.EXIT_IP);
        coordinatesFromIpChoice.setOnCheckedChangeListener((view, checked) -> {
            locationMode = checked ? EnvironmentRegionalPolicy.LocationMode.EXIT_IP : EnvironmentRegionalPolicy.LocationMode.CUSTOM;
            selectedGeolocationMode = checked ? EnvironmentGeolocationSettings.SYSTEM : EnvironmentGeolocationSettings.CUSTOM;
            updateGeolocationFields();
        });
        location.addView(coordinatesFromIpChoice, block(-2, 0, 12));
        geolocationCustomFields = vertical();
        latitudeField = field(geolocationCustomFields, "纬度", "-90 到 90",
                geo.mode == EnvironmentGeolocationSettings.CUSTOM ? Double.toString(geo.latitude) : "", true, false);
        longitudeField = field(geolocationCustomFields, "经度", "-180 到 180",
                geo.mode == EnvironmentGeolocationSettings.CUSTOM ? Double.toString(geo.longitude) : "", true, false);
        accuracyField = field(geolocationCustomFields, "精度（米）", "1 到 1000000",
                geo.mode == EnvironmentGeolocationSettings.CUSTOM ? Double.toString(geo.accuracy) : "100", true, false);
        for (EditText input : new EditText[] {latitudeField, longitudeField, accuracyField}) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(40)});
        }
        location.addView(geolocationCustomFields);
        updateGeolocationFields();
        location.addView(text("基于 IP 使用出口服务提供的大致坐标，不读取手机 GPS；网站仍需定位授权。检测不到坐标时阻止定位并提示。自定义坐标、询问网站和禁止定位可分别选择。", 14, 300, MUTED));
        content.addView(location, block(-2, 0, 20));
        LinearLayout browserIdentity = card();
        browserIdentity.addView(text("Android 浏览器身份", 18, 500, TEXT));
        browserIdentity.addView(text("默认 UA 自动匹配当前浏览器内核版本，无需手动填写主版本号。", 14, 300, MUTED), block(-2, 8, 12));
        EnvironmentAndroidIdentity identity = environment == null ? EnvironmentAndroidIdentity.system() : environment.androidIdentity;
        followPhoneIdentity = toggle("跟随手机的版本与型号", identity.isSystem());
        browserIdentity.addView(followPhoneIdentity, block(-2, 8, 12));
        androidVersionField = field(browserIdentity, "Android 版本", "例如：15 或 15.1.2", identity.platformVersion, true, false);
        androidVersionField.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(16)});
        androidModelField = field(browserIdentity, "设备型号（可选）", "例如：Pixel 8；留空不报告型号", identity.model, false, false);
        androidModelField.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
        followPhoneIdentity.setOnCheckedChangeListener((button, checked) -> updateIdentityFields());
        updateIdentityFields();
        browserIdentity.addView(text("自定义版本与型号用于网页身份和 Client Hints。内核版本及 UA 缩减规则保留；这不会改变实际系统或 GPU。修改后需关闭环境再启动。", 14, 300, MUTED));
        content.addView(browserIdentity, block(-2, 0, 20));
        LinearLayout uaCard = card();
        uaCard.addView(text("User-Agent 与 UA-CH", 18, 500, TEXT));
        uaCard.addView(text("自动模式使用真实内核版本；自定义设置同时用于请求头、网页及 Worker。", 14, 300, MUTED), block(-2, 8, 12));
        EnvironmentUserAgentSettings ua = environment == null ? EnvironmentUserAgentSettings.automatic() : environment.fingerprint.userAgent;
        uaCard.addView(text("当前内核：Chrome " + recommendedUserAgent().values()[1], 14, 400, TEXT), block(-2, 0, 12));
        automaticUaChoice = toggle("自动匹配当前内核（推荐）", !ua.custom);
        uaCard.addView(automaticUaChoice, block(-2, 8, 12));
        uaCard.addView(button("读取当前内核推荐值", false, () -> {
            EnvironmentUserAgentSettings recommended = recommendedUserAgent();
            String[] values = recommended.values();
            for (int i = 0; i < uaFields.length; i++) uaFields[i].setText(values[i]);
            uaMobileChoice.setChecked(recommended.mobile); uaWow64Choice.setChecked(recommended.wow64);
            automaticUaChoice.setChecked(false);
        }), block(BUTTON_HEIGHT, 0, 16));
        uaCustomFields = vertical();
        uaFields = new EditText[EnvironmentUserAgentSettings.FIELD_COUNT];
        String[] uaValues = (ua.custom ? ua : recommendedUserAgent()).values();
        for (int i = 0; i < uaFields.length; i++) {
            String hint = i == 0 ? "留空按 UA-CH 生成" : i == 8 || i == 9 ? "每行 品牌=版本，两个列表顺序一致" : i == 10 ? "Mobile、Desktop 或 Tablet；多项用逗号分隔" : "";
            uaFields[i] = field(uaCustomFields, EnvironmentUserAgentSettings.label(i), hint, uaValues[i], true, i == 0 || i == 8 || i == 9);
            uaFields[i].setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(i == 0 ? 1024 : 512)});
        }
        uaMobileChoice = toggle("UA-CH Mobile", !ua.custom || ua.mobile);
        uaWow64Choice = toggle("UA-CH WoW64", ua.custom && ua.wow64);
        uaCustomFields.addView(uaMobileChoice, block(-2, 0, 12));
        uaCustomFields.addView(uaWow64Choice, block(-2, 0, 12));
        uaCard.addView(uaCustomFields);
        automaticUaChoice.setOnCheckedChangeListener((view, checked) -> updateUserAgentFields());
        updateUserAgentFields();
        uaCard.addView(text("自定义身份不会更换实际渲染内核，不能保证消除启发式版本差异。修改后关闭环境再启动。", 14, 300, MUTED));
        content.addView(uaCard, block(-2, 0, 20));
        LinearLayout rafCard = card();
        rafCard.addView(text("RAF 动画帧率", 18, 500, TEXT));
        int rate = environment == null ? 0 : environment.fingerprint.animationFrames.maxFramesPerSecond;
        deviceRafChoice = toggle("跟随设备刷新率", rate == 0);
        rafCard.addView(deviceRafChoice, block(-2, 8, 12));
        rafRateField = field(rafCard, "目标帧率（FPS）", "1–240，例如 30 或 60", Integer.toString(rate == 0 ? 60 : rate), true, false);
        rafRateField.setInputType(InputType.TYPE_CLASS_NUMBER);
        rafRateField.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(3)});
        deviceRafChoice.setOnCheckedChangeListener((view, checked) -> updateRafFields());
        updateRafFields();
        rafCard.addView(text("独立限制网页与支持 RAF 的 Worker 回调频率，保留真实时间戳；受屏幕、负载与后台节流影响，无法强制超出设备帧率。不会伪造帧间隔。修改后重启环境。", 14, 300, MUTED));
        content.addView(rafCard, block(-2, 0, 20));
        LinearLayout gpu = card();
        gpu.addView(text("GPU 标识", 18, 500, TEXT));
        EnvironmentGpuMetadata metadata = environment == null ? EnvironmentGpuMetadata.system() : environment.fingerprint.gpuMetadata;
        followGpuIdentity = toggle("使用设备的原生 GPU 标识", metadata.isSystem());
        gpu.addView(followGpuIdentity, block(-2, 8, 12));
        gpuCustomFields = vertical();
        gpuFields = new EditText[6];
        String[] gpuValues = metadata.values();
        String[] gpuHints = {"例如：Google Inc. (Qualcomm)", "例如：ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)",
                "例如：qualcomm", "例如：adreno-7xx；可留空", "例如：0x0000；可留空", "可留空，不报告描述"};
        for (int i = 0; i < gpuFields.length; i++) {
            gpuFields[i] = field(gpuCustomFields, EnvironmentGpuMetadata.label(i), gpuHints[i], gpuValues[i], true, false);
            gpuFields[i].setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(EnvironmentGpuMetadata.limit(i))});
        }
        gpu.addView(gpuCustomFields);
        followGpuIdentity.setOnCheckedChangeListener((button, checked) -> updateGpuFields());
        updateGpuFields();
        gpu.addView(text("自定义 WebGL 与 WebGPU 对外报告的标识。请填写同一 GPU 的匹配信息；不会改变实际图形功能、扩展或性能限制。可选字段留空会明确报告为空。修改后需关闭环境再启动。", 14, 300, MUTED));
        content.addView(gpu, block(-2, 0, 20));
        selectedCpu = environment == null ? 0 : environment.fingerprint.hardwareConcurrency;
        selectedMemory = environment == null ? 0 : environment.fingerprint.deviceMemory;
        LinearLayout hardware = card();
        hardware.addView(text("设备指纹", 18, 500, TEXT));
        hardware.addView(text("调整网页读取到的 CPU 与内存值，修改后需关闭环境再启动。", 14, 300, MUTED), block(-2, 8, 16));
        hardware.addView(text("CPU 核心数", 14, 400, TEXT), block(-2, 0, 8));
        cpuChoice = button("", false, () -> chooseHardware(true));
        cpuChoice.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        hardware.addView(cpuChoice, block(BUTTON_HEIGHT, 0, 16));
        hardware.addView(text("设备内存", 14, 400, TEXT), block(-2, 0, 8));
        memoryChoice = button("", false, () -> chooseHardware(false));
        memoryChoice.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        hardware.addView(memoryChoice, block(BUTTON_HEIGHT, 0, 0));
        updateHardwareLabels();
        content.addView(hardware, block(-2, 0, 20));
        LinearLayout fonts = card();
        fonts.addView(text("本地字体", 18, 500, TEXT));
        fontIsolationChoice = toggle("中文与厂商字体隔离", environment == null
                ? FingerprintSettings.NEW_ENVIRONMENT_FONT_ISOLATION : environment.fingerprint.regionalFontIsolation);
        fonts.addView(fontIsolationChoice, block(-2, 8, 8));
        fonts.addView(text("限制网页按名称读取常见中文和设备厂商的本地字体，保留中文显示。修改后需关闭环境再启动。", 14, 300, MUTED));
        content.addView(fonts, block(-2, 0, 20));
        LinearLayout canvas = card();
        canvas.addView(text("Canvas 与 WebGL 像素", 18, 500, TEXT));
        canvasNoiseChoice = toggle("固定环境噪声", environment == null
                ? FingerprintSettings.NEW_ENVIRONMENT_CANVAS_NOISE : environment.fingerprint.canvasNoise);
        canvas.addView(canvasNoiseChoice, block(-2, 8, 8));
        canvas.addView(text("对 Canvas 读回、PNG 导出和 WebGL 像素读取加入微量扰动，同一环境保持稳定；不改变网页显示。修改后需关闭环境再启动。", 14, 300, MUTED));
        content.addView(canvas, block(-2, 0, 20));
        LinearLayout audio = card();
        audio.addView(text("音频", 18, 500, TEXT));
        audioNoiseChoice = toggle("固定环境音频噪声", environment == null
                ? FingerprintSettings.NEW_ENVIRONMENT_AUDIO_NOISE : environment.fingerprint.audioNoise);
        audio.addView(audioNoiseChoice, block(-2, 8, 8));
        audio.addView(text("对离线音频渲染结果与音频分析读数加入微量扰动；同一环境保持稳定，静音数据保持不变。", 14, 300, MUTED));
        muteAudioChoice = toggle("静音播放", environment != null && environment.fingerprint.muteAudio);
        audio.addView(muteAudioChoice, block(-2, 12, 8));
        audio.addView(text("关闭此环境的声音输出。静音不等于关闭音频指纹；两项可独立设置，修改后需关闭环境再启动。", 14, 300, MUTED));
        content.addView(audio, block(-2, 0, 20));
        LinearLayout rectangles = card();
        rectangles.addView(text("布局测量", 18, 500, TEXT));
        clientRectsNoiseChoice = toggle("固定环境 ClientRects 噪声", environment == null
                ? FingerprintSettings.NEW_ENVIRONMENT_CLIENT_RECTS_NOISE : environment.fingerprint.clientRectsNoise);
        rectangles.addView(clientRectsNoiseChoice, block(-2, 8, 8));
        rectangles.addView(text("微调网页读取到的元素与文字选区尺寸，同一环境保持稳定；保留真实页面排版。修改后需关闭环境再启动。", 14, 300, MUTED));
        content.addView(rectangles, block(-2, 0, 20));
        LinearLayout media = card();
        media.addView(text("媒体设备", 18, 500, TEXT));
        EnvironmentMediaSettings mediaSettings = environment == null ? EnvironmentMediaSettings.isolated() : environment.fingerprint.media;
        selectedMediaMode = mediaSettings.mode;
        mediaModeChoice = button("", false, this::chooseMediaMode);
        mediaModeChoice.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        media.addView(mediaModeChoice, block(BUTTON_HEIGHT, 12, 12));
        blockMediaChoice = toggle("禁止网站使用摄像头和麦克风", mediaSettings.blockCapture);
        media.addView(blockMediaChoice, block(-2, 0, 12));
        mediaLabelFields = vertical();
        mediaLabels = new EditText[3];
        String[] labels = mediaSettings.labels();
        String[] kinds = {"麦克风名称", "摄像头名称", "扬声器名称"};
        for (int i = 0; i < mediaLabels.length; ++i) {
            mediaLabels[i] = field(mediaLabelFields, kinds[i] + "（可选）", "留空自动生成环境名称", labels[i], false, false);
            mediaLabels[i].setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(200)});
        }
        media.addView(mediaLabelFields);
        updateMediaFields();
        media.addView(text("隔离模式调整网站读取到的设备标识和名称，保留真实设备数量与采集能力；隐藏名称模式仍保留可用的设备标识。不会自动授予录音或摄像权限。修改后需关闭环境再启动。", 14, 300, MUTED));
        content.addView(media, block(-2, 0, 20));
        selectedDnt = environment == null ? FingerprintSettings.DNT_DEFAULT : environment.fingerprint.doNotTrack;
        LinearLayout privacy = card();
        privacy.addView(text("隐私偏好", 18, 500, TEXT));
        privacy.addView(text("Do Not Track", 14, 400, TEXT), block(-2, 12, 8));
        dntChoice = button("", false, this::chooseDnt);
        dntChoice.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        privacy.addView(dntChoice, block(BUTTON_HEIGHT, 0, 8));
        updateDntLabel();
        privacy.addView(text("向网站表达不希望被跟踪的偏好。网站可能不遵守此信号；修改后需关闭环境再启动。", 14, 300, MUTED));
        content.addView(privacy, block(-2, 0, 20));
        cancelEditor = button("取消", false, this::leaveEditor);
        saveEnvironment = button("保存环境", true, this::save);
        setFixedActions(actionRow(cancelEditor, saveEnvironment));
        enterPage(1);
    }

    private void showAbout() {
        runWork(() -> {
            try {
                org.json.JSONObject info = new org.json.JSONObject(readNotice("build-info.json"));
                String project = publicLink(info.getString("projectUrl"));
                String upstream = publicLink(info.getString("upstreamUrl"));
                if (!info.getBoolean("free") || !getPackageName().equals(info.getString("package"))) {
                    throw new IOException("安装包品牌信息不一致");
                }
                String description = "免费开源 · 本地运行\n使用你自己的网络，环境数据保存在手机。\n\n"
                        + "基于 OpenBrowser 开发，保留原作者的开源许可与贡献说明。"
                        + "浏览器内核来自 Chromium。\n\n"
                        + "内核源码版本：" + info.getString("kernelVersion") + "\n"
                        + "项目：" + project + "\n原项目：" + upstream + "\n\n"
                        + "OpenBrowser · MIT License\n" + readNotice("OPENBROWSER-LICENSE.txt")
                        + "\n\nChromium · BSD License\n" + readNotice("CHROMIUM-LICENSE.txt")
                        + "\n\nChromium 第三方组件声明可在浏览器中打开 chrome://credits 查看。";
                return new String[] {description, project, upstream};
            } catch (org.json.JSONException | IllegalArgumentException error) {
                throw new IOException("无法读取安装包内的开源声明", error);
            }
        }, value -> {
            if (isFinishing() || editing) return;
            String[] info = (String[]) value;
            ScrollView scroll = new ScrollView(this);
            TextView description = text(info[0], 14, 300, TEXT);
            description.setTextIsSelectable(true);
            description.setPadding(dp(24), dp(12), dp(24), dp(12));
            scroll.addView(description);
            new AlertDialog.Builder(this).setTitle("关于岚渡浏览器").setView(scroll)
                    .setPositiveButton("项目仓库", (dialog, which) -> openPublicLink(info[1]))
                    .setNeutralButton("原项目", (dialog, which) -> openPublicLink(info[2]))
                    .setNegativeButton("关闭", null).show();
        });
    }

    private String readNotice(String name) throws IOException {
        try (InputStream input = getAssets().open("landu/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 64 * 1024) throw new IOException("开源声明文件过大");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String publicLink(String value) throws IOException {
        java.net.URI uri = java.net.URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException("无效的项目链接");
        }
        return value;
    }

    private void openPublicLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(publicLink(url)))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (IOException | RuntimeException error) {
            showError("无法打开链接，请稍后重试");
        }
    }

    private EditText field(LinearLayout parent, String label, String hint, String value, boolean url, boolean multiline) {
        TextView title = text(label, 14, 400, TEXT);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        title.setLabelFor(input.getId());
        parent.addView(title, block(-2, 4, 8));
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE :
                url ? InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        input.setSingleLine(!multiline);
        input.setImeOptions(input.getImeOptions()
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setGravity(multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL | Gravity.START);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setMinHeight(dp(BUTTON_HEIGHT));
        input.setBackground(shape(Color.WHITE, BORDER, 12));
        input.setHint(hint);
        input.setText(value);
        parent.addView(input, block(multiline ? 112 : -2, 0, 16));
        return input;
    }

    private void showAccountDialog(EnvironmentDefinition environment) {
        if (working || isDestroyed() || accountDialogState != null) return;
        AccountDialogState state = new AccountDialogState(environment);
        accountDialogState = state;
        LinearLayout form = vertical();
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView owner = text(environment.name, 16, 500, TEXT);
        owner.setMaxLines(2);
        owner.setEllipsize(android.text.TextUtils.TruncateAt.END);
        form.addView(owner, block(-2, 0, 12));
        state.username = field(form, "账号 / 邮箱", "平台登录账号（可选）", "", false, false);
        state.password = field(form, "密码", "平台登录密码（可选）", "", false, false);
        state.secret = field(form, "2FA 密钥", "Base32 密钥或 otpauth://totp/ 链接（可选）", "", false, false);
        state.username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        state.password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        state.secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        state.username.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(200)});
        state.password.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(500)});
        state.secret.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(2048)});
        for (EditText input : new EditText[] {state.username, state.password, state.secret}) {
            input.setSaveEnabled(false);
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            input.setImeOptions(input.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        state.copyUsername = button("复制账号", false, () -> copyAccountValue(state, 0));
        state.copyPassword = button("复制密码", false, () -> copyAccountValue(state, 1));
        form.addView(actionRow(state.copyUsername, state.copyPassword), block(BUTTON_HEIGHT, 0, 12));
        state.code = text("— — — — — —", 24, 500, TEXT);
        state.code.setGravity(Gravity.CENTER);
        state.code.setMinHeight(dp(48));
        form.addView(state.code, block(-2, 0, 0));
        state.remaining = text("填写有效 2FA 密钥后显示验证码", 14, 300, MUTED);
        state.remaining.setGravity(Gravity.CENTER);
        state.remaining.setMinHeight(dp(24));
        form.addView(state.remaining, block(-2, 0, 8));
        state.copyCode = button("复制验证码", false, () -> copyAccountValue(state, 2));
        form.addView(state.copyCode, block(BUTTON_HEIGHT, 0, 12));
        form.addView(text("保存空白字段会清除此环境的账号信息。离开页面或旋转屏幕后，未保存的账号内容不会保留。验证码按手机时间生成。", 14, 300, MUTED), block(-2, 0, 8));
        state.message = text("正在读取此环境的账号…", 14, 400, MUTED);
        state.message.setLines(3);
        state.message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        state.message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(state.message);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        state.dialog = new AlertDialog.Builder(this).setTitle("环境账号").setView(scroll)
                .setNegativeButton("关闭", null).setPositiveButton("加密保存", null).create();
        state.dialog.setOnDismissListener(closed -> closeAccountDialog());
        state.dialog.setOnShowListener(shown -> {
            state.dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            state.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            state.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> saveAccount(state));
            setAccountBusy(state, true);
            runAccountWork(state, () -> catalog.accessActive(environment.identity, environment.createdAt,
                    current -> AndroidAccountCredentials.open(getNoBackupFilesDir().toPath().resolve("landu-control")).read(current)), result -> {
                AccountCredentialStore.Snapshot saved = (AccountCredentialStore.Snapshot) result;
                state.revision = saved.revision;
                state.loaded = true;
                state.username.setText(saved.credentials.username);
                state.password.setText(saved.credentials.password);
                state.secret.setText(saved.credentials.totpSecret);
                state.message.setText(saved.credentials.isEmpty() ? "此环境尚未保存账号" : "已读取此手机保存的账号");
                state.message.setTextColor(MUTED);
                refreshAccountCode(state);
            });
        });
        state.secret.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                state.code.setText("— — — — — —");
                state.copyCode.setEnabled(false);
                main.removeCallbacks(state.tick);
                main.postDelayed(state.tick, 400);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        state.dialog.show();
    }

    private void refreshAccountCode(AccountDialogState state) {
        main.removeCallbacks(state.tick);
        if (accountDialogState != state || !state.loaded || state.busy || isDestroyed()) return;
        String secret = state.secret.getText().toString();
        if (secret.trim().isEmpty()) {
            state.code.setText("— — — — — —");
            state.remaining.setText(R.string.landu_totp_empty);
            state.copyCode.setEnabled(false);
            return;
        }
        try {
            Totp.Parameters parameters = Totp.parse(secret);
            long seconds = System.currentTimeMillis() / 1000;
            state.code.setText(parameters.atUnixSeconds(seconds));
            state.remaining.setText(getString(R.string.landu_totp_countdown, parameters.secondsRemaining(seconds), parameters.digits));
            state.copyCode.setEnabled(true);
            main.postDelayed(state.tick, 1000);
        } catch (IllegalArgumentException | IllegalStateException error) {
            state.code.setText("— — — — — —");
            state.remaining.setText(R.string.landu_totp_invalid);
            state.copyCode.setEnabled(false);
        }
    }

    private void copyAccountValue(AccountDialogState state, int field) {
        if (accountDialogState != state || !state.loaded || state.busy) return;
        final String value;
        try {
            value = field == 0 ? state.username.getText().toString() : field == 1 ? state.password.getText().toString()
                    : state.secret.getText().toString();
            if (field == 2) Totp.parse(value);
            if (value.isEmpty()) { state.message.setText("此字段为空，请先填写"); return; }
        } catch (IllegalArgumentException | IllegalStateException error) {
            state.message.setText(R.string.landu_totp_invalid);
            return;
        }
        runAccountWork(state, () -> catalog.accessActive(state.environment.identity, state.environment.createdAt,
                current -> value), result -> {
            final String copied;
            try {
                // 在实际复制时计算验证码，避免排队等待跨过验证码的刷新边界。
                copied = field == 2 ? Totp.parse((String) result).atUnixSeconds(System.currentTimeMillis() / 1000) : (String) result;
            } catch (IllegalArgumentException | IllegalStateException error) {
                state.message.setTextColor(0xffb42318);
                state.message.setText(R.string.landu_totp_invalid);
                return;
            }
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("岚渡浏览器", copied);
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(extras);
                clipboard.setPrimaryClip(clip);
                state.message.setTextColor(MUTED);
                state.message.setText("已复制");
            } catch (RuntimeException error) {
                state.message.setTextColor(0xffb42318);
                state.message.setText("复制失败，请检查系统剪贴板权限后重试");
            }
        });
    }

    private void saveAccount(AccountDialogState state) {
        if (accountDialogState != state || !state.loaded || state.busy) return;
        final AccountCredentials value;
        try {
            value = new AccountCredentials(state.username.getText().toString(), state.password.getText().toString(),
                    state.secret.getText().toString());
        } catch (IllegalArgumentException error) {
            state.message.setTextColor(0xffb42318);
            state.message.setText(error.getMessage());
            return;
        }
        state.message.setText("正在加密保存…");
        runAccountWork(state, () -> catalog.accessActive(state.environment.identity, state.environment.createdAt,
                current -> AndroidAccountCredentials.open(getNoBackupFilesDir().toPath().resolve("landu-control"))
                        .save(current, state.revision, value)), result -> {
            AccountCredentialStore.Snapshot saved = (AccountCredentialStore.Snapshot) result;
            state.revision = saved.revision;
            state.message.setTextColor(0xff168660);
            state.message.setText(saved.credentials.isEmpty() ? "账号信息已清空" : "账号已加密保存");
        });
    }

    private void runAccountWork(AccountDialogState state, Work operation, Result result) {
        if (accountDialogState != state || isDestroyed()) return;
        setAccountBusy(state, true);
        try { worker.execute(() -> {
            try {
                Object value = operation.run();
                main.post(() -> {
                    if (accountDialogState != state || isDestroyed()) return;
                    result.accept(value);
                    setAccountBusy(state, false);
                    refreshAccountCode(state);
                });
            } catch (IOException | RuntimeException error) {
                main.post(() -> {
                    if (accountDialogState != state || isDestroyed()) return;
                    setAccountBusy(state, false);
                    state.message.setTextColor(0xffb42318);
                    state.message.setText(error instanceof java.nio.file.FileSystemException || error.getMessage() == null
                            ? "账号读取或保存失败，请稍后重试" : error.getMessage());
                    refreshAccountCode(state);
                });
            }
        }); } catch (java.util.concurrent.RejectedExecutionException error) {
            setAccountBusy(state, false);
            state.message.setText("账号操作暂不可用，请重新打开页面");
        }
    }

    private void setAccountBusy(AccountDialogState state, boolean busy) {
        state.busy = busy;
        boolean enabled = !busy && state.loaded;
        for (EditText input : new EditText[] {state.username, state.password, state.secret}) input.setEnabled(enabled);
        state.copyUsername.setEnabled(enabled);
        state.copyPassword.setEnabled(enabled);
        state.copyCode.setEnabled(false);
        Button save = state.dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (save != null) save.setEnabled(enabled);
        if (busy) main.removeCallbacks(state.tick);
    }

    private void closeAccountDialog() {
        AccountDialogState state = accountDialogState;
        if (state == null) return;
        accountDialogState = null;
        main.removeCallbacks(state.tick);
        for (EditText input : new EditText[] {state.username, state.password, state.secret}) {
            if (input != null) input.setText("");
        }
        if (state.code != null) state.code.setText("");
        main.removeCallbacks(state.tick);
        if (state.dialog != null) state.dialog.dismiss();
    }

    private final class AccountDialogState {
        final EnvironmentDefinition environment;
        final Runnable tick = () -> refreshAccountCode(this);
        AlertDialog dialog;
        EditText username;
        EditText password;
        EditText secret;
        TextView code;
        TextView remaining;
        TextView message;
        Button copyUsername;
        Button copyPassword;
        Button copyCode;
        boolean loaded;
        boolean busy;
        long revision;
        AccountDialogState(EnvironmentDefinition environment) { this.environment = environment; }
    }

    private void chooseGeolocationMode() {
        hideKeyboard(); content.requestFocus();
        String[] labels = {"基于出口 IP", "询问（使用手机定位）", "禁止网页定位", "自定义坐标"};
        new AlertDialog.Builder(this).setTitle("网页地理位置")
                .setSingleChoiceItems(labels, LOCATION_POLICIES.indexOf(locationMode), (dialog, which) -> {
                    locationMode = LOCATION_POLICIES.get(which);
                    selectedGeolocationMode = locationMode == EnvironmentRegionalPolicy.LocationMode.CUSTOM ? EnvironmentGeolocationSettings.CUSTOM
                            : locationMode == EnvironmentRegionalPolicy.LocationMode.BLOCKED ? EnvironmentGeolocationSettings.BLOCKED : EnvironmentGeolocationSettings.SYSTEM;
                    updateGeolocationFields();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void updateGeolocationFields() {
        String[] labels = {"基于出口 IP", "询问（使用手机定位）", "禁止网页定位", "自定义坐标"};
        geolocationChoice.setText(labels[LOCATION_POLICIES.indexOf(locationMode)]);
        boolean ip = locationMode == EnvironmentRegionalPolicy.LocationMode.EXIT_IP;
        coordinatesFromIpChoice.setOnCheckedChangeListener(null);
        coordinatesFromIpChoice.setChecked(ip);
        coordinatesFromIpChoice.setVisibility(ip || locationMode == EnvironmentRegionalPolicy.LocationMode.CUSTOM ? View.VISIBLE : View.GONE);
        coordinatesFromIpChoice.setOnCheckedChangeListener((view, checked) -> {
            locationMode = checked ? EnvironmentRegionalPolicy.LocationMode.EXIT_IP : EnvironmentRegionalPolicy.LocationMode.CUSTOM;
            selectedGeolocationMode = checked ? EnvironmentGeolocationSettings.SYSTEM : EnvironmentGeolocationSettings.CUSTOM;
            updateGeolocationFields();
        });
        geolocationCustomFields.setVisibility(locationMode == EnvironmentRegionalPolicy.LocationMode.CUSTOM
                ? View.VISIBLE : View.GONE);
    }

    private String proxyProtocolLabel() {
        return "http".equals(proxyProtocol) ? "HTTP  ▾"
                : "https".equals(proxyProtocol) ? "HTTPS  ▾" : "SOCKS5  ▾";
    }

    private boolean proxyInputChanged() {
        try {
            return !EnvironmentNetworkSettings.addressInput(proxyField.getText().toString(), proxyProtocol)
                    .equals(original == null ? "" : original.network.proxyUri);
        } catch (IllegalArgumentException invalid) { return true; }
    }

    private void updateProxyCredentialsFields() {
        boolean enabled = proxyAuthenticationChoice.isChecked();
        boolean saved = original != null && !original.network.credentialReference.isEmpty();
        replaceProxyCredentialsChoice.setVisibility(enabled && saved ? View.VISIBLE : View.GONE);
        if (!saved) replaceProxyCredentialsChoice.setChecked(true);
        proxyCredentialFields.setVisibility(enabled && replaceProxyCredentialsChoice.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void save() {
        if (working) return;
        final EnvironmentNetworkSettings network;
        try { network = new EnvironmentNetworkSettings(EnvironmentNetworkSettings.addressInput(proxyField.getText().toString(), proxyProtocol), webRtcChoice.isChecked(),
                proxyAuthenticationChoice.isChecked(), ""); }
        catch (IllegalArgumentException error) {
            proxyField.setError(error.getMessage());
            proxyField.requestFocus();
            return;
        }
        final EnvironmentRegionSettings region;
        try { region = new EnvironmentRegionSettings(languagesField.getText().toString(), timeZoneField.getText().toString()); }
        catch (IllegalArgumentException error) {
            EditText invalid = error.getMessage() != null && error.getMessage().startsWith("网页语言") ? languagesField : timeZoneField;
            invalid.setError(error.getMessage());
            invalid.requestFocus();
            return;
        }
        final EnvironmentDefinition environment;
        final EnvironmentGeolocationSettings geolocation;
        try {
            geolocation = selectedGeolocationMode == EnvironmentGeolocationSettings.CUSTOM
                    ? EnvironmentGeolocationSettings.custom(latitudeField.getText().toString(),
                            longitudeField.getText().toString(), accuracyField.getText().toString())
                    : selectedGeolocationMode == EnvironmentGeolocationSettings.BLOCKED
                            ? EnvironmentGeolocationSettings.blocked() : EnvironmentGeolocationSettings.system();
        } catch (IllegalArgumentException error) {
            EditText invalid = error.getMessage() != null && error.getMessage().startsWith("纬度") ? latitudeField
                    : error.getMessage() != null && error.getMessage().startsWith("经度") ? longitudeField : accuracyField;
            invalid.setError(error.getMessage()); invalid.requestFocus(); return;
        }
        final EnvironmentUserAgentSettings userAgent;
        final AnimationFrameSettings frames;
        try {
            userAgent = automaticUaChoice.isChecked() ? EnvironmentUserAgentSettings.automatic()
                    : new EnvironmentUserAgentSettings(true, userAgentFieldValues(), uaMobileChoice.isChecked(), uaWow64Choice.isChecked());
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            EditText invalid = uaFields[1];
            for (int i = 0; i < uaFields.length; i++) if (error.getMessage() != null && error.getMessage().startsWith(EnvironmentUserAgentSettings.label(i))) invalid = uaFields[i];
            invalid.setError(error.getMessage()); invalid.requestFocus(); return;
        }
        try {
            int rate = deviceRafChoice.isChecked() ? 0 : Integer.parseInt(rafRateField.getText().toString().trim());
            if (!deviceRafChoice.isChecked() && rate == 0) throw new IllegalArgumentException("自定义 RAF 帧率须为 1–240");
            frames = new AnimationFrameSettings(rate);
        } catch (IllegalArgumentException error) {
            rafRateField.setError("RAF 帧率须为 1–240"); rafRateField.requestFocus(); return;
        }
        final EnvironmentAndroidIdentity identity;
        final EnvironmentGpuMetadata gpuMetadata;
        try {
            identity = followPhoneIdentity.isChecked() ? EnvironmentAndroidIdentity.system()
                    : new EnvironmentAndroidIdentity(androidVersionField.getText().toString(), androidModelField.getText().toString());
            if (!followPhoneIdentity.isChecked() && identity.isSystem()) throw new IllegalArgumentException("请输入自定义 Android 版本");
        } catch (IllegalArgumentException error) {
            EditText invalid = error.getMessage() != null && error.getMessage().startsWith("设备型号") ? androidModelField : androidVersionField;
            invalid.setError(error.getMessage()); invalid.requestFocus(); return;
        }
        try {
            gpuMetadata = followGpuIdentity.isChecked() ? EnvironmentGpuMetadata.system() : new EnvironmentGpuMetadata(gpuFieldValues());
            if (!followGpuIdentity.isChecked() && gpuMetadata.isSystem()) throw new IllegalArgumentException("请填写 WebGL 厂商、渲染器及 WebGPU 厂商");
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            for (int i = 0; i < gpuFields.length; i++) {
                if (error.getMessage() != null && error.getMessage().contains(EnvironmentGpuMetadata.label(i))) {
                    gpuFields[i].setError(error.getMessage()); gpuFields[i].requestFocus(); break;
                }
            }
            return;
        }
        try {
            EnvironmentDefinition changed = original == null
                    ? EnvironmentDefinition.create(nameField.getText().toString(), urlField.getText().toString(), notesField.getText().toString())
                    : original.edit(nameField.getText().toString(), urlField.getText().toString(), notesField.getText().toString());
            environment = changed.withFingerprint(changed.fingerprint.withUserAgent(userAgent).withAnimationFrames(frames).withHardware(selectedCpu, selectedMemory)
                    .withRegionalFontIsolation(fontIsolationChoice.isChecked())
                    .withCanvasNoise(canvasNoiseChoice.isChecked()).withDoNotTrack(selectedDnt).withGpuMetadata(gpuMetadata)
                    .withAudio(audioNoiseChoice.isChecked(), muteAudioChoice.isChecked())
                    .withClientRectsNoise(clientRectsNoiseChoice.isChecked())
                    .withMedia(new EnvironmentMediaSettings(selectedMediaMode, blockMediaChoice.isChecked(), mediaLabelValues())))
                    .withNetwork(network).withRegion(region).withAndroidIdentity(identity).withGeolocation(geolocation)
                    .withOrganization(new EnvironmentOrganization(changed.organization.number, selectedGroupId, tagsField.getText().toString()))
                    .withRegionalPolicy(new EnvironmentRegionalPolicy(languageMode, timeZoneMode, locationMode,
                            preferredLanguagesField.getText().toString()));
        } catch (IllegalArgumentException error) { showError(error.getMessage()); return; }
        long expectedRevision = editorRevision;
        final ProxyCredentials replacementCredentials;
        final EnvironmentNetworkSettings retainedNetwork;
        try {
            replacementCredentials = network.requiresAuthentication && replaceProxyCredentialsChoice.isChecked()
                    ? new ProxyCredentials(proxyUsernameField.getText().toString(), proxyPasswordField.getText().toString()) : null;
            if (replacementCredentials != null) replacementCredentials.validateFor(network);
            if (network.requiresAuthentication && replacementCredentials == null) {
                if (original == null || !original.network.requiresAuthentication
                        || original.network.credentialReference.isEmpty()
                        || !original.network.proxyUri.equals(network.proxyUri)) {
                    throw new IllegalArgumentException("请填写账号密码；更换代理地址后需重新填写");
                }
                retainedNetwork = new EnvironmentNetworkSettings(network.proxyUri, network.restrictWebRtcUdp,
                        true, original.network.credentialReference);
            } else retainedNetwork = network;
        } catch (IllegalArgumentException error) {
            proxyUsernameField.setError(error.getMessage());
            proxyUsernameField.requestFocus();
            showError(error.getMessage());
            return;
        }
        runWork(() -> {
            ProxyCredentialStore secrets = null;
            String newReference = null;
            boolean committed = false;
            try {
                EnvironmentDefinition candidate = environment.withNetwork(retainedNetwork);
                if (replacementCredentials != null) {
                    secrets = AndroidProxyCredentials.open(getNoBackupFilesDir().toPath().resolve("landu-control"));
                    newReference = secrets.put(candidate.identity, network, replacementCredentials);
                    candidate = candidate.withNetwork(new EnvironmentNetworkSettings(network.proxyUri,
                            network.restrictWebRtcUdp, true, newReference));
                }
                EnvironmentCatalog.Snapshot saved = catalog.save(expectedRevision, candidate);
                committed = true;
                return saved;
            } finally {
                if (!committed && secrets != null && newReference != null) secrets.discardNew(newReference);
            }
        }, result -> {
            snapshot = (EnvironmentCatalog.Snapshot) result;
            proxyPasswordField.setText("");
            proxyUsernameField.setText("");
            showList();
            feedback.setTextColor(0xff168660);
            feedback.setText(observation != null && observation.processRunning && observation.belongsTo(environment.identity)
                    ? "环境已保存，配置将在关闭并重新启动后生效" : "环境已保存");
        });
    }

    private void launch(EnvironmentDefinition environment) {
        String requestedLink = externalLink;
        runWork(() -> {
            return AndroidRegionalLaunch.prepare(this, leases, environment.identity);
        }, result -> {
            EnvironmentLeaseStore.LaunchRequest request = (EnvironmentLeaseStore.LaunchRequest) result;
            EnvironmentLeaseStore.Ticket ticket = request.ticket;
            Intent intent = new Intent(this, LanduLaunchActivity.class);
            intent.putExtra(NativeEnvironmentRuntime.EXTRA_ID, ticket.identity.id());
            intent.putExtra(NativeEnvironmentRuntime.EXTRA_NONCE, ticket.nonce);
            intent.putExtra(NativeEnvironmentRuntime.EXTRA_RESUME, request.resumeExisting);
            if (requestedLink != null) intent.putExtra(LanduLinkActivity.EXTRA_LINK, requestedLink);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
                if (java.util.Objects.equals(externalLink, requestedLink)) externalLink = null;
                showList();
            }
            catch (RuntimeException error) { showError("内核启动入口不可用；可点击关闭当前环境取消待启动状态"); }
        });
        if (working && feedback != null) feedback.setText("正在按环境网络检测实际出口…");
    }

    private void loadExitSummary(EnvironmentDefinition environment, TextView label) {
        worker.execute(() -> {
            String summary = environment.network.summary() + "\n实际出口：未检测或配置已修改";
            try {
                EnvironmentRegionalLaunchStore.Receipt receipt = new EnvironmentRegionalLaunchStore(
                        getNoBackupFilesDir().toPath().resolve("landu-control")).read(environment);
                if (receipt.measured != null) {
                    EnvironmentExitInfo exit = receipt.measured;
                    summary = environment.network.summary() + "\n上次检测出口：" + exit.countryCode
                            + (exit.city.isEmpty() ? "" : " · " + exit.city) + " · " + exit.ip
                            + "\n" + (exit.timeZone.isEmpty() ? "时区未返回" : exit.timeZone)
                            + " · " + android.text.format.DateFormat.format("MM-dd HH:mm", exit.checkedAt);
                    if (receipt.resolved.missingExitCoordinates) summary += "\n未返回坐标，本次网页定位已阻止";
                    if (receipt.resolved.languageFallback) summary += "\n语言使用首选回退值";
                    if (receipt.resolved.timeZoneFallback) summary += "\n时区使用回退值";
                } else if (!receipt.failure.isEmpty()) summary = environment.network.summary() + "\n" + receipt.failure;
            } catch (IOException ignored) { }
            final String value = summary;
            main.post(() -> { if (!isDestroyed() && !editing && label.isAttachedToWindow()) label.setText(value); });
        });
    }

    private void stopCurrent() {
        runWork(() -> {
            if (!leases.isBrowserRunning()) {
                EnvironmentLeaseStore.Ticket pending = leases.pendingLaunch();
                if (pending != null) leases.cancelPending(pending);
                return null;
            }
            EnvironmentLeaseStore.Ticket current = leases.currentLaunch();
            if (current == null) throw new IOException("环境正在关闭，请稍后重试");
            return current;
        }, result -> {
            if (result == null) { feedback.setText("当前没有运行中的环境"); return; }
            working = true;
            feedback.setText("正在保存并关闭环境…");
            sessionClient.stop((EnvironmentLeaseStore.Ticket) result, error -> {
                working = false;
                if (error != null) showError(error);
                else { feedback.setTextColor(0xff168660); feedback.setText("环境已关闭，可以切换其他环境"); }
                scheduleStatusPoll();
            });
        });
    }

    private EnvironmentLeaseStore.Ticket requireCookieTicket(EnvironmentIdentity owner, long createdAt) throws IOException {
        EnvironmentDefinition current = catalog.read().find(owner);
        if (current.createdAt != createdAt) throw new IOException("环境已变化，请重新进入编辑页");
        EnvironmentLeaseStore.Observation observed = leases.observe();
        if (observed.status != EnvironmentLeaseStore.Status.RUNNING || !observed.processRunning
                || !observed.belongsTo(owner) || observed.ticket == null)
            throw new IOException("请先启动此环境，再返回管理页管理 Cookie；不会操作其他环境");
        return observed.ticket;
    }

    private void chooseCookieDocument(EnvironmentDefinition environment, boolean importing) {
        if (working || isDestroyed()) return;
        if (pendingCookieRequest != 0 || pendingDocumentRequest != 0 || documentBusy) {
            showError("请先完成当前文件选择或配置传输"); return;
        }
        runWork(() -> requireCookieTicket(environment.identity, environment.createdAt), ignored -> {
            pendingCookieOwner = environment.identity.id();
            pendingCookieCreatedAt = environment.createdAt;
            pendingCookieRequest = importing ? REQUEST_IMPORT_COOKIES : REQUEST_EXPORT_COOKIES;
            Intent picker = new Intent(importing ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType(importing ? "*/*" : "application/json");
            if (!importing) picker.putExtra(Intent.EXTRA_TITLE, "landu-cookies.json");
            try { startActivityForResult(picker, pendingCookieRequest); }
            catch (RuntimeException error) {
                pendingCookieRequest = 0; pendingCookieOwner = null;
                showError("系统文件选择器不可用");
            }
        });
    }

    private void consumeCookieSelection() {
        if (working || isDestroyed() || catalog == null || snapshot == null || queuedCookieUri == null) return;
        final Uri uri = queuedCookieUri;
        final String ownerText = pendingCookieOwner;
        final long createdAt = pendingCookieCreatedAt;
        final boolean importing = pendingCookieRequest == REQUEST_IMPORT_COOKIES;
        queuedCookieUri = null; pendingCookieRequest = 0; pendingCookieOwner = null;
        final EnvironmentIdentity owner;
        try { owner = EnvironmentIdentity.parse(ownerText); }
        catch (IllegalArgumentException failure) { showError("Cookie 文件选择已失效，请重新选择"); return; }
        runWork(() -> {
            requireCookieTicket(owner, createdAt);
            if (!importing) return null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                String json = CookieTransferStreams.read(input);
                try {
                    org.json.JSONArray entries = new org.json.JSONArray(json);
                    if (entries.length() > 5000) throw new IOException("每次最多导入 5000 个 Cookie");
                    return entries;
                } catch (org.json.JSONException failure) { throw new IOException("请选择 Cookie JSON 数组文件"); }
            }
        }, result -> {
            if (!importing) { transferCookies(owner, createdAt, false, "", uri); return; }
            org.json.JSONArray entries = (org.json.JSONArray) result;
            cookiePreviewDialog = new AlertDialog.Builder(this)
                    .setTitle("导入 Cookie")
                    .setMessage("将向此环境导入 " + entries.length() + " 项。对应的同名、同域、同路径及同分区 Cookie 将被覆盖，其他项保留。无痕模式不受影响。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认导入", (dialog, which) -> transferCookies(owner, createdAt, true, entries.toString(), null))
                    .create();
            cookiePreviewDialog.setOnDismissListener(dialog -> cookiePreviewDialog = null);
            cookiePreviewDialog.show();
            if (cookiePreviewDialog.getWindow() != null)
                cookiePreviewDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
    }

    private void transferCookies(EnvironmentIdentity owner, long createdAt, boolean importing, String json, Uri destination) {
        runWork(() -> requireCookieTicket(owner, createdAt), ticket -> {
            working = true;
            updateStatusControls();
            feedback.setText(importing ? R.string.landu_cookie_importing : R.string.landu_cookie_exporting);
            cookieClient = new CookieTransferClient(this);
            cookieClient.start((EnvironmentLeaseStore.Ticket) ticket, importing, json, (payload, error) -> {
                cookieClient = null;
                if (isDestroyed()) return;
                working = false;
                updateStatusControls();
                if (error != null) { showError(error); return; }
                final org.json.JSONObject report;
                try {
                    report = new org.json.JSONObject(payload);
                    if (!report.getBoolean("ok")) {
                        String kind = report.optString("error", "");
                        if (importing && (kind.equals("invalid_cookie") || kind.equals("input_format") || kind.equals("input_size"))) {
                            showError("Cookie 文件校验未通过，未写入任何项。请检查字段、过期时间及分区信息。");
                            return;
                        }
                        int accepted = report.optInt("accepted", 0);
                        int verified = report.optInt("verified", 0);
                        showError(importing ? "Cookie 未全部确认：已接受 " + accepted + " 项，读回核对 " + verified
                                + " 项。请先导出核对，勿直接重复导入。" : "Cookie 导出未完成，未写入文件");
                        return;
                    }
                    if (importing) {
                        feedback.setTextColor(0xff168660);
                        feedback.setText(getString(R.string.landu_cookie_import_complete, report.getInt("verified")));
                        return;
                    }
                    String array = report.getJSONArray("cookies").toString();
                    int count = report.getInt("count");
                    int skipped = report.getInt("skipped");
                    runWork(() -> {
                        try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                            CookieTransferStreams.write(output, array);
                        }
                        try (InputStream input = getContentResolver().openInputStream(destination)) {
                            if (!CookieTransferStreams.read(input).equals(array)) throw new IOException("Cookie 文件读回不一致");
                        }
                        return null;
                    }, ignored -> {
                        feedback.setTextColor(skipped == 0 ? 0xff168660 : 0xffb54708);
                        feedback.setText(skipped == 0 ? getString(R.string.landu_cookie_export_complete, count)
                                : getString(R.string.landu_cookie_export_skipped_complete, count, skipped));
                    });
                } catch (org.json.JSONException | RuntimeException failure) {
                    showError(importing ? "Cookie 导入结果无效，请先导出核对，勿重复导入" : "Cookie 导出结果无效，未写入文件");
                }
            });
        });
    }

    private void runWork(Work operation, Result result) {
        if (working || isDestroyed()) return;
        uiGeneration++;
        working = true;
        updateStatusControls();
        if (feedback != null) { feedback.setTextColor(MUTED); feedback.setText("正在处理…"); }
        worker.execute(() -> {
            try {
                Object value = operation.run();
                main.post(() -> {
                    if (isDestroyed()) return;
                    working = false;
                    if (feedback != null) feedback.setText("");
                    result.accept(value);
                    if (restoredCookieTransferUnknown) {
                        restoredCookieTransferUnknown = false;
                        showError("上次 Cookie 操作结果未确认，请先导出核对；不会自动重复导入。");
                    }
                    updateStatusControls();
                    scheduleStatusPoll();
                    consumeDocumentSelection();
                    consumeCookieSelection();
                });
            } catch (IOException | RuntimeException error) {
                main.post(() -> {
                    working = false;
                    if (!isDestroyed()) { showError(error.getMessage()); updateStatusControls(); scheduleStatusPoll(); consumeDocumentSelection(); consumeCookieSelection(); }
                });
            }
        });
    }

    private void showError(String error) {
        if (feedback == null) {
            feedback = text("", 14, 400, 0xffb42318);
            content.addView(feedback);
        }
        feedback.setTextColor(0xffb42318);
        feedback.setText(error == null ? "操作失败，请重试" : error);
        // A list action can fail after its row has scrolled the notice offscreen.
        // Preserve editor field focus, and respect reduced-motion preferences.
        if (!editing && mainScroll != null) {
            TextView notice = feedback;
            long generation = uiGeneration;
            mainScroll.post(() -> {
                if (isDestroyed() || editing || feedback != notice || generation != uiGeneration) return;
                int top = Math.max(0, notice.getTop() - dp(16));
                if (ValueAnimator.areAnimatorsEnabled()) mainScroll.smoothScrollTo(0, top);
                else mainScroll.scrollTo(0, top);
            });
        }
    }

    private static final class ListSnapshot {
        final EnvironmentCatalog.Snapshot catalog;
        final EnvironmentLeaseStore.Observation observation;
        ListSnapshot(EnvironmentCatalog.Snapshot catalog, EnvironmentLeaseStore.Observation observation) {
            this.catalog = catalog;
            this.observation = observation;
        }
    }

    private void scheduleStatusPoll() {
        main.removeCallbacks(statusPoll);
        if (resumed && !editing && snapshot != null && !isDestroyed()) main.postDelayed(statusPoll, 1000);
    }

    private void pollStatus() {
        if (!resumed || editing || isDestroyed() || snapshot == null) return;
        if (working || checkingStatus) { scheduleStatusPoll(); return; }
        checkingStatus = true;
        long generation = uiGeneration;
        try {
            worker.execute(() -> {
                EnvironmentLeaseStore.Observation observed = null;
                String failure = null;
                try { observed = leases.observe(); }
                catch (IOException | RuntimeException error) { failure = "运行状态读取失败，请稍后重试"; }
                final EnvironmentLeaseStore.Observation result = observed;
                final String error = failure;
                main.post(() -> {
                    checkingStatus = false;
                    if (isDestroyed()) return;
                    if (resumed && !editing && !working && generation == uiGeneration) {
                        if (error == null) { observation = result; updateStatusControls(); }
                        else { observation = null; updateStatusControls(); showError(error); }
                    }
                    scheduleStatusPoll();
                });
            });
        } catch (RuntimeException error) {
            checkingStatus = false;
            showError("运行状态检查暂不可用，请重新打开管理页面");
        }
    }

    private void updateStatusControls() {
        if (editing) {
            for (Button action : new Button[] {cancelEditor, saveEnvironment}) {
                if (action != null) { action.setEnabled(!working); action.setAlpha(working ? .5f : 1); }
            }
            return;
        }
        if (activeStatus == null || closeCurrent == null) return;
        if (observation == null) {
            activeStatus.setText("运行状态暂不可用");
            activeStatus.setTextColor(MUTED);
            for (TextView label : statusLabels.values()) { label.setText("状态未知"); label.setTextColor(MUTED); }
            for (Button launch : launchButtons.values()) { launch.setEnabled(false); launch.setAlpha(.5f); }
            closeCurrent.setEnabled(false);
            closeCurrent.setAlpha(.5f);
            fixedActions.setVisibility(View.GONE);
            return;
        }
        String status;
        switch (observation.status) {
            case RUNNING: status = "运行中"; break;
            case PENDING: status = observation.processRunning ? "正在启动" : "待启动"; break;
            case STOPPING: status = "正在关闭"; break;
            case BUSY: status = "正在切换状态"; break;
            default: status = "当前没有运行中的环境";
        }
        String name = "";
        if (observation.ticket != null) {
            for (EnvironmentDefinition item : snapshot.environments) {
                if (observation.belongsTo(item.identity)) { name = item.name + " · "; break; }
            }
        }
        activeStatus.setText(getString(R.string.landu_status, name, status));
        activeStatus.setTextColor(observation.status == EnvironmentLeaseStore.Status.RUNNING ? 0xff168660 : MUTED);
        for (EnvironmentDefinition environment : snapshot.environments) {
            boolean current = observation.belongsTo(environment.identity);
            TextView label = statusLabels.get(environment.identity);
            if (label != null) {
                label.setText(current ? status : "已停止");
                label.setTextColor(current && observation.status == EnvironmentLeaseStore.Status.RUNNING ? 0xff168660 : MUTED);
            }
            Button launch = launchButtons.get(environment.identity);
            if (launch != null) {
                boolean idle = observation.status == EnvironmentLeaseStore.Status.IDLE;
                boolean running = current && observation.status == EnvironmentLeaseStore.Status.RUNNING;
                boolean pending = current && observation.status == EnvironmentLeaseStore.Status.PENDING && !observation.processRunning;
                launch.setEnabled(!working && (idle || running || pending));
                launch.setAlpha(launch.isEnabled() ? 1 : .5f);
                launch.setText(running ? (externalLink == null ? "继续浏览" : "打开链接")
                        : pending ? "重试启动" : "启动");
            }
        }
        boolean pending = observation.status == EnvironmentLeaseStore.Status.PENDING && !observation.processRunning;
        closeCurrent.setText(working ? "正在处理…" : pending ? "取消待启动" : "关闭当前环境");
        closeCurrent.setEnabled(!working && (pending || observation.status == EnvironmentLeaseStore.Status.RUNNING));
        closeCurrent.setAlpha(closeCurrent.isEnabled() ? 1 : .5f);
        fixedActions.setVisibility(observation.status == EnvironmentLeaseStore.Status.IDLE ? View.GONE : View.VISIBLE);
    }

    @Override protected void onResume() {
        super.onResume();
        applySystemBarStyle();
        resumed = true;
        if (snapshot != null && !editing && (!working || documentBusy)
                && (documentBusy || documentJobId != null || AndroidConfigurationJobs.get().currentId() != null)) resumeDocumentWork();
        else if (snapshot != null && !editing && !working) { renderEnvironmentCards(); pollStatus(); }
        else scheduleStatusPoll();
    }

    @Override protected void onPause() {
        closeAccountDialog();
        resumed = false;
        AndroidConfigurationJobs.get().detach(documentObserver);
        uiGeneration++;
        main.removeCallbacks(statusPoll);
        main.removeCallbacks(filterRefresh);
        super.onPause();
    }

    private void leaveEditor() {
        if (working) return;
        boolean changed = !nameField.getText().toString().equals(original == null ? "" : original.name)
                || !tagsField.getText().toString().equals(original == null ? "" : original.organization.tags)
                || !selectedGroupId.equals(original == null ? (filterGroupId == null ? "" : filterGroupId) : original.organization.groupId)
                || !notesField.getText().toString().equals(original == null ? "" : original.notes)
                || !urlField.getText().toString().equals(original == null || original.startPage.equals("about:blank") ? "" : original.startPage)
                || selectedCpu != (original == null ? 0 : original.fingerprint.hardwareConcurrency)
                || selectedMemory != (original == null ? 0 : original.fingerprint.deviceMemory)
                || selectedDnt != (original == null ? FingerprintSettings.DNT_DEFAULT : original.fingerprint.doNotTrack)
                || fontIsolationChoice.isChecked() != (original == null
                    ? FingerprintSettings.NEW_ENVIRONMENT_FONT_ISOLATION : original.fingerprint.regionalFontIsolation)
                || canvasNoiseChoice.isChecked() != (original == null
                    ? FingerprintSettings.NEW_ENVIRONMENT_CANVAS_NOISE : original.fingerprint.canvasNoise)
                || audioNoiseChoice.isChecked() != (original == null
                    ? FingerprintSettings.NEW_ENVIRONMENT_AUDIO_NOISE : original.fingerprint.audioNoise)
                || muteAudioChoice.isChecked() != (original != null && original.fingerprint.muteAudio)
                || clientRectsNoiseChoice.isChecked() != (original == null
                    ? FingerprintSettings.NEW_ENVIRONMENT_CLIENT_RECTS_NOISE : original.fingerprint.clientRectsNoise)
                || proxyInputChanged()
                || proxyAuthenticationChoice.isChecked() != (original != null && original.network.requiresAuthentication)
                || (proxyAuthenticationChoice.isChecked() && replaceProxyCredentialsChoice.isChecked()
                    && (proxyUsernameField.length() != 0 || proxyPasswordField.length() != 0))
                || webRtcChoice.isChecked() != (original != null && original.network.restrictWebRtcUdp)
                || !languagesField.getText().toString().equals(original == null ? "" : original.region.languages)
                || !timeZoneField.getText().toString().equals(original == null ? "" : original.region.timeZone);
        EnvironmentRegionalPolicy initialPolicy = original == null ? EnvironmentRegionalPolicy.desktopDefaults() : original.regionalPolicy;
        changed |= languageMode != initialPolicy.languageMode || timeZoneMode != initialPolicy.timeZoneMode
                || locationMode != initialPolicy.locationMode || !preferredLanguagesField.getText().toString().equals(initialPolicy.preferredLanguages);
        EnvironmentGeolocationSettings initialGeo = original == null ? EnvironmentGeolocationSettings.system() : original.geolocation;
        changed |= selectedGeolocationMode != initialGeo.mode
                || (selectedGeolocationMode == EnvironmentGeolocationSettings.CUSTOM
                    && (!latitudeField.getText().toString().equals(initialGeo.mode == EnvironmentGeolocationSettings.CUSTOM ? Double.toString(initialGeo.latitude) : "")
                        || !longitudeField.getText().toString().equals(initialGeo.mode == EnvironmentGeolocationSettings.CUSTOM ? Double.toString(initialGeo.longitude) : "")
                        || !accuracyField.getText().toString().equals(initialGeo.mode == EnvironmentGeolocationSettings.CUSTOM ? Double.toString(initialGeo.accuracy) : "100")));
        EnvironmentUserAgentSettings initialUa = original == null ? EnvironmentUserAgentSettings.automatic() : original.fingerprint.userAgent;
        int initialRaf = original == null ? 0 : original.fingerprint.animationFrames.maxFramesPerSecond;
        changed |= automaticUaChoice.isChecked() == initialUa.custom
                || (!automaticUaChoice.isChecked() && (!java.util.Arrays.equals(userAgentFieldValues(), initialUa.values())
                    || uaMobileChoice.isChecked() != initialUa.mobile || uaWow64Choice.isChecked() != initialUa.wow64));
        changed |= deviceRafChoice.isChecked() != (initialRaf == 0)
                || (!deviceRafChoice.isChecked() && !rafRateField.getText().toString().equals(Integer.toString(initialRaf)));
        changed |= followPhoneIdentity.isChecked() != (original == null || original.androidIdentity.isSystem())
                || (!followPhoneIdentity.isChecked()
                    && (!androidVersionField.getText().toString().equals(original == null ? "" : original.androidIdentity.platformVersion)
                        || !androidModelField.getText().toString().equals(original == null ? "" : original.androidIdentity.model)));
        changed |= followGpuIdentity.isChecked() != (original == null || original.fingerprint.gpuMetadata.isSystem())
                || (!followGpuIdentity.isChecked() && !java.util.Arrays.equals(gpuFieldValues(),
                    original == null ? EnvironmentGpuMetadata.system().values() : original.fingerprint.gpuMetadata.values()));
        EnvironmentMediaSettings initialMedia = original == null ? EnvironmentMediaSettings.isolated() : original.fingerprint.media;
        changed |= selectedMediaMode != initialMedia.mode || blockMediaChoice.isChecked() != initialMedia.blockCapture
                || !java.util.Arrays.equals(mediaLabelValues(), initialMedia.labels());
        if (!changed) { showList(); return; }
        new AlertDialog.Builder(this).setTitle("放弃未保存的修改？")
                .setNegativeButton("继续编辑", null).setPositiveButton("放弃修改", (dialog, which) -> showList()).show();
    }

    // Android 13+ uses the native OnBackInvokedDispatcher registered in onCreate.
    // Keep this override solely for hardware/back-button navigation on older OS versions.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (editing) leaveEditor(); else super.onBackPressed(); }

    private void acceptExternalLink(Intent intent) {
        if (intent == null) return;
        try {
            String link = intent.getStringExtra(LanduLinkActivity.EXTRA_LINK);
            if (link != null) externalLink = ExternalNavigation.webAddress(link);
            intent.removeExtra(LanduLinkActivity.EXTRA_LINK);
        } catch (RuntimeException ignored) { }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptExternalLink(intent);
        if (snapshot != null && !working && !editing) showList();
        else if (externalLink != null && feedback != null) feedback.setText("收到新链接，保存或返回列表后选择环境打开");
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("pendingCookieRequest", pendingCookieRequest);
        state.putBoolean("cookieTransferPending", cookieClient != null || restoredCookieTransferUnknown);
        state.putString("pendingCookieOwner", pendingCookieOwner);
        state.putLong("pendingCookieCreatedAt", pendingCookieCreatedAt);
        state.putString("queuedCookieUri", queuedCookieUri == null ? null : queuedCookieUri.toString());
        // Never persist Cookie JSON in an Activity Bundle, log, or draft.
        state.putBoolean("editing", editing);
        state.putString("externalLink", externalLink);
        state.putString("listQuery", listQuery);
        state.putString("filterGroupId", filterGroupId);
        state.putInt("listPage", listPage);
        state.putInt("pendingDocumentRequest", pendingDocumentRequest);
        state.putInt("queuedDocumentRequest", queuedDocumentRequest);
        state.putString("queuedDocumentUri", queuedDocumentUri == null ? null : queuedDocumentUri.toString());
        state.putString("pendingImportUri", pendingImportUri);
        state.putString("documentJobId", documentJobId);
        state.putBoolean("updateImportedExisting", updateImportedExisting);
        if (groupNameDialog != null && groupNameDialog.isShowing()) {
            state.putBoolean("groupNameDialog", true);
            state.putString("groupNameIdentity", groupNameIdentity);
            state.putString("groupName", groupNameField.getText().toString());
            state.putLong("groupNameRevision", groupNameRevision);
        }
        if (editing) {
            state.putLong("editorRevision", editorRevision);
            state.putInt("cpu", selectedCpu);
            state.putInt("memory", selectedMemory);
            state.putInt("doNotTrack", selectedDnt);
            state.putBoolean("regionalFontIsolation", fontIsolationChoice.isChecked());
            state.putBoolean("canvasNoise", canvasNoiseChoice.isChecked());
            state.putBoolean("audioNoise", audioNoiseChoice.isChecked());
            state.putBoolean("muteAudio", muteAudioChoice.isChecked());
            state.putBoolean("clientRectsNoise", clientRectsNoiseChoice.isChecked());
            state.putInt("mediaMode", selectedMediaMode);
            state.putBoolean("blockMedia", blockMediaChoice.isChecked());
            for (int i = 0; i < mediaLabels.length; ++i) state.putString("mediaLabel" + i, mediaLabels[i].getText().toString());
            state.putString("proxy", proxyField.getText().toString());
            state.putString("proxyProtocol", proxyProtocol);
            state.putBoolean("proxyAuthentication", proxyAuthenticationChoice.isChecked());
            state.putBoolean("replaceProxyCredentials", replaceProxyCredentialsChoice.isChecked());
            state.putBoolean("restrictWebRtcUdp", webRtcChoice.isChecked());
            state.putString("languages", languagesField.getText().toString());
            state.putString("timeZone", timeZoneField.getText().toString());
            state.putString("languagePolicy", languageMode.name());
            state.putString("zonePolicy", timeZoneMode.name());
            state.putString("geoPolicy", locationMode.name());
            state.putString("preferredLanguages", preferredLanguagesField.getText().toString());
            state.putInt("geolocationMode", selectedGeolocationMode);
            state.putString("latitude", latitudeField.getText().toString());
            state.putString("longitude", longitudeField.getText().toString());
            state.putString("accuracy", accuracyField.getText().toString());
            state.putString("androidVersion", androidVersionField.getText().toString());
            state.putString("androidModel", androidModelField.getText().toString());
            state.putBoolean("followPhoneIdentity", followPhoneIdentity.isChecked());
            state.putStringArray("userAgentFields", userAgentFieldValues());
            state.putBoolean("automaticUa", automaticUaChoice.isChecked());
            state.putBoolean("uaMobile", uaMobileChoice.isChecked());
            state.putBoolean("uaWow64", uaWow64Choice.isChecked());
            state.putBoolean("deviceRaf", deviceRafChoice.isChecked());
            state.putString("rafRate", rafRateField.getText().toString());
            state.putBoolean("followGpuIdentity", followGpuIdentity.isChecked());
            state.putStringArray("gpuMetadataFields", gpuFieldValues());
            state.putString("identity", original == null ? null : original.identity.id());
            state.putString("name", nameField.getText().toString());
            state.putString("url", urlField.getText().toString());
            state.putString("notes", notesField.getText().toString());
            state.putString("tags", tagsField.getText().toString());
            state.putString("selectedGroupId", selectedGroupId);
        }
    }

    @Override
    protected void onDestroy() {
        closeAccountDialog();
        if (cookiePreviewDialog != null) cookiePreviewDialog.dismiss();
        if (cookieClient != null) cookieClient.close();
        AndroidConfigurationJobs.get().detach(documentObserver);
        if (importPreviewDialog != null) importPreviewDialog.dismiss();
        main.removeCallbacks(filterRefresh);
        if (groupNameDialog != null) groupNameDialog.dismiss();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (sessionClient != null) sessionClient.close();
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }

    private void applySystemBarStyle() {
        if (isDestroyed()) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(appearance, appearance);
            }
        } else {
            getWindow().setStatusBarColor(0xfff6f8fc);
            getWindow().setNavigationBarColor(0xfff6f8fc);
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void setFixedActions(View actions) {
        fixedActions.removeAllViews();
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        fixedActions.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout body = vertical();
        actionBody = body;
        updateActionPadding();
        body.addView(actions, new LinearLayout.LayoutParams(-1, dp(BUTTON_HEIGHT)));
        fixedActions.addView(body, new LinearLayout.LayoutParams(-1, -2));
        fixedActions.setVisibility(View.VISIBLE);
    }

    private void updateActionPadding() {
        if (actionBody == null) return;
        int verticalPadding = compactActions ? 0 : dp(12);
        actionBody.setPadding(dp(20), verticalPadding, dp(20), verticalPadding);
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this) {
            @Override public void setPressed(boolean pressed) {
                super.setPressed(pressed);
                // 轻微按压反馈；系统关闭动画时立即恢复原尺寸。
                animate().cancel();
                if (!ValueAnimator.areAnimatorsEnabled()) { setScaleX(1); setScaleY(1); return; }
                animate().scaleX(pressed ? .98f : 1).scaleY(pressed ? .98f : 1)
                        .setDuration(PRESS_DURATION).setInterpolator(new PathInterpolator(.4f, 0, .2f, 1)).start();
            }
        };
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(16);
        button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        button.setTextColor(primary ? Color.WHITE : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinimumHeight(dp(BUTTON_HEIGHT));
        button.setMinHeight(dp(BUTTON_HEIGHT));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33ffffff : 0x14087bff),
                shape(primary ? BLUE : Color.WHITE, primary ? BLUE : BORDER, 12), null));
        button.setOnClickListener(view -> { if (!working) action.run(); });
        return button;
    }

    private void enterPage(int direction) {
        content.animate().cancel();
        // 页面复用同一个滚动容器；切页时移走旧表单焦点，避免键盘和滚动位置残留。
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        mainScroll.scrollTo(0, 0);
        final long generation = uiGeneration;
        mainScroll.post(() -> {
            if (!isDestroyed() && generation == uiGeneration) mainScroll.scrollTo(0, 0);
        });
        if (!ValueAnimator.areAnimatorsEnabled()) {
            content.setAlpha(1);
            content.setTranslationX(0);
            return;
        }
        // 编辑页向前轻推，返回列表反向退回，保持页面方向关系。
        content.setAlpha(.35f);
        content.setTranslationX(dp(12) * direction);
        content.animate().alpha(1).translationX(0).setDuration(direction == 0 ? 150 : 450)
                .setInterpolator(new PathInterpolator(0, 0, .2f, 1)).start();
    }

    private Button cpuChoice;
    private Button memoryChoice;

    private EnvironmentUserAgentSettings recommendedUserAgent() {
        final String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new IllegalStateException("无法读取当前内核版本", error); }
        String platformVersion = Build.VERSION.RELEASE.matches("[0-9]{1,3}(\\.[0-9]{1,5}){0,2}") ? Build.VERSION.RELEASE : "15";
        String model = Build.MODEL.matches("[A-Za-z0-9 ._()/-]{0,80}") ? Build.MODEL : "";
        return EnvironmentUserAgentSettings.androidDefaults(version, platformVersion, model);
    }

    private String[] userAgentFieldValues() {
        String[] values = new String[uaFields.length];
        for (int i = 0; i < values.length; i++) values[i] = uaFields[i].getText().toString();
        return values;
    }

    private void updateUserAgentFields() {
        boolean custom = !automaticUaChoice.isChecked();
        uaCustomFields.setVisibility(custom ? View.VISIBLE : View.GONE);
        followPhoneIdentity.setEnabled(!custom);
        updateIdentityFields();
        for (EditText field : uaFields) { field.setEnabled(custom); if (!custom) field.setError(null); }
    }

    private void updateRafFields() {
        boolean custom = !deviceRafChoice.isChecked();
        rafRateField.setEnabled(custom); rafRateField.setAlpha(custom ? 1 : .5f);
        if (!custom) rafRateField.setError(null);
    }

    private void updateIdentityFields() {
        boolean enabled = !followPhoneIdentity.isChecked() && (automaticUaChoice == null || automaticUaChoice.isChecked());
        for (EditText field : new EditText[] {androidVersionField, androidModelField}) {
            field.setEnabled(enabled);
            field.setAlpha(enabled ? 1 : .5f);
            if (!enabled) field.setError(null);
        }
    }

    private void updateGpuFields() {
        boolean custom = !followGpuIdentity.isChecked();
        gpuCustomFields.setVisibility(custom ? View.VISIBLE : View.GONE);
        for (EditText field : gpuFields) {
            field.setEnabled(custom);
            if (!custom) field.setError(null);
        }
    }

    private String[] gpuFieldValues() {
        String[] values = new String[gpuFields.length];
        for (int i = 0; i < values.length; i++) values[i] = gpuFields[i].getText().toString();
        return values;
    }

    private String[] mediaLabelValues() {
        String[] values = new String[3];
        for (int i = 0; i < values.length; ++i) values[i] = mediaLabels[i].getText().toString();
        return values;
    }

    private void updateMediaFields() {
        mediaModeChoice.setText(new String[] {"跟随设备", "按环境隔离标识", "隐藏设备名称"}[selectedMediaMode]);
        mediaLabelFields.setVisibility(selectedMediaMode == EnvironmentMediaSettings.ISOLATED ? View.VISIBLE : View.GONE);
    }

    private void chooseMediaMode() {
        new AlertDialog.Builder(this).setTitle("媒体设备模式")
                .setSingleChoiceItems(new String[] {"跟随设备", "按环境隔离标识", "隐藏设备名称"}, selectedMediaMode,
                        (dialog, which) -> { selectedMediaMode = which; updateMediaFields(); dialog.dismiss(); })
                .setNegativeButton("取消", null).show();
    }

    private void updateDntLabel() {
        if (dntChoice != null) dntChoice.setText(new String[] {"跟随此环境的浏览器设置", "开启", "关闭"}[selectedDnt]);
    }

    private void chooseDnt() {
        new AlertDialog.Builder(this).setTitle("Do Not Track")
                .setSingleChoiceItems(new String[] {"跟随此环境的浏览器设置", "开启", "关闭"}, selectedDnt,
                        (dialog, which) -> { selectedDnt = which; updateDntLabel(); dialog.dismiss(); })
                .setNegativeButton("取消", null).show();
    }

    private void updateHardwareLabels() {
        cpuChoice.setText(getString(R.string.landu_choice, selectedCpu == 0 ? "跟随设备" : getString(R.string.landu_cpu_count, selectedCpu)));
        memoryChoice.setText(getString(R.string.landu_choice, selectedMemory == 0 ? "跟随设备" : getString(R.string.landu_memory_count, selectedMemory)));
    }

    private void chooseHardware(boolean cpu) {
        int[] choices = cpu ? FingerprintSettings.cpuChoices() : FingerprintSettings.memoryChoices();
        String[] labels = new String[choices.length];
        int checked = 0;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i] == 0 ? "跟随设备" : choices[i] + (cpu ? " 核" : " GB");
            if (choices[i] == (cpu ? selectedCpu : selectedMemory)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(cpu ? "CPU 核心数" : "设备内存")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (cpu) selectedCpu = choices[which]; else selectedMemory = choices[which];
                    updateHardwareLabels();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private TextView text(String value, int size, int weight, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false));
        text.setLineSpacing(dp(3), 1);
        return text;
    }

    private Switch toggle(String label, boolean checked) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextSize(16);
        control.setTextColor(TEXT);
        control.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        control.setMinHeight(dp(BUTTON_HEIGHT));
        control.setSwitchPadding(dp(12));
        control.setShowText(false);
        int[][] states = {{android.R.attr.state_checked}, {}};
        control.setThumbTintList(new ColorStateList(states, new int[] {BLUE, Color.WHITE}));
        control.setTrackTintList(new ColorStateList(states, new int[] {0xffc7e2ff, 0xffd5dce7}));
        control.setChecked(checked);
        return control;
    }

    private void chooseRegion() {
        hideKeyboard();
        content.requestFocus();
        EnvironmentRegionSettings.Preset[] choices = EnvironmentRegionSettings.Preset.values();
        EnvironmentRegionSettings.Preset current = EnvironmentRegionSettings.matchingPreset(
                languagesField.getText().toString(), timeZoneField.getText().toString());
        String[] labels = new String[choices.length];
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i].label;
            if (choices[i] == current && languageMode != EnvironmentRegionalPolicy.LanguageMode.EXIT_IP
                    && timeZoneMode != EnvironmentRegionalPolicy.TimeZoneMode.EXIT_IP) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("网页语言与时区")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    languageMode = choices[which].languages.isEmpty() ? EnvironmentRegionalPolicy.LanguageMode.SYSTEM : EnvironmentRegionalPolicy.LanguageMode.FIXED;
                    timeZoneMode = choices[which].timeZone.isEmpty() ? EnvironmentRegionalPolicy.TimeZoneMode.SYSTEM : EnvironmentRegionalPolicy.TimeZoneMode.CUSTOM;
                    languagesField.setText(choices[which].languages);
                    timeZoneField.setText(choices[which].timeZone);
                    updateRegionalPolicyFields();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void updateRegionLabel() {
        EnvironmentRegionSettings.Preset current = EnvironmentRegionSettings.matchingPreset(
                languagesField.getText().toString(), timeZoneField.getText().toString());
        String label = languageMode == EnvironmentRegionalPolicy.LanguageMode.EXIT_IP
                || timeZoneMode == EnvironmentRegionalPolicy.TimeZoneMode.EXIT_IP
                ? "选择常用地区（手动设置）" : current == null ? "自定义语言与时区" : current.label;
        regionChoice.setText(label);
        regionChoice.setContentDescription("网页语言与时区：" + label);
    }

    private void chooseLanguagePolicy() {
        hideKeyboard(); content.requestFocus();
        new AlertDialog.Builder(this).setTitle("浏览器语言")
                .setSingleChoiceItems(new String[] {"基于出口 IP 所在地", "跟随手机语言", "固定网页语言"}, LANGUAGE_POLICIES.indexOf(languageMode), (dialog, which) -> {
                    languageMode = LANGUAGE_POLICIES.get(which);
                    updateRegionalPolicyFields(); dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseTimeZonePolicy() {
        hideKeyboard(); content.requestFocus();
        new AlertDialog.Builder(this).setTitle("出口时区")
                .setSingleChoiceItems(new String[] {"基于出口 IP", "使用手机真实时区", "自定义时区"}, ZONE_POLICIES.indexOf(timeZoneMode), (dialog, which) -> {
                    timeZoneMode = ZONE_POLICIES.get(which);
                    updateRegionalPolicyFields(); dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void updateRegionalPolicyFields() {
        languagePolicyChoice.setText(new String[] {"基于出口 IP 所在地", "跟随手机语言", "固定网页语言"}[LANGUAGE_POLICIES.indexOf(languageMode)]);
        timeZonePolicyChoice.setText(new String[] {"基于出口 IP", "使用手机真实时区", "自定义时区"}[ZONE_POLICIES.indexOf(timeZoneMode)]);
        languagesField.setEnabled(languageMode == EnvironmentRegionalPolicy.LanguageMode.FIXED);
        languagesField.setAlpha(languagesField.isEnabled() ? 1f : .5f);
        timeZoneField.setEnabled(timeZoneMode == EnvironmentRegionalPolicy.TimeZoneMode.CUSTOM);
        timeZoneField.setAlpha(timeZoneField.isEnabled() ? 1f : .5f);
        preferredLanguagesField.setEnabled(languageMode == EnvironmentRegionalPolicy.LanguageMode.EXIT_IP);
        preferredLanguagesField.setAlpha(preferredLanguagesField.isEnabled() ? 1f : .5f);
        updateRegionLabel();
    }

    private static <T extends Enum<T>> T draftPolicy(Bundle draft, String key, T fallback) {
        try { return Enum.valueOf(fallback.getDeclaringClass(), draft.getString(key, fallback.name())); }
        catch (IllegalArgumentException invalid) { return fallback; }
    }

    private LinearLayout card() {
        LinearLayout view = vertical();
        view.setPadding(dp(18), dp(18), dp(18), dp(18));
        view.setBackground(shape(Color.WHITE, BORDER, 16));
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private LinearLayout.LayoutParams block(int height, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(height));
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface Work { Object run() throws IOException; }
    private interface Result { void accept(Object result); }
}
