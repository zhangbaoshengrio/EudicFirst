# EudicFirst

[English](#english) | [中文](#中文)

---

## English

An LSPosed module that injects an **Eudic** lookup button into the floating text-selection toolbar, so you can look up any selected word without switching apps.

### Features

- **OPlus bottom strip** — remaps the toolbar layout to **Eudic · Copy · Select all** (replaces Copy with Eudic, Share with Copy, hides Web Search)
- **WebView apps** (Gemini, AnkiDroid, browsers…) — extracts selected text via JavaScript at selection time
- **Compose apps** (Claude) — silently triggers Copy internally and intercepts it before it reaches the clipboard; no system "Text copied" toast, clipboard is not modified
- **Stale-word prevention** — clears cached text when ActionMode ends, so looking up word B after copying word A always gives the correct result

### Requirements

- OPlus / OnePlus device running Android 16
- [LSPosed](https://github.com/LSPosed/LSPosed) framework installed
- [Eudic (欧路词典)](https://www.eudic.net/) installed

### Installation

1. Download the APK from [Releases](../../releases/latest)
2. Install it on your device
3. Open LSPosed → Modules → enable **EudicFirst**
4. Select all apps you want the button to appear in (or choose System Framework for global effect)
5. Reboot or force-stop the target apps

### How it works

| App type | Text capture method |
|---|---|
| OPlus native / TextView | Direct selection range read |
| WebView (Gemini, AnkiDroid…) | `window.getSelection()` via `evaluateJavascript` |
| Compose (Claude) | Intercept internal Copy action via `ClipboardManager.setPrimaryClip` hook |

---

## 中文

一个 LSPosed 模块，将**欧路词典（Eudic）**查词按钮注入文字选择浮动工具栏，选中单词后无需切换 App 即可直接查词。

### 功能特性

- **OPlus 底部条重映射** — 将工具栏布局改为 **Eudic · 复制 · 全选**（Copy 改为 Eudic，Share 改为 Copy，隐藏 Web Search）
- **WebView 类应用**（Gemini、AnkiDroid、浏览器等）— 选词时通过 JavaScript 提取选中文字
- **Compose 类应用**（Claude）— 内部静默触发 Copy 并在写入剪贴板前拦截：不弹系统"已复制"提示，剪贴板内容不变
- **防查错词** — ActionMode 结束时自动清空缓存文字，复制了 A 词再选 B 词点 Eudic 始终查 B

### 环境要求

- OPlus / OnePlus 设备，Android 16
- 已安装 [LSPosed](https://github.com/LSPosed/LSPosed) 框架
- 已安装[欧路词典](https://www.eudic.net/)

### 安装方法

1. 从 [Releases](../../releases/latest) 下载 APK
2. 在设备上安装
3. 打开 LSPosed → 模块 → 启用 **EudicFirst**
4. 选择需要注入的应用（或选择系统框架以全局生效）
5. 重启设备或强制停止目标应用

### 各应用文字提取方式

| 应用类型 | 文字提取方式 |
|---|---|
| OPlus 原生 / TextView | 直接读取选区范围 |
| WebView（Gemini、AnkiDroid 等） | `evaluateJavascript` 执行 `window.getSelection()` |
| Compose（Claude） | Hook `ClipboardManager.setPrimaryClip` 拦截内部 Copy 动作 |

### 工作原理

模块通过以下 Hook 实现功能：

- **Hook A** `Activity.onActionModeStarted` — 向菜单注入 Eudic 条目，并从菜单 Intent 提取文字
- **Hook A2** `Activity.onActionModeFinished` — 清空缓存，防止查错词
- **Hook C** `Editor.TextActionModeCallback` — 捕获 TextView 的选中文字
- **Hook D** `View.startActionMode` — 多策略提取文字（原生/Compose 反射/WebView JS）
- **Hook F** `Editor.updateAssistMenuItems` — 辅助捕获 TextView 选中文字
- **Hook G** `FloatingToolbar.getVisibleAndEnabledMenuItems` — OPlus 底部条条目重映射
- **Hook H** `ClipboardManager.setPrimaryClip` — 拦截 Compose 应用的剪贴板写入

---

## License

MIT
