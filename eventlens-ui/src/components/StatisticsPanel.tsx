import { useState } from 'react';
import { useStatistics } from '../hooks/useStatistics';

export default function StatisticsPanel({ source }: { source?: string | null }) {
    const [windowHours, setWindowHours] = useState(24);
    const { data, isLoading } = useStatistics(source, 1, windowHours);

    return (
        <section className="card statistics-panel" role="region" aria-label="Statistics panel" aria-busy={isLoading}>
            <div className="card-title">Statistics {source ? `- ${source}` : ''}</div>
            <div className="statistics-toolbar">
                {[6, 24, 72].map(hours => (
                    <button
                        key={hours}
                        type="button"
                        className={`filter-chip ${windowHours === hours ? 'active' : ''}`}
                        onClick={() => setWindowHours(hours)}
                    >
                        {hours === 72 ? '3d' : `${hours}h`}
                    </button>
                ))}
            </div>
            {isLoading && <div className="skeleton" style={{ height: 140 }} />}
            {!isLoading && data && !data.available && (
                <p style={{ color: 'var(--text-muted)' }}>{data.message ?? 'Statistics not available.'}</p>
            )}
            {!isLoading && data?.available && (
                <>
                    <div className="stats-kpis">
                        <div className="stat-card"><strong>{data.totalEvents}</strong><span>Total events</span></div>
                        <div className="stat-card"><strong>{data.distinctAggregates}</strong><span>Aggregates</span></div>
                        <div className="stat-card"><strong>{data.eventTypes.length}</strong><span>Event types</span></div>
                    </div>
                    <div className="stats-chart">
                        {data.throughput.map(point => (
                            <div key={point.bucket} className="stats-bar-row">
                                <span>{point.bucket.slice(11, 16)}</span>
                                <div className="stats-bar-track"><div className="stats-bar-fill" style={{ width: `${Math.max(8, (point.count / Math.max(...data.throughput.map(p => p.count), 1)) * 100)}%` }} /></div>
                                <strong>{point.count}</strong>
                            </div>
                        ))}
                    </div>
                    <div className="stats-distribution">
                        <div>
                            <h4>Event Types</h4>
                            {data.eventTypes.map(item => <div key={item.type} className="stats-list-row"><span>{item.type}</span><strong>{item.count}</strong></div>)}
                        </div>
                        <div>
                            <h4>Aggregate Types</h4>
                            {data.aggregateTypes.map(item => <div key={item.type} className="stats-list-row"><span>{item.type}</span><strong>{item.count}</strong></div>)}
                        </div>
                    </div>
                </>
            )}
        </section>
    );
}
