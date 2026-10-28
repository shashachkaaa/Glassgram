package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GlassgramChatLock;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

/**
 * What glassGram's chat PIN asks of one (GlassgramChatLock): the PIN, or a fingerprint in its
 * place, before a locked chat or the hidden chats open, and making the PIN.
 */
public final class GlassgramChatLockUi {

    private GlassgramChatLockUi() {
    }

    /**
     * Asks for the PIN, a fingerprint first where the phone has one and it is allowed. onSuccess
     * runs once it is given, onCancel (may be null) when one gives up.
     */
    public static void requirePin(BaseFragment fragment, Runnable onSuccess, Runnable onCancel) {
        final Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        if (!GlassgramChatLock.hasPin()) {
            onSuccess.run();
            return;
        }
        if (GlassgramChatLock.useFingerprint() && canUseFingerprint(activity) && activity instanceof FragmentActivity) {
            try {
                final BiometricPrompt prompt = new BiometricPrompt((FragmentActivity) activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        onSuccess.run();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        // "Use PIN", or the fingerprint gave up: the PIN then
                        showPinDialog(fragment, onSuccess, onCancel);
                    }
                });
                prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(getString(R.string.GlassgramChatLockUnlock))
                        .setNegativeButtonText(getString(R.string.UsePIN))
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        .build());
                return;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        showPinDialog(fragment, onSuccess, onCancel);
    }

    public static boolean canUseFingerprint(Context context) {
        try {
            return BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private static void showPinDialog(BaseFragment fragment, Runnable onSuccess, Runnable onCancel) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final EditTextBoldCursor field = pinField(activity, getString(R.string.GlassgramChatLockPinHint));
        final FrameLayout container = new FrameLayout(activity);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 4, 24, 0));

        final boolean[] done = new boolean[1];
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.GlassgramChatLockUnlock));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.OK), null);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        final Runnable check = () -> {
            if (GlassgramChatLock.checkPin(field.getText().toString())) {
                done[0] = true;
                AndroidUtilities.hideKeyboard(field);
                dialog.dismiss();
                onSuccess.run();
            } else {
                field.setText("");
                AndroidUtilities.shakeViewSpring(field, 8);
                field.setHint(getString(R.string.GlassgramChatLockWrongPin));
            }
        };
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, actionId, event) -> {
            check.run();
            return true;
        });
        dialog.setOnDismissListener(d -> {
            if (!done[0] && onCancel != null) {
                onCancel.run();
            }
        });
        fragment.showDialog(dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> check.run());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            AndroidUtilities.hideKeyboard(field);
            dialog.dismiss();
        });
        field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(field), 200);
    }

    /** Makes the PIN (or a new one), asked twice; onDone once it is set. */
    public static void createPin(BaseFragment fragment, Runnable onDone) {
        final Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final EditTextBoldCursor first = pinField(activity, getString(R.string.GlassgramChatLockNewPin));
        final EditTextBoldCursor second = pinField(activity, getString(R.string.GlassgramChatLockRepeatPin));
        final LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));
        container.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.GlassgramChatLockSetPin));
        builder.setMessage(getString(R.string.GlassgramChatLockSetPinInfo));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), null);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        final AlertDialog dialog = builder.create();
        dialog.setDismissDialogByButtons(false);
        final Runnable save = () -> {
            final String pin = first.getText().toString();
            if (!GlassgramChatLock.isValidPin(pin)) {
                AndroidUtilities.shakeViewSpring(first, 8);
                return;
            }
            if (!pin.equals(second.getText().toString())) {
                second.setText("");
                second.setHint(getString(R.string.GlassgramChatLockPinsDiffer));
                AndroidUtilities.shakeViewSpring(second, 8);
                return;
            }
            GlassgramChatLock.setPin(pin);
            AndroidUtilities.hideKeyboard(second);
            dialog.dismiss();
            if (onDone != null) {
                onDone.run();
            }
        };
        first.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        second.setImeOptions(EditorInfo.IME_ACTION_DONE);
        second.setOnEditorActionListener((v, actionId, event) -> {
            save.run();
            return true;
        });
        fragment.showDialog(dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> save.run());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            AndroidUtilities.hideKeyboard(first);
            dialog.dismiss();
        });
        first.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(first), 200);
    }

    private static EditTextBoldCursor pinField(Context context, CharSequence hint) {
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint(hint);
        field.setSingleLine(true);
        field.setGravity(Gravity.CENTER);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        field.setPadding(0, dp(8), 0, dp(8));
        return field;
    }

    /** Locks the chat, or unlocks it after the PIN; makes the PIN first when there is none. */
    public static void toggleLock(BaseFragment fragment, int account, long dialogId, Runnable done) {
        if (GlassgramChatLock.isLocked(account, dialogId)) {
            requirePin(fragment, () -> {
                GlassgramChatLock.setLocked(account, dialogId, false);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.unlock_icon, getString(R.string.GlassgramChatUnlocked)).show();
                if (done != null) {
                    done.run();
                }
            }, null);
            return;
        }
        final Runnable lock = () -> {
            GlassgramChatLock.setLocked(account, dialogId, true);
            // locked from inside: it stays open until one leaves the app
            GlassgramChatLock.markUnlocked(account, dialogId);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.passcode_lock_close, getString(R.string.GlassgramChatLocked)).show();
            if (done != null) {
                done.run();
            }
        };
        if (GlassgramChatLock.hasPin()) {
            lock.run();
        } else {
            createPin(fragment, lock);
        }
    }

    /** Hides the chat from the chat lists, or shows it again; makes the PIN first when there is none. */
    public static void toggleHidden(BaseFragment fragment, int account, long dialogId, Runnable done) {
        if (GlassgramChatLock.isHidden(account, dialogId)) {
            GlassgramChatLock.setHidden(account, dialogId, false);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_unhide, getString(R.string.GlassgramChatShown)).show();
            if (done != null) {
                done.run();
            }
            return;
        }
        final Runnable hide = () -> {
            GlassgramChatLock.setHidden(account, dialogId, true);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_hide, getString(R.string.GlassgramChatHidden)).show();
            if (done != null) {
                done.run();
            }
        };
        if (GlassgramChatLock.hasPin()) {
            hide.run();
        } else {
            createPin(fragment, hide);
        }
    }

    /** Opens the hidden chats, after the PIN. */
    public static void openHidden(BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        if (!GlassgramChatLock.hasPin()) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, getString(R.string.GlassgramHiddenChatsNone)).show();
            return;
        }
        requirePin(fragment, () -> {
            GlassgramChatLock.setHiddenOpen(true);
            fragment.presentFragment(new GlassgramHiddenChatsActivity());
        }, null);
    }
}
