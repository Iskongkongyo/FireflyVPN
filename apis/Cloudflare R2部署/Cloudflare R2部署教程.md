# Cloudflare R2部署教程

无论是API文件还是APP文件下载都可以使用Cloudflare R2对象存储！这里用 **自定义域名 `firefly.202132.xyz`** 做演示，给出**Cloudflare 配置步骤 + 可直接用的 GitHub Actions** （并把下载链接设计成 **稳定的 latest 永久链接**）。

------

> [!CAUTION]
>
> 截止2026年2月12日，按 Cloudflare R2 **官方免费额度**来算，目前**31MB APK** 每月大概能支持的“免费下载次数”主要看 **请求次数（Class B operations）**，不是看流量，因为 **R2 出网（egress）不收费**。
>
> ### R2 免费额度（每月）
>
> - **存储：10GB-month**（31MB 很轻松）
> - **Class B 操作：1000 万次 / 月**（包含 `GetObject/HeadObject` 等读取请求）
> - **Egress：免费**（出网流量不收费）
>
> ### 换算成“下载次数/月”
>
> 一次 APK 下载通常至少触发 **1 次 `GET`**（= 1 次 Class B）。有些客户端下载/浏览器可能会多一个 `HEAD` 或重试（那就算 2 次甚至更多）。
>
> - **最理想（1 次请求/下载）**：`10,000,000 ÷ 1` ≈ **1000 万次下载/月**
> - **更保守（2 次请求/下载）**：`10,000,000 ÷ 2` ≈ **500 万次下载/月**
> - **再保守（3 次请求/下载）**：≈ **333 万次下载/月**
>
> > 注意：31MB 的“流量”本身不是 R2 的瓶颈（因为 egress 免费），真正先到上限的是“每次下载产生的读取请求数”。
>
> ### 超出免费额度会怎样？会不会爆炸扣费？
>
>  超出后是按量计费，R2 的 **Class B** 价格是 **$0.36 / 百万次请求（Standard）**。
>  所以就算你到 2000 万次下载/月（假设 1 请求/次），超出的 1000 万次请求大约也就是 **$3.6/月** 量级（不含存储等极小项）。

------

## 一、Cloudflare R2 + 自定义域名配置（一次性）

### 0) 前提

确保 `202132.xyz` 这个域名已经在 Cloudflare 里（DNS 托管到 Cloudflare）。

> R2 的“自定义域名”能力依赖 Cloudflare 的域名/规则体系。

------

### 1) 创建 R2 Bucket

Cloudflare Dashboard → **R2 Object Storage** → Create bucket
 例如 bucket 名叫：`firefly-download`

------

### 2) 给 Bucket 绑定自定义域名

进入这个 bucket 的设置页面，找到 **Custom domains**（自定义域名）：

- 添加：`firefly.202132.xyz`

Cloudflare 会在你的 DNS 里自动生成一个 **R2 只读记录**（通常不用你手动建 CNAME）。

完成后，你的文件就可以用这种形式访问：

- `https://firefly.202132.xyz/latest/FireflyVPN.apk`
- `https://firefly.202132.xyz/v1.10.0/FireflyVPN.apk`

------

### 3) 强烈建议：给这个域名开启缓存（否则每次下载都算 R2 请求）

Cloudflare 文档明确提到：默认只缓存部分文件类型，要缓存桶里的所有文件需要设置 **Cache Everything**。

做法（两种任选一种）：

**做法 A：Cache Rules（推荐）**

- Rules → Cache Rules → Create rule
  - When incoming requests match:
    - Hostname equals `firefly.202132.xyz`
  - Cache eligibility: **Eligible for cache**（或 “Cache everything” 类似选项）
  - Edge TTL：比如 7 天（随你）
  - 也可以加 “Tiered Cache/Smart Tiered Cache”（加速回源）

**做法 B：Page Rules（老方式）**

- Rules → Page Rules
  - URL pattern：`https://firefly.202132.xyz/*`
  - Setting：**Cache Level: Cache Everything**

> 这样大多数下载会命中 Cloudflare 边缘缓存，R2 的 Class B 请求数会大幅下降（更不容易触免费额度上限）。

------

## 二、生成 R2 S3 API 凭证（给 GitHub Actions 用）

Cloudflare R2 → **API / Tokens**（R2 tokens）创建一个 **R2 Access Key**：

你会得到三样东西：

- **Access Key ID**
- **Secret Access Key**（创建时只显示一次，务必保存）
- **Account ID**（仪表盘可见）

并且 S3 endpoint 是：
 `https://<ACCOUNT_ID>.r2.cloudflarestorage.com` 

------

## 三、在 GitHub 仓库里添加 Secrets（必做）

GitHub → Settings → Secrets and variables → Actions → New repository secret

新增这些：

- `R2_ACCOUNT_ID`：你的 Cloudflare Account ID
- `R2_ACCESS_KEY_ID`：R2 Access Key ID，即访问密钥 ID
- `R2_SECRET_ACCESS_KEY`：R2 Secret Access Key，即机密访问密钥
- `R2_BUCKET`：`firefly-download`（你创建的 bucket 名）
- `R2_PUBLIC_BASE`：`https://firefly.202132.xyz`  你自定义的域名

## 四、GitHub Actions：Release 发布后自动同步 APK 到 R2

把下面文件保存为（放到项目根目录且commit到main分支）：

```
.github/workflows/sync-apk-to-r2.yml
```

> 作用：你每次发布 GitHub Release（并上传 `FireflyVPN.apk`）后
>  自动把 APK 同步到：
>
> - `/<tag>/FireflyVPN.apk`（版本固定链接）
> - `/latest/FireflyVPN.apk`（永久最新链接）
>    同时生成并上传 sha256 校验文件。

```yml
name: Sync Release APK to Cloudflare R2

on:
  release:
    types: [published, edited]
  workflow_dispatch:
    inputs:
      tag:
        description: "Release tag (e.g. v1.10.0). Required when manually running."
        required: true
        type: string

permissions:
  contents: read

# 同一个 tag 短时间内触发多次（published/edited/重新发布等），只保留最后一次
concurrency:
  group: r2-sync-${{ github.repository }}-${{ github.event.release.tag_name || inputs.tag }}
  cancel-in-progress: true

jobs:
  sync:
    runs-on: ubuntu-latest

    env:
      R2_ACCOUNT_ID: ${{ secrets.R2_ACCOUNT_ID }}
      R2_BUCKET: ${{ secrets.R2_BUCKET }}
      R2_ENDPOINT: https://${{ secrets.R2_ACCOUNT_ID }}.r2.cloudflarestorage.com
      APK_NAME: FireflyVPN.apk

      # 你的公开下载域名（你这里应当设置为 https://firefly.202132.xyz）
      R2_PUBLIC_BASE: ${{ secrets.R2_PUBLIC_BASE }}

      AWS_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}
      AWS_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}
      AWS_DEFAULT_REGION: auto

    steps:
      - name: Checkout (optional)
        uses: actions/checkout@v4

      - name: Install awscli
        run: |
          python3 -m pip install --upgrade pip
          pip install awscli

      - name: Ensure gh exists
        run: |
          set -e
          if command -v gh >/dev/null 2>&1; then
            gh --version
          else
            sudo apt-get update
            sudo apt-get install -y gh
            gh --version
          fi

      - name: Download APK asset from GitHub Release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail

          TAG="${{ github.event.release.tag_name || inputs.tag }}"
          echo "Release tag: $TAG"

          if [ -z "$TAG" ]; then
            echo "ERROR: TAG is empty. If you ran workflow manually, please provide inputs.tag."
            exit 1
          fi

          # 找到名为 FireflyVPN.apk 的 asset 并下载
          ASSET_URL=$(gh api repos/${{ github.repository }}/releases/tags/$TAG \
            --jq '.assets[] | select(.name=="'"$APK_NAME"'") | .url')

          if [ -z "${ASSET_URL:-}" ]; then
            echo "ERROR: Asset $APK_NAME not found in release $TAG"
            echo "Available assets:"
            gh api repos/${{ github.repository }}/releases/tags/$TAG --jq '.assets[].name'
            exit 1
          fi

          # 用 GitHub API 下载 asset（二进制）
          curl -L \
            -H "Authorization: Bearer $GH_TOKEN" \
            -H "Accept: application/octet-stream" \
            "$ASSET_URL" \
            -o "$APK_NAME"

          ls -lh "$APK_NAME"

      - name: Generate SHA256
        run: |
          set -euo pipefail
          sha256sum "$APK_NAME" | tee "${APK_NAME}.sha256"

      # 真正去重：如果 R2 上 latest 的 sha256 一样，就直接跳过上传
      - name: Skip upload if same as current latest on R2
        run: |
          set -euo pipefail
          LOCAL_SHA=$(cut -d' ' -f1 "${APK_NAME}.sha256" || true)
          echo "Local sha256: $LOCAL_SHA"

          if [ -n "${R2_PUBLIC_BASE:-}" ]; then
            REMOTE_URL="${R2_PUBLIC_BASE}/latest/${APK_NAME}.sha256"
            echo "Fetch remote sha256: $REMOTE_URL"

            REMOTE_SHA=$(curl -fsSL "$REMOTE_URL" | cut -d' ' -f1 || true)
            echo "Remote sha256: ${REMOTE_SHA:-<empty>}"

            if [ -n "${REMOTE_SHA:-}" ] && [ "$REMOTE_SHA" = "$LOCAL_SHA" ]; then
              echo "Same APK already on R2 (latest). Skip upload."
              exit 0
            fi
          else
            echo "R2_PUBLIC_BASE not set; cannot compare. Continue upload."
          fi

      - name: Upload to R2 (versioned + latest)
        run: |
          set -euo pipefail
          TAG="${{ github.event.release.tag_name || inputs.tag }}"
          echo "Upload tag: $TAG"

          # 1) 版本目录：/<tag>/FireflyVPN.apk
          aws s3 cp "$APK_NAME" "s3://${R2_BUCKET}/${TAG}/${APK_NAME}" \
            --endpoint-url "${R2_ENDPOINT}"

          aws s3 cp "${APK_NAME}.sha256" "s3://${R2_BUCKET}/${TAG}/${APK_NAME}.sha256" \
            --endpoint-url "${R2_ENDPOINT}"

          # 2) 最新目录：/latest/FireflyVPN.apk
          aws s3 cp "$APK_NAME" "s3://${R2_BUCKET}/latest/${APK_NAME}" \
            --endpoint-url "${R2_ENDPOINT}"

          aws s3 cp "${APK_NAME}.sha256" "s3://${R2_BUCKET}/latest/${APK_NAME}.sha256" \
            --endpoint-url "${R2_ENDPOINT}"

      - name: Print public URLs
        run: |
          set -euo pipefail
          TAG="${{ github.event.release.tag_name || inputs.tag }}"
          if [ -n "${R2_PUBLIC_BASE:-}" ]; then
            echo "Versioned APK: ${R2_PUBLIC_BASE}/${TAG}/${APK_NAME}"
            echo "Versioned SHA: ${R2_PUBLIC_BASE}/${TAG}/${APK_NAME}.sha256"
            echo "Latest APK:    ${R2_PUBLIC_BASE}/latest/${APK_NAME}"
            echo "Latest SHA:    ${R2_PUBLIC_BASE}/latest/${APK_NAME}.sha256"
          else
            echo "R2_PUBLIC_BASE not set. Set it to your custom domain base URL, e.g. https://firefly.202132.xyz"
          fi

```

sync-apk-to-r2.yml解决了几个问题：

- ✅ **避免重复触发跑多次**：加入 `concurrency`（同一个 tag 只保留最后一次运行，前面的自动取消）
- ✅ **支持手动 Run**：加入 `workflow_dispatch` 并要求输入 `tag`
- ✅ **手动 Run 不再出现 tag 为空导致 404**：TAG 自动取 `release.tag_name` 或 `inputs.tag`
- ✅ **真正去重**：如果 R2 上的 `latest/*.sha256` 和本次一致，直接跳过上传（即使触发了也不会浪费）
- ✅ **更健壮**：检查 `gh` 是否存在、TAG 为空直接报错

这样以后：

- 你 **新发布 Release**：自动同步到 R2
- 你 **改 Release/重新发布导致多事件**：最多跑一次，而且相同文件会自动跳过上传
- 你要 **补传某个版本**：Actions 里手动 Run，填 `v1.10.0` 即可

## 五、开始访问！

访问https://firefly.202132.xyz/latest/FireflyVPN.apk即可在国内外稳定下载APP安装包！！！

## 六、其他文件处理

backupNodes、update、notice文件需要更新直接扔到R2对象存储的apis目录即可！

nodes文件由于需要爬取节点信息并更新，我已经编写好了upload_r2.sh这个shell脚本，把里面需要的信息填写完毕即可

实现获取节点信息并上传到R2对象存储apis目录下的nodes文件中！