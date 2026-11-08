const zone = "Asia/Shanghai";
function parts(d: Date) {
  return Object.fromEntries(
    new Intl.DateTimeFormat("en-GB", {
      timeZone: zone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(d)
      .map((p) => [p.type, p.value]),
  );
}
export function relativeDate(
  value: unknown,
  full = true,
  now = new Date(),
): string {
  if (!value) return "—";
  const d = new Date(String(value));
  if (Number.isNaN(d.getTime())) return String(value);
  const p = parts(d),
    n = parts(now);
  const day = (x: Record<string, string>) =>
    Date.UTC(Number(x.year), Number(x.month) - 1, Number(x.day));
  const ago = Math.round((day(n) - day(p)) / 86400000);
  if (ago === 0) return `${p.hour}:${p.minute}`;
  if (ago === 1) return "昨天";
  if (ago === 2) return "前天";
  if (ago >= 3 && ago <= 7) return `${ago}天前`;
  const yearAgo = new Date(day(n));
  yearAgo.setUTCFullYear(yearAgo.getUTCFullYear() - 1);
  const year =
    day(p) < yearAgo.getTime() || Number(p.year) > Number(n.year)
      ? `${p.year}/`
      : "";
  return `${year}${p.month}/${p.day}${full ? ` ${p.hour}:${p.minute}` : ""}`;
}
