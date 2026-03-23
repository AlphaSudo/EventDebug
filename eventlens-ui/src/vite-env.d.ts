/// <reference types="vite/client" />

interface ImportMetaEnv {
    readonly VITE_EVENTLENS_DEMO?: string;
    readonly VITE_EVENTLENS_DEMO_ALLOW?: string;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
