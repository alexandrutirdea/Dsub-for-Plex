package github.daneren2005.dsub.view;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.preference.EditTextPreference;

import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.KeyStoreUtil;
import github.daneren2005.dsub.util.Util;

/**
 * A server password, kept encrypted where the device has a keystore to encrypt it with.
 *
 * What is persisted is the ciphertext. The plaintext exists only for as long as the dialog
 * is open, which is why the decrypting and re-encrypting happen in
 * {@link EditPasswordPreferenceDialogFragment} rather than here: this preference's own
 * text is written straight to SharedPreferences, so putting a plaintext password into it -
 * even briefly, to show it - would be writing it to disk.
 */
public class EditPasswordPreference extends EditTextPreference {
    private static final String TAG = EditPasswordPreference.class.getSimpleName();

    /** Which configured server this password belongs to. */
    private final int instance;

    public EditPasswordPreference(Context context, int instance) {
        super(context);
        this.instance = instance;
    }

    public int getInstance() {
        return instance;
    }

    /** Whether there is a keystore here to encrypt with at all. */
    public boolean canEncrypt() {
        return Build.VERSION.SDK_INT >= 23;
    }

    private boolean isStoredEncrypted() {
        return Util.getPreferences(getContext())
                .getBoolean(Constants.PREFERENCES_KEY_ENCRYPTED_PASSWORD + instance, false);
    }

    /**
     * The password as a person should see it, given what is currently stored. Returns the
     * stored value unchanged when it was never encrypted, or when it will not decrypt -
     * showing nonsense that then gets re-encrypted would break the server connection.
     */
    public String plaintext() {
        String stored = getText();
        if(stored == null || !isStoredEncrypted()) {
            return stored;
        }

        String decrypted = KeyStoreUtil.decrypt(stored);
        if(decrypted == null) {
            Log.w(TAG, "Could not decrypt the stored password for server " + instance);
            Util.toast(getContext(), "Password Decryption Failed");
            return stored;
        }
        return decrypted;
    }

    /**
     * Stores what was typed, encrypting it first where that is possible, and records which
     * of the two it turned out to be so {@link #plaintext} knows what it is looking at.
     */
    public void store(String plaintext) {
        boolean encrypted = false;
        String toPersist = plaintext;

        if(canEncrypt()) {
            String ciphertext = KeyStoreUtil.encrypt(plaintext);
            if(ciphertext != null) {
                toPersist = ciphertext;
                encrypted = true;
            } else {
                Util.toast(getContext(), "Password encryption failed");
            }
        }

        Util.getPreferences(getContext()).edit()
                .putBoolean(Constants.PREFERENCES_KEY_ENCRYPTED_PASSWORD + instance, encrypted)
                .apply();
        setText(toPersist);
    }
}
