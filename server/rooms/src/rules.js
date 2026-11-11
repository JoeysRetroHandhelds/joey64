import { decode } from "./code.js";

/** The checks a posted room goes through (index.js); kept apart because a Worker's main module may export only its handler. */

export const TTL_MS = 75_000;
export const MAX_ROOMS = 300;
export const MAX_PER_ADDRESS = 4;
const HEX32 = /^[0-9a-f]{32}$/;

export class Invalid extends Error {}

/**
 * The room as stored, or Invalid. `from` is the poster's address (cf-connecting-ip); "" only for a local
 * `wrangler dev` with LOCAL_TEST=1, where posts come from 127.0.0.1 through adb reverse.
 */
export function validate(b, from) {
  if (!b || typeof b !== "object" || Array.isArray(b)) throw new Invalid("not an object");
  const id = String(b.id ?? ""), secret = String(b.secret ?? "");
  if (!HEX32.test(id)) throw new Invalid("id");
  if (!HEX32.test(secret)) throw new Invalid("secret");
  const md5 = String(b.md5 ?? "").toLowerCase();
  if (!HEX32.test(md5)) throw new Invalid("md5");
  const players = b.players;
  if (!Number.isInteger(players) || players < 1 || players > 4) throw new Invalid("players");
  const code = typeof b.code === "string" ? b.code.toUpperCase().replace(/\s/g, "") : "";
  const room = decode(code);
  if (!room) throw new Invalid("code");
  if (/^\d+\.\d+\.\d+\.\d+$/.test(from) && room.ip !== from) throw new Invalid("code is not this address");
  if (from !== "" && isPrivate(room.ip)) throw new Invalid("code is a private address");
  return {
    id, secret, md5, players, code,
    name: field(b.name, 48, "name"),
    device: field(b.device, 48, "device", true),
    game: field(b.game, 80, "game"),
  };
}

function field(v, max, what, mayBeEmpty = false) {
  if (typeof v !== "string") throw new Invalid(what);
  // No control characters, no bidi overrides; spaces squeezed.
  const s = v.replace(/[\u0000-\u001f\u007f-\u009f‪-‮⁦-⁩]/g, " ").replace(/\s+/g, " ").trim();
  if (s.length > max) throw new Invalid(what + " too long");
  if (!s && !mayBeEmpty) throw new Invalid(what);
  return s;
}

/** Addresses nobody outside the host's own network can reach (a public room must be reachable). */
export function isPrivate(ip) {
  const [a, b] = ip.split(".").map(Number);
  return a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b < 128) ||
    (a === 169 && b === 254) || (a === 172 && b >= 16 && b < 32) || (a === 192 && b === 168);
}
