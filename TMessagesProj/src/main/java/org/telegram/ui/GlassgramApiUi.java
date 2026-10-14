package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.GlassgramApi;
import org.telegram.messenger.GlassgramBadges;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.GlassgramStats;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

/**
 * glassGram: the dialogs of glassGram's own API (GlassgramApi): signing in, a badge from a profile,
 * support reports, crashes and admins. The developer's screen is GlassgramDeveloperActivity.
 */
public final class GlassgramApiUi {

    private GlassgramApiUi() {
    }

    // --- Common -----------------------------------------------------------------------------------

    static void showError(BaseFragment fragment, GlassgramApi.Response response) {
        if (fragment.getParentActivity() != null) {
            BulletinFactory.of(fragment).createErrorBulletin(response.message()).show();
        }
    }

    static void showDone(BaseFragment fragment, CharSequence text) {
        if (fragment.getParentActivity() != null) {
            BulletinFactory.of(fragment).createSuccessBulletin(text).show();
        }
    }

    static AlertDialog progress(BaseFragment fragment) {
        final AlertDialog dialog = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        dialog.setCanCancel(false);
        dialog.show();
        return dialog;
    }

    static EditTextBoldCursor field(Context context, CharSequence hint, boolean digits, boolean multiline) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setHint(hint);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        if (digits) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER);
            field.setSingleLine(true);
        } else if (multiline) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            field.setSingleLine(false);
            field.setMaxLines(6);
        } else {
            field.setSingleLine(true);
        }
        field.setPadding(0, dp(6), 0, dp(6));
        return field;
    }

    static FrameLayout wrap(Context context, View field) {
        final FrameLayout container = new FrameLayout(context);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 0));
        return container;
    }

    /** A long text in a scrollable dialog, with a button that copies it. */
    static void showText(BaseFragment fragment, CharSequence title, String text) {
        final Activity context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final TextView view = new TextView(context);
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        view.setPadding(dp(24), dp(4), dp(24), dp(4));
        final ScrollView scroll = new ScrollView(context);
        scroll.addView(view);
        final FrameLayout container = new FrameLayout(context);
        container.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y * 0.55f)));
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(getString(R.string.Copy), (d, w) -> AndroidUtilities.addToClipboard(text))
                .setNegativeButton(getString(R.string.Close), null)
                .show();
    }

    static String date(long seconds) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(seconds * 1000L));
    }

    /** JSON laid out for reading, without the braces' noise. */
    static String pretty(Object value, String indent) {
        final StringBuilder sb = new StringBuilder();
        if (value instanceof JSONObject) {
            final JSONObject json = (JSONObject) value;
            final Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                final Object item = json.opt(key);
                if (item instanceof JSONObject || item instanceof JSONArray && ((JSONArray) item).optJSONObject(0) != null) {
                    sb.append(indent).append(key).append(":\n").append(pretty(item, indent + "  "));
                } else {
                    sb.append(indent).append(key).append(": ").append(item instanceof JSONArray ? join((JSONArray) item) : String.valueOf(item)).append('\n');
                }
            }
        } else if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                sb.append(pretty(array.opt(i), indent)).append(i + 1 < array.length() ? indent + "—\n" : "");
            }
        } else if (value != null) {
            sb.append(indent).append(value).append('\n');
        }
        return sb.toString();
    }

    private static String join(JSONArray array) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(array.opt(i));
        }
        return sb.toString();
    }

    // --- Signing in -------------------------------------------------------------------------------

    /** Runs the action signed in, signing in first if need be. */
    public static void withSession(BaseFragment fragment, Runnable action) {
        if (GlassgramApi.getSession(fragment.getCurrentAccount()) != null) {
            action.run();
        } else {
            signIn(fragment, action);
        }
    }

    /** Signs in by Telegram's signature; where that can't be, by a code from the bot. */
    public static void signIn(BaseFragment fragment, Runnable onSignedIn) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final AlertDialog progress = progress(fragment);
        GlassgramApi.signInWithTelegram(account, response -> {
            progress.dismiss();
            if (response.ok()) {
                signedIn(fragment, onSignedIn);
            } else if (response.status == 0 || response.status >= 500 || response.status == 409 || response.status == 429) {
                showError(fragment, response);
            } else {
                // the bot has no Mini App: the code it gives for /login instead
                GlassgramApi.resolveBot(account, botId -> askCode(fragment, botId, onSignedIn));
            }
        });
    }

    private static void signedIn(BaseFragment fragment, Runnable onSignedIn) {
        final GlassgramApi.Session session = GlassgramApi.getSession(fragment.getCurrentAccount());
        if (session != null && session.isStaff()) {
            showDone(fragment, formatString(R.string.GlassgramApiSignedInAs, session.levelName));
        }
        if (onSignedIn != null) {
            onSignedIn.run();
        }
    }

    private static void askCode(BaseFragment fragment, long botId, Runnable onSignedIn) {
        final Activity context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final EditTextBoldCursor field = field(context, "12345678", true, false);
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(getString(R.string.GlassgramApiSignIn))
                .setMessage(AndroidUtilities.replaceTags(formatString(R.string.GlassgramApiCodeInfo, "@" + GlassgramApi.BOT_USERNAME)))
                .setView(wrap(context, field))
                .setPositiveButton(getString(R.string.GlassgramApiSignInButton), null)
                .setNeutralButton(getString(R.string.GlassgramApiGetCode), null)
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        final boolean[] busy = {false};
        final Runnable submit = () -> {
            final String code = field.getText().toString().replaceAll("\\D", "");
            if (busy[0]) {
                return;
            }
            if (code.length() != 8) {
                AndroidUtilities.shakeViewSpring(field);
                return;
            }
            busy[0] = true;
            GlassgramApi.signInWithCode(account, code, response -> {
                busy[0] = false;
                if (response.ok()) {
                    dialog.dismiss();
                    signedIn(fragment, onSignedIn);
                } else {
                    field.setText("");
                    Toast.makeText(context, response.message(), Toast.LENGTH_LONG).show();
                }
            });
        };
        // the bot's answer to /login fills the code in by itself
        final NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> {
            if (id != NotificationCenter.didReceiveNewMessages || botId == 0 || !(args[0] instanceof Long) || (Long) args[0] != botId) {
                return;
            }
            @SuppressWarnings("unchecked") final ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            for (MessageObject message : messages) {
                final String code = GlassgramApi.codeIn(message, botId);
                if (code != null) {
                    field.setText(code);
                    submit.run();
                    return;
                }
            }
        };
        NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.didReceiveNewMessages);
        dialog.setOnDismissListener(d -> NotificationCenter.getInstance(account).removeObserver(observer, NotificationCenter.didReceiveNewMessages));
        dialog.show();
        final View signInButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (signInButton != null) {
            signInButton.setOnClickListener(v -> submit.run());
        }
        final View getCodeButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (getCodeButton != null) {
            getCodeButton.setOnClickListener(v -> {
                if (botId == 0) {
                    Toast.makeText(context, getString(R.string.GlassgramApiNoConnection), Toast.LENGTH_SHORT).show();
                    return;
                }
                GlassgramApi.requestCode(account, botId);
                Toast.makeText(context, getString(R.string.GlassgramApiCodeRequested), Toast.LENGTH_SHORT).show();
            });
        }
    }

    // --- Support reports --------------------------------------------------------------------------

    /** Anyone: sends the developer this phone's details, with the camera report and a comment. */
    public static void sendReport(BaseFragment fragment) {
        final Activity context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor field = field(context, getString(R.string.GlassgramApiReportHint), false, true);
        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.GlassgramApiReportTitle))
                .setMessage(getString(R.string.GlassgramApiReportInfo))
                .setView(wrap(context, field))
                .setPositiveButton(getString(R.string.GlassgramApiReportSend), (d, w) -> {
                    final String comment = field.getText().toString().trim();
                    withSession(fragment, () -> doSendReport(fragment, comment));
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private static void doSendReport(BaseFragment fragment, String comment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final AlertDialog progress = progress(fragment);
        Utilities.globalQueue.postRunnable(() -> {
            final Context context = ApplicationLoader.applicationContext;
            final int size = GlassgramConfig.roundVideoSize(MessagesController.getInstance(account).roundVideoSize);
            final JSONObject body = GlassgramApi.json(
                    "comment", comment,
                    "app", GlassgramStats.app(),
                    "device", GlassgramStats.device(),
                    "camera", org.telegram.messenger.camera.CameraReport.summary(context, size),
                    "camera_report", org.telegram.messenger.camera.CameraReport.build(context, size));
            AndroidUtilities.runOnUIThread(() -> GlassgramApi.call(account, "POST", "/support/report", body, response -> {
                progress.dismiss();
                if (response.ok()) {
                    showDone(fragment, getString(R.string.GlassgramApiReportSent));
                } else {
                    showError(fragment, response);
                }
            }));
        });
    }

    /** The support reports of one person (userId), or the latest of everyone (0). */
    public static void showReports(BaseFragment fragment, long userId) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final AlertDialog progress = progress(fragment);
        GlassgramApi.call(fragment.getCurrentAccount(), "GET", "/support/reports?limit=30" + (userId != 0 ? "&user_id=" + userId : ""), null, response -> {
            progress.dismiss();
            if (!response.ok()) {
                showError(fragment, response);
                return;
            }
            final JSONArray reports = response.json.optJSONArray("reports");
            if (reports == null || reports.length() == 0) {
                showDone(fragment, getString(R.string.GlassgramApiNoReports));
                return;
            }
            final CharSequence[] items = new CharSequence[reports.length()];
            for (int i = 0; i < reports.length(); i++) {
                final JSONObject report = reports.optJSONObject(i);
                final String comment = report.optString("comment");
                items[i] = "#" + report.optLong("id") + " · " + date(report.optLong("created_at")) + " · " + report.optString("model")
                        + (TextUtils.isEmpty(comment) ? "" : "\n" + comment.split("\n")[0]);
            }
            new AlertDialog.Builder(fragment.getParentActivity())
                    .setTitle(getString(R.string.GlassgramApiReports))
                    .setItems(items, (d, which) -> {
                        final JSONObject report = reports.optJSONObject(which);
                        final JSONObject details = report.optJSONObject("report");
                        final StringBuilder text = new StringBuilder();
                        text.append("User ").append(report.optLong("user_id")).append(", ").append(date(report.optLong("created_at"))).append("\n\n");
                        if (!TextUtils.isEmpty(report.optString("comment"))) {
                            text.append(report.optString("comment")).append("\n\n");
                        }
                        if (details != null) {
                            text.append("App:\n").append(pretty(details.optJSONObject("app"), "  "));
                            text.append("\nDevice:\n").append(pretty(details.optJSONObject("device"), "  "));
                            if (!TextUtils.isEmpty(details.optString("camera_report"))) {
                                text.append("\n").append(details.optString("camera_report"));
                            }
                            if (!TextUtils.isEmpty(details.optString("logs"))) {
                                text.append("\n\nLogs:\n").append(details.optString("logs"));
                            }
                        }
                        showText(fragment, "#" + report.optLong("id"), text.toString());
                    })
                    .setNegativeButton(getString(R.string.Close), null)
                    .show();
        });
    }

    // --- Badges -----------------------------------------------------------------------------------

    /** The way badges.json names a profile: users and bots by id, channels as -100…, groups as -id. */
    public static String holderOf(long userId, TLRPC.Chat chat) {
        if (userId != 0) {
            return String.valueOf(userId);
        }
        if (chat == null) {
            return null;
        }
        return ChatObject.isChannel(chat) ? "-100" + chat.id : "-" + chat.id;
    }

    /** Asks who, by id or @username, and edits their badge. */
    public static void askHolderAndEditBadge(BaseFragment fragment) {
        final Activity context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor field = field(context, getString(R.string.GlassgramApiHolderHint), false, false);
        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.GlassgramApiGiveBadge))
                .setView(wrap(context, field))
                .setPositiveButton(getString(R.string.OK), (d, w) -> {
                    final String holder = field.getText().toString().trim().replace("https://t.me/", "").replace("t.me/", "");
                    if (!holder.isEmpty()) {
                        editBadge(fragment, holder, holder);
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    /** Gives, changes or takes the badge of a holder, as far as the signed-in admin may. */
    public static void editBadge(BaseFragment fragment, String holder, CharSequence name) {
        if (fragment.getParentActivity() == null || holder == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final AlertDialog progress = progress(fragment);
        GlassgramApi.call(account, "GET", "/badges", null, kinds -> {
            if (!kinds.ok()) {
                progress.dismiss();
                showError(fragment, kinds);
                return;
            }
            GlassgramApi.call(account, "GET", "/badges/" + Uri.encode(holder), null, current -> {
                progress.dismiss();
                if (!current.ok()) {
                    showError(fragment, current);
                    return;
                }
                showBadgeChoice(fragment, holder, name, kinds.json, current.json);
            });
        });
    }

    private static void showBadgeChoice(BaseFragment fragment, String holder, CharSequence name, JSONObject kinds, JSONObject current) {
        final Activity context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final String badge = current.isNull("badge") ? null : current.optString("badge");
        if (!current.optBoolean("can_change", true)) {
            new AlertDialog.Builder(context)
                    .setTitle(name)
                    .setMessage(formatString(R.string.GlassgramApiBadgeLocked, badge))
                    .setPositiveButton(getString(R.string.OK), null)
                    .show();
            return;
        }
        final JSONArray allowed = kinds.optJSONArray("allowed");
        final ArrayList<String> ids = new ArrayList<>();
        final ArrayList<CharSequence> items = new ArrayList<>();
        for (int i = 0; allowed != null && i < allowed.length(); i++) {
            final String id = allowed.optString(i);
            ids.add(id);
            items.add((id.equals(badge) ? "✓ " : "") + id);
        }
        if (badge != null) {
            ids.add(null);
            items.add(formatString(R.string.GlassgramApiTakeBadge, badge));
        }
        final String title = badge != null ? formatString(R.string.GlassgramApiBadgeNow, name, badge) : formatString(R.string.GlassgramApiBadgeNone, name);
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    final String chosen = ids.get(which);
                    if (chosen == null) {
                        takeBadge(fragment, holder, badge);
                    } else {
                        askBadgeText(fragment, holder, chosen);
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private static void askBadgeText(BaseFragment fragment, String holder, String badge) {
        final Activity context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor field = field(context, getString(R.string.GlassgramApiBadgeTextHint), false, true);
        new AlertDialog.Builder(context)
                .setTitle(formatString(R.string.GlassgramApiGiveBadgeNamed, badge))
                .setMessage(getString(R.string.GlassgramApiBadgeTextInfo))
                .setView(wrap(context, field))
                .setPositiveButton(getString(R.string.GlassgramApiGive), (d, w) -> {
                    final String text = field.getText().toString().trim();
                    Object description = null;
                    final String[] parts = text.split("(?m)^\\s*---\\s*$");
                    if (parts.length == 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty()) {
                        description = GlassgramApi.json("ru", parts[0].trim(), "en", parts[1].trim());
                    } else if (!text.isEmpty()) {
                        description = text;
                    }
                    final AlertDialog progress = progress(fragment);
                    GlassgramApi.call(fragment.getCurrentAccount(), "PUT", "/badges/" + Uri.encode(holder),
                            GlassgramApi.json("badge", badge, "text", description), response -> {
                                progress.dismiss();
                                if (response.ok()) {
                                    showDone(fragment, formatString(R.string.GlassgramApiBadgeGiven, badge));
                                    GlassgramBadges.refresh(true);
                                } else {
                                    showError(fragment, response);
                                }
                            });
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private static void takeBadge(BaseFragment fragment, String holder, String badge) {
        final AlertDialog progress = progress(fragment);
        GlassgramApi.call(fragment.getCurrentAccount(), "DELETE", "/badges/" + Uri.encode(holder), null, response -> {
            progress.dismiss();
            if (response.ok()) {
                showDone(fragment, formatString(R.string.GlassgramApiBadgeTaken, badge));
                GlassgramBadges.refresh(true);
            } else {
                showError(fragment, response);
            }
        });
    }

    // --- Crashes ----------------------------------------------------------------------------------

    public static void showCrashes(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final AlertDialog progress = progress(fragment);
        GlassgramApi.call(account, "GET", "/crashes?days=30", null, response -> {
            progress.dismiss();
            if (!response.ok()) {
                showError(fragment, response);
                return;
            }
            final JSONArray crashes = response.json.optJSONArray("crashes");
            if (crashes == null || crashes.length() == 0) {
                showDone(fragment, getString(R.string.GlassgramApiNoCrashes));
                return;
            }
            final CharSequence[] items = new CharSequence[crashes.length()];
            for (int i = 0; i < crashes.length(); i++) {
                final JSONObject crash = crashes.optJSONObject(i);
                items[i] = "×" + crash.optInt("count") + " · " + formatString(R.string.GlassgramApiInstallsCount, crash.optInt("installs"))
                        + "\n" + crash.optString("title");
            }
            new AlertDialog.Builder(fragment.getParentActivity())
                    .setTitle(getString(R.string.GlassgramApiCrashes))
                    .setItems(items, (d, which) -> {
                        final JSONObject crash = crashes.optJSONObject(which);
                        final AlertDialog loading = progress(fragment);
                        GlassgramApi.call(account, "GET", "/crashes/" + crash.optString("signature"), null, samples -> {
                            loading.dismiss();
                            if (!samples.ok()) {
                                showError(fragment, samples);
                                return;
                            }
                            final JSONArray list = samples.json.optJSONArray("samples");
                            final StringBuilder text = new StringBuilder();
                            text.append(formatString(R.string.GlassgramApiCrashSummary, crash.optInt("count"), crash.optInt("installs"),
                                    join(crash.optJSONArray("versions") != null ? crash.optJSONArray("versions") : new JSONArray())));
                            for (int i = 0; list != null && i < list.length(); i++) {
                                final JSONObject sample = list.optJSONObject(i);
                                text.append("\n\n— ").append(date(sample.optLong("created_at"))).append(", ").append(sample.optString("version"))
                                        .append(", ").append(sample.optString("model")).append(", SDK ").append(sample.optInt("sdk"));
                                if (i == 0) {
                                    text.append("\n").append(sample.optString("trace"));
                                }
                            }
                            showText(fragment, crash.optString("title"), text.toString());
                        });
                    })
                    .setNegativeButton(getString(R.string.Close), null)
                    .show();
        });
    }

    // --- Admins -----------------------------------------------------------------------------------

    public static void showAdmins(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final AlertDialog progress = progress(fragment);
        GlassgramApi.call(account, "GET", "/admins", null, response -> {
            progress.dismiss();
            if (!response.ok()) {
                showError(fragment, response);
                return;
            }
            final JSONArray owners = response.json.optJSONArray("owners");
            final JSONArray admins = response.json.optJSONArray("admins");
            final JSONArray levels = response.json.optJSONArray("levels");
            final ArrayList<CharSequence> items = new ArrayList<>();
            final ArrayList<JSONObject> rows = new ArrayList<>();
            for (int i = 0; owners != null && i < owners.length(); i++) {
                items.add("👑 " + owners.optLong(i));
                rows.add(null);
            }
            for (int i = 0; admins != null && i < admins.length(); i++) {
                final JSONObject admin = admins.optJSONObject(i);
                final String adminName = admin.optString("name");
                items.add(admin.optLong("id") + (TextUtils.isEmpty(adminName) ? "" : " " + adminName) + " — " + admin.optString("level_name"));
                rows.add(admin);
            }
            items.add(getString(R.string.GlassgramApiAppoint));
            rows.add(new JSONObject());
            new AlertDialog.Builder(fragment.getParentActivity())
                    .setTitle(getString(R.string.GlassgramApiAdmins))
                    .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                        final JSONObject row = rows.get(which);
                        if (row == null) {
                            return;
                        }
                        if (row.length() == 0) {
                            askAdminId(fragment, levels);
                        } else if (row.optBoolean("can_change")) {
                            showAdmin(fragment, row.optLong("id"), levels, true);
                        }
                    })
                    .setNegativeButton(getString(R.string.Close), null)
                    .show();
        });
    }

    private static void askAdminId(BaseFragment fragment, JSONArray levels) {
        final Activity context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor field = field(context, getString(R.string.GlassgramApiUserIdHint), true, false);
        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.GlassgramApiAppoint))
                .setView(wrap(context, field))
                .setPositiveButton(getString(R.string.OK), (d, w) -> {
                    final long id = Utilities.parseLong(field.getText().toString());
                    if (id > 0) {
                        showAdmin(fragment, id, levels, false);
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private static void showAdmin(BaseFragment fragment, long userId, JSONArray levels, boolean existing) {
        final Activity context = fragment.getParentActivity();
        if (context == null || levels == null) {
            return;
        }
        final ArrayList<CharSequence> items = new ArrayList<>();
        for (int i = 0; i < levels.length(); i++) {
            items.add(levels.optJSONObject(i).optString("name"));
        }
        if (existing) {
            items.add(getString(R.string.GlassgramApiRemoveAdmin));
        }
        final int account = fragment.getCurrentAccount();
        new AlertDialog.Builder(context)
                .setTitle(String.valueOf(userId))
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                    final AlertDialog progress = progress(fragment);
                    final GlassgramApi.Callback done = response -> {
                        progress.dismiss();
                        if (response.ok()) {
                            showDone(fragment, getString(R.string.GlassgramApiSaved));
                        } else {
                            showError(fragment, response);
                        }
                    };
                    if (which < levels.length()) {
                        GlassgramApi.call(account, "PUT", "/admins/" + userId,
                                GlassgramApi.json("level", levels.optJSONObject(which).optInt("level")), done);
                    } else {
                        GlassgramApi.call(account, "DELETE", "/admins/" + userId, null, done);
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
}
