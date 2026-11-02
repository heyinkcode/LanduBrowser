package com.landu.browser.nativecore;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** A distinct application-keystore key; proxy credentials are not migrated or modified. */
public final class AndroidAccountCredentials {
    private static final String ALIAS = "landu.account.credentials.v1";
    private static final Object LOCK = new Object();
    private AndroidAccountCredentials() {}

    public static AccountCredentialStore open(Path controlRoot) throws IOException {
        return new AccountCredentialStore(controlRoot, AndroidAccountCredentials::key);
    }

    private static SecretKey key(boolean create) throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            Key existing = store.getKey(ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            if (existing != null || store.containsAlias(ALIAS) || !create) {
                throw new GeneralSecurityException("Account credential key unavailable");
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        }
    }
}
