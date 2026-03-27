package dev.kouros.sidecar.launcher;

import java.lang.reflect.Method;

/**
 * Thin bootstrap entrypoint that delegates into the versioned sidecar payload.
 */
public final class LauncherMain {
    private LauncherMain() {}

    public static void main(String[] args) throws Exception {
        Class<?> payloadMain = Class.forName("dev.kouros.sidecar.MainKt");
        Method main = payloadMain.getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }
}
