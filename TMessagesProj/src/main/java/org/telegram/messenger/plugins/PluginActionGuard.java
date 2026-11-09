package org.telegram.messenger.plugins;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_payments;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * glassGram: what a plugin does with the account that the user decides, as exteraGram has it. A
 * Telegram request a plugin sends (or makes of one the app sends, through its request hooks) that
 * spends Stars or money, gives away or sells a gift, changes the account's security or manages a
 * chat waits for the user, who sees which plugin, the request, the amount and who gets it. Denied,
 * the plugin gets the error PLUGIN_ACTION_DENIED.
 *
 * A request is the plugin's when Python is on the thread that sends it: Java called into Python
 * (a hook, a callback, the plugins' queue) and the plugin's code calls back, or a thread Python made
 * calls Java with nothing of Java's under it. Which plugin, Python tells from its own frames
 * (_glassgram_engine.caller_plugin). The kinds of requests are in _glassgram_analysis.SENSITIVE too.
 */
public final class PluginActionGuard {

    public static final int KIND_STARS = 1, KIND_GIFTS = 2, KIND_SECURITY = 3, KIND_CHAT = 4;
    public static final String DENIED = "PLUGIN_ACTION_DENIED";

    private static final Map<Class<?>, Object[]> REQUESTS = new HashMap<>();

    private static void put(Class<?> type, String name, int kind) {
        REQUESTS.put(type, new Object[]{name, kind});
    }

    static {
        put(TL_stars.TL_payments_sendStarsForm.class, "payments.sendStarsForm", KIND_STARS);
        put(TLRPC.TL_payments_sendPaymentForm.class, "payments.sendPaymentForm", KIND_STARS);
        put(TLRPC.TL_messages_sendPaidReaction.class, "messages.sendPaidReaction", KIND_STARS);
        put(TL_stars.TL_fulfillStarsSubscription.class, "payments.fulfillStarsSubscription", KIND_STARS);
        put(TL_stars.TL_changeStarsSubscription.class, "payments.changeStarsSubscription", KIND_STARS);
        put(TL_payments.TL_sendStarGiftOffer.class, "payments.sendStarGiftOffer", KIND_STARS);
        put(TL_stars.upgradeStarGift.class, "payments.upgradeStarGift", KIND_STARS);

        put(TL_stars.transferStarGift.class, "payments.transferStarGift", KIND_GIFTS);
        put(TL_stars.convertStarGift.class, "payments.convertStarGift", KIND_GIFTS);
        put(TL_stars.updateStarGiftPrice.class, "payments.updateStarGiftPrice", KIND_GIFTS);
        put(TL_payments.TL_resolveStarGiftOffer.class, "payments.resolveStarGiftOffer", KIND_GIFTS);
        put(TL_stars.craftStarGift.class, "payments.craftStarGift", KIND_GIFTS);
        put(TL_stars.getStarGiftWithdrawalUrl.class, "payments.getStarGiftWithdrawalUrl", KIND_GIFTS);

        put(TL_account.updatePasswordSettings.class, "account.updatePasswordSettings", KIND_SECURITY);
        put(TL_account.getPasswordSettings.class, "account.getPasswordSettings", KIND_SECURITY);
        put(TLRPC.TL_auth_resetAuthorizations.class, "auth.resetAuthorizations", KIND_SECURITY);
        put(TL_account.resetAuthorization.class, "account.resetAuthorization", KIND_SECURITY);
        put(TL_account.setAuthorizationTTL.class, "account.setAuthorizationTTL", KIND_SECURITY);
        put(TL_account.changeAuthorizationSettings.class, "account.changeAuthorizationSettings", KIND_SECURITY);
        put(TLRPC.TL_auth_acceptLoginToken.class, "auth.acceptLoginToken", KIND_SECURITY);
        put(TLRPC.TL_auth_exportAuthorization.class, "auth.exportAuthorization", KIND_SECURITY);
        put(TL_account.deleteAccount.class, "account.deleteAccount", KIND_SECURITY);
        put(TL_account.changePhone.class, "account.changePhone", KIND_SECURITY);
        put(TL_account.sendChangePhoneCode.class, "account.sendChangePhoneCode", KIND_SECURITY);
        put(TL_account.setPrivacy.class, "account.setPrivacy", KIND_SECURITY);
        put(TL_account.resetPassword.class, "account.resetPassword", KIND_SECURITY);
        put(TLRPC.TL_auth_logOut.class, "auth.logOut", KIND_SECURITY);
        put(TL_account.acceptAuthorization.class, "account.acceptAuthorization", KIND_SECURITY);
        put(TL_account.resetWebAuthorization.class, "account.resetWebAuthorization", KIND_SECURITY);
        put(TL_account.resetWebAuthorizations.class, "account.resetWebAuthorizations", KIND_SECURITY);
        put(TL_account.registerPasskey.class, "account.registerPasskey", KIND_SECURITY);
        put(TL_account.deletePasskey.class, "account.deletePasskey", KIND_SECURITY);
        put(TL_account.sendVerifyEmailCode.class, "account.sendVerifyEmailCode", KIND_SECURITY);
        put(TL_account.verifyEmail.class, "account.verifyEmail", KIND_SECURITY);
        put(TLRPC.TL_auth_recoverPassword.class, "auth.recoverPassword", KIND_SECURITY);
        put(TLRPC.TL_auth_resetLoginEmail.class, "auth.resetLoginEmail", KIND_SECURITY);

        put(TLRPC.TL_channels_editCreator.class, "channels.editCreator", KIND_CHAT);
        put(TLRPC.TL_channels_editAdmin.class, "channels.editAdmin", KIND_CHAT);
        put(TLRPC.TL_messages_editChatAdmin.class, "messages.editChatAdmin", KIND_CHAT);
        put(TLRPC.TL_channels_editBanned.class, "channels.editBanned", KIND_CHAT);
        put(TLRPC.TL_messages_deleteChatUser.class, "messages.deleteChatUser", KIND_CHAT);
        put(TLRPC.TL_channels_deleteChannel.class, "channels.deleteChannel", KIND_CHAT);
        put(TLRPC.TL_messages_deleteChat.class, "messages.deleteChat", KIND_CHAT);
        put(TLRPC.TL_channels_deleteHistory.class, "channels.deleteHistory", KIND_CHAT);
        put(TLRPC.TL_channels_deleteParticipantHistory.class, "channels.deleteParticipantHistory", KIND_CHAT);
        put(TLRPC.TL_messages_editChatDefaultBannedRights.class, "messages.editChatDefaultBannedRights", KIND_CHAT);
        put(TLRPC.TL_channels_updateUsername.class, "channels.updateUsername", KIND_CHAT);
        put(TLRPC.TL_messages_migrateChat.class, "messages.migrateChat", KIND_CHAT);
        put(TLRPC.TL_channels_convertToGigagroup.class, "channels.convertToGigagroup", KIND_CHAT);
        put(TLRPC.TL_messages_deleteHistory.class, "messages.deleteHistory", KIND_CHAT);
    }

    private PluginActionGuard() {
    }

    /** What the request does that the user decides for a plugin (KIND_*), 0 for nothing. Cheap: by its class. */
    public static int kindOf(TLObject request) {
        if (request == null) {
            return 0;
        }
        final Object[] entry = REQUESTS.get(request.getClass());
        if (entry != null) {
            return (int) entry[1];
        }
        // a message sent where messages cost Stars
        return paidStars(request) > 0 ? KIND_STARS : 0;
    }

    private static long paidStars(TLObject request) {
        if (request instanceof TLRPC.TL_messages_sendMessage) {
            return ((TLRPC.TL_messages_sendMessage) request).allow_paid_stars;
        } else if (request instanceof TLRPC.TL_messages_sendMedia) {
            return ((TLRPC.TL_messages_sendMedia) request).allow_paid_stars;
        } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
            return ((TLRPC.TL_messages_forwardMessages) request).allow_paid_stars;
        } else if (request instanceof TLRPC.TL_messages_sendInlineBotResult) {
            return ((TLRPC.TL_messages_sendInlineBotResult) request).allow_paid_stars;
        } else if (request instanceof TLRPC.TL_messages_sendMultiMedia) {
            return ((TLRPC.TL_messages_sendMultiMedia) request).allow_paid_stars;
        }
        return 0;
    }

    /** The request's name as Telegram's API has it: payments.sendStarsForm. */
    public static String requestName(TLObject request) {
        final Object[] entry = REQUESTS.get(request.getClass());
        if (entry != null) {
            return (String) entry[0];
        }
        String name = PluginsController.tlName(request);
        if (name.startsWith("TL_")) {
            name = name.substring(3);
        }
        final int underscore = name.indexOf('_');
        return underscore > 0 ? name.substring(0, underscore) + "." + name.substring(underscore + 1) : name;
    }

    /**
     * Who sends a request now, from this thread: a plugin's id, "" for plugins' code of no plugin
     * known, null for the app's own. Only asked for the requests the user decides (it reads the stack).
     */
    public static String caller() {
        if (!PluginsController.isStarted()) {
            return null;
        }
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        boolean python = false;
        for (StackTraceElement element : stack) {
            if (element.getClassName().startsWith("com.chaquo.python.")) {
                python = true;
                break;
            }
        }
        boolean nativeThread = false;
        if (!python && stack.length > 0) {
            // A thread Python made calls into Java with nothing of Java's under it; the app's own
            // threads start in Java or Android, or in the network's callbacks
            final StackTraceElement root = stack[stack.length - 1];
            final String type = root.getClassName();
            nativeThread = !(type.startsWith("java.") || type.startsWith("android.") || type.startsWith("com.android.")
                || type.startsWith("dalvik.") || type.startsWith("libcore.") || type.startsWith("kotlin")
                || type.startsWith("org.webrtc.") || type.startsWith("org.telegram.messenger.voip.")
                || type.startsWith("org.telegram.tgnet.") && !root.getMethodName().startsWith("sendRequest"));
        }
        if (!python && !nativeThread) {
            return null;
        }
        String id = "";
        try {
            final PyObject engine = PluginsController.getEngine();
            if (engine != null) {
                final PyObject found = engine.callAttr("caller_plugin");
                id = found != null ? found.toString() : "";
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        // Not a plugin's frame on a thread of no Java: someone else's native code
        return nativeThread && TextUtils.isEmpty(id) ? null : id;
    }

    /** The plugins that hook the request (they may have made or changed it), by their names. */
    public static String hookingPlugins(TLObject request) {
        try {
            final PyObject engine = PluginsController.getEngine();
            if (engine != null) {
                final PyObject found = engine.callAttr("hooking_plugins", PluginsController.tlName(request));
                return found != null ? found.toString() : "";
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return "";
    }

    /** The request as bytes, to see whether a plugin's hook changed it. */
    public static byte[] bytesOf(TLObject request) {
        try {
            final SerializedData data = new SerializedData(request.getObjectSize());
            request.serializeToStream(data);
            final byte[] bytes = data.toByteArray();
            data.cleanup();
            return bytes;
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean changed(TLObject before, byte[] beforeBytes, TLObject after) {
        return before != after || beforeBytes == null || !Arrays.equals(beforeBytes, bytesOf(after));
    }

    /* The payment forms got, for the amount a form's payment is */

    private static final ConcurrentHashMap<Long, Object[]> forms = new ConcurrentHashMap<>();

    /** A payment form got (payments.getPaymentForm): what paying it costs. */
    public static void rememberForm(TLObject response) {
        if (!(response instanceof TLRPC.PaymentForm)) {
            return;
        }
        final TLRPC.PaymentForm form = (TLRPC.PaymentForm) response;
        final TLRPC.TL_invoice invoice = form.invoice;
        if (invoice == null) {
            return;
        }
        long amount = 0;
        for (TLRPC.TL_labeledPrice price : invoice.prices) {
            amount += price.amount;
        }
        if (forms.size() > 64) {
            forms.clear();
        }
        forms.put(form.form_id, new Object[]{amount, invoice.currency, form.bot_id});
    }

    /* Asking */

    private static final class Ask {
        final int account;
        final String pluginId;
        final TLObject request;
        final int kind;
        final Utilities.Callback<Boolean> done;

        Ask(int account, String pluginId, TLObject request, int kind, Utilities.Callback<Boolean> done) {
            this.account = account;
            this.pluginId = pluginId;
            this.request = request;
            this.kind = kind;
            this.done = done;
        }
    }

    private static final ArrayDeque<Ask> waiting = new ArrayDeque<>();
    private static boolean asking;

    /** Asks the user whether the plugin may send the request; done(allowed), on the main thread. */
    public static void confirm(int account, String pluginId, TLObject request, Utilities.Callback<Boolean> done) {
        final Ask ask = new Ask(account, pluginId, request, kindOf(request), done);
        AndroidUtilities.runOnUIThread(() -> {
            waiting.add(ask);
            next();
        });
    }

    private static void next() {
        if (asking || waiting.isEmpty()) {
            return;
        }
        final Ask ask = waiting.poll();
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || activity.isFinishing()) {
            // nobody to ask: no
            finish(ask, false);
            next();
            return;
        }
        asking = true;
        final boolean[] answered = {false};
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final AlertDialog dialog = new AlertDialog.Builder(activity, resourcesProvider)
            .setTitle(getString(R.string.GlassgramPluginActionTitle))
            .setMessage(describe(ask))
            .setPositiveButton(getString(R.string.GlassgramPluginActionAllow), (d, w) -> {
                answered[0] = true;
                finish(ask, true);
            })
            .setNegativeButton(getString(R.string.GlassgramPluginActionDeny), (d, w) -> {
                answered[0] = true;
                finish(ask, false);
            })
            .create();
        dialog.setOnDismissListener(d -> {
            if (!answered[0]) {
                finish(ask, false);
            }
            asking = false;
            next();
        });
        dialog.setCanceledOnTouchOutside(false);
        try {
            dialog.show();
        } catch (Exception e) {
            FileLog.e(e);
            asking = false;
            if (!answered[0]) {
                answered[0] = true;
                finish(ask, false);
            }
            next();
            return;
        }
        final View allow = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (allow instanceof TextView) {
            ((TextView) allow).setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        }
    }

    private static void finish(Ask ask, boolean allowed) {
        PluginsController.log(TextUtils.isEmpty(ask.pluginId) ? "plugins" : ask.pluginId,
            (allowed ? "Allowed: " : "Denied: ") + requestName(ask.request));
        try {
            ask.done.run(allowed);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String pluginName(String id) {
        if (TextUtils.isEmpty(id)) {
            return getString(R.string.GlassgramPluginActionUnknown);
        }
        if (id.contains(",")) {
            // the plugins hooking the request
            final StringBuilder names = new StringBuilder();
            for (String one : id.split(",")) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(pluginName(one));
            }
            return names.toString();
        }
        final PluginInfo info = PluginsController.getPlugin(id);
        return info != null && !TextUtils.isEmpty(info.name) ? info.name : id;
    }

    private static CharSequence describe(Ask ask) {
        final int action;
        switch (ask.kind) {
            case KIND_GIFTS: action = R.string.GlassgramPluginActionGifts; break;
            case KIND_SECURITY: action = R.string.GlassgramPluginActionSecurity; break;
            case KIND_CHAT: action = R.string.GlassgramPluginActionChat; break;
            default: action = R.string.GlassgramPluginActionStars; break;
        }
        final SpannableStringBuilder text = new SpannableStringBuilder(AndroidUtilities.replaceTags(
            formatString(R.string.GlassgramPluginActionText, pluginName(ask.pluginId), getString(action))));
        final Details details = new Details();
        details.read(ask.account, ask.request, 0);
        text.append("\n\n");
        text.append(AndroidUtilities.replaceTags(formatString(R.string.GlassgramPluginActionRequest, requestName(ask.request))));
        final String amount = details.amount(ask.account, ask.request);
        if (amount != null) {
            text.append("\n").append(AndroidUtilities.replaceTags(formatString(R.string.GlassgramPluginActionAmount, amount)));
        }
        if (details.recipient != null) {
            text.append("\n").append(AndroidUtilities.replaceTags(formatString(R.string.GlassgramPluginActionRecipient, details.recipient)));
        }
        if (details.chat != null && !details.chat.equals(details.recipient)) {
            text.append("\n").append(AndroidUtilities.replaceTags(formatString(R.string.GlassgramPluginActionChatName, details.chat)));
        }
        if (details.gift != null) {
            text.append("\n").append(AndroidUtilities.replaceTags(formatString(R.string.GlassgramPluginActionGift, details.gift)));
        }
        text.append("\n\n").append(getString(R.string.GlassgramPluginActionHint));
        return text;
    }

    /** Who gets what a request does and how much it costs, read from its fields (and its invoice's). */
    private static final class Details {
        String recipient, chat, gift;
        long stars, money, formId;
        String currency;
        int recipientRank = Integer.MAX_VALUE;

        void read(int account, Object object, int depth) {
            if (object == null || depth > 3) {
                return;
            }
            for (Field field : object.getClass().getFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                final Object value;
                try {
                    value = field.get(object);
                } catch (Exception e) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                final String name = field.getName();
                switch (name) {
                    case "to_id":
                    case "user_id":
                    case "participant":
                    case "bot":
                    case "peer": {
                        final int rank = Arrays.asList("to_id", "user_id", "participant", "bot", "peer").indexOf(name);
                        final String who = nameOf(account, value);
                        if (who == null) {
                            break;
                        }
                        if (depth > 0 && object instanceof TL_stars.InputSavedStarGift) {
                            // the gift's owner, not who gets it
                            break;
                        }
                        if (rank < recipientRank) {
                            if ("peer".equals(name) && (object instanceof TLRPC.TL_messages_deleteHistory)) {
                                chat = who;
                                break;
                            }
                            recipient = who;
                            recipientRank = rank;
                        }
                        break;
                    }
                    case "channel":
                    case "chat_id": {
                        // chat_id is a basic group's own id
                        final String which = nameOf(account, "chat_id".equals(name) && value instanceof Long ? -(Long) value : value);
                        if (which != null) {
                            chat = which;
                        }
                        break;
                    }
                    case "allow_paid_stars":
                    case "stars":
                        if (value instanceof Long) {
                            stars += (Long) value;
                        }
                        break;
                    case "resell_amount":
                    case "price":
                        if (value instanceof TL_stars.StarsAmount) {
                            stars += ((TL_stars.StarsAmount) value).amount;
                        } else if (value instanceof Long) {
                            stars += (Long) value;
                        }
                        break;
                    case "form_id":
                        if (value instanceof Long) {
                            formId = (Long) value;
                        }
                        break;
                    case "slug":
                        if (value instanceof String && !TextUtils.isEmpty((String) value)) {
                            gift = "t.me/nft/" + value;
                        }
                        break;
                    case "invoice":
                    case "purpose":
                    case "stargift":
                        read(account, value, depth + 1);
                        break;
                    default:
                        break;
                }
            }
        }

        String amount(int account, TLObject request) {
            if (request instanceof TLRPC.TL_messages_sendPaidReaction) {
                stars = ((TLRPC.TL_messages_sendPaidReaction) request).count;
            } else if (request instanceof TLRPC.TL_messages_sendMultiMedia) {
                final List<?> media = ((TLRPC.TL_messages_sendMultiMedia) request).multi_media;
                stars = paidStars(request) * Math.max(1, media.size());
            } else if (request instanceof TLRPC.TL_messages_forwardMessages) {
                final List<?> ids = ((TLRPC.TL_messages_forwardMessages) request).id;
                stars = paidStars(request) * Math.max(1, ids.size());
            }
            if (formId != 0) {
                final Object[] form = forms.get(formId);
                if (form != null) {
                    currency = (String) form[1];
                    if ("XTR".equals(currency)) {
                        stars = (Long) form[0];
                    } else {
                        money = (Long) form[0];
                    }
                    if (recipient == null && (Long) form[2] != 0) {
                        recipient = nameOf(account, form[2]);
                    }
                }
            }
            if (money > 0 && currency != null) {
                return LocaleController.getInstance().formatCurrencyString(money, currency);
            }
            if (stars > 0) {
                return "⭐ " + LocaleController.formatNumber(stars, ' ');
            }
            return request instanceof TL_stars.TL_payments_sendStarsForm || request instanceof TLRPC.TL_payments_sendPaymentForm
                ? getString(R.string.GlassgramPluginActionAmountUnknown) : null;
        }
    }

    /** A name for a peer, a user, a chat or a dialog id; null when it is none. */
    private static String nameOf(int account, Object value) {
        long dialogId;
        if (value instanceof TLRPC.InputPeer) {
            if (value instanceof TLRPC.TL_inputPeerEmpty) {
                return null;
            }
            if (value instanceof TLRPC.TL_inputPeerSelf) {
                return getString(R.string.GlassgramPluginActionYou);
            }
            dialogId = DialogObject.getPeerDialogId((TLRPC.InputPeer) value);
        } else if (value instanceof TLRPC.InputUser) {
            if (value instanceof TLRPC.TL_inputUserSelf) {
                return getString(R.string.GlassgramPluginActionYou);
            }
            dialogId = ((TLRPC.InputUser) value).user_id;
        } else if (value instanceof TLRPC.InputChannel) {
            dialogId = -((TLRPC.InputChannel) value).channel_id;
        } else if (value instanceof Long) {
            dialogId = (Long) value;
        } else {
            return null;
        }
        if (dialogId == 0) {
            return null;
        }
        final MessagesController controller = MessagesController.getInstance(account);
        if (dialogId > 0) {
            final TLRPC.User user = controller.getUser(dialogId);
            if (user == null) {
                return "id " + dialogId;
            }
            final String username = UserObject.getPublicUsername(user);
            return UserObject.getUserName(user) + (TextUtils.isEmpty(username) ? "" : " (@" + username + ")");
        }
        final TLRPC.Chat chat = controller.getChat(-dialogId);
        if (chat == null) {
            return "id " + Math.abs(dialogId);
        }
        return chat.title + (TextUtils.isEmpty(chat.username) ? "" : " (@" + chat.username + ")");
    }
}
