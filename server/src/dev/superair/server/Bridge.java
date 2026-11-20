package dev.superair.server;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * How a shell process hands a binder to an ordinary app.
 *
 * There is no door the other way round: the app cannot create a trusted display, and a Surface
 * cannot be sent down a socket — it is a reference to a buffer queue, and only a binder transaction
 * carries one. So the host reaches into the app's own ContentProvider and leaves its binder there,
 * which is the mechanism Shizuku has used for years and the reason this works without root.
 *
 * Acquiring the provider also starts the app if it is not running, so the host can bring the
 * glasses back up on its own after the app has been killed.
 */
final class Bridge {
    static final String AUTHORITY = "dev.superair.bridge";
    private static final String METHOD = "host";
    private static final String KEY = "binder";

    private Bridge() {}

    /** Leave `host` with the app. True once the app has taken it. */
    static boolean publish(IBinder host) {
        IBinder token = new Binder();
        Object holder = null;
        try {
            IInterface am = Sys.service("activity", "android.app.IActivityManager");
            Method acquire = method(am.getClass(), "getContentProviderExternal");
            if (acquire == null) throw new NoSuchMethodException("getContentProviderExternal");
            // (String name, int userId, IBinder token, String tag)
            holder = acquire.invoke(am, AUTHORITY, 0, token, "superair");
            if (holder == null) throw new IllegalStateException("no provider for " + AUTHORITY);

            Field providerField = holder.getClass().getDeclaredField("provider");
            providerField.setAccessible(true);
            Object provider = providerField.get(holder);
            if (provider == null) throw new IllegalStateException("the provider holder was empty");

            Bundle extras = new Bundle();
            extras.putBinder(KEY, host);
            Method call = callMethod(provider.getClass());
            if (call == null) throw new NoSuchMethodException("IContentProvider.call");
            call.invoke(provider, callArguments(call, extras));
            Log.d("handed the binder to the app");
            return true;
        } catch (Throwable t) {
            // Reflection wraps everything; the wrapper is never the interesting part.
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            Log.d("the app did not take the binder yet: " + cause);
            return false;
        } finally {
            if (holder != null) release(token);
        }
    }

    /**
     * IContentProvider.call has been respelled more than once. minSdk is 33, so the
     * AttributionSource form is the one that matters; the older package-name form is kept because
     * it costs three lines and a Samsung build has surprised this family before.
     */
    private static Object[] callArguments(Method call, Bundle extras) {
        Class<?>[] types = call.getParameterTypes();
        Object[] args = new Object[types.length];
        int string = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Bundle.class) args[i] = extras;
            else if (types[i] == AttributionSource.class) args[i] = Sys.context().getAttributionSource();
            else if (types[i] == String.class) {
                // ... authority, method, arg — with any leading package/feature names first.
                int remaining = 0;
                for (int j = i; j < types.length; j++) if (types[j] == String.class) remaining++;
                if (remaining == 3 && string == 0) { args[i] = AUTHORITY; string = 1; }
                else if (remaining == 2) { args[i] = METHOD; string = 2; }
                else if (remaining == 1) args[i] = null;
                else args[i] = Sys.ShellContext.PACKAGE;
            } else if (types[i] == int.class) args[i] = 0;
            else args[i] = null;
        }
        return args;
    }

    private static void release(IBinder token) {
        try {
            IInterface am = Sys.service("activity", "android.app.IActivityManager");
            Method remove = method(am.getClass(), "removeContentProviderExternal");
            if (remove != null) remove.invoke(am, AUTHORITY, token);
        } catch (Throwable t) {
            Log.d("could not release the provider: " + t);
        }
    }

    private static Method method(Class<?> type, String name) {
        for (Method m : type.getMethods()) if (m.getName().equals(name)) return m;
        return null;
    }

    /**
     * There are two `call` overloads on this device and only one of them carries an authority.
     *
     * The legacy `call(String callingPkg, String method, String arg, Bundle)` is still there, and
     * taking whichever the reflection happened to list first got the arguments shifted by one —
     * the authority went in as the calling package, and the framework refused the lot with "the
     * authority unknown does not match". So the overload is chosen by shape, never by order.
     */
    private static Method callMethod(Class<?> type) {
        Method fallback = null;
        for (Method m : type.getMethods()) {
            if (!m.getName().equals("call")) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types.length > 0 && types[0] == AttributionSource.class) return m;
            if (fallback == null || types.length > fallback.getParameterTypes().length) fallback = m;
        }
        return fallback;
    }
}
