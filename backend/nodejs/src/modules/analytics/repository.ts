export interface DashboardSnapshot {
  accountsTotal: number;
  activeAccountsToday: number;
  activeAccountsMonth: number;
  devicesTotal: number;
  devicesActive: number;
  devicesRevoked: number;
  devicesBanned: number;
  todayUploadBytes: number;
  todayDownloadBytes: number;
  monthUploadBytes: number;
  monthDownloadBytes: number;
}

interface CountRow { count: number }
interface UsageRow { uploadBytes: number; downloadBytes: number }

export async function readDashboardSnapshot(
  db: Database,
  todayKey: string,
  monthKey: string,
  todayStartIso: string,
  monthStartIso: string,
): Promise<DashboardSnapshot> {
  const results = await db.batch([
    db.prepare("SELECT COUNT(*) AS count FROM accounts WHERE status != 'deleted'"),
    db.prepare("SELECT COUNT(DISTINCT account_id) AS count FROM devices WHERE last_seen_at >= ?1")
      .bind(todayStartIso),
    db.prepare("SELECT COUNT(DISTINCT account_id) AS count FROM devices WHERE last_seen_at >= ?1")
      .bind(monthStartIso),
    db.prepare("SELECT COUNT(*) AS count FROM devices"),
    db.prepare("SELECT COUNT(*) AS count FROM devices WHERE status = 'active'"),
    db.prepare("SELECT COUNT(*) AS count FROM devices WHERE status = 'revoked'"),
    db.prepare("SELECT COUNT(*) AS count FROM devices WHERE status = 'banned'"),
    db.prepare(`
      SELECT COALESCE(SUM(upload_bytes), 0) AS uploadBytes,
             COALESCE(SUM(download_bytes), 0) AS downloadBytes
      FROM usage_daily WHERE day = ?1
    `).bind(todayKey),
    db.prepare(`
      SELECT COALESCE(SUM(upload_bytes), 0) AS uploadBytes,
             COALESCE(SUM(download_bytes), 0) AS downloadBytes
      FROM usage_monthly WHERE month = ?1
    `).bind(monthKey),
  ]);
  const count = (index: number) => Number((results[index]?.results[0] as unknown as CountRow)?.count ?? 0);
  const usage = (index: number) => results[index]?.results[0] as unknown as UsageRow | undefined;
  const today = usage(7);
  const month = usage(8);
  return {
    accountsTotal: count(0),
    activeAccountsToday: count(1),
    activeAccountsMonth: count(2),
    devicesTotal: count(3),
    devicesActive: count(4),
    devicesRevoked: count(5),
    devicesBanned: count(6),
    todayUploadBytes: Number(today?.uploadBytes ?? 0),
    todayDownloadBytes: Number(today?.downloadBytes ?? 0),
    monthUploadBytes: Number(month?.uploadBytes ?? 0),
    monthDownloadBytes: Number(month?.downloadBytes ?? 0),
  };
}

export async function readDailyUsage(
  db: Database,
  fromDay: string,
): Promise<Array<{ day: string; uploadBytes: number; downloadBytes: number }>> {
  const result = await db.prepare(`
    SELECT day, SUM(upload_bytes) AS uploadBytes, SUM(download_bytes) AS downloadBytes
    FROM usage_daily WHERE day >= ?1 GROUP BY day ORDER BY day ASC
  `).bind(fromDay).all<{ day: string; uploadBytes: number; downloadBytes: number }>();
  return result.results;
}

export async function readMonthlyUsage(
  db: Database,
  fromMonth: string,
): Promise<Array<{ month: string; uploadBytes: number; downloadBytes: number }>> {
  const result = await db.prepare(`
    SELECT month, SUM(upload_bytes) AS uploadBytes, SUM(download_bytes) AS downloadBytes
    FROM usage_monthly WHERE month >= ?1 GROUP BY month ORDER BY month ASC
  `).bind(fromMonth).all<{ month: string; uploadBytes: number; downloadBytes: number }>();
  return result.results;
}

export async function readPlatformDistribution(
  db: Database,
): Promise<Array<{ platform: string; count: number }>> {
  const result = await db.prepare(`
    SELECT platform, COUNT(*) AS count FROM devices GROUP BY platform ORDER BY platform
  `).all<{ platform: string; count: number }>();
  return result.results;
}

export async function readDailyActiveAccounts(
  db: Database,
  fromDay: string,
): Promise<Array<{ day: string; activeAccounts: number }>> {
  const result = await db.prepare(`
    SELECT day, COUNT(DISTINCT account_id) AS activeAccounts
    FROM usage_daily WHERE day >= ?1 GROUP BY day ORDER BY day ASC
  `).bind(fromDay).all<{ day: string; activeAccounts: number }>();
  return result.results;
}

export async function readDailyNewAccounts(
  db: Database,
  fromDay: string,
): Promise<Array<{ day: string; newAccounts: number }>> {
  const result = await db.prepare(`
    SELECT date(datetime(created_at, '+8 hours')) AS day, COUNT(*) AS newAccounts
    FROM accounts
    WHERE status != 'deleted' AND date(datetime(created_at, '+8 hours')) >= ?1
    GROUP BY day ORDER BY day ASC
  `).bind(fromDay).all<{ day: string; newAccounts: number }>();
  return result.results;
}

export async function readDailyNewDevices(
  db: Database,
  fromDay: string,
): Promise<Array<{ day: string; newDevices: number }>> {
  const result = await db.prepare(`
    SELECT date(datetime(created_at, '+8 hours')) AS day, COUNT(*) AS newDevices
    FROM devices
    WHERE date(datetime(created_at, '+8 hours')) >= ?1
    GROUP BY day ORDER BY day ASC
  `).bind(fromDay).all<{ day: string; newDevices: number }>();
  return result.results;
}

export async function readMonthlyActiveAccounts(
  db: Database,
  fromMonth: string,
): Promise<Array<{ month: string; activeAccounts: number }>> {
  const result = await db.prepare(`
    SELECT month, COUNT(DISTINCT account_id) AS activeAccounts
    FROM usage_monthly WHERE month >= ?1 GROUP BY month ORDER BY month ASC
  `).bind(fromMonth).all<{ month: string; activeAccounts: number }>();
  return result.results;
}

export async function readMonthlyNewAccounts(
  db: Database,
  fromMonth: string,
): Promise<Array<{ month: string; newAccounts: number }>> {
  const result = await db.prepare(`
    SELECT substr(date(datetime(created_at, '+8 hours')), 1, 7) AS month,
           COUNT(*) AS newAccounts
    FROM accounts
    WHERE status != 'deleted'
      AND substr(date(datetime(created_at, '+8 hours')), 1, 7) >= ?1
    GROUP BY month ORDER BY month ASC
  `).bind(fromMonth).all<{ month: string; newAccounts: number }>();
  return result.results;
}

export async function readCumulativeUsage(
  db: Database,
): Promise<{ uploadBytes: number; downloadBytes: number }> {
  const row = await db.prepare(`
    SELECT COALESCE(SUM(upload_bytes), 0) AS uploadBytes,
           COALESCE(SUM(download_bytes), 0) AS downloadBytes
    FROM usage_daily
  `).first<{ uploadBytes: number; downloadBytes: number }>();
  return {
    uploadBytes: Number(row?.uploadBytes ?? 0),
    downloadBytes: Number(row?.downloadBytes ?? 0),
  };
}
