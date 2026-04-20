package com.r4x.capcut_xposed;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * CapCut Xposed Helper v7
 * Target: com.lemon.lvoverseas (CapCut)
 * By: R4X
 *
 * Features:
 * - IPC Bridge via LocalSocket (abstract: capcut_xposed_bridge)
 * - Hook EditActivity for timeline control
 * - Hook MainActivity for navigation
 * - Watermark removal hook
 * - Export quality hook
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "R4X_CapcutXposed";
    public static final String TARGET_PKG = "com.lemon.lvoverseas";

    private static BridgeSocketServer bridgeServer;
    private static Context appContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PKG)) return;

        XposedBridge.log(TAG + ": CapCut loaded! Injecting hooks...");
        Log.d(TAG, "CapCut package loaded: " + lpparam.packageName);

        // Hook Application.onCreate to get context ASAP
        hookApplicationonCreate(lpparam.classLoader);
    }

    /**
     * Hook Application onCreate to get context and start bridge server
     */
    private void hookApplicationonCreate(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
            Application.class,
            "onCreate",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Application app = (Application) param.thisObject;
                    appContext = app.getApplicationContext();
                    
                    XposedBridge.log(TAG + ": Application.onCreate - context acquired");
                    
                    // Start IPC bridge server in background thread
                    new Thread(() -> {
                        try {
                            bridgeServer = new BridgeSocketServer(appContext, classLoader);
                            bridgeServer.start();
                            XposedBridge.log(TAG + ": Bridge server started");
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": Bridge server failed: " + e.getMessage());
                        }
                    }, "R4X-Bridge-Thread").start();

                    // Install all hooks
                    hookEditActivity(classLoader);
                    hookMainActivity(classLoader);
                    hookWatermark(classLoader);
                    hookExportQuality(classLoader);
                }
            }
        );
    }

    /**
     * Hook EditActivity - Main video editor
     * com.vega.edit.editpage.activity.EditActivity
     */
    private void hookEditActivity(ClassLoader cl) {
        try {
            Class<?> editActivityClass = XposedHelpers.findClass(
                "com.vega.edit.editpage.activity.EditActivity", cl);

            // Hook onCreate
            XposedHelpers.findAndHookMethod(editActivityClass, "onCreate",
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": EditActivity onCreate");
                        if (bridgeServer != null) {
                            bridgeServer.setEditActivity(param.thisObject);
                        }
                    }
                }
            );

            // Hook onDestroy
            XposedHelpers.findAndHookMethod(editActivityClass, "onDestroy",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": EditActivity onDestroy");
                        if (bridgeServer != null) {
                            bridgeServer.setEditActivity(null);
                        }
                    }
                }
            );

            XposedBridge.log(TAG + ": EditActivity hooked ✓");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": EditActivity hook failed: " + e.getMessage());
        }
    }

    /**
     * Hook MainActivity
     * com.vega.main.MainActivity
     */
    private void hookMainActivity(ClassLoader cl) {
        try {
            Class<?> mainActivityClass = XposedHelpers.findClass(
                "com.vega.main.MainActivity", cl);

            XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate",
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": MainActivity onCreate");
                        if (bridgeServer != null) {
                            bridgeServer.setMainActivity(param.thisObject);
                        }
                    }
                }
            );

            XposedBridge.log(TAG + ": MainActivity hooked ✓");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": MainActivity hook failed: " + e.getMessage());
        }
    }

    /**
     * Watermark removal hook
     * Tries to hook common watermark-related methods
     */
    private void hookWatermark(ClassLoader cl) {
        // Try multiple possible watermark class names
        String[] watermarkClasses = {
            "com.vega.export.edit.view.ExportActivity",
            "com.vega.export.business.BusinessExportActivity"
        };

        for (String className : watermarkClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, cl);
                // Hook isWatermarkEnabled or similar
                try {
                    XposedHelpers.findAndHookMethod(clazz, "isWatermarkEnabled",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                param.setResult(false);
                            }
                        }
                    );
                    XposedBridge.log(TAG + ": Watermark hook applied on " + className);
                } catch (NoSuchMethodError ignored) {}

            } catch (Exception ignored) {}
        }
    }

    /**
     * Export quality hook - unlock higher resolution
     */
    private void hookExportQuality(ClassLoader cl) {
        try {
            // Hook resolution/bitrate limits if found
            Class<?> exportClass = XposedHelpers.findClass(
                "com.vega.export.edit.view.ExportActivity", cl);

            XposedHelpers.findAndHookMethod(exportClass, "onCreate",
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log(TAG + ": ExportActivity hooked ✓");
                        if (bridgeServer != null) {
                            bridgeServer.setExportActivity(param.thisObject);
                        }
                    }
                }
            );
        } catch (Exception e) {
            XposedBridge.log(TAG + ": ExportActivity hook skipped: " + e.getMessage());
        }
    }

    public static Context getAppContext() {
        return appContext;
    }
}
