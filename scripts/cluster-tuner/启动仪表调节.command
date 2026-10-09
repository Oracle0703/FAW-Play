#!/bin/zsh
TOOL_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
cd -- "$TOOL_DIR" || exit 1
python3 "$TOOL_DIR/tuner.py"
if [[ $? -ne 0 ]]; then
    print '启动失败。请保留上面的错误信息。按回车关闭。'
    read
fi
