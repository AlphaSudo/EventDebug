import { useQuery } from '@tanstack/react-query';
import { getAnomalies, AnomalyReport } from '../api/client';
import { parseEventTimestamp } from '../utils/time';

interface Props {
    source?: string | null;
    onSelectAggregate?: (aggregateId: string) => void;
}

function severityBadgeClass(sev: string): string {
    switch (sev) {
        case 'CRITICAL':
            return 'sev-critical';
        case 'HIGH':
            return 'sev-high';
        case 'MEDIUM':
            return 'sev-medium';
        case 'LOW':
            return 'sev-low';
        default:
            return 'sev-low';
    }
}

function severityLabel(sev: string): string {
    switch (sev) {
        case 'CRITICAL':
            return 'Critical';
        case 'HIGH':
            return 'High';
        case 'MEDIUM':
            return 'Warning';
        case 'LOW':
            return 'Info';
        default:
            return sev;
    }
}

function ShieldIcon() {
    return (
        <svg viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <defs>
                <linearGradient id="shield-grad" x1="16" y1="8" x2="48" y2="56">
                    <stop offset="0%" stopColor="#00ff88" stopOpacity="0.9" />
                    <stop offset="100%" stopColor="#00cc66" stopOpacity="0.6" />
                </linearGradient>
            </defs>
            <path
                d="M32 4 L52 14 L52 32 C52 46 32 58 32 58 C32 58 12 46 12 32 L12 14 Z"
                stroke="url(#shield-grad)"
                strokeWidth="2"
                fill="rgba(0, 255, 136, 0.06)"
            />
            <path
                d="M32 10 L48 18 L48 32 C48 43 32 53 32 53 C32 53 16 43 16 32 L16 18 Z"
                stroke="rgba(0, 255, 136, 0.2)"
                strokeWidth="1"
                fill="none"
            />
            <polyline
                points="22,32 29,40 42,24"
                stroke="#00ff88"
                strokeWidth="3"
                strokeLinecap="round"
                strokeLinejoin="round"
                fill="none"
            />
        </svg>
    );
}

function GaugeWave({ color }: { color: 'green' | 'cyan' }) {
    const bars = Array.from({ length: 12 }, (_, i) => i);
    return (
        <div className="gauge-wave">
            {bars.map(i => (
                <div
                    key={i}
                    className={`gauge-wave-bar ${color}`}
                    style={{ animationDelay: `${i * 0.12}s` }}
                />
            ))}
        </div>
    );
}

export default function AnomalyPanel({ source, onSelectAggregate }: Props) {
    const { data: anomalies, isLoading } = useQuery({
        queryKey: ['anomalies', source ?? 'default'],
        queryFn: () => getAnomalies(100, source),
        refetchInterval: 30_000,
    });

    const hasAnomalies = anomalies && anomalies.length > 0;

    return (
        <div className="card">
            <div className="card-title anomaly-card-title-row">
                <span className="anomaly-title-text">&#x26A0;&#xFE0F; Anomaly Detection</span>
                {!isLoading && hasAnomalies && (
                    <span className="anomaly-header-count" aria-label={`${anomalies!.length} anomalies`}>
                        {anomalies!.length}
                    </span>
                )}
            </div>

            {isLoading && <div className="skeleton" style={{ height: 120 }} />}

            {!isLoading && !hasAnomalies && (
                <div className="anomaly-panel-inner">
                    <div className="anomaly-shield">
                        <div className="shield-icon">
                            <ShieldIcon />
                        </div>
                        <div className="shield-text">No anomalies detected</div>
                    </div>

                    <div className="gauge-row">
                        <div className="gauge-card optimal">
                            <div className="gauge-label">Data Integrity</div>
                            <div className="gauge-value optimal">OPTIMAL</div>
                            <GaugeWave color="green" />
                        </div>
                        <div className="gauge-card baseline">
                            <div className="gauge-label">Pattern Scan</div>
                            <div className="gauge-value baseline">BASELINE</div>
                            <GaugeWave color="cyan" />
                        </div>
                        <div className="gauge-card zero">
                            <div className="gauge-label">Threat Level</div>
                            <div className="gauge-value zero">ZERO</div>
                            <GaugeWave color="green" />
                        </div>
                    </div>
                </div>
            )}

            {!isLoading && hasAnomalies && (
                <div className="anomaly-scroll-region">
                    <div className="anomaly-list-inner">
                    {anomalies!.map((a: AnomalyReport, i: number) => (
                        <details key={`${a.aggregateId}-${a.atSequence}-${i}`} className={`anomaly-card ${a.severity}`}>
                            <summary className="anomaly-card-summary">
                                <span className={`anomaly-severity-badge ${severityBadgeClass(a.severity)}`}>
                                    {severityLabel(a.severity)}
                                </span>
                                <span className="anomaly-card-title">{a.description}</span>
                                <span className="anomaly-card-chevron" aria-hidden>
                                    ▼
                                </span>
                            </summary>
                            <div className="anomaly-card-body">
                                <p className="anomaly-card-meta">
                                    <span className="anomaly-meta-label">Aggregate</span>
                                    {onSelectAggregate ? (
                                        <button
                                            type="button"
                                            className="anomaly-aggregate-link"
                                            onClick={() => onSelectAggregate(a.aggregateId)}
                                        >
                                            <code className="anomaly-meta-value">{a.aggregateId}</code>
                                        </button>
                                    ) : (
                                        <code className="anomaly-meta-value">{a.aggregateId}</code>
                                    )}
                                </p>
                                <p className="anomaly-card-meta">
                                    <span className="anomaly-meta-label">Sequence</span>
                                    <span className="anomaly-meta-value">#{a.atSequence}</span>
                                </p>
                                <p className="anomaly-card-meta">
                                    <span className="anomaly-meta-label">Event type</span>
                                    <span className="anomaly-meta-value">{a.triggeringEventType}</span>
                                </p>
                                <p className="anomaly-card-meta">
                                    <span className="anomaly-meta-label">When</span>
                                    <span className="anomaly-meta-value">
                                        {parseEventTimestamp(a.timestamp).toLocaleString()}
                                    </span>
                                </p>
                                <p className="anomaly-card-meta">
                                    <span className="anomaly-meta-label">Code</span>
                                    <code className="anomaly-meta-value">{a.code}</code>
                                </p>
                            </div>
                        </details>
                    ))}
                    </div>
                </div>
            )}
        </div>
    );
}
