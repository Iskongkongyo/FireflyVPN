export interface NoticeConfig {
  enabled: boolean;
  id: string;
  title: string;
  content: string;
  showOnce: boolean;
}

export interface AppUpdateConfig {
  versionCode: number;
  versionName: string;
  downloadUrl: string;
  force: boolean;
  changelog: string;
}

export interface RuntimeSettings {
  websiteUrl: string;
  feedbackEmail: string;
  feedbackUrl: string;
  githubUrl: string;
  subscriptionFetchTimeoutMs: number;
}

export interface RuntimeConfig {
  notice: NoticeConfig;
  appUpdate: AppUpdateConfig | null;
  pcAppUpdate: AppUpdateConfig | null;
  settings: RuntimeSettings;
}

export const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  notice: {
    enabled: false,
    id: "",
    title: "",
    content: "",
    showOnce: true,
  },
  appUpdate: null,
  pcAppUpdate: null,
  settings: {
    websiteUrl: "",
    feedbackEmail: "",
    feedbackUrl: "",
    githubUrl: "",
    subscriptionFetchTimeoutMs: 15_000,
  },
};

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function text(value: unknown, fallback = "", maximum = 4096): string {
  return typeof value === "string" ? value.slice(0, maximum) : fallback;
}

function normalizeAppUpdate(value: unknown): AppUpdateConfig | null {
  if (value === null || value === undefined) return null;
  const update = record(value);
  return {
    versionCode: Number.isSafeInteger(update.versionCode)
      ? Math.max(Number(update.versionCode), 0)
      : 0,
    versionName: text(update.versionName, "", 64),
    downloadUrl: text(update.downloadUrl, "", 2048),
    force: update.force === true,
    changelog: text(update.changelog, "", 16_384),
  };
}

export function normalizeRuntimeConfig(value: unknown): RuntimeConfig {
  const root = record(value);
  const notice = record(root.notice);
  const settings = record(root.settings);
  return {
    notice: {
      enabled: notice.enabled === true,
      id: text(notice.id, "", 128),
      title: text(notice.title, "", 200),
      content: text(notice.content, "", 16_384),
      showOnce: notice.showOnce !== false,
    },
    appUpdate: normalizeAppUpdate(root.appUpdate),
    pcAppUpdate: normalizeAppUpdate(root.pcAppUpdate),
    settings: {
      websiteUrl: text(settings.websiteUrl, "", 2048),
      feedbackEmail: text(settings.feedbackEmail, "", 254),
      feedbackUrl: text(settings.feedbackUrl, "", 2048),
      githubUrl: text(settings.githubUrl, "", 2048),
      subscriptionFetchTimeoutMs: Number.isSafeInteger(settings.subscriptionFetchTimeoutMs)
        ? Math.min(Math.max(Number(settings.subscriptionFetchTimeoutMs), 1_000), 60_000)
        : DEFAULT_RUNTIME_CONFIG.settings.subscriptionFetchTimeoutMs,
    },
  };
}
