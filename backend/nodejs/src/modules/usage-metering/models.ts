export interface UsageReportInput {
  sessionId: string;
  uploadBytes: number;
  downloadBytes: number;
}

export interface UsageReportResult {
  duplicate: boolean;
  sessionId: string;
}
