import { createHash } from 'node:crypto';
import fs from 'node:fs';
import { restrictManifestComponents, libraryManifestOverrides } from './manifest-policy.mjs';

const inventory = JSON.parse(fs.readFileSync(new URL('../overlay-inventory.json', import.meta.url), 'utf8'));
if (inventory.format !== 1 || inventory.packagePath !== 'com/landu/browser/nativecore'
    || !Array.isArray(inventory.javaFiles) || !Array.isArray(inventory.nativeFiles)) {
  throw new Error('Invalid native overlay inventory');
}
export const { javaFiles, nativeFiles, packagePath, resourceFiles = [] } = inventory;

export const bootstrapPath = 'chrome/android/java/src/org/chromium/chrome/browser/base/SplitCompatApplication.java';
export const buildPath = 'chrome/android/BUILD.gn';
export const splitPath = 'chrome/android/java/src/org/chromium/chrome/browser/base/SplitChromeApplication.java';
export const activityPath = 'chrome/android/java/src/org/chromium/chrome/browser/ChromeBaseAppCompatActivity.java';
export const factoryPath = 'chrome/android/java/src/org/chromium/chrome/browser/base/SplitCompatAppComponentFactory.java';
export const initializationPath = 'chrome/android/java/src/org/chromium/chrome/browser/init/ProcessInitializationHandler.java';
export const proxyLoginPath = 'chrome/browser/ui/android/login_handler_android.cc';
export const cookiesJavaPath = 'chrome/browser/android/cookies/java/src/org/chromium/chrome/browser/cookies/CookiesFetcher.java';
export const cookiesNativePath = 'chrome/browser/android/cookies/cookies_fetcher_util.cc';
export const cookiesBuildPath = 'chrome/browser/android/BUILD.gn';
export const cookiesJavaBuildPath = 'chrome/browser/android/cookies/BUILD.gn';
export const proxyAuthNativePath = 'chrome/browser/ui/android/chrome_http_auth_handler.cc';
export const proxyAuthHeaderPath = 'chrome/browser/ui/android/chrome_http_auth_handler.h';
export const proxyAuthJavaPath = 'chrome/android/java/src/org/chromium/chrome/browser/login/ChromeHttpAuthHandler.java';
export const proxyLoginBasePath = 'chrome/browser/ui/login/login_handler.cc';
export const tabbedPath = 'chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java';
export const tabOrchestratorPath = 'chrome/android/java/src/org/chromium/chrome/browser/app/tabmodel/TabModelOrchestrator.java';
export const tabStorageJavaPath = 'chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/TabStateStorageService.java';
export const tabStorageNativePath = 'chrome/browser/android/tab_state_storage_service_android.cc';
export const tabStorageHeaderPath = 'chrome/browser/android/tab_state_storage_service_android.h';
export const tabStorePath = 'chrome/android/java/src/org/chromium/chrome/browser/app/tabmodel/TabStateStore.java';
export const modelTrackingPath = 'chrome/android/java/src/org/chromium/chrome/browser/app/tabmodel/ModelTrackingOrchestrator.java';
export const tabStoreInterfacePath = 'chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabPersistentStore.java';
export const collectionJavaPath = 'chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/StorageCollectionSynchronizer.java';
export const collectionNativePath = 'chrome/browser/android/storage_collection_synchronizer_android.cc';
export const collectionHeaderPath = 'chrome/browser/android/storage_collection_synchronizer_android.h';
export const tabPersistenceBuildPath = 'chrome/browser/tabpersistence/BUILD.gn';
export const legacyStorePath = 'chrome/android/java/src/org/chromium/chrome/browser/tabmodel/TabPersistentStoreImpl.java';
export const tabStateFilePath = 'chrome/browser/tabpersistence/android/java/src/org/chromium/chrome/browser/tabpersistence/TabStateFileManager.java';
export const tabMetadataFilePath = 'chrome/browser/tabpersistence/android/java/src/org/chromium/chrome/browser/tabpersistence/TabMetadataFileManager.java';
export const tabAttributesPath = 'chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/TabStateAttributes.java';
export const tabExtractorPath = 'chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/TabStateExtractor.java';
export const manifestPath = 'chrome/android/java/AndroidManifest.xml';
export const taskInfoPath = 'components/background_task_scheduler/android/java/src/org/chromium/components/background_task_scheduler/TaskInfo.java';
export const taskSchedulerPath = 'components/background_task_scheduler/internal/android/java/src/org/chromium/components/background_task_scheduler/internal/BackgroundTaskSchedulerImpl.java';
export const taskServicePath = 'components/background_task_scheduler/internal/android/java/src/org/chromium/components/background_task_scheduler/internal/BackgroundTaskJobService.java';
export const taskJobPath = 'components/background_task_scheduler/internal/android/java/src/org/chromium/components/background_task_scheduler/internal/BackgroundTaskSchedulerJobService.java';
export const intentUtilsPath = 'base/android/java/src/org/chromium/base/IntentUtils.java';
export const pendingIntentPath = 'components/browser_ui/notifications/android/java/src/org/chromium/components/browser_ui/notifications/PendingIntentProvider.java';
const notificationPackage = 'components/browser_ui/notifications/android/java/src/org/chromium/components/browser_ui/notifications/';
export const notificationMetadataPath = notificationPackage + 'NotificationMetadata.java';
export const notificationProxyPath = notificationPackage + 'NotificationManagerProxyImpl.java';
export const asyncNotificationProxyPath = notificationPackage + 'AsyncNotificationManagerProxyImpl.java';
export const notificationAdaptorPath = notificationPackage + 'StatusBarNotificationAdaptor.java';
export const foregroundServicePath = notificationPackage + 'ForegroundServiceUtils.java';
export const notificationJobPath = 'chrome/android/java/src/org/chromium/chrome/browser/notifications/NotificationJobService.java';
export const notificationJobImplPath = 'chrome/android/java/src/org/chromium/chrome/browser/notifications/NotificationJobServiceImpl.java';
export const notificationServiceImplPath = 'chrome/android/java/src/org/chromium/chrome/browser/notifications/NotificationServiceImpl.java';
export const downloadBroadcastImplPath = 'chrome/android/java/src/org/chromium/chrome/browser/download/DownloadBroadcastManagerImpl.java';
export const notificationInterceptorPath = 'chrome/browser/notifications/android/java/src/org/chromium/chrome/browser/notifications/NotificationIntentInterceptor.java';
export const cpuPath = 'third_party/blink/renderer/core/frame/navigator_concurrent_hardware.cc';
export const fontPath = 'third_party/blink/renderer/platform/fonts/font_platform_data_cache.cc';
export const memoryPath = 'third_party/blink/common/device_memory/approximated_device_memory.cc';
export const browserClientPath = 'chrome/browser/chrome_content_browser_client.cc';
export const browserClientHeaderPath = 'chrome/browser/chrome_content_browser_client.h';
export const browserBuildPath = 'chrome/browser/BUILD.gn';
export const permissionsBuildPath = 'components/permissions/BUILD.gn';
export const permissionContextPath = 'components/permissions/permission_context_base.cc';
export const commonBuildPath = 'third_party/blink/public/common/BUILD.gn';
export const profileJavaPath = 'chrome/browser/profiles/android/java/src/org/chromium/chrome/browser/profiles/ProfileManagerUtils.java';
export const profileNativePath = 'chrome/browser/profiles/android/profile_manager_utils.cc';
export const rendererHostPath = 'content/browser/renderer_host/render_process_host_impl.cc';
export const timezonePath = 'third_party/blink/renderer/core/timezone/timezone_controller.cc';
export const canvas2dPath = 'third_party/blink/renderer/modules/canvas/canvas2d/base_rendering_context_2d.cc';
export const canvasElementPath = 'third_party/blink/renderer/core/html/canvas/html_canvas_element.cc';
export const canvasBlobPath = 'third_party/blink/renderer/core/html/canvas/canvas_async_blob_creator.cc';
export const imageBufferPath = 'third_party/blink/renderer/platform/graphics/image_data_buffer.cc';
export const imageBufferHeaderPath = 'third_party/blink/renderer/platform/graphics/image_data_buffer.h';
export const platformBuildPath = 'third_party/blink/renderer/platform/BUILD.gn';
export const preferencePath = 'chrome/browser/prefs/chrome_command_line_pref_store.cc';
export const userAgentPath = 'components/embedder_support/user_agent_utils.cc';
export const userAgentBuildPath = 'components/embedder_support/BUILD.gn';
export const apkTemplatePath = 'chrome/android/chrome_public_apk_tmpl.gni';
export const webGlPath = 'third_party/blink/renderer/modules/webgl/webgl_rendering_context_base.cc';
export const webGlHeaderPath = 'third_party/blink/renderer/modules/webgl/webgl_rendering_context_base.h';
export const webGl2Path = 'third_party/blink/renderer/modules/webgl/webgl2_rendering_context_base.cc';
export const webGlBuildPath = 'third_party/blink/renderer/modules/webgl/BUILD.gn';
export const webGpuPath = 'third_party/blink/renderer/modules/webgpu/gpu_adapter.cc';
export const offlineAudioPath = 'third_party/blink/renderer/modules/webaudio/offline_audio_context.cc';
export const analyserPath = 'third_party/blink/renderer/modules/webaudio/analyser_node.cc';
export const audioWorkletPath = 'third_party/blink/renderer/modules/webaudio/audio_worklet_processor.cc';
export const audioWorkletHeaderPath = 'third_party/blink/renderer/modules/webaudio/audio_worklet_processor.h';
export const webAudioBuildPath = 'third_party/blink/renderer/modules/webaudio/BUILD.gn';
export const elementPath = 'third_party/blink/renderer/core/dom/element.cc';
export const rangePath = 'third_party/blink/renderer/core/dom/range.cc';
export const geometryBuildPath = 'third_party/blink/renderer/core/geometry/build.gni';
export const digest = value => createHash('sha256').update(value).digest('hex');
const runtime = 'com.landu.browser.nativecore.NativeEnvironmentRuntime';
const managerCheck = `${runtime}.isManagerProcess()`;
const settingsInclude = '#include "third_party/blink/public/common/landu/landu_fingerprint_settings.h"\n';
const pendingIntentOwnershipCode = `
    /** Process-fixed identity for environment-owned Android operations. */
    public static final class LanduEnvironment {
        private static final String RESERVED_PREFIX = "com.landu.browser.intent:";
        private static final String PREFIX = RESERVED_PREFIX + "v1:";
        private static final Object LOCK = new Object();
        private static volatile @Nullable String sOwner;
        private static volatile @Nullable NotificationIds sNotificationIds;
        private static volatile java.util.function.@Nullable BooleanSupplier sNotificationDispatchGate;

        private LanduEnvironment() {}

        /** Immutable for the lifetime of this browser process; null before the lease is claimed. */
        public static @Nullable String getCurrentOwner() {
            return sOwner;
        }

        public static void bindNotificationDispatchGate(String owner, java.util.function.BooleanSupplier gate) {
            synchronized (LOCK) {
                if (!owner.equals(sOwner) || sNotificationDispatchGate != null) {
                    throw new IllegalStateException("Notification dispatch must use the fixed process owner");
                }
                sNotificationDispatchGate = gate;
            }
        }

        public static boolean canDispatchNotification(Intent intent) {
            java.util.function.BooleanSupplier gate = sNotificationDispatchGate;
            return gate != null && owns(intent) && gate.getAsBoolean();
        }

        /** The implementation lives in the app's private control directory, outside profile data. */
        public interface NotificationIds {
            int getOrCreate(int logicalId);
            int find(int logicalId);
            boolean owns(int platformId);
        }

        public static void bindNotificationIds(String owner, NotificationIds ids) {
            synchronized (LOCK) {
                if (!owner.equals(sOwner) || (sNotificationIds != null && sNotificationIds != ids)) {
                    throw new IllegalStateException("Notification IDs must use the fixed process owner");
                }
                sNotificationIds = ids;
            }
        }

        private static NotificationIds requireNotificationIds() {
            NotificationIds ids = sNotificationIds;
            if (sOwner == null || ids == null) throw new IllegalStateException("Notification IDs are unavailable");
            return ids;
        }

        public static int notificationId(int logicalId) {
            return requireNotificationIds().getOrCreate(logicalId);
        }

        public static int findNotificationId(int logicalId) {
            return requireNotificationIds().find(logicalId);
        }

        public static boolean ownsNotificationId(int platformId) {
            return requireNotificationIds().owns(platformId);
        }

        /** Called after the native environment lease is claimed, before Chromium startup. */
        public static void initialize(String owner) {
            synchronized (LOCK) {
                if (!isCanonicalOwner(owner)) {
                    throw new IllegalArgumentException("Invalid Landu environment identity");
                }
                if (sOwner != null && !owner.equals(sOwner)) {
                    throw new IllegalStateException("Cannot switch environment inside a browser process");
                }
                sOwner = owner;
            }
        }

        private static boolean isCanonicalOwner(@Nullable String value) {
            if (value == null || value.length() != 36) return false;
            try {
                return java.util.UUID.fromString(value).toString().equals(value);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }

        /** Reads only the filter identity; mutable extras are never an ownership authority. */
        public static @Nullable String ownerOf(@Nullable Intent intent) {
            if (intent == null) return null;
            String identifier = intent.getIdentifier();
            int offset = PREFIX.length();
            if (identifier == null || !identifier.startsWith(PREFIX)
                    || identifier.length() < offset + 38
                    || identifier.charAt(offset + 36) != ':') {
                return null;
            }
            String owner = identifier.substring(offset, offset + 36);
            if (!isCanonicalOwner(owner)) return null;
            char kind = identifier.charAt(offset + 37);
            if (kind == '0' && identifier.length() == offset + 38) return owner;
            return kind == '1' ? owner : null;
        }

        public static boolean owns(@Nullable Intent intent) {
            String owner = sOwner;
            return owner != null && owner.equals(ownerOf(intent));
        }

        /** Copies the caller's intent; null and empty original identifiers stay distinct. */
        public static Intent scopePendingIntent(Intent original) {
            String owner = sOwner;
            if (owner == null) {
                throw new IllegalStateException("No active Landu environment for PendingIntent");
            }
            String identifier = original.getIdentifier();
            if (identifier != null && identifier.startsWith(RESERVED_PREFIX)) {
                if (!owner.equals(ownerOf(original))) {
                    throw new IllegalArgumentException("Cannot retag a foreign or malformed intent");
                }
                return new Intent(original);
            }
            Intent scoped = new Intent(original);
            scoped.setIdentifier(PREFIX + owner + ':'
                    + (identifier == null ? "0" : "1" + identifier));
            return scoped;
        }

        /** RemoteInput may add extras, but may not replace the bound filter identifier. */
        public static int protectPendingIntentFlags(int flags) {
            return flags & ~Intent.FILL_IN_IDENTIFIER;
        }
    }

`;
const notificationTagCode = `
    private static final String LANDU_TAG_RESERVED = "com.landu.browser.notification:";
    private static final String LANDU_TAG_PREFIX = LANDU_TAG_RESERVED + "v1:";

    /** Map untagged IDs at the OS boundary only; tagged IDs already have an owner namespace. */
    static int landuId(@Nullable String tag, int logicalId) {
        return tag == null ? org.chromium.base.IntentUtils.LanduEnvironment.notificationId(logicalId) : logicalId;
    }

    static void landuCancel(androidx.core.app.NotificationManagerCompat manager,
            @Nullable String tag, int logicalId) {
        if (tag != null) {
            manager.cancel(landuTag(tag), logicalId);
            return;
        }
        int platformId = org.chromium.base.IntentUtils.LanduEnvironment.findNotificationId(logicalId);
        if (platformId != 0) manager.cancel(platformId);
    }

    /** Non-null tags use an injective owner + tag encoding, without hashing notification IDs. */
    static @Nullable String landuTag(@Nullable String tag) {
        // Foreground services use the ID mapper, since Android does not accept a tag there.
        if (tag == null) return null;
        String owner = org.chromium.base.IntentUtils.LanduEnvironment.getCurrentOwner();
        if (owner == null) throw new IllegalStateException("No active Landu notification owner");
        if (tag.startsWith(LANDU_TAG_RESERVED)) {
            if (!landuOwnsTag(tag)) {
                throw new IllegalArgumentException("Cannot retag a foreign or malformed notification");
            }
            return tag;
        }
        return LANDU_TAG_PREFIX + owner + ':' + tag;
    }

    static boolean landuOwnsTag(@Nullable String tag) {
        String owner = org.chromium.base.IntentUtils.LanduEnvironment.getCurrentOwner();
        return owner != null && tag != null && tag.startsWith(LANDU_TAG_PREFIX + owner + ':');
    }

    /** Only called after ownership filtering; callers keep seeing their original logical tag. */
    static String landuLogicalTag(@Nullable String tag) {
        if (tag == null || !landuOwnsTag(tag)) {
            throw new IllegalArgumentException("Notification is outside the selected environment");
        }
        return tag.substring(LANDU_TAG_PREFIX.length() + 37);
    }

    /** Never clear another environment or Android foreground-service notifications. */
    static void landuCancelAll(androidx.core.app.NotificationManagerCompat manager) {
        for (var notification : manager.getActiveNotifications()) {
            if (landuOwnsTag(notification.getTag())) {
                manager.cancel(notification.getTag(), notification.getId());
            } else if (notification.getTag() == null
                    && (notification.getNotification().flags
                        & android.app.Notification.FLAG_FOREGROUND_SERVICE) == 0
                    && org.chromium.base.IntentUtils.LanduEnvironment.ownsNotificationId(notification.getId())) {
                manager.cancel(notification.getId());
            }
        }
    }

`;
const taskOwnershipCode = `
    private static final String LANDU_OWNER = "com.landu.browser.task.OWNER";
    private static final String LANDU_SCHEMA = "com.landu.browser.task.SCHEMA";
    private static final String LANDU_NAMESPACE = "com.landu.browser.environment.";

    private static @Nullable String canonicalLanduOwner(@Nullable String owner) {
        if (owner == null || owner.length() != 36) return null;
        try {
            return java.util.UUID.fromString(owner).toString().equals(owner) ? owner : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static @Nullable String currentLanduEnvironmentId() {
        if (!CommandLine.isInitialized()) return null;
        return canonicalLanduOwner(CommandLine.getInstance().getSwitchValue("landu-environment-id"));
    }

    /** Namespace for the selected environment; never return the global namespace. */
    public static @Nullable String getLanduJobNamespace() {
        String owner = currentLanduEnvironmentId();
        return owner == null ? null : LANDU_NAMESPACE + owner;
    }

    /** Validate a queued task independently of the currently selected environment. */
    public static @Nullable String getLanduJobNamespace(PersistableBundle extras) {
        try {
            if (extras.getInt(LANDU_SCHEMA, 0) != 1) return null;
            String owner = canonicalLanduOwner(extras.getString(LANDU_OWNER));
            return owner == null ? null : LANDU_NAMESPACE + owner;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** Reject tasks from other environments, legacy tasks without an owner, and malformed tags. */
    public static boolean hasMatchingLanduEnvironment(PersistableBundle extras) {
        String owner = currentLanduEnvironmentId();
        if (owner == null) return false;
        try {
            return extras.getInt(LANDU_SCHEMA, 0) == 1
                    && owner.equals(extras.getString(LANDU_OWNER));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static PersistableBundle copyLanduExtras(@Nullable PersistableBundle source) {
        PersistableBundle extras = source == null
                ? new PersistableBundle() : new PersistableBundle(source);
        String owner = currentLanduEnvironmentId();
        if (owner == null) return extras;
        if (extras.containsKey(LANDU_OWNER) || extras.containsKey(LANDU_SCHEMA)) {
            if (!hasMatchingLanduEnvironment(extras)) {
                throw new IllegalArgumentException("Task belongs to a different or unknown Landu environment");
            }
        } else {
            extras.putString(LANDU_OWNER, owner);
            extras.putInt(LANDU_SCHEMA, 1);
        }
        return extras;
    }
`;
const notificationJobOwnershipCode = `
    private static final String LANDU_IDENTIFIER = "com.landu.browser.notification.IDENTIFIER";
    private static final String LANDU_NAMESPACE = "com.landu.browser.notifications.";
    private final android.os.Handler mLanduHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.Map<String, android.app.job.JobParameters> mLanduDeferred =
            new java.util.HashMap<>();
    private final java.util.Map<String, Runnable> mLanduCallbacks = new java.util.HashMap<>();
    private boolean mLanduDestroyed;

    static void putLanduIdentity(android.content.Intent intent, android.os.PersistableBundle extras) {
        if (org.chromium.base.IntentUtils.LanduEnvironment.ownerOf(intent) == null) {
            throw new IllegalArgumentException("Notification has no valid environment identity");
        }
        extras.putString(LANDU_IDENTIFIER, intent.getIdentifier());
    }

    static @org.chromium.build.annotations.Nullable String landuNamespace(android.content.Intent intent) {
        String owner = org.chromium.base.IntentUtils.LanduEnvironment.ownerOf(intent);
        return owner == null ? null : LANDU_NAMESPACE + owner;
    }

    /** Never fall back to the application's global job queue. */
    static android.app.job.@org.chromium.build.annotations.Nullable JobScheduler landuScheduler(
            android.content.Context context, android.content.Intent intent) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null;
        String namespace = landuNamespace(intent);
        if (namespace == null) return null;
        try {
            android.app.job.JobScheduler scheduler = (android.app.job.JobScheduler)
                    context.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE);
            return scheduler == null ? null : scheduler.forNamespace(namespace);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Recover the filter identity from our private job envelope and verify its OS namespace. */
    static android.content.@org.chromium.build.annotations.Nullable Intent landuJobIntent(
            android.app.job.JobParameters params) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null;
        try {
            String identifier = params.getExtras().getString(LANDU_IDENTIFIER);
            android.content.Intent intent = new android.content.Intent().setIdentifier(identifier);
            String namespace = landuNamespace(intent);
            return namespace != null && namespace.equals(params.getJobNamespace()) ? intent : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    @Override
    public boolean onStartJob(android.app.job.JobParameters params) {
        if (mLanduDestroyed) return false;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false;
        android.content.Intent intent = landuJobIntent(params);
        if (intent == null) return false;
        if (org.chromium.base.IntentUtils.LanduEnvironment.owns(intent)) {
            return super.onStartJob(params);
        }
        String key = params.getJobNamespace() + ":" + params.getJobId();
        Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (mLanduDestroyed || mLanduCallbacks.get(key) != this
                        || mLanduDeferred.get(key) != params) return;
                mLanduCallbacks.remove(key);
                mLanduDeferred.remove(key);
                jobFinished(params, true);
            }
        };
        Runnable previous = mLanduCallbacks.put(key, finish);
        if (previous != null) mLanduHandler.removeCallbacks(previous);
        mLanduDeferred.put(key, params);
        if (mLanduHandler.post(finish)) return true;
        mLanduCallbacks.remove(key);
        mLanduDeferred.remove(key);
        return false;
    }

    @Override
    public boolean onStopJob(android.app.job.JobParameters params) {
        if (mLanduDestroyed) return false;
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false;
        android.content.Intent intent = landuJobIntent(params);
        if (intent == null) return false;
        if (org.chromium.base.IntentUtils.LanduEnvironment.owns(intent)) {
            return super.onStopJob(params);
        }
        String key = params.getJobNamespace() + ":" + params.getJobId();
        if (mLanduDeferred.get(key) != params) return false;
        mLanduDeferred.remove(key);
        Runnable callback = mLanduCallbacks.remove(key);
        if (callback != null) mLanduHandler.removeCallbacks(callback);
        return true;
    }

    @Override
    public void onDestroy() {
        mLanduDestroyed = true;
        mLanduHandler.removeCallbacksAndMessages(null);
        mLanduCallbacks.clear();
        mLanduDeferred.clear();
        super.onDestroy();
    }

`;
const canvasInclude = '#include "third_party/blink/renderer/platform/graphics/landu_canvas_readback.h"\n';
const geolocationBlock = '#if BUILDFLAG(IS_ANDROID)\n' +
  '  if ((content_settings_type_ == ContentSettingsType::MEDIASTREAM_MIC ||\n' +
  '       content_settings_type_ == ContentSettingsType::MEDIASTREAM_CAMERA ||\n' +
  '       content_settings_type_ == ContentSettingsType::CAMERA_PAN_TILT_ZOOM) &&\n' +
  '      base::CommandLine::ForCurrentProcess()->HasSwitch("landu-block-media")) {\n' +
  '    return content::PermissionResult(PermissionStatus::DENIED,\n' +
  '                                     content::PermissionStatusSource::KILL_SWITCH);\n' +
  '  }\n' +
  '  if ((content_settings_type_ == ContentSettingsType::GEOLOCATION ||\n' +
  '       content_settings_type_ == ContentSettingsType::GEOLOCATION_WITH_OPTIONS) &&\n' +
  '      landu::ReadGeolocationPolicy().mode == landu::GeolocationMode::kBlocked) {\n' +
  '    return content::PermissionResult(PermissionStatus::DENIED,\n' +
  '                                     content::PermissionStatusSource::UNSPECIFIED);\n' +
  '  }\n#endif\n';
const taskSchedulerNamespaceCode = `    private static @Nullable JobScheduler landuJobScheduler(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null;
        String namespace = TaskInfo.getLanduJobNamespace();
        if (namespace == null) return null;
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            return scheduler == null ? null : scheduler.forNamespace(namespace);
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to access the selected Landu job namespace", error);
            return null;
        }
    }

`;
const taskServiceNamespaceCode = `    private final Map<String, BackgroundTask> mCurrentTasks = new HashMap<>();
    private final Map<String, TaskParameters> mLanduTaskParameters = new HashMap<>();
    private final Map<String, TaskFinishedCallbackJobService> mLanduTaskCallbacks = new HashMap<>();
    private final Map<String, Runnable> mLanduDeferredJobs = new HashMap<>();
    private final android.os.Handler mLanduDeferredHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static String landuJobKey(JobParameters params) {
        String namespace = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? params.getJobNamespace() : null;
        return (namespace == null ? "" : namespace) + ":" + params.getJobId();
    }

    private void removeLanduCurrentTask(JobParameters params) {
        String key = landuJobKey(params);
        mCurrentTasks.remove(key);
        mLanduTaskParameters.remove(key);
        mLanduTaskCallbacks.remove(key);
    }

    private boolean deferLanduJob(JobParameters params) {
        String key = landuJobKey(params);
        Runnable finish = new Runnable() {
            @Override
            public void run() {
                if (mLanduDeferredJobs.get(key) != this) return;
                mLanduDeferredJobs.remove(key);
                jobFinished(params, true);
            }
        };
        Runnable previous = mLanduDeferredJobs.put(key, finish);
        if (previous != null) mLanduDeferredHandler.removeCallbacks(previous);
        if (mLanduDeferredHandler.post(finish)) return true;
        mLanduDeferredJobs.remove(key);
        return false;
    }

    @Override
    public void onDestroy() {
        mLanduDeferredHandler.removeCallbacksAndMessages(null);
        mLanduDeferredJobs.clear();
        mCurrentTasks.clear();
        mLanduTaskParameters.clear();
        mLanduTaskCallbacks.clear();
        super.onDestroy();
    }
`;
const shutdownNativeCode = `static void JNI_ProfileManagerUtils_FlushPersistentDataBeforeLanduExit(
    JNIEnv* env,
    const JavaRef<::java::lang::JRunnable>& on_flushed,
    const JavaRef<::java::lang::JRunnable>& on_failure) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  std::vector<PrefService*> preferences;
  std::vector<content::StoragePartition*> partitions;
  for (Profile* profile : g_browser_process->profile_manager()->GetLoadedProfiles()) {
    preferences.push_back(profile->GetPrefs());
    profile->ForEachLoadedStoragePartition(
        [&partitions](content::StoragePartition* partition) {
          partitions.push_back(partition);
        });
  }
  if (g_browser_process->local_state())
    preferences.push_back(g_browser_process->local_state());

  // Preference replies and cookie Mojo replies are dispatched on this UI
  // sequence. A dropped cookie callback must report failure, not success.
  auto failed = std::make_shared<bool>(false);
  auto barrier = base::BarrierClosure(
      preferences.size() + partitions.size(),
      base::BindOnce(
          [](std::shared_ptr<bool> failed,
             base::android::ScopedJavaGlobalRef<::java::lang::JRunnable> done,
             base::android::ScopedJavaGlobalRef<::java::lang::JRunnable> error) {
            jni_zero::RunRunnable(*failed ? error : done);
          },
          failed, base::android::ScopedJavaGlobalRef<::java::lang::JRunnable>(env, on_flushed),
          base::android::ScopedJavaGlobalRef<::java::lang::JRunnable>(env, on_failure)));
  for (PrefService* preference : preferences)
    preference->CommitPendingWrite(barrier);
  for (content::StoragePartition* partition : partitions) {
    // DOM storage schedules its writes here; its completion is not exposed by
    // this upstream API. Do not label this callback as an all-storage barrier.
    partition->Flush();
    partition->GetCookieManagerForBrowserProcess()->FlushCookieStore(
        mojo::WrapCallbackWithDropHandler(
            base::BindOnce(barrier),
            base::BindOnce(
                [](std::shared_ptr<bool> failed, base::RepeatingClosure ready) {
                  *failed = true;
                  ready.Run();
                },
                failed, barrier)));
  }
}

`;

const replacements = new Map([
  ["third_party/blink/renderer/controller/BUILD.gn", [["test(\"blink_unittests\") {", "# Focused real-renderer regressions; independent of the browser APK and its JNI hook.\ntest(\"landu_touch_regression_tests\") {\n  sources = [\n    \"tests/run_all_tests.cc\",\n    \"tests/landu_touch_adjustment_test.cc\",\n  ]\n  deps = [\n    \":controller\",\n    \"//base\",\n    \"//base:i18n\",\n    \"//base/test:test_support\",\n    \"//content/test:test_support\",\n    \"//gpu:test_support\",\n    \"//testing/gmock\",\n    \"//testing/gtest\",\n    \"//third_party/blink/renderer/core:testing\",\n    \"//third_party/blink/renderer/core:unit_test_support\",\n    \"//third_party/blink/renderer/modules\",\n    \"//third_party/blink/renderer/modules/exported:test_support\",\n    \"//third_party/blink/renderer/platform\",\n    \"//third_party/blink/renderer/platform:test_support\",\n    \"//third_party/blink/renderer/platform/wtf\",\n    \"//third_party/zlib\",\n    \"//url\",\n    \"//v8\",\n  ]\n  configs += [\n    \"//third_party/blink/renderer:config\",\n    \"//third_party/blink/renderer:inside_blink\",\n    \"//third_party/blink/renderer/core:blink_core_pch\",\n  ]\n  data_deps = [\n    \":blink_unittests_data\",\n    \"//content/shell:pak\",\n  ]\n  if (is_android) {\n    deps += [\n      \"//content/public/android:content_java\",\n      \"//content/shell/android:content_shell_assets\",\n      \"//net/android:net_java\",\n    ]\n  }\n}\n\ntest(\"blink_unittests\") {"]]],
  ["components/browser_ui/widget/android/java/src/org/chromium/components/browser_ui/widget/textbubble/TextBubble.java", [["    public void show() {\n        if (mPopupWindow.isShowing()) return;\n\n        if (!mPopupWindow.isShowing() && mAutoDismissTimeoutMs != NO_TIMEOUT) {\n            mHandler.postDelayed(mDismissRunnable, mAutoDismissTimeoutMs);\n        }\n\n        mPopupWindow.show();\n\n        boolean popupShowing = sSkipShowCheckForTesting || mPopupWindow.isShowing();\n        assert popupShowing : \"TextBubble is not presented: \" + mString;\n\n        if (!popupShowing) return;\n\n        sBubbles.add(this);\n        sCountSupplier.set(sBubbles.size());\n        mBubbleShowStartTime = System.currentTimeMillis();\n    }\n", "    public void show() {\n        boolean popupShowing = tryShow();\n        assert popupShowing : \"TextBubble is not presented: \" + mString;\n    }\n\n    /**\n     * Attempts to show an optional bubble. Returns false if its window cannot be presented, e.g.\n     * because the anchor was detached, space is insufficient, or the window token expired.\n     * Callers must release any help-UI reservation when this returns false.\n     */\n    public boolean tryShow() {\n        if (mPopupWindow.isShowing()) return true;\n\n        mPopupWindow.show();\n        boolean popupShowing = sSkipShowCheckForTesting || mPopupWindow.isShowing();\n        if (!popupShowing) {\n            // PopupWindow does not dispatch onDismiss when showAtLocation never succeeded.\n            mPopupWindow.dismiss();\n            return false;\n        }\n\n        if (mAutoDismissTimeoutMs != NO_TIMEOUT) {\n            mHandler.postDelayed(mDismissRunnable, mAutoDismissTimeoutMs);\n        }\n        sBubbles.add(this);\n        sCountSupplier.set(sBubbles.size());\n        mBubbleShowStartTime = System.currentTimeMillis();\n        return true;\n    }\n"]]],
  ["ui/android/java/src/org/chromium/ui/widget/AnchoredPopupWindow.java", [["        mPopupWindow.dismiss();\n    }\n\n    /** Used for testing only. Explicitly trigger dismiss listeners. */", "        if (!mPopupWindow.isShowing()) {\n            // A failed show does not produce PopupWindow.onDismiss. Release the observers even\n            // when the root was detached or showAtLocation rejected the window token.\n            mRectProvider.stopObserving();\n            mViewportRectProvider.stopObserving();\n        }\n        mPopupWindow.dismiss();\n    }\n\n    /** Used for testing only. Explicitly trigger dismiss listeners. */"], ["            // parent.getWindowToken is not valid. See http://crbug.com/826052.\n        }\n", "            // parent.getWindowToken is not valid. See http://crbug.com/826052.\n            // PopupWindow marks itself showing before WindowManager accepts the token. Reset\n            // that state so optional callers can distinguish failure from a visible popup.\n            mPopupWindow.dismiss();\n        }\n"]]],
  ["chrome/browser/user_education/java/src/org/chromium/chrome/browser/user_education/UserEducationHelper.java", [["            mTextBubble.addOnDismissListener(\n                    () -> handleDismissal(/* dismissedProgrammatically= */ false));\n", "            // Register dismissal only after presentation succeeds.\n"], ["            if (iphCommand.dismissOnTouchTimeout != TextBubble.NO_TIMEOUT) {", "            if (!mTextBubble.tryShow()) {\n                // The tracker reservation was acquired, but no help UI was presented. Do not\n                // announce a successful show or leave the tracker blocking future requests.\n                mDismissCallback = null;\n                mTextBubble = null;\n                if (iphCommand.enableSnoozeMode) {\n                    tracker.dismissedWithSnooze(featureName, SnoozeAction.SNOOZED);\n                } else {\n                    tracker.dismissed(featureName);\n                }\n                iphCommand.onBlockedCallback.run();\n                return;\n            }\n            mTextBubble.addOnDismissListener(\n                    () -> handleDismissal(/* dismissedProgrammatically= */ false));\n            if (iphCommand.dismissOnTouchTimeout != TextBubble.NO_TIMEOUT) {"], ["\n            mTextBubble.show();\n", "\n            // Optional bubble was already shown successfully by tryShow above.\n"]]],
  ["chrome/browser/user_education/BUILD.gn", [["  robolectric_library(\"junit\") {", "  # Focused host lifecycle regressions; this does not compile a browser APK.\n  robolectric_binary(\"landu_iph_junit_tests\") {\n    sources = [ \"java/src/org/chromium/chrome/browser/user_education/LanduIphLifecycleTest.java\" ]\n    android_manifest = \"//chrome/android/junit/AndroidManifest.xml\"\n    package_name = \"com.landu.browser\"\n    deps = [\n      \":java\",\n      \":java_resources\",\n      \":junit\",\n      \"//base:base_java_test_support\",\n      \"//chrome/android:chrome_app_java_resources\",\n      \"//chrome/android:chrome_java\",\n      \"//chrome/android:chrome_public_apk_base_module_resources\",\n      \"//chrome/browser/glic/android:java_resources\",\n      \"//chrome/browser/ui/android/web_app_header:java_resources\",\n      \"//components/browser_ui/widget/android:java_resources\",\n      \"//base:base_java\",\n      \"//base:base_junit_test_support\",\n      \"//chrome/browser/feature_engagement:java\",\n      \"//chrome/browser/profiles/android:java\",\n      \"//components/browser_ui/widget/android:java\",\n      \"//components/feature_engagement/public:public_java\",\n      \"//third_party/androidx:androidx_test_ext_junit_java\",\n      \"//ui/android:ui_java_test_support\",\n      \"//ui/android:ui_no_recycler_view_java\",\n    ]\n  }\n\n  robolectric_library(\"junit\") {"]]],
  [proxyLoginBasePath, [
    ['  if (log_manager && log_manager->IsLoggingActive()) {\n    logger =\n',
     '  if (!auth_info_.is_proxy && log_manager && log_manager->IsLoggingActive()) {\n    logger =\n'],
    ['  if (password_manager::HttpAuthManager* httpauth_manager =\n          GetHttpAuthManagerForLogin()) {\n',
     '  // Proxy secrets are owned by the Landu encrypted vault, not password autofill.\n' +
     '  if (password_manager::HttpAuthManager* httpauth_manager =\n' +
     '          auth_info_.is_proxy ? nullptr : GetHttpAuthManagerForLogin()) {\n'],
  ]],
  [proxyLoginPath, [
    ['          authority, explanation, auth_info().challenger.GetURL(),\n          login_model_data);',
     '          authority, explanation, auth_info().challenger.GetURL(),\n' +
     '          auth_info().is_proxy, auth_info().scheme, login_model_data);'],
  ]],
  [proxyAuthHeaderPath, [
    ['                        const GURL& challenger_url,\n',
     '                        const GURL& challenger_url,\n' +
     '                        bool is_proxy,\n                        const std::string& auth_scheme,\n'],
    ['  GURL challenger_url_;\n',
     '  GURL challenger_url_;\n  const bool is_proxy_;\n  const std::string auth_scheme_;\n'],
  ]],
  [proxyAuthNativePath, [
    ['    const GURL& challenger_url,\n',
     '    const GURL& challenger_url,\n    bool is_proxy,\n    const std::string& auth_scheme,\n'],
    ['      challenger_url_(challenger_url),\n',
     '      challenger_url_(challenger_url),\n      is_proxy_(is_proxy),\n      auth_scheme_(auth_scheme),\n'],
    ['                                        challenger_url_);',
     '                                        challenger_url_, is_proxy_, auth_scheme_);'],
  ]],
  [proxyAuthJavaPath, [
    ['            Tab tab, WindowAndroid windowAndroid, @JniType("GURL") GURL challengerUrl) {\n',
     '            Tab tab, WindowAndroid windowAndroid, @JniType("GURL") GURL challengerUrl,\n' +
     '            boolean isProxy, @JniType("std::string") String authenticationScheme) {\n' +
     '        // Only the native proxy challenge may obtain this process-fixed secret.\n' +
     '        int port = challengerUrl.getPort().isEmpty()\n' +
     '                ? ("https".equals(challengerUrl.getScheme()) ? 443 : 80)\n' +
     '                : Integer.parseInt(challengerUrl.getPort());\n' +
     '        com.landu.browser.nativecore.ProxyCredentials credentials =\n' +
     '                com.landu.browser.nativecore.NativeEnvironmentRuntime.proxyCredentials(\n' +
     '                        isProxy, challengerUrl.getScheme(), challengerUrl.getHost(), port,\n' +
     '                        authenticationScheme);\n' +
     '        if (credentials != null) {\n' +
     '            proceed(credentials.username, credentials.password);\n            return;\n        }\n'],
  ]],
  [notificationMetadataPath, [
    ['public class NotificationMetadata {\n', 'public class NotificationMetadata {\n' + notificationTagCode],
  ]],
  ...[notificationProxyPath, asyncNotificationProxyPath].map(path => [path, [
    ['() -> mNotificationManager.cancel(id)',
     '() -> NotificationMetadata.landuCancel(mNotificationManager, null, id)'],
    ['() -> mNotificationManager.cancel(tag, id)',
     '() -> NotificationMetadata.landuCancel(mNotificationManager, tag, id)'],
    ['() -> mNotificationManager.cancelAll()',
     '() -> NotificationMetadata.landuCancelAll(mNotificationManager)'],
    ['notification.getMetadata().tag,', 'NotificationMetadata.landuTag(notification.getMetadata().tag),'],
    ['notification.getMetadata().id,',
     'NotificationMetadata.landuId(notification.getMetadata().tag, notification.getMetadata().id),'],
    ['                    for (var notification : mNotificationManager.getActiveNotifications()) {\n',
     '                    for (var notification : mNotificationManager.getActiveNotifications()) {\n' +
     '                        if (!NotificationMetadata.landuOwnsTag(notification.getTag())) continue;\n'],
    ...(path === notificationProxyPath ? [
      ['() -> mNotificationManager.notify(id, notification)',
       '() -> mNotificationManager.notify(NotificationMetadata.landuId(null, id), notification)'],
      ['() -> mNotificationManager.notify(tag, id, notification)',
       '() -> mNotificationManager.notify(NotificationMetadata.landuTag(tag),\n' +
       '                        NotificationMetadata.landuId(tag, id), notification)'],
      ['return mStatusBarNotification.getTag();',
       'return NotificationMetadata.landuLogicalTag(mStatusBarNotification.getTag());'],
    ] : []),
  ]]),
  [notificationAdaptorPath, [
    ['return mStatusBarNotification.getTag();',
     'return NotificationMetadata.landuLogicalTag(mStatusBarNotification.getTag());'],
  ]],
  [foregroundServicePath, [
    ['        if (notification == null) return;\n',
     '        if (notification == null) return;\n' +
     '        id = org.chromium.base.IntentUtils.LanduEnvironment.notificationId(id);\n'],
  ]],
  [notificationJobPath, [
    ['    public NotificationJobService() {\n', notificationJobOwnershipCode + '    public NotificationJobService() {\n'],
  ]],
  [notificationInterceptorPath, [
    ['import static org.chromium.build.NullUtil.assertNonNull;\n\nimport android.app.Activity;\n',
     'import android.app.Activity;\n'],
    ['        public void onReceive(Context context, Intent intent) {\n' +
     '            processIntent(intent);\n',
     '        public void onReceive(Context context, Intent intent) {\n' +
     '            if (intent == null || !IntentUtils.LanduEnvironment.canDispatchNotification(intent)) return;\n' +
     '            processIntent(intent);\n' +
     '            // Canceled or absent inner PendingIntents are terminal, not retryable.\n' +
     '            if (isOrderedBroadcast()) setResultCode(Activity.RESULT_OK);\n'],
    ['        protected void onHandleIntent(@Nullable Intent intent) {\n' +
     '            processIntent(assertNonNull(intent));\n',
     '        protected void onHandleIntent(@Nullable Intent intent) {\n' +
     '            processIntent(intent);\n'],
    ['    private static boolean processIntent(Intent intent) {\n',
     '    private static boolean processIntent(@Nullable Intent intent) {\n' +
     '        if (intent == null || !IntentUtils.LanduEnvironment.owns(intent)) return false;\n'],
    ['        private long mCreateTime;\n',
     '        private long mCreateTime;\n        private boolean mLanduAcceptedIntent;\n'],
    ['            super.onCreate(savedInstanceState);\n',
     '            super.onCreate(savedInstanceState);\n' +
     '            if (!IntentUtils.LanduEnvironment.owns(getIntent())) {\n' +
     '                finish();\n' +
     '                return;\n' +
     '            }\n' +
     '            mLanduAcceptedIntent = true;\n'],
    ['        protected void onDestroy() {\n',
     '        protected void onDestroy() {\n' +
     '            if (!mLanduAcceptedIntent) {\n' +
     '                super.onDestroy();\n' +
     '                return;\n' +
     '            }\n'],
    ['        return shouldUseBroadcast\n',
     '        // The outer wrapper must keep the same environment as the inner operation.\n' +
     '        intent = IntentUtils.LanduEnvironment.scopePendingIntent(intent);\n' +
     '        flags = IntentUtils.LanduEnvironment.protectPendingIntentFlags(flags);\n' +
     '        if (!shouldUseBroadcast) {\n' +
     '            intent.setClassName(applicationContext, "com.landu.browser.nativecore.LanduNotificationActivity");\n' +
     '        } else {\n' +
     '            intent.setClassName(applicationContext, "com.landu.browser.nativecore.LanduNotificationReceiver");\n' +
     '        }\n' +
     '        return shouldUseBroadcast\n'],
    ['        // If the notification action was a text action, add the reply to the PendingIntent\n',
     '        if (!IntentUtils.LanduEnvironment.owns(intent)) return false;\n\n' +
     '        // If the notification action was a text action, add the reply to the PendingIntent\n'],
  ]],
  [downloadBroadcastImplPath, [
    ['    private final DownloadSharedPreferenceHelper mDownloadSharedPreferenceHelper =\n' +
     '            DownloadSharedPreferenceHelper.getInstance();\n\n' +
     '    private final DownloadNotificationService mDownloadNotificationService;\n',
     `    // SplitCompat constructs this implementation before onStartCommand sees the owner.
    // Do not open the selected environment's preferences or notification service here.
    private boolean mLanduDestroyed;
    private boolean mLanduAcceptedInteraction;

    private DownloadSharedPreferenceHelper landuPreferences() {
        return DownloadSharedPreferenceHelper.getInstance();
    }

    private DownloadNotificationService landuNotifications() {
        return DownloadNotificationService.getInstance();
    }

    private boolean landuCanHandle(@Nullable Intent intent) {
        if (mLanduDestroyed || intent == null
                || !IntentUtils.LanduEnvironment.owns(intent) || !isActionHandled(intent)) return false;
        // The notification body may open Download Home without a specific item.
        if (ACTION_NOTIFICATION_CLICKED.equals(intent.getAction())) return true;
        ContentId id = getContentIdFromIntent(intent);
        return id != null && id.id != null && !id.id.isEmpty()
                && id.namespace != null && !id.namespace.isEmpty();
    }

    @Override
    public void onDestroy() {
        mLanduDestroyed = true;
        mHandler.removeCallbacks(mStopSelfRunnable);
        super.onDestroy();
    }
`],
    ['    public DownloadBroadcastManagerImpl() {\n' +
     '        mDownloadNotificationService = DownloadNotificationService.getInstance();\n' +
     '    }\n', '    public DownloadBroadcastManagerImpl() {}\n'],
    ['        Intent intent = source != null ? new Intent(source) : new Intent();\n',
     '        if (source == null || !isActionHandled(source)) return;\n' +
     '        Intent intent = IntentUtils.LanduEnvironment.scopePendingIntent(source);\n'],
    ['        // Handle the download operation.\n',
     '        if (!landuCanHandle(intent)) {\n' +
     '            // A rejected start must not stop an earlier accepted native operation.\n' +
     '            if (!mLanduAcceptedInteraction) getService().stopSelfResult(startId);\n' +
     '            return Service.START_NOT_STICKY;\n' +
     '        }\n' +
     '        // Handle the download operation.\n'],
    ['    public void onNotificationInteraction(final @Nullable Intent intent) {\n' +
     '        if (intent == null) return;\n' +
     '        if (!isActionHandled(intent)) return;\n',
     '    public void onNotificationInteraction(final @Nullable Intent source) {\n' +
     '        if (source == null || !landuCanHandle(source)) return;\n' +
     '        final Intent intent = new Intent(source);\n' +
     '        mLanduAcceptedInteraction = true;\n'],
    ['    void updateNotification(Intent intent) {\n',
     '    void updateNotification(Intent intent) {\n        if (!landuCanHandle(intent)) return;\n'],
    ['    void loadNativeAndPropagateInteraction(final Intent intent) {\n',
     '    void loadNativeAndPropagateInteraction(final Intent intent) {\n' +
     '        if (!landuCanHandle(intent)) return;\n'],
    ['                    public void finishNativeInitialization() {\n',
     '                    public void finishNativeInitialization() {\n' +
     '                        if (!landuCanHandle(intent)) return;\n'],
    ['    void propagateInteraction(Intent intent) {\n',
     '    void propagateInteraction(Intent intent) {\n        if (!landuCanHandle(intent)) return;\n'],
    ['                        if (result.contentUri == null) {\n',
     '                        if (!landuCanHandle(intent)) return;\n' +
     '                        if (result.contentUri == null) {\n'],
    ['    private void openDownloadWithId(Context context, Intent intent, long id, ContentId contentId) {\n',
     '    private void openDownloadWithId(Context context, Intent intent, long id, ContentId contentId) {\n' +
     '        if (!landuCanHandle(intent)) return;\n'],
    ...['notifyDownloadPaused', 'notifyDownloadCanceled', 'notifyDownloadPending'].map(name =>
      [`mDownloadNotificationService.${name}(`, `landuNotifications().${name}(`]),
    ...['addOrReplaceSharedPreferenceEntry', 'getDownloadSharedPreferenceEntry'].map(name =>
      [`mDownloadSharedPreferenceHelper.${name}(`, `landuPreferences().${name}(`]),
  ]],
  [notificationJobImplPath, [
    ['        PersistableBundle bundle = new PersistableBundle();\n',
     '        PersistableBundle bundle = new PersistableBundle();\n' +
     '        NotificationJobService.putLanduIdentity(intent, bundle);\n'],
    ['        PersistableBundle extras = params.getExtras();\n',
     '        Intent landuIntent = NotificationJobService.landuJobIntent(params);\n' +
     '        if (!org.chromium.base.IntentUtils.LanduEnvironment.owns(landuIntent)) return false;\n' +
     '        PersistableBundle extras = params.getExtras();\n'],
    ['        Intent intent = new Intent(action);\n',
     '        Intent intent = new Intent(action);\n' +
     '        intent.setIdentifier(assumeNonNull(landuIntent).getIdentifier());\n'],
    ['        String action =\n                assumeNonNull(extras.getString(NotificationConstants.EXTRA_NOTIFICATION_ACTION));\n',
     '        String action = extras.getString(NotificationConstants.EXTRA_NOTIFICATION_ACTION);\n' +
     '        if (action == null) return false;\n'],
    ['    public boolean onStopJob(JobParameters params) {\n',
     '    public boolean onStopJob(JobParameters params) {\n' +
     '        if (!org.chromium.base.IntentUtils.LanduEnvironment.owns(\n' +
     '                NotificationJobService.landuJobIntent(params))) return false;\n'],
  ]],
  [notificationServiceImplPath, [
    ['        public void onReceive(Context context, Intent intent) {\n',
     '        public void onReceive(Context context, Intent intent) {\n' +
     '            JobScheduler scheduler = NotificationJobService.landuScheduler(context, intent);\n' +
     '            if (scheduler == null) return;\n' +
     '            if (!org.chromium.base.IntentUtils.LanduEnvironment.owns(intent)) {\n' +
     '                scheduleLanduForeignIntent(context, intent, scheduler);\n' +
     '                return;\n' +
     '            }\n'],
    ['            // milliseconds.\n            JobScheduler scheduler =\n                    (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);\n',
     '            // milliseconds.\n'],
    ['        private static void recordJobIsAlreadyPendingHistogram(\n',
     `        /** Queue under the source environment without touching the selected profile. */
        private static void scheduleLanduForeignIntent(
                Context context, Intent intent, JobScheduler scheduler) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
            if (intent.getAction() == null
                    || intent.getStringExtra(NotificationConstants.EXTRA_NOTIFICATION_ID) == null
                    || intent.getStringExtra(NotificationConstants.EXTRA_NOTIFICATION_INFO_ORIGIN) == null) return;
            PersistableBundle extras = NotificationJobServiceImpl.getJobExtrasFromIntent(intent);
            putJobScheduledTimeInExtras(extras);
            boolean expedited = NotificationConstants.ACTION_PRE_UNSUBSCRIBE.equals(intent.getAction());
            int taskId = expedited ? TaskIds.NOTIFICATION_SERVICE_PRE_UNSUBSCRIBE_JOB_ID
                    : TaskIds.NOTIFICATION_SERVICE_JOB_ID;
            JobInfo.Builder builder = new JobInfo.Builder(
                    taskId, new ComponentName(context, NotificationJobService.class)).setExtras(extras);
            if (expedited) builder.setExpedited(true);
            else builder.setOverrideDeadline(0);
            try {
                if (scheduler.schedule(builder.build()) != JobScheduler.RESULT_SUCCESS) {
                    Log.w(TAG, "Unable to queue notification for its Landu environment");
                }
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "Unable to queue notification for its Landu environment", unavailable);
            }
        }

        private static void recordJobIsAlreadyPendingHistogram(
`],
    ['        if (intent == null) return;\n',
     '        if (intent == null) return;\n' +
     '        if (!org.chromium.base.IntentUtils.LanduEnvironment.owns(intent)) {\n' +
     '            PostTask.runOrPostTask(TaskTraits.UI_DEFAULT,\n' +
     '                    () -> new Receiver().onReceive(ContextUtils.getApplicationContext(), intent));\n' +
     '            return;\n' +
     '        }\n'],
    ['    static void dispatchIntentOnUiThread(Intent intent) {\n',
     '    static void dispatchIntentOnUiThread(Intent intent) {\n' +
     '        if (!org.chromium.base.IntentUtils.LanduEnvironment.owns(intent)) return;\n'],
  ]],
  [intentUtilsPath, [
    ['    private static final String TAG = "IntentUtils";\n',
     '    private static final String TAG = "IntentUtils";\n' + pendingIntentOwnershipCode],
  ]],
  [pendingIntentPath, [
    ['PendingIntent.getBroadcast(context, requestCode, intent, flags),',
     'PendingIntent.getBroadcast(context, requestCode,\n' +
     '                        IntentUtils.LanduEnvironment.scopePendingIntent(intent), flags),'],
    ['PendingIntent.getService(context, requestCode, intent, flags), flags, requestCode);',
     'PendingIntent.getService(context, requestCode,\n' +
     '                        IntentUtils.LanduEnvironment.scopePendingIntent(intent), flags), flags, requestCode);'],
    ['PendingIntent.getActivity(context, requestCode, intent, flags), flags, requestCode);',
     'PendingIntent.getActivity(context, requestCode,\n' +
     '                        IntentUtils.LanduEnvironment.scopePendingIntent(intent), flags), flags, requestCode);'],
    ['        flags |= IntentUtils.getPendingIntentMutabilityFlag(mutable);\n',
     '        flags = IntentUtils.LanduEnvironment.protectPendingIntentFlags(flags);\n' +
     '        flags |= IntentUtils.getPendingIntentMutabilityFlag(mutable);\n'],
  ]],
  [tabExtractorPath, [
    [`        TabState tabState = new TabState();
        tabState.contentsState = getWebContentsState(tab);
        tabState.openerAppId = TabAssociatedApp.getAppId(tab);
        tabState.parentId = tab.getParentId();
        tabState.timestampMillis = tab.getTimestampMillis();
        tabState.tabLaunchTypeAtCreation = tab.getTabLaunchTypeAtCreation();
        // Don't save the actual default theme color because it could change on night mode state
        // changed.
        tabState.themeColor = getThemeColorForSerialization(tab);
        tabState.rootId = tab.getRootId();
        tabState.userAgent = tab.getUserAgent();
        tabState.lastNavigationCommittedTimestampMillis =
                tab.getLastNavigationCommittedTimestampMillis();
        tabState.tabGroupId = tab.getTabGroupId();
        tabState.tabHasSensitiveContent = tab.getTabHasSensitiveContent();
        tabState.isPinned = tab.getIsPinned();
        tabState.url = tab.getUrl();
        return tabState;
`, `        TabState tabState = new TabState();
        boolean handedOff = false;
        try {
            tabState.contentsState = getWebContentsState(tab);
            tabState.openerAppId = TabAssociatedApp.getAppId(tab);
            tabState.parentId = tab.getParentId();
            tabState.timestampMillis = tab.getTimestampMillis();
            tabState.tabLaunchTypeAtCreation = tab.getTabLaunchTypeAtCreation();
            // Don't save the actual default theme color because it could change on night mode state
            // changed.
            tabState.themeColor = getThemeColorForSerialization(tab);
            tabState.rootId = tab.getRootId();
            tabState.userAgent = tab.getUserAgent();
            tabState.lastNavigationCommittedTimestampMillis =
                    tab.getLastNavigationCommittedTimestampMillis();
            tabState.tabGroupId = tab.getTabGroupId();
            tabState.tabHasSensitiveContent = tab.getTabHasSensitiveContent();
            tabState.isPinned = tab.getIsPinned();
            tabState.url = tab.getUrl();
            handedOff = true;
            return tabState;
        } finally {
            // Release an owned native buffer if a later metadata getter throws.
            if (!handedOff && tabState.contentsState != null) tabState.contentsState.destroy();
        }
`],
  ]],
  [tabAttributesPath, [
    ['    private @DirtinessState int mDirtinessState = DirtinessState.CLEAN;\n',
     '    private @DirtinessState int mDirtinessState = DirtinessState.CLEAN;\n' +
     '    private long mLanduSaveRevision;\n'],
    ['    public @DirtinessState int getDirtinessState() {\n', `    /** Changes include repeated DIRTY/UNTIDY notifications, not just state transitions. */
    public long getLanduSaveRevision() { return mLanduSaveRevision; }

    public boolean isReadyForLanduSave() {
        return !mTab.isDestroyed() && !mTab.isBeingRestored() && mNumberOpenBatchEdits == 0;
    }

    public @DirtinessState int getDirtinessState() {
`],
    ['            @DirtinessState int dirtiness, Predicate<Tab> shouldSetToClean) {\n',
     '            @DirtinessState int dirtiness, Predicate<Tab> shouldSetToClean) {\n' +
     '        if (dirtiness != DirtinessState.CLEAN) mLanduSaveRevision++;\n'],
  ]],
  [legacyStorePath, [
    ['    private boolean mDestroyed;\n', `    private boolean mDestroyed;
    private boolean mLanduSavePaused;
    private long mLanduModelRevision;
    private static final com.landu.browser.nativecore.EnvironmentLegacySaveBarrier
            sLanduSaveBarrier = new com.landu.browser.nativecore.EnvironmentLegacySaveBarrier();
`],
    ['        mSequencedTaskRunner = PostTask.createSequencedTaskRunner(taskTraits);\n',
     `        mSequencedTaskRunner = PostTask.createSequencedTaskRunner(taskTraits);
        sLanduSaveBarrier.register(this,
                new com.landu.browser.nativecore.EnvironmentLegacySaveBarrier.Store() {
                    @Override public void pause() { pauseForLanduClose(); }
                    @Override public void drain(Runnable ready) {
                        mSequencedTaskRunner.execute(
                                () -> PostTask.postTask(TaskTraits.UI_DEFAULT, ready));
                    }
                    @Override public com.landu.browser.nativecore.EnvironmentLegacySaveBarrier.Snapshot capture() {
                        return captureSnapshotForLanduClose();
                    }
                    @Override public void resume(boolean saved) {
                        mLanduSavePaused = false;
                        if (saved) {
                            mTabsToSave.clear();
                            mTabsToMigrate.clear();
                        } else if (!mDestroyed) {
                            saveNextTab();
                        }
                    }
                });
`],
    ['    public void saveState() {\n',
     '    public void saveState() {\n        if (mLanduSavePaused) { mLanduModelRevision++; return; }\n'],
    ['    public void destroy() {\n', '    public void destroy() {\n        sLanduSaveBarrier.unregister(this);\n'],
    ['    void saveNextTab() {\n', '    void saveNextTab() {\n        if (mLanduSavePaused) return;\n'],
    ['    private void migrateNextTabIfApplicable(int numMigration) {\n',
     '    private void migrateNextTabIfApplicable(int numMigration) {\n        if (mLanduSavePaused) return;\n'],
    ['    private void saveTabListAsynchronously() {\n',
     '    private void saveTabListAsynchronously() {\n        mLanduModelRevision++;\n        if (mLanduSavePaused) return;\n'],
    ['    /* package */ void addTabToSaveQueue(@Nullable Tab tab) {\n',
     '    /* package */ void addTabToSaveQueue(@Nullable Tab tab) {\n        mLanduModelRevision++;\n'],
    ['    void removeTabFromQueues(Tab tab) {\n',
     '    void removeTabFromQueues(Tab tab) {\n        mLanduModelRevision++;\n'],
    ['    private void saveListToFile(TabModelSelectorMetadata listData) {\n', `    @Override
    public void saveStateBeforeLanduExit(Runnable onDone, Runnable onFailure) {
        ThreadUtils.assertOnUiThread();
        sLanduSaveBarrier.saveAll(onDone, onFailure);
    }

    private void pauseForLanduClose() {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed || !mTabModelSelector.isTabStateInitialized()
                || mLoadInProgress || !mTabsToRestore.isEmpty()) {
            throw new IllegalStateException("Tab restoration is not complete");
        }
        mLanduSavePaused = true;
        // No interrupts and no Future.get() on the UI thread. The sequenced
        // marker waits for running I/O; cancelled queued tasks cannot start it.
        if (mSaveTabTask != null) {
            mTabsToSave.addFirst(mSaveTabTask.mTab);
            mSaveTabTask.cancel(false);
            mSaveTabTask = null;
        }
        if (mMigrateTabTask != null) {
            mTabsToMigrate.addFirst(mMigrateTabTask.mTab);
            mMigrateTabTask.cancel(false);
            mMigrateTabTask = null;
        }
        if (mSaveListTask != null) {
            mSaveListTask.cancel(false);
            mSaveListTask = null;
        }
    }

    private com.landu.browser.nativecore.EnvironmentLegacySaveBarrier.Snapshot captureSnapshotForLanduClose() {
        ThreadUtils.assertOnUiThread();
        if (mDestroyed || !mLanduSavePaused || !mTabModelSelector.isTabStateInitialized()
                || mLoadInProgress || !mTabsToRestore.isEmpty()) {
            throw new IllegalStateException("Tab restoration is not complete");
        }
        List<Tab> tabs = new ArrayList<>();
        List<TabState> states = new ArrayList<>();
        List<Boolean> encrypted = new ArrayList<>();
        List<TabStateAttributes> attributes = new ArrayList<>();
        List<Long> revisions = new ArrayList<>();
        long modelRevision = mLanduModelRevision;
        boolean handedOff = false;
        try {
            for (boolean incognito : new boolean[] {false, true}) {
                for (Tab tab : mTabModelSelector.getModel(incognito)) {
                    if (tab.isClosing()) continue;
                    TabStateAttributes attr = TabStateAttributesRegistry.getAttributesFor(
                            tab, TabPersistentStoreImpl.class);
                    if (attr == null || !attr.isReadyForLanduSave()) {
                        throw new IllegalStateException("Tab is not ready for a snapshot");
                    }
                    long revision = attr.getLanduSaveRevision();
                    TabState state = TabStateExtractor.from(tab);
                    if (state == null) throw new IllegalStateException("Missing tab snapshot");
                    try { states.add(state); }
                    catch (RuntimeException | OutOfMemoryError error) {
                        if (state.contentsState != null) state.contentsState.destroy();
                        throw error;
                    }
                    if (state.contentsState == null) throw new IllegalStateException("Missing navigation state");
                    tabs.add(tab);
                    encrypted.add(incognito);
                    attributes.add(attr);
                    revisions.add(revision);
                }
            }
            TabModelSelectorMetadata metadata = extractTabMetadata();
            LanduCloseSnapshot snapshot = new LanduCloseSnapshot(
                    tabs, states, encrypted, attributes, revisions, metadata, modelRevision);
            handedOff = true;
            return snapshot;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot capture tab index", error);
        } finally {
            if (!handedOff) releaseLanduStates(states);
        }
    }

    private static void releaseLanduStates(List<TabState> states) {
        ThreadUtils.assertOnUiThread();
        boolean released = true;
        for (TabState state : states) {
            try {
                if (state.contentsState != null) state.contentsState.destroy();
            } catch (RuntimeException | OutOfMemoryError error) { released = false; }
        }
        states.clear();
        if (!released) throw new IllegalStateException("Cannot release tab snapshot");
    }

    private final class LanduCloseSnapshot
            implements com.landu.browser.nativecore.EnvironmentLegacySaveBarrier.Snapshot {
        private final List<Tab> mTabs;
        private final List<TabState> mStates;
        private final List<Boolean> mEncrypted;
        private final List<TabStateAttributes> mAttributes;
        private final List<Long> mRevisions;
        private final TabModelSelectorMetadata mMetadata;
        private final long mModelRevision;

        LanduCloseSnapshot(List<Tab> tabs, List<TabState> states, List<Boolean> encrypted,
                List<TabStateAttributes> attributes, List<Long> revisions,
                TabModelSelectorMetadata metadata, long modelRevision) {
            mTabs = tabs;
            mStates = states;
            mEncrypted = encrypted;
            mAttributes = attributes;
            mRevisions = revisions;
            mMetadata = metadata;
            mModelRevision = modelRevision;
        }

        @Override
        public void write(com.landu.browser.nativecore.EnvironmentLegacySaveBarrier.Result result) {
            ThreadUtils.assertOnUiThread();
            // Capture IDs before entering the background: no Tab/selector APIs there.
            List<Integer> ids = new ArrayList<>();
            for (Tab tab : mTabs) ids.add(tab.getId());
            mSequencedTaskRunner.execute(() -> {
                ThreadUtils.assertOnBackgroundThread();
                boolean saved = false;
                try {
                    File directory = getStateDirectory();
                    boolean tabsSaved = true;
                    for (int i = 0; i < mStates.size(); i++) {
                        if (!TabStateFileManager.saveStateBeforeLanduExit(
                                directory, mStates.get(i), ids.get(i), mEncrypted.get(i), mCipherFactory)) {
                            tabsSaved = false;
                            break;
                        }
                    }
                    if (tabsSaved) {
                        File file = new File(directory, mPersistencePolicy.getMetadataFileName());
                        saved = TabMetadataFileManager.saveListBeforeLanduExit(file, mMetadata);
                    }
                } catch (RuntimeException | OutOfMemoryError error) {
                    Log.e(TAG, "Landu close write failed; keeping the environment open", error);
                }
                final boolean confirmed = saved;
                PostTask.postTask(TaskTraits.UI_DEFAULT, () -> result.complete(confirmed));
            });
        }

        @Override
        public boolean unchanged() {
            ThreadUtils.assertOnUiThread();
            if (mDestroyed || !mLanduSavePaused || mModelRevision != mLanduModelRevision
                    || !mTabModelSelector.isTabStateInitialized() || mLoadInProgress
                    || !mTabsToRestore.isEmpty()) return false;
            for (int i = 0; i < mTabs.size(); i++) {
                Tab tab = mTabs.get(i);
                TabStateAttributes attr = mAttributes.get(i);
                if (tab.isDestroyed() || tab.isClosing() || !attr.isReadyForLanduSave()
                        || attr.getLanduSaveRevision() != mRevisions.get(i)
                        || TabStateAttributesRegistry.getAttributesFor(tab, TabPersistentStoreImpl.class) != attr
                        || mTabModelSelector.getModel(mEncrypted.get(i)).getTabById(tab.getId()) != tab) {
                    return false;
                }
            }
            try { return Objects.equals(mMetadata, extractTabMetadata()); }
            catch (IOException error) { return false; }
        }

        @Override
        public void release(boolean saved) {
            releaseLanduStates(mStates);
            if (saved) {
                if (!unchanged()) throw new IllegalStateException("Tabs changed while saving");
                for (TabStateAttributes attr : mAttributes) attr.clearTabStateDirtiness();
                mLastSavedMetadata = mMetadata;
            }
        }
    }

    private void saveListToFile(TabModelSelectorMetadata listData) {
`],
  ]],
  [tabStateFilePath, [
    ['    private static void saveStateFlatBuffer(\n', `    /** Strict explicit-close path. The owner must first drain ordinary save tasks. */
    public static boolean saveStateBeforeLanduExit(
            File directory, @Nullable TabState state, int tabId, boolean encrypted,
            @Nullable CipherFactory cipherFactory) {
        if (state == null || state.contentsState == null || (encrypted && cipherFactory == null)) {
            return false;
        }
        try {
            Cipher cipher = encrypted ? assumeNonNull(cipherFactory).getCipher(Cipher.ENCRYPT_MODE) : null;
            if (encrypted && cipher == null) return false;
            ByteBuffer data = new FlatBufferTabStateSerializer(encrypted).serialize(state);
            if (!data.hasRemaining()) return false;
            File file = getTabStateFile(directory, tabId, encrypted, /* isFlatbuffer= */ true);
            return LanduAtomicFileWriter.write(file, output -> {
                if (encrypted) {
                    try (DataOutputStream stream =
                            new DataOutputStream(new CipherOutputStream(output, assumeNonNull(cipher)))) {
                        stream.writeLong(KEY_CHECKER);
                        stream.writeInt(data.remaining());
                        WritableByteChannel channel = Channels.newChannel(stream);
                        while (data.hasRemaining()) channel.write(data);
                    }
                } else {
                    WritableByteChannel channel = Channels.newChannel(output);
                    while (data.hasRemaining()) channel.write(data);
                }
            });
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.e(TAG, "Landu tab write failed; retaining previous files", error);
            return false;
        }
    }

    private static void saveStateFlatBuffer(
`],
  ]],
  [tabMetadataFilePath, [
    ['    public static void saveListToFile(File metadataFile, TabModelSelectorMetadata metadata) {\n', `    /** Commit the index only after every referenced tab snapshot was saved. */
    public static boolean saveListBeforeLanduExit(File file, TabModelSelectorMetadata metadata) {
        synchronized (SAVE_LIST_LOCK) {
            return LanduAtomicFileWriter.write(file, output -> {
                DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(output));
                int normal = metadata.normalModelMetadata.ids.size();
                int incognito = metadata.incognitoModelMetadata.ids.size();
                stream.writeInt(SAVED_STATE_VERSION);
                stream.writeInt(normal + incognito);
                stream.writeInt(incognito);
                stream.writeInt(metadata.incognitoModelMetadata.index);
                stream.writeInt(metadata.normalModelMetadata.index + incognito);
                for (int i = 0; i < incognito; i++) {
                    stream.writeInt(metadata.incognitoModelMetadata.ids.get(i));
                    stream.writeUTF(metadata.incognitoModelMetadata.urls.get(i));
                }
                for (int i = 0; i < normal; i++) {
                    stream.writeInt(metadata.normalModelMetadata.ids.get(i));
                    stream.writeUTF(metadata.normalModelMetadata.urls.get(i));
                }
                stream.flush();
            });
        }
    }

    public static void saveListToFile(File metadataFile, TabModelSelectorMetadata metadata) {
`],
  ]],
  [tabPersistenceBuildPath, [
    ['    "android/java/src/org/chromium/chrome/browser/tabpersistence/FlatBufferTabStateSerializer.java",\n',
     '    "android/java/src/org/chromium/chrome/browser/tabpersistence/FlatBufferTabStateSerializer.java",\n' +
     '    "android/java/src/org/chromium/chrome/browser/tabpersistence/LanduAtomicFileWriter.java",\n'],
  ]],
  [tabStoreInterfacePath, [
    ['    void saveState();\n',
     '    void saveState();\n\n' +
     '    /** Explicit close requires a confirmed save; unsupported stores must fail closed. */\n' +
     '    default void saveStateBeforeLanduExit(Runnable onDone, Runnable onFailure) {\n' +
     '        onFailure.run();\n    }\n'],
  ]],
  [tabStorePath, [
    ['    @Override\n    public void loadState(boolean ignoreIncognitoFiles, boolean ignoreRegularFiles) {\n',
     '    @Override\n    public void saveStateBeforeLanduExit(Runnable onDone, Runnable onFailure) {\n' +
     '        org.chromium.base.ThreadUtils.assertOnUiThread();\n' +
     '        var manager = mModelTrackingManager;\n' +
     '        var storage = mTabStateStorageService;\n' +
     '        if (mIsDestroyed || manager == null || storage == null) {\n' +
     '            onFailure.run();\n            return;\n        }\n' +
     '        saveState();\n' +
     '        if (mIsDestroyed || mModelTrackingManager != manager || mTabStateStorageService != storage) {\n' +
     '            onFailure.run();\n            return;\n        }\n' +
     '        manager.saveAllBeforeLanduExit(() -> {\n' +
     '            if (mIsDestroyed || mModelTrackingManager != manager || mTabStateStorageService != storage)\n' +
     '                onFailure.run();\n            else onDone.run();\n' +
     '        }, onFailure);\n    }\n\n' +
     '    @Override\n    public void loadState(boolean ignoreIncognitoFiles, boolean ignoreRegularFiles) {\n'],
  ]],
  [modelTrackingPath, [
    ['    public boolean isSynchronizerPresent(boolean incognito) {\n',
     '    /** Rewrite the current tree, and require a successful transaction for every persistent model. */\n' +
     '    public void saveAllBeforeLanduExit(Runnable onDone, Runnable onFailure) {\n' +
     '        org.chromium.base.ThreadUtils.assertOnUiThread();\n' +
     '        var regular = mRegularSynchronizer;\n' +
     '        var incognito = mIncognitoSynchronizer;\n' +
     '        if (regular == null || (!mIsRestoreFinished\n' +
     '                && (!mRegularModelCaughtUp || (incognito != null && !mIncognitoModelCaughtUp)))) {\n' +
     '            onFailure.run();\n            return;\n        }\n' +
     '        var saves = new com.landu.browser.nativecore.EnvironmentSaveBarrier();\n' +
     '        saves.register(regular, result -> regular.fullSaveForLandu(\n' +
     '                () -> result.complete(null), () -> result.complete("标签事务提交失败")));\n' +
     '        if (incognito != null) saves.register(incognito, result -> incognito.fullSaveForLandu(\n' +
     '                () -> result.complete(null), () -> result.complete("标签事务提交失败")));\n' +
     '        saves.saveAll(error -> {\n' +
     '            if (error != null || mRegularSynchronizer != regular || mIncognitoSynchronizer != incognito)\n' +
     '                onFailure.run();\n            else onDone.run();\n        });\n' +
     '    }\n\n    public boolean isSynchronizerPresent(boolean incognito) {\n'],
  ]],
  [collectionJavaPath, [
    ['    @NativeMethods\n    interface Natives {\n',
     '    /** Full snapshot; success is delivered only after its transaction commits. */\n' +
     '    public void fullSaveForLandu(Runnable onDone, Runnable onFailure) {\n' +
     '        org.chromium.base.ThreadUtils.assertOnUiThread();\n' +
     '        if (mNativePtr == 0) {\n            onFailure.run();\n            return;\n        }\n' +
     '        StorageCollectionSynchronizerJni.get().fullSaveForLandu(mNativePtr, onDone, onFailure);\n' +
     '    }\n\n    @NativeMethods\n    interface Natives {\n' +
     '        void fullSaveForLandu(long nativeStorageCollectionSynchronizerAndroid,\n' +
     '                @JniType("base::OnceClosure") Runnable onDone,\n' +
     '                @JniType("base::OnceClosure") Runnable onFailure);\n\n'],
  ]],
  [collectionHeaderPath, [
    ['  void FullSave(JNIEnv* env, base::OnceClosure callback);\n',
     '  void FullSave(JNIEnv* env, base::OnceClosure callback);\n' +
     '  void FullSaveForLandu(JNIEnv* env, base::OnceClosure on_done,\n' +
     '                        base::OnceClosure on_failure);\n'],
  ]],
  [collectionNativePath, [
    ['#include "base/memory/ptr_util.h"\n',
     '#include "base/memory/ptr_util.h"\n#include "base/task/bind_post_task.h"\n' +
     '#include "mojo/public/cpp/bindings/callback_helpers.h"\n'],
    ['void StorageCollectionSynchronizerAndroid::CancelRestore(JNIEnv* env) {\n',
     'void StorageCollectionSynchronizerAndroid::FullSaveForLandu(\n' +
     '    JNIEnv* env, base::OnceClosure on_done, base::OnceClosure on_failure) {\n' +
     '  // The transaction callback runs on the DB sequence, or is destroyed\n' +
     '  // there on failure. Dispatch either result back to the calling UI sequence.\n' +
     '  synchronizer_.FullSave(mojo::WrapCallbackWithDropHandler(\n' +
     '      base::BindPostTaskToCurrentDefault(std::move(on_done)),\n' +
     '      base::BindPostTaskToCurrentDefault(std::move(on_failure))));\n' +
     '}\n\nvoid StorageCollectionSynchronizerAndroid::CancelRestore(JNIEnv* env) {\n'],
  ]],
  [tabStorageJavaPath, [
    ['    @NativeMethods\n    interface Natives {\n',
     '    /** Waits for tab database operations already queued by a Landu save. */\n' +
     '    public void waitForLanduSave(Runnable onDone, Runnable onFailure) {\n' +
     '        TabStateStorageServiceJni.get().waitForLanduSave(\n' +
     '                mNativeTabStateStorageService, onDone, onFailure);\n    }\n\n' +
     '    @NativeMethods\n    interface Natives {\n' +
     '        void waitForLanduSave(long nativeTabStateStorageServiceAndroid,\n' +
     '                @JniType("base::OnceClosure") Runnable onDone,\n' +
     '                @JniType("base::OnceClosure") Runnable onFailure);\n\n'],
  ]],
  [tabStorageHeaderPath, [
    ['  void BoostPriority(JNIEnv* env);\n',
     '  void BoostPriority(JNIEnv* env);\n\n' +
     '  void WaitForLanduSave(JNIEnv* env, base::OnceClosure on_done,\n' +
     '                       base::OnceClosure on_failure);\n'],
  ]],
  [tabStorageNativePath, [
    ['#include "base/logging.h"\n',
     '#include "base/logging.h"\n#include "mojo/public/cpp/bindings/callback_helpers.h"\n'],
    ['void TabStateStorageServiceAndroid::BoostPriority(JNIEnv* env) {\n',
     'void TabStateStorageServiceAndroid::WaitForLanduSave(\n' +
     '    JNIEnv* env, base::OnceClosure on_done, base::OnceClosure on_failure) {\n' +
     '  tab_state_storage_service_->BoostPriority();\n' +
     '  tab_state_storage_service_->WaitForAllPendingOperations(\n' +
     '      mojo::WrapCallbackWithDropHandler(std::move(on_done), std::move(on_failure)));\n' +
     '}\n\nvoid TabStateStorageServiceAndroid::BoostPriority(JNIEnv* env) {\n'],
  ]],
  [tabOrchestratorPath, [
    ['    public void saveState() {\n',
     '    /** Save current tabs, then wait for the new tab database if in use. */\n' +
     '    public void saveStateBeforeLanduExit(Runnable onDone, Runnable onFailure) {\n' +
     '        if (mIsDestroyed || !mStoresInitialized || mTabPersistentStoreDestroyedEarly\n' +
     '                || mTabPersistentStore == null || mTabModelSelector == null\n' +
     '                || !mTabModelSelector.isTabStateInitialized()) {\n' +
     '            onFailure.run();\n            return;\n        }\n' +
     '        var selector = mTabModelSelector;\n' +
     '        selector.commitAllTabClosures();\n' +
     '        boolean database = mTabPersistentStore.getStoreType() == StoreType.TAB_STATE_STORE\n' +
     '                || (mShadowTabPersistentStore != null\n' +
     '                    && mShadowTabPersistentStore.getStoreType() == StoreType.TAB_STATE_STORE);\n' +
     '        var saves = new com.landu.browser.nativecore.EnvironmentSaveBarrier();\n' +
     '        var primary = mTabPersistentStore;\n' +
     '        var shadow = mShadowTabPersistentStore;\n' +
     '        var profile = selector.getModel(false).getProfile();\n' +
     '        var service = database && profile != null\n' +
     '                ? org.chromium.chrome.browser.tab.TabStateStorageServiceFactory.getForProfile(profile) : null;\n' +
     '        if (database && service == null) {\n            onFailure.run();\n            return;\n        }\n' +
     '        saves.register(primary, result -> primary.saveStateBeforeLanduExit(\n' +
     '                () -> result.complete(null), () -> result.complete("标签存储保存失败")));\n' +
     '        if (shadow != null) saves.register(shadow, result -> shadow.saveStateBeforeLanduExit(\n' +
     '                () -> result.complete(null), () -> result.complete("标签存储保存失败")));\n' +
     '        saves.saveAll(error -> {\n' +
     '            if (error != null || mIsDestroyed || mTabPersistentStoreDestroyedEarly\n' +
     '                    || mTabModelSelector != selector || mTabPersistentStore != primary\n' +
     '                    || mShadowTabPersistentStore != shadow) {\n' +
     '                onFailure.run();\n                return;\n            }\n' +
     '            if (service == null) {\n                onDone.run();\n                return;\n            }\n' +
     '            try { service.waitForLanduSave(onDone, onFailure); }\n' +
     '            catch (RuntimeException failure) { onFailure.run(); }\n' +
     '        });\n    }\n\n' +
     '    public void saveState() {\n'],
  ]],
  [tabbedPath, [
    ['    protected void onPreCreate() {\n',
     '    protected void onPreCreate() {\n' +
     `        ${runtime}.registerWindowSave(this, result -> {\n` +
     '            if (mTabModelOrchestrator == null) {\n' +
     '                result.complete("标签页尚未准备好，环境仍保持打开");\n                return;\n            }\n' +
     '            mTabModelOrchestrator.saveStateBeforeLanduExit(\n' +
     '                    () -> result.complete(null),\n' +
     '                    () -> result.complete("标签页保存未完成，环境仍保持打开"));\n' +
     '        });\n'],
    ['    public void onDestroyInternal() {\n',
     `    public void onDestroyInternal() {\n        ${runtime}.unregisterWindowSave(this);\n`],
  ]],
  [webGlHeaderPath, [
    ['  void RecordANGLEImplementation();\n',
     '  bool ReadPixelsWithLanduNoise(GLint x, GLint y, GLsizei width, GLsizei height,\n' +
     '      GLenum format, GLenum type, void* array_destination,\n' +
     '      GLuint pack_buffer, GLintptr buffer_offset);\n\n' +
     '  void RecordANGLEImplementation();\n'],
  ]],
  [webGlBuildPath, [
    ['    "webgl_rendering_context_base.cc",\n',
     '    "webgl_rendering_context_base.cc",\n    "landu_webgl_readback.inc",\n'],
  ]],
  [webGl2Path, [
    ['    ContextGL()->ReadPixels(x, y, width, height, format, type,\n' +
     '                            reinterpret_cast<void*>(offset));\n',
     '    if (!ReadPixelsWithLanduNoise(x, y, width, height, format, type,\n' +
     '                                  nullptr, buffer->Object(), offset)) {\n' +
     '      ContextGL()->ReadPixels(x, y, width, height, format, type,\n' +
     '                              reinterpret_cast<void*>(offset));\n    }\n'],
  ]],
  [webGlPath, [
    ['#include "base/feature_list.h"\n', '#include "base/feature_list.h"\n' +
     '#include "third_party/blink/renderer/platform/graphics/landu_gpu_metadata.h"\n'],
    ['#include "base/bit_cast.h"\n', '#include "base/bit_cast.h"\n' +
     '#include "base/functional/bind.h"\n#include "base/functional/callback_helpers.h"\n' +
     '#include "base/process/memory.h"\n' +
     '#include "third_party/blink/public/common/landu/landu_webgl_noise.h"\n' +
     '#include "third_party/blink/renderer/platform/graphics/landu_canvas_readback.h"\n'],
    ['void WebGLRenderingContextBase::ReadPixelsHelper(GLint x,\n',
     '#include "third_party/blink/renderer/modules/webgl/landu_webgl_readback.inc"\n\n' +
     'void WebGLRenderingContextBase::ReadPixelsHelper(GLint x,\n'],
    ['    ContextGL()->ReadPixels(x, y, width, height, format, type, data);\n',
     '    if (!ReadPixelsWithLanduNoise(x, y, width, height, format, type, data, 0, 0)) {\n' +
     '      ContextGL()->ReadPixels(x, y, width, height, format, type, data);\n    }\n'],
    ['    case WebGLDebugRendererInfo::kUnmaskedRendererWebgl:\n' +
     '      if (ExtensionEnabled(kWebGLDebugRendererInfoName)) {\n',
     '    case WebGLDebugRendererInfo::kUnmaskedRendererWebgl:\n' +
     '      if (ExtensionEnabled(kWebGLDebugRendererInfoName)) {\n' +
     '        const auto& metadata = GetLanduGpuMetadata();\n' +
     '        if (metadata.enabled)\n' +
     '          return WebGLAny(script_state, String::FromUtf8(metadata.fields[1]));\n'],
    ['    case WebGLDebugRendererInfo::kUnmaskedVendorWebgl:\n' +
     '      if (ExtensionEnabled(kWebGLDebugRendererInfoName)) {\n',
     '    case WebGLDebugRendererInfo::kUnmaskedVendorWebgl:\n' +
     '      if (ExtensionEnabled(kWebGLDebugRendererInfoName)) {\n' +
     '        const auto& metadata = GetLanduGpuMetadata();\n' +
     '        if (metadata.enabled)\n' +
     '          return WebGLAny(script_state, String::FromUtf8(metadata.fields[0]));\n'],
  ]],
  [webGpuPath, [
    ['#include "services/metrics/public/cpp/ukm_builders.h"\n',
     '#include "services/metrics/public/cpp/ukm_builders.h"\n' +
     '#include "third_party/blink/renderer/platform/graphics/landu_gpu_metadata.h"\n'],
    ['  GPUAdapterInfo* info;\n',
     '  const auto& metadata = GetLanduGpuMetadata();\n' +
     '  if (metadata.enabled) {\n' +
     '    // Keep real functionality and limits. Do not expose real driver strings\n' +
     '    // or experimental adapter details alongside the selected public identity.\n' +
     '    return MakeGarbageCollected<GPUAdapterInfo>(\n' +
     '        String::FromUtf8(metadata.fields[2]), String::FromUtf8(metadata.fields[3]),\n' +
     '        subgroup_min_size_, subgroup_max_size_, is_fallback_adapter,\n' +
     '        String::FromUtf8(metadata.fields[4]), String::FromUtf8(metadata.fields[5]));\n' +
     '  }\n\n  GPUAdapterInfo* info;\n'],
  ]],
  [apkTemplatePath, [
    ['  # *_bundle variants). This defaults to the Chromium browser package name.\n' +
     '  chrome_public_manifest_package = "org.chromium.chrome"\n',
     '  # *_bundle variants). Landu uses a separate package from Chromium and the WebView prototype.\n' +
     '  chrome_public_manifest_package = "com.landu.browser"\n'],
    ['"//chrome/android:chrome_public_apk_base_module_resources"', '"//chrome/android:landu_brand_resources"'],
  ]],
  [userAgentBuildPath, [
    ['    "user_agent_utils.h",\n', '    "user_agent_utils.h",\n    "landu_android_identity.h",\n'],
  ]],
  [userAgentPath, [
    ['#include "base/command_line.h"\n', '#include "base/command_line.h"\n#include "base/check.h"\n'],
    ['#include "components/embedder_support/pref_names.h"\n',
     '#include "components/embedder_support/pref_names.h"\n#include "components/embedder_support/landu_android_identity.h"\n'],
    ['namespace embedder_support {\n\nnamespace {\n',
     'namespace embedder_support {\n\nnamespace {\n\n' +
     '#if BUILDFLAG(IS_ANDROID)\n' +
     'const landu::AndroidIdentity& GetLanduAndroidIdentity() {\n' +
     '  static const base::NoDestructor<landu::AndroidIdentity> identity([] {\n' +
     '    const auto* command = base::CommandLine::ForCurrentProcess();\n' +
     '    auto parsed = landu::ParseAndroidIdentity(\n' +
     '        command->HasSwitch("landu-android-version"),\n' +
     '        command->HasSwitch("landu-android-model"),\n' +
     '        command->GetSwitchValueASCII("landu-android-version"),\n' +
     '        command->GetSwitchValueASCII("landu-android-model"));\n' +
     '    CHECK(parsed.has_value());\n' +
     '    return *parsed;\n' +
     '  }());\n' +
     '  return *identity;\n' +
     '}\n#endif\n'],
    ['  std::string android_version_str = base::SysInfo::OperatingSystemVersion();\n',
     '  const auto& landu_identity = GetLanduAndroidIdentity();\n' +
     '  std::string android_version_str = landu_identity.enabled\n' +
     '      ? landu_identity.ua_version : base::SysInfo::OperatingSystemVersion();\n'],
    ['    return std::string();\n  }\n#endif\n\n#if BUILDFLAG(IS_WIN)\n  return GetWindowsPlatformVersion();\n',
     '    return std::string();\n  }\n' +
     '  const auto& landu_identity = GetLanduAndroidIdentity();\n' +
     '  if (landu_identity.enabled) return landu_identity.platform_version;\n' +
     '#endif\n\n#if BUILDFLAG(IS_WIN)\n  return GetWindowsPlatformVersion();\n'],
    ['  // Only send the model information if on the release build of Android,\n',
     '  const auto& landu_identity = GetLanduAndroidIdentity();\n' +
     '  if (landu_identity.enabled) return landu_identity.model;\n\n' +
     '  // Only send the model information if on the release build of Android,\n'],
    ['  if (include_android_build_number == IncludeAndroidBuildNumber::Include) {\n',
     '  if (include_android_build_number == IncludeAndroidBuildNumber::Include &&\n' +
     '      !GetLanduAndroidIdentity().enabled) {\n'],
  ]],
  [preferencePath, [
    ['    ChromeCommandLinePrefStore::boolean_switch_map_[] = {\n',
     '    ChromeCommandLinePrefStore::boolean_switch_map_[] = {\n' +
     '        {"landu-dnt-on", prefs::kEnableDoNotTrack, true},\n' +
     '        {"landu-dnt-off", prefs::kEnableDoNotTrack, false},\n'],
  ]],
  [canvas2dPath, [
    ['#include "third_party/blink/renderer/platform/graphics/image_data_buffer.h"\n',
     '#include "third_party/blink/renderer/platform/graphics/image_data_buffer.h"\n' + canvasInclude],
    ['    if (!read_pixels_successful) {\n      SkIRect bounds =\n',
     '    if (read_pixels_successful) {\n' +
     '      CHECK(NoiseLanduCanvasReadback(image_data_pixmap, sx, sy));\n' +
     '    }\n    if (!read_pixels_successful) {\n      SkIRect bounds =\n'],
  ]],
  [canvasElementPath, [
    ['        ImageDataBuffer::Create(image_bitmap);\n',
     '        ImageDataBuffer::Create(image_bitmap, /*landu_canvas=*/true);\n'],
  ]],
  [imageBufferHeaderPath, [
    ['      scoped_refptr<StaticBitmapImage>);\n',
     '      scoped_refptr<StaticBitmapImage>, bool landu_canvas = false);\n'],
    ['  ImageDataBuffer(scoped_refptr<StaticBitmapImage>);\n',
     '  ImageDataBuffer(scoped_refptr<StaticBitmapImage>, bool landu_canvas);\n'],
  ]],
  [imageBufferPath, [
    ['#include "base/memory/ptr_util.h"\n', '#include "base/memory/ptr_util.h"\n' + canvasInclude],
    ['ImageDataBuffer::ImageDataBuffer(scoped_refptr<StaticBitmapImage> image) {\n',
     'ImageDataBuffer::ImageDataBuffer(scoped_refptr<StaticBitmapImage> image, bool landu_canvas) {\n'],
    ['  is_valid_ = true;\n',
     '  if (landu_canvas) {\n' +
     '    retained_image_ = LanduCanvasExportImage(std::move(retained_image_));\n' +
     '    if (!retained_image_ || !retained_image_->peekPixels(&pixmap_)) return;\n' +
     '  }\n  is_valid_ = true;\n'],
    ['    scoped_refptr<StaticBitmapImage> image) {\n',
     '    scoped_refptr<StaticBitmapImage> image, bool landu_canvas) {\n'],
    ['      base::WrapUnique(new ImageDataBuffer(image));\n',
     '      base::WrapUnique(new ImageDataBuffer(image, landu_canvas));\n'],
  ]],
  [canvasBlobPath, [
    ['#include "third_party/blink/renderer/platform/graphics/image_data_buffer.h"\n',
     '#include "third_party/blink/renderer/platform/graphics/image_data_buffer.h"\n' + canvasInclude],
    ['    if (skia_image_->peekPixels(&src_data_)) {\n',
     '    skia_image_ = LanduCanvasExportImage(std::move(skia_image_));\n' +
     '    if (skia_image_ && skia_image_->peekPixels(&src_data_)) {\n'],
  ]],
  [platformBuildPath, [
    ['    "graphics/image_data_buffer.h",\n',
     '    "graphics/image_data_buffer.h",\n    "graphics/landu_canvas_readback.h",\n' +
     '    "graphics/landu_gpu_metadata.h",\n'],
  ]],
  [rendererHostPath, [
    ['  const std::string locale =\n      GetContentClient()->browser()->GetApplicationLocale();\n  command_line->AppendSwitchASCII(switches::kLang, locale);\n',
     '  const std::string landu_locale =\n      browser_command_line.GetSwitchValueASCII("landu-web-locale");\n' +
     '  const std::string locale = landu_locale.empty()\n' +
     '      ? GetContentClient()->browser()->GetApplicationLocale()\n      : landu_locale;\n' +
     '  command_line->AppendSwitchASCII(switches::kLang, locale);\n'],
  ]],
  [timezonePath, [
    ['#include "base/feature_list.h"\n', '#include "base/feature_list.h"\n#include "base/no_destructor.h"\n'],
    ['void TimeZoneController::Init() {\n',
     'void TimeZoneController::Init() {\n' +
     '  const std::string landu_timezone = base::CommandLine::ForCurrentProcess()->\n' +
     '      GetSwitchValueASCII("landu-timezone");\n' +
     '  if (!landu_timezone.empty()) {\n' +
     '    // The selected environment owns this override for the renderer lifetime.\n' +
     '    static base::NoDestructor<std::unique_ptr<TimeZoneOverride>> landu_override;\n' +
     '    if (!*landu_override) {\n' +
     '      auto result = SetTimeZoneOverride(String::FromUtf8(landu_timezone));\n' +
     '      CHECK_EQ(result.status, TimeZoneOverrideStatus::kSuccess);\n' +
     '      *landu_override = std::move(result.handle);\n' +
     '    }\n  }\n'],
  ]],
  [profileJavaPath, [
    ['    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)\n',
     '    /** Waits for loaded-profile preferences and cookie stores before Landu exits. */\n' +
     '    public static void flushPersistentDataBeforeLanduExit(Runnable onFlushed, Runnable onFailure) {\n' +
     '        ProfileManagerUtilsJni.get().flushPersistentDataBeforeLanduExit(onFlushed, onFailure);\n' +
     '    }\n\n    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)\n'],
    ['        void flushPersistentDataForAllProfiles();\n',
     '        void flushPersistentDataForAllProfiles();\n\n' +
     '        void flushPersistentDataBeforeLanduExit(Runnable onFlushed, Runnable onFailure);\n'],
  ]],
  [profileNativePath, [
    ['#include <algorithm>\n', '#include <algorithm>\n#include <memory>\n'],
    ['#include "base/android/jni_android.h"\n', '#include "base/android/jni_android.h"\n' +
     '#include "base/barrier_closure.h"\n' +
     '#include "mojo/public/cpp/bindings/callback_helpers.h"\n' +
     '#include "third_party/jni_zero/common_apis.h"\n'],
    ['static void JNI_ProfileManagerUtils_RemoveSessionCookiesForAllProfiles(\n',
     shutdownNativeCode + 'static void JNI_ProfileManagerUtils_RemoveSessionCookiesForAllProfiles(\n'],
  ]],
  [fontPath, [
    ['#include "base/feature_list.h"\n', '#include "base/feature_list.h"\n' +
     '#include "base/command_line.h"\n' + settingsInclude],
    ['    AlternateFontName alternate_font_name) {\n',
     '    AlternateFontName alternate_font_name) {\n' +
     '  // Gate before the shared cache, including local() unique-name lookups.\n' +
     '  if (creation_params.CreationType() == kCreateFontByFamily &&\n' +
     '      base::CommandLine::InitializedForCurrentProcess() &&\n' +
     '      base::CommandLine::ForCurrentProcess()->HasSwitch("landu-isolate-regional-fonts") &&\n' +
     '      landu::IsRegionalLocalFont(creation_params.Family().Utf8())) {\n' +
     '    return nullptr;\n  }\n'],
  ]],
  [factoryPath, [
    ['        setProcessCreationReason(ProcessCreationReason.ACTIVITY);\n',
     `        if (${managerCheck}) return super.instantiateActivity(cl, className, intent);\n` +
     '        setProcessCreationReason(ProcessCreationReason.ACTIVITY);\n'],
    ['        // Android always initializes all ContentProviders when it initializes the Application,\n',
     `        if (${managerCheck}) return super.instantiateProvider(cl, className);\n` +
     '        // Android always initializes all ContentProviders when it initializes the Application,\n'],
    ['        setProcessCreationReason(ProcessCreationReason.BROADCAST_RECEIVER);\n',
     `        if (${managerCheck}) return super.instantiateReceiver(cl, className, intent);\n` +
     '        setProcessCreationReason(ProcessCreationReason.BROADCAST_RECEIVER);\n'],
    ['        setProcessCreationReason(ProcessCreationReason.SERVICE);\n',
     `        if (${managerCheck}) return super.instantiateService(cl, className, intent);\n` +
     '        setProcessCreationReason(ProcessCreationReason.SERVICE);\n'],
  ]],
  [cpuPath, [
    ['#include "base/system/sys_info.h"\n', '#include "base/system/sys_info.h"\n' +
     '#include "base/command_line.h"\n' + settingsInclude],
    ['unsigned NavigatorConcurrentHardware::hardwareConcurrency() const {\n',
     'unsigned NavigatorConcurrentHardware::hardwareConcurrency() const {\n' +
     '  auto value = landu::HardwareConcurrency(base::CommandLine::ForCurrentProcess()->\n' +
     '      GetSwitchValueASCII("landu-hardware-concurrency"));\n' +
     '  if (value) return *value;\n'],
  ]],
  [memoryPath, [
    ['#include "third_party/blink/public/common/device_memory/approximated_device_memory.h"\n',
     '#include "third_party/blink/public/common/device_memory/approximated_device_memory.h"\n' +
     '#include "base/command_line.h"\n' + settingsInclude],
    ['float ApproximatedDeviceMemory::GetApproximatedDeviceMemory() {\n',
     'float ApproximatedDeviceMemory::GetApproximatedDeviceMemory() {\n' +
     '  // Keep navigator.deviceMemory and both HTTP Client Hints on one source.\n' +
     '  if (base::CommandLine::InitializedForCurrentProcess()) {\n' +
     '    auto value = landu::DeviceMemory(base::CommandLine::ForCurrentProcess()->\n' +
     '        GetSwitchValueASCII("landu-device-memory"));\n' +
     '    if (value) return *value;\n  }\n'],
  ]],
  [browserClientPath, [
    ['#include "chrome/browser/chrome_content_browser_client.h"\n',
     '#include "chrome/browser/chrome_content_browser_client.h"\n\n' +
     '#if BUILDFLAG(IS_ANDROID)\n#include "chrome/browser/landu_geolocation_provider.h"\n' +
     '#include "components/permissions/landu_geolocation_settings.h"\n#endif\n'],
    ['ChromeContentBrowserClient::~ChromeContentBrowserClient() {\n',
     '#if BUILDFLAG(IS_ANDROID)\n' +
     'std::unique_ptr<device::LocationProvider>\nChromeContentBrowserClient::OverrideSystemLocationProvider() {\n' +
     '  const auto policy = permissions::landu::ReadGeolocationPolicy();\n' +
     '  if (policy.mode == permissions::landu::GeolocationMode::kSystem) return nullptr;\n' +
     '  return std::make_unique<landu::GeolocationProvider>(policy);\n}\n#endif\n\n' +
     'ChromeContentBrowserClient::~ChromeContentBrowserClient() {\n'],
    ['  static const char* const kCommonSwitchNames[] = {\n',
     '  static const char* const kCommonSwitchNames[] = {\n' +
     '      "landu-hardware-concurrency",\n      "landu-device-memory",\n' +
     '      "landu-isolate-regional-fonts",\n      "landu-timezone",\n      "landu-canvas-seed",\n      "landu-audio-seed",\n      "landu-client-rects-seed",\n      "landu-media-mode",\n      "landu-media-seed",\n      "landu-media-audioinput",\n      "landu-media-videoinput",\n      "landu-media-audiooutput",\n' +
     '      "landu-android-version",\n      "landu-android-model",\n' +
     '      "landu-webgl-vendor",\n      "landu-webgl-renderer",\n' +
     '      "landu-webgpu-vendor",\n      "landu-webgpu-architecture",\n' +
     '      "landu-webgpu-device",\n      "landu-webgpu-description",\n'],
  ]],
  [browserClientHeaderPath, [
    ['  ~ChromeContentBrowserClient() override;\n',
     '  ~ChromeContentBrowserClient() override;\n\n#if BUILDFLAG(IS_ANDROID)\n' +
     '  std::unique_ptr<device::LocationProvider> OverrideSystemLocationProvider() override;\n#endif\n'],
  ]],
  [browserBuildPath, [
    ['    "chrome_content_browser_client.cc",\n',
     '    "chrome_content_browser_client.cc",\n    "landu_geolocation_provider.h",\n' +
     '    "landu_geolocation_provider.cc",\n'],
  ]],
  [permissionsBuildPath, [
    ['    "permission_context_base.h",\n',
     '    "permission_context_base.h",\n    "landu_geolocation_settings.h",\n    "landu_geolocation_policy.h",\n'],
  ]],
  [permissionContextPath, [
    ['#include "components/permissions/permission_context_base.h"\n\n#include <stddef.h>\n',
     '#include "components/permissions/permission_context_base.h"\n\n' +
     '#if BUILDFLAG(IS_ANDROID)\n#include "components/permissions/landu_geolocation_settings.h"\n#endif\n\n' +
     '#include <stddef.h>\n'],
    ['content::PermissionResult PermissionContextBase::GetPermissionStatus(\n' +
     '    const PermissionRequestData& request_data,\n' +
     '    content::RenderFrameHost* render_frame_host) const {\n',
     'content::PermissionResult PermissionContextBase::GetPermissionStatus(\n' +
     '    const PermissionRequestData& request_data,\n' +
     '    content::RenderFrameHost* render_frame_host) const {\n' + geolocationBlock],
    ['content::PermissionResult PermissionContextBase::GetPermissionStatus(\n' +
     '    const PermissionResolver& resolver,\n' +
     '    content::RenderFrameHost* render_frame_host,\n' +
     '    const GURL& requesting_origin,\n' +
     '    const GURL& embedding_origin) const {\n',
     'content::PermissionResult PermissionContextBase::GetPermissionStatus(\n' +
     '    const PermissionResolver& resolver,\n' +
     '    content::RenderFrameHost* render_frame_host,\n' +
     '    const GURL& requesting_origin,\n' +
     '    const GURL& embedding_origin) const {\n' + geolocationBlock],
  ]],
  ["third_party/blink/renderer/modules/mediastream/media_device_info.cc", [["#include \"third_party/blink/renderer/modules/mediastream/media_device_info.h\"\n", "#include \"third_party/blink/renderer/modules/mediastream/media_device_info.h\"\n#include \"third_party/blink/renderer/modules/mediastream/landu_media_labels.h\"\n"], ["String MediaDeviceInfo::label() const {\n  return label_;\n}", "String MediaDeviceInfo::label() const {\n  switch (device_type_) {\n    case mojom::blink::MediaDeviceType::kMediaAudioInput:\n      return LanduMediaLabel(label_, device_id_, \"audioinput\", \"Microphone\");\n    case mojom::blink::MediaDeviceType::kMediaVideoInput:\n      return LanduMediaLabel(label_, device_id_, \"videoinput\", \"Camera\");\n    case mojom::blink::MediaDeviceType::kMediaAudioOutput:\n      return LanduMediaLabel(label_, device_id_, \"audiooutput\", \"Speaker\");\n    case mojom::blink::MediaDeviceType::kNumMediaDeviceTypes:\n      break;\n  }\n  return label_;\n}"]]],
  ["third_party/blink/renderer/modules/mediastream/media_device_info.h", [[" private:\n  String device_id_;", " protected:\n  bool HasUnmaskedLabel() const { return !label_.empty(); }\n\n private:\n  String device_id_;"]]],
  ["third_party/blink/renderer/modules/mediastream/input_device_info.cc", [["if (label().empty() || !platform_capabilities_.is_available)", "if (!HasUnmaskedLabel() || !platform_capabilities_.is_available)"]]],
  ["third_party/blink/renderer/modules/mediastream/media_stream_track_impl.cc", [["#include \"third_party/blink/renderer/modules/mediastream/media_stream_track_impl.h\"\n", "#include \"third_party/blink/renderer/modules/mediastream/media_stream_track_impl.h\"\n#include \"third_party/blink/renderer/modules/mediastream/landu_media_labels.h\"\n"], ["String MediaStreamTrackImpl::label() const {\n  return component_->GetSourceName();\n}", "String MediaStreamTrackImpl::label() const {\n  const String original = component_->GetSourceName();\n  const auto capture_device = device();\n  if (!component_->Remote() && capture_device) {\n    if (capture_device->type == mojom::MediaStreamType::DEVICE_AUDIO_CAPTURE)\n      return LanduMediaLabel(original, String::FromUtf8(capture_device->id),\n                             \"audioinput\", \"Microphone\");\n    if (capture_device->type == mojom::MediaStreamType::DEVICE_VIDEO_CAPTURE)\n      return LanduMediaLabel(original, String::FromUtf8(capture_device->id),\n                             \"videoinput\", \"Camera\");\n  }\n  return original;\n}"]]],
  ["third_party/blink/renderer/modules/mediastream/BUILD.gn", [["    \"media_device_info.cc\",\n", "    \"media_device_info.cc\",\n    \"landu_media_labels.h\",\n"]]],
  ["content/browser/media/media_devices_util.cc", [["#include \"content/browser/media/media_devices_util.h\"\n", "#include \"content/browser/media/media_devices_util.h\"\n#include \"third_party/blink/public/common/landu/landu_media_policy.h\"\n"], ["  std::array<uint8_t, crypto::hash::kSha256Size> result;\n", "  // Add environment identity at the shared forward/reverse HMAC boundary.\n  // Preserve Chromium's origin, cookie lifetime and document group salts.\n  if (base::CommandLine::InitializedForCurrentProcess()) {\n    const auto* command = base::CommandLine::ForCurrentProcess();\n    const auto mode = blink::landu::ParseMediaMode(\n        command->GetSwitchValueASCII(\"landu-media-mode\"));\n    const std::string seed = command->GetSwitchValueASCII(\"landu-media-seed\");\n    if (mode != blink::landu::MediaIdentityMode::kReal &&\n        blink::landu::ValidMediaSeed(seed)) {\n      hmac.Update(base::as_byte_span(std::string_view(\"landu-media-v1:\")));\n      hmac.Update(base::as_byte_span(seed));\n    }\n  }\n  std::array<uint8_t, crypto::hash::kSha256Size> result;\n"]]],
  [elementPath, [
    ['#include "third_party/blink/renderer/core/dom/element.h"\n',
     '#include "third_party/blink/renderer/core/dom/element.h"\n' +
     '#include "third_party/blink/renderer/core/geometry/landu_client_rects.h"\n'],
    ['  return MakeGarbageCollected<DOMRectList>(rects);\n',
     '  return NoiseLanduClientRects(MakeGarbageCollected<DOMRectList>(rects));\n'],
    ['  SyncScrollAttemptHeuristic::DidAccessScrollOffset();\n  return GetBoundingClientRect();\n',
     '  SyncScrollAttemptHeuristic::DidAccessScrollOffset();\n' +
     '  if (LanduClientRectsSeed()) {\n' +
     '    if (auto* bounds = LanduClientRectsBounds(getClientRects())) return bounds;\n' +
     '  }\n  return GetBoundingClientRect();\n'],
  ]],
  [rangePath, [
    ['#include "third_party/blink/renderer/core/dom/range.h"\n',
     '#include "third_party/blink/renderer/core/dom/range.h"\n' +
     '#include "third_party/blink/renderer/core/geometry/landu_client_rects.h"\n'],
    ['  return MakeGarbageCollected<DOMRectList>(quads);\n',
     '  return NoiseLanduClientRects(MakeGarbageCollected<DOMRectList>(quads));\n'],
    ['  SyncScrollAttemptHeuristic::DidAccessScrollOffset();\n  return DOMRect::FromRectF(BoundingRect());\n',
     '  SyncScrollAttemptHeuristic::DidAccessScrollOffset();\n' +
     '  if (LanduClientRectsSeed()) {\n' +
     '    if (auto* bounds = LanduClientRectsBounds(getClientRects())) return bounds;\n' +
     '  }\n  return DOMRect::FromRectF(BoundingRect());\n'],
  ]],
  [geometryBuildPath, [
    ['  "dom_rect.h",\n', '  "dom_rect.h",\n  "landu_client_rects.h",\n'],
  ]],
  [offlineAudioPath, [
    ['#include "third_party/blink/renderer/modules/webaudio/offline_audio_context.h"\n',
     '#include "third_party/blink/renderer/modules/webaudio/offline_audio_context.h"\n' +
     '#include "third_party/blink/renderer/modules/webaudio/landu_audio_readback.h"\n'],
    ['    // Call the offline rendering completion event listener and resolve the\n',
     '    // The render thread has joined and the buffer is not yet exposed to JS.\n' +
     '    // Process once here so event/promise readers and subsequent writes share\n' +
     '    // the same real AudioBuffer; ordinary authored/decoded buffers stay intact.\n' +
     '    for (unsigned channel = 0; channel < rendered_buffer->numberOfChannels(); ++channel) {\n' +
     '      NoiseLanduAudioArray(rendered_buffer->getChannelData(channel).Get(),\n' +
     '          rendered_buffer->length(), landu::AudioReadback::kOffline, channel);\n' +
     '    }\n\n' +
     '    // Call the offline rendering completion event listener and resolve the\n'],
  ]],
  [analyserPath, [
    ['#include "third_party/blink/renderer/modules/webaudio/analyser_node.h"\n',
     '#include "third_party/blink/renderer/modules/webaudio/analyser_node.h"\n' +
     '#include "third_party/blink/renderer/modules/webaudio/landu_audio_readback.h"\n'],
    ...['FloatFrequency', 'ByteFrequency', 'FloatTimeDomain', 'ByteTimeDomain'].map(kind => {
      const frequency = kind.endsWith('Frequency');
      const arrayType = kind.startsWith('Float') ? 'DOMFloat32Array' : 'DOMUint8Array';
      const signature = `void AnalyserNode::get${kind}Data(NotShared<${arrayType}> array) {\n`;
      const original = frequency
        ? `  GetAnalyserHandler().Get${kind}Data(array.Get(),\n${' '.repeat(kind.startsWith('Float') ? 45 : 44)}context()->currentTime());\n`
        : `  GetAnalyserHandler().Get${kind}Data(array.Get());\n`;
      return [signature + original + '}\n', signature + original +
        `  NoiseLanduAudioArray(array.Get(), ${frequency ? 'frequencyBinCount()' : 'fftSize()'},\n` +
        `      landu::AudioReadback::${frequency ? 'kFrequency' : 'kTimeDomain'});\n}\n`];
    }),
  ]],
  [audioWorkletPath, [
    ['#include "third_party/blink/renderer/modules/webaudio/audio_worklet_processor.h"\n',
     '#include "third_party/blink/renderer/modules/webaudio/audio_worklet_processor.h"\n' +
     '#include "third_party/blink/renderer/modules/webaudio/landu_audio_readback.h"\n'],
    ['                            BackingArrayBuffers& array_buffers) {\n  DCHECK_EQ(audio_port.size(), array_buffers.size());\n',
     '                            BackingArrayBuffers& array_buffers,\n' +
     '                            const std::optional<uint64_t>& landu_seed) {\n' +
     '  DCHECK_EQ(audio_port.size(), array_buffers.size());\n'],
    ['          blink::features::kAudioWorkletJSDenormalEnabler)) {\n',
     '          blink::features::kAudioWorkletJSDenormalEnabler)),\n' +
     '      landu_noise_seed_(LanduAudioNoiseSeed()) {\n'],
    ['  CopyPortToArrayBuffers(isolate, inputs, input_array_buffers_);\n',
     '  CopyPortToArrayBuffers(isolate, inputs, input_array_buffers_, landu_noise_seed_);\n'],
    ['      base::subtle::reinterpret_span<float>(\n' +
     '          ArrayBufferContents(std::move(backing_store)).ByteSpan())\n' +
     '          .first(bus_length)\n' +
     '          .copy_from(audio_bus->Channel(channel_index)->Span());\n',
     '      ArrayBufferContents landu_contents(std::move(backing_store));\n' +
     '      auto landu_values = base::subtle::reinterpret_span<float>(\n' +
     '          landu_contents.ByteSpan()).first(bus_length);\n' +
     '      landu_values.copy_from(audio_bus->Channel(channel_index)->Span());\n' +
     '      // Process only the JS-facing input copy, never the source AudioBus.\n' +
     '      // A pass-through worklet may forward this one-ULP change downstream.\n' +
     '      if (landu_seed) {\n' +
     '        landu::NoiseAudioValues(\n' +
     '            std::span<float>(landu_values.data(), landu_values.size()),\n' +
     '            *landu_seed, landu::AudioReadback::kWorklet,\n' +
     '            (static_cast<uint64_t>(bus_index) << 32) | channel_index);\n' +
     '      }\n'],
  ]],
  [audioWorkletHeaderPath, [
    ['#include "third_party/blink/renderer/modules/modules_export.h"\n',
     '#include <cstdint>\n#include <optional>\n\n' +
     '#include "third_party/blink/renderer/modules/modules_export.h"\n'],
    ['  const bool is_denormal_enabler_enabled_;\n',
     '  const bool is_denormal_enabler_enabled_;\n' +
     '  // Snapshot process-fixed policy at construction, outside the render loop.\n' +
     '  const std::optional<uint64_t> landu_noise_seed_;\n'],
  ]],
  [webAudioBuildPath, [
    ['    "analyser_node.h",\n', '    "analyser_node.h",\n    "landu_audio_readback.h",\n'],
  ]],
  [commonBuildPath, [
    ['    "device_memory/approximated_device_memory.h",\n',
     '    "device_memory/approximated_device_memory.h",\n    "landu/landu_fingerprint_settings.h",\n' +
     '    "landu/landu_canvas_noise.h",\n    "landu/landu_gpu_metadata_settings.h",\n' +
     '    "landu/landu_webgl_noise.h",\n    "landu/landu_audio_noise.h",\n' +
     '    "landu/landu_client_rects_noise.h",\n    "landu/landu_media_policy.h",\n'],
  ]],
  [bootstrapPath, [
    ['    protected void attachBaseContext(Context context) {\n',
     '    protected void attachBaseContext(Context context) {\n' +
     `        if (${managerCheck}) {\n            super.attachBaseContext(context);\n            return;\n        }\n`],
    ['        boolean isBrowserProcess = isBrowserProcess();\n',
     '        boolean isBrowserProcess = isBrowserProcess();\n' +
     '        if (isBrowserProcess) {\n' +
     '            context = com.landu.browser.nativecore.LanduEnvironmentBootstrap.attach(context);\n' +
     '        }\n'],
    ['            PathUtils.setPrivateDataDirectorySuffix(PRIVATE_DATA_DIRECTORY_SUFFIX);',
     '            com.landu.browser.nativecore.LanduEnvironmentBootstrap.initializePaths(context);'],
    ['        super.onCreate();\n', `        super.onCreate();\n        if (${managerCheck}) return;\n`],
    ['        super.onTrimMemory(level);\n', `        super.onTrimMemory(level);\n        if (${managerCheck}) return;\n`],
    ['        super.onConfigurationChanged(newConfig);\n',
     `        super.onConfigurationChanged(newConfig);\n        if (${managerCheck}) return;\n`],
    ['    public void startActivity(Intent intent, @Nullable Bundle options) {\n        getImpl().startActivity(intent, options);',
     '    public void startActivity(Intent intent, @Nullable Bundle options) {\n' +
     `        if (${managerCheck}) {\n            super.startActivity(intent, options);\n            return;\n        }\n` +
     '        getImpl().startActivity(intent, options);'],
  ]],
  [splitPath, [
    ['    protected void attachBaseContext(Context context) {\n',
     '    protected void attachBaseContext(Context context) {\n' +
     `        if (${managerCheck}) {\n            super.attachBaseContext(context);\n            return;\n        }\n`],
    ['    public void onCreate() {\n', '    public void onCreate() {\n' +
     `        if (${managerCheck}) {\n            super.onCreate();\n            return;\n        }\n`],
    ['    public Context createContextForSplit(String name) {\n',
     '    public Context createContextForSplit(String name) {\n' +
     `        if (${managerCheck}) {\n` +
     '            try { return super.createContextForSplit(name); }\n' +
     '            catch (PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }\n' +
     '        }\n'],
  ]],
  [activityPath, [
    ['    protected void attachBaseContext(Context newBase) {\n',
     '    protected void attachBaseContext(Context newBase) {\n' +
     '        if (org.chromium.chrome.browser.base.SplitCompatApplication.isBrowserProcess()) {\n' +
     `            newBase = ${runtime}.wrapActivity(newBase);\n        }\n`],
  ]],
  [initializationPath, [
    ['                        mInitializedPostNative = true;\n',
     '                        mInitializedPostNative = true;\n' +
     `                        ${runtime}.registerStopHandler(\n` +
     '                                result -> org.chromium.chrome.browser.profiles.ProfileManagerUtils.flushPersistentDataBeforeLanduExit(\n' +
     '                                        () -> result.complete(null),\n' +
     '                                        () -> result.complete("数据保存接口中断，环境仍保持打开")),\n' +
     '                                () -> org.chromium.chrome.browser.lifetime.ApplicationLifetime.terminate(false),\n' +
     '                                () -> ChromeBrowserInitializer.getInstance().isFullBrowserInitialized());\n' +
     `                        ${runtime}.registerCookieHandler((importing, json, result) ->\n` +
     '                                org.chromium.chrome.browser.cookies.CookiesFetcher.transferForLandu(\n' +
     '                                        org.chromium.chrome.browser.profiles.ProfileManager.getLastUsedRegularProfile(),\n' +
     '                                        importing, json, result::accept));\n'],
  ]],
  [cookiesJavaPath, [
    ['    @VisibleForTesting\n    @NativeMethods\n    public interface Natives {\n',
     '    /** Explicit manager transfer for this process\'s regular environment profile. */\n' +
     '    public static void transferForLandu(Profile profile, boolean importing, String json,\n' +
     '            org.chromium.base.Callback<String> callback) {\n' +
     '        ThreadUtils.assertOnUiThread();\n' +
     '        if (profile.isOffTheRecord()) throw new IllegalArgumentException("Regular profile required");\n' +
     '        CookiesFetcherJni.get().transferForLandu(profile, importing, json, callback);\n' +
     '    }\n\n' +
     '    @VisibleForTesting\n    @NativeMethods\n    public interface Natives {\n' +
     '        void transferForLandu(@JniType("Profile*") Profile profile, boolean importing,\n' +
     '                @JniType("std::string") String json, org.chromium.base.Callback<String> callback);\n\n'],
  ]],
  [cookiesNativePath, [
    ['DEFINE_JNI(CookiesFetcher)\n',
     '#include "chrome/browser/android/cookies/landu_cookie_transfer.inc"\n\nDEFINE_JNI(CookiesFetcher)\n'],
  ]],
  [cookiesBuildPath, [
    ['    "cookies/cookies_fetcher_util.cc",\n',
     '    "cookies/cookies_fetcher_util.cc",\n    "cookies/landu_cookie_transfer.inc",\n    "cookies/landu_cookie_codec.h",\n'],
  ]],
  [cookiesJavaBuildPath, [
    ['robolectric_library("junit") {\n',
     'executable("landu_cookie_codec_tests") {\n' +
     '  testonly = true\n' +
     '  sources = [ "landu_cookie_codec_test.cc", "landu_cookie_codec.h" ]\n' +
     '  deps = [ "//base", "//net", "//url" ]\n' +
     '}\n\nrobolectric_library("junit") {\n'],
  ]],
  [taskInfoPath, [
    ['import org.chromium.build.annotations.NullMarked;\n',
     'import org.chromium.base.CommandLine;\nimport org.chromium.build.annotations.NullMarked;\n'],
    ['    public static final String SERIALIZED_TASK_EXTRAS = "serialized_task_extras";\n',
     '    public static final String SERIALIZED_TASK_EXTRAS = "serialized_task_extras";\n' + taskOwnershipCode],
    ['        mExtras = builder.mExtras == null ? new PersistableBundle() : builder.mExtras;\n',
     '        mExtras = copyLanduExtras(builder.mExtras);\n'],
  ]],
  [taskSchedulerPath, [
    ['    public boolean schedule(Context context, TaskInfo taskInfo) {\n',
     '    public boolean schedule(Context context, TaskInfo taskInfo) {\n' +
     '        if (!TaskInfo.hasMatchingLanduEnvironment(taskInfo.getExtras())) return false;\n'],
  ]],
  [taskServicePath, [
    ['                                notificationId,\n                                notification,\n',
     '                                org.chromium.base.IntentUtils.LanduEnvironment.notificationId(notificationId),\n' +
     '                                notification,\n'],
    ['import org.chromium.components.background_task_scheduler.TaskParameters;\n',
     'import org.chromium.components.background_task_scheduler.TaskInfo;\n' +
     'import org.chromium.components.background_task_scheduler.TaskParameters;\n'],
    ['        Log.w(TAG, "Starting background task (jobId=%d)", params.getJobId());\n',
     '        TaskParameters taskParams;\n' +
     '        try {\n' +
     '            taskParams = BackgroundTaskSchedulerJobService.getTaskParametersFromJobParameters(params);\n' +
     '            if (BackgroundTaskSchedulerJobService.didTaskExpire(params, mClock.currentTimeMillis())) return false;\n' +
     '        } catch (RuntimeException error) {\n' +
     '            Log.w(TAG, "Refusing malformed Landu background task");\n' +
     '            return false;\n' +
     '        }\n' +
     '        String targetNamespace = TaskInfo.getLanduJobNamespace(taskParams.getExtras());\n' +
     '        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE\n' +
     '                || targetNamespace == null || TaskInfo.getLanduJobNamespace() == null\n' +
     '                || !targetNamespace.equals(params.getJobNamespace())) return false;\n' +
     '        if (!TaskInfo.hasMatchingLanduEnvironment(taskParams.getExtras())) {\n' +
     '            Log.w(TAG, "Deferring background task outside the selected Landu environment");\n' +
     '            return deferLanduJob(params);\n' +
     '        }\n' +
     '        Log.w(TAG, "Starting background task (jobId=%d)", params.getJobId());\n'],
    ['        mCurrentTasks.put(params.getJobId(), backgroundTask);\n\n' +
     '        TaskParameters taskParams =\n' +
     '                BackgroundTaskSchedulerJobService.getTaskParametersFromJobParameters(params);\n',
     '        mCurrentTasks.put(landuJobKey(params), backgroundTask);\n' +
     '        mLanduTaskParameters.put(landuJobKey(params), taskParams);\n'],
    ['    private final Map<Integer, BackgroundTask> mCurrentTasks = new HashMap<>();\n', taskServiceNamespaceCode],
    ['                            mJobService.mCurrentTasks.remove(mParams.getJobId());\n',
     '                            mJobService.removeLanduCurrentTask(mParams);\n'],
    ['            return mJobService.mCurrentTasks.get(mParams.getJobId()) == mBackgroundTask;\n',
     '            String key = landuJobKey(mParams);\n' +
     '            return mJobService.mCurrentTasks.get(key) == mBackgroundTask\n' +
     '                    && mJobService.mLanduTaskCallbacks.get(key) == this;\n'],
    ['        if (BackgroundTaskSchedulerJobService.didTaskExpire(params, mClock.currentTimeMillis())) {\n' +
     '            return false;\n        }\n',
     '        // Task expiry was checked before ownership and task construction.\n'],
    ['        if (!taskNeedsBackgroundProcessing) mCurrentTasks.remove(params.getJobId());\n',
     '        if (!taskNeedsBackgroundProcessing\n' +
     '                && mLanduTaskCallbacks.get(landuJobKey(params)) == callback) removeLanduCurrentTask(params);\n'],
    ['        boolean taskNeedsBackgroundProcessing =\n' +
     '                backgroundTask.onStartTask(\n' +
     '                        ContextUtils.getApplicationContext(),\n' +
     '                        taskParams,\n' +
     '                        new TaskFinishedCallbackJobService(this, backgroundTask, params));\n',
     '        TaskFinishedCallbackJobService callback =\n' +
     '                new TaskFinishedCallbackJobService(this, backgroundTask, params);\n' +
     '        mLanduTaskCallbacks.put(landuJobKey(params), callback);\n' +
     '        boolean taskNeedsBackgroundProcessing =\n' +
     '                backgroundTask.onStartTask(ContextUtils.getApplicationContext(), taskParams, callback);\n'],
    ['        Log.w(TAG, "Stopping background task (jobId=%d)", params.getJobId());\n',
     '        String key = landuJobKey(params);\n' +
     '        Runnable deferred = mLanduDeferredJobs.remove(key);\n' +
     '        if (deferred != null) {\n' +
     '            mLanduDeferredHandler.removeCallbacks(deferred);\n' +
     '            return true;\n        }\n' +
     '        Log.w(TAG, "Stopping background task (jobId=%d)", params.getJobId());\n'],
    ['        if (!mCurrentTasks.containsKey(params.getJobId())) {\n',
     '        if (!mCurrentTasks.containsKey(key)) {\n'],
    ['        BackgroundTask backgroundTask = mCurrentTasks.get(params.getJobId());\n',
     '        BackgroundTask backgroundTask = mCurrentTasks.get(key);\n' +
     '        TaskFinishedCallbackJobService callback = mLanduTaskCallbacks.get(key);\n'],
    ['        TaskParameters taskParams =\n' +
     '                BackgroundTaskSchedulerJobService.getTaskParametersFromJobParameters(params);\n',
     '        TaskParameters taskParams = mLanduTaskParameters.get(key);\n' +
     '        if (taskParams == null) return false;\n'],
    ['        mCurrentTasks.remove(params.getJobId());\n        return taskNeedsReschedule;\n',
     '        if (mLanduTaskCallbacks.get(key) == callback) removeLanduCurrentTask(params);\n' +
     '        return taskNeedsReschedule;\n'],
  ]],
  [taskJobPath, [
    ['import android.os.PersistableBundle;\n',
     'import android.os.Build;\nimport android.os.PersistableBundle;\n'],
    ['import org.chromium.build.annotations.NullMarked;\n',
     'import org.chromium.build.annotations.NullMarked;\nimport org.chromium.build.annotations.Nullable;\n'],
    ['        PersistableBundle persistableTaskExtras =\n' +
     '                jobExtras.getPersistableBundle(BACKGROUND_TASK_EXTRAS_KEY);\n\n' +
     '        PersistableBundle taskExtras = new PersistableBundle();\n' +
     '        taskExtras.putAll(persistableTaskExtras);\n',
     '        PersistableBundle persistableTaskExtras = jobExtras == null ? null\n' +
     '                : jobExtras.getPersistableBundle(BACKGROUND_TASK_EXTRAS_KEY);\n\n' +
     '        PersistableBundle taskExtras = new PersistableBundle();\n' +
     '        if (persistableTaskExtras != null) taskExtras.putAll(persistableTaskExtras);\n'],
    ['    @Override\n    public boolean schedule(Context context, TaskInfo taskInfo) {\n',
     taskSchedulerNamespaceCode + '    @Override\n    public boolean schedule(Context context, TaskInfo taskInfo) {\n'],
    ['        JobScheduler jobScheduler =\n' +
     '                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);\n\n' +
     '        if (!taskInfo.shouldUpdateCurrent()',
     '        JobScheduler jobScheduler = landuJobScheduler(context);\n' +
     '        if (jobScheduler == null) return false;\n\n' +
     '        if (!taskInfo.shouldUpdateCurrent()'],
    ['        JobScheduler jobScheduler =\n' +
     '                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);\n        try {\n',
     '        JobScheduler jobScheduler = landuJobScheduler(context);\n' +
     '        if (jobScheduler == null) return;\n        try {\n'],
  ]],
  [manifestPath, [
    ['        {% if backup_key is defined %}\n' +
     '        android:allowBackup="true"\n' +
     '        android:backupAgent="org.chromium.chrome.browser.backup.ChromeBackupAgent"\n' +
     '        android:fullBackupOnly="false"\n' +
     '        android:restoreAnyVersion="true"\n' +
     '        {% else %}\n' +
     '        android:allowBackup="false"\n' +
     '        {% endif %}\n',
     '        android:allowBackup="false"\n        android:fullBackupContent="false"\n'],
    ['      {% macro application_definitions() %}\n',
     '      <activity android:name="com.landu.browser.nativecore.LanduManagerActivity"\n' +
     '          android:process=":landu_manager" android:exported="false"\n' +
     '          android:taskAffinity="{{ manifest_package }}.landu.manager" android:launchMode="singleTask"\n' +
     '          android:theme="@android:style/Theme.Material.Light.NoActionBar"\n' +
     '          android:label="岚渡浏览器" android:windowSoftInputMode="adjustResize" />\n' +
     '      <activity android:name="com.landu.browser.nativecore.LanduLinkActivity"\n' +
     '          android:process=":landu_manager" android:exported="false" android:excludeFromRecents="true"\n' +
     '          android:taskAffinity="{{ manifest_package }}.landu.manager"\n' +
     '          android:theme="@android:style/Theme.Material.Light.NoActionBar" />\n' +
     '      <activity android:name="com.landu.browser.nativecore.LanduLaunchActivity"\n' +
     '          android:exported="false" android:excludeFromRecents="true" android:taskAffinity=""\n' +
     '          android:theme="@android:style/Theme.Material.Light.NoActionBar" />\n' +
     '      <activity android:name="com.landu.browser.nativecore.LanduNotificationActivity"\n' +
     '          android:process=":landu_manager" android:exported="false" android:excludeFromRecents="true"\n' +
     '          android:taskAffinity="{{ manifest_package }}.landu.notification"\n' +
     '          android:theme="@android:style/Theme.Material.Light.NoActionBar" />\n' +
     '      <service android:name="com.landu.browser.nativecore.LanduSessionService" android:exported="false" />\n' +
     '      <receiver android:name="com.landu.browser.nativecore.LanduNotificationReceiver"\n' +
     '          android:process=":landu_manager" android:exported="false" />\n' +
     '      <service android:name="com.landu.browser.nativecore.LanduNotificationJobService"\n' +
     '          android:process=":landu_manager" android:exported="false"\n' +
     '          android:permission="android.permission.BIND_JOB_SERVICE" />\n' +
     libraryManifestOverrides +
     '      {% macro application_definitions() %}\n'],
    ['        <activity-alias android:name="com.google.android.apps.chrome.Main"\n' +
     '            android:targetActivity="org.chromium.chrome.browser.ChromeTabbedActivity"\n',
     '        <activity-alias android:name="com.google.android.apps.chrome.Main"\n' +
     '            android:targetActivity="com.landu.browser.nativecore.LanduManagerActivity"\n'],
    ['        <activity-alias android:name="com.google.android.apps.chrome.IntentDispatcher"\n' +
     '            android:targetActivity="org.chromium.chrome.browser.document.ChromeLauncherActivity"\n',
     '        <activity-alias android:name="com.google.android.apps.chrome.IntentDispatcher"\n' +
     '            android:targetActivity="com.landu.browser.nativecore.LanduLinkActivity"\n'],
    ['        <activity android:name="org.chromium.chrome.browser.ChromeTabbedActivity"\n' +
     '            android:theme="@style/Theme.Chromium.TabbedMode"\n' +
     '            android:exported="true"\n',
     '        <activity android:name="org.chromium.chrome.browser.ChromeTabbedActivity"\n' +
     '            android:theme="@style/Theme.Chromium.TabbedMode"\n' +
     '            android:exported="false"\n'],
  ]],
  [buildPath, [
    ['  android_library("base_module_java") {\n    sources = [\n',
     '  android_library("base_module_java") {\n    sources = [\n' + javaFiles.map(([, name]) =>
       `      "java/src/${packagePath}/${name}",\n`).join('')],
    ['    resources_package = "org.chromium.chrome.base"\n',
     '    deps += [\n      ":landu_brand_resources",\n      "//net/android:net_java",\n    ]\n    resources_package = "org.chromium.chrome.base"\n'],
    ['  version_resource_dir = "$target_gen_dir/templates/chrome_version_xml/res"\n',
     '  android_resources("landu_brand_resources") {\n' +
     '    resource_overlay = true\n    sources = [\n' +
     resourceFiles.filter(([, target]) => target.startsWith('chrome/android/landu/res/'))
       .map(([, target]) => `      "${target.slice('chrome/android/'.length)}",\n`).join('') +
     '    ]\n    deps = [ ":chrome_public_apk_base_module_resources" ]\n  }\n\n' +
     '  android_assets("landu_notices_assets") {\n    renaming_sources = [\n' +
     resourceFiles.filter(([, target]) => target.startsWith('chrome/android/landu/assets/'))
       .map(([, target]) => `      "${target.slice('chrome/android/'.length)}",\n`).join('') +
     '    ]\n    renaming_destinations = [\n' +
     resourceFiles.filter(([, target]) => target.startsWith('chrome/android/landu/assets/'))
       .map(([, target]) => `      "landu/${target.slice('chrome/android/landu/assets/'.length)}",\n`).join('') +
     '    ]\n  }\n\n' +
     '  version_resource_dir = "$target_gen_dir/templates/chrome_version_xml/res"\n'],
    ['  java_group("chrome_public_non_pak_assets") {\n    deps = [\n',
     '  java_group("chrome_public_non_pak_assets") {\n    deps = [\n      ":landu_notices_assets",\n'],
  ]],
]);

// Validated renderer-region checks and translated-ABI JNI compatibility.
for (const [target, changes] of [
  [
    "third_party/blink/renderer/core/page/touch_adjustment.h",
    [
      [
        "#include \"third_party/blink/renderer/core/core_export.h\"",
        "#include \"base/functional/function_ref.h\"\n#include \"third_party/blink/renderer/core/core_export.h\""
      ],
      [
        "    const HeapVector<Member<Node>>&);",
        "    const HeapVector<Member<Node>>& nodes,\n    base::FunctionRef<bool(const gfx::Point&)> is_valid_point);"
      ]
    ]
  ],
  [
    "third_party/blink/renderer/core/page/touch_adjustment.cc",
    [
      [
        "                                      DistanceFunction distance_function) {",
        "                                      DistanceFunction distance_function,\n                                      base::FunctionRef<bool(const gfx::Point&)>\n                                          is_valid_point) {"
      ],
      [
        "\n                       AppendSubtargetsForNode append_subtargets_for_node) {",
        "\n                       AppendSubtargetsForNode append_subtargets_for_node,\n                       base::FunctionRef<bool(const gfx::Point&)> is_valid_point) {"
      ],
      [
        "      touch_adjustment::HybridDistanceFunction);",
        "      touch_adjustment::HybridDistanceFunction, is_valid_point);"
      ],
      [
        "\n    const HeapVector<Member<Node>>& nodes) {",
        "\n    const HeapVector<Member<Node>>& nodes,\n    base::FunctionRef<bool(const gfx::Point&)> is_valid_point) {"
      ],
      [
        "\n                           append_subtargets_for_node);",
        "\n                           append_subtargets_for_node, is_valid_point);"
      ],
      [
        "    if (distance_metric < best_distance_metric) {\n      if (SnapTo(subtarget, touch_hotspot, touch_area, snapped_point)) {",
        "    if (distance_metric < best_distance_metric) {\n      if (SnapTo(subtarget, touch_hotspot, touch_area, snapped_point) &&\n          is_valid_point(snapped_point)) {"
      ],
      [
        "    } else if (distance_metric - best_distance_metric < kZeroTolerance) {\n      if (SnapTo(subtarget, touch_hotspot, touch_area, snapped_point)) {",
        "    } else if (distance_metric - best_distance_metric < kZeroTolerance) {\n      if (SnapTo(subtarget, touch_hotspot, touch_area, snapped_point) &&\n          is_valid_point(snapped_point)) {"
      ]
    ]
  ],
  [
    "third_party/blink/renderer/core/input/event_handler.cc",
    [
      [
        "  return FindBestTouchAdjustmentCandidate(candidate_type, adjusted_node,\n                                          adjusted_point, touch_hotspot,\n                                          touch_rect, nodes);",
        "  // The integer broad-phase envelope can contain points outside a fractional\n  // or transformed touch region. Reject those points during selection, so they\n  // cannot suppress another eligible target with a valid snapped point.\n  const auto is_valid_point = [this, &location](const gfx::Point& point) {\n    return location.ContainsPoint(\n        gfx::PointF(frame_->View()->ConvertFromRootFrame(point)));\n  };\n  return FindBestTouchAdjustmentCandidate(candidate_type, adjusted_node,\n                                          adjusted_point, touch_hotspot,\n                                          touch_rect, nodes, is_valid_point);"
      ]
    ]
  ],
  [
    "base/android/library_loader/library_loader_hooks.cc",
    [
      [
        "#include \"base/android/jni_android.h\"",
        "#include \"base/android/android_info.h\"\n#include \"base/android/jni_android.h\""
      ],
      [
        "#include \"base/debug/asan_service.h\"",
        "#include \"base/debug/asan_service.h\"\n#include \"build/build_config.h\""
      ],
      [
        "LibraryProcessType g_library_process_type = PROCESS_UNINITIALIZED;\n",
        "LibraryProcessType g_library_process_type = PROCESS_UNINITIALIZED;\n\nbool ShouldHookJniFindClass() {\n#if defined(ARCH_CPU_ARM64)\n  // A translated ARM library shares a JNIEnv table with host ART. Installing\n  // a raw ARM function pointer lets host code jump into guest instructions.\n  // Keep Native Bridge's JNI table on x86 hosts; native ARM phones retain the\n  // normal Chromium class-loader hook used by the Cardboard SDK.\n  return android_info::abi_name().find(\"x86\") == std::string::npos;\n#else\n  return true;\n#endif\n}\n"
      ],
      [
        "  if (result && library_process_type == PROCESS_BROWSER) {",
        "  if (result && library_process_type == PROCESS_BROWSER &&\n      ShouldHookJniFindClass()) {"
      ]
    ]
  ]
]) {
  if (replacements.has(target)) throw new Error('Duplicate stability patch: ' + target);
  replacements.set(target, changes);
}

// Platform ELF registration for translated ARM libraries, including App Zygote.
for (const [target, changes] of [
  [
    "base/android/java/src/org/chromium/base/library_loader/LibraryLoader.java",
    [
      [
        "    private boolean useChromiumLinker() {\n        return mUseChromiumLinker;\n    }\n",
        "    private boolean useChromiumLinker() {\n        // Register translated libraries through the Android platform loader.\n        return mUseChromiumLinker && !isArmLibraryOnX86Host();\n    }\n\n    /** Whether this build's ARM library runs through Native Bridge on an x86 host. */\n    public static boolean isArmLibraryOnX86Host() {\n        return NativeLibraries.sCpuFamily == NativeLibraries.CPU_FAMILY_ARM\n                && Build.SUPPORTED_ABIS.length > 0\n                && (Build.SUPPORTED_ABIS[0].equals(\"x86_64\")\n                        || Build.SUPPORTED_ABIS[0].equals(\"x86\"));\n    }\n"
      ]
    ]
  ]
]) {
  if (replacements.has(target)) throw new Error('Duplicate Native Bridge loader patch: ' + target);
  replacements.set(target, changes);
}

// Retain sandboxing while selecting the existing non-preforked Chrome service.
for (const [target, changes] of [
  [
    "base/android/java/src/org/chromium/base/process_launcher/ChildConnectionAllocator.java",
    [
      [
        "import org.chromium.base.SysUtils;",
        "import org.chromium.base.SysUtils;\nimport org.chromium.base.library_loader.LibraryLoader;"
      ],
      [
        "        disableZygote = SysUtils.isLowEndDevice() || disableZygote;",
        "        // Use the existing isolated non-Zygote variant for translated Chrome\n        // renderers. WebView's separate Zygote service selection is preserved.\n        boolean translatedChromeService =\n                LibraryLoader.isArmLibraryOnX86Host()\n                        && serviceClassName.equals(\"org.chromium.content.app.SandboxedProcessService\");\n        disableZygote = SysUtils.isLowEndDevice() || disableZygote || translatedChromeService;"
      ],
      [
        "        boolean forceZygote = !bindAsExternalService;",
        "        boolean forceZygote = !bindAsExternalService && !translatedChromeService;"
      ]
    ]
  ]
]) {
  if (replacements.has(target)) throw new Error('Duplicate Native Bridge child patch: ' + target);
  replacements.set(target, changes);
}

// Authenticated SOCKS5: exact pinned protocol and Android credential hooks.
for (const [target, changes] of [
  [
    "net/android/java/src/org/chromium/net/AndroidNetworkLibrary.java",
    [
      [
        "import java.util.List;\n\n/** This class implements net utilities required by the net component. */\n@NullMarked\nclass AndroidNetworkLibrary {\n    private static final String TAG = \"AndroidNetworkLibrary\";\n\n    // Cached value indicating if app has ACCESS_NETWORK_STATE permission.\n    private static @Nullable Boolean sHaveAccessNetworkState;\n    // Cached value indicating if app has ACCESS_WIFI_STATE permission.\n",
        "import java.util.List;\n\n/** This class implements net utilities required by the net component. */\n@NullMarked\npublic class AndroidNetworkLibrary {\n    private static final String TAG = \"AndroidNetworkLibrary\";\n\n    /** Bound once by the owning browser environment, before network initialization. */\n    public interface LanduSocks5Credentials {\n        String[] get(String host, int port);\n    }\n\n    private static final Object LANDU_SOCKS5_LOCK = new Object();\n    private static volatile @Nullable LanduSocks5Credentials sLanduSocks5Credentials;\n\n    public static void bindLanduSocks5Credentials(LanduSocks5Credentials provider) {\n        synchronized (LANDU_SOCKS5_LOCK) {\n            if (sLanduSocks5Credentials != null) throw new IllegalStateException(\"Proxy owner already bound\");\n            sLanduSocks5Credentials = java.util.Objects.requireNonNull(provider);\n        }\n    }\n\n    @CalledByNative\n    private static String[] getLanduSocks5Credentials(String host, int port) {\n        LanduSocks5Credentials provider = sLanduSocks5Credentials;\n        if (provider == null) {\n            Context application = ContextUtils.getApplicationContextUnsafe();\n            // An unexpected Landu network process must fail closed, rather\n            // than silently omit an environment's configured authentication.\n            return application != null && \"com.landu.browser\".equals(application.getPackageName())\n                    ? new String[]{\"\"} : new String[0];\n        }\n        try {\n            return provider.get(host, port);\n        } catch (RuntimeException unavailable) {\n            // Deny rather than disclose the exception or try an anonymous route.\n            return new String[]{\"\"};\n        }\n    }\n\n    // Cached value indicating if app has ACCESS_NETWORK_STATE permission.\n    private static @Nullable Boolean sHaveAccessNetworkState;\n    // Cached value indicating if app has ACCESS_WIFI_STATE permission.\n"
      ]
    ]
  ],
  [
    "net/android/network_library.cc",
    [
      [
        "using base::android::ToJavaArrayOfByteArray;\nusing base::android::ToJavaByteArray;\n\nnamespace net::android {\n\nstd::vector<std::string> GetUserAddedRoots() {\n  std::vector<std::string> roots;\n  JNIEnv* env = AttachCurrentThread();\n",
        "using base::android::ToJavaArrayOfByteArray;\nusing base::android::ToJavaByteArray;\n\nnamespace net::android {\n\nstd::vector<std::string> GetLanduSocks5Credentials(std::string_view host,\n                                                  uint16_t port) {\n  JNIEnv* env = AttachCurrentThread();\n  auto values = Java_AndroidNetworkLibrary_getLanduSocks5Credentials(\n      env, ConvertUTF8ToJavaString(env, host), static_cast<jint>(port));\n  std::vector<std::string> result;\n  if (values.is_null()) return {\"\"};\n  base::android::AppendJavaStringArrayToStringVector(env, values, &result);\n  return result;\n}\n\nstd::vector<std::string> GetUserAddedRoots() {\n  std::vector<std::string> roots;\n  JNIEnv* env = AttachCurrentThread();\n"
      ]
    ]
  ],
  [
    "net/android/network_library.h",
    [
      [
        "#include \"net/base/network_handle.h\"\n#include \"net/socket/socket_descriptor.h\"\n\nnamespace net::android {\n\n// Get the list of user-added roots from Android.\n// |roots| is a list of DER-encoded user-added roots from Android.\nstd::vector<std::string> GetUserAddedRoots();\n",
        "#include \"net/base/network_handle.h\"\n#include \"net/socket/socket_descriptor.h\"\n\nnamespace net::android {\n\n// Empty: anonymous proxy; one entry: deny; two: exact endpoint username/password.\n// These values must never be attached to NetLog or command-line switches.\nNET_EXPORT_PRIVATE std::vector<std::string> GetLanduSocks5Credentials(\n    std::string_view host, uint16_t port);\n\n// Get the list of user-added roots from Android.\n// |roots| is a list of DER-encoded user-added roots from Android.\nstd::vector<std::string> GetUserAddedRoots();\n"
      ]
    ]
  ],
  [
    "net/socket/socks5_client_socket.cc",
    [
      [
        "#include \"net/socket/socks5_client_socket.h\"\n\n#include <stdint.h>\n\n#include <array>\n#include <utility>\n\n#include \"base/compiler_specific.h\"\n",
        "#include \"net/socket/socks5_client_socket.h\"\n\n#include <stdint.h>\n\n#include <algorithm>\n#include <array>\n#include <utility>\n\n#include \"base/compiler_specific.h\"\n"
      ],
      [
        "      traffic_annotation_(traffic_annotation) {}\n\nSOCKS5ClientSocket::~SOCKS5ClientSocket() {\n  Disconnect();\n}\n\nint SOCKS5ClientSocket::Connect(CompletionOnceCallback callback) {\n  DCHECK(transport_socket_);\n",
        "      traffic_annotation_(traffic_annotation) {}\n\nSOCKS5ClientSocket::~SOCKS5ClientSocket() {\n  Disconnect();\n  std::fill(username_.begin(), username_.end(), '\\0');\n  std::fill(password_.begin(), password_.end(), '\\0');\n}\n\nbool SOCKS5ClientSocket::SetUsernamePassword(const std::string& username,\n                                             const std::string& password) {\n  if (next_state_ != STATE_NONE || completed_handshake_ ||\n      username.empty() || username.size() > 255 ||\n      password.empty() || password.size() > 255) return false;\n  username_ = username;\n  password_ = password;\n  requires_authentication_ = true;\n  return true;\n}\n\nint SOCKS5ClientSocket::Connect(CompletionOnceCallback callback) {\n  DCHECK(transport_socket_);\n"
      ],
      [
        "        rv = DoGreetReadComplete(rv);\n        net_log_.EndEventWithNetErrorCode(NetLogEventType::SOCKS5_GREET_READ,\n                                          rv);\n        break;\n      case STATE_HANDSHAKE_WRITE:\n        DCHECK_EQ(OK, rv);\n        net_log_.BeginEvent(NetLogEventType::SOCKS5_HANDSHAKE_WRITE);\n        rv = DoHandshakeWrite();\n",
        "        rv = DoGreetReadComplete(rv);\n        net_log_.EndEventWithNetErrorCode(NetLogEventType::SOCKS5_GREET_READ,\n                                          rv);\n        break;\n      case STATE_AUTH_WRITE:\n        rv = DoAuthWrite();\n        break;\n      case STATE_AUTH_WRITE_COMPLETE:\n        rv = DoAuthWriteComplete(rv);\n        break;\n      case STATE_AUTH_READ:\n        rv = DoAuthRead();\n        break;\n      case STATE_AUTH_READ_COMPLETE:\n        rv = DoAuthReadComplete(rv);\n        break;\n      case STATE_HANDSHAKE_WRITE:\n        DCHECK_EQ(OK, rv);\n        net_log_.BeginEvent(NetLogEventType::SOCKS5_HANDSHAKE_WRITE);\n        rv = DoHandshakeWrite();\n"
      ],
      [
        "}\n\nstatic constexpr std::array<uint8_t, 3> kSOCKS5GreetWriteData{\n    0x05, 0x01, 0x00};  // no authentication\n\nint SOCKS5ClientSocket::DoGreetWrite() {\n  // Since we only have 1 byte to send the hostname length in, if the\n  // URL has a hostname longer than 255 characters we can't send it.\n",
        "}\n\nstatic constexpr std::array<uint8_t, 3> kSOCKS5GreetWriteData{\n    0x05, 0x01, 0x00};  // no authentication\nstatic constexpr std::array<uint8_t, 3> kSOCKS5AuthenticatedGreet{\n    0x05, 0x01, 0x02};  // Offer only username/password; never downgrade.\n\nint SOCKS5ClientSocket::DoGreetWrite() {\n  // Since we only have 1 byte to send the hostname length in, if the\n  // URL has a hostname longer than 255 characters we can't send it.\n"
      ],
      [
        "  }\n\n  if (!write_buf_) {\n    auto greet_buffer =\n        base::MakeRefCounted<WrappedIOBuffer>(kSOCKS5GreetWriteData);\n    write_buf_ = base::MakeRefCounted<DrainableIOBuffer>(\n        std::move(greet_buffer), greet_buffer->size());\n  }\n\n",
        "  }\n\n  if (!write_buf_) {\n    auto greet_buffer =\n        base::MakeRefCounted<WrappedIOBuffer>(requires_authentication_\n            ? kSOCKS5AuthenticatedGreet : kSOCKS5GreetWriteData);\n    write_buf_ = base::MakeRefCounted<DrainableIOBuffer>(\n        std::move(greet_buffer), greet_buffer->size());\n  }\n\n"
      ],
      [
        "    net_log_.AddEventWithIntParams(NetLogEventType::SOCKS_UNEXPECTED_VERSION,\n                                   \"version\", read_data[0]);\n    return ERR_SOCKS_CONNECTION_FAILED;\n  }\n  if (read_data[1] != 0x00) {\n    net_log_.AddEventWithIntParams(NetLogEventType::SOCKS_UNEXPECTED_AUTH,\n                                   \"method\", read_data[1]);\n    return ERR_SOCKS_CONNECTION_FAILED;\n  }\n\n  read_buf_.reset();\n  next_state_ = STATE_HANDSHAKE_WRITE;\n  return OK;\n}\n",
        "    net_log_.AddEventWithIntParams(NetLogEventType::SOCKS_UNEXPECTED_VERSION,\n                                   \"version\", read_data[0]);\n    return ERR_SOCKS_CONNECTION_FAILED;\n  }\n  if (read_data[1] != (requires_authentication_ ? 0x02 : 0x00)) {\n    net_log_.AddEventWithIntParams(NetLogEventType::SOCKS_UNEXPECTED_AUTH,\n                                   \"method\", read_data[1]);\n    return ERR_SOCKS_CONNECTION_FAILED;\n  }\n\n  read_buf_.reset();\n  next_state_ = requires_authentication_ ? STATE_AUTH_WRITE : STATE_HANDSHAKE_WRITE;\n  return OK;\n}\n\nint SOCKS5ClientSocket::DoAuthWrite() {\n  if (!write_buf_) {\n    std::vector<uint8_t> frame;\n    frame.reserve(3 + username_.size() + password_.size());\n    frame.push_back(0x01);\n    frame.push_back(base::checked_cast<uint8_t>(username_.size()));\n    frame.insert(frame.end(), username_.begin(), username_.end());\n    frame.push_back(base::checked_cast<uint8_t>(password_.size()));\n    frame.insert(frame.end(), password_.begin(), password_.end());\n    auto data = base::MakeRefCounted<VectorIOBuffer>(std::move(frame));\n    write_buf_ = base::MakeRefCounted<DrainableIOBuffer>(data, data->size());\n  }\n  next_state_ = STATE_AUTH_WRITE_COMPLETE;\n  return transport_socket_->Write(write_buf_.get(), write_buf_->BytesRemaining(),\n                                  io_callback_, traffic_annotation_);\n}\n\nint SOCKS5ClientSocket::DoAuthWriteComplete(int result) {\n  if (result < 0) return result;\n  write_buf_->DidConsume(result);\n  if (write_buf_->BytesRemaining() == 0) {\n    write_buf_.reset();\n    next_state_ = STATE_AUTH_READ;\n  } else {\n    next_state_ = STATE_AUTH_WRITE;\n  }\n  return OK;\n}\n\nint SOCKS5ClientSocket::DoAuthRead() {\n  if (!read_buf_) {\n    read_buf_ = base::MakeRefCounted<GrowableIOBuffer>();\n    read_buf_->SetCapacity(2);\n  }\n  next_state_ = STATE_AUTH_READ_COMPLETE;\n  return transport_socket_->Read(read_buf_.get(), read_buf_->RemainingCapacity(),\n                                 io_callback_);\n}\n\nint SOCKS5ClientSocket::DoAuthReadComplete(int result) {\n  if (result < 0) return result;\n  if (result == 0) return ERR_PROXY_AUTH_REQUESTED;\n  read_buf_->set_offset(read_buf_->offset() + result);\n  if (read_buf_->RemainingCapacity() > 0) {\n    next_state_ = STATE_AUTH_READ;\n    return OK;\n  }\n  auto response = read_buf_->span_before_offset();\n  if (response[0] != 0x01 || response[1] != 0x00)\n    return ERR_PROXY_AUTH_REQUESTED;\n  read_buf_.reset();\n  next_state_ = STATE_HANDSHAKE_WRITE;\n  return OK;\n}\n"
      ]
    ]
  ],
  [
    "net/socket/socks5_client_socket.h",
    [
      [
        "class DrainableIOBuffer;\nclass GrowableIOBuffer;\n\n// This StreamSocket is used to setup a SOCKSv5 handshake with a socks proxy.\n// Currently no SOCKSv5 authentication is supported.\nclass NET_EXPORT_PRIVATE SOCKS5ClientSocket : public StreamSocket {\n public:\n  // |destination| contains the hostname and port to which the socket above will\n  // communicate to via the SOCKS layer.\n",
        "class DrainableIOBuffer;\nclass GrowableIOBuffer;\n\n// This StreamSocket is used to setup a SOCKSv5 handshake with a socks proxy.\n// Optional RFC 1929 credentials are bound before Connect(), never embedded in URLs.\nclass NET_EXPORT_PRIVATE SOCKS5ClientSocket : public StreamSocket {\n public:\n  // |destination| contains the hostname and port to which the socket above will\n  // communicate to via the SOCKS layer.\n"
      ],
      [
        "  SOCKS5ClientSocket& operator=(const SOCKS5ClientSocket&) = delete;\n\n  // On destruction Disconnect() is called.\n  ~SOCKS5ClientSocket() override;\n\n  // StreamSocket implementation.\n\n  // Does the SOCKS handshake and completes the protocol.\n",
        "  SOCKS5ClientSocket& operator=(const SOCKS5ClientSocket&) = delete;\n\n  // On destruction Disconnect() is called.\n  ~SOCKS5ClientSocket() override;\n\n  // Only before Connect(). Lengths are UTF-8 bytes, as required by RFC 1929.\n  bool SetUsernamePassword(const std::string& username,\n                           const std::string& password);\n\n  // StreamSocket implementation.\n\n  // Does the SOCKS handshake and completes the protocol.\n"
      ],
      [
        "    STATE_GREET_WRITE,\n    STATE_GREET_WRITE_COMPLETE,\n    STATE_GREET_READ,\n    STATE_GREET_READ_COMPLETE,\n    STATE_HANDSHAKE_WRITE,\n    STATE_HANDSHAKE_WRITE_COMPLETE,\n    STATE_HANDSHAKE_READ,\n    STATE_HANDSHAKE_READ_COMPLETE,\n",
        "    STATE_GREET_WRITE,\n    STATE_GREET_WRITE_COMPLETE,\n    STATE_GREET_READ,\n    STATE_GREET_READ_COMPLETE,\n    STATE_AUTH_WRITE,\n    STATE_AUTH_WRITE_COMPLETE,\n    STATE_AUTH_READ,\n    STATE_AUTH_READ_COMPLETE,\n    STATE_HANDSHAKE_WRITE,\n    STATE_HANDSHAKE_WRITE_COMPLETE,\n    STATE_HANDSHAKE_READ,\n    STATE_HANDSHAKE_READ_COMPLETE,\n"
      ],
      [
        "  int DoGreetRead();\n  int DoGreetReadComplete(int result);\n  int DoGreetWrite();\n  int DoGreetWriteComplete(int result);\n\n  // Creates a DrainableIOBuffer containing the SOCKS handshake.\n  scoped_refptr<DrainableIOBuffer> BuildHandshakeWriteBuffer() const;\n\n",
        "  int DoGreetRead();\n  int DoGreetReadComplete(int result);\n  int DoGreetWrite();\n  int DoGreetWriteComplete(int result);\n  int DoAuthWrite();\n  int DoAuthWriteComplete(int result);\n  int DoAuthRead();\n  int DoAuthReadComplete(int result);\n\n  // Creates a DrainableIOBuffer containing the SOCKS handshake.\n  scoped_refptr<DrainableIOBuffer> BuildHandshakeWriteBuffer() const;\n\n"
      ],
      [
        "  // overlying connection is free to communicate.\n  bool completed_handshake_ = false;\n\n  bool was_ever_used_ = false;\n\n  const HostPortPair destination_;\n\n  NetLogWithSource net_log_;\n",
        "  // overlying connection is free to communicate.\n  bool completed_handshake_ = false;\n\n  bool was_ever_used_ = false;\n  bool requires_authentication_ = false;\n  std::string username_;\n  std::string password_;\n\n  const HostPortPair destination_;\n\n  NetLogWithSource net_log_;\n"
      ]
    ]
  ],
  [
    "net/socket/socks5_client_socket_unittest.cc",
    [
      [
        "#include <iterator>\n#include <map>\n#include <memory>\n#include <utility>\n\n#include \"base/containers/span.h\"\n#include \"base/memory/ptr_util.h\"\n#include \"base/memory/raw_ptr.h\"\n",
        "#include <iterator>\n#include <map>\n#include <memory>\n#include <utility>\n#include <vector>\n\n#include \"base/containers/span.h\"\n#include \"base/memory/ptr_util.h\"\n#include \"base/memory/raw_ptr.h\"\n"
      ],
      [
        "  // non-owning pointer to it.\n  return std::make_unique<SOCKS5ClientSocket>(std::move(tcp_sock),\n                                              HostPortPair(hostname, port),\n                                              TRAFFIC_ANNOTATION_FOR_TESTS);\n}\n\n// Tests a complete SOCKS5 handshake and the disconnection.\nTEST_F(SOCKS5ClientSocketTest, CompleteHandshake) {\n",
        "  // non-owning pointer to it.\n  return std::make_unique<SOCKS5ClientSocket>(std::move(tcp_sock),\n                                              HostPortPair(hostname, port),\n                                              TRAFFIC_ANNOTATION_FOR_TESTS);\n}\n\n// Real socket state machine: authenticated success, failure, and no downgrade.\nTEST_F(SOCKS5ClientSocketTest, LanduUsernamePasswordAuthentication) {\n  for (IoMode io : {SYNCHRONOUS, ASYNC}) {\n    for (int mode = 0; mode < 5; ++mode) {\n      user_sock_.reset();\n      const std::string greet(\"\\x05\\x01\\x02\", 3);\n      const std::string credentials(\"\\x01\\x04user\\x04pass\", 11);\n      const std::string connect(\"\\x05\\x01\\x00\\x03\\x09localhost\\x00\\x50\", 16);\n      const std::string version(1, mode == 3 ? '\\x05' : '\\x01');\n      const std::string status(1, mode == 1 ? '\\x01' : '\\x00');\n      const std::string method(1, mode == 2 ? '\\x00' : '\\x02');\n      std::vector<MockWrite> writes{MockWrite(io, greet)};\n      std::vector<MockRead> reads{MockRead(io, std::string_view(\"\\x05\", 1)),\n                                  MockRead(io, method)};\n      if (mode != 2) {\n        writes.emplace_back(io, credentials);\n        reads.emplace_back(io, version);\n        if (mode == 4) reads.emplace_back(io, 0);\n        else reads.emplace_back(io, status);\n      }\n      if (mode == 0) {\n        writes.emplace_back(io, connect);\n        reads.emplace_back(io, kSOCKS5OkResponse);\n      }\n      user_sock_ = BuildMockSocket(reads, writes, \"localhost\", 80, nullptr);\n      ASSERT_TRUE(user_sock_->SetUsernamePassword(\"user\", \"pass\"));\n      int result = user_sock_->Connect(callback_.callback());\n      if (result == ERR_IO_PENDING) result = callback_.WaitForResult();\n      if (mode == 0) {\n        EXPECT_THAT(result, IsOk());\n        EXPECT_TRUE(user_sock_->IsConnected());\n      } else {\n        EXPECT_LT(result, 0);\n        EXPECT_FALSE(user_sock_->IsConnected());\n      }\n      EXPECT_TRUE(data_->AllReadDataConsumed());\n      EXPECT_TRUE(data_->AllWriteDataConsumed());\n    }\n  }\n}\n\nTEST_F(SOCKS5ClientSocketTest, LanduCredentialByteLimits) {\n  MockRead reads[] = {MockRead()};\n  MockWrite writes[] = {MockWrite()};\n  user_sock_ = BuildMockSocket(reads, writes, \"localhost\", 80, nullptr);\n  EXPECT_FALSE(user_sock_->SetUsernamePassword(\"\", \"pass\"));\n  EXPECT_FALSE(user_sock_->SetUsernamePassword(\"user\", \"\"));\n  EXPECT_FALSE(user_sock_->SetUsernamePassword(std::string(256, 'u'), \"pass\"));\n  EXPECT_FALSE(user_sock_->SetUsernamePassword(\"user\", std::string(256, 'p')));\n  EXPECT_TRUE(user_sock_->SetUsernamePassword(std::string(255, 'u'), std::string(255, 'p')));\n}\n\n// Tests a complete SOCKS5 handshake and the disconnection.\nTEST_F(SOCKS5ClientSocketTest, CompleteHandshake) {\n"
      ]
    ]
  ],
  [
    "net/socket/socks_connect_job.cc",
    [
      [
        "// found in the LICENSE file.\n\n#include \"net/socket/socks_connect_job.h\"\n\n#include <memory>\n#include <utility>\n\n#include \"base/functional/bind.h\"\n#include \"net/base/net_errors.h\"\n#include \"net/log/net_log_source_type.h\"\n#include \"net/log/net_log_with_source.h\"\n#include \"net/socket/client_socket_factory.h\"\n",
        "// found in the LICENSE file.\n\n#include \"net/socket/socks_connect_job.h\"\n\n#include <algorithm>\n#include <memory>\n#include <utility>\n\n#include \"base/functional/bind.h\"\n#include \"build/build_config.h\"\n#if BUILDFLAG(IS_ANDROID)\n#include \"net/android/network_library.h\"\n#endif\n#include \"net/base/net_errors.h\"\n#include \"net/log/net_log_source_type.h\"\n#include \"net/log/net_log_with_source.h\"\n#include \"net/socket/client_socket_factory.h\"\n"
      ],
      [
        "  next_state_ = STATE_SOCKS_CONNECT_COMPLETE;\n\n  // Add a SOCKS connection on top of the tcp socket.\n  if (socks_params_->is_socks_v5()) {\n    socket_ = std::make_unique<SOCKS5ClientSocket>(\n        transport_connect_job_->PassSocket(), socks_params_->destination(),\n        socks_params_->traffic_annotation());\n  } else {\n    auto socks_socket = std::make_unique<SOCKSClientSocket>(\n        transport_connect_job_->PassSocket(), socks_params_->destination(),\n        socks_params_->network_anonymization_key(), priority(), host_resolver(),\n",
        "  next_state_ = STATE_SOCKS_CONNECT_COMPLETE;\n\n  // Add a SOCKS connection on top of the tcp socket.\n  if (socks_params_->is_socks_v5()) {\n    auto socket = std::make_unique<SOCKS5ClientSocket>(\n        transport_connect_job_->PassSocket(), socks_params_->destination(),\n        socks_params_->traffic_annotation());\n#if BUILDFLAG(IS_ANDROID)\n    // Resolve credentials against the configured proxy host, never the target\n    // website or a DNS-resolved alias of the proxy.\n    const auto proxy = std::visit([](const auto& endpoint) {\n      return HostPortPair(endpoint.host(), endpoint.port());\n    }, socks_params_->transport_params()->destination());\n    auto credentials = android::GetLanduSocks5Credentials(proxy.host(), proxy.port());\n    if (!credentials.empty() &&\n        (credentials.size() != 2 ||\n         !socket->SetUsernamePassword(credentials[0], credentials[1]))) {\n      return ERR_PROXY_AUTH_REQUESTED;\n    }\n    for (auto& value : credentials) std::fill(value.begin(), value.end(), '\\0');\n#endif\n    socket_ = std::move(socket);\n  } else {\n    auto socks_socket = std::make_unique<SOCKSClientSocket>(\n        transport_connect_job_->PassSocket(), socks_params_->destination(),\n        socks_params_->network_anonymization_key(), priority(), host_resolver(),\n"
      ]
    ]
  ]
]) {
  if (replacements.has(target)) throw new Error("Duplicate SOCKS5 patch: " + target);
  replacements.set(target, changes);
}

// Process-fixed UA-CH and native animation callback policy.
for (const [target, changes] of [
  [
    "components/embedder_support/user_agent_utils.cc",
    [
      [
        "#include \"build/build_config.h\"\n#include \"components/embedder_support/pref_names.h\"\n#include \"components/embedder_support/landu_android_identity.h\"\n#include \"components/embedder_support/switches.h\"\n#include \"components/policy/core/common/policy_pref_names.h\"\n#include \"components/prefs/pref_service.h\"\n",
        "#include \"build/build_config.h\"\n#include \"components/embedder_support/pref_names.h\"\n#include \"components/embedder_support/landu_android_identity.h\"\n#include \"components/embedder_support/landu_user_agent_metadata.h\"\n#include \"components/embedder_support/switches.h\"\n#include \"components/policy/core/common/policy_pref_names.h\"\n#include \"components/prefs/pref_service.h\"\n"
      ],
      [
        "}\n\nblink::UserAgentMetadata GetUserAgentMetadata(bool only_low_entropy_ch) {\n  blink::UserAgentMetadata metadata;\n\n  // Low entropy client hints.\n",
        "}\n\nblink::UserAgentMetadata GetUserAgentMetadata(bool only_low_entropy_ch) {\n  // The process-fixed, validated environment identity is shared by network,\n  // Window, iframe and worker consumers of this metadata factory.\n  static const base::NoDestructor<std::optional<blink::UserAgentMetadata>>\n      landu_metadata([]() -> std::optional<blink::UserAgentMetadata> {\n        const auto* command = base::CommandLine::ForCurrentProcess();\n        if (!command->HasSwitch(\"landu-user-agent-metadata\")) return std::nullopt;\n        auto parsed = landu::ParseUserAgentMetadata(\n            command->GetSwitchValueASCII(\"landu-user-agent-metadata\"));\n        CHECK(parsed.has_value());\n        CHECK(GetUserAgentFromCommandLine().has_value());\n        return parsed;\n      }());\n  if (landu_metadata->has_value()) {\n    if (!only_low_entropy_ch) return landu_metadata->value();\n    blink::UserAgentMetadata low;\n    low.brand_version_list = landu_metadata->value().brand_version_list;\n    low.platform = landu_metadata->value().platform;\n    low.mobile = landu_metadata->value().mobile;\n    return low;\n  }\n  blink::UserAgentMetadata metadata;\n\n  // Low entropy client hints.\n"
      ]
    ]
  ],
  [
    "components/embedder_support/BUILD.gn",
    [
      [
        "    \"user_agent_utils.cc\",\n    \"user_agent_utils.h\",\n    \"landu_android_identity.h\",\n  ]\n\n  deps = [\n",
        "    \"user_agent_utils.cc\",\n    \"user_agent_utils.h\",\n    \"landu_android_identity.h\",\n    \"landu_user_agent_metadata.h\",\n  ]\n\n  deps = [\n"
      ]
    ]
  ],
  [
    "chrome/browser/chrome_content_browser_client.cc",
    [
      [
        "      \"landu-media-audiooutput\",\n      \"landu-android-version\",\n      \"landu-android-model\",\n      \"landu-webgl-vendor\",\n      \"landu-webgl-renderer\",\n      \"landu-webgpu-vendor\",\n",
        "      \"landu-media-audiooutput\",\n      \"landu-android-version\",\n      \"landu-android-model\",\n      \"landu-user-agent-metadata\",\n      \"landu-navigator-platform\",\n      \"landu-raf-max-fps\",\n      \"landu-webgl-vendor\",\n      \"landu-webgl-renderer\",\n      \"landu-webgpu-vendor\",\n"
      ]
    ]
  ],
  [
    "third_party/blink/public/common/BUILD.gn",
    [
      [
        "    \"landu/landu_audio_noise.h\",\n    \"landu/landu_client_rects_noise.h\",\n    \"landu/landu_media_policy.h\",\n    \"dom/dom_node_id.h\",\n    \"dom_storage/session_storage_namespace_id.h\",\n    \"features.h\",\n",
        "    \"landu/landu_audio_noise.h\",\n    \"landu/landu_client_rects_noise.h\",\n    \"landu/landu_media_policy.h\",\n    \"landu/landu_raf_limiter.h\",\n    \"dom/dom_node_id.h\",\n    \"dom_storage/session_storage_namespace_id.h\",\n    \"features.h\",\n"
      ]
    ]
  ],
  [
    "third_party/blink/renderer/core/execution_context/navigator_base.cc",
    [
      [
        "#include \"third_party/blink/renderer/core/execution_context/navigator_base.h\"\n\n#include \"base/feature_list.h\"\n#include \"build/build_config.h\"\n#include \"third_party/blink/public/common/features.h\"\n#include \"third_party/blink/renderer/core/execution_context/execution_context.h\"\n",
        "#include \"third_party/blink/renderer/core/execution_context/navigator_base.h\"\n\n#include \"base/feature_list.h\"\n#include \"base/command_line.h\"\n#include \"build/build_config.h\"\n#include \"third_party/blink/public/common/features.h\"\n#include \"third_party/blink/renderer/core/execution_context/execution_context.h\"\n"
      ],
      [
        "}\n\nString NavigatorBase::platform() const {\n#if BUILDFLAG(IS_ANDROID)\n  // We need to check the ReduceUserAgentMinorVersion feature flag for\n  // Android WebView, which does not currently ship a reduced User-Agent.\n",
        "}\n\nString NavigatorBase::platform() const {\n  const auto* command = base::CommandLine::ForCurrentProcess();\n  if (command->HasSwitch(\"landu-user-agent-metadata\") &&\n      command->HasSwitch(\"landu-navigator-platform\")) {\n    return String::FromUtf8(command->GetSwitchValueASCII(\"landu-navigator-platform\"));\n  }\n#if BUILDFLAG(IS_ANDROID)\n  // We need to check the ReduceUserAgentMinorVersion feature flag for\n  // Android WebView, which does not currently ship a reduced User-Agent.\n"
      ]
    ]
  ],
  [
    "third_party/blink/renderer/core/dom/frame_request_callback_collection.cc",
    [
      [
        "\n#include \"third_party/blink/renderer/core/dom/frame_request_callback_collection.h\"\n\n#include \"third_party/blink/renderer/core/frame/web_feature.h\"\n#include \"third_party/blink/renderer/core/inspector/inspector_trace_events.h\"\n#include \"third_party/blink/renderer/core/probe/core_probes.h\"\n#include \"third_party/blink/renderer/platform/instrumentation/use_counter.h\"\n\nnamespace blink {\n\nFrameRequestCallbackCollection::FrameRequestCallbackCollection(\n    ExecutionContext* context)\n",
        "\n#include \"third_party/blink/renderer/core/dom/frame_request_callback_collection.h\"\n\n#include \"base/command_line.h\"\n#include \"base/strings/string_number_conversions.h\"\n#include \"third_party/blink/public/common/landu/landu_raf_limiter.h\"\n#include \"third_party/blink/renderer/core/frame/web_feature.h\"\n#include \"third_party/blink/renderer/core/inspector/inspector_trace_events.h\"\n#include \"third_party/blink/renderer/core/probe/core_probes.h\"\n#include \"third_party/blink/renderer/platform/instrumentation/use_counter.h\"\n\nnamespace blink {\nnamespace {\n\nclass LanduAnimationFrameState final\n    : public GarbageCollected<LanduAnimationFrameState>,\n      public Supplement<ExecutionContext> {\n public:\n  static constexpr char kSupplementName[] = \"LanduAnimationFrameState\";\n  explicit LanduAnimationFrameState(ExecutionContext& context)\n      : Supplement<ExecutionContext>(context) {}\n  static bool ShouldDispatch(ExecutionContext& context, double timestamp_ms) {\n    static const int max_fps = [] {\n      int value = 0;\n      return base::StringToInt(base::CommandLine::ForCurrentProcess()->GetSwitchValueASCII(\n                 \"landu-raf-max-fps\"), &value) && value >= 1 && value <= 240 ? value : 0;\n    }();\n    if (!max_fps) return true;\n    auto* state = Supplement<ExecutionContext>::From<LanduAnimationFrameState>(context);\n    if (!state) {\n      state = MakeGarbageCollected<LanduAnimationFrameState>(context);\n      ProvideTo(context, state);\n    }\n    return state->limiter_.ShouldDispatch(timestamp_ms, max_fps);\n  }\n  void Trace(Visitor* visitor) const override { Supplement<ExecutionContext>::Trace(visitor); }\n\n private:\n  landu::RafLimiter limiter_;\n};\n\n}  // namespace\n\nFrameRequestCallbackCollection::FrameRequestCallbackCollection(\n    ExecutionContext* context)\n"
      ],
      [
        "  // considered only for the \"next\" frame, not this one.\n  DCHECK(callbacks_to_invoke_.empty());\n  DCHECK(internal_callbacks_to_invoke_.empty());\n  swap(callbacks_to_invoke_, frame_callbacks_);\n  swap(internal_callbacks_to_invoke_, internal_frame_callbacks_);\n\n  ExecuteFrameCallbacksImpl(callbacks_to_invoke_, high_res_now_ms,\n",
        "  // considered only for the \"next\" frame, not this one.\n  DCHECK(callbacks_to_invoke_.empty());\n  DCHECK(internal_callbacks_to_invoke_.empty());\n  // Leave deferred web callbacks in the pending list, preserving their IDs,\n  // cancellation and next-frame registration semantics. Internal callbacks\n  // (e.g. browser work) are never subject to the web RAF policy.\n  if (!frame_callbacks_.empty() &&\n      LanduAnimationFrameState::ShouldDispatch(*context_, high_res_now_ms)) {\n    swap(callbacks_to_invoke_, frame_callbacks_);\n  }\n  swap(internal_callbacks_to_invoke_, internal_frame_callbacks_);\n\n  ExecuteFrameCallbacksImpl(callbacks_to_invoke_, high_res_now_ms,\n"
      ]
    ]
  ],
  [
    "third_party/blink/renderer/core/animation_frame/worker_animation_frame_provider.cc",
    [
      [
        "          provider->callback_collection_.ExecuteFrameCallbacks(time, time);\n        }\n        provider->begin_frame_provider_->FinishBeginFrame(args);\n      },\n      WrapWeakPersistent(this), args));\n}\n",
        "          provider->callback_collection_.ExecuteFrameCallbacks(time, time);\n        }\n        provider->begin_frame_provider_->FinishBeginFrame(args);\n        // A capped frame does not invoke JS, so JS cannot request the next\n        // BeginFrame. Keep pending callbacks alive without inventing a timer.\n        if (!provider->context_->IsContextDestroyed() &&\n            provider->callback_collection_.HasFrameCallback()) {\n          provider->begin_frame_provider_->RequestBeginFrame();\n        }\n      },\n      WrapWeakPersistent(this), args));\n}\n"
      ]
    ]
  ]
]) {
  if (replacements.has(target)) replacements.get(target).push(...changes);
  else replacements.set(target, changes);
}

// Graceful Google account fallback when the Android backend is unavailable.
for (const [target, changes] of [
  [
    "components/signin/public/android/java/src/org/chromium/components/signin/NullAccountManagerDelegate.java",
    [
      [
        "        throw new UnsupportedOperationException(\n                \"NullAccountManagerDelegate does not implement createAddAccountIntent\");",
        "        // Callers already handle an unavailable account backend through a null intent.\n        callback.onResult(null);"
      ]
    ]
  ],
  [
    "components/signin/public/android/java/src/org/chromium/components/signin/AccountManagerFacadeProvider.java",
    [
      [
        "        private static final AccountManagerFacade INSTANCE;",
        "        private static final AccountManagerFacade INSTANCE;\n        private static final boolean HAS_ACCOUNT_MANAGER;"
      ],
      [
        "                    ServiceLoaderUtil.maybeCreate(AccountManagerDelegate.class);\n            if (delegate == null) {",
        "                    ServiceLoaderUtil.maybeCreate(AccountManagerDelegate.class);\n            HAS_ACCOUNT_MANAGER = delegate != null;\n            if (delegate == null) {"
      ],
      [
        "    /** Sets the test instance. */",
        "    /** Whether this build has an Android account backend instead of the empty fallback. */\n    @AnyThread\n    public static boolean isAccountManagerAvailable() {\n        return sInstanceForTesting != null || Holder.HAS_ACCOUNT_MANAGER;\n    }\n\n    /** Sets the test instance. */"
      ]
    ]
  ],
  [
    "chrome/browser/ui/android/signin/java/src/org/chromium/chrome/browser/ui/signin/SigninUtils.java",
    [
      [
        "import android.app.Activity;",
        "import android.app.Activity;\nimport android.app.AlertDialog;"
      ],
      [
        "import android.content.res.Configuration;",
        "import android.content.res.Configuration;\nimport android.net.Uri;"
      ],
      [
        "import org.chromium.components.sync.UserActionableError;",
        "import org.chromium.components.signin.AccountManagerFacadeProvider;\nimport org.chromium.components.sync.UserActionableError;"
      ],
      [
        "    public static boolean openSettingsForAllAccounts(Activity activity) {\n        Intent intent = new Intent(Settings.ACTION_SYNC_SETTINGS);",
        "    public static boolean openSettingsForAllAccounts(Activity activity) {\n        if (!AccountManagerFacadeProvider.isAccountManagerAvailable()) {\n            if (activity.isFinishing() || activity.isDestroyed()) return false;\n            new AlertDialog.Builder(activity)\n                    .setTitle(\"Google 网页登录\")\n                    .setMessage(\"当前版本支持在环境内登录 Google 网页。浏览器账号同步尚未接入，书签和密码保存在当前环境内。\")\n                    .setNegativeButton(android.R.string.cancel, null)\n                    .setPositiveButton(\n                            \"打开网页登录\",\n                            (dialog, which) -> {\n                                Intent webSignin = new Intent(\n                                        Intent.ACTION_VIEW, Uri.parse(\"https://accounts.google.com/\"));\n                                // An explicit in-process activity keeps the selected environment.\n                                webSignin.setClassName(\n                                        activity,\n                                        \"org.chromium.chrome.browser.ChromeTabbedActivity\");\n                                webSignin.addFlags(\n                                        Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);\n                                if (!IntentUtils.safeStartActivity(activity, webSignin)) {\n                                    org.chromium.ui.widget.Toast.makeText(\n                                                    activity,\n                                                    \"暂时无法打开 Google 网页，请在地址栏访问 accounts.google.com\",\n                                                    org.chromium.ui.widget.Toast.LENGTH_LONG)\n                                            .show();\n                                }\n                            })\n                    .show();\n            return true;\n        }\n        Intent intent = new Intent(Settings.ACTION_SYNC_SETTINGS);"
      ]
    ]
  ]
]) {
  replacements.set(target, changes);
}

// User-triggered website translation when the native service is unavailable.
// Native translation when configured, otherwise on-device text translation.
replacements.set("chrome/browser/language/android/java/src/org/chromium/chrome/browser/translate/TranslateBridge.java", [["import org.jni_zero.CalledByNative;", "import android.net.Uri;\n\nimport org.jni_zero.CalledByNative;"], ["        RecordUserAction.record(\"Android.ManualTranslate\");\n        TranslateBridgeJni.get().manualTranslateWhenReady(assertNonNull(tab.getWebContents()));", "        RecordUserAction.record(\"Android.ManualTranslate\");\n        WebContents contents = tab.getWebContents();\n        if (contents == null || tab.isDestroyed()) return;\n        if (canManuallyTranslate(contents, false)) {\n            TranslateBridgeJni.get().manualTranslateWhenReady(contents);\n        } else if (canTranslateInPlace(tab)) {\n            LanduPageTranslator.translate(tab);\n        }"], ["    public static boolean canManuallyTranslate(Tab tab, boolean menuLogging) {\n        return canManuallyTranslate(assertNonNull(tab.getWebContents()), menuLogging);\n    }", "    public static boolean canManuallyTranslate(Tab tab, boolean menuLogging) {\n        WebContents contents = tab.getWebContents();\n        return contents != null\n                && (canManuallyTranslate(contents, menuLogging) || canTranslateInPlace(tab));\n    }"], ["    /** Returns true iff we're in a state where the manual translate IPH could be shown. */", "    private static boolean canTranslateInPlace(Tab tab) {\n        if (tab.isDestroyed() || tab.isNativePage() || tab.getWebContents() == null) return false;\n        String scheme = Uri.parse(tab.getUrl().getSpec()).getScheme();\n        return \"https\".equals(scheme) || \"http\".equals(scheme);\n    }\n\n    /** Returns true iff we're in a state where the manual translate IPH could be shown. */"]]);

replacements.set("chrome/browser/language/android/BUILD.gn", [["    \"java/src/org/chromium/chrome/browser/translate/TranslateBridge.java\",\n    \"java/src/org/chromium/chrome/browser/translate/TranslationObserver.java\",\n  ]\n  deps = [", "    \"java/src/org/chromium/chrome/browser/translate/TranslateBridge.java\",\n    \"java/src/org/chromium/chrome/browser/translate/LanduPageTranslator.java\",\n    \"java/src/org/chromium/chrome/browser/translate/TranslationObserver.java\",\n  ]\n  deps = ["], ["    \":base_module_java\",\n    \":java_resources\",\n    \"//base:base_java\",\n    \"//base:ui_java\",", "    \":base_module_java\",\n    \":java_resources\",\n    \"//third_party/android_deps:landu_translation_java\",\n    \"$google_play_services_package:google_play_services_tasks_java\",\n    \"//third_party/android_deps:com_google_mlkit_common_java\",\n    \"//base:base_java\",\n    \"//base:ui_java\","], ["    \"//ui/accessibility:ax_base_java\",\n    \"//ui/android:ui_full_java\",\n    \"//ui/android:ui_no_recycler_view_java\",", "    \"//ui/accessibility:ax_base_java\",\n    \"//ui/android:ui_full_java\",\n    \"//ui/android:ui_no_recycler_view_java\",\n    \"//url:url_java\","]]);

replacements.set("third_party/android_deps/BUILD.gn", [["# Targets are declared inside a template", "\njava_prebuilt(\"landu_okhttp_java\") {\n  jar_path = \"//chrome/browser/language/android/landu_deps/okhttp-3.0.0.jar\"\n  deps = [ \":com_squareup_okio_okio_jvm_java\" ]\n}\n\nandroid_aar_prebuilt(\"landu_translation_java\") {\n  aar_path = \"//chrome/browser/language/android/landu_deps/translate-17.0.3.aar\"\n  info_path = \"//chrome/browser/language/android/landu_deps/translate.info\"\n  extract_native_libraries = true\n  deps = [\n    \":landu_okhttp_java\",\n    \"//third_party/android_deps:com_google_mlkit_common_java\",\n    \"$google_play_services_package:google_play_services_base_java\",\n    \"$google_play_services_package:google_play_services_basement_java\",\n    \"$google_play_services_package:google_play_services_tasks_java\",\n    \"//third_party/android_deps:google_android_datatransport_transport_api_java\",\n    \"//third_party/android_deps:google_android_datatransport_transport_backend_cct_java\",\n    \"//third_party/android_deps:google_android_datatransport_transport_runtime_java\",\n    \"//third_party/android_deps:google_firebase_firebase_components_java\",\n    \"//third_party/android_deps:google_firebase_firebase_encoders_java\",\n    \"//third_party/android_deps:google_firebase_firebase_encoders_json_java\",\n    \"//third_party/androidx:androidx_core_core_java\",\n  ]\n}\n\n# Targets are declared inside a template"]]);

replacements.get(apkTemplatePath).push(["    library_always_compress = []", "    # ML Kit's AAR extraction is separate from APK native-library packaging.\n    _landu_translate_unpack = \"//third_party/android_deps:landu_translation_J__unpack_aar\"\n    asset_deps += [ _landu_translate_unpack ]\n    _landu_translate_libs = get_label_info(_landu_translate_unpack, \"target_out_dir\") +\n                            \"/landu_translation_java/jni\"\n    if (android_64bit_target_cpu && !_is_64_bit_browser) {\n      secondary_abi_loadable_modules += [\n        \"$_landu_translate_libs/$android_app_secondary_abi/libtranslate_jni.so\",\n      ]\n    } else {\n      loadable_modules += [\n        \"$_landu_translate_libs/$android_app_abi/libtranslate_jni.so\",\n      ]\n    }\n\n    library_always_compress = []"]);

// Keep Android application identity independent of Chromium's actual engine version.
const landuRelease = JSON.parse(fs.readFileSync(new URL('../branding/product.json', import.meta.url), 'utf8'));
if (!/^[0-9]+(?:\.[0-9]+){2,4}$/.test(landuRelease.releaseVersion)
    || !Number.isInteger(landuRelease.androidVersionCode)
    || landuRelease.androidVersionCode < 1 || landuRelease.androidVersionCode > 2100000000) {
  throw new Error('Invalid Landu Android release version');
}
replacements.get(apkTemplatePath).push(
  ['      version_name = chrome_version_name\n', `      version_name = "${landuRelease.releaseVersion}"\n`],
  ['    _version_code = chrome_version_code\n', `    _version_code = "${landuRelease.androidVersionCode}"\n`],
);

// Adreno can label this exact advisory as ERROR | VALIDATION with no message ID.
// Reproduced on Android 15: Dawn otherwise aborts the GPU in OnDebugUtilsCallback.
// Keep genuine Vulkan validation failures and unknown driver messages unchanged.
replacements.set('third_party/dawn/src/dawn/native/vulkan/BackendVk.cpp', [[
  '    // The Vulkan spec does allow pMessageIdName to be nullptr, but it may still contain a valid\n',
  `    // Adreno's VKDBGUTILWARN003 is a render-pass advisory, even when the driver
    // sends ERROR | VALIDATION and a null message ID. Match the complete known
    // diagnostic rather than suppressing Vulkan validation errors generally.
    // https://issuetracker.unity.com/issues/19435/warning-androidplayer-device-vulkan-general-warning-the-following-warning-was-triggered-vkdbgutilwarn003-spammed-every-frame-when-the-application-is-actively-running-on-a-device
    if (message == "The following warning was triggered: VKDBGUTILWARN003. Please refer to the "
                   "Adreno Game Developer Guide for more information: "
                   "https://developer.qualcomm.com/docs/adreno-gpu/developer-guide/index.html") {
        return false;
    }

    // The Vulkan spec does allow pMessageIdName to be nullptr, but it may still contain a valid
`
]]);


// Virtual geolocation keeps site consent while avoiding Android sensor permission requests.
replacements.set("components/location/android/java/src/org/chromium/components/location/LocationUtils.java", [["    private boolean hasPermission(String name) {\n", "    /** Virtual positions come from the immutable environment, without Android sensor access. */\n    public static boolean usesVirtualLocation() {\n        if (!org.chromium.base.CommandLine.isInitialized()) return false;\n        org.chromium.base.CommandLine command = org.chromium.base.CommandLine.getInstance();\n        if (!\"custom\".equals(command.getSwitchValue(\"landu-geolocation-mode\"))) return false;\n        String position = command.getSwitchValue(\"landu-geolocation-position\");\n        if (position == null || position.length() > 128) return false;\n        String[] parts = position.split(\",\", -1);\n        if (parts.length != 3) return false;\n        try {\n            double latitude = Double.parseDouble(parts[0]);\n            double longitude = Double.parseDouble(parts[1]);\n            double accuracy = Double.parseDouble(parts[2]);\n            return Double.isFinite(latitude) && Double.isFinite(longitude) && Double.isFinite(accuracy)\n                    && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180\n                    && accuracy >= 1 && accuracy <= 1000000;\n        } catch (NumberFormatException invalidPosition) {\n            return false;\n        }\n    }\n\n    private boolean hasPermission(String name) {\n"]]);
replacements.set("components/location/android/java/src/org/chromium/components/location/LocationSettings.java", [["        return LocationUtils.getInstance().hasAndroidLocationPermission();", "        return LocationUtils.usesVirtualLocation()\n                || LocationUtils.getInstance().hasAndroidLocationPermission();"], ["        return LocationUtils.getInstance().hasAndroidFineLocationPermission();", "        return LocationUtils.usesVirtualLocation()\n                || LocationUtils.getInstance().hasAndroidFineLocationPermission();"], ["        return LocationUtils.getInstance().isSystemLocationSettingEnabled();", "        return LocationUtils.usesVirtualLocation()\n                || LocationUtils.getInstance().isSystemLocationSettingEnabled();"]]);
replacements.set("components/permissions/android/java/src/org/chromium/components/permissions/PermissionUtil.java", [["    public static String[] getRequiredAndroidPermissionsForContentSetting(int contentSettingType) {\n", "    public static String[] getRequiredAndroidPermissionsForContentSetting(int contentSettingType) {\n        if ((contentSettingType == ContentSettingsType.GEOLOCATION\n                || contentSettingType == ContentSettingsType.GEOLOCATION_WITH_OPTIONS)\n                && org.chromium.components.location.LocationUtils.usesVirtualLocation()) {\n            return EMPTY_PERMISSIONS;\n        }\n"], ["    public static String[] getOptionalAndroidPermissionsForContentSetting(int contentSettingType) {\n", "    public static String[] getOptionalAndroidPermissionsForContentSetting(int contentSettingType) {\n        if ((contentSettingType == ContentSettingsType.GEOLOCATION\n                || contentSettingType == ContentSettingsType.GEOLOCATION_WITH_OPTIONS)\n                && org.chromium.components.location.LocationUtils.usesVirtualLocation()) {\n            return EMPTY_PERMISSIONS;\n        }\n"]]);

function replaceExactlyOnce(value, before, after) {
  const position = value.indexOf(before);
  if (position < 0 || value.indexOf(before, position + before.length) !== -1) {
    throw new Error('The pinned upstream patch anchor is missing or ambiguous');
  }
  return value.slice(0, position) + after + value.slice(position + before.length);
}

export function preparePatchedFile(path, bytes, expectedDigest) {
  const changes = replacements.get(path);
  if (!changes) throw new Error(`Unsupported patch target: ${path}`);
  let original = bytes.toString('utf8');
  let alreadyPatched = false;
  if (digest(bytes) !== expectedDigest) {
    if (path === manifestPath) original = restrictManifestComponents(original, true);
    for (const [before, after] of [...changes].reverse()) {
      original = replaceExactlyOnce(original, after, before);
    }
    if (digest(original) !== expectedDigest) {
      throw new Error(`Upstream file changed; refusing to overwrite: ${path}`);
    }
    alreadyPatched = true;
  }
  let content = original;
  for (const [before, after] of changes) content = replaceExactlyOnce(content, before, after);
  if (path === manifestPath) content = restrictManifestComponents(content);
  return { path, content, alreadyPatched, sha256: digest(content) };
}
