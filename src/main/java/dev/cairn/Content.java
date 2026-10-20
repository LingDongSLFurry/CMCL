package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.regex.*;

public final class Content {
    private static final String MR="https://api.modrinth.com/v2", CF="https://api.curseforge.com/v1";
    private final Store store;
    private final Net net;
    private final Minecraft mc;
    private final Ui ui;
    public Content(Store store,Net net,Minecraft mc,Ui ui) { this.store=store; this.net=net; this.mc=mc; this.ui=ui; }
    public String baseGame(String instance) throws IOException {
        String id=Json.str(store.instanceConfig(instance),"version",""); Set<String> seen=new HashSet<>();
        while (seen.add(id) && seen.size() < 32) {
            Map<String,Object> d=Json.obj(Json.read(mc.versionFile(id))); String parent=Json.str(d,"inheritsFrom","");
            if (parent.isBlank()) return id; id=parent;
        }
        throw new IOException("继承循环");
    }
    private Map<String,String> cfHeaders() {
        String key=store.text("curseforgeKey"); if (key.isBlank()) key=System.getenv().getOrDefault("CAIRN_CF_KEY","");
        Util.require(!key.isBlank(),"CurseForge 需要自己的 API Key: config set curseforgeKey，或 CAIRN_CF_KEY 环境变量");
        return Map.of("x-api-key",key,"Accept","application/json");
    }
    public Map<String,Object> cf(String path) throws Exception { return net.request("GET",CF+path,null,cfHeaders(),Net.Policy.UPSTREAM).object(); }
    public List<Object> search(String query,String type,String game,String loader,String source,int limit,int offset) throws Exception {
        if (source.equals("curseforge")) {
            int classId=switch(type) { case "modpack" -> 4471; case "resourcepack" -> 12; case "shader" -> 6552; case "world" -> 17; default -> 6; };
            String url="/mods/search?gameId=432&classId="+classId+"&searchFilter="+Util.enc(query)+"&pageSize="+Math.min(50,limit)+"&index="+offset;
            if (!game.isBlank()) url+="&gameVersion="+Util.enc(game);
            if (!loader.isBlank()) url+="&modLoaderType="+cfLoader(loader);
            return Json.list(cf(url),"data");
        }
        Util.require(source.equals("modrinth"),"资源源: modrinth | curseforge");
        List<Object> facets=new ArrayList<>();
        String projectType=type.equals("datapack") ? "mod" : type;
        if (!projectType.isBlank()) facets.add(List.of("project_type:"+projectType));
        if (type.equals("datapack")) facets.add(List.of("categories:datapack"));
        if (!game.isBlank()) facets.add(List.of("versions:"+game));
        if (!loader.isBlank()) facets.add(List.of("categories:"+loader));
        Map<String,Object> result=Json.obj(net.json(MR+"/search?query="+Util.enc(query)+"&facets="+Util.enc(Json.stringify(facets))+"&limit="+Math.min(100,limit)+"&offset="+offset,Net.Policy.UPSTREAM));
        return Json.list(result,"hits");
    }
    private int cfLoader(String loader) { return switch (loader) { case "forge" -> 1; case "liteloader" -> 3; case "fabric" -> 4; case "quilt" -> 5; case "neoforge" -> 6; default -> 0; }; }
    public Map<String,Object> project(String id,String source) throws Exception {
        return source.equals("curseforge") ? Json.child(cf("/mods/"+numeric(id)),"data") : Json.obj(net.json(MR+"/project/"+Util.enc(id),Net.Policy.UPSTREAM));
    }
    private String numeric(String s) { Util.require(s.matches("\\d+"),"CurseForge 使用数字项目 ID"); return s; }
    public List<Object> versions(String id,String source,String game,String loader) throws Exception {
        if (source.equals("curseforge")) {
            String path="/mods/"+numeric(id)+"/files?pageSize=50";
            if (!game.isBlank()) path+="&gameVersion="+Util.enc(game);
            if (!loader.isBlank()) path+="&modLoaderType="+cfLoader(loader);
            return Json.list(cf(path),"data");
        }
        String url=MR+"/project/"+Util.enc(id)+"/version";
        List<String> params=new ArrayList<>();
        if (!game.isBlank()) params.add("game_versions="+Util.enc(Json.stringify(List.of(game))));
        if (!loader.isBlank()) params.add("loaders="+Util.enc(Json.stringify(List.of(loader))));
        if (!params.isEmpty()) url+="?"+String.join("&",params);
        return Json.arr(net.json(url,Net.Policy.UPSTREAM));
    }
    public synchronized void install(String project,String version,String source,String instance,String type,String world,Jobs.Job job) throws Exception {
        instance=store.selected(instance);
        installRecursive(project,version,source,instance,type,world,job,new HashSet<>(),0);
    }
    private void installRecursive(String project,String requested,String source,String instance,String type,String world,Jobs.Job job,Set<String> visited,int depth) throws Exception {
        job.check(); Util.require(depth <= 40,"资源依赖过深");
        if (!visited.add(source+":"+project+":"+requested)) return;
        Map<String,Object> config=store.instanceConfig(instance);
        String game=baseGame(instance), loader=Json.str(config,"loader","vanilla");
        if (type.equals("datapack")) loader="datapack";
        else if (!type.equals("mod")) loader="";
        Map<String,Object> version;
        if (source.equals("curseforge")) {
            if (requested.isBlank() || requested.equals("latest")) {
                List<Object> list=versions(project,source,game,loader); Util.require(!list.isEmpty(),"没有适配 "+game+" "+loader+" 的资源"); version=Json.obj(list.get(0));
            } else version=Json.child(cf("/mods/"+numeric(project)+"/files/"+numeric(requested)),"data");
            for (Object raw : Json.list(version,"dependencies")) {
                Map<String,Object> dep=Json.obj(raw);
                if (Json.integer(dep,"relationType",0) == 3)
                    installRecursive(String.valueOf(dep.get("modId")),"latest",source,instance,"mod",world,job,visited,depth+1);
            }
        } else {
            if (requested.isBlank() || requested.equals("latest")) {
                List<Object> list=versions(project,source,game,loader); Util.require(!list.isEmpty(),"没有适配 "+game+" "+loader+" 的资源"); version=Json.obj(list.get(0));
            } else version=Json.obj(net.json(MR+"/version/"+Util.enc(requested),Net.Policy.UPSTREAM));
            Util.require(Json.list(version,"game_versions").contains(game),"所选资源版本与游戏不兼容");
            if (!loader.isBlank()) Util.require(Json.list(version,"loaders").contains(loader),"所选资源不支持 "+loader);
            for (Object raw : Json.list(version,"dependencies")) {
                Map<String,Object> dep=Json.obj(raw); if (!Json.str(dep,"dependency_type","").equals("required")) continue;
                String p=Json.str(dep,"project_id",""), v=Json.str(dep,"version_id","latest");
                if (p.isBlank() && !v.equals("latest")) {
                    Map<String,Object> metadata=Json.obj(net.json(MR+"/version/"+Util.enc(v),Net.Policy.UPSTREAM)); p=Json.str(metadata,"project_id","");
                }
                if (!p.isBlank()) installRecursive(p,v,source,instance,type,world,job,visited,depth+1);
            }
        }
        Path dir=destination(instance,type,world);
        Map<String,Object> entry=downloadVersion(project,version,source,dir,job);
        String actualProject=Json.str(entry,"project",project);
        Map<String,Object> lock=lock(instance);
        List<Object> installed=new ArrayList<>(Json.list(lock,"files"));
        // Only retire an old file after a new file has passed hash validation.
        for (Object raw : new ArrayList<>(installed)) {
            Map<String,Object> old=Json.obj(raw);
            if (source.equals(old.get("source")) && actualProject.equals(old.get("project")) && !entry.get("file").equals(old.get("file"))) {
                Path previous=Util.within(store.gameDir(instance),Json.str(old,"path",""));
                if (Files.isRegularFile(previous) && Util.hash(previous,Json.str(old,"algorithm","SHA-1")).equalsIgnoreCase(Json.str(old,"hash",""))) store.trash(previous,store.gameDir(instance));
                installed.remove(raw);
            }
        }
        entry.put("path",store.gameDir(instance).relativize(dir.resolve(Json.str(entry,"file",""))).toString().replace('\\','/'));
        entry.put("type",type); entry.put("world",world);
        installed.removeIf(o -> entry.get("path").equals(Json.obj(o).get("path"))); installed.add(entry);
        lock.put("files",installed); Util.atomic(store.instance(instance).resolve("content-lock.json"),Json.pretty(lock));
        ui.note("已安装 "+entry.get("file"));
    }
    public Path destination(String instance,String type,String world) throws IOException {
        Path root=store.gameDir(instance);
        String dir=switch (type) {
            case "mod" -> "mods"; case "shader" -> "shaderpacks"; case "resourcepack" -> "resourcepacks";
            case "datapack" -> "saves/"+Util.fileName(world)+"/datapacks";
            default -> throw new IllegalArgumentException("安装类型: mod | shader | resourcepack | datapack；整合包请用 pack");
        };
        if (type.equals("datapack")) Util.require(Files.isDirectory(root.resolve("saves").resolve(world)),"指定世界不存在");
        Path dest=Util.within(root,dir); Files.createDirectories(dest); return dest;
    }
    public Map<String,Object> downloadVersion(String project,Map<String,Object> v,String source,Path dir,Jobs.Job job) throws Exception {
        String url,file,hash="",algorithm="SHA-1",version,actualProject=project; long size;
        if (source.equals("curseforge")) {
            url=Json.str(v,"downloadUrl",""); file=Util.fileName(Json.str(v,"fileName","")); size=Json.number(v,"fileLength",-1); version=String.valueOf(v.get("id"));
            Util.require(!url.isBlank(),"作者未开放第三方下载，请访问 CurseForge 项目页手动下载后 content import");
            for (Object raw : Json.list(v,"hashes")) {
                Map<String,Object> h=Json.obj(raw); int algo=Json.integer(h,"algo",0);
                if (algo == 1 || hash.isBlank() && algo == 2) { hash=Json.str(h,"value",""); algorithm=algo == 1 ? "SHA-1" : "MD5"; }
            }
        } else {
            List<Object> files=Json.list(v,"files"); Util.require(!files.isEmpty(),"资源没有可下载文件"); Map<String,Object> f=Json.obj(files.get(0));
            for (Object raw : files) if (Json.bool(Json.obj(raw),"primary",false)) f=Json.obj(raw);
            url=Json.str(f,"url",""); file=Util.fileName(Json.str(f,"filename","")); size=Json.number(f,"size",-1);
            Map<String,Object> hashes=Json.child(f,"hashes");
            hash=Json.str(hashes,"sha512",Json.str(hashes,"sha1","")); algorithm=hashes.containsKey("sha512") ? "SHA-512" : "SHA-1";
            version=Json.str(v,"id",""); actualProject=Json.str(v,"project_id",project);
        }
        Util.require(!hash.isBlank(),"资源缺少上游哈希，拒绝无校验安装");
        Path dest=Util.within(dir,file); Net.File download=new Net.File(url,dest,hash,algorithm,size,Net.Policy.UPSTREAM);
        Util.require(!Files.exists(dest) || net.valid(download),"目标已有同名但不同内容的文件: "+file);
        job.stage("获取资源 "+file); net.download(download,job);
        return Json.map("source",source,"project",actualProject,"version",version,"file",file,"hash",hash,"algorithm",algorithm,"url",url,"size",size);
    }
    public Map<String,Object> lock(String instance) throws IOException {
        Path file=store.instance(instance).resolve("content-lock.json");
        return Files.exists(file) ? Json.obj(Json.read(file)) : Json.map("schema",1,"files",new ArrayList<>());
    }
    public synchronized void update(String instance,boolean apply,Jobs.Job job) throws Exception {
        instance=store.selected(instance);
        for (Object raw : new ArrayList<>(Json.list(lock(instance),"files"))) {
            job.check(); Map<String,Object> old=Json.obj(raw);
            String project=Json.str(old,"project",""), source=Json.str(old,"source","modrinth"), type=Json.str(old,"type","mod");
            String loader=type.equals("mod") ? Json.str(store.instanceConfig(instance),"loader","vanilla") : type.equals("datapack") ? "datapack" : "";
            List<Object> list=versions(project,source,baseGame(instance),loader); if (list.isEmpty()) continue;
            Map<String,Object> latest=Json.obj(list.get(0)); String id=String.valueOf(latest.get("id"));
            if (!id.equals(String.valueOf(old.get("version")))) {
                ui.note(Json.str(old,"file","")+" → "+Json.str(latest,"version_number",Json.str(latest,"displayName",id)));
                if (apply) {
                    Path previous=Util.within(store.gameDir(instance),Json.str(old,"path",""));
                    Util.require(!Files.exists(previous) || Util.hash(previous,Json.str(old,"algorithm","SHA-1")).equalsIgnoreCase(Json.str(old,"hash","")),"资源已手动修改，跳过以免覆盖: "+previous);
                    installRecursive(project,id,source,instance,type,Json.str(old,"world",""),job,new HashSet<>(),0);
                }
            }
        }
    }
    public List<Object> local(String instance) throws IOException {
        Path mods=destination(instance,"mod",""); List<Object> list=new ArrayList<>();
        for (Path file : Util.children(mods)) if (Files.isRegularFile(file) && (file.toString().endsWith(".jar") || file.toString().endsWith(".jar.disabled"))) {
            Map<String,Object> row=Json.map("file",file.getFileName().toString(),"enabled",file.toString().endsWith(".jar"),"size",Files.size(file),"id","","name","","version","");
            try (ZipFile jar=new ZipFile(file.toFile())) {
                if (jar.getEntry("fabric.mod.json") != null) {
                    Map<String,Object> meta=Json.obj(Json.parse(readEntry(jar,"fabric.mod.json")));
                    for (String k : List.of("id","name","version")) row.put(k,Json.str(meta,k,""));
                    row.put("depends",Json.child(meta,"depends"));
                } else if (jar.getEntry("quilt.mod.json") != null) {
                    Map<String,Object> meta=Json.child(Json.obj(Json.parse(readEntry(jar,"quilt.mod.json"))),"quilt_loader");
                    for (String k : List.of("id","version")) row.put(k,Json.str(meta,k,"")); row.put("name",Json.str(Json.child(meta,"metadata"),"name",""));
                } else {
                    String entry=jar.getEntry("META-INF/neoforge.mods.toml") != null ? "META-INF/neoforge.mods.toml" : "META-INF/mods.toml";
                    if (jar.getEntry(entry) != null) {
                        String text=readEntry(jar,entry);
                        for (var e : Map.of("id","modId","name","displayName","version","version").entrySet()) {
                            Matcher m=Pattern.compile("(?m)^\\s*"+e.getValue()+"\\s*=\\s*[\"']([^\"']+)").matcher(text);
                            if (m.find()) row.put(e.getKey(),m.group(1));
                        }
                    } else if (jar.getEntry("mcmod.info") != null) row.put("metadata",Json.parse(readEntry(jar,"mcmod.info")));
                }
            } catch (Exception e) { row.put("warning","无法读取 Mod 元数据"); }
            list.add(row);
        }
        return list;
    }
    private String readEntry(ZipFile jar,String path) throws IOException {
        try (InputStream in=jar.getInputStream(jar.getEntry(path))) {
            byte[] data=in.readNBytes(2*1024*1024+1); Util.require(data.length <= 2*1024*1024,"元数据过大"); return new String(data,StandardCharsets.UTF_8);
        }
    }
    public void toggle(String instance,String file,boolean enable) throws IOException {
        Path mods=destination(instance,"mod",""), from=Util.within(mods,Util.fileName(file));
        if (enable) Util.require(file.endsWith(".jar.disabled"),"启用需要 .jar.disabled 文件"); else Util.require(file.endsWith(".jar"),"禁用需要 .jar 文件");
        Path to=mods.resolve(enable ? file.substring(0,file.length()-9) : file+".disabled");
        Util.require(!Files.exists(to),"目标已存在"); Util.move(from,to,false);
    }
    public void importLocal(String instance,String type,Path input,String world) throws Exception {
        Util.require(Files.isRegularFile(input),"输入文件不存在");
        String filename=Util.fileName(input.getFileName().toString());
        if (type.equals("mod")) Util.require(filename.endsWith(".jar"),"Mod 必须为 jar");
        Path dest=destination(instance,type,world).resolve(filename); Util.require(!Files.exists(dest),"同名文件已存在");
        Files.copy(input,dest); ui.note("已导入 "+filename+" · SHA-256 "+Util.hash(dest,"SHA-256"));
    }
}
