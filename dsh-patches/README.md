# DeepSeek Harness（DSH 内核 + 自研手机端界面）

> 内核用 DeepSeek Harness（DSH），前端是我们自研的手机端聊天界面，打包成 Android APK。
> 与 Coomi 无关。

## 目录结构（关键）

```
home/
├── runtime/                  Node 26 运行时（node + 依赖库，Android bionic 版）
├── dshroot/                  DSH 本体（@deepseek-ai/dsh，含 Android 补丁）
├── webapp/                   ★ 我们自研的手机端前端（index.html + app.css + app.js + DSH 图标）
├── dshapk/                   APK 构建工程（Android 壳 + build.sh + release.jks）
├── dsh-patches/              DSH 源码补丁归档（升级后重新应用）
└── node-global/              npm/pnpm 全局工具（仅开发环境，不进 APK）
```

## 架构

```
APK (com.deepseek.harness)
├── Android 壳（MainActivity：WebView + 首次解压 payload + 拉起 node）
├── payload.zip（首次解压到 files/）
│   ├── runtime/               node 二进制 + .so
│   ├── dshroot/               DSH 内核（含我们的 webapp 页面）
│   ├── dshhome/               DSH 配置（无凭证）
│   └── bin/bash               shim（/system/bin/sh）
└── 前端 = webapp/ 页面，通过 DSH 的 /api 接口通信
```

- 页面加载 `http://127.0.0.1:3080`
- 发消息：`POST /api/session.prompt`
- 收流式回复：WebSocket `ws://127.0.0.1:3080/api/events.mux`

## Android 源码补丁（dsh-patches/）

DSH 有 4 处需要适配 Android 的源码改动，更新 DSH 后需重新应用：

| 补丁 | 原因 | 改动 |
|---|---|---|
| dsh-subprocess-local | node-pty 原生模块 Android 无法编译 | 用 child_process 模拟 |
| dsh-attachment-local | sharp 原生模块 + Android 禁硬链接 | 纯 JS 头解析 + link→rename |
| dsh-bash-local | 原生沙箱禁用后需兼容 | 补 sandboxMode getter |
| dsh-session-persistence-jsonl | Android SELinux 禁硬链接 | link→rename |

## 如何升级 DSH 版本

```sh
# 1. 更新 DSH 包（开发环境，需 npm）
source runtime/env.sh
cd dshroot/lib/node_modules/@deepseek-ai/dsh
npm install @deepseek-ai/dsh@latest

# 2. 重新应用 Android 源码补丁（带语法自检，源码变了会报错提醒）
sh dsh-patches/apply.sh

# 3. 重新精简体积（DSH 更新可能拉回被删的 SDK，见 trim-quarantine/）

# 4. 重新打包 APK（会自动用 webapp/ 覆盖前端）
cd dshapk && bash build.sh
```

## 如何打包 APK

```sh
cd dshapk && bash build.sh
# 产物：dshapk/DeepSeekHarness.apk
```

build.sh 会自动：
1. 组装 payload（runtime + dshroot + dshhome + bin）
2. 用 `webapp/` 覆盖 DSH 前端目录（保证自研页面生效）
3. 安全检查（payload 里禁止出现 API Key / .credentials.yaml）
4. aapt/javac/d8/zipalign/apksigner 全流程

## 前端自研页面（webapp/）

- 纯 HTML/CSS/JS，无框架，手机优先
- 顶部标题栏（新会话 + DeepSeek Harness + 设置）
- 消息区（用户右侧蓝气泡，助手左侧白气泡，流式渲染）
- 底部输入框 + 发送
- 思考过程（reasoning）可折叠展示
- 设置里可填 DeepSeek API Key（`credentials.set`，存本机，不进 APK）

改页面 = 改 `webapp/` 里的文件，重新 `build.sh` 即可，不碰 DSH 源码。

## 插件管理

DSH 插件系统（cordis）通过 `dsh plugin`（内部转 pnpm）安装。开发环境已装好 pnpm：

```sh
source runtime/env.sh
export DSH_HOME=/data/data/com.coomi.android/files/home/.dsh
node --expose-internals dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
  plugin --profile web add <插件包名>
```

装完重新 `build.sh` 打进 APK。注意：插件若依赖原生模块需像上面 4 个补丁一样做 Android 适配。

## 关键注意事项

- node 运行需要 `LD_LIBRARY_PATH=runtime/lib`（libz/libicu 等软链由 APK 首次解压后按 LINKS.txt 重建）
- DSH 启动必须 `--expose-internals`（否则 HMR 报错）
- Android 无 `/usr/bin/env`，所有 wrapper 用 `#!/system/bin/sh`
- API Key 通过页面 `credentials.set` 写入本机，**绝不打包进 APK**（分享给别人的前提）
