import { businessPeriodKeys } from "../../foundation/time/clock";
import {
  readCumulativeUsage,
  readDailyActiveAccounts,
  readDailyNewAccounts,
  readDailyNewDevices,
  readDailyUsage,
  readDashboardSnapshot,
  readMonthlyActiveAccounts,
  readMonthlyNewAccounts,
  readMonthlyUsage,
  readPlatformDistribution,
} from "./repository";

function shanghaiStartIso(date: Date, monthStart = false): string {
  const { day } = businessPeriodKeys(date);
  const [year, month, dateOfMonth] = day.split("-").map(Number) as [number, number, number];
  return new Date(Date.UTC(
    year,
    month - 1,
    monthStart ? 1 : dateOfMonth,
    -8,
  )).toISOString();
}

function shiftedDayKey(date: Date, deltaDays: number): string {
  const shifted = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  shifted.setUTCDate(shifted.getUTCDate() + deltaDays);
  return shifted.toISOString().slice(0, 10);
}

function shiftedMonthKey(date: Date, deltaMonths: number): string {
  const shifted = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  shifted.setUTCDate(1);
  shifted.setUTCMonth(shifted.getUTCMonth() + deltaMonths);
  return shifted.toISOString().slice(0, 7);
}

export async function dashboard(db: Database, now = new Date()): Promise<Record<string, unknown>> {
  const { day, month } = businessPeriodKeys(now);
  const snapshot = await readDashboardSnapshot(
    db,
    day,
    month,
    shanghaiStartIso(now),
    shanghaiStartIso(now, true),
  );
  const dailyRows = await readDailyUsage(db, shiftedDayKey(now, -29));
  const dailyByKey = new Map(dailyRows.map((row) => [row.day, row]));
  const daily = Array.from({ length: 30 }, (_, index) => {
    const dayKey = shiftedDayKey(now, index - 29);
    return dailyByKey.get(dayKey) ?? { day: dayKey, uploadBytes: 0, downloadBytes: 0 };
  });
  return {
    accounts: {
      total: snapshot.accountsTotal,
      activeToday: snapshot.activeAccountsToday,
      activeMonth: snapshot.activeAccountsMonth,
    },
    devices: {
      total: snapshot.devicesTotal,
      active: snapshot.devicesActive,
      revoked: snapshot.devicesRevoked,
      banned: snapshot.devicesBanned,
    },
    traffic: {
      today: {
        uploadBytes: snapshot.todayUploadBytes,
        downloadBytes: snapshot.todayDownloadBytes,
        totalBytes: snapshot.todayUploadBytes + snapshot.todayDownloadBytes,
      },
      month: {
        uploadBytes: snapshot.monthUploadBytes,
        downloadBytes: snapshot.monthDownloadBytes,
        totalBytes: snapshot.monthUploadBytes + snapshot.monthDownloadBytes,
      },
    },
    daily,
    generatedAt: now.toISOString(),
  };
}

export async function analytics(
  db: Database,
  days: number,
  months: number,
  now = new Date(),
): Promise<Record<string, unknown>> {
  const fromDay = shiftedDayKey(now, -(days - 1));
  const fromMonth = shiftedMonthKey(now, -(months - 1));
  const [dailyUsage, dailyActive, dailyNew, dailyNewDevices, monthlyUsage, monthlyActive, monthlyNew, platforms, cumulative] = await Promise.all([
    readDailyUsage(db, fromDay),
    readDailyActiveAccounts(db, fromDay),
    readDailyNewAccounts(db, fromDay),
    readDailyNewDevices(db, fromDay),
    readMonthlyUsage(db, fromMonth),
    readMonthlyActiveAccounts(db, fromMonth),
    readMonthlyNewAccounts(db, fromMonth),
    readPlatformDistribution(db),
    readCumulativeUsage(db),
  ]);
  const dailyUsageMap = new Map(dailyUsage.map((row) => [row.day, row]));
  const dailyActiveMap = new Map(dailyActive.map((row) => [row.day, row.activeAccounts]));
  const dailyNewMap = new Map(dailyNew.map((row) => [row.day, row.newAccounts]));
  const dailyNewDevicesMap = new Map(dailyNewDevices.map((row) => [row.day, row.newDevices]));
  const daily = Array.from({ length: days }, (_, index) => {
    const day = shiftedDayKey(now, index - (days - 1));
    const usage = dailyUsageMap.get(day);
    const uploadBytes = Number(usage?.uploadBytes ?? 0);
    const downloadBytes = Number(usage?.downloadBytes ?? 0);
    return {
      day,
      activeAccounts: Number(dailyActiveMap.get(day) ?? 0),
      newAccounts: Number(dailyNewMap.get(day) ?? 0),
      newDevices: Number(dailyNewDevicesMap.get(day) ?? 0),
      uploadBytes,
      downloadBytes,
      totalBytes: uploadBytes + downloadBytes,
    };
  });
  const monthlyUsageMap = new Map(monthlyUsage.map((row) => [row.month, row]));
  const monthlyActiveMap = new Map(monthlyActive.map((row) => [row.month, row.activeAccounts]));
  const monthlyNewMap = new Map(monthlyNew.map((row) => [row.month, row.newAccounts]));
  const monthly = Array.from({ length: months }, (_, index) => {
    const month = shiftedMonthKey(now, index - (months - 1));
    const usage = monthlyUsageMap.get(month);
    const uploadBytes = Number(usage?.uploadBytes ?? 0);
    const downloadBytes = Number(usage?.downloadBytes ?? 0);
    return {
      month,
      activeAccounts: Number(monthlyActiveMap.get(month) ?? 0),
      newAccounts: Number(monthlyNewMap.get(month) ?? 0),
      uploadBytes,
      downloadBytes,
      totalBytes: uploadBytes + downloadBytes,
    };
  });
  return {
    daily,
    monthly,
    platforms,
    cumulative: {
      ...cumulative,
      totalBytes: cumulative.uploadBytes + cumulative.downloadBytes,
    },
    generatedAt: now.toISOString(),
  };
}
