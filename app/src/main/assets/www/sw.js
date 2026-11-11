const CACHE='giw-v2';
self.addEventListener('install',e=>{self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('giw-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
// Network-first: always show the latest version when online; fall back to the cache when offline.
self.addEventListener('fetch',e=>{
  const req=e.request;
  if(req.method!=='GET'||new URL(req.url).origin!==location.origin)return;
  e.respondWith((async()=>{
    const cache=await caches.open(CACHE);
    try{
      const res=await fetch(req,{cache:'no-cache'});
      if(res.ok)cache.put(req,res.clone());
      return res;
    }catch(err){
      return (await cache.match(req))||Response.error();
    }
  })());
});
