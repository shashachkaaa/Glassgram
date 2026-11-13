package org.telegram.messenger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;

/**
 * glassGram's tunnel in a process of its own (":tunnel"): the Xray core, Go, is only ever loaded here.
 * <p>
 * Go installs its own handlers for SIGSEGV and SIGBUS in the whole process it is loaded in. Android's
 * runtime turns those signals into NullPointerException and StackOverflowError, and with Go's handler
 * in the way an exception of that kind on a thread that never ran Go ended the app in Go's code instead,
 * even where the app would have caught it. Here no app code runs beside Go.
 * <p>
 * {@link GlassgramTunnel} reaches it by {@link android.content.ContentResolver#call}: "invoke" runs
 * libXray's invoke with the request in arg and gives its response as "response".
 */
public class GlassgramTunnelProvider extends ContentProvider {

    public static final String METHOD_INVOKE = "invoke";
    public static final String KEY_RESPONSE = "response";
    public static final String KEY_ERROR = "error";

    private static Method invoke;

    public static String authority(android.content.Context context) {
        return context.getPackageName() + ".tunnel";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        final Bundle result = new Bundle();
        if (!METHOD_INVOKE.equals(method)) {
            result.putString(KEY_ERROR, "unknown method " + method);
            return result;
        }
        try {
            synchronized (GlassgramTunnelProvider.class) {
                if (invoke == null) {
                    invoke = Class.forName("libXray.LibXray").getMethod("invoke", String.class);
                }
            }
            result.putString(KEY_RESPONSE, (String) invoke.invoke(null, arg));
        } catch (Throwable e) {
            final Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            result.putString(KEY_ERROR, cause.toString());
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
