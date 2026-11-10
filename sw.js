/* FaceFusion Mobile — service worker
   - app shell: cache-first, so the UI opens offline
   - CDN assets (face-api.js, nsfwjs + its content-checker weights, face models):
     cache-first too. This matters for the content checker specifically: the gate
     is fail-closed, so if its model were unavailable offline the whole app would
     refuse to process. Caching it keeps the app usable offline AND checked. */
const CACHE = 'ffm-v2';
const CDN_CACHE = 'ffm-cdn-v2';
const SHELL = ['./', './index.html', './styles.css', './app.js', './manifest.json', './icon-192.png', './icon-512.png'];
const CDN_HOSTS = ['cdn.jsdelivr.net', 'justadudewhohacks.github.io', 'hf-mirror.com', 'huggingface.co', 'raw.githubusercontent.com'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE && k !== CDN_CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);

  // model / library CDNs — cache-first, keep opaque responses too
  if (CDN_HOSTS.includes(url.hostname)) {
    e.respondWith(
      caches.open(CDN_CACHE).then((c) =>
        c.match(e.request).then((hit) =>
          hit || fetch(e.request).then((res) => {
            if (res && (res.ok || res.type === 'opaque')) c.put(e.request, res.clone()).catch(() => {});
            return res;
          })
        )
      )
    );
    return;
  }

  if (url.origin !== self.location.origin) return;

  e.respondWith(
    caches.match(e.request).then((hit) => hit || fetch(e.request).then((res) => {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match('./index.html')))
  );
});
