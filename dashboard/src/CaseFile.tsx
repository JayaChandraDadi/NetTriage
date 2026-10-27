import { useState } from "react";
import { api, type Action, type ActionRecord, type Incident } from "./api";
import { usePoll } from "./usePoll";
import { clock, duration, shortElement } from "./format";

const CONFIDENCE: Record<Incident["confidence"], string> = {
  HIGH: "High confidence",
  MEDIUM: "Medium confidence",
  LOW: "Low confidence",
};

/** Everything about one incident, laid out like a case file: finding, exhibits, ruled out, actions. */
export function CaseFile({ incident: i, approver }: { incident: Incident; approver: string }) {
  const log = usePoll(api.actions, 2000);
  const mine = (log.data ?? []).filter((r) => r.incidentId === i.id);
  const maxMargin = Math.max(10, ...i.alternatives.map((a) => Math.min(a.margin, 80)));

  return (
    <article className="case">
      <header className="case-head">
        <p className="kicker">
          {i.id} · {i.state === "OPEN" ? `open for ${duration(i.onsetAt, i.lastSeenAt)}` : `resolved at ${clock(i.resolvedAt)} UTC`}
        </p>
        <h2>{i.headline}</h2>
        <dl className="facts">
          <div>
            <dt>Root cause</dt>
            <dd className="mono">{i.rootCause}</dd>
          </div>
          <div>
            <dt>{CONFIDENCE[i.confidence]}</dt>
            <dd>{Math.round(i.margin * 10) / 10} nats ahead of the next explanation</dd>
          </div>
          <div>
            <dt>First flagged</dt>
            <dd>{clock(i.onsetAt)} UTC</dd>
          </div>
          <div>
            <dt>Scope</dt>
            <dd>
              {i.state === "OPEN" ? `${i.degradedNow} of ${i.coveredNow} tunnels now` : `peak ${i.peakDegraded} tunnels`};{" "}
              {i.sitesAffected} {i.sitesAffected === 1 ? "site" : "sites"} {i.state === "OPEN" ? "so far" : "in all"}
            </dd>
          </div>
        </dl>
        {i.history.length > 1 && (
          <p className="history">
            The explanation sharpened as data came in:{" "}
            {i.history.map((h, k) => (
              <span key={k}>
                {k > 0 && " → "}
                <span className="mono">{shortElement(h.rootCause)}</span> ({clock(h.at)})
              </span>
            ))}
          </p>
        )}
      </header>

      <section className="exhibits" aria-labelledby="ev-title">
        <h3 id="ev-title">Evidence</h3>
        <ol>
          {i.evidence.map((e) => (
            <li key={e.id}>
              <span className="eid">{e.id}</span>
              <span>{e.text}</span>
            </li>
          ))}
        </ol>
      </section>

      {i.alternatives.length > 0 && (
        <section className="ruled" aria-labelledby="alt-title">
          <h3 id="alt-title">Ruled out</h3>
          <p className="hint">How much worse each alternative explains the same data, in log-likelihood (nats). Under 4 is too close to call.</p>
          <ul>
            {i.alternatives.map((a) => (
              <li key={a.element}>
                <div className="alt-head">
                  <span className="alt-name">{a.description}</span>
                  <span className="mono alt-margin">+{a.margin >= 80 ? "80+" : a.margin.toFixed(1)}</span>
                </div>
                <div className="bar" aria-hidden>
                  <span style={{ width: `${(Math.min(a.margin, 80) / maxMargin) * 100}%` }} className={a.margin < 4 ? "close" : ""} />
                </div>
                <p className="alt-reason">{a.reason}</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="actions" aria-labelledby="act-title">
        <h3 id="act-title">What to do</h3>
        <ul>
          {i.actions.map((a) => (
            <ActionRow key={a.id} incident={i} action={a} approver={approver}
                       records={mine.filter((r) => r.actionId === a.id)} />
          ))}
        </ul>
      </section>
    </article>
  );
}

function ActionRow({ incident, action, approver, records }: {
  incident: Incident; action: Action; approver: string; records: ActionRecord[];
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const latest = records[0];
  const done = latest && latest.state !== "PENDING_APPROVAL" && latest.state !== "FAILED";
  const pending = latest?.state === "PENDING_APPROVAL";

  const approve = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.approve(incident.id, action.id, approver);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className={`action ${action.kind === "ROLLBACK_DEPLOYMENT" ? "exec" : ""}`}>
      <div className="action-body">
        <p className="action-title">
          <span className="eid">{action.id}</span> {action.title}
        </p>
        <p className="action-detail">{action.detail}</p>
        {pending && (
          <p className="request">
            Requested by {latest.requestedBy}: “{latest.justification}”
          </p>
        )}
        {latest && latest.state !== "PENDING_APPROVAL" && (
          <p className={`outcome ${latest.state.toLowerCase()}`}>
            {latest.state.toLowerCase()} by {latest.decidedBy} at {clock(latest.decidedAt)}: {latest.outcome}
          </p>
        )}
        {error && <p className="outcome failed">{error}</p>}
      </div>
      {!done && (
        <button type="button" className="approve" disabled={busy || !approver.trim()} onClick={approve}
                title={approver.trim() ? undefined : "Enter your name at the top first"}>
          {action.kind === "ROLLBACK_DEPLOYMENT" ? "Approve rollback" : "Acknowledge"}
        </button>
      )}
    </li>
  );
}
