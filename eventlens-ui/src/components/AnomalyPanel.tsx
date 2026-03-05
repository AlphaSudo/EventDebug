import { useQuery } from '@tanstack/react-query';
import { getAnomalies, AnomalyReport } from '../api/client';

const icons: Record<string, string> = {
    CRITICAL: '🔴',
    HIGH: '🟠',
    MEDIUM: '🟡',
    LOW: '🔵',
};

export default function AnomalyPanel() {
    const { data: anomalies, isLoading } = useQuery({
        queryKey: ['anomalies'],
        queryFn: () => getAnomalies(),
        refetchInterval: 30_000,
    });

    return (
        <div className="card">
            <div className="card-title">⚠️ Anomaly Detection</div>

            {isLoading && <div className="skeleton" style={{ height: 80 }} />}

            {!isLoading && anomalies?.length === 0 && (
                <div className="no-anomalies">
                    <span>✅</span>
                    <span>No anomalies detected</span>
                </div>
            )}

            {!isLoading && anomalies && anomalies.length > 0 && (
                <div className="anomaly-list">
                    {anomalies.map((a: AnomalyReport, i: number) => (
                        <div key={i} className={`anomaly-item ${a.severity}`}>
                            <span className="anomaly-icon">{icons[a.severity] ?? '⚪'}</span>
                            <div>
                                <div className="anomaly-desc">{a.description}</div>
                                <div className="anomaly-sub">
                                    {a.aggregateId} · event #{a.atSequence} · {a.triggeringEventType}
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
