package com.landu.browser.nativecore;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ContextParams;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.view.Display;
import android.os.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class IsolatedEnvironmentContext extends ContextWrapper {
    private final Path directory;
    private final String preferencePrefix;
    private final String environmentName;

    public IsolatedEnvironmentContext(Context base, EnvironmentLeaseStore.Session session) {
        this(base, session.directory(), session.ticket().identity.directoryName());
    }

    private IsolatedEnvironmentContext(Context base, Path directory, String environmentName) {
        super(unwrapSameEnvironment(base, directory, environmentName));
        if (base.isDeviceProtectedStorage()) {
            throw new IllegalArgumentException("Environment requires unlocked credential storage");
        }
        this.directory = directory;
        this.environmentName = environmentName;
        preferencePrefix = environmentName + "__";
    }

    private static Context unwrapSameEnvironment(Context base, Path directory, String environmentName) {
        // Some split/display paths are wrapped more than once by Chromium.
        // Avoid adding a second SharedPreferences prefix; never reuse another
        // environment's wrapped context.
        if (base instanceof IsolatedEnvironmentContext) {
            IsolatedEnvironmentContext isolated = (IsolatedEnvironmentContext) base;
            if (!isolated.directory.equals(directory) || !isolated.environmentName.equals(environmentName)) {
                throw new IllegalArgumentException("Cannot wrap a different environment context");
            }
            return isolated.getBaseContext();
        }
        return base;
    }

    private Context isolated(Context base) {
        return new IsolatedEnvironmentContext(base, directory, environmentName);
    }

    @Override
    public Context createConfigurationContext(Configuration configuration) {
        return new IsolatedEnvironmentContext(super.createConfigurationContext(configuration),
                directory, environmentName);
    }

    @Override
    public Context createDisplayContext(Display display) {
        return new IsolatedEnvironmentContext(super.createDisplayContext(display), directory, environmentName);
    }

    @Override
    public Context createContextForSplit(String splitName) throws PackageManager.NameNotFoundException {
        return new IsolatedEnvironmentContext(super.createContextForSplit(splitName), directory, environmentName);
    }

    @Override
    public Context createAttributionContext(String attributionTag) {
        return isolated(super.createAttributionContext(attributionTag));
    }

    @Override
    public Context createWindowContext(int type, Bundle options) {
        return isolated(super.createWindowContext(type, options));
    }

    @Override
    public Context createWindowContext(Display display, int type, Bundle options) {
        return isolated(super.createWindowContext(display, type, options));
    }

    @Override
    public Context createContext(ContextParams params) {
        return isolated(super.createContext(params));
    }

    @Override
    public Context createPackageContext(String packageName, int flags) throws PackageManager.NameNotFoundException {
        Context created = super.createPackageContext(packageName, flags);
        return getPackageName().equals(packageName) ? isolated(created) : created;
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        throw new UnsupportedOperationException("Environment data cannot move to unlocked device storage");
    }

    private static String fileName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid private file name");
        }
        return name;
    }

    private File subdirectory(String name) {
        Path path = directory.resolve(fileName(name));
        try {
            if (Files.isSymbolicLink(path)) throw new IOException("Linked directory is not allowed");
            Files.createDirectories(path);
            if (!path.toRealPath().equals(path)) throw new IOException("Invalid environment directory");
            return path.toFile();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot open private environment directory", error);
        }
    }

    @Override
    public File getDataDir() {
        return directory.toFile();
    }

    @Override
    public File getFilesDir() {
        return subdirectory("files");
    }

    @Override
    public File getCacheDir() {
        return subdirectory("cache");
    }

    @Override
    public File getCodeCacheDir() {
        return subdirectory("code_cache");
    }

    @Override
    public File getNoBackupFilesDir() {
        return subdirectory("no_backup");
    }

    private File externalDirectory(File parent) {
        if (parent == null) return null;
        try {
            Files.createDirectories(parent.toPath());
            Path path = parent.toPath().toRealPath().resolve(environmentName);
            if (Files.isSymbolicLink(path)) throw new IOException("Linked external directory is not allowed");
            Files.createDirectories(path);
            if (!path.toRealPath().equals(path)) throw new IOException("Invalid external environment directory");
            return path.toFile();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot open environment external directory", error);
        }
    }

    @Override
    public File getExternalFilesDir(String type) {
        return externalDirectory(super.getExternalFilesDir(type));
    }

    @Override
    public File[] getExternalFilesDirs(String type) {
        File[] parents = super.getExternalFilesDirs(type);
        File[] result = new File[parents.length];
        for (int i = 0; i < parents.length; i++) result[i] = externalDirectory(parents[i]);
        return result;
    }

    @Override
    public File getExternalCacheDir() {
        return externalDirectory(super.getExternalCacheDir());
    }

    @Override
    public File[] getExternalCacheDirs() {
        File[] parents = super.getExternalCacheDirs();
        File[] result = new File[parents.length];
        for (int i = 0; i < parents.length; i++) result[i] = externalDirectory(parents[i]);
        return result;
    }

    @Override
    public File[] getExternalMediaDirs() {
        File[] parents = super.getExternalMediaDirs();
        File[] result = new File[parents.length];
        for (int i = 0; i < parents.length; i++) result[i] = externalDirectory(parents[i]);
        return result;
    }

    @Override
    public File getDir(String name, int mode) {
        if (mode != MODE_PRIVATE) throw new IllegalArgumentException("Only private directories are supported");
        return subdirectory("app_" + fileName(name));
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (mode != MODE_PRIVATE) throw new IllegalArgumentException("Only private preferences are supported");
        return super.getSharedPreferences(preferencePrefix + fileName(name), mode);
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        return super.deleteSharedPreferences(preferencePrefix + fileName(name));
    }

    @Override
    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        throw new UnsupportedOperationException("Cross-environment preference migration is not allowed");
    }

    @Override
    public File getFileStreamPath(String name) {
        return new File(getFilesDir(), fileName(name));
    }

    @Override
    public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream(getFileStreamPath(name));
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        if (mode != MODE_PRIVATE && mode != MODE_APPEND) {
            throw new IllegalArgumentException("Only private files are supported");
        }
        return new FileOutputStream(getFileStreamPath(name), mode == MODE_APPEND);
    }

    @Override
    public boolean deleteFile(String name) {
        return getFileStreamPath(name).delete();
    }

    @Override
    public String[] fileList() {
        String[] names = getFilesDir().list();
        return names == null ? new String[0] : names;
    }

    @Override
    public File getDatabasePath(String name) {
        return new File(subdirectory("databases"), fileName(name));
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return openOrCreateDatabase(name, mode, factory, null);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode,
            SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
        if ((mode & ~(MODE_ENABLE_WRITE_AHEAD_LOGGING | MODE_NO_LOCALIZED_COLLATORS)) != 0) {
            throw new IllegalArgumentException("Only private databases are supported");
        }
        int flags = SQLiteDatabase.CREATE_IF_NECESSARY;
        if ((mode & MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        if ((mode & MODE_NO_LOCALIZED_COLLATORS) != 0) flags |= SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        return SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(), factory, flags, handler);
    }

    @Override
    public boolean deleteDatabase(String name) {
        return SQLiteDatabase.deleteDatabase(getDatabasePath(name));
    }

    @Override
    public String[] databaseList() {
        String[] names = subdirectory("databases").list();
        return names == null ? new String[0] : names;
    }

    @Override
    public boolean moveDatabaseFrom(Context sourceContext, String name) {
        throw new UnsupportedOperationException("Cross-environment database migration is not allowed");
    }
}
