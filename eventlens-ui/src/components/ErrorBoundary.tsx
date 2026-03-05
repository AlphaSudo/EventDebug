import { Component, ErrorInfo, ReactNode } from 'react';

interface Props {
    children: ReactNode;
}

interface State {
    hasError: boolean;
}

export default class ErrorBoundary extends Component<Props, State> {
    state: State = { hasError: false };

    static getDerivedStateFromError(): State {
        return { hasError: true };
    }

    componentDidCatch(error: Error, info: ErrorInfo) {
        // eslint-disable-next-line no-console
        console.error('UI error boundary caught:', error, info);
    }

    render() {
        if (this.state.hasError) {
            return (
                <div className="app">
                    <main className="app-main">
                        <div className="card">
                            <div className="card-title">Something went wrong</div>
                            <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>
                                The UI hit an unexpected error. Try refreshing the page. If the problem
                                persists, check the browser console and server logs.
                            </p>
                        </div>
                    </main>
                </div>
            );
        }
        return this.props.children;
    }
}

