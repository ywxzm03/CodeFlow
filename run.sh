#!/bin/bash

# CodeWarp 启动脚本

echo "======================================"
echo "CodeWarp 启动"
echo "======================================"

CONFIG_FILE="$HOME/.codewarp/settings.json"

# 检查配置文件
if [ ! -f "$CONFIG_FILE" ]; then
    echo ""
    echo "⚠️  配置文件不存在，将在首次运行时创建示例配置"
    echo "   配置文件位置: $CONFIG_FILE"
    echo ""
fi

echo ""
echo "启动 CodeWarp..."
echo ""

# 运行应用
./gradlew run --console=plain
