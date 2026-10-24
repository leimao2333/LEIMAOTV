#!/usr/bin/env bash
#
# 生成发版签名密钥（SYS-07）。**只跑一次**，跑完把生成的两样东西一起提交：
#
#   git add keystore/leimao-tv.jks keystore.properties
#
# 密钥与配置都随仓库走，CI 不需要配任何 secret —— 这是仓库主人的决定，
# 代价与前提见 docs/发布流程.md「为什么密钥在仓库里」。
#
# 为什么强调「只跑一次」：Android 的包只能用同一把钥匙续签。密钥换了，
# 用户设备上已装的旧版就再也装不上新版（会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE），
# 只能卸载重装 —— 进度、收藏、源配置全没。这不是「麻烦」，是数据丢失。
#
# 用法：
#   bash scripts/create-keystore.sh                 # 交互输入密码
#   KEYSTORE_PASSWORD=xxx bash scripts/create-keystore.sh
#   KEYSTORE_PATH=keystore/other.jks KEY_ALIAS=other bash scripts/create-keystore.sh
#     （换路径生成的密钥不会自动被 .gitignore 放行，需要先改 .gitignore）
#
set -euo pipefail

cd "$(dirname "$0")/.."

ALIAS="${KEY_ALIAS:-leimao-tv}"
OUT="${KEYSTORE_PATH:-keystore/leimao-tv.jks}"

if [ -e "$OUT" ]; then
    cat >&2 <<EOF
密钥已存在：$OUT

覆盖它会让**已发布版本的用户**装不上后续版本（签名不一致，只能卸载重装）。
确认要换一把新钥匙，请先挪走旧的，或者用 KEYSTORE_PATH 换一个路径。
EOF
    exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "找不到 keytool：需要 JDK 17（和构建用的是同一个）。" >&2
    exit 1
fi

if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
    read -r -s -p "给密钥设一个密码（记牢）：" first; echo
    read -r -s -p "再输一次：" second; echo
    if [ "$first" != "$second" ]; then
        echo "两次输入不一致。" >&2
        exit 1
    fi
    KEYSTORE_PASSWORD="$first"
fi

if [ "${#KEYSTORE_PASSWORD}" -lt 6 ]; then
    echo "密码至少 6 位（keytool 的下限）。" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"

# RSA 2048 / 10000 天：Google Play 要求 25 年以上，我们自己发的包更不该过期
keytool -genkeypair \
    -keystore "$OUT" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=LEIMAOTV, OU=leimao.tv, O=leimao, C=CN" \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEYSTORE_PASSWORD"

cat >keystore.properties <<EOF
# 发版签名配置（SYS-07）。**随仓库提交**，CI 与本地都读这一份 —— 发版不需要配任何 secret。
#
# 密钥就在 $OUT，同样随仓库走（这是仓库主人的决定，代价与前提见
# docs/发布流程.md「为什么密钥在仓库里」）。
#
# 注意：Java 读 Properties 时反斜杠是转义符，Windows 上请写正斜杠。
# 这个文件与密钥都不是秘密 —— 能 clone 这个仓库的人就有签名能力。

storeFile=$OUT
storePassword=$KEYSTORE_PASSWORD
keyAlias=$ALIAS
keyPassword=$KEYSTORE_PASSWORD
EOF

echo
echo "已生成：$OUT"
echo "已写入：keystore.properties"
echo
echo "================================================================"
echo "接着把两样一起提交，CI 才能签出包来："
echo
echo "  git add $OUT keystore.properties"
echo
echo "顺手记一句：**能 clone 这个仓库的人就能用这把钥匙签包**。"
echo "仓库现在是公开的话，考虑改成私有，或者把签名能力收回到只有你自己的机器上"
echo "（docs/发布流程.md 里写了怎么改回来）。"
echo "================================================================"
