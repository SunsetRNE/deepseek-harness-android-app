# 给下一个 AI 的交接说明（DeepSeek Harness 手机版 · v3）

> 接手前先读这里。本文覆盖截至 2026-08-16 的最新状态。
> 项目已从「Coomi×DSH 融合」转向「**DSH 内核 + 自研手机端界面**」，与 Coomi 彻底脱钩。

---

## 一、项目目标（定版，不变）

做一个**能装到 Android 手机上的 DeepSeek Harness（DSH）App**：

- **内核** = DeepSeek Harness（`@deepseek-ai/dsh` 0.1.0-rc.6），保留插件生态 + RPC API。
- **前端** = DSH 原生界面（`dsh-web-frontend` 的 dist），只做移动端适配，不自己写页面。
- **移动端适配** = 横屏锁定（sensorLandscape）+ 触摸优化 CSS（`mobile-patch/mobile.css`）。
- **手机端特色** = Shizuku 插件 + 文件/系统工具 + **自研原生控制台**（状态/权限/日志/数据管理）。
- **品牌** = "DeepSeek Harness"，图标 = DSH 官方鲸鱼 logo。
- **与 Coomi 无关**。Coomi 只是运行本开发 agent 的宿主 App，**绝不能删它**。

用户硬约束（不可违反）：
1. **不删 Coomi**（宿主 App）。
2. **API Key 不写文件、不打包进 APK**（要分享给别人）。
3. **页面 = DSH 原生界面 + 移动端适配**，不自研页面，功能全保留。
4. 可锁横屏（已锁 sensorLandscape）。
5. 品牌 DeepSeek Harness、鲸鱼图标，不跟 Coomi 沾边。

---

## 二、当前状态快照（2026-08-16）

| 项 | 状态 |
|---|---|
| 最终 APK | `dshapk/DeepSeekHarness.apk`（约 75MB，已签名） |
| 包名 | `com.deepseek.harness` |
| 签名 | `dshapk/release.jks`，密码 `dsh2026`，别名 `dsh`，CN=DeepSeek Harness |
| **targetSdk** | **28（必须保持 28，见踩坑 #11）** |
| minSdk | 24 |
| DSH 内核 | 0.1.0-rc.6，冷启动实测约 7.4 秒 |
| 前端 | DSH 原生 dist + mobile.css 触摸优化 |
| Shizuku | **三层修复已完成并固化进源**（见"已完成"），默认自动执行、无需逐次审批 |
| dshroot 位置 | **外部公共目录 `/sdcard/DeepSeekHarness/dshroot`**（卸载/重装不丢） |
| node 二进制 + .so | 内部 `files/payload/runtime`（/sdcard 根 noexec，必须内部） |
| dshhome（凭证） | 内部 `files/payload/dshhome`（**绝不放外部**） |
| 首屏 | **原生控制台**（替换原权限页，见"架构"） |
| 权限配置 | 9 项，已并进控制台一个区块 |
| 崩溃日志 | `/sdcard/DeepSeekHarness/crash.log`（全局 handler 自动写） |

---

## 三、关键文件路径

```
$HOME = /data/data/com.coomi.android/files/home

runtime/                      Node 26 运行时（bionic 版）
├── bin/node                  node v26.4.0
└── lib/                      依赖库（含 ICU/openssl 软链）

dshroot/                      DSH 本体（开发源）
└── lib/node_modules/@deepseek-ai/dsh/
    └── node_modules/@deepseek-ai/dsh-tool-shizuku/lib/index.js   ← Shizuku 插件（异步 spawn + 自动授权）

mobile-patch/                 移动端适配
├── mobile.css                触摸优化
└── inject.sh                 注入 dist/index.html

dshapk/                       APK 构建工程
├── build.sh                  一键打包（自动 inject + 生成 REVISION）
├── AndroidManifest.xml       包名 com.deepseek.harness，targetSdk 28，锁横屏
├── release.jks               签名
├── res/drawable/ic_launcher.xml   鲸鱼 vector
├── src/com/deepseek/harness/MainActivity.java   ← 原生壳（控制台+权限+数据管理+引擎控制，约 1800 行）
├── libs/shizuku-*.aar        Shizuku API/Provider/AIDL 13.1.5
├── assets/payload.zip        组装好的运行时
├── assets/rish_shizuku.dex   Shizuku 检测用 dex
├── assets/dshroot_revision.txt   版本标记
└── staging/                  payload 源

dsh-patches/                  补丁归档
├── apply.sh                  重新应用源码补丁
├── README.md                 详细文档
└── overlay/.../dsh-tool-shizuku/lib/index.js   改好的 Shizuku 插件

.dsh/                         DSH 配置
├── cordis.patch.yml          关键配置：禁用 pi-ai/sandbox/bash-sandbox、
│                             插入 bash-local + tool-shizuku、
│                             权限默认 danger-full-access
├── settings.yaml
└── profiles/web/

外部运行时（运行时生成）：
/sdcard/DeepSeekHarness/dshroot/      外部 DSH 内核（AI 运行时改的代码在这）
/sdcard/DeepSeekHarness/backup/       数据导出/日志导出目录
/sdcard/DeepSeekHarness/crash.log     崩溃堆栈
/sdcard/DeepSeekHarness/REVISION      版本标记（在 dshroot/ 下）
```

---

## 四、架构

```
APK (com.deepseek.harness)
├── MainActivity（原生壳）
│   ├── 【控制台】（首屏，替换原权限页，跟随系统深/浅色）
│   │   ├── 运行状态：引擎 / Shizuku / 端口 3080
│   │   ├── 系统信息：设备 / Android 版本 / 内存 / 存储
│   │   ├── 权限配置：9 项权限（存储/所有文件/悬浮窗/写设置/使用情况/
│   │   │             安装未知来源/电池优化/通知/Shizuku）
│   │   ├── 运行日志：dsh-web.log 最近 40 行
│   │   ├── 操作：进入 DSH / 启动引擎 / 关闭引擎 / 重启引擎 /
│   │   │        刷新 / 清缓存 / 重置运行时
│   │   └── 数据管理：导出数据 / 导入数据 / 导出日志 / 清空所有数据
│   ├── 【引擎页】WebView 加载 http://127.0.0.1:3080（DSH 原生界面）
│   │   └── 按返回键：WebView 能后退就后退，否则回控制台（不退出 App）
│   ├── 解压 assets/payload.zip
│   │   ├── dshroot → /sdcard/DeepSeekHarness/dshroot（外部，永不覆盖已有文件，
│   │   │           但官方白名单 FORCE_OVERWRITE_PREFIXES 强制覆盖 shizuku 插件）
│   │   └── runtime/dshhome/bin → 内部 files/payload/
│   ├── 重建 runtime/lib 软链（LINKS.txt，link→symlink→copy 三级降级）
│   └── 拉起 node --expose-internals dsh/lib/bin.js web --host 127.0.0.1 --port 3080
└── 前端 = DSH 原生 dist，通过 DSH /api 通信
```

### 控制台引擎控制逻辑
- `startEngineNow()`：`healthOk()` 为真则提示"已在运行"，否则 `startEngine()`
- `stopEngine()`：`nodeProcess.destroy()` → 400ms 后仍存活则 `destroyForcibly()`
- `restartEngine()`：stop → sleep 1.2s（释放端口）→ start
- `nodeProcess` 在 `spawnNode()` 里赋值，是成员变量

### 数据管理逻辑（都在 MainActivity 内）
- **导出数据**：打包 `dshhome/` 成 zip（**排除 `.credentials.yaml`**），存 `backup/dsh-data-时间戳.zip`
- **导入数据**：列出 `backup/dsh-data-*.zip` 供选择，解压到 dshhome（跳过凭证 + 防路径穿越）
- **导出日志**：打包 `dsh-web.log` + `diagnostics.txt`（设备信息+会话清单）成 zip
- **清空所有数据**：二次确认后删外部 dshroot + 内部 payload + rish dex + 日志 + SharedPreferences

### Shizuku 插件（dsh-tool-shizuku，已三层修复）
- **异步 `spawn`**（不是 spawnSync，避免阻塞 node 事件循环 → 之前"Failed to fetch"的根因）
- `sanitizeEnv()`：剥离 `LD_LIBRARY_PATH/LD_PRELOAD/LD_DEBUG`（避免 app_process 链接错 libz.so）
- `ensureDexReadOnly()`：每次执行前 `chmod 444`（Android 15 拒绝加载可写 dex）
- 默认**自动执行**，设 `SHIZUKU_APPROVE=ask` 才逐次审批

---

## 五、DSH API 协议速查（写前端必读）

**通用 RPC**（POST /api/<method>）：
```json
{"type":"client-request","rpcId":"<uuid>","method":"session.prompt","payload":{...}}
```
返回：`{"type":"server-response","rpcId":"...","result":{"ok":true,"value":{...}}}`

**关键方法**：
| 方法 | 说明 |
|---|---|
| `session.create` | 建会话 |
| `session.list` | 会话列表 |
| `session.history` | 拉历史 `{sessionId, maxMessages}` |
| `session.prompt` | 发消息 `{sessionId, mode:"queue", content:[{type:"text",text}]}` |
| `session.models` / `session.selectModel` | 模型列表/切换 |
| `credentials.set` / `credentials.describe` | API Key |
| `workspace.archiveSession` | 归档会话 `{sessionId}` |

**收流式**：WebSocket `ws://127.0.0.1:3080/api/events.mux`，关键事件：
- `turn/start`、`assistant/chunk`（reasoning-delta/text-delta/usage/finish）、`tool/code-dispatch`、`session/title`

---

## 六、已完成清单（含本次会话全部成果）

### Shizuku（三层修复，已固化进源 + 打进 APK）
1. 输出字段统一 `exit_code`（原来 schema 是 `exit_code`、返回值是 `exitCode`，导致 INVALID_TOOL_OUTPUT）
2. `sanitizeEnv()` 剥离 LD_*，解决 app_process 的 libz.so 链接错误
3. `chmod 444` 自愈，解决 Android 15 拒绝加载可写 dex 的 Abort
4. 异步 spawn，解决 node 事件循环阻塞导致的 "Failed to fetch (internal)"

### 存储拆分（dshroot 外部持久化）
- dshroot 放 `/sdcard/DeepSeekHarness/dshroot`（卸载/重装不丢，AI 运行时改动保留）
- node + .so + dshhome 留内部
- 增量更新：外部已有文件不覆盖，官方白名单 `FORCE_OVERWRITE_PREFIXES` 强制覆盖 shizuku 插件
- REVISION 版本标记控制是否补齐

### 原生控制台（替换首次权限页）
- 状态/系统信息/权限/日志/操作/数据管理六区块
- 跟随系统深/浅色
- 引擎启动/关闭/重启
- 导出导入数据（不含 Key）、导出日志、清空所有数据（恢复出厂）

### 其他
- 加载页美化（深色 + 鲸鱼 logo + 品牌名 + 进度条，加载完成自动隐藏）
- 返回键：DSH 界面退无可退时回控制台
- 权限默认 `danger-full-access`（文件沙箱不限制）
- 崩溃日志自动写 `/sdcard/DeepSeekHarness/crash.log`
- 桌宠插件（`dshpet-1`）已归档删除

---

## 七、常用命令

```sh
# 打包 APK
cd $HOME/dshapk && bash build.sh

# 重新应用 Android 源码补丁（升级 DSH 后）
sh $HOME/dsh-patches/apply.sh

# 本地启动 DSH 测试
source $HOME/runtime/env.sh
export DSH_HOME=$HOME/.dsh
node --expose-internals $HOME/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
  web --host 127.0.0.1 --port 3080

# 看 DSH 组合配置（确认 permission 默认值等）
node --expose-internals $HOME/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
  --profile web --dump-config

# Shizuku rish 执行（App 里用，开发调试用）
DEX=$HOME/rish/rish_shizuku.dex
/system/bin/app_process -Djava.class.path="$DEX" /system/bin --nice-name=rish \
  rikka.shizuku.shell.ShizukuShellLoader -c "<命令>"

# 看崩溃日志
cat /sdcard/DeepSeekHarness/crash.log
```

改前端（移动端适配）：改 `mobile-patch/mobile.css` → `bash build.sh` → 重装。
改原生壳（控制台/权限/数据管理）：改 `dshapk/src/.../MainActivity.java` → `bash build.sh` → 重装。

---

## 八、关键踩坑（务必注意，新增 #11 起）

1. **node 运行需 `LD_LIBRARY_PATH=runtime/lib`**。
2. **Android 无 `/usr/bin/env`**，wrapper 脚本 shebang 用 `#!/system/bin/sh`。
3. **DSH 启动必须加 `--expose-internals`**。
4. **Android SELinux 禁硬链接 `link()`**，原子写用 `rename()`。
5. **runtime/lib 有软链**，APK 只打实体文件 + `LINKS.txt`，App 首次启动重建。
6. **`pkill -f` 会误杀自己**，用 `pkill -x node`。
7. **Shizuku `pm install` 在 ColorOS 报 binder 限制**，安装靠用户手动点 APK；`pm clear/uninstall` 可用。
8. **npm/pnpm 是开发工具，不进 APK**。
9. **API Key 绝不写文件/APK**，用户通过页面设置填。
10. **DSH 更新会覆盖补丁和前端**，更新后跑 `apply.sh` + `build.sh`。
11. **⚠️ targetSdk 必须保持 28**：`targetSdk ≥ 29` 时 Android 把应用私有数据目录挂 noexec，node 二进制执行报 EACCES（`error=13, Permission denied`）。之前试过升 33 直接引擎起不来，已回退 28。**除非做 nativeLibraryDir 迁移，否则别动 targetSdk**。
12. **⚠️ 原生 View 重复挂载崩溃**：`webView/statusView/progressBar` 是成员视图，`showEngineScreen()` 和 `showConsole()` 都要先 `detachView()` 再 addView，否则 "child already has a parent" 崩。已修，新增视图时沿用 `detachView()`。
13. **⚠️ Shizuku 回调在 binder 线程**：`onBinderDead` 等回调里更新 UI 必须 `post` 回主线程，`refreshAllStatuses()` 已内置线程保护。
14. **⚠️ 不要用 `spawnSync` 执行耗时命令**：会阻塞 node 事件循环 → 后端假死 → 前端 "Failed to fetch"。用异步 `spawn`。
15. **⚠️ ColorOS 电池优化会切断 Shizuku 连接**：建议用户把 DeepSeek Harness 和 Shizuku 都设"不限制"，否则 Shizuku 通道时不时断。
16. **dshroot 外部更新策略**：`FORCE_OVERWRITE_PREFIXES` 白名单强制覆盖官方插件（目前只有 dsh-tool-shizuku），其余已有文件不覆盖（保 AI 修改）。

---

## 九、下一步待办（建议顺序）

1. **验证最新版**：覆盖安装 → 控制台各功能（进 DSH / 引擎启停 / 返回键回控制台 / 数据导出导入 / 清空）→ 反复切换不崩。
2. **Shizuku 稳定性**：确认电池优化设为不限制后，Shizuku 通道不再断。
3. **可选的返回键优化**：目前"DSH 界面按返回键：WebView 后退，退无可退回控制台"。若用户觉得多按一下，可改"长按返回键回控制台，单击正常后退"。
4. **继续加手机端工具插件**（用户重点）：
   - 可加 `dsh-tool-android`（pm install/uninstall、am start/force-stop、grant/revoke、settings、dumpsys、截图、剪贴板等），危险操作走审批
   - 文件工具 DSH 已内置（fs），bash 已 shim 到 /system/bin/sh
5. 体积优化（可选）。

---

## 十、插件开发路径（可复制）

插件 = npm 包，核心 `ctx.tools.register(defineTool({...}))`，注册三步：
1. 包放 `dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/<名>/`
2. 在 dsh 的 `package.json` dependencies 加包名
3. 在 `$DSH_HOME/cordis.patch.yml` 的 `insert:` 加 `- id: <名> name: '@deepseek-ai/<名>'`
4. 归档到 `dsh-patches/overlay/`，更新 `apply.sh`

Shizuku 调用（已验证）：
```sh
DEX=/data/user/0/com.deepseek.harness/files/rish/rish_shizuku.dex
/system/bin/app_process -Djava.class.path="$DEX" /system/bin --nice-name=rish \
  rikka.shizuku.shell.ShizukuShellLoader -c "<命令>"
```
环境变量 `RISH_APPLICATION_ID=com.deepseek.harness`。

**注意**：DSH 自带 bash 工具已 shim 到 /system/bin/sh，文件工具 fs 已可用；缺的是 Shizuku 特权层（已有 dsh-tool-shizuku）和更多系统能力插件。

---

## 十一、Shell 权限实测清单（无 root，Shizuku shell 模式）

DSH AI 通过 `shizuku_shell` 实测（uid=2000, u:r:shell:s0）：

✅ 能：读 /system /vendor、读写 /data/local/tmp、读写 /sdcard、pm/am/settings/dumpsys、input、screencap

❌ 不能：读其他 App 私有数据（/data/user/0/*）、读 /data/system（packages.xml）、改 /system 分区、真正 root 全文件访问

要全文件访问只有 root（Magisk）或 userdebug 固件。这台是量产版（ro.debuggable=0、无 su），shell 就是上限。
