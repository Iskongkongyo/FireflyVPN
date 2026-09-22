import { describe, expect, it } from "vitest";

import { businessPeriodKeys } from "../../src/foundation/time/clock";

describe("business period keys", () => {
  it("uses Asia/Shanghai at the UTC day boundary", () => {
    expect(businessPeriodKeys(new Date("2026-09-05T15:59:59.999Z"))).toEqual({
      day: "2026-09-05",
      month: "2026-09",
    });
    expect(businessPeriodKeys(new Date("2026-09-05T16:00:00.000Z"))).toEqual({
      day: "2026-09-06",
      month: "2026-09",
    });
  });
});
