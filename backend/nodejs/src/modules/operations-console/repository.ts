export async function listAdminDevices(
  db: Database,
  accountId: string | null = null,
): Promise<Array<Record<string, unknown>>> {
  const result = await db.prepare(`
    SELECT id, account_id AS accountId, platform, display_name AS displayName,
           status, public_key_spki AS publicKeySpki,
           created_at AS createdAt, last_seen_at AS lastSeenAt
    FROM devices
    WHERE (?1 IS NULL OR account_id = ?1)
    ORDER BY last_seen_at DESC LIMIT 500
  `).bind(accountId).all<Record<string, unknown>>();
  return result.results;
}

export interface AdminListPage {
  items: Array<Record<string, unknown>>;
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}

export async function listAdminDevicesPage(
  db: Database,
  page: number,
  pageSize: number,
  query: string,
): Promise<AdminListPage> {
  const where = `
    WHERE (?1 = '' OR instr(lower(id), lower(?1)) > 0
      OR instr(lower(account_id), lower(?1)) > 0
      OR instr(lower(platform), lower(?1)) > 0
      OR instr(lower(status), lower(?1)) > 0)
  `;
  const count = await db.prepare(`SELECT COUNT(*) AS total FROM devices ${where}`)
    .bind(query).first<{ total: number }>();
  const total = Number(count?.total ?? 0);
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(page, totalPages);
  const result = await db.prepare(`
    SELECT id, account_id AS accountId, platform, display_name AS displayName,
           status, public_key_spki AS publicKeySpki,
           created_at AS createdAt, last_seen_at AS lastSeenAt
    FROM devices ${where}
    ORDER BY last_seen_at DESC, id ASC LIMIT ?2 OFFSET ?3
  `).bind(query, pageSize, (safePage - 1) * pageSize).all<Record<string, unknown>>();
  return { items: result.results, page: safePage, pageSize, total, totalPages };
}

const ACCOUNT_SUMMARY_SQL = `
  WITH device_stats AS (
    SELECT account_id,
           COUNT(*) AS deviceCount,
           SUM(CASE WHEN status = 'active' THEN 1 ELSE 0 END) AS activeDeviceCount,
           MAX(last_seen_at) AS lastActiveAt
    FROM devices GROUP BY account_id
  ), today_usage AS (
    SELECT account_id, upload_bytes + download_bytes AS todayBytes
    FROM usage_daily WHERE day = ?1
  ), month_usage AS (
    SELECT account_id, upload_bytes + download_bytes AS monthBytes
    FROM usage_monthly WHERE month = ?2
  ), total_usage AS (
    SELECT account_id, SUM(upload_bytes + download_bytes) AS totalBytes
    FROM usage_daily GROUP BY account_id
  )
  SELECT a.id, a.display_name AS displayName, a.status,
         a.created_at AS createdAt, a.updated_at AS updatedAt,
         COALESCE(ds.deviceCount, 0) AS deviceCount,
         COALESCE(ds.activeDeviceCount, 0) AS activeDeviceCount,
         ds.lastActiveAt,
         COALESCE(tu.todayBytes, 0) AS todayBytes,
         COALESCE(mu.monthBytes, 0) AS monthBytes,
         COALESCE(au.totalBytes, 0) AS totalBytes
  FROM accounts a
  LEFT JOIN device_stats ds ON ds.account_id = a.id
  LEFT JOIN today_usage tu ON tu.account_id = a.id
  LEFT JOIN month_usage mu ON mu.account_id = a.id
  LEFT JOIN total_usage au ON au.account_id = a.id
`;

export async function listAdminAccountsPage(
  db: Database,
  today: string,
  month: string,
  page: number,
  pageSize: number,
  query: string,
): Promise<AdminListPage> {
  const where = `
    WHERE (?3 = '' OR instr(lower(a.id), lower(?3)) > 0
      OR instr(lower(a.status), lower(?3)) > 0)
  `;
  const count = await db.prepare(`
    SELECT COUNT(*) AS total FROM accounts a
    WHERE (?1 = '' OR instr(lower(a.id), lower(?1)) > 0
      OR instr(lower(a.status), lower(?1)) > 0)
  `).bind(query).first<{ total: number }>();
  const total = Number(count?.total ?? 0);
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(page, totalPages);
  const result = await db.prepare(`${ACCOUNT_SUMMARY_SQL} ${where}
    ORDER BY a.created_at DESC, a.id ASC LIMIT ?4 OFFSET ?5
  `).bind(today, month, query, pageSize, (safePage - 1) * pageSize)
    .all<Record<string, unknown>>();
  return { items: result.results, page: safePage, pageSize, total, totalPages };
}

export async function getAdminAccount(
  db: Database,
  accountId: string,
  today: string,
  month: string,
): Promise<Record<string, unknown> | null> {
  return db.prepare(`${ACCOUNT_SUMMARY_SQL}
    WHERE a.id = ?3 LIMIT 1
  `).bind(today, month, accountId).first<Record<string, unknown>>();
}

export async function setAccountStatus(
  db: Database,
  accountId: string,
  status: "active" | "banned",
): Promise<boolean> {
  const result = await db.prepare(`
    UPDATE accounts SET status = ?2, updated_at = ?3
    WHERE id = ?1 AND status != 'deleted' AND status != ?2
  `).bind(accountId, status, new Date().toISOString()).run();
  return (result.meta.changes ?? 0) === 1;
}

export async function setDeviceStatus(
  db: Database,
  deviceId: string,
  status: "active" | "banned" | "revoked",
): Promise<boolean> {
  const result = await db.prepare(`
    UPDATE devices SET status = ?2, updated_at = ?3
    WHERE id = ?1 AND (
      (?2 = 'active' AND status = 'banned')
      OR (?2 = 'banned' AND status = 'active')
      OR (?2 = 'revoked' AND status != 'revoked')
    )
  `).bind(deviceId, status, new Date().toISOString()).run();
  return (result.meta.changes ?? 0) === 1;
}

export interface AuditListFilters {
  from: string;
  to: string;
  action: string;
  requestId: string;
  targetId: string;
}

export async function listAuditLogsPage(
  db: Database,
  page: number,
  pageSize: number,
  filters: AuditListFilters,
): Promise<AdminListPage> {
  const where = `
    WHERE (?1 = '' OR created_at >= ?1)
      AND (?2 = '' OR created_at <= ?2)
      AND (?3 = '' OR action = ?3)
      AND (?4 = '' OR instr(lower(COALESCE(request_id, '')), lower(?4)) > 0)
      AND (?5 = '' OR instr(lower(COALESCE(target_id, '')), lower(?5)) > 0)
  `;
  const bindings = [filters.from, filters.to, filters.action, filters.requestId, filters.targetId];
  const count = await db.prepare(`SELECT COUNT(*) AS total FROM admin_audit_logs ${where}`)
    .bind(...bindings).first<{ total: number }>();
  const total = Number(count?.total ?? 0);
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(page, totalPages);
  const result = await db.prepare(`
    SELECT id, action, target_type AS targetType, target_id AS targetId,
           request_id AS requestId, ip_hash AS ipHash, ip_address AS ipAddress,
           detail_json AS detailJson, created_at AS createdAt
    FROM admin_audit_logs ${where} ORDER BY id DESC LIMIT ?6 OFFSET ?7
  `).bind(...bindings, pageSize, (safePage - 1) * pageSize).all<Record<string, unknown>>();
  const items = result.results.map((row) => {
    const { detailJson, ...safe } = row;
    let detail: unknown = null;
    if (typeof detailJson === "string") {
      try { detail = JSON.parse(detailJson); } catch { detail = null; }
    }
    return { ...safe, detail };
  });
  return { items, page: safePage, pageSize, total, totalPages };
}

export async function deleteAuditLogs(
  db: Database,
  from: string,
  to: string,
  all: boolean,
): Promise<number> {
  const result = all
    ? await db.prepare("DELETE FROM admin_audit_logs").run()
    : await db.prepare(`
      DELETE FROM admin_audit_logs
      WHERE (?1 = '' OR created_at >= ?1)
        AND (?2 = '' OR created_at <= ?2)
    `).bind(from, to).run();
  return Number(result.meta.changes ?? 0);
}
