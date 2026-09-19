/// <reference types="vite/client" />

/**
 * Build-time environment. Only values that are safe to ship in a public bundle
 * belong here -- the Supabase anon key qualifies (Row Level Security is what
 * protects the data), an AI provider key never would.
 */
interface ImportMetaEnv {
  readonly VITE_SUPABASE_URL?: string;
  readonly VITE_SUPABASE_ANON_KEY?: string;
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
