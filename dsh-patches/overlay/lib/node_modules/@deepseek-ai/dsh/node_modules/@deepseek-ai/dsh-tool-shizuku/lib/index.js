/**
 * Android Shizuku 工具插件：给 DeepSeek Harness 提供特权 shell 能力。
 *
 * 默认在用户已授权的 Shizuku 通道下自动执行（无需逐次审批）。
 * 如需恢复"每次确认"，在 MainActivity 里给 node 设置环境变量 SHIZUKU_APPROVE=ask。
 *
 * 注意：使用异步 spawn 而非 spawnSync，避免同步阻塞 node 事件循环，
 * 否则命令执行期间整个 DSH 后端（HTTP/WebSocket）会卡死，前端报 "Failed to fetch"。
 */
import { defineTool } from "@deepseek-ai/dsh-tools";
import { spawn } from "node:child_process";
import { chmodSync, existsSync } from "node:fs";

const name = "tool-shizuku";
const inject = ["tools"];

const APP_PROC = "/system/bin/app_process";
const SHIZUKU_LOADER = "rikka.shizuku.shell.ShizukuShellLoader";
const MAX_STDOUT = 8000;
const MAX_STDERR = 2000;

/**
 * 剥离会污染系统 app_process 链接的环境变量。
 * DSH 运行时为了加载 Node 自带的 .so 会设置 LD_LIBRARY_PATH（内含自定义 libz.so，
 * SONAME=libz.so.1），而 /system/bin/app_process 是系统二进制，必须用系统库
 * （/apex 的 libunwindstack.so 需要 SONAME=libz.so）。原样继承 process.env 会让
 * 系统链接器报 "cannot find libz.so from verneed[1]"。
 */
function sanitizeEnv(env) {
  const clean = { ...env };
  delete clean.LD_LIBRARY_PATH;
  delete clean.LD_PRELOAD;
  delete clean.LD_DEBUG;
  return clean;
}

/**
 * 确保 rish dex 只读。Android 15 的 ART 拒绝加载"当前 uid 可写"的 dex
 * （logcat: Writable dex file ... is not allowed → Abort）。MainActivity 从 assets
 * 提取的 dex 默认是 600（属主可写），所以每次执行前强制 chmod 444。
 */
function ensureDexReadOnly(dex) {
  try {
    if (dex && existsSync(dex)) chmodSync(dex, 0o444);
  } catch (_) {
    // 尽力而为：chmod 失败不阻断，让 app_process 的报错自然暴露。
  }
}

/** 异步调用 rish 执行一条 shell 命令（不阻塞事件循环，内部函数）。 */
function shizukuCmd(command, dex, appId, timeoutMs) {
  if (!dex) {
    return Promise.resolve({ ok: false, exit_code: -1, stdout: "", stderr: "", error: "SHIZUKU_DEX 未配置" });
  }
  ensureDexReadOnly(dex);
  const timeout = Math.max(1000, Math.min(timeoutMs || 30000, 120000));
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(APP_PROC, [
        `-Djava.class.path=${dex}`,
        "/system/bin",
        "--nice-name=rish",
        SHIZUKU_LOADER,
        "-c", command
      ], {
        env: { ...sanitizeEnv(process.env), RISH_APPLICATION_ID: appId || "com.deepseek.harness" },
        stdio: ["ignore", "pipe", "pipe"]
      });
    } catch (e) {
      resolve({ ok: false, exit_code: -1, stdout: "", stderr: "", error: String(e && e.message || e) });
      return;
    }

    let stdout = "";
    let stderr = "";
    let settled = false;
    const timer = setTimeout(() => {
      try { child.kill("SIGKILL"); } catch (_) {}
    }, timeout);

    const finish = (ok, exitCode, err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({
        ok,
        exit_code: exitCode,
        stdout: stdout.trim().slice(0, MAX_STDOUT),
        stderr: stderr.trim().slice(0, MAX_STDERR),
        ...(err ? { error: err } : {})
      });
    };

    child.stdout.on("data", (d) => { stdout += d; });
    child.stderr.on("data", (d) => { stderr += d; });
    child.on("error", (e) => finish(false, -1, String(e && e.message || e)));
    child.on("close", (code, signal) => {
      const killed = signal === "SIGKILL" && code === null;
      if (killed) {
        finish(false, -1, "命令超时（" + timeout + "ms）被强制终止");
      } else {
        finish(code === 0, code ?? -1, undefined);
      }
    });
  });
}

function apply(ctx) {
  const approval = () => ctx.get("approval");

  // 1) 特权 shell（默认自动执行，SHIZUKU_APPROVE=ask 时逐次审批）
  ctx.tools.register(defineTool({
    name: "shizuku_shell",
    description:
      "通过 Shizuku（Android 特权通道）以系统 shell 权限执行一条命令，用于普通 bash 工具做不到的、需要系统/root 级权限的操作（例如 pm install/uninstall、am 启动/停止应用、settings 修改系统设置、dumpsys 查询系统状态、grant/revoke 运行时权限等）。" +
      "命令会在用户已授权的 Shizuku 通道下自动执行（默认无需逐次确认）；仅当环境变量 SHIZUKU_APPROVE=ask 时才需要逐次审批。优先用普通 `bash` 工具，只有确实需要系统特权时才用本工具。",
    parameters: {
      command: {
        type: "string",
        required: true,
        description: "要执行的 shell 命令。会以系统 shell 权限运行，请写清楚、可审计。"
      },
      timeout_ms: {
        type: "number",
        description: "超时毫秒，默认 30000，最大 120000。"
      }
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          ok: { type: "boolean", required: true },
          exit_code: { type: "number" },
          stdout: { type: "string" },
          stderr: { type: "string" },
          error: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: (value.ok ? "" : "执行失败：" + (value.error || value.stderr || "未知错误") + "\n\n") +
          "exit_code: " + value.exit_code + "\n" +
          (value.stdout ? "stdout:\n" + value.stdout : "") +
          (value.stderr ? "\nstderr:\n" + value.stderr : "")
      }]
    },
    async execute(args, exec) {
      // 审批：默认自动允许。如需每次确认，设 SHIZUKU_APPROVE=ask。
      if (process.env.SHIZUKU_APPROVE === "ask") {
        const approver = approval();
        if (approver === undefined) {
          throw new Error("审批服务未挂载，无法安全执行特权命令");
        }
        const reason = "Shizuku 特权命令：" + args.command;
        const outcome = await approver.request({
          agent: exec.agent,
          toolName: "shizuku_shell",
          callId: exec.callId,
          reason,
          signal: exec.signal
        });
        if (outcome !== "allowed-once") {
          throw new Error(`特权命令未获批准（${outcome}）：${args.command}`);
        }
      }
      return await shizukuCmd(args.command, process.env.SHIZUKU_DEX, process.env.SHIZUKU_APP_ID, args.timeout_ms);
    }
  }));

  // 2) 授权状态探测（只读，不审批）
  ctx.tools.register(defineTool({
    name: "shizuku_status",
    description: "检查 Shizuku 是否可用且当前应用已获授权。返回是否可用，以及失败时的原因（例如用户尚未在 Shizuku App 里授权本应用，或 Shizuku 服务端未运行）。",
    parameters: {},
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: {
          available: { type: "boolean", required: true },
          detail: { type: "string" }
        }
      },
      render: (_args, value) => [{
        type: "text",
        text: value.available ? "Shizuku 可用且已授权" : "Shizuku 不可用：" + (value.detail || "未知原因")
      }]
    },
    async execute() {
      const dex = process.env.SHIZUKU_DEX;
      if (!dex) return { available: false, detail: "SHIZUKU_DEX 未配置" };
      const r = await shizukuCmd("echo __SHIZUKU_OK__", dex, process.env.SHIZUKU_APP_ID, 10000);
      const available = r.ok && r.stdout.includes("__SHIZUKU_OK__");
      let detail;
      if (available) {
        detail = "可用";
      } else if (r.error || /Permission denied|not found|Aborted|CANNOT LINK|Writable dex/i.test(r.stderr || "")) {
        detail = "Shizuku 服务端未运行或本应用未授权：" + (r.stderr || r.error || r.stdout || "未知错误");
      } else {
        detail = r.stdout || r.stderr || r.error || "未授权或未运行";
      }
      return { available, detail };
    }
  }));
}

export { apply, inject, name };
