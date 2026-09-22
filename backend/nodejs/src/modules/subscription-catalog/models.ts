export type SubscriptionSourceType = "managed" | "external";
export type SubscriptionMergeMode =
  | "external_first"
  | "managed_first"
  | "interleave_external_first"
  | "interleave_managed_first";
export type ExternalHealthStatus = "disabled" | "unknown" | "healthy" | "unhealthy";

export interface SubscriptionSourceRecord {
  id: string;
  name: string;
  note: string | null;
  sourceType: SubscriptionSourceType;
  sourceUrl: string | null;
  enabled: number;
  sortOrder: number;
  cacheTtlSeconds: number;
  mergeMode: SubscriptionMergeMode;
  externalHealthEnabled: number;
  externalHealthStatus: ExternalHealthStatus;
  externalLastCheckedAt: string | null;
  externalLastSuccessAt: string | null;
  externalLastError: string | null;
  externalFallbackActive: number;
  createdAt: string;
  updatedAt: string;
}

export interface SubscriptionSourceInput {
  id: string;
  name: string;
  note: string | null;
  sourceType: SubscriptionSourceType;
  sourceUrl: string | null;
  enabled: boolean;
  sortOrder: number;
  cacheTtlSeconds: number;
  mergeMode: SubscriptionMergeMode;
  externalHealthEnabled: boolean;
  managedContent?: string;
}

export interface ExternalHealthOutcome {
  healthy: boolean;
  fallbackActive: boolean;
  errorCode: string | null;
  checkedAt: string;
}

export interface SubscriptionContentSummary {
  bytes: number;
  lines: number;
  format: "uri-list" | "base64" | "clash-yaml" | "json" | "text";
}
