export interface Clock {
  now(): Date;
  unixSeconds(): number;
}

export const systemClock: Clock = {
  now: () => new Date(),
  unixSeconds: () => Math.floor(Date.now() / 1000),
};

export const BUSINESS_TIME_ZONE = "Asia/Shanghai" as const;

const businessDateFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: BUSINESS_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

export function businessPeriodKeys(date: Date): { day: string; month: string } {
  if (Number.isNaN(date.getTime())) throw new TypeError("Invalid date");
  const parts = businessDateFormatter.formatToParts(date);
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  const day = parts.find((part) => part.type === "day")?.value;
  if (!year || !month || !day) throw new TypeError("Unable to format business date");
  return { day: `${year}-${month}-${day}`, month: `${year}-${month}` };
}
