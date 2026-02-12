#!/bin/bash
# 这是一个爬取节点信息进行AES加密、Base64编码成nodes文件并最终上传七牛云对象存储的Shell脚本

# ================= 用户配置区域 =================
# 1. 七牛云密钥
QN_AK="填写你的AK密钥"
QN_SK="填写你的SK密钥"
QN_BUCKET="填写你的存储桶名称"

# 2. 上传设置
SOURCE_URL="https://gist.githubusercontent.com/shuaidaoya/9e5cf2749c0ce79932dd9229d9b4162b/raw/base64.txt"
LOCAL_FILE="nodes"

# 文件保存到对象存储的apis目录下，文件名为nodes
REMOTE_KEY="apis/nodes"

# 3. 加密设置
# 必须为 16 字符 (AES-128 要求 16 字节密钥)
AES_KEY_STR="填写你的AES密钥"

# ================= 系统配置 =================
QSHELL_VERSION="v2.13.0"
QSHELL_URL="https://devtools.qiniu.com/qshell-${QSHELL_VERSION}-linux-amd64.tar.gz"
LOG_FILE="/tmp/qiniu_sync_task.log"

# ==================== 工具函数 ====================
log() { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] \033[32m$1\033[0m"; }
warn() { echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] \033[33m$1\033[0m"; }

handle_error() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] \033[31m❌ 发生严重错误: $1\033[0m"
    if [ -f "$LOG_FILE" ]; then
        echo "⬇️ --- 错误日志详情 --- ⬇️"
        tail -n 15 "$LOG_FILE"
        echo "⬆️ ---------------------- ⬆️"
    fi
    rm -f "$LOG_FILE"
    exit 1
}

check_root() {
    if [ "$(id -u)" -ne 0 ]; then warn "提示：当前不是 root 用户，可能需要 sudo 权限。"; fi
}

# ==================== 阶段一：环境检查与安装 ====================
prepare_environment() {
    # 1. 检查 Python3 和 pip (用于 GCM 加密)
    if ! command -v python3 &> /dev/null; then
        handle_error "系统未安装 python3，请先安装。"
    fi

    # 2. 检查并安装 pycryptodome 库
    # 这是一个标准的 Python 加密库，比 openssl 命令行更适合处理 GCM Tag
    if ! python3 -c "import Crypto" &> /dev/null; then
        log "⚙️ 检测到缺失 Python 加密库，正在安装 pycryptodome..."
        
        # 尝试安装 pip
        if ! command -v pip3 &> /dev/null; then
            if command -v apt-get &> /dev/null; then
                sudo apt-get update && sudo apt-get install -y python3-pip > "$LOG_FILE" 2>&1
            elif command -v yum &> /dev/null; then
                sudo yum install -y python3-pip > "$LOG_FILE" 2>&1
            fi
        fi

        # 安装库
        pip3 install pycryptodome > "$LOG_FILE" 2>&1
        if [ $? -ne 0 ]; then 
            # 备选方案：有些系统包名叫 python3-pycryptodome
            if command -v apt-get &> /dev/null; then
                 sudo apt-get install -y python3-pycryptodome > "$LOG_FILE" 2>&1
            else
                 handle_error "无法安装 pycryptodome 库，AES-GCM 加密无法执行。"
            fi
        fi
    fi

    # 3. 检查 qshell
    if ! command -v qshell &> /dev/null; then
        log "⚙️ 未检测到 qshell，开始自动安装..."
        wget "$QSHELL_URL" -O qshell.tar.gz > "$LOG_FILE" 2>&1
        tar -xvf qshell.tar.gz > "$LOG_FILE" 2>&1
        if [ -w /usr/local/bin ]; then
            mv qshell /usr/local/bin/ && chmod +x /usr/local/bin/qshell
        else
            sudo mv qshell /usr/local/bin/ && sudo chmod +x /usr/local/bin/qshell
        fi
        rm -f qshell.tar.gz
    fi
}

# ==================== 阶段二：业务逻辑 ====================

check_root
prepare_environment

# 1. 下载
log "⬇️ 正在下载节点文件..."
curl -fsSL "$SOURCE_URL" -o "$LOCAL_FILE" 2> "$LOG_FILE"
if [ ! -s "$LOCAL_FILE" ]; then handle_error "下载失败或文件为空。"; fi
log "✅ 下载成功 (原始大小: $(du -h "$LOCAL_FILE" | cut -f1))"

# 2. AES-GCM 加密 (使用 Python 嵌入脚本)
log "🔒 正在执行 AES-128-GCM 加密..."

# Python 脚本：生成 IV -> 加密 -> 拼接(IV+Cipher+Tag) -> Base64
# 这样可以精确控制二进制结构
python3 -c "
import sys
import os
import base64
from Crypto.Cipher import AES

try:
    # 1. 读取配置
    key_str = '$AES_KEY_STR'
    input_file = '$LOCAL_FILE'
    output_file = input_file + '.b64'

    # 2. 准备数据
    key = key_str.encode('utf-8') # 确保是 bytes
    if len(key) != 16:
        print('Error: Key must be 16 bytes for AES-128')
        sys.exit(1)

    with open(input_file, 'rb') as f:
        plaintext = f.read()

    # 3. 初始化 GCM
    # GCM 推荐 IV 长度为 12 字节 (96 bits)
    iv = os.urandom(12)
    cipher = AES.new(key, AES.MODE_GCM, nonce=iv)

    # 4. 加密并获取 Tag
    # encrypt_and_digest 会返回 (密文, AuthTag)
    # 默认 AuthTag 长度为 16 字节
    ciphertext, tag = cipher.encrypt_and_digest(plaintext)

    # 5. 拼接数据结构
    # 格式: [IV 12bytes] + [Ciphertext] + [AuthTag 16bytes]
    final_data = iv + ciphertext + tag

    # 6. Base64 编码 (无换行)
    b64_result = base64.b64encode(final_data).decode('utf-8')

    # 7. 写入文件
    with open(output_file, 'w') as f:
        f.write(b64_result)

except Exception as e:
    print(f'Error: {str(e)}')
    sys.exit(1)
" > "$LOG_FILE" 2>&1

if [ $? -ne 0 ]; then
    handle_error "Python 加密脚本执行失败，请查看日志。"
fi

# 移动生成的 .b64 文件覆盖原文件
if [ -s "${LOCAL_FILE}.b64" ]; then
    mv "${LOCAL_FILE}.b64" "$LOCAL_FILE"
    log "✅ 加密完成 (IV+Cipher+Tag -> Base64)"
else
    handle_error "加密产物为空。"
fi

# 3. 七牛鉴权
log "🔑 正在刷新七牛云鉴权..."
qshell user remove auto_bot > /dev/null 2>&1
qshell account "$QN_AK" "$QN_SK" "auto_bot" > "$LOG_FILE" 2>&1
if [ $? -ne 0 ]; then handle_error "七牛 AK/SK 鉴权失败。"; fi

# 4. 上传
log "⬆️ 正在上传到 [$QN_BUCKET]..."
qshell fput "$QN_BUCKET" "$REMOTE_KEY" "$LOCAL_FILE" --overwrite > "$LOG_FILE" 2>&1

if [ $? -ne 0 ]; then
    handle_error "上传失败。"
else
    log "✅ 任务全部完成！"
    echo "--------------------------------"
    echo "远程路径: $REMOTE_KEY"
    echo "加密模式: AES-128-GCM"
    echo "数据结构: Base64( IV[12] + Cipher + Tag[16] )"
    echo "--------------------------------"
fi

rm -f "$LOG_FILE"