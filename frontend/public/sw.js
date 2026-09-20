/* Service worker for the Android share-to-assistant flow.
 *
 * It exists for one reason: a share target that uses POST keeps the shared
 * conversation out of the URL. A GET target would need no service worker at
 * all, but the conversation would travel as a query string and land in browser
 * history and in any log along the way, which is the opposite of what this app
 * promises about conversations.
 *
 * So the POST is intercepted here, the text is parked in a cache entry that the
 * page reads exactly once, and the browser is redirected to the app. Nothing is
 * stored beyond that handoff, and nothing is sent anywhere.
 */

const SHARE_CACHE = "vibe-share";
const SHARE_KEY = "/__shared-conversation";
const SHARE_PATH = "/share";

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim());
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method === "POST" && url.pathname === SHARE_PATH) {
    event.respondWith(receiveShare(event.request));
  }
  // Everything else falls through to the network untouched. This worker is not
  // an offline cache; the app is useless without its backend anyway.
});

async function receiveShare(request) {
  let text = "";
  try {
    const form = await request.formData();
    text = [form.get("title"), form.get("text"), form.get("url")]
      .filter((part) => typeof part === "string" && part.trim())
      .join("\n")
      // multipart/form-data encodes every newline as CRLF, so a shared
      // conversation arrives with \r\n throughout. The parser copes either
      // way, but that is an artifact of the transport and has no business
      // being in what the user sees in the paste box.
      .replace(/\r\n/g, "\n")
      .trim();
  } catch {
    // A malformed share should still land the user in the app, empty-handed,
    // rather than showing them a browser error page.
  }

  if (text) {
    try {
      const cache = await caches.open(SHARE_CACHE);
      await cache.put(
        SHARE_KEY,
        new Response(text, { headers: { "Content-Type": "text/plain" } }),
      );
    } catch {
      text = "";
    }
  }

  // Resolved against the request rather than left relative: browsers resolve a
  // relative redirect against the worker's base URL, but being explicit keeps
  // it unambiguous and lets the flow be exercised outside a browser.
  const back = new URL(text ? "/?shared=1" : "/?shared=0", request.url);
  return Response.redirect(back.toString(), 303);
}
