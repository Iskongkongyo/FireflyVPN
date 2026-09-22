import { AppError } from "../../foundation/http/errors";
import { getAccountDevices } from "../device-registry/service";
import type { AuthenticatedDevice } from "../secure-delivery/authenticator";
import { deleteAnonymousAccount, readAnonymousAccount } from "./repository";

export async function getAnonymousAccount(
  db: Database,
  authenticated: AuthenticatedDevice,
): Promise<Record<string, unknown>> {
  const account = await readAnonymousAccount(db, authenticated.accountId);
  if (account === null) throw new AppError("not_found", 404);
  return {
    ...account,
    devices: await getAccountDevices(db, authenticated.accountId),
  };
}

export async function removeAnonymousAccount(
  db: Database,
  authenticated: AuthenticatedDevice,
  now = new Date(),
): Promise<{ accountId: string; status: "deleted"; revokedDevices: number }> {
  const revokedDevices = await deleteAnonymousAccount(
    db,
    authenticated.accountId,
    now.toISOString(),
  );
  if (revokedDevices < 0) throw new AppError("not_found", 404);
  return { accountId: authenticated.accountId, status: "deleted", revokedDevices };
}
