#!/bin/bash
# 这是一个爬取节点信息进行AES加密、Base64编码成nodes文件并最终上传Cloudflare R2对象存储的Shell脚本

# ================= 🔧 用户配置区域 (必须修改) =================

# 1. Cloudflare R2 配置
# 账户 ID (在 R2 概览页右侧获取)
R2_ACCOUNT_ID="填写你的_Cloudflare_Account_ID"

# R2 API 令牌 (在 Manage R2 API Tokens 中创建，需读写权限)
R2_ACCESS_KEY_ID="填写你的_Access_Key_ID"
R2_SECRET_ACCESS_KEY="填写你的_Secret_Access_Key"

# 存储桶名称
R2_BUCKET="填写你的存储桶名称"

# 你的自定义域名 (用于生成最终的下载链接)
# 注意：包含协议https且不带末尾的 /
R2_CUSTOM_DOMAIN="填写你的你的自定义域名"

# 2. 上传文件路径设置
# 下载源
SOURCE_URL="https://gist.githubusercontent.com/shuaidaoya/9e5cf2749c0ce79932dd9229d9b4162b/raw/base64.txt"
# 本地临时文件名
LOCAL_FILE="nodes"

# 远程存储路径 (不需要开头的 /)
# 例如: apis/nodes
REMOTE_KEY="apis/${LOCAL_FILE}"

# 3. 加密设置 (AES-128-GCM)
# 密钥必须为 16 字符
AES_KEY_STR="填写你的密钥必须为 16 字符"

# ================= ⚙️ 系统配置 (无需修改) =================
LOG_FILE="/tmp/r2_sync_task.log"

# ==================== 🛠 工具函数 ====================
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
    if [ "$(id -u)" -ne 0 ]; then warn "提示：当前不是 root 用户，安装软件可能需要 sudo 权限。"; fi
}

# ==================== 📦 阶段一：环境检查与安装 ====================
prepare_environment() {
    # 1. 检查 Python3 (用于加密)
    if ! command -v python3 &> /dev/null; then
        handle_error "系统未安装 python3，请先安装。"
    fi

    # 2. 检查 Python 加密库
    if ! python3 -c "import Crypto" &> /dev/null; then
        log "⚙️ 正在安装 Python 加密库 (pycryptodome)..."
        if ! command -v pip3 &> /dev/null; then
             if command -v apt-get &> /dev/null; then sudo apt-get update && sudo apt-get install -y python3-pip > "$LOG_FILE" 2>&1
             elif command -v yum &> /dev/null; then sudo yum install -y python3-pip > "$LOG_FILE" 2>&1; fi
        fi
        pip3 install pycryptodome > "$LOG_FILE" 2>&1 || sudo apt-get install -y python3-pycryptodome > "$LOG_FILE" 2>&1
    fi

    # 3. 检查 AWS CLI (用于上传到 R2)
    # Cloudflare R2 完美兼容 S3 协议，使用 aws-cli 是最稳定的方式
    if ! command -v aws &> /dev/null; then
        log "⚙️ 未检测到 aws-cli，正在安装..."
        if command -v apt-get &> /dev/null; then
            sudo apt-get update && sudo apt-get install -y awscli > "$LOG_FILE" 2>&1
        elif command -v yum &> /dev/null; then
            sudo yum install -y awscli > "$LOG_FILE" 2>&1
        else
            # 通用安装方式
            curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip" > "$LOG_FILE" 2>&1
            unzip awscliv2.zip > "$LOG_FILE" 2>&1
            sudo ./aws/install > "$LOG_FILE" 2>&1
            rm -rf aws awscliv2.zip
        fi
        
        if ! command -v aws &> /dev/null; then
             handle_error "aws-cli 安装失败，请手动安装 (apt install awscli)。"
        fi
        log "✅ aws-cli 安装完成。"
    fi
}

# ==================== 🚀 阶段二：业务逻辑 ====================

check_root
prepare_environment

# 1. 验证配置是否填写
if [[ "$R2_ACCOUNT_ID" == *"填写"* ]]; then
    handle_error "请先编辑脚本，填写 R2_ACCOUNT_ID 等配置信息！"
fi

# 2. 下载文件
log "⬇️ 正在下载节点文件..."
curl -fsSL "$SOURCE_URL" -o "$LOCAL_FILE" 2> "$LOG_FILE"
if [ ! -s "$LOCAL_FILE" ]; then handle_error "下载失败或文件为空。"; fi
log "✅ 下载成功 (原始大小: $(du -h "$LOCAL_FILE" | cut -f1))"

# 3. AES-GCM 加密 (Python 脚本)
log "🔒 正在执行 AES-128-GCM 加密..."
python3 -c "
import sys, os, base64
from Crypto.Cipher import AES

try:
    key = '$AES_KEY_STR'.encode('utf-8')
    if len(key) != 16: sys.exit('Key length error')
    
    with open('$LOCAL_FILE', 'rb') as f: plaintext = f.read()
    
    iv = os.urandom(12)
    cipher = AES.new(key, AES.MODE_GCM, nonce=iv)
    ciphertext, tag = cipher.encrypt_and_digest(plaintext)
    
    # 结构: IV(12) + Ciphertext + Tag(16)
    final_data = iv + ciphertext + tag
    b64_result = base64.b64encode(final_data).decode('utf-8')
    
    with open('$LOCAL_FILE', 'w') as f: f.write(b64_result)
except Exception as e:
    print(e); sys.exit(1)
" > "$LOG_FILE" 2>&1

if [ $? -ne 0 ]; then handle_error "加密失败，请检查 Python 环境。"; fi
log "✅ 加密完成。"

# 4. 上传到 Cloudflare R2
log "⬆️ 正在上传到 Cloudflare R2 ($R2_BUCKET)..."

# 设置临时环境变量供 aws-cli 使用
export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
export AWS_DEFAULT_REGION="auto" # R2 不需要特定区域，auto 即可

# 构造 R2 的 S3 API 端点 URL
R2_ENDPOINT="https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com"

# 执行上传
# s3://bucket/key
aws s3 cp "$LOCAL_FILE" "s3://${R2_BUCKET}/${REMOTE_KEY}" \
    --endpoint-url "$R2_ENDPOINT" \
    --content-type "text/plain" \
    --no-progress > "$LOG_FILE" 2>&1

if [ $? -ne 0 ]; then
    handle_error "上传失败。请检查 R2 Account ID 和 Key 是否正确。"
else
    # 构造最终的公共访问链接
    FINAL_URL="${R2_CUSTOM_DOMAIN}/${REMOTE_KEY}"
    
    log "✅ 任务全部完成！"
    echo "--------------------------------"
    echo "自定义域名链接: $FINAL_URL"
    echo "加密模式: AES-128-GCM + Base64"
    echo "--------------------------------"
fi

# 清理
rm -f "$LOG_FILE"
# rm -f "$LOCAL_FILE" # 如果需要保留本地文件用于调试，请注释此行