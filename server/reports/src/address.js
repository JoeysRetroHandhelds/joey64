// Where a post comes from, as the limits count it. Nothing here is stored as it is: the day's counts keep only
// scramble()'s hash, made with a random key that is new each day and deleted with that day's counts (store.js).

/** An IPv4 address's /24 or an IPv6 address's /64: what one poster can easily hold many addresses of. */
export function blockOf(ip) {
  const v4 = ipv4(ip);
  if (v4) return v4.split(".").slice(0, 3).join(".") + ".0/24";
  const g = ipv6Groups(ip);
  return g ? g.slice(0, 4).join(":") + "::/64" : ip;
}

/**
 * The rate limiter's key: an IPv4 address itself (homes behind one address share it), an IPv6 address's /64 (one
 * subscriber gets a whole /64, so the full address gave a poster billions of keys; code audit 3, M23).
 */
export function limiterKeyOf(ip) {
  const v4 = ipv4(ip);
  if (v4) return v4;
  const g = ipv6Groups(ip);
  return g ? g.slice(0, 4).join(":") + "::/64" : ip;
}

export function ipv4(ip) {
  const m = /^(?:::ffff:)?(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/i.exec(ip);
  return m ? m[1] : null;
}

/** An IPv6 address's 8 groups, lower case without leading zeros, or null if it isn't one. */
function ipv6Groups(ip) {
  if (!ip.includes(":")) return null;
  const halves = ip.split("::");
  if (halves.length > 2) return null;
  const part = (s) => (s ? s.split(":") : []);
  const head = part(halves[0]);
  const tail = halves.length === 2 ? part(halves[1]) : [];
  const fill = halves.length === 2 ? 8 - head.length - tail.length : 0;
  if (fill < 0) return null;
  const groups = [...head, ...Array(fill).fill("0"), ...tail];
  if (groups.length !== 8 || !groups.every((x) => /^[0-9a-f]{1,4}$/i.test(x))) return null;
  return groups.map((x) => parseInt(x, 16).toString(16));
}

const hex = (bytes) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");

/** A new random key for a day's counts. */
export function newSalt() {
  return hex(crypto.getRandomValues(new Uint8Array(16)));
}

/** [block] hashed with the day's [salt]: the name its count goes under, which can't be read back once the salt goes. */
export async function scramble(salt, block) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(salt + "|" + block));
  return hex(new Uint8Array(d).slice(0, 16));
}
