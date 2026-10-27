export function clock(ms: number | null | undefined): string {
  if (!ms) return "—";
  return new Date(ms).toLocaleTimeString("en-GB", {
    hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false, timeZone: "UTC",
  });
}

export function duration(fromMs: number, toMs: number): string {
  const s = Math.max(0, Math.round((toMs - fromMs) / 1000));
  if (s < 90) return `${s} s`;
  const m = Math.round(s / 60);
  return m < 90 ? `${m} min` : `${Math.round(m / 60)} h`;
}

export const pct = (part: number, whole: number) => (whole === 0 ? 0 : Math.round((100 * part) / whole));

/** "provider/biz-internet/us-west" → "biz-internet · us-west" for compact labels. */
export function shortElement(id: string): string {
  const [kind, ...rest] = id.split("/");
  if (kind === "change") return `rollout ${rest[0].slice(0, 8)}${rest[1] ? " · " + rest[1] : ""}`;
  if (kind === "site") return rest.length === 1 ? `site ${rest[0]}` : `site ${rest[0]} · ${rest[1]}`;
  return rest.join(" · ");
}
