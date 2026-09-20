/** The page half of sharing, in both directions.
 *
 * Coming in: the service worker parks a shared conversation in a cache entry
 * and this reads it once, then deletes it. Read-once is the point -- the text
 * is on its way into the paste box, and a conversation left sitting in a cache
 * is a conversation this app stored, which it promises not to do.
 *
 * Going out: a reply he picked is handed to the OS share sheet. That is the
 * only route there is. Neither WhatsApp nor Instagram exposes an API for
 * personal messages, so nothing can put a message in a chat except him, and
 * the honest version of "integration" is making that one tap instead of a
 * copy, a task switch and a paste.
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

/** What sending a reply onward will actually do on this device. */
export type SendOutcome = "shared" | "copied" | "cancelled" | "failed";

/** True when the OS share sheet can take text from here. */
export function canShareReply(): boolean {
  if (typeof navigator === "undefined" || typeof navigator.share !== "function") {
    return false;
  }
  // canShare is the only way to know text is an accepted payload; where it is
  // missing, share() exists and text is the one thing every implementation
  // takes, so assume yes rather than hiding the button.
  if (typeof navigator.canShare === "function") {
    try {
      return navigator.canShare({ text: "probe" });
    } catch {
      return false;
    }
  }
  return true;
}

/** Hands a reply to the share sheet, falling back to the clipboard.
 *
 * Dismissing the share sheet is reported separately from a failure. They look
 * the same to the caller otherwise, and changing his mind about sending a
 * message is not an error worth showing him a warning about.
 */
export async function sendReply(text: string): Promise<SendOutcome> {
  if (canShareReply()) {
    try {
      await navigator.share({ text });
      return "shared";
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") return "cancelled";
      // Anything else -- a share sheet that refused, a browser that lied about
      // support -- still leaves him wanting the text, so fall through.
    }
  }

  try {
    await navigator.clipboard.writeText(text);
    return "copied";
  } catch {
    return "failed";
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
