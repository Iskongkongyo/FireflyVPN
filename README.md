# 流萤加速器 (FireflyVPN)

<p align="center">
  <img src="./images/firefly.jpg" width="100" alt="Logo">
</p>


<p align="center">
  流萤加速器一款基于 sing-box 核心，支持多种代理协议和智能分流的 Android VPN 客户端。
</p>
<p align="center">
  <a href="#功能特性">功能特性</a> •
  <a href="#技术架构">技术架构</a> •
  <a href="#环境要求">环境要求</a> •
  <a href="#快速开始">快速开始</a> •
  <a href="#配置说明">配置说明</a> •
  <a href="#api-接口">API 接口</a> •
  <a href="#自定义">自定义</a> •
  <a href="#构建发布">构建发布</a>
</p>

---

**免责声明：** 本项目为我开源作品，与米哈游 (HoYoverse) 无关。本项目不盈利、不接受捐赠。所有涉及的游戏角色名称及设计版权归米哈游所有。

**Disclaimers:** This project is my open-source creation and is related to miHoYo (HoYoverse). This project is non-profit and not for sale. All game character names and design copyrights belong to miHoYo.

------

## 界面展示

---

<div style="display:flex;gap:16px;flex-wrap:wrap;max-width:100%;"><img style="width:360px;max-width:100%;height:auto;border-radius:10px;box-shadow:0 4px 12px rgba(0,0,0,.08);" src="./images/1.jpg" alt="image1"/><img style="width:360px;max-width:100%;height:auto;border-radius:10px;box-shadow:0 4px 12px rgba(0,0,0,.08);" src="./images/2.jpg" alt="image2"/><img style="width:360px;max-width:100%;height:auto;border-radius:10px;box-shadow:0 4px 12px rgba(0,0,0,.08);" src="./images/3.jpg" alt="image2"/></div>

---

## 功能特性

### 核心功能

- 🚀 **多协议支持**：VLESS、VMess、Trojan、Hysteria2、Shadowsocks、SOCKS4/5、HTTP/HTTPS 代理
- 🧭 **智能分流**：国内流量直连，国外流量代理，自动识别主流 CN 应用/CDN
- ⚡ **自动选择**：一键测速，自动选择最优节点
- 📦 **分应用代理**：精细控制哪些应用走代理或绕过 VPN
- 🌐 **绕过局域网**：一键开关，局域网流量直连不受影响
- 🌍 **IPv6 路由**：支持 IPv6 网络访问，可选禁用/启用/优先/仅 IPv6 模式
- 🔄 **备用节点**：支持配置备用订阅源，主节点不可用时可快速切换

### 测试与诊断

- 🔧 **工具箱按钮**：主界面内嵌工具按钮，提供 TCPing 测试、URL Test 测试、清理不可用节点、网速测试等功能
- 🌐 **URL Test（默认）**：通过启动临时无头 sing-box 实例进行 HTTP 握手延迟测试，无需连接 VPN 即可测试所有节点真实连通性 (作为 App 启动和刷新时的默认测试方式)
- ⚡ **Cloudflare 网速测试**：集成 Cloudflare Speed Test，支持下载/上传测速，实时显示速率
- 📡 **TCPing 测试**：直接 TCP 连接测试节点可达性和延迟
- 🗑️ **清理不可用节点**：一键移除测试后不可用的节点
- 🛠️ **网络工具箱**：内置 10 种常用网络检测工具（出口检测、IP查询、WebRTC泄漏、DNS泄漏、速度测试等），一键跳转浏览器使用

### 界面与体验

- 🚩 **智能国旗**：自动识别节点名称中的国旗 Emoji（如 🇫🇮），优雅展示
- 🔔 **VPN 通知**：实时显示上传/下载速度、累计流量，支持断开/重置连接
- ⏳ **节流保护**：刷新、切换备用节点、检查更新等操作 5 秒内防重复触发
- 🔔 **公告系统**：支持远程推送公告通知
- ℹ️ **关于页面**：展示应用版本信息、开源协议、GitHub 仓库链接和免责声明
- 🎨 **现代 UI**：基于 Jetpack Compose，Material Design 3 风格
- 🔧 **开源可定制**：易于修改 API、品牌和配置

### 更新与安全

- 📦 **稳健更新**：
  - 应用内下载，支持断点续传
  - 自动检测下载失败，连续失败 3 次及以上会引导跳转官网下载
  - 原子化更新机制，杜绝安装包损坏
  - 智能权限引导，适配 Android 8.0+ 安装权限

---

## 技术架构

| 组件 | 技术 |
|------|------|
| **UI 框架** | Jetpack Compose + Material 3 |
| **架构模式** | MVVM (ViewModel + StateFlow) |
| **网络请求** | Retrofit2 + OkHttp3 |
| **本地存储** | Room Database + DataStore |
| **VPN 核心** | [sing-box](https://github.com/SagerNet/sing-box) (libbox.aar) |
| **并发处理** | Kotlin Coroutines |

---

## 环境要求

### 开发环境

| 工具 | 版本要求 |
|------|---------| 
| **Android Studio** | Koala | 2024.1.1 或更高 |
| **JDK** | 17 |
| **Kotlin** | 1.9.21 |
| **Gradle** | 8.7+ |
| **Android Gradle Plugin** | 8.6.1 |
| **NDK** | 25.1.8937393 |

### 运行环境

| 要求 | 说明 |
|------|------|
| **Android 版本** | Android 7.0 (API 24) 及以上 |
| **目标版本** | Android 15 (API 35) |

### 核心依赖

项目依赖 `libbox.aar`（sing-box Android 库），需放置于 `app/libs/` 目录。

> **获取 libbox.aar**：
>
> - 从 [sing-box Releases](https://github.com/SagerNet/sing-box/releases) 下载预编译版本
> - 或参考 [libbox 构建指南](https://sing-box.sagernet.org/installation/build-from-source/#build-libbox-for-android) 自行编译

---

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/Iskongkongyo/FireflyVPN.git
cd FireflyVPN
```

### 2. 配置 libbox

将 `libbox.aar` 文件放入 `app/libs/` 目录：

```
app/
└── libs/
    └── libbox.aar
```

### 3. 配置 API 地址

编辑 `app/src/main/java/xyz/a202132/app/AppConfig.kt`，修改为你的后端地址：

```kotlin
object AppConfig {
    // 节点订阅 API
    const val SUBSCRIPTION_URL = "https://your-server.com/api/nodes"
    // 版本更新 API
    const val UPDATE_URL = "https://your-server.com/api/update"
    // 公告通知 API
    const val NOTICE_URL = "https://your-server.com/api/notice"
    // 官网地址
    const val WEBSITE_URL = "https://your-server.com"
    // 反馈链接（GitHub Issues）
    const val FEEDBACK_URL = "https://github.com/your-username/your-repo/issues"
    // 项目源码地址（留空则隐藏关于页相关按钮）
    const val GITHUB_URL = "https://github.com/your-username/your-repo"

    // 延迟测试 (TCPing & URL Test)
    const val TCPING_TEST_URL = "https://www.google.com/generate_204"
    const val URL_TEST_URL = "https://www.google.com/generate_204"

    // 速度测试 (Cloudflare)
    const val SPEED_TEST_DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    const val SPEED_TEST_UPLOAD_URL = "https://speed.cloudflare.com/__up"
}
```

同时修改 `app/src/main/java/xyz/a202132/app/network/NetworkClient.kt` 中的 baseUrl：

```kotlin
private val retrofit = Retrofit.Builder()
    .baseUrl("https://your-server.com/")  // 修改为你的域名
    // ...
```

同时修改 `app/src/main/res/xml/network_security_config.xml` ：

```xml
<domain includeSubdomains="true">example.com</domain> <!-- 修改为你的域名 -->
```

### 4. 构建运行

```bash
# 使用 Gradle 构建
./gradlew assembleDebug

# 或在 Android Studio 中直接运行
```

---

## 配置说明

### 项目结构

```
app/src/main/java/xyz/a202132/app/
├── AppConfig.kt                  # 全局配置常量（API地址、网络工具箱等）
├── MainActivity.kt               # 主 Activity
├── VpnApplication.kt             # Application 类
├── data/
│   ├── local/                    # 本地数据库
│   │   ├── AppDatabase.kt       # Room 数据库定义
│   │   └── NodeDao.kt           # 节点数据访问对象
│   ├── model/                    # 数据模型
│   │   ├── ApiModels.kt         # API 响应模型
│   │   ├── Node.kt              # 节点数据模型
│   │   ├── NodeType.kt          # 代理协议类型枚举
│   │   ├── PerAppProxyMode.kt   # 分应用代理模式枚举
│   │   └── IPv6RoutingMode.kt   # IPv6 路由模式枚举
│   └── repository/               # 数据仓库
│       └── SettingsRepository.kt # 设置存储（含分应用代理、绕过局域网等）
├── network/
│   ├── ApiService.kt             # Retrofit API 接口定义
│   ├── DownloadManager.kt        # 应用内下载管理器（断点续传）
│   ├── LatencyTester.kt          # 节点延迟测试（TCPing + URL Test）
│   ├── NetworkClient.kt          # 网络客户端配置
│   ├── SpeedTestService.kt       # Cloudflare 网速测试服务
│   ├── SubscriptionParser.kt     # 订阅链接解析器
│   └── UrlTestManager.kt         # 无头 sing-box URL Test 管理器
├── service/
│   ├── BoxPlatformInterface.kt   # sing-box 平台接口（TUN 管理、分应用代理）
│   ├── BoxVpnService.kt          # VPN 服务（sing-box 核心）
│   ├── HeadlessPlatformInterface.kt  # 无头平台接口（URL Test 专用）
│   └── ServiceManager.kt         # VPN 服务管理器
├── ui/
│   ├── components/               # 可复用 UI 组件
│   │   ├── ConnectButton.kt     # 连接按钮
│   │   ├── DrawerContent.kt     # 侧边栏内容
│   │   ├── NodeListDialog.kt    # 节点列表弹窗（含工具按钮）
│   │   ├── NodeSelector.kt      # 节点选择器
│   │   └── TrafficStatsRow.kt   # 流量统计展示
│   ├── dialogs/                  # 对话框
│   │   ├── AboutDialog.kt       # 关于页面弹窗
│   │   ├── Dialogs.kt           # 通用对话框（公告、更新等）
│   │   ├── NetworkToolboxDialog.kt  # 网络工具箱弹窗
│   │   ├── SpeedTestDialog.kt       # 网速测试弹窗
│   │   └── UserAgreementDialog.kt   # 用户协议弹窗
│   ├── screens/                  # 页面
│   │   ├── MainScreen.kt        # 主界面
│   │   └── PerAppProxyScreen.kt # 分应用代理设置界面
│   └── theme/                    # 主题配置
│       ├── Color.kt             # 颜色定义
│       ├── Theme.kt             # 主题配置
│       └── Type.kt              # 字体排版
├── util/
│   ├── CryptoUtils.kt           # AES 加解密工具
│   ├── NetworkUtils.kt          # 网络状态检测工具
│   ├── RuleManager.kt           # 智能分流规则管理
│   ├── SignatureVerifier.kt     # APK 签名验证（JNI 桥接）
│   └── SingBoxConfigGenerator.kt # sing-box 配置生成器
└── viewmodel/
    ├── MainViewModel.kt          # 主界面 ViewModel
    └── PerAppProxyViewModel.kt   # 分应用代理 ViewModel
```

### 资源文件结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt            # NDK 构建配置
│   └── native-lib.cpp            # Native 层（AES 密钥 + 签名校验）
├── libs/
│   └── libbox.aar                # sing-box 核心库
├── res/
│   ├── drawable/                 # 图标和图片资源
│   ├── mipmap-*/                 # 应用图标
│   ├── values/
│   │   ├── colors.xml            # 颜色资源
│   │   ├── strings.xml           # 字符串资源
│   │   └── themes.xml            # 主题定义
│   └── xml/
│       ├── file_paths.xml        # FileProvider 路径配置
│       └── network_security_config.xml  # 网络安全配置
└── AndroidManifest.xml           # 应用清单
```

---

## 高级功能

### 工具箱按钮

节点列表弹窗内嵌工具按钮，提供以下功能：

| 功能 | 说明 |
|------|------|
| **TCPing** | 直接 TCP 连接测试节点可达性和延迟 |
| **URL Test** | 通过 ClashAPI 发起 HTTP 握手延迟测试，结果更准确 (默认/推荐) |
| **网速测试** | 使用 Cloudflare 节点进行真实宽带测速 |
| **清理不可用** | 一键删除测试后不可用的节点 |

**URL Test 工作原理**：

- **VPN 已连接时**：直接使用现有 sing-box 实例的 ClashAPI (端口 9090)
- **VPN 未连接时**：自动启动临时无头 sing-box 实例 (端口 19090)，无需 VPN 权限即可测试

> 💡 无头实例不使用 TUN，仅创建本地 HTTP 代理和 ClashAPI，通过操作系统默认路由直接连接代理节点。

---

### 网络工具箱

内置 10 种常用网络检测工具，可在 `AppConfig.kt` 的 `NETWORK_TOOLS_JSON` 中自定义配置：

| 工具 | 用途 | 网站 |
|------|------|------|
| 出口检测 | 检测 VPN 出口 IP | ippure.com |
| IP信息查询 | 查询 IP 详细信息 | ippure.com |
| WebRTC泄漏 | 检测 WebRTC 是否泄漏真实 IP | ippure.com |
| DNS泄漏 | 检测 DNS 请求是否走代理 | ippure.com |
| IP检测 | 综合 IP 检测 | ipcheck.ing |
| 高精度IP查询 | 高精度 IP 地理位置 | ping0.cc |
| IP定位 | IP 地理定位 | iplark.com |
| 伪装度查询 | 代理伪装度检测 | whoer.net |
| BGP查询 | BGP 路由信息 | bgp.tools |
| 速度测试 | 网络速度测试 | speedtest.net |

**自定义工具列表**：

修改 `AppConfig.kt` 中的 JSON 数组即可添加、删除或修改工具项：

```kotlin
const val NETWORK_TOOLS_JSON = """
[
  {"name": "工具名称", "url": "https://example.com", "icon": "speed"}
]
"""
```

支持的图标标识：`outbound`、`ip`、`webrtc`、`dns`、`check`、`precision`、`location`、`disguise`、`bgp`、`speed`

---

### 关于页面

显示应用版本信息、开源协议声明、项目链接等。

**配置位置**：  
- 版本号：`app/build.gradle.kts` → `versionName` / `versionCode`  
- GitHub 链接：`AppConfig.kt` → `GITHUB_URL`（留空则隐藏相关按钮）  
- 反馈链接：`AppConfig.kt` → `FEEDBACK_URL`

**设置位置**：侧边栏 → 关于

---

### 分应用代理

分应用代理允许用户精细控制哪些应用走代理或绕过 VPN。

**功能特点**：
- 🟢 **代理模式**：只有选中的应用走代理，其他应用直连
- 🔴 **绕过模式**：选中的应用直连，其他应用走代理
- 🔍 **搜索过滤**：支持按应用名称或包名搜索
- 📂 **系统应用筛选**：可选择显示/隐藏系统应用

**设置位置**：侧边栏 → 分应用代理

---

### 绕过局域网

开启后，局域网流量将绕过 VPN 直连，确保内网设备访问正常。

**绕过的 IP 范围**：
| IP 段 | 说明 |
|--------|------|
| `127.0.0.0/8` | 本地回环 (localhost) |
| `10.0.0.0/8` | A类私有网络 |
| `172.16.0.0/12` | B类私有网络 |
| `192.168.0.0/16` | C类私有网络 |
| `169.254.0.0/16` | 链路本地地址 |

**设置位置**：侧边栏 → 绕过局域网（默认开启）

---

### IPv6 路由

IPv6 路由功能允许用户控制 VPN 对 IPv6 网络的处理方式。

**四种模式**：

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| **禁用** (默认) | 不使用 IPv6，所有流量走 IPv4 | 网络环境不支持 IPv6 时 |
| **启用** | 同时支持 IPv4 和 IPv6，优先 IPv4 | 需要 IPv6 但追求稳定性 |
| **优先** | 同时支持 IPv4 和 IPv6，优先 IPv6 | 希望尽量使用 IPv6 网络 |
| **仅** | 仅使用 IPv6 (实验性) | 测试纯 IPv6 环境 |

> ⚠️ **注意**：
> - 「仅」模式下，不支持 IPv6 的网站/服务将无法访问
> - 节点本身需要支持 IPv6 才能正常使用 IPv6 路由功能
> - 可通过 [test-ipv6.com](https://test-ipv6.com) 测试 IPv6 连通性

**设置位置**：侧边栏 → IPv6 路由

---

### 备用节点

备用节点功能允许运营者配置备用订阅源，当主订阅不可用或被封锁时，用户可以快速切换到备用源获取节点。

**功能特点**：
- 🔄 **一键切换**：侧边栏开关，快速切换主/备用订阅源
- 🛡️ **自动回退**：备用源请求失败时，自动关闭备用模式并恢复默认订阅
- 💾 **本地缓存**：备用节点 URL 会缓存到本地，确保下次启动时可用
- 🔔 **状态提示**：切换时自动断开现有连接，并 Toast 提示当前状态

**回退机制**：

以下情况会自动触发回退（关闭备用节点 → 清除缓存 → 恢复默认）：
- 备用订阅 URL 返回空响应或 HTTP 错误
- 备用订阅 URL 格式无效（非 http/https 开头）
- 公告配置中无 `backupNodes` 对象或无 `url` 属性

> ⚠️ **注意**：切换备用节点开关时，如果 VPN 正在运行，会自动断开连接并清除当前选中的节点。

**设置位置**：侧边栏 → 备用节点（仅在公告配置中包含有效 `backupNodes.url` 时显示）

**服务端配置**：参见下方 [公告通知接口](#3-公告通知接口) 中的 `backupNodes` 字段。

---

## 安全特性

本项目内置多层安全防护机制，防止 APK 被逆向分析、篡改或抓包复制订阅。

### 1. 字符串混淆 (StringFog)

使用 [StringFog](https://github.com/niceyun/gradle-stringfog-plugin) 插件对代码中的硬编码字符串进行加密混淆。

**效果**：反编译后无法直接看到 API 地址、密钥等敏感字符串。

**配置位置**：`app/build.gradle.kts`

```kotlin
configure<com.github.megatronking.stringfog.plugin.StringFogExtension> {
    implementation = "com.github.megatronking.stringfog.xor.StringFogImpl"
    enable = true
    fogPackages = arrayOf("xyz.a202132.app") // 只加密我们自己的代码
    kg = com.github.megatronking.stringfog.plugin.kg.RandomKeyGenerator()
    mode = com.github.megatronking.stringfog.plugin.StringFogMode.base64
}
```

---

### 2. NDK 密钥存储

AES 加密密钥存储在 Native (C++) 层，通过 XOR 混淆防止静态分析。

**密钥文件**：`app/src/main/cpp/native-lib.cpp`

#### 如何修改 AES 密钥

1. 确定你的 16 位密钥（AES-128 要求恰好 16 字节），例如：`MySecretKey12345`

2. 使用以下 Python 脚本生成混淆后的字节数组：

```python
key = "MySecretKey12345"  # 必须是 16 个字符
SEED = 0x33

encrypted = []
for i, c in enumerate(key):
    encrypted.append(hex(ord(c) ^ (SEED + i)))
    
print("unsigned char encrypted_key[] = {")
for i, v in enumerate(encrypted):
    print(f"        {v}, // '{key[i]}' ^ (0x33 + {i})")
print("        0x00  // Null terminator")
print("};")
```

3. 将输出替换到 `native-lib.cpp` 中的 `encrypted_key` 数组

4. **同步修改服务端加密密钥**

---

### 3. AES 流量加密

节点订阅数据在服务端加密后传输，APP 端使用 Java Crypto API 解密。

#### 服务端加密要求

| 参数 | 值 |
| :--- | :--- |
| **算法** | AES-128-GCM |
| **IV 长度** | 12 字节 (随机生成) |
| **认证标签** | 128 位 (16 字节) |
| **密钥** | 与 `native-lib.cpp` 中配置的相同（16 字节） |
| **输出格式** | Base64(IV + 密文 + AuthTag) |

#### 服务端加密流程

```
明文节点链接 → AES-GCM加密(生成随机IV) → 拼接(IV + 密文 + AuthTag) → Base64编码 → 返回给APP
```

#### AES-GCM vs AES-ECB

| 特性 | AES-ECB | AES-GCM ✅ |
|------|---------|------------|
| 需要 IV | ❌ | ✅ (12字节) |
| 认证标签 | ❌ | ✅ (防篡改) |
| 相同明文→相同密文 | ✅ (有风险) | ❌ (每次不同) |
| 安全性 | 基础 | 更高 |

#### Node.js 加密示例

```javascript
const crypto = require('crypto');

const SECRET_KEY = 'MySecretKey12345';  // 16 字节密钥，与 APP 一致

function encrypt(plaintext) {
    // 生成随机 12 字节 IV
    const iv = crypto.randomBytes(12);
    
    // 创建 cipher
    const cipher = crypto.createCipheriv('aes-128-gcm', Buffer.from(SECRET_KEY), iv);
    
    // 加密
    let encrypted = cipher.update(plaintext, 'utf8');
    encrypted = Buffer.concat([encrypted, cipher.final()]);
    
    // 获取 AuthTag (16 字节)
    const authTag = cipher.getAuthTag();
    
    // 拼接: IV (12) + 密文 + AuthTag (16)
    const combined = Buffer.concat([iv, encrypted, authTag]);
    
    // 返回 Base64
    return combined.toString('base64');
}

// 使用示例
const nodes = `hysteria2://uuid@server:port?sni=example.com#节点名称
vless://uuid@server:port?security=tls#另一个节点`;

const encrypted = encrypt(nodes);
// 将 encrypted 作为 API 响应返回
```

---

### 4. 签名校验 (Signature Verification)

APP 启动时在 Native 层校验 APK 签名。如果签名不匹配（说明被重新签名/篡改），APP 会立即 Crash。

**签名文件**：`app/src/main/cpp/native-lib.cpp`

#### 如何设置签名 SHA-256

1. 获取你的 Release 签名证书 SHA-256 值：

```powershell
# 使用 Android Studio 自带的 JDK
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore your-release-key.jks -alias your-alias
```

2. 复制输出中的 `SHA256:` 值（格式如 `95:CA:C5:8A:...`）

3. 去掉冒号，转成大写，替换 `native-lib.cpp` 中的 `EXPECTED_SIGNATURE`：

```cpp
static const char* EXPECTED_SIGNATURE = "95CAC58A...";
```

4. 重新 Clean → Rebuild 项目

> ⚠️ **注意**：每次更换签名证书后，都需要更新此值，否则 APP 将无法启动。

---

### 5. Release 日志屏蔽

Release 版本默认移除所有 `android.util.Log` 调用（包括 `Log.d`、`Log.i`、`Log.w`、`Log.e`），通过 R8/ProGuard 在编译时优化。

**优点**：
- 减少 APK 体积
- 防止日志泄露敏感信息
- 提升运行性能

**配置位置**：`app/proguard-rules.pro`

```proguard
# 移除所有日志调用
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
    public static int w(...);
    public static int e(...);
}
```

#### 如何保留部分日志

如果需要在 Release 版本中保留部分日志（如用于崩溃分析），可以注释掉对应级别：

```proguard
# 保留 Warning 和 Error 级别日志
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
    # public static int w(...);  # 保留
    # public static int e(...);  # 保留
}
```

修改后需重新 Build Release 版本。

---

### 6. 网络安全配置

`network_security_config.xml` 控制应用的 HTTP 明文流量策略：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">your-domain.com</domain>
        <domain>127.0.0.1</domain>  <!-- URL Test ClashAPI 本地通信 -->
    </domain-config>
</network-security-config>
```

> ⚠️ `127.0.0.1` 的明文放行是 URL Test 功能必需的（ClashAPI 使用 HTTP 协议通信），请勿移除。

---

## API 接口（简单部署方式可以参考apis目录里面说明）

### 1. 节点订阅接口

**端点**: `GET /api/nodes`

**响应格式**: Base64 编码的节点链接列表（每行一个）

**支持的协议**:
- `vless://` - VLESS
- `vmess://` - VMess (Base64 JSON)
- `trojan://` - Trojan
- `hysteria2://` 或 `hy2://` - Hysteria2
- `ss://` - Shadowsocks
- `socks://` 或 `socks5://` - SOCKS5
- `socks4://` - SOCKS4
- `http://` - HTTP 代理
- `https://` - HTTPS 代理 (自动启用 TLS)

**示例响应** (Base64 解码后):
```
vless://uuid@server:443?security=reality&type=tcp&sni=example.com#节点名称
vmess://eyJ2IjoiMiIsInBzIjoi5ZCN56ewIiwiYWRkIjoic2VydmVyLmNvbSIsInBvcnQiOiI0NDMifQ==
trojan://password@server:443?sni=example.com#Trojan节点
ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@server:8388#SS节点
```

**配置位置**: `AppConfig.kt` → `SUBSCRIPTION_URL`

---

### 2. 版本更新接口

**端点**: `GET /api/update`

**响应格式**: JSON

```json
{
    "version": "1.1.0",
    "versionCode": 2,
    "is_force":0,
    "downloadUrl": "https://your-server.com/download/app-v1.1.0.apk",
    "changelog": "1. 新增智能分流功能\n2. 修复已知问题"
}
```

**字段说明**:
| 字段 | 类型 | 说明 |
|------|------|------|
| `version` | String | 版本号（显示用） |
| `versionCode` | Int | 版本代码（用于比较） |
| `is_force` | Int | 是否强制更新（1为强制更新） |
| `downloadUrl` | String | APK 下载地址 |
| `changelog` | String | 更新日志 |

**配置位置**: `AppConfig.kt` → `UPDATE_URL`

---

### 3. 公告通知接口

**端点**: `GET /api/notice`

**响应格式**: JSON

```json
{
    "hasNotice": true,
    "title": "系统公告",
    "content": "服务器将于今晚 22:00 进行维护，届时可能无法连接。",
    "noticeId": "notice_20240117",
    "showOnce": true,
    "backupNodes": {
        "msg": "主节点不可用时，请开启备用节点",
        "url": "https://your-server.com/api/backup-nodes"
    }
}
```

**字段说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `hasNotice` | Boolean | ✅ | 是否有公告 |
| `title` | String | ❌ | 公告标题 |
| `content` | String | ❌ | 公告内容 |
| `noticeId` | String | ❌ | 公告唯一ID（用于去重） |
| `showOnce` | Boolean | ❌ | 是否只显示一次（默认 `true`） |
| `backupNodes` | Object | ❌ | 备用节点配置（可选） |
| `backupNodes.msg` | String | ❌ | 备用节点提示信息 |
| `backupNodes.url` | String | ❌ | 备用订阅 URL（必须以 `http://` 或 `https://` 开头） |

> 💡 **备用节点说明**：
> - 当 `backupNodes.url` 存在且格式有效时，侧边栏会显示「备用节点」开关
> - 备用订阅的响应格式与主订阅相同（Base64 编码的节点链接列表）
> - 如果不需要备用节点功能，可省略整个 `backupNodes` 字段

**配置位置**: `AppConfig.kt` → `NOTICE_URL`

---

## 自定义

### 修改应用主题颜色

**文件**: `app/src/main/java/xyz/a202132/app/ui/theme/Color.kt`

项目使用青绿色系主题，修改 `Color.kt` 中的颜色变量即可全局更改主题色：

```kotlin
// Primary Colors - Cyan/Teal Theme (matching firefly images)
val Primary = Color(0xFF00BFA5)          // 青绿主色
val PrimaryVariant = Color(0xFF00897B)   // 深青绿
val Secondary = Color(0xFF26C6DA)        // 亮青色
// ...
```

---

### 修改应用名称

**文件**: `app/src/main/res/values/strings.xml`

```xml
<string name="app_name">你的应用名称</string>
<string name="vpn_notification_title">你的应用名称运行中</string>
```

**文件**: `app/ui/screens/MainScreen.kt`

```
text = "流萤加速器"; 替换成你的应用名称
```

---

### 修改 VPN 连接按钮

当前连接按钮使用三张自定义图片表示不同状态：

| 图片文件 | 状态 | 说明 |
|---------|------|------|
| `btn_disconnected.png` | 未连接 | 默认待机状态 |
| `btn_connecting.png` | 连接中/断开中 | 带脉冲动画 |
| `btn_connected.png` | 已连接 | VPN 已开启 |

**图片位置**: `app/src/main/res/drawable/`

#### 替换按钮图片

将你的三张图片重命名为上述文件名，替换到 `drawable` 目录即可。

> 💡 推荐使用 **透明背景的 PNG 图片**，尺寸建议 512×512 像素以上以保证清晰度。

#### 恢复经典圆形按钮

如果不想使用自定义图片，可以将 `ConnectButton.kt` 替换为经典的 Material Design 圆形电源按钮：

```kotlin
package xyz.a202132.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.theme.*

@Composable
fun ConnectButton(
    vpnState: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customLabel: String? = null
) {
    val isConnecting = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING
    val buttonColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> ConnectedGreen
            VpnState.CONNECTING, VpnState.DISCONNECTING -> ConnectingYellow
            VpnState.DISCONNECTED -> Primary
        },
        animationSpec = tween(300), label = "buttonColor"
    )
    val glowColor by animateColorAsState(
        targetValue = when (vpnState) {
            VpnState.CONNECTED -> ConnectedGreenGlow.copy(alpha = 0.3f)
            VpnState.CONNECTING, VpnState.DISCONNECTING -> ConnectingYellow.copy(alpha = 0.3f)
            VpnState.DISCONNECTED -> Primary.copy(alpha = 0.2f)
        },
        animationSpec = tween(300), label = "glowColor"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )
    val scale = if (isConnecting) pulseScale else 1f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size((140 * scale).dp)
                .shadow(20.dp, CircleShape, ambientColor = glowColor, spotColor = glowColor)
                .background(Brush.radialGradient(listOf(glowColor, Color.Transparent)), CircleShape)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(buttonColor, buttonColor.copy(alpha = 0.8f))))
                    .clickable(enabled = !isConnecting) { onClick() }
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Connect",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = customLabel ?: when (vpnState) {
                VpnState.CONNECTED -> "已连接"
                VpnState.CONNECTING -> "连接中..."
                VpnState.DISCONNECTING -> "断开中..."
                VpnState.DISCONNECTED -> "点击连接"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}
```

替换后可删除 `drawable` 目录中的 `btn_disconnected.png`、`btn_connecting.png`、`btn_connected.png`。

---

### 修改应用图标

**图标文件位置**:
```
app/src/main/res/
├── drawable/
│   ├── ic_launcher_background.xml  # 图标背景
│   └── ic_launcher_foreground.xml  # 图标前景
├── mipmap-anydpi-v26/
│   ├── ic_launcher.xml             # 自适应图标配置
│   └── ic_launcher_round.xml       # 圆形图标配置
└── mipmap-*/                        # 各分辨率位图（可选）
```

**推荐方式**: 使用 Android Studio 的 **Image Asset Studio**
1. 右键 `res` → New → Image Asset
2. 选择 Launcher Icons
3. 配置前景/背景图像
4. 自动生成所有尺寸

---

### 修改应用包名

需要修改以下位置：

1. **`app/build.gradle.kts`**:
```kotlin
android {
    namespace = "com.your.package"
    defaultConfig {
        applicationId = "com.your.package"
    }
}
```

2. **`AndroidManifest.xml`**: 确保 package 声明正确

3. **源代码目录**: 重构 `app/src/main/java/xyz/a202132/app/` 为新包名路径

4. **所有 Kotlin 文件**: 更新 `package` 声明

---

### 修改应用版本号

**文件**:**`app/build.gradle.kts`**:

```kotlin
versionCode = 11
versionName = "1.10.0"
```

版本号规范建议：
versionCode — 整数，每次发布必须递增，用于 Google Play 和系统判断新旧版本
versionName — 字符串，格式通常为 主版本.次版本.修订号（如 1.1.0）

---

### 修改主题颜色

**文件**: `app/src/main/res/values/themes.xml`

```xml
<style name="Theme.FireflyVPN" parent="android:Theme.Material.Light.NoActionBar">
    <item name="android:colorPrimary">@color/your_primary</item>
    <item name="android:colorAccent">@color/your_accent</item>
</style>
```

或在 Compose 主题文件中修改 Material 3 颜色方案。

------

### 自定义网络工具箱

**文件**: `app/src/main/java/xyz/a202132/app/AppConfig.kt`

修改 `NETWORK_TOOLS_JSON` 数组，按照以下格式添加或修改工具项：

```json
{"name": "工具名称", "url": "https://example.com", "icon": "icon_key"}
```

如需添加新的图标类型，需同时修改 `NetworkToolboxDialog.kt` 中的 `getToolIcon()` 函数。

---

## 构建发布

### Debug 构建

Debug 模式已配置使用 Release 签名（防止 Native 签名验证失败）：

```bash
./gradlew assembleDebug
```

输出: `app/build/outputs/apk/debug/app-debug.apk`

> ⚠️ **注意**: 由于 Native 层有签名校验，Debug 和 Release 构建均需使用相同的签名密钥。`build.gradle.kts` 中已配置 `debug { signingConfig = signingConfigs.getByName("release") }`。

### Release 构建

> ⚠️ **注意**: `keystore.properties` 和 `*.jks` 签名文件已被 `.gitignore` 忽略，构建 Release 版本需要你配置自己的签名。

1. **准备签名文件**:
   生成一个新的 `.jks` 签名文件（或使用现有的），放在项目根目录。

2. **创建配置文件**:
   在项目根目录创建 `keystore.properties` 文件：

```properties
keyAlias=你的KeyAlias
keyPassword=你的KeyPassword
storeFile=你的签名文件.jks
storePassword=你的StorePassword
```

3. **执行构建**:
```bash
./gradlew assembleRelease
# Windows PowerShell:
.\gradlew assembleRelease
```

**备选方法 (IDE 界面操作)**:
1. 菜单栏点击 **Build** -> **Generate Signed Bundle / APK**
2. 选择 **APK** -> **Next**
3. 选择密钥库并输入密码
4. 选择 **release** -> **Create**

输出: `app/build/outputs/apk/release/app-release.apk`

### 16 KB 页面对齐

自 2025 年 11 月起，Google Play 要求所有面向 Android 15+ 的应用支持 16 KB 页面大小。项目已在 `CMakeLists.txt` 中配置对齐：

```cmake
target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=16384")
```

> 💡 如果使用第三方 `.so` 库（如 `libbox.so`），也需确保其支持 16 KB 对齐，否则需更新上游库版本。

---

## 常见问题

### Q: URL Test 全部显示超时
**A**: 
1. 确认 `network_security_config.xml` 中包含 `<domain>127.0.0.1</domain>` 的明文放行配置
2. URL Test 需要网络连接，确保 WiFi 或移动数据正常
3. 检查 Logcat 中 `SingBoxCoreLog` 标签的日志，排查 sing-box 核心错误

### Q: 如何添加新的代理协议？
**A**:

1. 在 `NodeType.kt` 添加新枚举值
2. 在 `SubscriptionParser.kt` 添加解析逻辑
3. 在 `SingBoxConfigGenerator.kt` 添加 outbound 生成逻辑

### Q: 如何添加新的网络工具？
**A**: 编辑 `AppConfig.kt` 中的 `NETWORK_TOOLS_JSON`，按格式添加新条目。如需自定义图标，同时修改 `NetworkToolboxDialog.kt` 中的 `getToolIcon()` 函数。

---

## 开源协议

本项目基于 [GNU General Public License v3.0](LICENSE) 开源，与核心依赖 sing-box 的协议保持一致。

**依赖项目**:
- [sing-box](https://github.com/SagerNet/sing-box) - GPLv3

---

## 致谢

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box) - VPN 核心
- [JetBrains/Kotlin](https://github.com/JetBrains/kotlin) - Kotlin 语言
- [Google/Jetpack Compose](https://developer.android.com/jetpack/compose) - UI 框架
