export type SubscriptionSourceType = "managed" | "external";

export interface SubscriptionSourceRecord {
  id: string;
  name: string;
  note: string | null;
  sourceType: SubscriptionSourceType;
  sourceUrl: string | null;
  enabled: number;
  sortOrder: number;
  cacheTtlSeconds: number;
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
  managedContent?: string;
}

export interface SubscriptionContentSummary {
  bytes: number;
  lines: number;
  format: "uri-list" | "base64" | "clash-yaml" | "json" | "text";
}
