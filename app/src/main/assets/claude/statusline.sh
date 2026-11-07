#!/system/bin/sh
# 确保能用到环境页安装的 jq
[ -n "$CCM_ENV_DIR" ] && PATH="$CCM_ENV_DIR/bin:$PATH"

input=$(cat)

# 诊断用：保留最近一次 statusline 的原始输入。
printf '%s' "$input" > "$HOME/.claude/statusline-last.json" 2>/dev/null
if [ -n "$CCM_OUTPUT_DIR" ]; then
    mkdir -p "$CCM_OUTPUT_DIR" 2>/dev/null
    printf '%s' "$input" > "$CCM_OUTPUT_DIR/statusline-last.json" 2>/dev/null
fi
cwd=$(printf '%s' "$input" | jq -r '.cwd // empty' 2>/dev/null)
[ -n "$cwd" ] && [ -d "$cwd" ] && printf '%s' "$input" > "$cwd/.ccm-statusline.json" 2>/dev/null

in_tok=$(printf '%s' "$input" | jq -r '.context_window.total_input_tokens // 0' 2>/dev/null)
out_tok=$(printf '%s' "$input" | jq -r '.context_window.total_output_tokens // 0' 2>/dev/null)
pct=$(printf '%s' "$input" | jq -r '.context_window.used_percentage // empty' 2>/dev/null)
window=$(printf '%s' "$input" | jq -r '.context_window.context_window_size // 0' 2>/dev/null)
tpath=$(printf '%s' "$input" | jq -r '.transcript_path // empty' 2>/dev/null)

# CLI 没有给出 usage 时，从会话记录最后一条 assistant 消息里补算。
if [ "$in_tok" = "0" ] && [ -n "$tpath" ] && [ -f "$tpath" ]; then
    vals=$(tail -n 400 "$tpath" 2>/dev/null | jq -s -r '
        ([.[] | (.message.usage? // empty)] | last) as $u |
        if $u == null then "0 0" else
            "\(($u.input_tokens // 0) + ($u.cache_creation_input_tokens // 0) + ($u.cache_read_input_tokens // 0)) \($u.output_tokens // 0)"
        end
    ' 2>/dev/null)
    if [ -n "$vals" ]; then
        set -- $vals
        in_tok=$1
        out_tok=$2
    fi
fi

if [ "$in_tok" = "0" ]; then
    echo "输入: 0 | 输出: 0 | 上下文: --"
    exit 0
fi

case "$pct" in
    ''|null|0)
        case "$window" in
            ''|0|null|*[!0-9]*) window=200000 ;;
        esac
        pct=$((in_tok * 100 / window))
        [ "$pct" -gt 100 ] && pct=100
        ;;
esac

echo "输入: $in_tok | 输出: $out_tok | 上下文: ${pct}%"