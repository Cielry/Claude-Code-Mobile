const fs = require("fs");
const path = require("path");

let event = {};
try {
  event = JSON.parse(fs.readFileSync(0, "utf8"));
} catch {
  process.exit(0);
}

const cwd = event.cwd || process.cwd();
const sessionId = String(event.session_id || Date.now())
  .replace(/[^a-zA-Z0-9_-]/g, "_");

const transcript = event.transcript_path;
const archiveDir = path.join(cwd, "conversation-archives");
const txtPath = path.join(archiveDir, `session_${sessionId}.txt`);

function formatTime(d) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Shanghai",
    year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit",
    hour12: false,
  }).formatToParts(d);
  const get = (type) => (parts.find((p) => p.type === type) || {}).value || "";
  return `${get("year")}-${get("month")}-${get("day")} ${get("hour")}:${get("minute")}:${get("second")}`;
}

function extract(value) {
  if (typeof value === "string") return value;
  if (!Array.isArray(value)) return "";

  return value.map(block => {
    if (!block || typeof block !== "object") return "";

    if (block.type === "text") return block.text || "";

    if (block.type === "tool_use") {
      return `[工具调用：${block.name || "未知工具"}]\n` +
        JSON.stringify(block.input || {}, null, 2);
    }

    if (block.type === "tool_result") {
      return "[工具结果]\n" + extract(block.content);
    }

    if (block.type === "image") return "[包含图片内容]";
    if (block.type === "thinking") return "";
    if (block.type === "redacted_thinking") return "";

    return "";
  }).filter(Boolean).join("\n\n");
}

try {
  fs.mkdirSync(archiveDir, { recursive: true });

  const entries = [];
  if (transcript && fs.existsSync(transcript)) {
    const raw = fs.readFileSync(transcript, "utf8");

    for (const line of raw.split(/\r?\n/)) {
      if (!line.trim()) continue;

      try {
        const row = JSON.parse(line);
        const role = row.message?.role || row.type;

        if (role !== "user" && role !== "assistant") continue;

        const content = extract(row.message?.content ?? row.content);
        if (!content.trim()) continue;

        entries.push({
          role,
          content,
          timestamp: row.timestamp || ""
        });
      } catch {
        // 跳过无法解析的单行记录
      }
    }

    // 同时保留原始会话记录，便于以后进一步处理
    fs.copyFileSync(
      transcript,
      path.join(archiveDir, `session_${sessionId}.jsonl`)
    );
  }

  // 会话记录可能稍有延迟；补上本次最终可见回复（若尚未写入）
  const lastReply = event.last_assistant_message;
  const assistantTexts = entries
    .filter(item => item.role === "assistant")
    .map(item => item.content);

  if (
    typeof lastReply === "string" &&
    lastReply.trim() &&
    !assistantTexts.includes(lastReply)
  ) {
    entries.push({
      role: "assistant",
      content: lastReply,
      timestamp: new Date().toISOString()
    });
  }

  const lines = [
    "Claude Code 会话存档",
    `项目目录：${cwd}`,
    `会话 ID：${sessionId}`,
    `最近更新：${formatTime(new Date())}`,
    "",
    "说明：此 TXT 根据当前可读取的会话记录生成。",
    "部分非文本内容、隐藏推理或未写入记录的内容不会出现在 TXT 中。",
    ""
  ];

  for (const item of entries) {
    const label = item.role === "user" ? "用户" : "Claude";
    lines.push(`========== ${label} ==========`);
    if (item.timestamp) lines.push(`时间：${item.timestamp}`);
    lines.push("", item.content, "");
  }

  fs.writeFileSync(txtPath, lines.join("\n"), "utf8");
} catch (error) {
  // 归档失败时不阻断正常对话
  process.stderr.write(`会话归档失败：${error.message}\n`);
}
