/** The page half of the Android share-to-assistant flow.
 *
 * The service worker parks a shared conversation in a cache entry; this reads
 * it once and deletes it. Read-once is the point: the text is on its way into
 * the paste box, and a conversation left sitting in a cache is a conversation
 * this app stored, which it promises not to do.
 */

const SHARE_CACHE = "vibe-share";
const SHARE_KEY = "/__shared-conversation";

/** Returns text shared from another app, and removes it. Null if there is none. */
export async function takeSharedText(): Promise<string | null> {
  if (typeof caches === "undefined") return null;
  try {
    const cache = await caches.open(SHARE_CACHE);
    const hit = await cache.match(SHARE_KEY);
    if (!hit) return null;
    await cache.delete(SHARE_KEY);
    const text = (await hit.text()).trim();
    return text || null;
  } catch {
    // Private windows and blocked site data both land here. A share that
    // cannot be read is a share that did not happen.
    return null;
  }
}

/** Strips the ?shared= marker so a reload is not treated as a second share. */
export function clearShareMarker(): void {
  try {
    const url = new URL(window.location.href);
    if (!url.searchParams.has("shared")) return;
    url.searchParams.delete("shared");
    window.history.replaceState({}, "", url.pathname + url.search + url.hash);
  } catch {
    /* history is not essential */
  }
}

/** Registers the worker that receives shares. Needs a secure context. */
export function registerShareWorker(): void {
  if (!("serviceWorker" in navigator) || !window.isSecureContext) return;
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => {
      // Nothing to do: without the worker the app still works, it just is not
      // a share target.
    });
  });
}
