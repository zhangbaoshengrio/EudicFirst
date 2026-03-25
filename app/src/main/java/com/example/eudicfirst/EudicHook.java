package com.example.eudicfirst;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class EudicHook implements IXposedHookLoadPackage {

    private static final int EUDIC_MENU_ID = 10001;
    private static final int EUDIC_ORDER = -10000;
    private static final String EUDIC_PACKAGE = "com.eusoft.eudic";
    private static final String EUDIC_ACTIVITY = "com.eusoft.eudic.ExternalSearchActivity";
    private static final String MENU_TITLE = "欧路查词";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 跳过欧路词典自身
        if (EUDIC_PACKAGE.equals(lpparam.packageName)) return;

        hookActivityActionMode(lpparam);
        hookEditorCallback(lpparam);
    }

    // ─────────────────────────────────────────────
    // Hook 1（主力）：Activity.onActionModeStarted
    // 任何 App 弹出 ActionMode 时都会触发，最可靠
    // ─────────────────────────────────────────────
    private void hookActivityActionMode(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "onActionModeStarted",
                    ActionMode.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                ActionMode mode = (ActionMode) param.args[0];
                                if (mode == null) return;

                                Menu menu = mode.getMenu();
                                if (menu == null) return;

                                // 只在有文字选择相关菜单项时注入（Copy/Cut/Paste 的 id 是固定的）
                                if (!isTextSelectionMenu(menu)) return;

                                if (menu.findItem(EUDIC_MENU_ID) != null) return;

                                MenuItem item = menu.add(
                                        Menu.NONE, EUDIC_MENU_ID, EUDIC_ORDER, MENU_TITLE);
                                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                                item.setOnMenuItemClickListener(menuItem -> {
                                    launchEudic(activity, mode);
                                    return true;
                                });
                            } catch (Throwable t) {
                                XposedBridge.log("[EudicFirst] onActionModeStarted inner: " + t);
                            }
                        }
                    });

            XposedBridge.log("[EudicFirst] Hook1(Activity) OK: " + lpparam.packageName);
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] Hook1 failed for " + lpparam.packageName + ": " + t);
        }
    }

    // ─────────────────────────────────────────────
    // Hook 2（补充）：Editor$SelectionActionModeCallback
    // 覆盖部分不走 Activity.onActionModeStarted 的场景
    // ─────────────────────────────────────────────
    private void hookEditorCallback(XC_LoadPackage.LoadPackageParam lpparam) {
        // 尝试多个可能的内部类名（不同 ROM/Android 版本不同）
        String[] classNames = {
                "android.widget.Editor$SelectionActionModeCallback",
                "android.widget.Editor$SelectionActionModeCallback2",
                "android.widget.Editor$TextActionModeCallback",
        };

        for (String className : classNames) {
            try {
                Class<?> clz = XposedHelpers.findClass(className, lpparam.classLoader);
                hookCallbackClass(clz, lpparam.packageName);
                XposedBridge.log("[EudicFirst] Hook2 found: " + className);
            } catch (XposedHelpers.ClassNotFoundError ignored) {
                // 该类不存在则跳过
            } catch (Throwable t) {
                XposedBridge.log("[EudicFirst] Hook2 err " + className + ": " + t);
            }
        }
    }

    private void hookCallbackClass(Class<?> clz, String pkg) {
        XposedBridge.hookAllMethods(clz, "onCreateActionMode", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ActionMode mode = (ActionMode) param.args[0];
                Menu menu = (Menu) param.args[1];
                if (menu == null || menu.findItem(EUDIC_MENU_ID) != null) return;
                injectItem(mode, menu);
            }
        });

        XposedBridge.hookAllMethods(clz, "onPrepareActionMode", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ActionMode mode = (ActionMode) param.args[0];
                Menu menu = (Menu) param.args[1];
                if (menu == null || menu.findItem(EUDIC_MENU_ID) != null) return;
                injectItem(mode, menu);
                param.setResult(true);
            }
        });
    }

    // ─────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────

    /** 判断是否是文字选择菜单（包含 Copy/Cut/SelectAll 等）*/
    private boolean isTextSelectionMenu(Menu menu) {
        // android.R.id.copy = 0x01020030, cut = 0x0102002f, selectAll = 0x01020035
        return menu.findItem(android.R.id.copy) != null
                || menu.findItem(android.R.id.cut) != null
                || menu.findItem(android.R.id.selectAll) != null
                || menu.findItem(android.R.id.shareText) != null;
    }

    private void injectItem(ActionMode mode, Menu menu) {
        // 从 mode 中拿不到 Activity，通过 view 反射获取 context
        Context ctx = getContextFromMode(mode);
        MenuItem item = menu.add(Menu.NONE, EUDIC_MENU_ID, EUDIC_ORDER, MENU_TITLE);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(menuItem -> {
            launchEudic(ctx, mode);
            return true;
        });
    }

    private void launchEudic(Context ctx, ActionMode mode) {
        try {
            String text = getSelectedText(mode, ctx);
            if (text == null || text.isEmpty()) {
                XposedBridge.log("[EudicFirst] No selected text");
                return;
            }

            Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT);
            intent.setClassName(EUDIC_PACKAGE, EUDIC_ACTIVITY);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                ctx.startActivity(intent);
            } catch (Throwable e) {
                // 回退 ACTION_SEND
                Intent fallback = new Intent(Intent.ACTION_SEND);
                fallback.setClassName(EUDIC_PACKAGE, EUDIC_ACTIVITY);
                fallback.setType("text/plain");
                fallback.putExtra(Intent.EXTRA_TEXT, text);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(fallback);
            }

            mode.finish();
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] launchEudic: " + t);
        }
    }

    /** 从 ActionMode 关联的 View 里取选中文字 */
    private String getSelectedText(ActionMode mode, Context ctx) {
        try {
            // FloatingActionMode 持有 mOriginatingView
            View view = (View) XposedHelpers.getObjectField(mode, "mOriginatingView");
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                int start = tv.getSelectionStart();
                int end = tv.getSelectionEnd();
                if (start >= 0 && end > start) {
                    return tv.getText().subSequence(start, end).toString();
                }
            }
        } catch (Throwable ignored) {}

        // 从 Activity 的当前焦点 View 取
        if (ctx instanceof Activity) {
            try {
                View focused = ((Activity) ctx).getCurrentFocus();
                if (focused instanceof TextView) {
                    TextView tv = (TextView) focused;
                    int start = tv.getSelectionStart();
                    int end = tv.getSelectionEnd();
                    if (start >= 0 && end > start) {
                        return tv.getText().subSequence(start, end).toString();
                    }
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private Context getContextFromMode(ActionMode mode) {
        try {
            return (Context) XposedHelpers.getObjectField(mode, "mContext");
        } catch (Throwable ignored) {}
        return null;
    }
}
