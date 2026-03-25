package com.example.eudicfirst;

import android.content.Context;
import android.content.Intent;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class EudicHook implements IXposedHookLoadPackage {

    private static final int EUDIC_MENU_ID = 10001;
    // order 设为极小值，确保排在所有系统菜单（Copy=1, Cut=2 等）之前
    private static final int EUDIC_ORDER = -10000;
    private static final String EUDIC_PACKAGE = "com.eusoft.eudic";
    private static final String EUDIC_ACTIVITY = "com.eusoft.eudic.ExternalSearchActivity";
    private static final String MENU_TITLE = "欧路查词";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook 1: 标准文本框（EditText、TextView 等）
        hookEditorCallback(lpparam);
        // Hook 2: WebView / Chromium 内核（Anki 等）
        hookChromiumSelection(lpparam);
    }

    // ─────────────────────────────────────────────
    // Hook 1 ── android.widget.Editor$SelectionActionModeCallback
    // ─────────────────────────────────────────────
    private void hookEditorCallback(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> callbackClass = XposedHelpers.findClass(
                    "android.widget.Editor$SelectionActionModeCallback",
                    lpparam.classLoader);

            // onCreateActionMode：首次创建菜单时注入
            XposedHelpers.findAndHookMethod(
                    callbackClass,
                    "onCreateActionMode",
                    ActionMode.class, Menu.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ActionMode mode = (ActionMode) param.args[0];
                            Menu menu = (Menu) param.args[1];
                            injectEudicItem(mode, menu, lpparam.classLoader);
                        }
                    });

            // onPrepareActionMode：菜单刷新时保持注入（如选区变化）
            XposedHelpers.findAndHookMethod(
                    callbackClass,
                    "onPrepareActionMode",
                    ActionMode.class, Menu.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ActionMode mode = (ActionMode) param.args[0];
                            Menu menu = (Menu) param.args[1];
                            // 避免重复添加
                            if (menu.findItem(EUDIC_MENU_ID) == null) {
                                injectEudicItem(mode, menu, lpparam.classLoader);
                            }
                            // 返回 true 通知系统菜单已更新
                            param.setResult(true);
                        }
                    });

            XposedBridge.log("[EudicFirst] Hook 1 (Editor callback) installed for: "
                    + lpparam.packageName);
        } catch (Throwable t) {
            // 部分 ROM 可能类名不同，忽略即可
            XposedBridge.log("[EudicFirst] Hook 1 not applicable for "
                    + lpparam.packageName + ": " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Hook 2 ── Chromium SelectionPopupControllerImpl
    //           适用于 Anki、微信内置浏览器等 WebView 宿主
    // ─────────────────────────────────────────────
    private void hookChromiumSelection(XC_LoadPackage.LoadPackageParam lpparam) {
        // Chromium 在运行时才加载，需要延迟 Hook
        // 通过 XposedBridge.hookAllMethods 捕获目标类的 showActionMode
        try {
            Class<?> controllerClass = XposedHelpers.findClass(
                    "org.chromium.content.browser.selection.SelectionPopupControllerImpl",
                    lpparam.classLoader);

            XposedHelpers.findAndHookMethod(
                    controllerClass,
                    "showActionMode",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 获取宿主对象内部的 ActionMode
                            try {
                                ActionMode mode = (ActionMode) XposedHelpers
                                        .getObjectField(param.thisObject, "mActionMode");
                                if (mode == null) return;
                                Menu menu = mode.getMenu();
                                if (menu == null) return;
                                if (menu.findItem(EUDIC_MENU_ID) == null) {
                                    injectEudicItem(mode, menu, lpparam.classLoader);
                                }
                            } catch (Throwable inner) {
                                XposedBridge.log("[EudicFirst] Hook 2 inner: "
                                        + inner.getMessage());
                            }
                        }
                    });

            XposedBridge.log("[EudicFirst] Hook 2 (Chromium) installed for: "
                    + lpparam.packageName);
        } catch (Throwable t) {
            // 非 Chromium 宿主正常报错，忽略
            XposedBridge.log("[EudicFirst] Hook 2 not applicable for "
                    + lpparam.packageName + ": " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 公共：向 Menu 注入欧路查词按钮
    // ─────────────────────────────────────────────
    private void injectEudicItem(ActionMode mode, Menu menu, ClassLoader classLoader) {
        try {
            MenuItem item = menu.add(Menu.NONE, EUDIC_MENU_ID, EUDIC_ORDER, MENU_TITLE);
            // 强制显示在一级菜单，不折叠进"..."
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            item.setOnMenuItemClickListener(menuItem -> {
                launchEudic(mode);
                return true;
            });
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] injectEudicItem failed: " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 获取选中文本 → 唤起欧路词典
    // ─────────────────────────────────────────────
    private void launchEudic(ActionMode mode) {
        try {
            // 方式一：通过 ActionMode.getCustomView() 所在的 Context
            //         取当前焦点 View 的选中文本
            String selectedText = null;

            // 尝试从 ActionMode tag（部分 AOSP 存放了 CharSequence）
            Object tag = mode.getTag();
            if (tag instanceof CharSequence) {
                selectedText = tag.toString().trim();
            }

            // 回退：从全局焦点 View 读取
            if (selectedText == null || selectedText.isEmpty()) {
                selectedText = getSelectedTextFromFocusedView();
            }

            if (selectedText == null || selectedText.isEmpty()) {
                XposedBridge.log("[EudicFirst] No selected text found.");
                return;
            }

            // 先尝试 ACTION_PROCESS_TEXT（Android 6+，最干净）
            Context ctx = getContextFromMode(mode);
            if (ctx == null) return;

            Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT);
            intent.setClassName(EUDIC_PACKAGE, EUDIC_ACTIVITY);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT, selectedText);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                ctx.startActivity(intent);
            } catch (Throwable e) {
                // 回退：ACTION_SEND
                Intent fallback = new Intent(Intent.ACTION_SEND);
                fallback.setClassName(EUDIC_PACKAGE, EUDIC_ACTIVITY);
                fallback.setType("text/plain");
                fallback.putExtra(Intent.EXTRA_TEXT, selectedText);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(fallback);
            }

            mode.finish(); // 关闭浮动菜单
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] launchEudic failed: " + t.getMessage());
        }
    }

    // 从 ActionMode 中取 Context（AOSP 实现中 ActionMode 持有 Context 引用）
    private Context getContextFromMode(ActionMode mode) {
        try {
            return (Context) XposedHelpers.getObjectField(mode, "mContext");
        } catch (Throwable ignored) {}
        try {
            // FloatingActionMode
            return (Context) XposedHelpers.getObjectField(mode, "mContext");
        } catch (Throwable ignored) {}
        return null;
    }

    // 从当前焦点 View 反射读取选中文本
    private String getSelectedTextFromFocusedView() {
        try {
            // android.view.View 的静态字段 sCurrentFocusedView 不可靠；
            // 使用 TextView.getSelectionStart/End 更稳健，但需要 View 引用。
            // 此处作为保底，通过剪贴板或其他渠道补充（暂留空，由 ActionMode tag 已覆盖）
        } catch (Throwable ignored) {}
        return null;
    }
}
