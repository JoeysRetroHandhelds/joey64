/**
 * The room code, as the app's netplay/RoomCode.kt makes it: the host's IPv4 address and which of the room ports
 * 45000-45007 it uses, in seven characters of five bits, then a check character. Keep the two in step.
 */
export const CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const BASE_PORT = 45000;

const check = (data) => data.reduce((s, d, i) => s + d * (2 * i + 1), 0) & 31;

/** { ip: "a.b.c.d", port } for a good code, else null. */
export function decode(code) {
  if (typeof code !== "string") return null;
  const c = code.toUpperCase().replace(/\s/g, "");
  if (c.length !== 8) return null;
  const vals = [...c].map((ch) => CHARS.indexOf(ch));
  if (vals.some((v) => v < 0)) return null;
  const data = vals.slice(0, 7);
  if (check(data) !== vals[7]) return null;
  let v = 0n;
  for (const d of data) v = (v << 5n) | BigInt(d);
  const port = BASE_PORT + Number(v & 7n);
  const ip = Number(v >> 3n);
  return { ip: [ip >>> 24, (ip >>> 16) & 255, (ip >>> 8) & 255, ip & 255].join("."), port };
}

/** The code for an IPv4 address and room port (tests). */
export function encode(ip, port) {
  const parts = ip.split(".").map(Number);
  let v = 0n;
  for (const p of parts) v = (v << 8n) | BigInt(p);
  v = (v << 3n) | BigInt(port - BASE_PORT);
  const data = [];
  for (let i = 0; i < 7; i++) data.push(Number((v >> BigInt(5 * (6 - i))) & 31n));
  return data.map((d) => CHARS[d]).join("") + CHARS[check(data)];
}
