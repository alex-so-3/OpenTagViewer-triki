package dev.wander.android.opentagviewer.source;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Per-device extras that do not belong in the accessory JSON: the Google tracker whose reports
 * are shown on this device, and the password of the device's configuration service.
 *
 * <p>Kept apart because FindMy.py rewrites the accessory JSON on every fetch and sighting
 * ({@code to_json}), dropping any key it does not know.
 */
public final class SourceLinks {
    private final SharedPreferences prefs;

    public SourceLinks(final Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences("source_links", Context.MODE_PRIVATE);
    }

    @Nullable
    public String googleId(final String beaconId) {
        return this.prefs.getString("google:" + beaconId, null);
    }

    public void setGoogleId(final String beaconId, @Nullable final String googleId) {
        if (googleId == null) this.prefs.edit().remove("google:" + beaconId).apply();
        else this.prefs.edit().putString("google:" + beaconId, googleId).apply();
    }

    /** Everytag's default password, which is what a tag has until someone changes it. */
    public String configPassword(final String beaconId) {
        return this.prefs.getString("password:" + beaconId, "abcdefgh");
    }

    public void setConfigPassword(final String beaconId, final String password) {
        this.prefs.edit().putString("password:" + beaconId, password).apply();
    }

    public void forget(final String beaconId) {
        this.prefs.edit().remove("google:" + beaconId).remove("password:" + beaconId).apply();
    }
}
