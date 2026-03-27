package com.example.eudicfirst;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.Selection;
import android.text.Spanned;
import android.webkit.WebView;
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

    private static volatile String sLastSelectedText = null;
    private static volatile java.lang.ref.WeakReference<View> sLastWebView = null;
    private static volatile ActionMode sLastActionMode = null;
    private static volatile ActionMode.Callback sLastCallback = null;
    // 拦截剪贴板写入标志：为 true 时截获文字并跳过实际写入（避免系统提示+避免覆盖剪贴板）
    private static volatile boolean sSuppressNextClipboard = false;

    private static final int    EUDIC_MENU_ID = 10001;
    private static final int    EUDIC_ORDER   = 0;
    private static final String EUDIC_PKG     = "com.qianyan.eudic";
    private static final String EUDIC_ACT     = "com.eusoft.dict.activity.dict.LightpeekActivity";
    private static final String MENU_TITLE    = "Eudic";

    // OPlus 底部条系统 item ID（Android 16 OPlus ROM）
    private static final int ID_COPY       = 33620240;
    private static final int ID_SHARE      = 33620249;
    private static final int ID_SELECT_ALL = 33620248;
    private static final int ID_WEB_SEARCH = 33620252;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (EUDIC_PKG.equals(lp.packageName)) return;

        // Hook A: Activity.onActionModeStarted（非 OPlus 或 OPlus 上划面板兜底）
        hookActivityActionMode(lp);
        // Hook A2: Activity.onActionModeFinished（ActionMode 结束时清空选中文字）
        hookActionModeFinished(lp);
        // Hook C: Editor Callback（原生 TextView 文字选择）
        hookEditorCallbacks(lp);
        // Hook D: View.startActionMode（提取选中文字 + 保存 mode/callback）
        hookViewStartActionMode(lp);
        // Hook F: Editor.updateAssistMenuItems（捕获 TextView 选中文字）
        hookEditorUpdateAssist(lp);
        // Hook G: FloatingToolbar.getVisibleAndEnabledMenuItems（OPlus 底部条重映射）
        hookFloatingToolbarRemap(lp);
        // Hook H: ClipboardManager.setPrimaryClip（拦截 Compose Copy，无提示查词）
        hookClipboardIntercept(lp);
    }

    // ── Hook A ──────────────────────────────────────────────────────────────
    private void hookActivityActionMode(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActionModeStarted",
                    ActionMode.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ActionMode mode = (ActionMode) param.args[0];
                    if (mode == null) return;
                    Menu menu = mode.getMenu();
                    if (menu == null) return;
                    // 从系统菜单 intent 提取选中文字（在 app 主进程有效，即使 WebView 在独立进程）
                    String menuText = getTextFromMenu(menu);
                    if (menuText != null && !menuText.isEmpty()) sLastSelectedText = menuText;
                    if (eudicAlreadyAdded(menu)) return;
                    addItem(menu, mode, (Activity) param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] HookA fail: " + t.getMessage());
        }
    }

    // ── Hook A2: Activity.onActionModeFinished（清空旧选中文字）──────────────
    private void hookActionModeFinished(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActionModeFinished",
                    ActionMode.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sLastSelectedText = null;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] HookA2 fail: " + t.getMessage());
        }
    }

    // ── Hook C: Editor Callback ───────────────────────────────────────────
    private void hookEditorCallbacks(XC_LoadPackage.LoadPackageParam lp) {
        String[] candidates = {
            "android.widget.Editor$TextActionModeCallback",
            "android.widget.Editor$SelectionActionModeCallback",
            "android.widget.Editor$SelectionActionModeCallback2",
        };
        for (String name : candidates) {
            try {
                Class<?> clz = XposedHelpers.findClass(name, lp.classLoader);
                XposedBridge.hookAllMethods(clz, "onCreateActionMode", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Menu menu = (Menu) param.args[1];
                        if (menu == null || eudicAlreadyAdded(menu)) return;
                        String text = getTextFromCallback(param.thisObject);
                        if (text == null) text = getTextFromMenu(menu);
                        addItemWithText(menu, (ActionMode) param.args[0],
                                getContextFromMode((ActionMode) param.args[0]), text);
                    }
                });
                XposedBridge.hookAllMethods(clz, "onPrepareActionMode", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Menu menu = (Menu) param.args[1];
                        if (menu == null || eudicAlreadyAdded(menu)) return;
                        addItem(menu, (ActionMode) param.args[0],
                                getContextFromMode((ActionMode) param.args[0]));
                        param.setResult(true);
                    }
                });
            } catch (XposedHelpers.ClassNotFoundError ignored) {
            } catch (Throwable t) {
                XposedBridge.log("[EudicFirst] HookC fail " + name + ": " + t.getMessage());
            }
        }
    }

    // ── Hook D: View.startActionMode ─────────────────────────────────────
    private void hookViewStartActionMode(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    View.class, "startActionMode",
                    ActionMode.Callback.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if ((int) param.args[1] != 1) return; // TYPE_FLOATING only
                            ActionMode mode = (ActionMode) param.getResult();
                            if (mode == null) return;
                            sLastSelectedText = null;
                            View thisView = (View) param.thisObject;

                            // 1. 原生 TextView 提取
                            String nativeText = selectionFrom(thisView);
                            if (nativeText != null && !nativeText.isEmpty())
                                sLastSelectedText = nativeText;

                            // 2. Compose 等非 WebView：从 ActionMode.Callback 反射取 selectedText
                            if (sLastSelectedText == null || sLastSelectedText.isEmpty()) {
                                String cbText = extractFromCallback(param.args[0]);
                                if (cbText != null && !cbText.isEmpty())
                                    sLastSelectedText = cbText;
                            }

                            // 3. WebView 异步 JS 提取
                            if (thisView instanceof WebView) {
                                sLastWebView = new java.lang.ref.WeakReference<>(thisView);
                                evalJsSelection(thisView, text -> {
                                    if (!text.isEmpty()) sLastSelectedText = text;
                                });
                            } else {
                                try {
                                    thisView.getClass().getMethod("evaluateJavascript",
                                            String.class, android.webkit.ValueCallback.class);
                                    sLastWebView = new java.lang.ref.WeakReference<>(thisView);
                                    evalJsSelection(thisView, text -> {
                                        if (!text.isEmpty()) sLastSelectedText = text;
                                    });
                                } catch (Throwable ignored) { sLastWebView = null; }
                            }

                            // 保存 mode + callback，供 addItem click 在 Compose 等场景下使用
                            sLastActionMode = mode;
                            sLastCallback = (ActionMode.Callback) param.args[0];

                            Menu menu = mode.getMenu();
                            if (menu == null || eudicAlreadyAdded(menu)) return;
                            addItem(menu, mode, thisView.getContext());
                            mode.invalidate();
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] HookD fail: " + t.getMessage());
        }
    }

    // ── Hook F: Editor.updateAssistMenuItems ─────────────────────────────
    private void hookEditorUpdateAssist(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> editorClass = XposedHelpers.findClass("android.widget.Editor", lp.classLoader);
            XposedBridge.hookAllMethods(editorClass, "updateAssistMenuItems", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object tv = getField(param.thisObject, "mTextView");
                        String text = selectionFrom((View) tv);
                        if (text != null && !text.isEmpty()) sLastSelectedText = text;
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] HookF fail: " + t.getMessage());
        }
    }

    // ── Hook G: OPlus FloatingToolbar 底部条重映射 ─────────────────────────
    // 在 getVisibleAndEnabledMenuItems 前修改菜单：
    //   Copy(33620240)  → Eudic（查词）
    //   Share(33620249) → Copy（复制到剪贴板）
    //   WebSearch(33620252) → 隐藏到上划面板
    // 结果底部条显示：Eudic · Copy · Select all
    private void hookFloatingToolbarRemap(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> ftClass = XposedHelpers.findClass(
                    "com.android.internal.widget.floatingtoolbar.FloatingToolbar", lp.classLoader);
            XposedBridge.hookAllMethods(ftClass, "getVisibleAndEnabledMenuItems", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Menu menu = (Menu) param.args[0];
                    if (menu == null) return;
                    for (int i = 0; i < menu.size(); i++) {
                        MenuItem mi = menu.getItem(i);
                        int id = mi.getItemId();
                        // 把非 OPlus 系统 ID 的 Eudic/Copy 条目推到溢出菜单
                        // 避免与 Hook G 改名后的 OPlus Eudic 重复
                        boolean isOplusId = (id == ID_COPY || id == ID_SHARE
                                || id == ID_SELECT_ALL || id == ID_WEB_SEARCH);
                        if (!isOplusId) {
                            String title = String.valueOf(mi.getTitle());
                            if (MENU_TITLE.equals(title) || "Copy".equals(title)) {
                                mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                                continue;
                            }
                        }
                        if (id == ID_COPY) {
                            mi.setTitle(MENU_TITLE);
                            mi.setOnMenuItemClickListener(m -> {
                                String text = sLastSelectedText;
                                if (text != null && !text.isEmpty()) {
                                    launchEudicWithText(text, currentApp());
                                } else {
                                    // sLastSelectedText 未就绪（异步竞争），从 WebView 当场查询
                                    View wv = sLastWebView != null ? sLastWebView.get() : null;
                                    if (wv != null) {
                                        evalJsSelection(wv, t -> {
                                            if (!t.isEmpty()) launchEudicWithText(t, currentApp());
                                        });
                                    }
                                }
                                return true;
                            });
                        } else if (id == ID_SHARE) {
                            mi.setTitle("Copy");
                            mi.setOnMenuItemClickListener(m -> {
                                String text = sLastSelectedText;
                                if (text != null) {
                                    try {
                                        Context ctx = currentApp();
                                        ClipboardManager cm = (ClipboardManager)
                                                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                        if (cm != null)
                                            cm.setPrimaryClip(ClipData.newPlainText("text", text));
                                    } catch (Throwable ignored) {}
                                }
                                return true;
                            });
                        } else if (id == ID_WEB_SEARCH) {
                            mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError ignored) {
            // 非 OPlus 设备
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] HookG fail: " + t.getMessage());
        }
    }

    // ── Hook H: 拦截剪贴板写入（Compose 触发 Copy 时截获文字，不弹系统提示）──
    private void hookClipboardIntercept(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(ClipboardManager.class, "setPrimaryClip",
                    ClipData.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sSuppressNextClipboard) return;
                    sSuppressNextClipboard = false;
                    param.setResult(null); // 跳过真正的剪贴板写入（不弹系统提示）
                    try {
                        ClipData cd = (ClipData) param.args[0];
                        if (cd == null || cd.getItemCount() == 0) return;
                        Context ctx = currentApp();
                        if (ctx == null) return;
                        CharSequence clip = cd.getItemAt(0).coerceToText(ctx);
                        if (clip != null && clip.length() > 0)
                            launchEudicWithText(clip.toString(), ctx);
                    } catch (Throwable t) {
                        XposedBridge.log("[EudicFirst] HookH: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] HookH fail: " + t.getMessage());
        }
    }

    // ── 检查菜单里是否已有我们注入的 Eudic 条目 ─────────────────────────────
    private boolean eudicAlreadyAdded(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem mi = menu.getItem(i);
            if (MENU_TITLE.equals(String.valueOf(mi.getTitle())) && mi.getItemId() != 0) {
                return true;
            }
        }
        return false;
    }

    // ── 把 Share/Copy 原地改造成 Eudic（HookA/D/C 兜底用，非 OPlus 场景）────
    private boolean hijackCopy(Menu menu, String selectedText) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem mi = menu.getItem(i);
            CharSequence t = mi.getTitle();
            if (t == null) continue;
            String ts = t.toString();
            if (ts.equals("Share") || ts.equals("分享") || ts.equals("共享")) {
                mi.setTitle(MENU_TITLE);
                final String text = selectedText != null ? selectedText : sLastSelectedText;
                final Context ctx = currentApp();
                mi.setOnMenuItemClickListener(m -> {
                    launchEudicWithText(text != null ? text : sLastSelectedText, ctx);
                    return true;
                });
                return true;
            }
        }
        return false;
    }

    // ── 公共：添加菜单项（已知文字版）────────────────────────────────────────
    private void addItemWithText(Menu menu, ActionMode mode, Context ctx, String knownText) {
        try {
            String preText = knownText;
            if (preText == null || preText.isEmpty()) preText = getTextFromMenu(menu);
            if (preText == null || preText.isEmpty()) preText = sLastSelectedText;
            final String finalText = preText;
            if (hijackCopy(menu, finalText)) return;
            MenuItem item = menu.add(Menu.NONE, EUDIC_MENU_ID, EUDIC_ORDER, MENU_TITLE);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            item.setOnMenuItemClickListener(m -> {
                String t = finalText != null ? finalText : sLastSelectedText;
                if (t != null && !t.isEmpty()) {
                    Context app = currentApp();
                    if (app != null) { launchEudicWithText(t, app); return true; }
                }
                return true;
            });
        } catch (Throwable e) {
            XposedBridge.log("[EudicFirst] addItemWithText FAIL: " + e.getMessage());
        }
    }

    // ── 公共：添加菜单项 ─────────────────────────────────────────────────────
    private void addItem(Menu menu, ActionMode mode, Context ctx) {
        try {
            final String preText = getTextFromMenu(menu);
            if (hijackCopy(menu, preText)) return;
            MenuItem item = menu.add(Menu.NONE, EUDIC_MENU_ID, EUDIC_ORDER, MENU_TITLE);
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            item.setOnMenuItemClickListener(m -> {
                String t = preText;
                if (t == null || t.isEmpty()) t = sLastSelectedText;
                if (t != null && !t.isEmpty()) {
                    launchEudicWithText(t, currentApp());
                    return true;
                }
                // 无法直接取得选中文字（如 Compose 应用）
                // → 静默触发 Copy，Hook H 拦截写入、截获文字、不弹提示
                ActionMode am = sLastActionMode;
                ActionMode.Callback cb = sLastCallback;
                if (am != null && cb != null) {
                    Menu mn = am.getMenu();
                    if (mn != null) {
                        for (int j = 0; j < mn.size(); j++) {
                            MenuItem ci = mn.getItem(j);
                            int cid = ci.getItemId();
                            String ct = String.valueOf(ci.getTitle());
                            if (cid == android.R.id.copy
                                    || "Copy".equalsIgnoreCase(ct) || "复制".equals(ct)) {
                                sSuppressNextClipboard = true;
                                try { cb.onActionItemClicked(am, ci); } catch (Throwable e2) {
                                    sSuppressNextClipboard = false;
                                    XposedBridge.log("[EudicFirst] copy-trigger fail: " + e2);
                                }
                                return true;
                            }
                        }
                    }
                }
                return true;
            });
        } catch (Throwable e) {
            XposedBridge.log("[EudicFirst] addItem FAIL: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    // ── 从菜单其他条目的 intent 中取 EXTRA_PROCESS_TEXT ─────────────────────
    private String getTextFromMenu(Menu menu) {
        if (menu == null) return null;
        for (int i = 0; i < menu.size(); i++) {
            try {
                MenuItem mi = menu.getItem(i);
                if (mi.getItemId() == EUDIC_MENU_ID) continue;
                Intent intent = mi.getIntent();
                if (intent == null) continue;
                String t = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT);
                if (t == null) {
                    CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                    if (cs != null) t = cs.toString();
                }
                if (t != null && !t.isEmpty()) return t;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ── 从 ActionMode.Callback 反射提取选中文字（Compose / 自定义 view 兜底）─
    private String extractFromCallback(Object callback) {
        if (callback == null) return null;
        Class<?> clz = callback.getClass();
        while (clz != null && clz != Object.class) {
            for (java.lang.reflect.Field f : clz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(callback);
                    String text = null;
                    if (val instanceof String) {
                        text = (String) val;
                    } else if (val != null) {
                        try { text = (String) val.getClass().getMethod("getText").invoke(val); }
                        catch (Throwable ignored) {}
                        if (text == null && val instanceof CharSequence)
                            text = val.toString();
                    }
                    if (text != null && !text.isEmpty() && text.length() < 1000)
                        return text;
                } catch (Throwable ignored) {}
            }
            clz = clz.getSuperclass();
        }
        return null;
    }

    // ── JS 查询 WebView 选中文字（兼容普通 WebView 和混淆子类，支持 iframe）──
    private void evalJsSelection(View view, java.util.function.Consumer<String> callback) {
        String js = "(function(){" +
                "var s=window.getSelection?window.getSelection().toString():'';" +
                "if(s)return s;" +
                "try{for(var i=0;i<frames.length;i++){" +
                "var fs=frames[i].getSelection?frames[i].getSelection().toString():'';" +
                "if(fs)return fs;}}catch(e){}" +
                "return '';})()";
        android.webkit.ValueCallback<String> cb = value -> {
            if (value == null || value.equals("null") || value.equals("\"\"")) {
                callback.accept("");
                return;
            }
            String text = value;
            if (text.startsWith("\"") && text.endsWith("\""))
                text = text.substring(1, text.length() - 1)
                           .replace("\\n", "\n").replace("\\\"", "\"");
            callback.accept(text);
        };
        if (view instanceof WebView) {
            ((WebView) view).evaluateJavascript(js, cb);
        } else {
            try {
                java.lang.reflect.Method m = view.getClass().getMethod(
                        "evaluateJavascript", String.class, android.webkit.ValueCallback.class);
                m.invoke(view, js, cb);
            } catch (Throwable ignored) {}
        }
    }

    // ── 从 Editor Callback 的 mTextView 取选中文字 ───────────────────────────
    private String getTextFromCallback(Object callback) {
        try {
            Object editor = getField(callback, "this$0");
            if (editor == null) editor = getField(callback, "mEditor");
            if (editor == null) return null;
            Object tv = getField(editor, "mTextView");
            if (tv instanceof TextView) return selectionFrom((TextView) tv);
        } catch (Throwable ignored) {}
        return null;
    }

    // ── 查词（已知文字）──────────────────────────────────────────────────────
    private void launchEudicWithText(String text, Context ctx) {
        try {
            if (text == null || text.isEmpty() || ctx == null) return;
            Context app = ctx.getApplicationContext();
            if (app == null) app = ctx;
            Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT);
            intent.setClassName(EUDIC_PKG, EUDIC_ACT);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text);
            intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log("[EudicFirst] launchEudicWithText: " + t);
        }
    }

    private String selectionFrom(View v) {
        if (v == null) return null;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            int s = tv.getSelectionStart(), e = tv.getSelectionEnd();
            if (s >= 0 && e > s && tv.getText() != null)
                return tv.getText().subSequence(s, e).toString();
            return null;
        }
        try {
            Object text = v.getClass().getMethod("getText").invoke(v);
            if (text instanceof Spanned) {
                Spanned sp = (Spanned) text;
                int s = Selection.getSelectionStart(sp);
                int e = Selection.getSelectionEnd(sp);
                if (s >= 0 && e > s) return sp.subSequence(s, e).toString();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(Object obj, String name) {
        try {
            return (T) XposedHelpers.getObjectField(obj, name);
        } catch (Throwable ignored) {}
        try {
            Class<?> clz = obj.getClass();
            while (clz != null) {
                try {
                    Field f = clz.getDeclaredField(name);
                    f.setAccessible(true);
                    return (T) f.get(obj);
                } catch (NoSuchFieldException e) {
                    clz = clz.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Context getContextFromMode(ActionMode mode) {
        if (mode == null) return null;
        try { return getField(mode, "mContext"); } catch (Throwable ignored) {}
        return null;
    }

    private Context currentApp() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentApplication");
        } catch (Throwable ignored) {}
        return null;
    }
}
