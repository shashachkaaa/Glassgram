package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.GlassgramApi;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

/**
 * glassGram Preferences > For the glassGram team: signing in to glassGram's API (GlassgramApi) and,
 * as far as the bot's admin level allows, the phones' statistics, crashes, badges, support reports
 * and admins.
 */
public class GlassgramDeveloperActivity extends UniversalFragment {

    private static final int ID_SIGN_IN = 1;
    private static final int ID_SIGN_OUT = 2;
    private static final int ID_ACCOUNT = 3;
    private static final int ID_RELOAD = 4;
    private static final int ID_ACTIVE = 5;
    private static final int ID_INSTALLS = 6;
    private static final int ID_FEATURES = 7;
    private static final int ID_CAMERA = 8;
    private static final int ID_FIND_MODEL = 9;
    private static final int ID_CRASHES = 10;
    private static final int ID_GIVE_BADGE = 11;
    private static final int ID_REPORTS = 12;
    private static final int ID_ADMINS = 13;
    private static final int ID_ACCOUNTS = 14;
    private static final int ID_RULES = 15;
    private static final int ID_CATALOG = 16;
    private static final int ID_BANS = 17;
    private static final int ID_PROXIES = 18;
    private static final int ID_BREAKDOWN = 100;

    /** The summary's [value, count] lists, and what they are. */
    private static final String[] BREAKDOWNS = {"versions", "telegram", "editions", "android", "manufacturers", "models", "socs"};
    private static final int[] BREAKDOWN_NAMES = {
            R.string.GlassgramApiVersions, R.string.GlassgramApiTelegramVersions, R.string.GlassgramApiEditions,
            R.string.GlassgramApiAndroid, R.string.GlassgramApiManufacturers, R.string.GlassgramApiModels, R.string.GlassgramApiSocs};

    private JSONObject summary;
    private boolean loading;
    private String loadError;

    @Override
    public boolean onFragmentCreate() {
        // levels change in the bot: ask who this is now, then load what they may see
        GlassgramApi.refresh(currentAccount, response -> {
            update();
            load();
        });
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.GlassgramApiTeam);
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void load() {
        if (loading || !GlassgramApi.can(currentAccount, GlassgramApi.RIGHT_STATS)) {
            return;
        }
        loading = true;
        loadError = null;
        update();
        GlassgramApi.call(currentAccount, "GET", "/stats/summary?days=30", null, response -> {
            loading = false;
            if (response.ok()) {
                summary = response.json;
            } else {
                loadError = response.message();
            }
            update();
        });
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final GlassgramApi.Session session = GlassgramApi.getSession(currentAccount);
        if (session == null) {
            items.add(UItem.asButton(ID_SIGN_IN, R.drawable.msg_permissions, getString(R.string.GlassgramApiSignIn)).accent());
            items.add(UItem.asShadow(formatString(R.string.GlassgramApiTeamInfo, "@" + GlassgramApi.BOT_USERNAME)));
            return;
        }
        items.add(UItem.asHeader(getString(R.string.GlassgramApiAccount)));
        items.add(UItem.asButton(ID_ACCOUNT, R.drawable.msg_openprofile,
                session.isStaff() ? session.levelName : getString(R.string.GlassgramApiNoLevel), String.valueOf(session.userId)));
        items.add(UItem.asButton(ID_SIGN_OUT, R.drawable.msg_leave, getString(R.string.GlassgramApiSignOut)).red());
        items.add(UItem.asShadow(session.isStaff() ? null : formatString(R.string.GlassgramApiNoRights, "@" + GlassgramApi.BOT_USERNAME)));

        final boolean stats = session.can(GlassgramApi.RIGHT_STATS);
        if (stats) {
            items.add(UItem.asHeader(getString(R.string.GlassgramApiStats)));
            if (summary == null) {
                final CharSequence text = getString(loading ? R.string.Loading : R.string.GlassgramApiRetry);
                final CharSequence value = loading ? null : loadError;
                final UItem row = UItem.asButton(ID_RELOAD, text, value);
                items.add(loading ? row.locked() : row);
            } else {
                final JSONObject active = summary.optJSONObject("active");
                items.add(UItem.asButton(ID_ACTIVE, getString(R.string.GlassgramApiActive), active == null ? "—"
                        : active.optInt("day") + " / " + active.optInt("week") + " / " + active.optInt("month")));
                items.add(UItem.asButton(ID_INSTALLS, getString(R.string.GlassgramApiInstalls), String.valueOf(summary.optInt("installs"))));
                items.add(UItem.asButton(ID_ACCOUNTS, getString(R.string.GlassgramApiAccounts), String.valueOf(summary.optInt("users"))));
                for (int i = 0; i < BREAKDOWNS.length; i++) {
                    final JSONArray list = summary.optJSONArray(BREAKDOWNS[i]);
                    final JSONArray top = list != null ? list.optJSONArray(0) : null;
                    items.add(UItem.asButton(ID_BREAKDOWN + i, getString(BREAKDOWN_NAMES[i]), top != null ? valueName(BREAKDOWNS[i], top.opt(0)) : "—"));
                }
                items.add(UItem.asButton(ID_FEATURES, getString(R.string.GlassgramApiFeatures)));
                items.add(UItem.asButton(ID_CAMERA, getString(R.string.GlassgramApiCameras)));
            }
            items.add(UItem.asShadow(getString(R.string.GlassgramApiStatsInfo)));
        }

        final boolean badges = session.can(GlassgramApi.RIGHT_BADGES);
        final boolean support = session.can(GlassgramApi.RIGHT_SUPPORT);
        final boolean admins = session.can(GlassgramApi.RIGHT_ADMINS);
        final boolean rules = session.can(GlassgramApi.RIGHT_CONFIG);
        final boolean catalog = session.can(GlassgramApi.RIGHT_PLUGINS);
        final boolean proxies = session.can(GlassgramApi.RIGHT_PROXIES);
        if (stats || badges || support || admins || rules || catalog || proxies) {
            items.add(UItem.asHeader(getString(R.string.GlassgramApiTools)));
            if (stats) {
                items.add(UItem.asButton(ID_FIND_MODEL, R.drawable.msg_search, getString(R.string.GlassgramApiFindModel)));
                items.add(UItem.asButton(ID_CRASHES, R.drawable.msg_report, getString(R.string.GlassgramApiCrashes)));
            }
            if (rules) {
                items.add(UItem.asButton(ID_RULES, R.drawable.msg_settings, getString(R.string.GlassgramApiRules)));
            }
            if (catalog) {
                items.add(UItem.asButton(ID_CATALOG, R.drawable.msg_download, getString(R.string.GlassgramCatalog)));
            }
            if (session.can(GlassgramApi.RIGHT_BANS)) {
                items.add(UItem.asButton(ID_BANS, R.drawable.msg_block, getString(R.string.GlassgramApiBans)));
            }
            if (proxies) {
                items.add(UItem.asButton(ID_PROXIES, R.drawable.msg2_secret, getString(R.string.GlassgramApiProxies)));
            }
            if (badges) {
                items.add(UItem.asButton(ID_GIVE_BADGE, R.drawable.msg_premium_badge, getString(R.string.GlassgramApiGiveBadge)));
            }
            if (support) {
                items.add(UItem.asButton(ID_REPORTS, R.drawable.msg_log, getString(R.string.GlassgramApiReports)));
            }
            if (admins) {
                items.add(UItem.asButton(ID_ADMINS, R.drawable.msg_admins, getString(R.string.GlassgramApiAdmins)));
            }
            items.add(UItem.asShadow(badges ? getString(R.string.GlassgramApiToolsInfo) : null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_SIGN_IN:
                GlassgramApiUi.signIn(this, () -> {
                    update();
                    load();
                });
                return;
            case ID_SIGN_OUT:
                GlassgramApi.signOut(currentAccount);
                summary = null;
                update();
                return;
            case ID_RELOAD:
                load();
                return;
            case ID_ACTIVE:
                showDaily();
                return;
            case ID_FEATURES:
                showFeatures();
                return;
            case ID_CAMERA:
                showCameras();
                return;
            case ID_FIND_MODEL:
                askModel();
                return;
            case ID_CRASHES:
                GlassgramApiUi.showCrashes(this);
                return;
            case ID_GIVE_BADGE:
                GlassgramApiUi.askHolderAndEditBadge(this);
                return;
            case ID_REPORTS:
                GlassgramApiUi.showReports(this, 0);
                return;
            case ID_ADMINS:
                GlassgramApiUi.showAdmins(this);
                return;
            case ID_RULES:
                GlassgramApiUi.showRules(this);
                return;
            case ID_CATALOG:
                presentFragment(new GlassgramPluginCatalogActivity());
                return;
            case ID_BANS:
                GlassgramApiUi.showBans(this);
                return;
            case ID_PROXIES:
                GlassgramApiUi.showProxies(this);
                return;
        }
        if (item.id >= ID_BREAKDOWN && item.id - ID_BREAKDOWN < BREAKDOWNS.length) {
            showBreakdown(item.id - ID_BREAKDOWN);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // --- The summary ------------------------------------------------------------------------------

    /** Installs active in the summary's window: what the lists' shares are of. */
    private int activeInstalls() {
        final JSONObject active = summary != null ? summary.optJSONObject("active") : null;
        return active != null ? active.optInt("month") : 0;
    }

    private String share(int count) {
        final int total = activeInstalls();
        return total > 0 ? count + " (" + Math.round(count * 100f / total) + "%)" : String.valueOf(count);
    }

    private static String valueName(String list, Object value) {
        if (value == null || value == JSONObject.NULL || "".equals(value)) {
            return "?";
        }
        if ("android".equals(list)) {
            return androidName(value instanceof Number ? ((Number) value).intValue() : 0) + " (SDK " + value + ")";
        }
        return String.valueOf(value);
    }

    static String androidName(int sdk) {
        switch (sdk) {
            case 21: return "Android 5.0";
            case 22: return "Android 5.1";
            case 23: return "Android 6";
            case 24: return "Android 7.0";
            case 25: return "Android 7.1";
            case 26: return "Android 8.0";
            case 27: return "Android 8.1";
            case 28: return "Android 9";
            case 29: return "Android 10";
            case 30: return "Android 11";
            case 31: return "Android 12";
            case 32: return "Android 12L";
            default: return sdk >= 33 ? "Android " + (sdk - 20) : "Android";
        }
    }

    private void showBreakdown(int index) {
        final Activity context = getParentActivity();
        final JSONArray list = summary != null ? summary.optJSONArray(BREAKDOWNS[index]) : null;
        if (context == null || list == null || list.length() == 0) {
            return;
        }
        final boolean models = "models".equals(BREAKDOWNS[index]);
        final CharSequence[] items = new CharSequence[list.length()];
        for (int i = 0; i < list.length(); i++) {
            final JSONArray row = list.optJSONArray(i);
            items[i] = row == null ? "" : valueName(BREAKDOWNS[index], row.opt(0)) + " — " + share(row.optInt(1));
        }
        new AlertDialog.Builder(context)
                .setTitle(getString(BREAKDOWN_NAMES[index]))
                .setItems(items, models ? (d, which) -> {
                    final JSONArray row = list.optJSONArray(which);
                    if (row != null && !row.isNull(0)) {
                        showDevices(row.optString(0));
                    }
                } : null)
                .setNegativeButton(getString(R.string.Close), null)
                .show();
    }

    private void showDaily() {
        final JSONArray daily = summary != null ? summary.optJSONArray("daily") : null;
        if (daily == null || daily.length() == 0) {
            return;
        }
        final StringBuilder text = new StringBuilder();
        for (int i = daily.length() - 1; i >= 0; i--) {
            final JSONArray row = daily.optJSONArray(i);
            if (row != null) {
                text.append(row.optString(0)).append("  ").append(row.optInt(1)).append('\n');
            }
        }
        GlassgramApiUi.showText(this, getString(R.string.GlassgramApiDaily), text.toString());
    }

    private void showFeatures() {
        final JSONObject features = summary != null ? summary.optJSONObject("features") : null;
        if (features == null) {
            return;
        }
        final StringBuilder text = new StringBuilder();
        final Iterator<String> names = features.keys();
        while (names.hasNext()) {
            final String name = names.next();
            text.append(name).append(": ").append(share(features.optInt(name))).append('\n');
        }
        GlassgramApiUi.showText(this, getString(R.string.GlassgramApiFeatures),
                text.length() > 0 ? text.toString() : getString(R.string.GlassgramApiNothingYet));
    }

    private void showCameras() {
        final JSONObject camera = summary != null ? summary.optJSONObject("camera") : null;
        if (camera == null) {
            return;
        }
        final int reports = camera.optInt("reports");
        final JSONObject wide = camera.optJSONObject("wide");
        final String text = formatString(R.string.GlassgramApiCameraSummary, reports,
                part(camera.optInt("gyroscope"), reports), part(camera.optInt("back_fps60"), reports), part(camera.optInt("front_fps60"), reports),
                part(wide != null ? wide.optInt("lens") : 0, reports), part(wide != null ? wide.optInt("zoom") : 0, reports),
                part(wide != null ? wide.optInt("none") : 0, reports));
        GlassgramApiUi.showText(this, getString(R.string.GlassgramApiCameras), text);
    }

    private static String part(int count, int of) {
        return of > 0 ? String.format(Locale.US, "%d (%d%%)", count, Math.round(count * 100f / of)) : String.valueOf(count);
    }

    // --- Devices ----------------------------------------------------------------------------------

    private void askModel() {
        final Activity context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor field = GlassgramApiUi.field(context, getString(R.string.GlassgramApiModelHint), false, false);
        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.GlassgramApiFindModel))
                .setView(GlassgramApiUi.wrap(context, field))
                .setPositiveButton(getString(R.string.Search), (d, w) -> findModels(field.getText().toString().trim()))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void findModels(String query) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog progress = GlassgramApiUi.progress(this);
        GlassgramApi.call(currentAccount, "GET", "/stats/models?days=90&q=" + Uri.encode(query), null, response -> {
            progress.dismiss();
            if (!response.ok()) {
                GlassgramApiUi.showError(this, response);
                return;
            }
            final JSONArray models = response.json.optJSONArray("models");
            if (models == null || models.length() == 0) {
                GlassgramApiUi.showDone(this, getString(R.string.GlassgramApiNoModels));
                return;
            }
            if (getParentActivity() == null) {
                return;
            }
            final CharSequence[] items = new CharSequence[models.length()];
            for (int i = 0; i < models.length(); i++) {
                final JSONArray row = models.optJSONArray(i);
                items[i] = row == null ? "" : valueName("models", row.opt(0)) + " — " + row.optInt(1);
            }
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.GlassgramApiModels))
                    .setItems(items, (d, which) -> {
                        final JSONArray row = models.optJSONArray(which);
                        if (row != null && !row.isNull(0)) {
                            showDevices(row.optString(0));
                        }
                    })
                    .setNegativeButton(getString(R.string.Close), null)
                    .show();
        });
    }

    /** The installs of a model and their latest reports: what the phone is and can do. */
    private void showDevices(String model) {
        if (getParentActivity() == null || TextUtils.isEmpty(model)) {
            return;
        }
        final AlertDialog progress = GlassgramApiUi.progress(this);
        GlassgramApi.call(currentAccount, "GET", "/stats/devices?days=90&model=" + Uri.encode(model), null, response -> {
            progress.dismiss();
            if (!response.ok()) {
                GlassgramApiUi.showError(this, response);
                return;
            }
            final JSONArray installs = response.json.optJSONArray("installs");
            final JSONArray reports = response.json.optJSONArray("reports");
            final StringBuilder text = new StringBuilder();
            text.append(formatString(R.string.GlassgramApiModelInstalls, installs != null ? installs.length() : 0)).append('\n');
            for (int i = 0; installs != null && i < installs.length(); i++) {
                final JSONObject install = installs.optJSONObject(i);
                if (install == null) {
                    continue;
                }
                text.append("• ").append(install.optString("version")).append(" (").append(install.optString("telegram")).append(", ")
                        .append(install.optString("edition")).append("), ").append(valueName("android", install.opt("sdk")))
                        .append(", ").append(install.optString("soc")).append(", ")
                        .append(GlassgramApiUi.date(install.optLong("last_seen"))).append('\n');
            }
            for (int i = 0; reports != null && i < reports.length(); i++) {
                final JSONObject report = reports.optJSONObject(i);
                if (report == null) {
                    continue;
                }
                report.remove("install");
                text.append("\n— ").append(formatString(R.string.GlassgramApiModelReport, i + 1)).append('\n')
                        .append(GlassgramApiUi.pretty(report, "  "));
            }
            // a rule for this model, from its phones
            final boolean rules = GlassgramApi.can(currentAccount, GlassgramApi.RIGHT_CONFIG);
            GlassgramApiUi.showText(this, model, text.toString(), rules ? getString(R.string.GlassgramApiRuleForModel) : null,
                    rules ? () -> GlassgramApiUi.editRule(this, null, model) : null);
        });
    }
}
