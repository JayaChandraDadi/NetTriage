import type { Incident } from "./api";
import { clock, shortElement } from "./format";

function Spark({ points }: { points: Incident["timeline"] }) {
  if (points.length < 2) return null;
  const tail = points.slice(-60);
  const max = Math.max(1, ...tail.map((p) => p.degraded));
  const d = tail.map((p, i) => `${(i / (tail.length - 1)) * 100},${20 - (p.degraded / max) * 18}`).join(" ");
  return (
    <svg className="spark" viewBox="0 0 100 20" preserveAspectRatio="none" aria-hidden>
      <polyline points={d} />
    </svg>
  );
}

export function IncidentList({ incidents, selected, onSelect }: {
  incidents: Incident[]; selected: string | null; onSelect: (id: string) => void;
}) {
  const open = incidents.filter((i) => i.state === "OPEN");
  const resolved = incidents.filter((i) => i.state === "RESOLVED");
  const item = (i: Incident) => (
    <li key={i.id}>
      <button type="button" className={`inc ${i.id === selected ? "is-selected" : ""} ${i.state.toLowerCase()}`}
              onClick={() => onSelect(i.id)} aria-current={i.id === selected}>
        <span className="inc-top">
          <span className="inc-id">{i.id}</span>
          <span className={`conf ${i.confidence.toLowerCase()}`}>{i.confidence.toLowerCase()}</span>
        </span>
        <span className="inc-root">{shortElement(i.rootCause)}</span>
        <span className="inc-meta">
          {i.state === "OPEN"
            ? `${i.degradedNow} tunnels · ${i.sitesAffected} sites · since ${clock(i.onsetAt)}`
            : `resolved ${clock(i.resolvedAt)} · peak ${i.peakDegraded} tunnels`}
        </span>
        <Spark points={i.timeline} />
      </button>
    </li>
  );
  return (
    <section className="panel incidents" aria-labelledby="inc-title">
      <h2 id="inc-title">Incidents</h2>
      {incidents.length === 0 && <p className="hint">None yet.</p>}
      <ol className="inc-list">{open.map(item)}</ol>
      {resolved.length > 0 && (
        <>
          <h3 className="sub">Resolved</h3>
          <ol className="inc-list">{resolved.slice(0, 12).map(item)}</ol>
        </>
      )}
    </section>
  );
}
