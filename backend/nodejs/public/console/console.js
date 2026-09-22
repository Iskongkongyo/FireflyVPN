(() => {
  "use strict";
  const base = location.pathname.replace(/\/$/, "");
  const storageKey = "firefly-admin-jwt";
  const legacyStorageKey = "firefly-admin-token";
  const themeKey = "firefly-console-theme";
  const sidebarKey = "firefly-sidebar-collapsed";
  const titles = { dashboard: "概览", subscriptions: "订阅源", accounts: "用户账号", devices: "设备管理", analytics: "统计分析", settings: "其他设置", audit: "审计日志" };
  const content = document.querySelector("#content");
  const title = document.querySelector("#page-title");
  const status = document.querySelector("#status");
  const dialog = document.querySelector("#login-dialog");
  const auditCleanupDialog = document.querySelector("#audit-cleanup-dialog");
  const auditCleanupForm = document.querySelector("#audit-cleanup-form");
  const tokenInput = document.querySelector("#admin-token");
  const toast = document.querySelector("#toast");
  const themeToggle = document.querySelector("#theme-toggle");
  const themeIcon = document.querySelector("#theme-icon");
  const themeLabel = document.querySelector("#theme-label");
  const shell = document.querySelector("#app-shell");
  const sidebar = document.querySelector("#sidebar");
  const sidebarToggle = document.querySelector("#sidebar-toggle");
  const sidebarBackdrop = document.querySelector("#sidebar-backdrop");
  const sidebarMedia = window.matchMedia("(max-width: 760px)");
  let page = "dashboard";
  let toastTimer;
  const listState = {
    accounts: { page: 1, query: "" },
    devices: { page: 1, query: "" },
    audit: { page: 1, from: "", to: "", action: "", requestId: "", targetId: "" },
  };

  const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
  const bytes = value => { const n = Number(value || 0); if (n < 1024) return `${n} B`; const units = ["KiB", "MiB", "GiB", "TiB"]; let size = n / 1024, i = 0; while (size >= 1024 && i < units.length - 1) { size /= 1024; i++; } return `${size.toFixed(size < 10 ? 2 : 1)} ${units[i]}`; };
  const setStatus = (text, kind = "") => { status.textContent = text; status.className = `status ${kind}`; };
  const statusLabel = value => ({ active: "正常", banned: "已封禁", revoked: "已撤销", deleted: "已注销" })[value] || value || "未知";
  const platformLabel = value => ({ android: "安卓", ios: "iOS", windows: "Windows", macos: "macOS", linux: "Linux" })[String(value).toLowerCase()] || value || "未知";
  const sourceTypeLabel = value => ({ managed: "托管", external: "外源 + 托管" })[value] || value || "未知";
  const externalHealthBadge = row => {
    if (row.sourceType !== "external") return "-";
    if (!row.externalHealthEnabled) return '<span class="badge">未启用</span>';
    const state = row.externalHealthStatus === "healthy"
      ? { label: "正常", className: "active" }
      : row.externalHealthStatus === "unhealthy"
        ? { label: row.externalFallbackActive ? "异常 · 已回退" : "异常", className: "banned" }
        : { label: "待检测", className: "" };
    const details = [
      row.externalLastCheckedAt ? `最近检测：${dateTime(row.externalLastCheckedAt)}` : "尚未检测",
      row.externalLastSuccessAt ? `最近成功：${dateTime(row.externalLastSuccessAt)}` : "尚无成功内容",
      row.externalLastError ? `结果：${errorLabels[row.externalLastError] || row.externalLastError}` : "",
    ].filter(Boolean).join("；");
    return `<span class="badge ${state.className}" title="${escapeHtml(details)}">${state.label}</span>`;
  };
  const formatLabel = value => ({ "uri-list": "URI 列表", base64: "Base64 文本", "clash-yaml": "Clash 配置", json: "JSON", text: "纯文本" })[value] || value;
  const auditActions = {
    "account.ban": "封禁用户账号",
    "account.unban": "解封用户账号",
    "device.ban": "封禁设备",
    "device.unban": "解封设备",
    "device.revoke": "撤销设备",
    "subscription.create": "新增订阅源",
    "subscription.update": "更新订阅源",
    "subscription.delete": "删除订阅源",
    "subscription.refresh": "刷新订阅源",
    "settings.update": "更新其他设置",
    "admin.access.update": "更新登录相关设置",
    "admin.login": "控制台登录",
    "admin.logout": "退出控制台",
    "audit.cleanup": "清理审计日志",
    "account.self_delete": "用户注销账号",
    "device.self_revoke": "用户撤销设备",
    "device.rotate_key": "更新设备密钥",
    "demo.seed": "导入演示数据",
  };
  const auditActionLabel = value => auditActions[value] || "其他操作";
  const auditTargetTypeLabel = value => ({ account: "用户账号", device: "设备", subscription: "订阅源", "runtime-config": "其他设置", "admin-access": "登录相关设置", "admin-session": "登录会话", "audit-log": "审计日志", "demo-data": "演示数据" })[value] || "其他目标";
  const auditDetailLabels = {
    accessPath: "访问路径", tokenChanged: "登录密码已修改", jwtSecretChanged: "会话密钥已修改",
    all: "全部清理", from: "开始时间", to: "结束时间", deleted: "清理条数",
    status: "状态", sourceType: "订阅模式", enabled: "已启用",
    noticeEnabled: "公告已启用", appUpdateEnabled: "移动端更新已启用", pcAppUpdateEnabled: "电脑端更新已启用",
    externalHealthEnabled: "外源健康监测与故障回退",
    adminLoginRateLimitEnabled: "登录限流已启用", adminLoginRateLimitPerMinute: "每分钟登录次数",
    bytes: "内容大小", lines: "内容行数", format: "内容格式",
    revokedDevices: "撤销设备数", accountId: "所属账号", cryptoVersion: "加密协议版本",
    accounts: "演示账号数", devices: "演示设备数", days: "演示天数", safeToDelete: "可安全删除",
  };
  const auditDetailValue = (key, value) => {
    if (value === null || value === undefined || value === "") return key === "from" || key === "to" ? "不限" : "未设置";
    if (typeof value === "boolean") return value ? "是" : "否";
    if (key === "from" || key === "to") return dateTime(value);
    if (key === "status") return statusLabel(value);
    if (key === "sourceType") return sourceTypeLabel(value);
    if (key === "format") return formatLabel(value);
    if (key === "bytes") return bytes(value);
    if (key === "cryptoVersion") return `第 ${value} 版`;
    if (key === "days") return `${value} 天`;
    if (key === "lines") return `${value} 行`;
    if (key === "deleted") return `${value} 条`;
    if (key === "accounts") return `${value} 个`;
    if (key === "devices" || key === "revokedDevices") return `${value} 台`;
    if (Array.isArray(value)) return value.map(item => auditDetailValue("", item)).join("、") || "无";
    if (typeof value === "object") return Object.entries(value).map(([name, item]) => `${auditDetailLabels[name] || "补充信息"}：${auditDetailValue(name, item)}`).join("；") || "无";
    return String(value);
  };
  const auditDetailEntries = detail => {
    if (typeof detail === "string") {
      try { detail = JSON.parse(detail); } catch { /* Keep older plain-text details readable. */ }
    }
    if (detail === null || detail === undefined || detail === "") return [];
    if (typeof detail !== "object" || Array.isArray(detail)) return [["说明", auditDetailValue("", detail)]];
    return Object.entries(detail).map(([key, value], index) => [auditDetailLabels[key] || `补充信息 ${index + 1}`, auditDetailValue(key, value)]);
  };
  const auditDetailText = detail => auditDetailEntries(detail).map(([label, value]) => `${label}：${value}`).join(" · ") || "无详细信息";
  const auditDetailHtml = detail => {
    const entries = auditDetailEntries(detail);
    if (!entries.length) return '<span class="audit-detail-empty">无详细信息</span>';
    return `<div class="audit-detail">${entries.map(([label, value], index) => `${index ? '<span class="audit-detail-separator" aria-hidden="true">·</span>' : ""}<span class="audit-detail-item"><span class="audit-detail-label">${escapeHtml(label)}：</span>${escapeHtml(value)}</span>`).join("")}</div>`;
  };
  const dateTime = value => {
    if (!value) return "-";
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return String(value);
    const parts = Object.fromEntries(new Intl.DateTimeFormat("zh-CN", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    }).formatToParts(parsed).map(part => [part.type, part.value]));
    return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
  };
  const errorLabels = {
    invalid_request: "提交内容不符合要求，请检查必填项、格式和长度",
    unauthorized: "登录密码无效或登录已失效",
    subscription_not_found: "订阅源不存在或已被删除",
    subscription_unavailable: "订阅内容为空、格式异常或上游返回了网页",
    unsupported_subscription_format: "合并只支持节点 URI 列表或 Base64 节点订阅；请检查外源与托管正文",
    payload_too_large: "提交内容超过大小限制",
    upstream_failed: "外部订阅地址访问失败，请检查地址和上游状态",
    not_found: "目标不存在或访问路径已变更",
    internal_error: "服务器处理失败，请稍后重试",
    feature_disabled: "此功能当前未启用",
    rate_limited: "操作过于频繁，请稍后重试",
  };

  function notify(message, kind = "ok", duration = 4200) {
    setStatus(message, kind);
    toast.textContent = message;
    toast.className = `toast show ${kind}`;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toast.className = "toast"; }, duration);
  }

  async function runAction(button, messages, action) {
    const originalText = button?.textContent;
    if (button) {
      button.disabled = true;
      button.textContent = messages.working;
    }
    notify(messages.working, "busy", 60_000);
    try {
      const result = await action();
      const success = typeof messages.success === "function" ? messages.success(result) : messages.success;
      notify(success, "ok");
      return result;
    } catch (error) {
      notify(`${messages.failure}：${error?.message || "未知错误"}`, "bad", 7000);
      return null;
    } finally {
      if (button?.isConnected) {
        button.disabled = false;
        button.textContent = originalText;
      }
    }
  }

  function pager(data, noun) {
    const pages = [...new Set([1, data.page - 1, data.page, data.page + 1, data.totalPages])]
      .filter(value => value >= 1 && value <= data.totalPages)
      .sort((left, right) => left - right);
    let previous = 0;
    const pageButtons = pages.map(value => {
      const gap = value - previous > 1 ? `<span class="page-gap">…</span>` : "";
      previous = value;
      return `${gap}<button class="secondary ${value === data.page ? "page-current" : ""}" type="button" data-list-page="${value}" ${value === data.page ? "disabled" : ""}>${value}</button>`;
    }).join("");
    return `<div class="pagination"><span>共 ${escapeHtml(data.total)} ${escapeHtml(noun)} · 第 ${escapeHtml(data.page)} / ${escapeHtml(data.totalPages)} 页</span><div><button class="secondary" type="button" data-list-page="${data.page - 1}" ${data.page <= 1 ? "disabled" : ""}>上一页</button>${pageButtons}<button class="secondary" type="button" data-list-page="${data.page + 1}" ${data.page >= data.totalPages ? "disabled" : ""}>下一页</button></div></div>`;
  }

  function bindListControls(kind, loader) {
    const form = document.querySelector(`#${kind}-search-form`);
    const loadList = requestedPage => loader(requestedPage).catch(error => {
      notify(`加载列表失败：${error?.message || "未知错误"}`, "bad", 7000);
    });
    form.onsubmit = event => {
      event.preventDefault();
      listState[kind].query = String(new FormData(form).get("query") || "").trim();
      loadList(1);
    };
    const clear = document.querySelector(`#${kind}-search-clear`);
    if (clear) clear.onclick = () => {
      listState[kind].query = "";
      loadList(1);
    };
    content.querySelectorAll("[data-list-page]").forEach(button => {
      button.onclick = () => loadList(Number(button.dataset.listPage));
    });
  }

  function applyTheme(theme) {
    const selected = theme === "dark" ? "dark" : "light";
    document.documentElement.dataset.theme = selected;
    localStorage.setItem(themeKey, selected);
    themeIcon.textContent = selected === "dark" ? "☀" : "☾";
    themeLabel.textContent = selected === "dark" ? "明亮" : "深色";
    themeToggle.setAttribute("aria-pressed", String(selected === "dark"));
    themeToggle.setAttribute("title", `切换到${selected === "dark" ? "明亮" : "深色"}主题`);
  }

  function syncSidebarState() {
    const mobile = sidebarMedia.matches;
    const expanded = mobile
      ? shell.classList.contains("sidebar-open")
      : !shell.classList.contains("sidebar-collapsed");
    sidebarToggle.setAttribute("aria-expanded", String(expanded));
    sidebarToggle.setAttribute("aria-label", expanded ? "隐藏侧边栏" : "显示侧边栏");
    sidebar.setAttribute("aria-hidden", String(!expanded));
    document.body.classList.toggle("sidebar-drawer-open", mobile && expanded);
  }

  function closeMobileSidebar() {
    if (!sidebarMedia.matches) return;
    shell.classList.remove("sidebar-open");
    syncSidebarState();
  }

  function toggleSidebar() {
    if (sidebarMedia.matches) {
      shell.classList.toggle("sidebar-open");
    } else {
      const collapsed = shell.classList.toggle("sidebar-collapsed");
      localStorage.setItem(sidebarKey, collapsed ? "true" : "false");
    }
    syncSidebarState();
  }

  function niceMaximum(value, ticks = 4) {
    if (!Number.isFinite(value) || value <= 0) return ticks;
    const rough = value / ticks;
    const magnitude = 10 ** Math.floor(Math.log10(rough));
    const normalized = rough / magnitude;
    const nice = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
    return nice * magnitude * ticks;
  }

  function metricTrendChart({ title, current, rows, key, periodKey, format = value => String(Math.round(value)) }) {
    const width = 360;
    const height = 195;
    const margin = { top: 12, right: 12, bottom: 36, left: 56 };
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;
    const values = rows.map(row => Number(row[key] || 0));
    const max = niceMaximum(Math.max(0, ...values));
    const ticks = Array.from({ length: 5 }, (_, index) => max * index / 4);
    const x = index => margin.left + (rows.length <= 1 ? plotWidth / 2 : index / (rows.length - 1) * plotWidth);
    const y = value => margin.top + plotHeight - Number(value || 0) / max * plotHeight;
    const points = rows.map((row, index) => `${x(index)},${y(row[key])}`).join(" ");
    const labelEvery = Math.max(1, Math.ceil(rows.length / 4));
    return `<section class="panel metric-panel"><div class="metric-head"><div><span>${escapeHtml(title)}</span><strong>${escapeHtml(current)}</strong></div><small>${escapeHtml(rows.length)} 个周期</small></div><div class="metric-chart-wrap"><svg class="chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeHtml(title)}趋势图">
      ${ticks.map(tick => `<line class="grid-line" x1="${margin.left}" x2="${width - margin.right}" y1="${y(tick)}" y2="${y(tick)}"></line><text x="${margin.left - 8}" y="${y(tick) + 4}" text-anchor="end">${escapeHtml(format(tick))}</text>`).join("")}
      <line class="axis-line" x1="${margin.left}" x2="${margin.left}" y1="${margin.top}" y2="${margin.top + plotHeight}"></line><line class="axis-line" x1="${margin.left}" x2="${width - margin.right}" y1="${margin.top + plotHeight}" y2="${margin.top + plotHeight}"></line>
      <polyline class="metric-line" points="${points}"></polyline>
      ${rows.map((row, index) => `<g><circle class="metric-point" cx="${x(index)}" cy="${y(row[key])}" r="3.4"><title>${escapeHtml(row[periodKey])} ${escapeHtml(title)}：${escapeHtml(format(row[key]))}</title></circle>${index % labelEvery === 0 || index === rows.length - 1 ? `<text x="${x(index)}" y="${height - 15}" text-anchor="middle">${escapeHtml(String(row[periodKey]).slice(5))}</text>` : ""}</g>`).join("")}
    </svg></div></section>`;
  }

  function trafficChart(daily, today, compact = false) {
    const rows = Array.isArray(daily) ? daily : [];
    const width = 900;
    const height = compact ? 290 : 350;
    const margin = { top: 24, right: 22, bottom: compact ? 45 : 55, left: 76 };
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;
    const totals = rows.flatMap(row => [Number(row.uploadBytes || 0), Number(row.downloadBytes || 0), Number(row.uploadBytes || 0) + Number(row.downloadBytes || 0)]);
    const max = niceMaximum(Math.max(0, ...totals));
    const ticks = Array.from({ length: 5 }, (_, index) => max * index / 4);
    const x = index => margin.left + (rows.length <= 1 ? plotWidth / 2 : index / (rows.length - 1) * plotWidth);
    const y = value => margin.top + plotHeight - Number(value || 0) / max * plotHeight;
    const points = key => rows.map((row, index) => `${x(index)},${y(row[key])}`).join(" ");
    const labelEvery = Math.max(1, Math.ceil(rows.length / 6));
    const details = rows.map(row => `<tr><td>${escapeHtml(row.day)}</td><td>${escapeHtml(bytes(row.uploadBytes))}</td><td>${escapeHtml(bytes(row.downloadBytes))}</td><td>${escapeHtml(bytes(Number(row.uploadBytes || 0) + Number(row.downloadBytes || 0)))}</td></tr>`).join("");
    return `<div class="legend"><span><i class="upload"></i>上传</span><span><i></i>下载</span><span>悬停数据点可看精确值</span></div><div class="chart-wrap"><svg class="chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="最近三十日上传和下载流量折线图">
      ${ticks.map(tick => `<line class="grid-line" x1="${margin.left}" x2="${width - margin.right}" y1="${y(tick)}" y2="${y(tick)}"></line><text x="${margin.left - 10}" y="${y(tick) + 4}" text-anchor="end">${escapeHtml(bytes(tick))}</text>`).join("")}
      <line class="axis-line" x1="${margin.left}" x2="${margin.left}" y1="${margin.top}" y2="${margin.top + plotHeight}"></line>
      <line class="axis-line" x1="${margin.left}" x2="${width - margin.right}" y1="${margin.top + plotHeight}" y2="${margin.top + plotHeight}"></line>
      ${rows.length ? `<polyline class="line-upload" points="${points("uploadBytes")}"></polyline><polyline class="line-download" points="${points("downloadBytes")}"></polyline>` : ""}
      ${rows.map((row, index) => `<g><circle class="point-upload" cx="${x(index)}" cy="${y(row.uploadBytes)}" r="3.5"><title>${escapeHtml(row.day)} 上传：${escapeHtml(bytes(row.uploadBytes))}</title></circle><circle class="point-download" cx="${x(index)}" cy="${y(row.downloadBytes)}" r="3.5"><title>${escapeHtml(row.day)} 下载：${escapeHtml(bytes(row.downloadBytes))}</title></circle>${index % labelEvery === 0 || index === rows.length - 1 ? `<text x="${x(index)}" y="${height - 28}" text-anchor="middle">${escapeHtml(String(row.day).slice(5))}</text>` : ""}</g>`).join("")}
    </svg></div><div class="today-summary"><span>今日上传 <strong>${escapeHtml(bytes(today.uploadBytes))}</strong></span><span>今日下载 <strong>${escapeHtml(bytes(today.downloadBytes))}</strong></span><span>今日合计 <strong>${escapeHtml(bytes(today.totalBytes))}</strong></span></div><details class="chart-details"><summary>查看 30 日精确数据</summary><table><thead><tr><th>日期</th><th>上传</th><th>下载</th><th>合计</th></tr></thead><tbody>${details}</tbody></table></details>`;
  }

  function accountTrendChart(rows) {
    const width = 900;
    const height = 320;
    const margin = { top: 24, right: 22, bottom: 55, left: 58 };
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;
    const max = niceMaximum(Math.max(0, ...rows.flatMap(row => [Number(row.activeAccounts || 0), Number(row.newAccounts || 0)])));
    const ticks = Array.from({ length: 5 }, (_, index) => max * index / 4);
    const x = index => margin.left + (rows.length <= 1 ? plotWidth / 2 : index / (rows.length - 1) * plotWidth);
    const y = value => margin.top + plotHeight - Number(value || 0) / max * plotHeight;
    const points = key => rows.map((row, index) => `${x(index)},${y(row[key])}`).join(" ");
    const labelEvery = Math.max(1, Math.ceil(rows.length / 6));
    return `<div class="legend"><span><i class="upload"></i>活跃账号</span><span><i></i>新增用户账号</span></div><div class="chart-wrap"><svg class="chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="账号活跃和新增趋势图">
      ${ticks.map(tick => `<line class="grid-line" x1="${margin.left}" x2="${width - margin.right}" y1="${y(tick)}" y2="${y(tick)}"></line><text x="${margin.left - 10}" y="${y(tick) + 4}" text-anchor="end">${escapeHtml(Math.round(tick))}</text>`).join("")}
      <line class="axis-line" x1="${margin.left}" x2="${margin.left}" y1="${margin.top}" y2="${margin.top + plotHeight}"></line><line class="axis-line" x1="${margin.left}" x2="${width - margin.right}" y1="${margin.top + plotHeight}" y2="${margin.top + plotHeight}"></line>
      <polyline class="line-upload" points="${points("activeAccounts")}"></polyline><polyline class="line-download" points="${points("newAccounts")}"></polyline>
      ${rows.map((row, index) => `<g><circle class="point-upload" cx="${x(index)}" cy="${y(row.activeAccounts)}" r="3.5"><title>${escapeHtml(row.day)} 活跃账号：${escapeHtml(row.activeAccounts)}</title></circle><circle class="point-download" cx="${x(index)}" cy="${y(row.newAccounts)}" r="3.5"><title>${escapeHtml(row.day)} 新增账号：${escapeHtml(row.newAccounts)}</title></circle>${index % labelEvery === 0 || index === rows.length - 1 ? `<text x="${x(index)}" y="${height - 28}" text-anchor="middle">${escapeHtml(String(row.day).slice(5))}</text>` : ""}</g>`).join("")}
    </svg></div>`;
  }

  async function api(path, options = {}) {
    const token = localStorage.getItem(storageKey) || "";
    const response = await fetch(`${base}/api${path}`, {
      ...options,
      headers: { Authorization: `Bearer ${token}`, ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers },
    });
    const body = await response.json().catch(() => ({}));
    if (response.status === 401) { localStorage.removeItem(storageKey); if (!dialog.open) dialog.showModal(); throw new Error("登录会话无效或已过期"); }
    if (!response.ok || !body.ok) {
      const code = body.error || `HTTP ${response.status}`;
      const reason = code === "invalid_request" && response.status === 409
        ? "相同标识已经存在或当前状态不允许此操作"
        : errorLabels[code] || `请求失败（${code}）`;
      const error = new Error(`${reason}${body.requestId ? `；请求标识 ${body.requestId}` : ""}`);
      error.code = code;
      throw error;
    }
    return body.data;
  }

  async function loginWithAdminToken(adminToken) {
    const response = await fetch(`${base}/api/session/login`, {
      method: "POST",
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || !body.ok || !body.data?.token) {
      const code = body.error || `HTTP ${response.status}`;
      const reason = errorLabels[code] || `登录请求失败（${code}）`;
      throw new Error(`${reason}${body.requestId ? `；请求标识：${body.requestId}` : ""}`);
    }
    return body.data;
  }

  async function loadDashboard() {
    const [data, analytics] = await Promise.all([
      api("/dashboard"),
      api("/analytics?days=30&months=12"),
    ]);
    const cumulativeSeries = (rows, currentTotal, newKey, valueKey) => {
      let running = Math.max(0, Number(currentTotal || 0) - rows.reduce((sum, row) => sum + Number(row[newKey] || 0), 0));
      return rows.map(row => ({ ...row, [valueKey]: running += Number(row[newKey] || 0) }));
    };
    const accountSeries = cumulativeSeries(analytics.daily, data.accounts.total, "newAccounts", "totalAccounts");
    const deviceSeries = cumulativeSeries(analytics.daily, data.devices.total, "newDevices", "totalDevices");
    const cards = [
      ["用户账号", data.accounts.total],
      ["今日活跃", data.accounts.activeToday],
      ["本月活跃", data.accounts.activeMonth],
      ["设备总数", data.devices.total],
      ["今日流量", bytes(data.traffic.today.totalBytes)],
      ["本月流量", bytes(data.traffic.month.totalBytes)],
      ["已封禁", data.devices.banned],
      ["已撤销", data.devices.revoked],
    ];
    content.innerHTML = `<div class="grid">${cards.map(([label, value]) => `<article class="card"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></article>`).join("")}</div>
      <div class="section-heading"><h2>核心指标趋势</h2><p>数据点可悬停查看对应日期与精确值</p></div>
      <div class="metric-grid">
        ${metricTrendChart({ title: "用户账号", current: data.accounts.total, rows: accountSeries, key: "totalAccounts", periodKey: "day" })}
        ${metricTrendChart({ title: "今日活跃", current: data.accounts.activeToday, rows: analytics.daily, key: "activeAccounts", periodKey: "day" })}
        ${metricTrendChart({ title: "本月活跃", current: data.accounts.activeMonth, rows: analytics.monthly, key: "activeAccounts", periodKey: "month" })}
        ${metricTrendChart({ title: "设备总数", current: data.devices.total, rows: deviceSeries, key: "totalDevices", periodKey: "day" })}
        ${metricTrendChart({ title: "今日流量", current: bytes(data.traffic.today.totalBytes), rows: analytics.daily, key: "totalBytes", periodKey: "day", format: bytes })}
        ${metricTrendChart({ title: "本月流量", current: bytes(data.traffic.month.totalBytes), rows: analytics.monthly, key: "totalBytes", periodKey: "month", format: bytes })}
      </div>
      <section class="panel"><div class="panel-head"><div><h2>最近 30 日流量（含今日）</h2><p class="panel-subtitle">横轴为日期，纵轴为流量；最右侧数据点代表今日</p></div></div>${trafficChart(data.daily, data.traffic.today, true)}</section>`;
  }

  async function loadSubscriptions() {
    const rows = await api("/subscriptions");
    content.innerHTML = `<section class="panel"><div class="panel-head"><h2>订阅源</h2><button class="primary" id="new-source">新增</button></div><table><thead><tr><th>名称</th><th>备注</th><th>模式</th><th>外源健康</th><th>状态</th><th>排序</th><th>操作</th></tr></thead><tbody>${rows.map(row => `<tr><td><strong>${escapeHtml(row.name)}</strong><br><code>${escapeHtml(row.id)}</code></td><td>${escapeHtml(row.note || "-")}</td><td>${escapeHtml(sourceTypeLabel(row.sourceType))}</td><td>${externalHealthBadge(row)}</td><td><span class="badge ${row.enabled ? "active" : ""}">${row.enabled ? "启用" : "停用"}</span></td><td>${row.sortOrder}</td><td class="actions"><button data-test="${escapeHtml(row.id)}">测试</button>${row.sourceType === "external" ? `<button data-refresh="${escapeHtml(row.id)}">刷新</button>` : ""}<button data-edit="${escapeHtml(row.id)}">编辑</button><button class="danger" data-delete="${escapeHtml(row.id)}">删除</button></td></tr>`).join("") || `<tr><td colspan="7" class="empty">还没有订阅源</td></tr>`}</tbody></table></section><section id="source-editor"></section>`;
    document.querySelector("#new-source").onclick = () => renderSourceEditor();
    content.querySelectorAll("[data-edit]").forEach(button => button.onclick = async () => {
      const row = rows.find(source => source.id === button.dataset.edit);
      if (!row) return;
      const detail = await runAction(button, {
        working: "读取正文中…",
        success: "订阅源已读取",
        failure: "读取订阅源失败",
      }, () => api(`/subscriptions/${encodeURIComponent(row.id)}`));
      if (detail) renderSourceEditor(detail);
    });
    content.querySelectorAll("[data-delete]").forEach(button => button.onclick = async () => {
      if (!confirm(`删除订阅源 ${button.dataset.delete}？`)) return;
      await runAction(button, {
        working: "删除中…",
        success: "订阅源已删除",
        failure: "删除失败",
      }, async () => {
        await api(`/subscriptions/${encodeURIComponent(button.dataset.delete)}`, { method: "DELETE" });
        await loadSubscriptions();
      });
    });
    content.querySelectorAll("[data-test]").forEach(button => button.onclick = async () => {
      await runAction(button, {
        working: "测试中…",
        success: result => `测试成功：${formatLabel(result.format)} · ${result.lines} 行 · ${bytes(result.bytes)}`,
        failure: "测试失败",
      }, () => api(`/subscriptions/${encodeURIComponent(button.dataset.test)}/test`, { method: "POST" }));
    });
    content.querySelectorAll("[data-refresh]").forEach(button => button.onclick = async () => {
      const result = await runAction(button, {
        working: "刷新中…",
        success: result => `刷新成功：${formatLabel(result.format)} · ${bytes(result.bytes)}`,
        failure: "刷新失败",
      }, () => api(`/subscriptions/${encodeURIComponent(button.dataset.refresh)}/refresh`, { method: "POST" }));
      if (result) await loadSubscriptions();
    });
  }

  function renderSourceEditor(row) {
    const editor = document.querySelector("#source-editor");
    const enabled = row ? Boolean(row.enabled) : true;
    editor.innerHTML = `<form class="panel form-grid" id="source-form"><h2 class="wide">${row ? "编辑订阅源" : "新增订阅源"}</h2><label class="source-main-field">订阅源标识<input name="id" ${row ? "readonly" : ""} required pattern="[a-z0-9][a-z0-9_-]{0,63}" maxlength="64" value="${escapeHtml(row?.id || "")}"><small>输入的大写字母会自动转成小写</small></label><label class="source-main-field">名称<input name="name" required maxlength="100" value="${escapeHtml(row?.name || "")}"><small>用于控制台和客户端展示</small></label><div class="source-meta-row wide"><label>备注<input name="note" maxlength="200" value="${escapeHtml(row?.note || "")}" placeholder="可选，最多 200 个字符"></label><label>排序<input name="sortOrder" type="number" value="${row?.sortOrder ?? 0}"></label><label>缓存秒数<input name="cacheTtlSeconds" type="number" min="30" max="86400" value="${row?.cacheTtlSeconds || 300}"></label></div><div class="source-settings-row wide"><label>订阅模式<select name="sourceType"><option value="managed" ${row?.sourceType === "managed" ? "selected" : ""}>托管</option><option value="external" ${row?.sourceType !== "managed" ? "selected" : ""}>外源 + 托管</option></select></label><label class="source-specific" id="merge-mode-field">排序方式<select name="mergeMode"><option value="external_first" ${(row?.mergeMode || "external_first") === "external_first" ? "selected" : ""}>订阅在前</option><option value="managed_first" ${row?.mergeMode === "managed_first" ? "selected" : ""}>托管在前</option><option value="interleave_external_first" ${row?.mergeMode === "interleave_external_first" ? "selected" : ""}>交替(订阅在前)</option><option value="interleave_managed_first" ${row?.mergeMode === "interleave_managed_first" ? "selected" : ""}>交替(托管在前)</option></select></label><label class="source-specific" id="external-health-field">健康监测与故障回退<select name="externalHealthEnabled"><option value="true" ${row?.externalHealthEnabled !== 0 ? "selected" : ""}>启用</option><option value="false" ${row?.externalHealthEnabled === 0 ? "selected" : ""}>停用</option></select><small>每小时检测；失败时下发最近一次成功内容</small></label></div><div class="external-source-row wide"><label class="source-specific" id="source-url-field">外源订阅地址<input name="sourceUrl" type="url" maxlength="2048" value="${escapeHtml(row?.sourceUrl || "")}" placeholder="https://..."><small>外源 + 托管模式必填 HTTPS 地址</small></label><label>状态<select name="enabled"><option value="true" ${enabled ? "selected" : ""}>启用</option><option value="false" ${!enabled ? "selected" : ""}>停用</option></select></label></div><label class="wide source-specific">托管订阅正文<textarea class="source-content" name="managedContent" placeholder="每行一个节点 URI">${escapeHtml(row?.managedContent || "")}</textarea><small id="managed-content-hint"></small></label><div class="form-actions wide"><button class="primary" type="submit">保存</button><button class="secondary" type="button" id="cancel-source">取消</button></div></form>`;
    const form = document.querySelector("#source-form");
    const typeInput = form.elements.sourceType;
    const urlInput = form.elements.sourceUrl;
    const managedInput = form.elements.managedContent;
    const syncSourceType = () => {
      const external = typeInput.value === "external";
      form.querySelector("#source-url-field").hidden = !external;
      form.querySelector("#merge-mode-field").hidden = !external;
      form.querySelector("#external-health-field").hidden = !external;
      urlInput.required = external;
      managedInput.required = !external;
      form.querySelector("#managed-content-hint").textContent = external
        ? "可选；合并支持节点 URI 列表或 Base64 节点订阅，留空则只下发外源"
        : "必填；可使用服务端支持的独立订阅格式";
    };
    typeInput.addEventListener("change", syncSourceType);
    syncSourceType();
    const idInput = form.elements.id;
    idInput.addEventListener("input", () => { idInput.value = idInput.value.toLowerCase(); });
    document.querySelector("#cancel-source").onclick = () => { editor.innerHTML = ""; };
    form.onsubmit = async event => {
      event.preventDefault();
      const values = new FormData(form);
      const payload = {
        id: String(values.get("id") || "").toLowerCase(),
        name: String(values.get("name") || ""),
        note: String(values.get("note") || ""),
        sourceType: String(values.get("sourceType")),
        sourceUrl: values.get("sourceType") === "external" ? String(values.get("sourceUrl") || "") : null,
        managedContent: String(values.get("managedContent") || ""),
        enabled: values.get("enabled") === "true",
        sortOrder: Number(values.get("sortOrder")),
        cacheTtlSeconds: Number(values.get("cacheTtlSeconds")),
        mergeMode: values.get("sourceType") === "external" ? String(values.get("mergeMode")) : "external_first",
        externalHealthEnabled: values.get("sourceType") === "external" && values.get("externalHealthEnabled") === "true",
      };
      await runAction(event.submitter, {
        working: "保存中…",
        success: row ? "订阅源已更新" : "订阅源已新增",
        failure: "保存失败",
      }, async () => {
        await api(row ? `/subscriptions/${encodeURIComponent(row.id)}` : "/subscriptions", {
          method: row ? "PATCH" : "POST",
          body: JSON.stringify(payload),
        });
        await loadSubscriptions();
      });
    };
  }

  async function loadAccounts(requestedPage = listState.accounts.page) {
    const query = listState.accounts.query;
    const data = await api(`/accounts?page=${Math.max(1, requestedPage)}&pageSize=20&q=${encodeURIComponent(query)}`);
    listState.accounts.page = data.page;
    const rows = data.items;
    content.innerHTML = `<section class="panel"><div class="list-heading"><div><h2>用户账号</h2><p class="panel-subtitle">可按账号标识或状态检索</p></div><span>共 ${escapeHtml(data.total)} 个</span></div><form class="list-search" id="accounts-search-form"><input name="query" value="${escapeHtml(query)}" maxlength="128" placeholder="输入账号标识或状态"><button class="primary" type="submit">查询</button>${query ? `<button class="secondary" id="accounts-search-clear" type="button">清除</button>` : ""}</form><table><thead><tr><th>账号标识</th><th>状态</th><th>正常设备 / 总设备</th><th>今日流量</th><th>本月流量</th><th>累计流量</th><th>最后活跃（北京时间）</th><th>操作</th></tr></thead><tbody>${rows.map(row => `<tr><td><code>${escapeHtml(row.id)}</code></td><td><span class="badge ${escapeHtml(row.status)}">${escapeHtml(statusLabel(row.status))}</span></td><td>${escapeHtml(row.activeDeviceCount)} / ${escapeHtml(row.deviceCount)}</td><td>${escapeHtml(bytes(row.todayBytes))}</td><td>${escapeHtml(bytes(row.monthBytes))}</td><td>${escapeHtml(bytes(row.totalBytes))}</td><td>${escapeHtml(dateTime(row.lastActiveAt))}</td><td class="actions"><button data-account-view="${escapeHtml(row.id)}">详情</button>${row.status === "banned" ? `<button data-account-action="unban" data-id="${escapeHtml(row.id)}">解封</button>` : `<button class="danger" data-account-action="ban" data-id="${escapeHtml(row.id)}">封禁</button>`}</td></tr>`).join("") || `<tr><td colspan="8" class="empty">没有符合条件的用户账号</td></tr>`}</tbody></table>${pager(data, "个账号")}</section><section id="account-detail"></section>`;
    bindListControls("accounts", loadAccounts);
    content.querySelectorAll("[data-account-action]").forEach(button => button.onclick = async () => {
      const isBan = button.dataset.accountAction === "ban";
      await runAction(button, {
        working: isBan ? "封禁中…" : "解封中…",
        success: isBan ? "用户账号已封禁" : "用户账号已解封",
        failure: isBan ? "封禁失败" : "解封失败",
      }, async () => {
        await api(`/accounts/${encodeURIComponent(button.dataset.id)}/${button.dataset.accountAction}`, { method: "POST" });
        await loadAccounts(listState.accounts.page);
      });
    });
    content.querySelectorAll("[data-account-view]").forEach(button => button.onclick = async () => {
      const account = await runAction(button, {
        working: "加载中…",
        success: "账号详情已加载",
        failure: "加载账号详情失败",
      }, () => api(`/accounts/${encodeURIComponent(button.dataset.accountView)}`));
      if (account === null) return;
      const detail = document.querySelector("#account-detail");
      detail.innerHTML = `<section class="panel"><div class="panel-head"><div><h2>账号详情</h2><p class="panel-subtitle mono">${escapeHtml(account.id)}</p></div><button class="secondary" id="close-account-detail">关闭</button></div><div class="grid"><article class="card"><span>创建时间（北京时间）</span><strong class="card-text">${escapeHtml(dateTime(account.createdAt))}</strong></article><article class="card"><span>状态</span><strong>${escapeHtml(statusLabel(account.status))}</strong></article><article class="card"><span>设备数</span><strong>${escapeHtml(account.deviceCount)}</strong></article><article class="card"><span>累计流量</span><strong>${escapeHtml(bytes(account.totalBytes))}</strong></article></div><table><thead><tr><th>设备标识</th><th>平台</th><th>状态</th><th>公钥指纹</th><th>最后活跃（北京时间）</th></tr></thead><tbody>${account.devices.map(device => `<tr><td class="mono">${escapeHtml(device.id)}</td><td>${escapeHtml(platformLabel(device.platform))}</td><td><span class="badge ${escapeHtml(device.status)}">${escapeHtml(statusLabel(device.status))}</span></td><td class="mono">${escapeHtml(device.publicKeyFingerprint)}</td><td>${escapeHtml(dateTime(device.lastSeenAt))}</td></tr>`).join("")}</tbody></table></section>`;
      document.querySelector("#close-account-detail").onclick = () => { detail.innerHTML = ""; };
    });
  }

  async function loadDevices(requestedPage = listState.devices.page) {
    const query = listState.devices.query;
    const data = await api(`/devices?page=${Math.max(1, requestedPage)}&pageSize=20&q=${encodeURIComponent(query)}`);
    listState.devices.page = data.page;
    const rows = data.items;
    content.innerHTML = `<section class="panel"><div class="list-heading"><div><h2>设备</h2><p class="panel-subtitle">可按设备标识、账号标识、平台或状态检索</p></div><span>共 ${escapeHtml(data.total)} 台</span></div><form class="list-search" id="devices-search-form"><input name="query" value="${escapeHtml(query)}" maxlength="128" placeholder="输入设备标识、账号标识、平台或状态"><button class="primary" type="submit">查询</button>${query ? `<button class="secondary" id="devices-search-clear" type="button">清除</button>` : ""}</form><table><thead><tr><th>设备</th><th>所属账号</th><th>平台</th><th>状态</th><th>公钥指纹</th><th>最后活跃（北京时间）</th><th>操作</th></tr></thead><tbody>${rows.map(row => `<tr><td><strong>${escapeHtml(row.displayName || "未命名")}</strong><br><code>${escapeHtml(row.id)}</code></td><td><code>${escapeHtml(row.accountId)}</code></td><td>${escapeHtml(platformLabel(row.platform))}</td><td><span class="badge ${escapeHtml(row.status)}">${escapeHtml(statusLabel(row.status))}</span></td><td class="mono">${escapeHtml(row.publicKeyFingerprint)}</td><td>${escapeHtml(dateTime(row.lastSeenAt))}</td><td class="actions">${row.status === "banned" ? `<button data-device-action="unban" data-id="${escapeHtml(row.id)}">解封</button>` : `<button data-device-action="ban" data-id="${escapeHtml(row.id)}">封禁</button>`}<button class="danger" data-device-action="revoke" data-id="${escapeHtml(row.id)}">撤销</button></td></tr>`).join("") || `<tr><td colspan="7" class="empty">没有符合条件的设备</td></tr>`}</tbody></table>${pager(data, "台设备")}</section>`;
    content.querySelector(".list-heading h2").textContent = "设备管理";
    bindListControls("devices", loadDevices);
    content.querySelectorAll("[data-device-action]").forEach(button => button.onclick = async () => {
      const labels = { ban: ["封禁中…", "设备已封禁", "封禁失败"], unban: ["解封中…", "设备已解封", "解封失败"], revoke: ["撤销中…", "设备已撤销", "撤销失败"] }[button.dataset.deviceAction];
      await runAction(button, { working: labels[0], success: labels[1], failure: labels[2] }, async () => {
        await api(`/devices/${encodeURIComponent(button.dataset.id)}/${button.dataset.deviceAction}`, { method: "POST" });
        await loadDevices(listState.devices.page);
      });
    });
  }

  async function loadAnalytics() {
    const data = await api("/analytics?days=30&months=12");
    const latest = data.daily[data.daily.length - 1] || { uploadBytes: 0, downloadBytes: 0, totalBytes: 0 };
    const maxPlatform = Math.max(1, ...data.platforms.map(row => Number(row.count || 0)));
    content.innerHTML = `<div class="grid"><article class="card"><span>累计上传</span><strong>${escapeHtml(bytes(data.cumulative.uploadBytes))}</strong></article><article class="card"><span>累计下载</span><strong>${escapeHtml(bytes(data.cumulative.downloadBytes))}</strong></article><article class="card"><span>累计总流量</span><strong>${escapeHtml(bytes(data.cumulative.totalBytes))}</strong></article></div>
      <section class="panel"><div class="panel-head"><div><h2>每日账号趋势</h2><p class="panel-subtitle">活跃账号以当日产生用量上报的用户账号计数</p></div></div>${accountTrendChart(data.daily)}</section>
      <section class="panel"><div class="panel-head"><div><h2>每日流量趋势</h2><p class="panel-subtitle">最近 30 日上传、下载及合计</p></div></div>${trafficChart(data.daily, latest)}</section>
      <section class="panel"><div class="panel-head"><h2>平台设备分布</h2></div><div class="platform-list">${data.platforms.map(row => `<div class="platform-row"><span>${escapeHtml(platformLabel(row.platform))}</span><progress max="${maxPlatform}" value="${escapeHtml(row.count)}"></progress><strong>${escapeHtml(row.count)}</strong></div>`).join("") || `<div class="empty">暂无设备数据</div>`}</div></section>
      <section class="panel"><div class="panel-head"><h2>最近 12 个月</h2></div><table><thead><tr><th>月份</th><th>活跃账号</th><th>新增账号</th><th>上传</th><th>下载</th><th>总流量</th></tr></thead><tbody>${data.monthly.map(row => `<tr><td>${escapeHtml(row.month)}</td><td>${escapeHtml(row.activeAccounts)}</td><td>${escapeHtml(row.newAccounts)}</td><td>${escapeHtml(bytes(row.uploadBytes))}</td><td>${escapeHtml(bytes(row.downloadBytes))}</td><td>${escapeHtml(bytes(row.totalBytes))}</td></tr>`).join("")}</tbody></table></section>`;
  }

  function richTextEditor(name, label, value, placeholder) {
    return `<div class="wide rich-text-field" data-rich-text-field="${escapeHtml(name)}"><div class="rich-text-field-head"><label for="${escapeHtml(name)}">${escapeHtml(label)}</label><div class="update-tabs rich-text-tabs" role="tablist" aria-label="${escapeHtml(label)}显示模式"><button class="active" type="button" data-rich-text-mode="input" data-rich-text-target="${escapeHtml(name)}" aria-selected="true">输入框</button><button type="button" data-rich-text-mode="preview" data-rich-text-target="${escapeHtml(name)}" aria-selected="false">预览</button></div></div><textarea id="${escapeHtml(name)}" name="${escapeHtml(name)}" maxlength="16384" placeholder="${escapeHtml(placeholder)}">${escapeHtml(value || "")}</textarea><iframe class="rich-text-preview" data-rich-text-preview="${escapeHtml(name)}" title="${escapeHtml(label)}预览" sandbox referrerpolicy="no-referrer" hidden></iframe></div>`;
  }

  function richTextPreviewDocument(value) {
    const content = String(value || "").trim();
    return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src * data: blob:; media-src * data: blob:; style-src 'unsafe-inline'"><meta name="viewport" content="width=device-width,initial-scale=1"><style>html{color-scheme:light}body{margin:12px;overflow-wrap:anywhere;color:#152033;background:#fff;font:14px/1.65 system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}img{max-width:100%;height:auto}table{max-width:100%;border-collapse:collapse}th,td{padding:6px 8px;border:1px solid #d9e2ef}a{color:#2563eb}.empty{color:#74829a}</style></head><body>${content || '<p class="empty">暂无可预览的内容</p>'}</body></html>`;
  }

  async function loadSettings() {
    const data = await api("/settings");
    const notice = data.notice || {};
    const mobileUpdate = data.appUpdate || {};
    const pcUpdate = data.pcAppUpdate || {};
    const settings = data.settings || {};
    let currentSecurity = data.security || {};
    const adminAccess = data.adminAccess || {};
    content.innerHTML = `<form id="admin-access-form" class="panel form-grid">
      <div class="panel-head wide"><div><h2>登录相关设置</h2><p class="panel-subtitle">登录密码不会明文保存；JWT 私钥不会回显</p></div><button class="primary" type="submit">保存登录相关设置</button></div>
      <label class="wide">访问路径<input name="accessPath" required minlength="8" maxlength="128" pattern="[A-Za-z0-9][A-Za-z0-9_-]{7,127}" value="${escapeHtml(adminAccess.accessPath || "")}" autocomplete="off"><small>不包含开头和结尾的 /，修改后页面会跳转到新地址</small></label>
      <label class="admin-token-field">新登录密码<input name="newAdminToken" type="password" minlength="8" maxlength="16" autocomplete="new-password" placeholder="留空表示不修改"><small>长度为 8–16 个字符；${adminAccess.adminTokenConfigured ? "当前已设置登录密码" : "当前使用部署时设置的密码"}</small></label>
      <label class="admin-token-field">确认新登录密码<input name="confirmAdminToken" type="password" minlength="8" maxlength="16" autocomplete="new-password" placeholder="再次输入；不修改时留空"><small>请与左侧新登录密码保持一致</small></label>
      <label class="admin-token-field">新 JWT 私钥<input name="newJwtSecret" type="password" minlength="32" maxlength="128" autocomplete="new-password" placeholder="留空表示不修改"><small>长度为 32–128 个字符；${adminAccess.jwtSecretCustomized ? "当前使用手动设置的私钥" : "当前使用系统自动生成的 UUID"}</small></label>
      <label class="admin-token-field">确认新 JWT 私钥<input name="confirmJwtSecret" type="password" minlength="32" maxlength="128" autocomplete="new-password" placeholder="再次输入；不修改时留空"><small>更换后，所有已签发的 JWT 会话立即失效</small></label>
      <div class="login-security-row wide">
        <label>每分钟最多尝试登录次数<input name="adminLoginRateLimitPerMinute" type="number" min="1" max="60" step="1" required value="${escapeHtml(currentSecurity.adminLoginRateLimitPerMinute ?? 3)}"><small>包括成功和失败的登录请求，可设置 1–60 次</small></label>
        <label>启用管理登录限流<select name="adminLoginRateLimitEnabled"><option value="true" ${currentSecurity.adminLoginRateLimitEnabled !== false ? "selected" : ""}>启用</option><option value="false" ${currentSecurity.adminLoginRateLimitEnabled === false ? "selected" : ""}>停用</option></select><small>限制同一管理入口下单个 IP 的登录频率</small></label>
      </div>
    </form>
    <form id="settings-form" class="panel form-grid">
      <div class="panel-head wide"><div><h2>其他设置</h2><p class="panel-subtitle">修改并保存后，配置将立即生效</p></div><button class="primary" type="submit">保存其他设置</button></div>
      <div class="section-title"><h3>应用公告</h3><p>向客户端下发公告内容</p></div>
      <label class="check-label"><input name="noticeEnabled" type="checkbox" ${notice.enabled ? "checked" : ""}>启用公告</label>
      <label class="check-label"><input name="noticeShowOnce" type="checkbox" ${notice.showOnce !== false ? "checked" : ""}>每台设备仅展示一次</label>
      <label>公告标识<input name="noticeId" maxlength="128" value="${escapeHtml(notice.id || "")}" placeholder="maintenance-2026"></label>
      <label>公告标题<input name="noticeTitle" maxlength="200" value="${escapeHtml(notice.title || "")}" placeholder="服务公告"></label>
      ${richTextEditor("noticeContent", "公告正文", notice.content, "输入要向客户端展示的 HTML 富文本")}

      <div class="section-title update-section-title"><div><h3>应用更新</h3><p>分别维护移动端与 PC 端更新；关闭后对应客户端不会收到更新信息</p></div><div class="update-tabs" role="tablist" aria-label="更新平台"><button class="active" type="button" data-update-platform="mobile">移动端</button><button type="button" data-update-platform="pc">PC 端</button></div></div>
      <div class="update-panel form-grid wide" data-update-panel="mobile">
        <label class="check-label"><input name="mobileUpdateEnabled" type="checkbox" ${data.appUpdate ? "checked" : ""}>启用移动端更新提示</label>
        <label class="check-label"><input name="mobileUpdateForce" type="checkbox" ${mobileUpdate.force ? "checked" : ""}>强制更新</label>
        <label>内部版本号<input name="mobileVersionCode" type="number" min="0" step="1" value="${escapeHtml(mobileUpdate.versionCode ?? 0)}"></label>
        <label>版本名称<input name="mobileVersionName" maxlength="64" value="${escapeHtml(mobileUpdate.versionName || "")}" placeholder="2.0.0"></label>
        <label class="wide">下载地址<input name="mobileDownloadUrl" type="url" maxlength="2048" value="${escapeHtml(mobileUpdate.downloadUrl || "")}" placeholder="https://example.com/firefly.apk"></label>
        ${richTextEditor("mobileChangelog", "更新说明", mobileUpdate.changelog, "输入移动端本次更新的 HTML 富文本")}
      </div>
      <div class="update-panel form-grid wide" data-update-panel="pc" hidden>
        <label class="check-label"><input name="pcUpdateEnabled" type="checkbox" ${data.pcAppUpdate ? "checked" : ""}>启用 PC 端更新提示</label>
        <label class="check-label"><input name="pcUpdateForce" type="checkbox" ${pcUpdate.force ? "checked" : ""}>强制更新</label>
        <label>内部版本号<input name="pcVersionCode" type="number" min="0" step="1" value="${escapeHtml(pcUpdate.versionCode ?? 0)}"></label>
        <label>版本名称<input name="pcVersionName" maxlength="64" value="${escapeHtml(pcUpdate.versionName || "")}" placeholder="2.0.0"></label>
        <label class="wide">下载地址<input name="pcDownloadUrl" type="url" maxlength="2048" value="${escapeHtml(pcUpdate.downloadUrl || "")}" placeholder="https://example.com/firefly-setup.exe"></label>
        ${richTextEditor("pcChangelog", "更新说明", pcUpdate.changelog, "输入 PC 端本次更新的 HTML 富文本")}
      </div>

      <div class="section-title"><div><h3>通用链接与网络</h3><p>客户端展示的入口及后端拉取订阅超时</p></div></div>
      <label>官方网站<input name="websiteUrl" type="url" maxlength="2048" value="${escapeHtml(settings.websiteUrl || "")}" placeholder="https://..."></label>
      <label>反馈邮箱<input name="feedbackEmail" type="email" maxlength="254" value="${escapeHtml(settings.feedbackEmail || "")}" placeholder="support@example.com"></label>
      <label>反馈页面<input name="feedbackUrl" type="url" maxlength="2048" value="${escapeHtml(settings.feedbackUrl || "")}" placeholder="https://..."></label>
      <label>GitHub 地址<input name="githubUrl" type="url" maxlength="2048" value="${escapeHtml(settings.githubUrl || "")}" placeholder="https://github.com/..."></label>
      <label>订阅拉取超时（毫秒）<input name="subscriptionFetchTimeoutMs" type="number" min="1000" max="60000" step="100" required value="${escapeHtml(settings.subscriptionFetchTimeoutMs ?? 15000)}"></label>
    </form>`;
    const updateTabs = [...document.querySelectorAll("[data-update-platform]")];
    const updatePanels = [...document.querySelectorAll("[data-update-panel]")];
    const activateUpdatePlatform = platform => {
      updateTabs.forEach(button => {
        const active = button.dataset.updatePlatform === platform;
        button.classList.toggle("active", active);
        button.setAttribute("aria-selected", String(active));
      });
      updatePanels.forEach(panel => { panel.hidden = panel.dataset.updatePanel !== platform; });
    };
    updateTabs.forEach(button => { button.onclick = () => activateUpdatePlatform(button.dataset.updatePlatform); });
    document.querySelectorAll("[data-rich-text-mode]").forEach(button => {
      button.onclick = () => {
        const target = button.dataset.richTextTarget;
        const field = document.querySelector(`[data-rich-text-field="${target}"]`);
        const textarea = field?.querySelector("textarea");
        const preview = field?.querySelector("[data-rich-text-preview]");
        if (!field || !textarea || !preview) return;
        const previewing = button.dataset.richTextMode === "preview";
        field.querySelectorAll("[data-rich-text-mode]").forEach(modeButton => {
          const active = modeButton.dataset.richTextMode === button.dataset.richTextMode;
          modeButton.classList.toggle("active", active);
          modeButton.setAttribute("aria-selected", String(active));
        });
        if (!previewing) {
          preview.hidden = true;
          textarea.hidden = false;
          return;
        }

        const previewHeight = Math.max(textarea.offsetHeight, 112);
        textarea.hidden = true;

        // Chromium can occasionally cancel a srcdoc navigation scheduled while
        // an iframe is hidden. A fresh, visible iframe also guarantees that a
        // failed previous load can never be mistaken for an already-rendered
        // value when the user switches between input and preview repeatedly.
        const freshPreview = preview.cloneNode(false);
        freshPreview.hidden = false;
        freshPreview.style.height = `${previewHeight}px`;
        preview.replaceWith(freshPreview);
        freshPreview.srcdoc = richTextPreviewDocument(textarea.value);
      };
    });
    document.querySelector("#admin-access-form").onsubmit = async event => {
      event.preventDefault();
      const form = new FormData(event.target);
      const accessPath = String(form.get("accessPath") || "").trim().replace(/^\/+|\/+$/g, "");
      const newAdminToken = String(form.get("newAdminToken") || "");
      const confirmation = String(form.get("confirmAdminToken") || "");
      const newJwtSecret = String(form.get("newJwtSecret") || "");
      const jwtConfirmation = String(form.get("confirmJwtSecret") || "");
      const nextSecurity = {
        adminLoginRateLimitEnabled: form.get("adminLoginRateLimitEnabled") === "true",
        adminLoginRateLimitPerMinute: Number(form.get("adminLoginRateLimitPerMinute") || 3),
      };
      const accessSettingsChanged = accessPath !== adminAccess.accessPath || Boolean(newAdminToken || newJwtSecret);
      const securityChanged = nextSecurity.adminLoginRateLimitEnabled !== currentSecurity.adminLoginRateLimitEnabled
        || nextSecurity.adminLoginRateLimitPerMinute !== currentSecurity.adminLoginRateLimitPerMinute;
      if (newAdminToken !== confirmation) {
        notify("保存失败：两次输入的登录密码不一致", "bad", 7000);
        return;
      }
      if (newJwtSecret !== jwtConfirmation) {
        notify("保存失败：两次输入的 JWT 私钥不一致", "bad", 7000);
        return;
      }
      if (accessSettingsChanged && !confirm("更新后旧访问路径或旧登录密码将立即失效，确定继续吗？")) {
        notify("已取消保存登录相关设置", "busy");
        return;
      }
      const result = await runAction(event.submitter, {
        working: "保存中…",
        success: newAdminToken || newJwtSecret
          ? "安全参数已更新，正在退出登录…"
          : accessPath !== adminAccess.accessPath ? "登录相关设置已更新，正在跳转…" : "登录相关设置已保存",
        failure: "保存登录相关设置失败",
      }, async () => {
        if (securityChanged) {
          const runtime = await api("/settings", {
            method: "PUT",
            body: JSON.stringify({
              notice: data.notice,
              appUpdate: data.appUpdate,
              pcAppUpdate: data.pcAppUpdate,
              settings: data.settings,
              security: nextSecurity,
            }),
          });
          Object.assign(data, runtime);
          currentSecurity = runtime.security || nextSecurity;
        }
        if (!accessSettingsChanged) return { accessPath };
        return api("/settings/admin-access", {
          method: "PUT",
          body: JSON.stringify({
            accessPath,
            ...(newAdminToken ? { newAdminToken } : {}),
            ...(newJwtSecret ? { newJwtSecret } : {}),
          }),
        });
      });
      if (result === null) return;
      if (newAdminToken || newJwtSecret) {
        localStorage.removeItem(storageKey);
        location.replace(`/${encodeURIComponent(result.accessPath)}/`);
        return;
      }
      if (accessPath !== adminAccess.accessPath) location.assign(`/${encodeURIComponent(result.accessPath)}/`);
    };
    document.querySelector("#settings-form").onsubmit = async event => {
      event.preventDefault();
      const form = new FormData(event.target);
      const enabled = name => form.get(name) === "on";
      const value = name => String(form.get(name) || "").trim();
      const appUpdate = prefix => enabled(`${prefix}UpdateEnabled`) ? {
        versionCode: Number(form.get(`${prefix}VersionCode`) || 0),
        versionName: value(`${prefix}VersionName`),
        downloadUrl: value(`${prefix}DownloadUrl`),
        force: enabled(`${prefix}UpdateForce`),
        changelog: value(`${prefix}Changelog`),
      } : null;
      const payload = {
        notice: {
          enabled: enabled("noticeEnabled"),
          id: value("noticeId"),
          title: value("noticeTitle"),
          content: value("noticeContent"),
          showOnce: enabled("noticeShowOnce"),
        },
        appUpdate: appUpdate("mobile"),
        pcAppUpdate: appUpdate("pc"),
        security: currentSecurity,
        settings: {
          websiteUrl: value("websiteUrl"),
          feedbackEmail: value("feedbackEmail"),
          feedbackUrl: value("feedbackUrl"),
          githubUrl: value("githubUrl"),
          subscriptionFetchTimeoutMs: Number(form.get("subscriptionFetchTimeoutMs") || 15000),
        },
      };
      const saved = await runAction(event.submitter, {
        working: "保存中…",
        success: "其他设置已保存",
        failure: "保存其他设置失败",
      }, () => api("/settings", { method: "PUT", body: JSON.stringify(payload) }));
      if (saved) Object.assign(data, saved);
    };
  }

  function auditParams(filters, requestedPage, pageSize = 20) {
    const params = new URLSearchParams({ page: String(Math.max(1, requestedPage)), pageSize: String(pageSize) });
    if (filters.from) params.set("from", new Date(filters.from).toISOString());
    if (filters.to) params.set("to", new Date(filters.to).toISOString());
    if (filters.action) params.set("action", filters.action);
    if (filters.requestId) params.set("requestId", filters.requestId);
    if (filters.targetId) params.set("targetId", filters.targetId);
    return params;
  }

  function csvCell(value) {
    const text = String(value ?? "");
    const safe = /^[=+\-@]/u.test(text) ? `'${text}` : text;
    return `"${safe.replace(/"/g, '""')}"`;
  }

  async function downloadAuditCsv() {
    const filters = listState.audit;
    const first = await api(`/audit?${auditParams(filters, 1, 100)}`);
    const rows = [...first.items];
    for (let currentPage = 2; currentPage <= first.totalPages; currentPage += 1) {
      const next = await api(`/audit?${auditParams(filters, currentPage, 100)}`);
      rows.push(...next.items);
    }
    const columns = ["时间（北京时间）", "操作", "目标类型", "目标标识", "请求标识", "访问 IP", "详情"];
    const lines = [columns, ...rows.map(row => [
      dateTime(row.createdAt),
      auditActionLabel(row.action),
      auditTargetTypeLabel(row.targetType),
      row.targetId || "-",
      row.requestId || "-",
      row.ipAddress || (row.ipHash ? `历史哈希：${row.ipHash}` : "-"),
      auditDetailText(row.detail),
    ])].map(row => row.map(csvCell).join(","));
    const blob = new Blob(["\uFEFF", lines.join("\r\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `firefly-audit-${new Date().toISOString().replace(/[:.]/g, "-")}.csv`;
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    return rows.length;
  }

  function syncAuditCleanupFields() {
    const disabled = auditCleanupForm.elements.all.checked;
    auditCleanupForm.elements.from.disabled = disabled;
    auditCleanupForm.elements.to.disabled = disabled;
  }

  function openAuditCleanup(filters) {
    auditCleanupForm.reset();
    auditCleanupForm.elements.from.value = filters.from || "";
    auditCleanupForm.elements.to.value = filters.to || "";
    syncAuditCleanupFields();
    auditCleanupDialog.showModal();
  }

  async function submitAuditCleanup(button) {
    const all = auditCleanupForm.elements.all.checked;
    const fromValue = auditCleanupForm.elements.from.value;
    const toValue = auditCleanupForm.elements.to.value;
    if (!all && !fromValue && !toValue) {
      notify("清理失败：请填写开始或结束时间，或选择全部清理", "bad", 7000);
      return;
    }
    if (fromValue && toValue && new Date(fromValue) > new Date(toValue)) {
      notify("清理失败：开始时间不能晚于结束时间", "bad", 7000);
      return;
    }
    const description = all
      ? "全部审计日志"
      : fromValue && toValue
        ? `${fromValue} 至 ${toValue} 的审计日志`
        : fromValue
          ? `从 ${fromValue} 起的审计日志`
          : `截至 ${toValue} 的审计日志`;
    if (!confirm(`确定清理${description}吗？该操作无法撤销。`)) {
      notify("已取消清理审计日志", "busy");
      return;
    }
    const payload = all ? { all: true } : {
      all: false,
      ...(fromValue ? { from: new Date(fromValue).toISOString() } : {}),
      ...(toValue ? { to: new Date(toValue).toISOString() } : {}),
    };
    const result = await runAction(button, {
      working: "正在清理日志…",
      success: data => `已清理 ${data.deleted} 条审计日志`,
      failure: "清理审计日志失败",
    }, () => api("/audit", { method: "DELETE", body: JSON.stringify(payload) }));
    if (result === null) return;
    auditCleanupDialog.close();
    await loadAudit(1);
  }

  async function loadAudit(requestedPage = listState.audit.page) {
    const filters = listState.audit;
    const params = auditParams(filters, requestedPage);
    const data = await api(`/audit?${params}`);
    listState.audit.page = data.page;
    const rows = data.items;
    const hasFilters = filters.from || filters.to || filters.action || filters.requestId || filters.targetId;
    content.innerHTML = `<section class="panel"><div class="list-heading"><div><h2>审计日志</h2><p class="panel-subtitle">按北京时间范围、操作类型、请求标识或目标标识筛选</p></div><span>共 ${escapeHtml(data.total)} 条</span></div><form class="audit-search" id="audit-search-form"><label>开始时间<input name="from" type="datetime-local" step="1" value="${escapeHtml(filters.from)}"></label><label>结束时间<input name="to" type="datetime-local" step="1" value="${escapeHtml(filters.to)}"></label><label>操作<select name="action"><option value="">全部操作</option>${Object.entries(auditActions).map(([value, label]) => `<option value="${escapeHtml(value)}" ${filters.action === value ? "selected" : ""}>${escapeHtml(label)}</option>`).join("")}</select></label><label>请求标识<input name="requestId" maxlength="128" value="${escapeHtml(filters.requestId)}" placeholder="输入完整或部分请求标识"></label><label>目标标识<input name="targetId" maxlength="128" value="${escapeHtml(filters.targetId)}" placeholder="账号、设备或订阅源标识"></label><div class="audit-search-actions"><button class="primary" type="submit">查询</button>${hasFilters ? `<button class="secondary" id="audit-search-clear" type="button">清除</button>` : ""}</div></form><table><thead><tr><th>时间（北京时间）</th><th>操作</th><th>目标</th><th>请求标识</th><th>访问 IP</th><th>详情</th></tr></thead><tbody>${rows.map(row => `<tr><td>${escapeHtml(dateTime(row.createdAt))}</td><td>${escapeHtml(auditActionLabel(row.action))}</td><td><code>${escapeHtml(row.targetId || "-")}</code></td><td><code>${escapeHtml(row.requestId || "-")}</code></td><td><code>${escapeHtml(row.ipAddress || (row.ipHash ? `历史哈希：${row.ipHash}` : "-"))}</code></td><td class="audit-detail-cell">${auditDetailHtml(row.detail)}</td></tr>`).join("") || `<tr><td colspan="6" class="empty">没有符合条件的审计记录</td></tr>`}</tbody></table>${pager(data, "条记录")}</section>`;
    const totalLabel = content.querySelector(".list-heading > span");
    const headingActions = document.createElement("div");
    headingActions.className = "audit-heading-actions";
    const downloadButton = document.createElement("button");
    downloadButton.className = "secondary";
    downloadButton.type = "button";
    downloadButton.textContent = "下载 CSV";
    const cleanupButton = document.createElement("button");
    cleanupButton.className = "danger";
    cleanupButton.type = "button";
    cleanupButton.textContent = "清理日志";
    totalLabel.replaceWith(headingActions);
    headingActions.append(downloadButton, cleanupButton, totalLabel);
    downloadButton.onclick = () => runAction(downloadButton, {
      working: "正在生成表格…",
      success: count => `已下载 ${count} 条审计记录`,
      failure: "下载审计日志失败",
    }, downloadAuditCsv);
    cleanupButton.onclick = () => openAuditCleanup(filters);
    const form = document.querySelector("#audit-search-form");
    const loadFilteredAudit = nextPage => loadAudit(nextPage).catch(error => notify(`加载审计日志失败：${error?.message || "未知错误"}`, "bad", 7000));
    form.onsubmit = event => {
      event.preventDefault();
      const values = new FormData(form);
      for (const key of ["from", "to", "action", "requestId", "targetId"]) {
        listState.audit[key] = String(values.get(key) || "").trim();
      }
      loadFilteredAudit(1);
    };
    const clear = document.querySelector("#audit-search-clear");
    if (clear) clear.onclick = () => {
      Object.assign(listState.audit, { page: 1, from: "", to: "", action: "", requestId: "", targetId: "" });
      loadFilteredAudit(1);
    };
    content.querySelectorAll("[data-list-page]").forEach(button => {
      button.onclick = () => loadFilteredAudit(Number(button.dataset.listPage));
    });
  }

  async function load(next = page) {
    page = next;
    title.textContent = titles[page];
    document.querySelectorAll("nav button").forEach(button => {
      const selected = button.dataset.page === page;
      button.classList.toggle("active", selected);
      if (selected) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
    setStatus("加载中…", "busy");
    try { await ({ dashboard: loadDashboard, subscriptions: loadSubscriptions, accounts: loadAccounts, devices: loadDevices, analytics: loadAnalytics, settings: loadSettings, audit: loadAudit })[page](); setStatus("已同步", "ok"); } catch (error) { setStatus(error.message || "请求失败", "bad"); content.innerHTML = `<div class="panel error">${escapeHtml(error.message || "请求失败")}</div>`; }
  }

  document.addEventListener("invalid", event => {
    const field = event.target;
    const updatePanel = field.closest?.("[data-update-panel]");
    if (updatePanel?.hidden) document.querySelector(`[data-update-platform="${updatePanel.dataset.updatePanel}"]`)?.click();
    const labels = {
      id: "订阅源标识",
      name: "名称",
      sourceUrl: "外部订阅地址",
      managedContent: "托管订阅正文",
      cacheTtlSeconds: "缓存秒数",
      accessPath: "访问路径",
      newAdminToken: "新登录密码",
      confirmAdminToken: "确认新登录密码",
      newJwtSecret: "新 JWT 私钥",
      confirmJwtSecret: "确认新 JWT 私钥",
      versionCode: "内部版本号",
      downloadUrl: "下载地址",
      mobileVersionCode: "移动端内部版本号",
      mobileDownloadUrl: "移动端下载地址",
      pcVersionCode: "PC 端内部版本号",
      pcDownloadUrl: "PC 端下载地址",
      subscriptionFetchTimeoutMs: "订阅拉取超时",
    };
    notify(`提交失败：请检查“${labels[field.name] || "输入内容"}”的格式、长度或必填内容`, "bad", 7000);
  }, true);

  auditCleanupForm.elements.all.onchange = syncAuditCleanupFields;
  document.querySelector("#cancel-audit-cleanup").onclick = () => auditCleanupDialog.close();
  auditCleanupForm.onsubmit = event => {
    event.preventDefault();
    submitAuditCleanup(event.submitter).catch(error => {
      notify(`清理审计日志失败：${error?.message || "未知错误"}`, "bad", 7000);
    });
  };

  document.querySelectorAll("nav button").forEach(button => button.onclick = () => {
    load(button.dataset.page);
    closeMobileSidebar();
  });
  sidebarToggle.onclick = toggleSidebar;
  sidebarBackdrop.onclick = closeMobileSidebar;
  document.addEventListener("keydown", event => {
    if (event.key === "Escape") closeMobileSidebar();
  });
  sidebarMedia.addEventListener?.("change", () => {
    shell.classList.remove("sidebar-open");
    syncSidebarState();
  });
  themeToggle.onclick = () => applyTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
  document.querySelector("#logout").onclick = async event => {
    closeMobileSidebar();
    await runAction(event.currentTarget, {
      working: "退出中…",
      success: "已退出登录",
      failure: "服务端登出记录失败，本地登录仍将退出",
    }, () => api("/session/logout", { method: "POST" }));
    localStorage.removeItem(storageKey);
    if (!dialog.open) dialog.showModal();
  };
  document.querySelector("#login-form").onsubmit = async event => {
    event.preventDefault();
    const button = event.submitter;
    const result = await runAction(button, {
      working: "登录中…",
      success: "登录成功",
      failure: "登录失败",
    }, async () => {
      const session = await loginWithAdminToken(tokenInput.value);
      localStorage.setItem(storageKey, session.token);
      tokenInput.value = "";
      dialog.close();
      document.querySelector("#login-error").textContent = "";
      await load();
      return true;
    });
    if (result === null) document.querySelector("#login-error").textContent = status.textContent;
  };
  if (localStorage.getItem(sidebarKey) === "true") shell.classList.add("sidebar-collapsed");
  syncSidebarState();
  applyTheme(localStorage.getItem(themeKey) === "dark" ? "dark" : "light");
  sessionStorage.removeItem(legacyStorageKey);
  if (!localStorage.getItem(storageKey)) dialog.showModal(); else load();
})();
