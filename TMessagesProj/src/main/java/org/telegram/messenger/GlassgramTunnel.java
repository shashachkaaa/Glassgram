package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.utils.proxy.ProxySettings;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * glassGram's tunnel: Xray-core (XTLS/libXray) in the app's own process, with the account's personal
 * VLESS key from glassGram's server (POST /tunnel/key, made in the team's 3x-ui). Xray listens for
 * SOCKS5 on a free local port behind a random login made at each start, so no other app on the
 * phone can use it, and GlassgramProxy gives that port to Telegram's connections.
 * <p>
 * The key comes as Xray outbounds, not a link, and is kept encrypted by a key of the phone's
 * Android Keystore, which never leaves it; it is shown nowhere. libXray is reached by reflection: the
 * builds without it (the RuStore edition, local builds) just have no tunnel.
 */
public final class GlassgramTunnel {

    private static final String PREFS = "glassgram_tunnel";
    private static final String KEYSTORE_ALIAS = "glassgram_tunnel";
    /** After a failed request for the key, how long until the next; after "no tunnel" or "banned", longer. */
    private static final long RETRY = 10 * 60 * 1000L;
    private static final long RETRY_REFUSED = 6 * 60 * 60 * 1000L;

    private static Method invoke;
    private static boolean checked, running;

    private static JSONArray outbounds;
    private static long refreshAfter, expiresAt, retryAt;
    private static boolean loaded, loading;

    private GlassgramTunnel() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether this build has the Xray core. */
    public static synchronized boolean isAvailable() {
        if (!checked) {
            checked = true;
            try {
                invoke = Class.forName("libXray.LibXray").getMethod("invoke", String.class);
            } catch (Throwable e) {
                invoke = null;
            }
        }
        return invoke != null && !GlassgramConfig.STORE_BUILD;
    }

    private static JSONObject call(String method, JSONObject payload) throws Exception {
        final JSONObject request = new JSONObject().put("apiVersion", 3).put("method", method);
        if (payload != null) {
            request.put("payload", payload);
        }
        final JSONObject response = new JSONObject((String) invoke.invoke(null, request.toString()));
        if (!response.optBoolean("success")) {
            throw new Exception(method + ": " + response.optString("error"));
        }
        return response;
    }

    // --- The core -----------------------------------------------------------------------------------

    /**
     * Starts Xray with the outbound, listening for SOCKS5 on 127.0.0.1; the local proxy to give the
     * connections, or null when it didn't start. Not on the main thread: it resolves the server's name.
     */
    public static synchronized ProxySettings start(JSONObject outbound) {
        stop();
        if (!isAvailable() || outbound == null) {
            return null;
        }
        try {
            final JSONObject out = new JSONObject(outbound.toString()).put("tag", "glassgram");
            // the server's name resolved by Android: Go's own resolver has no system DNS to ask here
            final JSONArray vnext = out.optJSONObject("settings") != null ? out.getJSONObject("settings").optJSONArray("vnext") : null;
            final JSONObject server = vnext != null ? vnext.optJSONObject(0) : null;
            if (server != null) {
                server.put("address", InetAddress.getByName(server.optString("address")).getHostAddress());
            }
            final int port;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            final String user = randomToken(), password = randomToken();
            final JSONObject config = new JSONObject()
                    .put("log", new JSONObject().put("loglevel", BuildVars.LOGS_ENABLED ? "info" : "warning"))
                    .put("inbounds", new JSONArray().put(new JSONObject()
                            .put("tag", "socks")
                            .put("listen", "127.0.0.1")
                            .put("port", port)
                            .put("protocol", "socks")
                            .put("settings", new JSONObject()
                                    .put("auth", "password")
                                    .put("accounts", new JSONArray().put(new JSONObject().put("user", user).put("pass", password)))
                                    .put("udp", false))))
                    .put("outbounds", new JSONArray().put(out));
            call("runXray", new JSONObject().put("xrayJson", config.toString()));
            running = true;
            return ProxySettings.builder()
                    .setType(ProxySettings.Type.SOCKS5)
                    .setAddress("127.0.0.1")
                    .setPort(port)
                    .setUser(user)
                    .setPassword(password)
                    .build();
        } catch (Throwable e) {
            FileLog.e(e, false);
            return null;
        }
    }

    public static synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            call("stopXray", null);
        } catch (Throwable e) {
            FileLog.e(e, false);
        }
    }

    private static String randomToken() {
        final byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    // --- The key ------------------------------------------------------------------------------------

    /** The key's outbounds, one per inbound giving them, or null without a key in force. */
    public static synchronized JSONArray outbounds() {
        loadKept();
        return outbounds != null && System.currentTimeMillis() / 1000 < expiresAt ? outbounds : null;
    }

    /**
     * Asks glassGram's server for the account's key when there is none or it's time for a new
     * period, signing in by Telegram's signature first if needed (that waits for a connection to
     * Telegram, through the team's proxies at worst). onChanged runs on the main thread when the key changed.
     */
    public static void refreshKey(Runnable onChanged) {
        if (!isAvailable()) {
            return;
        }
        synchronized (GlassgramTunnel.class) {
            loadKept();
            final long now = System.currentTimeMillis();
            if (loading || outbounds != null && now / 1000 < refreshAfter || now < retryAt) {
                return;
            }
            loading = true;
        }
        final int account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            loading = false;
            return;
        }
        if (GlassgramApi.getSession(account) != null) {
            askKey(account, true, onChanged);
        } else {
            signInAndAsk(account, onChanged);
        }
    }

    private static void signInAndAsk(int account, Runnable onChanged) {
        GlassgramApi.signInWithTelegram(account, response -> {
            if (response.ok()) {
                askKey(account, false, onChanged);
            } else {
                failed(RETRY);
            }
        });
    }

    private static void askKey(int account, boolean mayRetry, Runnable onChanged) {
        GlassgramApi.call(account, "POST", "/tunnel/key", new JSONObject(), response -> {
            if (response.status == 401 && mayRetry) {
                // the session ended: signed in again
                signInAndAsk(account, onChanged);
                return;
            }
            final JSONArray got = response.json.optJSONArray("outbounds");
            if (response.ok() && got != null && got.length() > 0) {
                keep(got, response.json.optLong("expires_at"), response.json.optLong("refresh_after"));
                loading = false;
                onChanged.run();
            } else if (response.status == 403 || response.status == 503) {
                // banned, or the tunnel isn't set up now: no key
                final boolean had = outbounds() != null;
                forget();
                failed(RETRY_REFUSED);
                if (had) {
                    onChanged.run();
                }
            } else {
                failed(RETRY);
            }
        });
    }

    private static synchronized void failed(long retry) {
        loading = false;
        retryAt = System.currentTimeMillis() + retry;
    }

    private static synchronized void keep(JSONArray got, long expires, long refresh) {
        outbounds = got;
        expiresAt = expires;
        refreshAfter = refresh;
        retryAt = 0;
        try {
            final JSONObject kept = new JSONObject().put("outbounds", got).put("expires_at", expires).put("refresh_after", refresh);
            prefs().edit().putString("key", encrypt(kept.toString())).apply();
        } catch (Throwable e) {
            // the key stays for this run of the app only
            FileLog.e(e, false);
        }
    }

    private static synchronized void forget() {
        outbounds = null;
        expiresAt = refreshAfter = 0;
        prefs().edit().remove("key").apply();
    }

    private static void loadKept() {
        if (loaded) {
            return;
        }
        loaded = true;
        final String stored = prefs().getString("key", null);
        if (stored == null) {
            return;
        }
        try {
            final JSONObject kept = new JSONObject(decrypt(stored));
            outbounds = kept.getJSONArray("outbounds");
            expiresAt = kept.optLong("expires_at");
            refreshAfter = kept.optLong("refresh_after");
        } catch (Throwable e) {
            FileLog.e(e, false);
            prefs().edit().remove("key").apply();
        }
    }

    // --- Keeping it encrypted -----------------------------------------------------------------------

    private static SecretKey storeKey() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null)).getSecretKey();
        }
        final KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String text) throws Exception {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, storeKey());
        final byte[] sealed = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        final int colon = stored.indexOf(':');
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, storeKey(), new GCMParameterSpec(128, Base64.decode(stored.substring(0, colon), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(stored.substring(colon + 1), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
