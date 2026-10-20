package dev.cairn;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class Net {
    public enum Policy { OFFICIAL, UPSTREAM }
    public record File(String url,Path path,String hash,String algorithm,long size,Policy policy,Sources.Kind kind) {
        public File(String url,Path path,String hash,String algorithm,long size,Policy policy) { this(url,path,hash,algorithm,size,policy,Sources.Kind.AUTO); }
        public File(String url,Path path,String sha1,long size,Policy policy) { this(url,path,sha1,"SHA-1",size,policy,Sources.Kind.AUTO); }
        public File(String url,Path path,String sha1,long size,Policy policy,Sources.Kind kind) { this(url,path,sha1,"SHA-1",size,policy,kind); }
    }
    public record Response(int status, String body) {
        public Map<String,Object> object() { return Json.obj(Json.parse(body)); }
    }
    private static final Set<String> OFFICIAL_HOSTS = Set.of("piston-meta.mojang.com","piston-data.mojang.com",
            "launchermeta.mojang.com","launcher.mojang.com","libraries.minecraft.net","resources.download.minecraft.net",
            "launcher-assets.mojang.com","client-statistics.mojang.com","s3.amazonaws.com");
    private final Store store;
    private final HttpClient client;
    private final ConcurrentMap<Path,Object> pathLocks = new ConcurrentHashMap<>();
    public Net(Store store) {
        this.store = store;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(25)).followRedirects(HttpClient.Redirect.NEVER);
        String proxy = store.text("proxy");
        if (proxy.isBlank()) proxy = System.getenv().getOrDefault("HTTPS_PROXY",System.getenv().getOrDefault("https_proxy",""));
        if (!proxy.isBlank()) {
            URI p = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            Util.require(p.getHost() != null && p.getUserInfo() == null,"代理请使用 http://host:port，不在配置中保存代理密码");
            builder.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(),p.getPort() < 0 ? 8080 : p.getPort())));
        }
        client = builder.build();
    }
    Net(Store store,HttpClient fixtureClient) { this.store=store; this.client=fixtureClient; }
    public static URI uri(String url,Policy policy) {
        URI u = URI.create(url.replace(" ","%20"));
        if ("http".equalsIgnoreCase(u.getScheme()) && u.getHost() != null &&
                (OFFICIAL_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT)) || policy == Policy.UPSTREAM))
            u = URI.create("https" + u.toString().substring(4));
        Util.require("https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null,"下载只允许 HTTPS 地址");
        if (policy == Policy.OFFICIAL) {
            String h = u.getHost().toLowerCase(Locale.ROOT);
            Util.require(OFFICIAL_HOSTS.contains(h),"拒绝非 Mojang 官方游戏源: " + h);
            if (h.equals("s3.amazonaws.com")) Util.require(u.getPath().startsWith("/Minecraft.Download/"),"拒绝非 Minecraft 官方 S3 路径");
        }
        return u;
    }
    private HttpRequest.Builder builder(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).header("User-Agent","CairnLauncher/"+Main.VERSION+" (independent Java terminal launcher)").header("Accept-Encoding","identity");
    }
    private HttpResponse<InputStream> getStream(String url,Policy policy,Map<String,String> headers) throws Exception {
        return getStream(url,policy,headers,Sources.Kind.AUTO,Sources.active(store.config()));
    }
    private HttpResponse<InputStream> getStream(String url,Policy policy,Map<String,String> headers,Sources.Kind kind,Sources.Selection source) throws Exception {
        URI current = policy == Policy.OFFICIAL ? source.resolve(url,kind) : uri(url,policy);
        for (int i = 0; i <= 6; i++) {
            HttpRequest.Builder b = builder(current); headers.forEach(b::header);
            HttpResponse<InputStream> r = client.send(b.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            if (Set.of(301,302,303,307,308).contains(r.statusCode())) {
                try {
                    String next = r.headers().firstValue("location").orElseThrow(() -> new IOException("重定向缺少目标"));
                    URI resolved = current.resolve(next);
                    if (policy == Policy.OFFICIAL) resolved=source.redirect(resolved); else resolved=uri(resolved.toString(),policy);
                    // Do not pass credentials across hosts.
                    if (!Objects.equals(current.getHost(),resolved.getHost()))
                        headers = headers.entrySet().stream().filter(e -> !Set.of("authorization","x-api-key").contains(e.getKey().toLowerCase(Locale.ROOT)))
                                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue));
                    current = resolved;
                } finally { r.body().close(); }
                continue;
            }
            return r;
        }
        throw new IOException("重定向次数过多");
    }
    public String text(String url,Policy policy) throws Exception { return request("GET",url,null,Map.of(),policy).body(); }
    public Object json(String url,Policy policy) throws Exception { return Json.parse(text(url,policy)); }
    public Object cachedJson(String url,Policy policy,boolean refresh) throws Exception {
        Sources.Selection selected=Sources.active(store.config());
        String key=(policy == Policy.OFFICIAL ? selected.fingerprint() : "upstream")+":"+url;
        Path cache = store.cache.resolve("meta-" + Util.sha1(key.getBytes(StandardCharsets.UTF_8))+".json");
        if (!refresh && Files.isRegularFile(cache) && System.currentTimeMillis()-Files.getLastModifiedTime(cache).toMillis() < 15*60*1000) return Json.read(cache);
        HttpResponse<InputStream> response=getStream(url,policy,new HashMap<>(),Sources.Kind.AUTO,selected);
        String text;
        try (InputStream in=response.body()) {
            Util.require(response.statusCode() >= 200 && response.statusCode() < 300,"元数据 HTTP "+response.statusCode()+" · "+response.uri().getHost());
            text=readLimited(in,32*1024*1024);
        }
        Object data=Json.parse(text); Util.atomic(cache,Json.stringify(data)); return data;
    }
    public Response requestRaw(String method,String url,String body,Map<String,String> headers) throws Exception {
        URI target = uri(url,Policy.UPSTREAM);
        HttpRequest.Builder b = builder(target); headers.forEach(b::header);
        b.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        HttpResponse<InputStream> r = client.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) { return new Response(r.statusCode(),readLimited(in,32*1024*1024)); }
    }
    public Response request(String method,String url,String body,Map<String,String> headers,Policy policy) throws Exception {
        Response result;
        if (method.equals("GET")) {
            HttpResponse<InputStream> r = getStream(url,policy,new HashMap<>(headers));
            try (InputStream in = r.body()) { result = new Response(r.statusCode(),readLimited(in,32*1024*1024)); }
        } else result = requestRaw(method,url,body,headers);
        if (result.status() < 200 || result.status() >= 300) {
            String detail = "";
            try {
                Map<String,Object> err = result.object();
                detail = Json.str(err,"error_description",Json.str(err,"errorMessage",Json.str(err,"message",Json.str(err,"error",""))));
                if (detail.length() > 500) detail = detail.substring(0,500);
            } catch (RuntimeException ignored) {}
            throw new IOException("HTTP " + result.status() + " · " + URI.create(url).getHost() + (detail.isBlank() ? "" : " · " + Util.redact(detail)));
        }
        return result;
    }
    public Map<String,Object> postJson(String url,Object body,Map<String,String> headers) throws Exception {
        Map<String,String> h = new HashMap<>(headers); h.put("Content-Type","application/json");
        return request("POST",url,Json.stringify(body),h,Policy.UPSTREAM).object();
    }
    public Response postBytes(String url,byte[] body,Map<String,String> headers) throws Exception {
        HttpRequest.Builder b=builder(uri(url,Policy.UPSTREAM)); headers.forEach(b::header);
        HttpResponse<InputStream> r=client.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in=r.body()) {
            Response result=new Response(r.statusCode(),readLimited(in,2*1024*1024));
            if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IOException("上传 HTTP "+r.statusCode()+" · "+URI.create(url).getHost());
            return result;
        }
    }
    public Response form(String url,Map<String,Object> fields) throws Exception {
        String body = fields.entrySet().stream().map(e -> Util.enc(e.getKey())+"="+Util.enc(e.getValue().toString())).collect(java.util.stream.Collectors.joining("&"));
        return requestRaw("POST",url,body,Map.of("Content-Type","application/x-www-form-urlencoded"));
    }
    private static String readLimited(InputStream in,int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) >= 0) { if (b.size()+n > max) throw new IOException("响应过大"); b.write(buf,0,n); }
        return b.toString(StandardCharsets.UTF_8);
    }
    public boolean valid(File f) throws IOException {
        if (!Files.isRegularFile(f.path())) return false;
        if (f.size() >= 0 && Files.size(f.path()) != f.size()) return false;
        return f.hash() == null || f.hash().isBlank() || Util.hash(f.path(),f.algorithm()).equalsIgnoreCase(f.hash());
    }
    public void download(File f,Jobs.Job job) throws Exception {
        Path target = f.path().toAbsolutePath().normalize();
        synchronized (pathLocks.computeIfAbsent(target,p -> new Object())) {
            if (job != null) job.check();
            if (valid(f)) return;
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName()+".part");
            Exception failure = null;
            for (int retry = 0; retry < store.integer("retries"); retry++) {
                if (job != null) job.check();
                try {
                    long offset = Files.exists(part) ? Files.size(part) : 0;
                    if (f.size() >= 0 && offset > f.size()) { Files.deleteIfExists(part); offset = 0; }
                    Map<String,String> h = offset > 0 ? Map.of("Range","bytes="+offset+"-") : Map.of();
                    HttpResponse<InputStream> r = getStream(f.url(),f.policy(),new HashMap<>(h),f.kind(),Sources.active(store.config()));
                    long start = offset;
                    try (InputStream in = r.body()) {
                        if (r.statusCode() == 416) { Files.deleteIfExists(part); throw new IOException("断点无效，重新下载"); }
                        Util.require(r.statusCode() == 200 || r.statusCode() == 206,"下载 HTTP " + r.statusCode() + " · " + r.uri().getHost());
                        if (r.statusCode() == 206) {
                            String range = r.headers().firstValue("content-range").orElse("");
                            Util.require(range.startsWith("bytes "+offset+"-"),"服务器返回错误的断点范围");
                        } else start = 0;
                        try (OutputStream out = Files.newOutputStream(part,StandardOpenOption.CREATE,StandardOpenOption.WRITE,
                                start > 0 ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                            byte[] b = new byte[65536]; int n; long transferred = 0, begin = System.nanoTime();
                            while ((n = in.read(b)) >= 0) {
                                if (job != null) job.check();
                                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
                                out.write(b,0,n); transferred += n;
                                if (f.size() >= 0 && start+transferred > f.size()) throw new IOException("下载内容超过声明大小");
                                if (job != null) { job.bytes.addAndGet(n); job.progress(false); }
                                int limit = store.integer("speedKiB");
                                if (limit > 0) {
                                    long expected = transferred*1_000_000_000L/(limit*1024L), elapsed = System.nanoTime()-begin;
                                    long wait = (expected-elapsed)/1_000_000;
                                    while (wait > 0) {
                                        long slice = Math.min(wait,100); Thread.sleep(slice); wait -= slice;
                                        if (job != null) job.check();
                                    }
                                }
                            }
                        }
                    }
                    File partial = new File(f.url(),part,f.hash(),f.algorithm(),f.size(),f.policy(),f.kind());
                    if (!valid(partial)) {
                        Files.deleteIfExists(part); throw new IOException("长度或 " + f.algorithm() + " 校验失败: " + target.getFileName());
                    }
                    Util.move(part,target,true); return;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted() || e instanceof InterruptedException || e instanceof InterruptedIOException) throw e;
                    failure = e;
                    if (retry+1 < store.integer("retries")) Thread.sleep(Math.min(4000,500L*(retry+1)));
                }
            }
            throw new IOException("下载失败 " + target.getFileName() + ": " + (failure == null ? "" : failure.getMessage()),failure);
        }
    }
    public void downloadAll(Collection<File> files,Jobs.Job job) throws Exception {
        List<File> unique = new ArrayList<>(files.stream().collect(java.util.stream.Collectors.toMap(f -> f.path().toAbsolutePath().normalize(),f -> f,(a,b) -> b,LinkedHashMap::new)).values());
        job.total.set(unique.size()); job.done.set(0);
        int threads = store.integer("threads");
        // speedKiB is an aggregate cap, divided among active worker slots.
        if (store.integer("speedKiB") > 0) threads = 1;
        ExecutorService pool = Executors.newFixedThreadPool(threads,r -> { Thread t = new Thread(r,"cairn-download"); t.setDaemon(true); return t; });
        List<Future<?>> futures = new ArrayList<>();
        try {
            CompletionService<Void> completed = new ExecutorCompletionService<>(pool);
            for (File f : unique) futures.add(completed.submit(() -> { job.check(); download(f,job); job.done.incrementAndGet(); job.progress(false); return null; }));
            for (int i = 0; i < unique.size(); i++) {
                job.check();
                try { completed.take().get(); }
                catch (ExecutionException e) { if (e.getCause() instanceof Exception ex) throw ex; throw e; }
            }
        } finally {
            for (Future<?> f : futures) f.cancel(true); pool.shutdownNow();
            if (!pool.awaitTermination(10,TimeUnit.SECONDS)) throw new IOException("下载线程未能退出，请稍后重试");
        }
    }
    public Map<String,Object> probeSource(String id) throws Exception {
        Sources.Selection source=Sources.select(store.config(),id); long start=System.nanoTime();
        String manifestText=probeText(Minecraft.MANIFEST,Sources.Kind.AUTO,source);
        Map<String,Object> manifest=Json.obj(Json.parse(manifestText)); List<Object> versions=Json.list(manifest,"versions");
        Util.require(!versions.isEmpty(),"下载源没有返回兼容的 Mojang 版本清单");
        String latest=Json.str(Json.child(manifest,"latest"),"release","");
        Map<String,Object> sample=Json.obj(versions.get(0));
        for (Object raw : versions) if (latest.equals(Json.obj(raw).get("id"))) { sample=Json.obj(raw); break; }
        String metadata=probeText(Json.str(sample,"url",""),Sources.Kind.METADATA,source);
        Map<String,Object> descriptor=Json.obj(Json.parse(metadata));
        Util.require(Objects.equals(descriptor.get("id"),sample.get("id")) && (descriptor.containsKey("mainClass") || descriptor.containsKey("inheritsFrom")),"样本版本描述不符合清单或缺少启动信息");
        String expected=Json.str(sample,"sha1","");
        if (!expected.isBlank()) Util.require(Util.sha1(metadata.getBytes(StandardCharsets.UTF_8)).equalsIgnoreCase(expected),"版本描述 SHA-1 与清单不符");
        return Json.map("source",id,"manifest",source.resolve(Minecraft.MANIFEST,Sources.Kind.AUTO).toString(),"versions",versions.size(),
                "sampleVersion",sample.get("id"),"metadataHash",expected.isBlank() ? "not-provided" : "verified","latencyMs",(System.nanoTime()-start)/1_000_000,"status","OK");
    }
    private String probeText(String url,Sources.Kind kind,Sources.Selection source) throws Exception {
        HttpResponse<InputStream> r=getStream(url,Policy.OFFICIAL,new HashMap<>(),kind,source);
        try (InputStream in=r.body()) {
            Util.require(r.statusCode() >= 200 && r.statusCode() < 300,"下载源检查 HTTP "+r.statusCode()+" · "+r.uri().getHost());
            return readLimited(in,32*1024*1024);
        }
    }
}
