package dev.cairn;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class Minecraft {
    public static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public record Library(Net.File file,boolean nativeJar,List<String> excludes) {}
    public record Version(String id,String jarId,Map<String,Object> data) {}
    private final Store store;
    private final Net net;
    private final Ui ui;
    public Minecraft(Store store,Net net,Ui ui) { this.store=store; this.net=net; this.ui=ui; }
    public Map<String,Object> manifest(boolean refresh) throws Exception { return Json.obj(net.cachedJson(MANIFEST,Net.Policy.OFFICIAL,refresh)); }
    public List<Object> remote(String filter,boolean refresh) throws Exception {
        List<Object> result = new ArrayList<>();
        for (Object o : Json.list(manifest(refresh),"versions")) {
            Map<String,Object> v = Json.obj(o);
            if (filter == null || filter.isBlank() || Json.str(v,"id","").contains(filter) || Json.str(v,"type","").equals(filter)) result.add(v);
        }
        return result;
    }
    public String canonical(String id) throws Exception {
        Map<String,Object> manifest = manifest(false);
        if (id.equals("latest") || id.equals("release")) return Json.str(Json.child(manifest,"latest"),"release","");
        if (id.equals("snapshot")) return Json.str(Json.child(manifest,"latest"),"snapshot","");
        return id;
    }
    public Path versionDir(String id) throws IOException { return Util.within(store.game.resolve("versions"),Util.fileName(id)); }
    public Path versionFile(String id) throws IOException { return versionDir(id).resolve(id+".json"); }
    public List<Map<String,Object>> installed() throws IOException {
        List<Map<String,Object>> versions = new ArrayList<>();
        for (Path p : Util.children(store.game.resolve("versions"))) {
            if (!Files.isDirectory(p)) continue;
            Path f = p.resolve(p.getFileName()+".json");
            if (Files.isRegularFile(f)) try {
                Map<String,Object> v = Json.obj(Json.read(f));
                versions.add(Json.map("id",p.getFileName().toString(),"type",Json.str(v,"type","custom"),"inheritsFrom",Json.str(v,"inheritsFrom","")));
            } catch (RuntimeException e) { ui.note("损坏版本描述: "+f.getFileName()); }
        }
        return versions;
    }
    public synchronized String install(String requested,String instance,Jobs.Job job) throws Exception {
        if (instance != null && !instance.isBlank()) Util.require(!Files.exists(store.instance(instance)),"实例已存在: "+instance+"；补全请使用 repair");
        String id = canonical(requested);
        job.stage("获取 Mojang 版本描述 "+id);
        Map<String,Object> entry = null;
        for (Object o : Json.list(manifest(false),"versions")) if (Json.str(Json.obj(o),"id","").equals(id)) { entry=Json.obj(o); break; }
        Util.require(entry != null,"官方列表中没有版本: "+id);
        net.download(new Net.File(Json.str(entry,"url",""),versionFile(id),Json.str(entry,"sha1",""),-1,Net.Policy.OFFICIAL,Sources.Kind.METADATA),job);
        Version v = resolve(id);
        repair(v,job);
        if (instance != null && !instance.isBlank()) createInstance(instance,id,"vanilla");
        return id;
    }
    public synchronized void createInstance(String name,String version,String loader) throws IOException {
        Path dir = store.instance(name);
        Util.require(!Files.exists(dir),"实例已存在: "+name);
        resolve(version); Files.createDirectories(dir.resolve("game"));
        store.saveInstance(name,Json.map("id",name,"version",version,"loader",loader,"description","","favorite",false,"hidden",false,"settings",Json.map(),"created",java.time.Instant.now().toString()));
        if (store.text("selected").isBlank()) store.set("selected",name);
        ui.note("实例已创建: "+name+" → "+version);
    }
    public Version resolve(String id) throws IOException { return resolve(id,new LinkedHashSet<>()); }
    private Version resolve(String id,Set<String> parents) throws IOException {
        Util.require(parents.size() < 32 && parents.add(id),"版本继承循环/过深: "+id);
        Path file = versionFile(id); Util.require(Files.isRegularFile(file),"版本描述不存在: "+id+"，请先安装或补全");
        Map<String,Object> own = Json.obj(Json.read(file));
        Util.require(!Json.str(own,"mainClass","").isBlank() || own.containsKey("inheritsFrom"),"版本描述缺少 mainClass");
        String parent = Json.str(own,"inheritsFrom","");
        if (parent.isBlank()) return new Version(id,Json.str(own,"jar",id),own);
        Version base = resolve(parent,parents);
        Map<String,Object> merged = Json.obj(Json.copy(base.data()));
        merged.putAll(own);
        Map<String,Object> arguments = Json.map();
        for (String kind : List.of("jvm","game")) {
            List<Object> list = new ArrayList<>(Json.list(Json.child(base.data(),"arguments"),kind));
            if (kind.equals("game") && list.isEmpty() && base.data().containsKey("minecraftArguments"))
                list.addAll(Shell.words(Json.str(base.data(),"minecraftArguments","")));
            if (kind.equals("game") && own.containsKey("minecraftArguments")) {
                list.clear(); list.addAll(Shell.words(Json.str(own,"minecraftArguments","")));
            } else list.addAll(Json.list(Json.child(own,"arguments"),kind));
            if (!list.isEmpty()) arguments.put(kind,list);
        }
        if (!arguments.isEmpty()) merged.put("arguments",arguments);
        Map<String,Object> libs = new LinkedHashMap<>();
        for (Object lib : Json.list(base.data(),"libraries")) libs.put(libraryKey(Json.str(Json.obj(lib),"name","")),lib);
        for (Object lib : Json.list(own,"libraries")) libs.put(libraryKey(Json.str(Json.obj(lib),"name","")),lib);
        merged.put("libraries",new ArrayList<>(libs.values()));
        Map<String,Object> downloads = new LinkedHashMap<>(Json.child(base.data(),"downloads")); downloads.putAll(Json.child(own,"downloads"));
        if (!downloads.isEmpty()) merged.put("downloads",downloads);
        return new Version(id,Json.str(own,"jar",base.jarId()),merged);
    }
    static String libraryKey(String name) {
        String[] c = name.split(":"); return c.length >= 3 ? c[0]+":"+c[1]+(c.length > 3 ? ":"+c[3] : "") : name;
    }
    public static String mavenPath(String coordinate) {
        String extension = "jar", value = coordinate;
        if (coordinate.contains("@")) { int at=coordinate.lastIndexOf('@'); extension=coordinate.substring(at+1); value=coordinate.substring(0,at); }
        String[] p = value.split(":"); Util.require(p.length >= 3 && p.length <= 4,"无效 Maven 坐标: "+coordinate);
        for (String component : p) Util.require(component.matches("[A-Za-z0-9_.+\\-]+"),"无效 Maven 坐标");
        Util.require(extension.matches("[A-Za-z0-9]+"),"无效扩展名");
        return p[0].replace('.','/')+"/"+p[1]+"/"+p[2]+"/"+p[1]+"-"+p[2]+(p.length == 4 ? "-"+p[3] : "")+"."+extension;
    }
    public static boolean allowed(Map<String,Object> item,Map<String,Boolean> features) {
        List<Object> rules = Json.list(item,"rules");
        if (rules.isEmpty()) return true;
        boolean allowed = false;
        for (Object raw : rules) {
            Map<String,Object> r = Json.obj(raw), os = Json.child(r,"os"); boolean matches=true;
            if (os.containsKey("name")) matches &= Util.OS.equals(os.get("name"));
            if (os.containsKey("arch")) matches &= Util.ARCH.matches(os.get("arch").toString());
            if (os.containsKey("version")) matches &= System.getProperty("os.version").matches(os.get("version").toString());
            for (var e : Json.child(r,"features").entrySet())
                matches &= Objects.equals(features.getOrDefault(e.getKey(),false),e.getValue());
            if (matches) allowed="allow".equals(Json.str(r,"action","disallow"));
        }
        return allowed;
    }
    public List<Library> libraries(Version v) throws IOException {
        List<Library> result = new ArrayList<>();
        for (Object raw : Json.list(v.data(),"libraries")) {
            Map<String,Object> lib = Json.obj(raw); if (!allowed(lib,Map.of())) continue;
            Map<String,Object> downloads=Json.child(lib,"downloads"), artifact=Json.child(downloads,"artifact");
            String coordinate=Json.str(lib,"name","");
            if (!artifact.isEmpty()) result.add(new Library(artifact(artifact,Json.str(lib,"url","https://libraries.minecraft.net/"),coordinate),
                    coordinate.contains(":natives-"),List.of("META-INF/")));
            else if (downloads.isEmpty() && !coordinate.isBlank()) {
                String path=mavenPath(coordinate), base=Json.str(lib,"url","https://libraries.minecraft.net/");
                result.add(new Library(new Net.File(Util.urlBase(base)+path,Util.within(store.game.resolve("libraries"),path),Json.str(lib,"sha1",""),Json.number(lib,"size",-1),policy(base),Sources.Kind.LIBRARY),
                        coordinate.contains(":natives-"),List.of("META-INF/")));
            }
            String classifier = Json.str(Json.child(lib,"natives"),Util.OS,"").replace("${arch}",Util.ARCH.contains("64") ? "64" : "32");
            if (!classifier.isBlank()) {
                Map<String,Object> nativeFile=Json.child(Json.child(downloads,"classifiers"),classifier);
                if (!nativeFile.isEmpty()) {
                    List<String> exclusions=Json.list(Json.child(lib,"extract"),"exclude").stream().map(Object::toString).toList();
                    result.add(new Library(artifact(nativeFile,"https://libraries.minecraft.net/",coordinate),true,exclusions));
                } else if (downloads.isEmpty()) {
                    String[] parts=coordinate.split(":"); String path=mavenPath(parts[0]+":"+parts[1]+":"+parts[2]+":"+classifier);
                    String base=Util.urlBase(Json.str(lib,"url","https://libraries.minecraft.net/"));
                    result.add(new Library(new Net.File(base+path,Util.within(store.game.resolve("libraries"),path),"",-1,policy(base),Sources.Kind.LIBRARY),true,List.of("META-INF/")));
                }
            }
        }
        return result;
    }
    private Net.Policy policy(String url) {
        try { Net.uri(url,Net.Policy.OFFICIAL); return Net.Policy.OFFICIAL; }
        catch (IllegalArgumentException e) { return Net.Policy.UPSTREAM; }
    }
    private Net.File artifact(Map<String,Object> data,String base,String coordinate) throws IOException {
        String path=Json.str(data,"path","");
        if (path.isBlank()) path=mavenPath(coordinate);
        String url=Json.str(data,"url",Util.urlBase(base)+path);
        return new Net.File(url,Util.within(store.game.resolve("libraries"),path),Json.str(data,"sha1",""),Json.number(data,"size",-1),policy(url),Sources.Kind.LIBRARY);
    }
    public void repair(Version v,Jobs.Job job) throws Exception {
        job.stage("校验客户端与依赖库 "+v.id());
        List<Net.File> files = new ArrayList<>();
        Map<String,Object> download=Json.child(Json.child(v.data(),"downloads"),"client");
        Path jar=versionDir(v.jarId()).resolve(v.jarId()+".jar");
        if (!download.isEmpty()) files.add(new Net.File(Json.str(download,"url",""),jar,Json.str(download,"sha1",""),Json.number(download,"size",-1),Net.Policy.OFFICIAL,Sources.Kind.CLIENT));
        else Util.require(Files.isRegularFile(jar),"缺少客户端 jar: "+jar);
        for (Library lib : libraries(v)) files.add(lib.file());
        Map<String,Object> logging=Json.child(Json.child(v.data(),"logging"),"client"), logFile=Json.child(logging,"file");
        if (!logFile.isEmpty()) files.add(new Net.File(Json.str(logFile,"url",""),Util.within(store.game.resolve("assets/log_configs"),Util.fileName(Json.str(logFile,"id",""))),
                Json.str(logFile,"sha1",""),Json.number(logFile,"size",-1),Net.Policy.OFFICIAL,Sources.Kind.CLIENT));
        net.downloadAll(files,job);
        Map<String,Object> index=Json.child(v.data(),"assetIndex");
        if (!index.isEmpty()) {
            job.stage("获取资源索引");
            String assetId=Util.fileName(Json.str(index,"id",""));
            Path indexPath=Util.within(store.game.resolve("assets/indexes"),assetId+".json");
            net.download(new Net.File(Json.str(index,"url",""),indexPath,Json.str(index,"sha1",""),Json.number(index,"size",-1),Net.Policy.OFFICIAL,Sources.Kind.METADATA),job);
            List<Net.File> assets=new ArrayList<>();
            for (Object raw : Json.child(Json.obj(Json.read(indexPath)),"objects").values()) {
                Map<String,Object> o=Json.obj(raw); String hash=Json.str(o,"hash","");
                Util.require(hash.matches("[0-9a-f]{40}"),"资源索引包含无效哈希");
                String path=hash.substring(0,2)+"/"+hash;
                assets.add(new Net.File("https://resources.download.minecraft.net/"+path,Util.within(store.game.resolve("assets/objects"),path),hash,Json.number(o,"size",-1),Net.Policy.OFFICIAL,Sources.Kind.ASSET));
            }
            job.stage("校验声音、材质和语言资源"); net.downloadAll(assets,job);
        }
    }
    public Path assetsRoot(Version v,Path gameDir) throws IOException {
        Map<String,Object> asset=Json.child(v.data(),"assetIndex");
        if (asset.isEmpty()) return store.game.resolve("assets");
        String id=Util.fileName(Json.str(asset,"id",""));
        Path file=store.game.resolve("assets/indexes").resolve(id+".json");
        if (!Files.exists(file)) return store.game.resolve("assets");
        Map<String,Object> index=Json.obj(Json.read(file));
        boolean virtual=Json.bool(index,"virtual",false), map=Json.bool(index,"map_to_resources",false);
        if (virtual || map) {
            Path dest=virtual ? store.game.resolve("assets/virtual").resolve(id) : gameDir.resolve("resources");
            Files.createDirectories(dest);
            for (var e : Json.child(index,"objects").entrySet()) {
                String hash=Json.str(Json.obj(e.getValue()),"hash","");
                Util.require(hash.matches("[0-9a-f]{40}"),"无效资源哈希");
                Path src=store.game.resolve("assets/objects").resolve(hash.substring(0,2)).resolve(hash), target=Util.within(dest,e.getKey());
                if (!Files.exists(target) || Files.size(target) != Files.size(src)) {
                    Files.createDirectories(target.getParent()); Files.copy(src,target,StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (virtual) return dest;
        }
        return store.game.resolve("assets");
    }
    public Path natives(Version v,String instance) throws IOException {
        Path dest=store.instance(instance).resolve("natives-"+Util.OS+"-"+Util.ARCH); Files.createDirectories(dest);
        for (Library lib : libraries(v)) {
            if (!lib.nativeJar()) continue;
            Util.require(Files.isRegularFile(lib.file().path()),"缺少本地库 "+lib.file().path().getFileName());
            try (ZipFile zip = new ZipFile(lib.file().path().toFile())) {
                var entries=zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e=entries.nextElement(); String name=e.getName();
                    if (e.isDirectory() || name.startsWith("META-INF/") || lib.excludes().stream().anyMatch(name::startsWith)) continue;
                    if (!(name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib") || name.endsWith(".jnilib"))) continue;
                    Util.require(e.getSize() < 512L*1024*1024,"本地库过大");
                    // Native lookup needs flat names, including modern jars with nested platform directories.
                    Path target=Util.within(dest,Util.fileName(name.substring(name.lastIndexOf('/')+1)));
                    try (InputStream in=zip.getInputStream(e)) {
                        byte[] bytes=in.readNBytes(512*1024*1024+1);
                        Util.require(bytes.length <= 512*1024*1024,"本地库过大"); Util.atomic(target,bytes);
                    }
                }
            }
        }
        return dest;
    }
    public void server(String requested,Path dest,Jobs.Job job) throws Exception {
        String id=canonical(requested); Map<String,Object> entry=null;
        for (Object o : Json.list(manifest(false),"versions")) if (Json.str(Json.obj(o),"id","").equals(id)) { entry=Json.obj(o); break; }
        Util.require(entry != null,"未知版本");
        Map<String,Object> v=Json.obj(net.json(Json.str(entry,"url",""),Net.Policy.OFFICIAL)), file=Json.child(Json.child(v,"downloads"),"server");
        Util.require(!file.isEmpty(),"此版本无官方下载服务端");
        Util.require(!Files.exists(dest),"输出已存在");
        net.download(new Net.File(Json.str(file,"url",""),dest,Json.str(file,"sha1",""),Json.number(file,"size",-1),Net.Policy.OFFICIAL,Sources.Kind.CLIENT),job);
        ui.note("服务端已保存: "+dest+"；EULA 需由你自行阅读并接受");
    }
}
