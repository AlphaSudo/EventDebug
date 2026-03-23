/// <reference types="vite/client" />

interface ImportMetaEnv {
    readonly VITE_EVENTLENS_DEMO?: string;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
