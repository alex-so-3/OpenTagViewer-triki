package dev.wander.android.opentagviewer.source;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The accessory JSON of the two kinds of device the extra sources add.
 *
 * <ul>
 *   <li>An OpenHaystack key is FindMy.py's own {@code custom_rolling_key_accessory} with a single
 *   key, so everything that already works for self-generated tags (BLE matching, play sound,
 *   history) works for it unchanged.</li>
 *   <li>A Google Find Hub tracker is {@code google_find_hub}: it has no Apple keys at all, and
 *   the Apple paths must never be handed one (FindMy.py raises on an unknown type).</li>
 * </ul>
 */
public final class ExternalAccessory {
    public static final String TYPE_OPENHAYSTACK = "custom_rolling_key_accessory";
    public static final String TYPE_GOOGLE = "google_find_hub";

    private ExternalAccessory() {
    }

    @Nullable
    private static JSONObject parse(@Nullable final String accessoryJson) {
        if (accessoryJson == null) return null;
        try {
            return new JSONObject(accessoryJson);
        } catch (final JSONException e) {
            return null;
        }
    }

    public static boolean isGoogle(@Nullable final String accessoryJson) {
        final JSONObject o = parse(accessoryJson);
        return o != null && TYPE_GOOGLE.equals(o.optString("type"));
    }

    public static boolean isOpenHaystack(@Nullable final String accessoryJson) {
        final JSONObject o = parse(accessoryJson);
        return o != null && TYPE_OPENHAYSTACK.equals(o.optString("type"));
    }

    /** The canonic id of a Google tracker. */
    @Nullable
    public static String googleId(@Nullable final String accessoryJson) {
        final JSONObject o = parse(accessoryJson);
        return o == null ? null : o.optString("google_id", null);
    }

    /**
     * Reads the private key out of a Macless-Haystack / OpenHaystack {@code .keys} file
     * ("Private key: &lt;base64&gt;"), as hex.
     *
     * @throws IllegalArgumentException when there is none
     */
    public static String privateKeyFromKeysFile(final String text) {
        for (final String line : text.split("\n")) {
            final int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Private key")) {
                final byte[] key = Base64.decode(line.substring(colon + 1).trim(), Base64.DEFAULT);
                if (key.length != 28) break;
                return toHex(key);
            }
        }
        throw new IllegalArgumentException("No 28-byte \"Private key\" line in this file");
    }

    static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    public static String newOpenHaystack(final String id, final String name, final String privateKeyHex) {
        try {
            return new JSONObject()
                    .put("type", TYPE_OPENHAYSTACK)
                    .put("identifier", id)
                    .put("name", name)
                    .put("private_keys", new JSONArray().put(privateKeyHex))
                    .toString();
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String newGoogle(final String googleId, final String name) {
        try {
            return new JSONObject()
                    .put("type", TYPE_GOOGLE)
                    .put("identifier", "google-" + googleId)
                    .put("name", name)
                    .put("google_id", googleId)
                    .toString();
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
