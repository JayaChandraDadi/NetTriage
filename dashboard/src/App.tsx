import { useEffect, useState } from "react";
import { api } from "./api";
import { usePoll } from "./usePoll";
import { FleetMap } from "./FleetMap";
import { IncidentList } from "./IncidentList";
import { CaseFile } from "./CaseFile";

function readName(): string {
  try {
    return localStorage.getItem("nettriage.approver") ?? "";
  } catch {
    return "";
  }
}

export function App() {
  const status = usePoll(api.status, 3000);
  const incidents = usePoll(api.incidents, 2000);
  const grid = usePoll(api.grid, 3000);
  const [selected, setSelected] = useState<string | null>(null);
  const [approver, setApprover] = useState(readName);

  useEffect(() => {
    try {
      localStorage.setItem("nettriage.approver", approver);
    } catch {
      /* private window: keep it in memory */
    }
  }, [approver]);

  const list = incidents.data ?? [];
  const open = list.filter((i) => i.state === "OPEN");
  const current = list.find((i) => i.id === selected) ?? open[0] ?? list[0];
  const e = status.data?.engine;

  return (
    <div className="page">
      <header className="masthead">
        <div className="brand">
          <h1>NetTriage</h1>
          <p className="tagline">Root causes for the SD-WAN overlay, with the evidence</p>
        </div>
        <label className="approver">
          <span>Approving as</span>
          <input value={approver} placeholder="your name" onChange={(ev) => setApprover(ev.target.value)} />
        </label>
      </header>

      <p className="lede">
        {status.error && !e ? (
          <>Can't reach the NetTriage service ({status.error}). This page reconnects on its own.</>
        ) : !e || e.tunnelsKnown === 0 ? (
          <>Waiting for telemetry. Baselines form after a minute of traffic.</>
        ) : (
          <>
            {open.length === 0 ? "Nothing open. " : `${open.length} open ${open.length === 1 ? "incident" : "incidents"}. `}
            {(e.observed - e.degraded).toLocaleString()} of {e.observed.toLocaleString()} tunnels healthy
            {e.unexplained > 0 && <>, {e.unexplained} degraded with no shared cause (background noise {e.backgroundNoisePct}%)</>}.
          </>
        )}
      </p>

      <main className="layout">
        <aside className="side">
          <FleetMap grid={grid.data} />
          <IncidentList incidents={list} selected={current?.id ?? null} onSelect={setSelected} />
        </aside>
        <section className="main" aria-live="polite">
          {current ? (
            <CaseFile incident={current} approver={approver} />
          ) : (
            <div className="empty">
              <h2>No incidents yet</h2>
              <p>
                When tunnels degrade, NetTriage looks for the smallest set of shared causes that explains which tunnels
                broke and which didn't, and opens an incident here with the evidence.
              </p>
            </div>
          )}
        </section>
      </main>

      {e && (
        <footer className="foot">
          {e.windowsProcessed.toLocaleString()} windows diagnosed · {e.meanProcessMs} ms average per window ·{" "}
          {e.tunnelsKnown.toLocaleString()} tunnels known · times in UTC
          {e.devicesKnown > 0 && <> · {e.devicesKnown} routers from EdgeFleet</>}
        </footer>
      )}
    </div>
  );
}
