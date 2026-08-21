package com.deepseek.harness;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 定时任务后台执行器（⑥ 全自动）：
 * 闹钟到点后由 AlarmReceiver 调用，**不依赖 Activity** ——
 * 直接定位引擎文件、启动 node、等 HTTP 就绪、调 DSH API 让 AI 自动执行任务。
 * 引擎已在运行时（3080 有响应）直接复用，不重复启动。
 */
public final class ScheduleExecutor {
    private static final String TAG = "ScheduleExecutor";
    private static final String REL_BINJS = "lib/node_modules/@deepseek-ai/dsh/lib/bin.js";
    private static final String PREFS = "dsh_setup";

    private ScheduleExecutor() {}

    /** 引擎端口：与 MainActivity 保持一致（它换端口后会持久化到 SharedPreferences）。
     *  修复 v1.5.1：原来硬编码 3080，主引擎换端口后定时任务仍连 3080 → 连到占位服务/失败。 */
    private static int enginePort(Context ctx) {
        try {
            int saved = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt("engine_port", 0);
            if (saved >= 3080 && saved <= 3099) return saved;
        } catch (Throwable ignored) {}
        return 3080; // 默认（与 MainActivity 一致）
    }

    /** 执行一条定时任务（后台线程，调用方勿阻塞主线程）。 */
    public static void execute(Context ctx, String task) {
        if (task == null || task.isEmpty()) return;
        log(ctx, "开始执行任务: " + task);
        try {
            if (!engineReady(ctx)) {
                log(ctx, "引擎未运行，尝试启动…");
                if (!startEngine(ctx)) {
                    log(ctx, "引擎启动失败，无法自动执行任务");
                    return;
                }
            }
            // 等引擎完全就绪
            for (int i = 0; i < 30; i++) {
                if (engineReady(ctx)) break;
                Thread.sleep(1000);
            }
            if (!engineReady(ctx)) {
                log(ctx, "引擎 30 秒未就绪，放弃");
                return;
            }
            String sessionId = createSession(ctx);
            if (sessionId == null) {
                log(ctx, "创建会话失败（可能未配置 API Key）");
                return;
            }
            String promptResp = sendPromptRaw(ctx, sessionId, task);
            log(ctx, promptResp == null
                    ? "任务发送失败（无响应）: " + task
                    : (promptResp.contains("\"ok\":true")
                        ? "任务已发送给 AI: " + task
                        : "任务发送失败，响应: " + promptResp.replace("\n", " ").substring(0, Math.min(300, promptResp.length()))));
        } catch (Throwable t) {
            log(ctx, "执行异常: " + t.getMessage());
        }
    }

    /** 引擎是否已在目标端口响应，且确认是 DSH 引擎（首页含 <title>DeepSeek Harness</title>）。
     *  修复 v1.5.1：原来任意 HTTP 200-499 都算就绪，占位服务会被误判为"引擎就绪"。 */
    private static boolean engineReady(Context ctx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + enginePort(ctx) + "/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            if (code < 200 || code >= 500) return false;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            try { in.close(); } catch (Throwable ignored) {}
            if (n <= 0) return false;
            return new String(buf, 0, n, "UTF-8").contains("<title>DeepSeek Harness</title>");
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** 启动 node 引擎（同 MainActivity.spawnNode 的环境变量；payload 必须已解压）。
     *  端口 = enginePort(ctx)，与主引擎换端口后保持一致。 */
    private static boolean startEngine(Context ctx) {
        try {
            File payload = new File(ctx.getFilesDir(), "payload");
            File node = new File(payload, "runtime/bin/node");
            // dshroot：本包外部目录优先（正式版 DeepSeekHarness / Lite DeepSeekHarnessLite），
            // 再回退另一版本目录，最后内部 fallback（修复 v1.5.1：原顺序 Lite 恒优先，正式版会错用 Lite 的 dshroot）
            String selfRoot = ctx.getPackageName().contains(".beta")
                    ? "DeepSeekHarnessLite" : "DeepSeekHarness";
            String otherRoot = selfRoot.equals("DeepSeekHarnessLite") ? "DeepSeekHarness" : "DeepSeekHarnessLite";
            File dshroot = null;
            for (String root : new String[]{selfRoot, otherRoot}) {
                File ext = new File(android.os.Environment.getExternalStorageDirectory(), root + "/dshroot");
                if (new File(ext, REL_BINJS).exists()) { dshroot = ext; break; }
            }
            if (dshroot == null) {
                File internal = new File(payload, "dshroot");
                if (new File(internal, REL_BINJS).exists()) dshroot = internal;
            }
            if (dshroot == null) { log(ctx, "dshroot 未找到"); return false; }
            File binjs = new File(dshroot, REL_BINJS);
            File lib = new File(payload, "runtime/lib");
            File home = new File(payload, "dshhome");
            File bin = new File(payload, "bin");
            File tmp = new File(ctx.getCacheDir(), "tmp");
            if (!tmp.exists()) tmp.mkdirs();
            if (!node.exists()) { log(ctx, "node 缺失"); return false; }
            if (!node.canExecute()) node.setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder(
                    node.getAbsolutePath(), "--expose-internals", binjs.getAbsolutePath(),
                    "web", "--host", "127.0.0.1", "--port", String.valueOf(enginePort(ctx)));
            java.util.Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
            env.put("PATH", bin.getAbsolutePath() + ":" +
                    new File(payload, "runtime/bin").getAbsolutePath() + ":/system/bin:/system/xbin");
            env.put("HOME", ctx.getFilesDir().getAbsolutePath());
            env.put("DSH_HOME", home.getAbsolutePath());
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("TERM", "xterm");
            env.put("SHIZUKU_APP_ID", ctx.getPackageName());
            env.put("SHIZUKU_AVAILABLE", "0");
            env.put("ROOT_AVAILABLE", "0");
            env.put("APP_NOTIFY_PORT", String.valueOf(enginePort(ctx) + 1));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // 日志写入 dsh-web.log
            final File logFile = new File(ctx.getFilesDir(), "dsh-web.log");
            final InputStream is = proc.getInputStream();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        FileOutputStream fos = new FileOutputStream(logFile, true);
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = is.read(b)) > 0) { fos.write(b, 0, n); fos.flush(); }
                        fos.close();
                    } catch (Throwable ignored) {}
                }
            }, "sched-node-log").start();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startEngine error", t);
            return false;
        }
    }

    /** 调 DSH API 创建会话。 */
    private static String createSession(Context ctx) {
        String json = rpc(ctx, "session.create", "{}");
        if (json == null) return null;
        // 解析 result.value.sessionId 或 result.sessionId
        int i = json.indexOf("\"sessionId\":\"");
        if (i >= 0) {
            int q1 = i + "\"sessionId\":\"".length();
            int q2 = json.indexOf('"', q1);
            if (q2 > q1) return json.substring(q1, q2);
        }
        return null;
    }

    /** 调 DSH API 发送消息。 */
    private static boolean sendPrompt(Context ctx, String sessionId, String text) {
        String payload = "{\"sessionId\":\"" + sessionId + "\",\"mode\":\"queue\",\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(text) + "\"}]}";
        String json = rpc(ctx, "session.prompt", payload);
        return json != null && json.contains("\"ok\":true");
    }

    /** 调 DSH API 发送消息，返回完整响应（诊断用）。 */
    private static String sendPromptRaw(Context ctx, String sessionId, String text) {
        String payload = "{\"sessionId\":\"" + sessionId + "\",\"mode\":\"queue\",\"content\":[{\"type\":\"text\",\"text\":\"" + escapeJson(text) + "\"}]}";
        return rpc(ctx, "session.prompt", payload);
    }

    /** DSH RPC 调用：标准协议 {"type":"client-request","rpcId":"...","method":"...","payload":{...}} */
    private static String rpc(Context ctx, String method, String payloadJson) {
        try {
            URL url = new URL("http://127.0.0.1:" + enginePort(ctx) + "/api/" + method);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            String rpcId = "sched-" + System.currentTimeMillis();
            String body = "{\"type\":\"client-request\",\"rpcId\":\"" + rpcId + "\",\"method\":\"" + method
                    + "\",\"payload\":" + (payloadJson == null || payloadJson.isEmpty() ? "{}" : payloadJson) + "}";
            c.getOutputStream().write(body.getBytes("UTF-8"));
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                in.close();
                c.disconnect();
                return new String(out.toByteArray(), "UTF-8");
            }
            c.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "rpc " + method + " error", t);
        }
        return null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 追加执行记录到外部目录（Lite 版用 DeepSeekHarnessLite，正式版用 DeepSeekHarness，便于排查）。 */
    static void log(Context ctx, String msg) {
        try {
            String rootName = ctx.getPackageName().contains(".beta")
                    ? "DeepSeekHarnessLite" : "DeepSeekHarness";
            File root = new File(android.os.Environment.getExternalStorageDirectory(), rootName);
            if (!root.exists()) root.mkdirs();
            File f = new File(root, "scheduled-log.txt");
            FileOutputStream fos = new FileOutputStream(f, true);
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n";
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {}
    }
}
