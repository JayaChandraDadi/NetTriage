import { Fragment } from "react";
import type { Grid } from "./api";
import { pct } from "./format";

/** Degraded share by region and transport, then by hub and transport. */
export function FleetMap({ grid }: { grid: Grid | undefined }) {
  if (!grid || grid.regions.length === 0) return null;
  const cell = (region: string, color: string) => grid.cells.find((c) => c.region === region && c.color === color);
  const hubs = [...new Set(grid.hubs.map((h) => h.hub))];
  const hubCell = (hub: string, color: string) => grid.hubs.find((c) => c.hub === hub && c.color === color);

  const td = (c: { observed: number; degraded: number } | undefined, label: string) => {
    if (!c || c.observed === 0) return <td className="cell none" aria-label={`${label}: no tunnels`}>·</td>;
    const share = pct(c.degraded, c.observed);
    const level = c.degraded === 0 ? 0 : share < 10 ? 1 : share < 50 ? 2 : 3;
    return (
      <td className={`cell l${level}`} aria-label={`${label}: ${c.degraded} of ${c.observed} degraded`}>
        <span className="n">{c.degraded}</span>
        <span className="of">/{c.observed}</span>
      </td>
    );
  };

  return (
    <section className="panel map" aria-labelledby="map-title">
      <h2 id="map-title">Where it hurts</h2>
      <p className="hint">Degraded tunnels out of those reporting</p>
      <table>
        <thead>
          <tr>
            <th />
            {grid.colors.map((c) => (
              <th key={c} scope="col">{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {grid.regions.map((r) => (
            <tr key={r}>
              <th scope="row">{r}</th>
              {grid.colors.map((c) => <Fragment key={c}>{td(cell(r, c), `${c} in ${r}`)}</Fragment>)}
            </tr>
          ))}
          {hubs.map((h) => (
            <tr key={h} className="hubrow">
              <th scope="row">to {h}</th>
              {grid.colors.map((c) => <Fragment key={c}>{td(hubCell(h, c), `${c} to hub ${h}`)}</Fragment>)}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
