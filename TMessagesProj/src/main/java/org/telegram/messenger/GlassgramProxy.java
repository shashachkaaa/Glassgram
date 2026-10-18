package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.util.ArrayList;

/**
 * glassGram: the team's proxies to Telegram (GET /proxies, kept by the team in glassGram Team), for
 * Telegram to work without a VPN. On "auto" the connections go through one of them while the phone
 * has no VPN, and straight to Telegram while one is on; on "always" through one with a VPN too.
 * The account's personal VLESS key (GlassgramTunnel) comes before them: its tunnel is tried first,
 * the team's proxies after it, and they bring the connection to Telegram the key is asked for over.
 * <p>
 * The person's own proxy (Telegram's proxy settings) comes first: while it is on, the team's isn't
 * used. The team's is given to the connections directly (ConnectionsManager.setTeamProxy), not put
 * in Telegram's proxy list, which stays the person's own. One that doesn't connect within
 * CONNECT_TIMEOUT gives way to the next. The list is kept, so it holds from the start, before the network.
 */
public final class GlassgramProxy {

    public static final int MODE_AUTO = 0, MODE_ALWAYS = 1, MODE_OFF = 2;

    private static final String PREFS = "glassgram_proxy";
    /** How often the app's opening asks for the list again; every start of the app asks once. */
    private static final long REFRESH = 30 * 60 * 1000L;
    /** How long a proxy has to connect before the next one is tried. */
    private static final long CONNECT_TIMEOUT = 12 * 1000L;
    /** After every proxy was tried in a row, how long until the next round. */
    private static final long ROUND_PAUSE = 60 * 1000L;

    private static final ArrayList<ProxySettings> proxies = new ArrayList<>();
    /** The personal key's outbounds, tried before the proxies, or null without a key. */
    private static JSONArray tunnel;
    /** The team's proxy the connections have now, or the tunnel's local one, or null: straight to Telegram, or through the person's own. */
    private static volatile ProxySettings active;
    /** The one in use or to try: the tunnel's outbounds first, then the proxies. */
    private static int index;
    /** The tunnel's outbound running (or starting), or -1; each start or stop raises the generation. */
    private static int activeTunnel = -1, tunnelGeneration;
    private static boolean tunnelStarting;
    private static final DispatchQueue tunnelQueue = new DispatchQueue("glassgramTunnel");
    /** Proxies tried in a row without connecting. */
    private static int failed;
    private static boolean started, loading;
    private static long loadedAt;

    private static final Runnable checkConnected = GlassgramProxy::checkConnected;
    private static final Runnable networkChanged = GlassgramProxy::onNetworkChanged;

    private GlassgramProxy() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** At the app's start, after the connections: the kept list, a look out for VPNs, and the list asked for again. */
    public static void start() {
        if (GlassgramConfig.STORE_BUILD) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (started) {
                return;
            }
            started = true;
            try {
                final String kept = prefs().getString("proxies", null);
                if (kept != null) {
                    parse(new JSONArray(kept), proxies);
                }
            } catch (Exception e) {
                FileLog.e(e, false);
            }
            tunnel = GlassgramTunnel.outbounds();
            // the tunnel first; without it, the proxy that last connected
            index = tunnelCount() > 0 ? 0 : prefs().getInt("index", 0);
            watchNetwork();
            update();
            refresh();
        });
    }

    /** Asks for the list when the app opens: at its start, then every REFRESH. */
    public static void refresh() {
        if (!started || loading || loadedAt != 0 && System.currentTimeMillis() - loadedAt < REFRESH) {
            return;
        }
        loading = true;
        GlassgramApi.request("GET", "/proxies", null, null, response -> {
            loading = false;
            final JSONArray list = response.json.optJSONArray("proxies");
            if (!response.ok() || list == null) {
                return;
            }
            loadedAt = System.currentTimeMillis();
            prefs().edit().putString("proxies", list.toString()).apply();
            final ArrayList<ProxySettings> now = new ArrayList<>();
            parse(list, now);
            if (now.equals(proxies)) {
                return;
            }
            // the proxy in use keeps its place in the new list, if it's still there
            final int at = index - tunnelCount();
            final ProxySettings current = at >= 0 && at < proxies.size() ? proxies.get(at) : null;
            proxies.clear();
            proxies.addAll(now);
            if (current != null) {
                index = tunnelCount() + Math.max(0, proxies.indexOf(current));
            }
            failed = 0;
            update();
        });
    }

    /** Asks for the list now, as after the team changed it. */
    public static void refreshNow() {
        loadedAt = 0;
        refresh();
    }

    private static void parse(JSONArray list, ArrayList<ProxySettings> out) {
        for (int i = 0; i < list.length(); i++) {
            final JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            final ProxySettings proxy = ProxySettings.builder()
                    .setType("socks5".equals(item.optString("type")) ? ProxySettings.Type.SOCKS5 : ProxySettings.Type.MTPROTO)
                    .setAddress(item.optString("host"))
                    .setPort(item.optInt("port"))
                    .setSecret(item.optString("secret"))
                    .setUser(item.optString("user"))
                    .setPassword(item.optString("password"))
                    .build();
            if (proxy.isValid() && !out.contains(proxy)) {
                out.add(proxy);
            }
        }
    }

    /** The team's proxy the connections have now, or null. */
    public static ProxySettings activeProxy() {
        return active;
    }

    /** How many proxies the team gives out, the tunnel's outbounds with them. */
    public static int count() {
        return candidates();
    }

    /** Whether the connections go through the tunnel of the personal key now. */
    public static boolean isTunnel() {
        return activeTunnel >= 0 && active != null && !tunnelStarting;
    }

    private static int tunnelCount() {
        return tunnel != null ? tunnel.length() : 0;
    }

    private static int candidates() {
        return tunnelCount() + proxies.size();
    }

    /** The personal key came, or changed, or went: the tunnel is tried first again. */
    private static void onKeyChanged() {
        final JSONArray now = GlassgramTunnel.outbounds();
        if (now == null ? tunnel == null : tunnel != null && now.toString().equals(tunnel.toString())) {
            return;
        }
        stopTunnel();
        tunnel = now;
        index = 0;
        failed = 0;
        update();
    }

    /** Whether the phone's traffic goes through a VPN now. */
    public static boolean vpnActive() {
        try {
            final ConnectivityManager manager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            final Network network = manager.getActiveNetwork();
            final NetworkCapabilities capabilities = network != null ? manager.getNetworkCapabilities(network) : null;
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception e) {
            FileLog.e(e, false);
            return false;
        }
    }

    /** The person's own proxy was changed (ConnectionsManager.setProxySettings), which also took the team's away. */
    public static void onOwnProxyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            active = null;
            // the tunnel's local proxy was taken away too: it starts again if it's still the one
            stopTunnel();
            update();
        });
    }

    /** Gives the connections the team's proxy, or takes it away, by the setting, the VPN and the person's own proxy. */
    public static void update() {
        if (!started) {
            return;
        }
        final int mode = GlassgramConfig.proxyMode;
        final boolean own = SharedConfig.isProxyEnabled();
        if (mode != MODE_OFF) {
            // the personal key is asked for, or renewed, with a VPN on too: ready for when it's off
            GlassgramTunnel.refreshKey(GlassgramProxy::onKeyChanged);
        }
        final int count = candidates();
        if (mode == MODE_OFF || count == 0 || own || mode == MODE_AUTO && vpnActive()) {
            AndroidUtilities.cancelRunOnUIThread(checkConnected);
            stopTunnel();
            if (active != null) {
                active = null;
                if (!own) {
                    ConnectionsManager.setTeamProxy(null);
                }
            }
            return;
        }
        if (index >= count) {
            index = 0;
        }
        if (index < tunnelCount()) {
            if (activeTunnel != index) {
                startTunnel(index);
            }
            return;
        }
        final ProxySettings proxy = proxies.get(index - tunnelCount());
        if (activeTunnel < 0 && proxy.equals(active)) {
            return;
        }
        stopTunnel();
        apply(proxy);
    }

    private static void apply(ProxySettings proxy) {
        active = proxy;
        ConnectionsManager.setTeamProxy(proxy);
        AndroidUtilities.cancelRunOnUIThread(checkConnected);
        AndroidUtilities.runOnUIThread(checkConnected, CONNECT_TIMEOUT);
    }

    /** Starts the tunnel with an outbound off the main thread, then gives the connections its local proxy. */
    private static void startTunnel(int at) {
        final JSONObject outbound = tunnel.optJSONObject(at);
        final int generation = ++tunnelGeneration;
        activeTunnel = at;
        tunnelStarting = true;
        tunnelQueue.postRunnable(() -> {
            final ProxySettings local = GlassgramTunnel.start(outbound);
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != tunnelGeneration) {
                    // stopped, or another started, meanwhile
                    return;
                }
                tunnelStarting = false;
                if (local != null) {
                    apply(local);
                    return;
                }
                // it didn't start: the next one, as for one that doesn't connect
                activeTunnel = -1;
                if (++failed >= candidates()) {
                    failed = 0;
                    AndroidUtilities.runOnUIThread(GlassgramProxy::update, ROUND_PAUSE);
                    return;
                }
                index = (index + 1) % candidates();
                update();
            });
        });
    }

    private static void stopTunnel() {
        if (activeTunnel < 0 && !tunnelStarting) {
            return;
        }
        tunnelGeneration++;
        activeTunnel = -1;
        tunnelStarting = false;
        tunnelQueue.postRunnable(GlassgramTunnel::stop);
    }

    /** A proxy that hasn't connected in CONNECT_TIMEOUT gives way to the next; one that has is remembered. */
    private static void checkConnected() {
        if (active == null || candidates() == 0) {
            return;
        }
        final int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
            failed = 0;
            if (index >= tunnelCount()) {
                prefs().edit().putInt("index", index - tunnelCount()).apply();
            }
            return;
        }
        if (state == ConnectionsManager.ConnectionStateWaitingForNetwork || ApplicationLoader.mainInterfacePaused) {
            // no network, or the app in the background where the connections rest: not the proxy's fault
            AndroidUtilities.runOnUIThread(checkConnected, CONNECT_TIMEOUT);
            return;
        }
        if (++failed >= candidates()) {
            // none of them connected in a row: this one stays a while before the next round
            failed = 0;
            AndroidUtilities.runOnUIThread(checkConnected, ROUND_PAUSE);
            return;
        }
        index = (index + 1) % candidates();
        update();
    }

    /** A VPN going on or off, or another network: the proxy follows, after the change settles. */
    private static void watchNetwork() {
        final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                changed();
            }

            @Override
            public void onLost(Network network) {
                changed();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                changed();
            }

            private void changed() {
                AndroidUtilities.cancelRunOnUIThread(networkChanged);
                AndroidUtilities.runOnUIThread(networkChanged, 500);
            }
        };
        try {
            final ConnectivityManager manager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 24) {
                manager.registerDefaultNetworkCallback(callback);
            } else {
                manager.registerNetworkCallback(new NetworkRequest.Builder()
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback);
            }
        } catch (Exception e) {
            FileLog.e(e, false);
        }
    }

    private static void onNetworkChanged() {
        failed = 0;
        update();
    }
}
