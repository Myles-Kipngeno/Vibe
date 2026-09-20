/**
 * Prove the Android share target actually receives a conversation.
 *
 * The service worker is the whole share-to-assistant flow, and it cannot be
 * exercised by the type-check or the build -- both pass happily on a worker
 * that drops every share on the floor. A real check needs a browser, or this:
 * the worker is plain JavaScript, so it runs here against stubbed `self`,
 * `caches` and `Response.redirect`, and the handoff can be asserted.
 *
 *     node scripts/verify_share_target.mjs
 *
 * What it does not prove is installability -- that Chrome accepts the manifest,
 * offers "Add to home screen", and lists Vibe in the Android share sheet. That
 * needs a real device and HTTPS. See the README.
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const frontend = join(root, "frontend");

let passed = 0;
let failed = 0;

function check(name, ok, detail = "") {
  if (ok) {
    passed += 1;
    console.log(`  pass  ${name}${detail ? `  -- ${detail}` : ""}`);
  } else {
    failed += 1;
    console.log(`  FAIL  ${name}  -- ${detail}`);
  }
}

/** A cache that records what the worker parked, standing in for the CacheStorage API. */
function makeCacheStub() {
  const store = new Map();
  return {
    store,
    caches: {
      async open() {
        return {
          async put(key, response) {
            store.set(key, await response.text());
          },
          async match(key) {
            return store.has(key) ? new Response(store.get(key)) : undefined;
          },
          async delete(key) {
            return store.delete(key);
          },
        };
      },
    },
  };
}

/** Loads sw.js with a stubbed worker global and returns its fetch handler. */
async function loadWorker(cachesStub) {
  const source = readFileSync(join(frontend, "public", "sw.js"), "utf8");
  const listeners = {};
  const self = {
    addEventListener: (type, fn) => {
      listeners[type] = fn;
    },
    skipWaiting: () => {},
    clients: { claim: async () => {} },
  };
  const fn = new Function("self", "caches", "Response", "URL", `${source}\nreturn null;`);
  fn(self, cachesStub, Response, URL);
  return { listeners, self };
}

/** Builds the request Chrome sends when a conversation is shared in. */
function shareRequest(fields) {
  const form = new FormData();
  for (const [key, value] of Object.entries(fields)) form.append(key, value);
  return new Request("https://vibe.example/share", { method: "POST", body: form });
}

async function dispatch(listeners, request) {
  let responded;
  await listeners.fetch({ request, respondWith: (p) => (responded = p) });
  return responded ? await responded : null;
}

async function main() {
  console.log("Share target\n");

  // --- the manifest is what puts Vibe in the Android share sheet ------------
  const manifest = JSON.parse(
    readFileSync(join(frontend, "public", "manifest.webmanifest"), "utf8"),
  );
  const target = manifest.share_target ?? {};
  check("manifest declares a share target", Boolean(manifest.share_target));
  check(
    "shares arrive by POST, not in a URL",
    target.method === "POST",
    `method=${target.method}`,
  );
  check(
    "enctype carries form fields",
    target.enctype === "multipart/form-data",
    target.enctype,
  );
  check("text is among the accepted params", target.params?.text === "text");
  check(
    "installable: name, start_url, display, icons",
    Boolean(manifest.name && manifest.start_url && manifest.display === "standalone") &&
      Array.isArray(manifest.icons) &&
      manifest.icons.length > 0,
  );
  check(
    "a maskable icon is offered",
    manifest.icons.some((i) => String(i.purpose).includes("maskable")),
  );

  // --- the worker actually receives the conversation ------------------------
  const { caches: cachesStub, store } = makeCacheStub();
  const { listeners } = await loadWorker(cachesStub);
  check("worker registers a fetch handler", typeof listeners.fetch === "function");

  const conversation = "Her: niaje, uko aje leo?\nMe: poa sana, wewe je?";
  const response = await dispatch(listeners, shareRequest({ text: conversation }));

  check("a share is answered, not passed to the network", response !== null);
  check(
    "the browser is redirected back into the app",
    response?.status === 303 || response?.status === 302,
    `status=${response?.status}`,
  );
  const parked = store.get("/__shared-conversation");
  check("the conversation is handed to the page", parked === conversation, parked);
  check(
    "the conversation never appears in the redirect URL",
    !String(response?.headers.get("location") ?? "").includes("niaje"),
    response?.headers.get("location") ?? "",
  );

  // --- the shapes Android actually sends ------------------------------------
  const { caches: c2, store: s2 } = makeCacheStub();
  const w2 = await loadWorker(c2);
  await dispatch(
    w2.listeners,
    shareRequest({ title: "WhatsApp", text: conversation, url: "" }),
  );
  check(
    "a share with a title keeps the conversation",
    (s2.get("/__shared-conversation") ?? "").includes("niaje"),
  );

  const { caches: c3, store: s3 } = makeCacheStub();
  const w3 = await loadWorker(c3);
  const empty = await dispatch(w3.listeners, shareRequest({ text: "   " }));
  check("an empty share stores nothing", !s3.has("/__shared-conversation"));
  check(
    "an empty share still lands in the app",
    empty?.status === 303 || empty?.status === 302,
    `status=${empty?.status}`,
  );

  // --- a GET to the same path must not be swallowed -------------------------
  const { caches: c4 } = makeCacheStub();
  const w4 = await loadWorker(c4);
  const get = await dispatch(
    w4.listeners,
    new Request("https://vibe.example/share", { method: "GET" }),
  );
  check("a GET to /share is left to the network", get === null);

  console.log(`\n${"-".repeat(60)}`);
  console.log(`${passed} passed, ${failed} failed`);
  return failed ? 1 : 0;
}

main().then((code) => process.exit(code));
