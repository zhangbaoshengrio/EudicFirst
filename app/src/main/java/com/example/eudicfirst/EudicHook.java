package com.example.eudicfirst;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class EudicHook implements IXposedHookLoadPackage {

    private static final int EUDIC_MENU_ID    = 10001;
    private static final int EUDIC_ORDER      = -10000;
    private static final String EUDIC_PKG     = "com.eusoft.eudic";
    private static final String EUDIC_ACT     = "com.eusoft.eudic.ExternalSearchActivity";
    private static final String MENU_TITLE    = "欧路查词";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (EUDIC_PKG.equals(lp.packageName)) return;

        XposedBridge.log("[EudicFirst] handleLoadPackage: " + lp.packageName);

        // Hook 1: Activity.onActionModeStarted（最广覆盖）
        hookActivityActionMode(lp);

        // Hook 2: FloatingToolbar.setMenu（更底层，不受 Activity 子类影响）
        hookFloatingToolbar(lp);

        // Hook 3: Editor Callback 系列（标准文本框补充）
        hookEditorCallbacks(lp);
    }

    // ── Hook 1 ──────────────────────────────────────────────────────────────
    private void hookActivityActionMode(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActionModeStarted",
                    ActionMode.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    ActionMode mode  = (ActionMode) param.args[0];
                    if (mode == null) return;
                    Menu menu = mode.getMenu();
                    if (menu == null || !isTextSelection(menu)) return;
                    if (menu.findItem(EUDIC_MENU_ID) != null) return;

                    addItem(menu, mode, activity);
                    XposedBridge.log("[EudicFirst] Hook1 injected in " + lp.packageName);
                }
            });
            XposedBridge.log("[EudicFirst] Hook1 registered: " + lp.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] Hook1 fail: " + t);
        }
    }

    // ── Hook 2 ──────────────────────────────────────────────────────────────
    private void hookFloatingToolbar(XC_LoadPackage.LoadPackageParam lp) {
        // android.view.FloatingToolbar 是 API 23+ 的内部类，所有 App 都有
        try {
            Class<?> ftClass = XposedHelpers.findClass(
                    "android.view.FloatingToolbar", lp.classLoader);

            XposedBridge.hookAllMethods(ftClass, "setMenu", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Menu menu = (Menu) param.args[0];
                        if (menu == null || !isTextSelection(menu)) return;
                        if (menu.findItem(EUDIC_MENU_ID) != null) return;

                        // FloatingToolbar 持有 mContext
                        Context ctx = getField(param.thisObject, "mContext");
                        // FloatingToolbar 持有 mActionMode
                        ActionMode mode = getField(param.thisObject, "mActionMode");

                        addItem(menu, mode, ctx);
                        XposedBridge.log("[EudicFirst] Hook2(FloatingToolbar) injected in "
                                + lp.packageName);
                    } catch (Throwable t) {
                        XposedBridge.log("[EudicFirst] Hook2 inner: " + t);
                    }
                }
            });
            XposedBridge.log("[EudicFirst] Hook2 registered: " + lp.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] Hook2 fail: " + t);
        }
    }

    // ── Hook 3 ──────────────────────────────────────────────────────────────
    private void hookEditorCallbacks(XC_LoadPackage.LoadPackageParam lp) {
        String[] candidates = {
            "android.widget.Editor$SelectionActionModeCallback",
            "android.widget.Editor$SelectionActionModeCallback2",
            "android.widget.Editor$TextActionModeCallback",
        };
        for (String name : candidates) {
            try {
                Class<?> clz = XposedHelpers.findClass(name, lp.classLoader);
                XposedBridge.hookAllMethods(clz, "onCreateActionMode", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ActionMode mode = (ActionMode) param.args[0];
                        Menu menu       = (Menu) param.args[1];
                        if (menu == null || menu.findItem(EUDIC_MENU_ID) != null) return;
                        addItem(menu, mode, getContextFromMode(mode));
                        XposedBridge.log("[EudicFirst] Hook3(" + name + ") injected");
                    }
                });
                XposedBridge.hookAllMethods(clz, "onPrepareActionMode", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ActionMode mode = (ActionMode) param.args[0];
                        Menu menu       = (Menu) param.args[1];
                        if (menu == null || menu.findItem(EUDIC_MENU_ID) != null) return;
                        addItem(menu, mode, getContextFromMode(mode));
                        param.setResult(true);
                    }
                });
                XposedBridge.log("[EudicFirst] Hook3 registered: " + name);
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) {
                XposedBridge.log("[EudicFirst] Hook3 fail " + name + ": " + t);
            }
        }
    }

    // ── 公共：添加菜单项 ────────────────────────────────────────────────────
    private void addItem(Menu menu, ActionMode mode, Context ctx) {
        MenuItem item = menu.add(Menu.NONE, EUDIC_MENU_ID, EUDIC_ORDER, MENU_TITLE);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(m -> {
            launchEudic(mode, ctx);
            return true;
        });
    }

    // ── 查词 ────────────────────────────────────────────────────────────────
    private void launchEudic(ActionMode mode, Context ctx) {
        try {
            String text = extractSelected(mode, ctx);
            if (text == null || text.isEmpty()) {
                XposedBridge.log("[EudicFirst] no selected text");
                return;
            }
            XposedBridge.log("[EudicFirst] launching eudic with: " + text);

            Context appCtx = (ctx != null) ? ctx.getApplicationContext() : null;
            if (appCtx == null) return;

            Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT);
            intent.setClassName(EUDIC_PKG, EUDIC_ACT);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                appCtx.startActivity(intent);
            } catch (Throwable e) {
                XposedBridge.log("[EudicFirst] fallback to ACTION_SEND: " + e.getMessage());
                Intent fb = new Intent(Intent.ACTION_SEND);
                fb.setClassName(EUDIC_PKG, EUDIC_ACT);
                fb.setType("text/plain");
                fb.putExtra(Intent.EXTRA_TEXT, text);
                fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appCtx.startActivity(fb);
            }

            if (mode != null) mode.finish();
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] launchEudic: " + t);
        }
    }

    // ── 选中文字提取（三级回退）──────────────────────────────────────────────
    private String extractSelected(ActionMode mode, Context ctx) {
        // 1. FloatingActionMode.mOriginatingView
        if (mode != null) {
            try {
                View v = getField(mode, "mOriginatingView");
                String t = selectionFrom(v);
                if (t != null) return t;
            } catch (Throwable ignored) {}
        }

        // 2. Activity.getCurrentFocus
        if (ctx instanceof Activity) {
            try {
                View v = ((Activity) ctx).getCurrentFocus();
                String t = selectionFrom(v);
                if (t != null) return t;
            } catch (Throwable ignored) {}
        }

        // 3. 剪贴板（用户通常会先复制一次，或系统自动写入）
        if (ctx != null) {
            try {
                ClipboardManager cm = (ClipboardManager) ctx
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    CharSequence clip = cm.getPrimaryClip()
                            .getItemAt(0).coerceToText(ctx);
                    if (clip != null && clip.length() > 0) return clip.toString();
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private String selectionFrom(View v) {
        if (!(v instanceof TextView)) return null;
        TextView tv = (TextView) v;
        int s = tv.getSelectionStart(), e = tv.getSelectionEnd();
        if (s >= 0 && e > s && tv.getText() != null)
            return tv.getText().subSequence(s, e).toString();
        return null;
    }

    // ── 工具 ────────────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private <T> T getField(Object obj, String name) {
        try {
            return (T) XposedHelpers.getObjectField(obj, name);
        } catch (Throwable t) {
            // 尝试父类字段
            try {
                Class<?> clz = obj.getClass();
                while (clz != null) {
                    try {
                        Field f = clz.getDeclaredField(name);
                        f.setAccessible(true);
                        return (T) f.get(obj);
                    } catch (NoSuchFieldException ignored) {
                        clz = clz.getSuperclass();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Context getContextFromMode(ActionMode mode) {
        if (mode == null) return null;
        try { return getField(mode, "mContext"); } catch (Throwable ignored) {}
        return null;
    }

    private boolean isTextSelection(Menu menu) {
        return menu.findItem(android.R.id.copy)      != null
            || menu.findItem(android.R.id.cut)       != null
            || menu.findItem(android.R.id.selectAll) != null
            || menu.findItem(android.R.id.shareText) != null;
    }
}
