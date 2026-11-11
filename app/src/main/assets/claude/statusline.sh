#!/system/bin/sh
# 状态栏：模型 / 输入 / 输出 / 上下文。
# 只依赖系统自带的 sed / awk，不要求环境页安装 jq。
[ -n "$CCM_ENV_DIR" ] && PATH="$CCM_ENV_DIR/bin:$PATH"

input=$(cat)

# 诊断用：保留最近一次 statusline 的原始输入。
printf '%s' "$input" > "$HOME/.claude/statusline-last.json" 2>/dev/null
if [ -n "$CCM_OUTPUT_DIR" ]; then
    mkdir -p "$CCM_OUTPUT_DIR" 2>/dev/null
    printf '%s' "$input" > "$CCM_OUTPUT_DIR/statusline-last.json" 2>/dev/null
fi

# 解析状态行 JSON。字段分别输出为一行内的制表符分隔值。
parsed=$(printf '%s' "$input" | awk '
function num(line, key,   s) {
    if (!match(line, "\"" key "\":[0-9]+")) return ""
    s = substr(line, RSTART, RLENGTH)
    sub(/.*:/, "", s)
    return s
}
{
    model = ""
    if (match($0, /"model":\{[^}]*\}/)) {
        mo = substr($0, RSTART, RLENGTH)
        if (match(mo, /"display_name":"[^"]*"/)) {
            s = substr(mo, RSTART, RLENGTH)
            sub(/^"display_name":"/, "", s)
            sub(/"$/, "", s)
            model = s
        }
    }
    printf "%s\t%s\t%s\t%s\n", model,
        num($0, "total_input_tokens"),
        num($0, "total_output_tokens"),
        num($0, "context_window_size")
}')
model=$(printf '%s' "$parsed" | cut -f1)
in_tok=$(printf '%s' "$parsed" | cut -f2)
out_tok=$(printf '%s' "$parsed" | cut -f3)
window=$(printf '%s' "$parsed" | cut -f4)

[ -n "$in_tok" ] || in_tok=0
[ -n "$out_tok" ] || out_tok=0

# CLI 没有给出 usage 时，从会话记录最后一条 assistant 消息里补算。
if [ "$in_tok" = "0" ]; then
    tpath=$(printf '%s' "$input" | sed -n 's/.*"transcript_path":"\([^"]*\)".*/\1/p')
    if [ -n "$tpath" ] && [ -f "$tpath" ]; then
        vals=$(tail -n 400 "$tpath" 2>/dev/null | awk '
        {
            if (match($0, /"input_tokens":[0-9]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/.*:/, "", s); in_tok = s
            }
            if (match($0, /"output_tokens":[0-9]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/.*:/, "", s); out_tok = s
            }
            if (match($0, /"cache_creation_input_tokens":[0-9]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/.*:/, "", s); cache_new = s
            }
            if (match($0, /"cache_read_input_tokens":[0-9]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/.*:/, "", s); cache_read = s
            }
        }
        END {
            if (in_tok == "") in_tok = 0
            if (out_tok == "") out_tok = 0
            if (cache_new == "") cache_new = 0
            if (cache_read == "") cache_read = 0
            print (in_tok + cache_new + cache_read) " " out_tok
        }')
        if [ -n "$vals" ]; then
            set -- $vals
            in_tok=$1
            out_tok=$2
        fi
    fi
fi

if [ -z "$in_tok" ] || [ "$in_tok" = "0" ]; then
    if [ -n "$model" ]; then
        echo "模型: $model | 输入: 0 | 输出: 0 | 上下文: --"
    else
        echo "输入: 0 | 输出: 0 | 上下文: --"
    fi
    exit 0
fi

case "$window" in
    ''|*[!0-9]*) window=200000 ;;
esac
[ "$window" -gt 0 ] 2>/dev/null || window=200000
# 四舍五入，与 Claude Code 自己的 used_percentage 计算方式一致。
pct=$(( (in_tok * 100 + window / 2) / window ))
[ "$pct" -gt 100 ] && pct=100

if [ -n "$model" ]; then
    echo "模型: $model | 输入: $in_tok | 输出: $out_tok | 上下文: ${pct}%"
else
    echo "输入: $in_tok | 输出: $out_tok | 上下文: ${pct}%"
fi