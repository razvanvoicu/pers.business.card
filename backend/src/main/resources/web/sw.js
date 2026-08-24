// Registering a service worker with a fetch handler is (or at least was, historically) part of Chrome's PWA
// installability criteria on Android. This one intentionally does nothing: the app has no offline/app-shell
// caching by design (see the README) and still needs a live connection to load.
self.addEventListener("fetch", () => {});
