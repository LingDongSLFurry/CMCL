package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class Packs {
    private final Store store;
    private final Net net;
    private final Minecraft mc;
    private final Loaders loaders;
    private final Content content;
    private final Ui ui;
    public Packs(Store store,Net net,Minecraft mc,Loaders loaders,Content content,Ui ui) {
        this.store=store; this.net=net; this.mc=mc; this.loaders=loaders; this.content=content; this.ui=ui;
    }
    public synchronized void install(Path archive,String name,boolean optional,Jobs.Job job) throws Exception {
        Util.id(name); Util.require(Files.isRegularFile(archive),"整合包文件不存在");
        Util.require(!Files.exists(store.instance(name)),"实例已存在: "+name);
        String tempName="import-"+UUID.randomUUID().toString().substring(0,12);
        Path extracted=Files.createTempDirectory(store.cache,"pack-");
        boolean success=false;
        try {
            job.stage("安全解压整合包"); Util.unzip(archive,extracted); job.check();
            Path root=findRoot(extracted);
            if (Files.exists(root.resolve("modrinth.index.json"))) installModrinth(root,tempName,optional,job);
            else if (Files.exists(root.resolve("manifest.json"))) installCurse(root,tempName,job);
            else if (Files.exists(root.resolve("cairn-pack.json"))) installCairn(root,tempName,job);
            else if (Files.exists(root.resolve("mmc-pack.json"))) installMultiMc(root,tempName,job);
            else if (Files.exists(root.resolve("mcbbs.packmeta"))) installAddons(root,root.resolve("mcbbs.packmeta"),tempName,job);
            else if (Files.exists(root.resolve("modpack.json"))) installAddons(root,root.resolve("modpack.json"),tempName,job);
            else installRaw(root,tempName,job);
            Map<String,Object> config=store.instanceConfig(tempName); config.put("id",name);
            config.put("importedFrom",archive.getFileName().toString());
            store.saveInstance(tempName,config);
            job.check();
            Util.move(store.instance(tempName),store.instance(name),false);
            config.put("id",name); store.saveInstance(name,config);
            if (store.text("selected").equals(tempName) || store.text("selected").isBlank()) store.set("selected",name);
            success=true; ui.note("整合包安装完成: "+name);
        } finally {
            // Keep extraction and failed instances recoverable; never recursively delete user data.
            if (!success && Files.exists(store.instance(tempName))) {
                store.trash(store.instance(tempName),store.instances);
                if (store.text("selected").equals(tempName)) store.set("selected","");
            }
            cleanup(extracted);
        }
    }
    private Path findRoot(Path extracted) throws IOException {
        Set<String> markers=Set.of("modrinth.index.json","manifest.json","cairn-pack.json","mmc-pack.json","mcbbs.packmeta","modpack.json");
        List<Path> candidates;
        try (var walk=Files.walk(extracted,4)) { candidates=walk.filter(p -> markers.contains(p.getFileName().toString())).map(Path::getParent).distinct().toList(); }
        if (candidates.contains(extracted)) return extracted;
        if (candidates.size() == 1) return candidates.get(0);
        Util.require(candidates.isEmpty(),"压缩包内含多个整合包描述，无法判断根目录");
        List<Path> children=Util.children(extracted);
        return children.size() == 1 && Files.isDirectory(children.get(0)) ? children.get(0) : extracted;
    }
    private String prepare(String game,Map<String,String> mods,String instance,Jobs.Job job) throws Exception {
        String base=mc.install(game,null,job), version=base, loader="vanilla";
        for (String kind : List.of("forge","neoforge","fabric","quilt","liteloader")) if (mods.containsKey(kind)) {
            Util.require(loader.equals("vanilla"),"整合包声明了多个冲突加载器");
            version=loaders.install(kind,base,mods.get(kind),null,job); loader=kind;
        }
        mc.createInstance(instance,version,loader); return version;
    }
    private void installModrinth(Path root,String instance,boolean optional,Jobs.Job job) throws Exception {
        Map<String,Object> index=Json.obj(Json.read(root.resolve("modrinth.index.json")));
        Util.require(Json.integer(index,"formatVersion",0) == 1 && Json.str(index,"game","").equals("minecraft"),"不支持的 Modrinth 整合包格式");
        Map<String,Object> deps=Json.child(index,"dependencies"); Map<String,String> mods=new LinkedHashMap<>();
        for (var e : Map.of("fabric-loader","fabric","quilt-loader","quilt","forge","forge","neoforge","neoforge").entrySet())
            if (deps.containsKey(e.getKey())) mods.put(e.getValue(),deps.get(e.getKey()).toString());
        prepare(Json.str(deps,"minecraft",""),mods,instance,job);
        List<Net.File> downloads=new ArrayList<>();
        Path game=store.gameDir(instance);
        for (Object raw : Json.list(index,"files")) {
            Map<String,Object> f=Json.obj(raw); String client=Json.str(Json.child(f,"env"),"client","required");
            if (client.equals("unsupported") || client.equals("optional") && !optional) continue;
            String relative=Json.str(f,"path",""); Path dest=Util.within(game,relative);
            Map<String,Object> hashes=Json.child(f,"hashes");
            String hash=Json.str(hashes,"sha512",Json.str(hashes,"sha1","")); Util.require(!hash.isBlank(),"整合包文件缺少哈希");
            List<Object> urls=Json.list(f,"downloads"); Util.require(!urls.isEmpty(),"整合包文件没有下载地址");
            downloads.add(new Net.File(urls.get(0).toString(),dest,hash,hashes.containsKey("sha512") ? "SHA-512" : "SHA-1",Json.number(f,"fileSize",-1),Net.Policy.UPSTREAM));
        }
        job.stage("下载整合包资源"); net.downloadAll(downloads,job);
        override(root.resolve("overrides"),game); override(root.resolve("client-overrides"),game);
        Map<String,Object> m=store.instanceConfig(instance); m.put("description",Json.str(index,"summary",Json.str(index,"name",""))); store.saveInstance(instance,m);
    }
    private void installCurse(Path root,String instance,Jobs.Job job) throws Exception {
        Map<String,Object> index=Json.obj(Json.read(root.resolve("manifest.json")));
        Util.require(Json.str(index,"manifestType","minecraftModpack").equals("minecraftModpack"),"不支持的 CurseForge manifest");
        Map<String,Object> minecraft=Json.child(index,"minecraft"); Map<String,String> mods=new LinkedHashMap<>();
        for (Object raw : Json.list(minecraft,"modLoaders")) {
            Map<String,Object> loader=Json.obj(raw); String id=Json.str(loader,"id",""); int split=id.indexOf('-');
            if (split > 0) mods.put(id.substring(0,split).replace("fabric-loader","fabric"),id.substring(split+1));
        }
        prepare(Json.str(minecraft,"version",""),mods,instance,job);
        for (Object raw : Json.list(index,"files")) {
            Map<String,Object> f=Json.obj(raw); job.check();
            content.install(String.valueOf(f.get("projectID")),String.valueOf(f.get("fileID")),"curseforge",instance,"mod","",job);
        }
        String folder=Json.str(index,"overrides","overrides"); override(Util.within(root,folder),store.gameDir(instance));
    }
    private void installCairn(Path root,String instance,Jobs.Job job) throws Exception {
        Map<String,Object> index=Json.obj(Json.read(root.resolve("cairn-pack.json")));
        String base=mc.install(Json.str(index,"minecraft",""),null,job);
        Path shared=root.resolve("minecraft");
        if (Files.isDirectory(shared.resolve("libraries"))) override(shared.resolve("libraries"),store.game.resolve("libraries"),false);
        if (Files.isDirectory(shared.resolve("versions"))) override(shared.resolve("versions"),store.game.resolve("versions"),false);
        String version=Json.str(index,"version",base); mc.repair(mc.resolve(version),job);
        mc.createInstance(instance,version,Json.str(index,"loader","vanilla"));
        override(root.resolve("overrides"),store.gameDir(instance));
        Map<String,Object> config=store.instanceConfig(instance);
        config.put("description",Json.str(index,"description","")); Map<String,Object> settings=new LinkedHashMap<>();
        for (var e : Json.child(index,"settings").entrySet()) if (Set.of("memoryMiB","minMemoryMiB","width","height","fullscreen","gc","customInfo").contains(e.getKey())) settings.put(e.getKey(),e.getValue());
        config.put("settings",settings); store.saveInstance(instance,config);
        if (Files.exists(root.resolve("content-lock.json"))) Files.copy(root.resolve("content-lock.json"),store.instance(instance).resolve("content-lock.json"));
    }
    private void installMultiMc(Path root,String instance,Jobs.Job job) throws Exception {
        Map<String,Object> index=Json.obj(Json.read(root.resolve("mmc-pack.json"))); String game=""; Map<String,String> mods=new LinkedHashMap<>();
        for (Object raw : Json.list(index,"components")) {
            Map<String,Object> c=Json.obj(raw); String uid=Json.str(c,"uid",""), ver=Json.str(c,"version","");
            if (uid.equals("net.minecraft")) game=ver;
            String kind=switch (uid) { case "net.minecraftforge" -> "forge"; case "net.neoforged" -> "neoforge"; case "net.fabricmc.fabric-loader" -> "fabric"; case "org.quiltmc.quilt-loader" -> "quilt"; case "com.mumfrey.liteloader" -> "liteloader"; default -> ""; };
            if (!kind.isBlank()) mods.put(kind,ver);
        }
        Util.require(!game.isBlank(),"MultiMC 描述缺少 net.minecraft"); prepare(game,mods,instance,job);
        Path overrides=Files.isDirectory(root.resolve(".minecraft")) ? root.resolve(".minecraft") : root.resolve("minecraft");
        override(overrides,store.gameDir(instance));
    }
    private void installAddons(Path root,Path meta,String instance,Jobs.Job job) throws Exception {
        Map<String,Object> index=Json.obj(Json.read(meta)); String game=Json.str(index,"gameVersion",Json.str(index,"minecraftVersion","")); Map<String,String> mods=new LinkedHashMap<>();
        for (Object raw : Json.list(index,"addons")) {
            Map<String,Object> a=Json.obj(raw); String id=Json.str(a,"id","").toLowerCase(Locale.ROOT), version=Json.str(a,"version","");
            if (id.equals("game") || id.equals("minecraft")) game=version;
            if (Set.of("forge","neoforge","fabric","quilt","liteloader").contains(id)) mods.put(id,version);
        }
        Util.require(!game.isBlank(),"整合包未声明 Minecraft 版本"); prepare(game,mods,instance,job);
        Path overrides=root.resolve(Json.str(index,"overrides","overrides"));
        Util.require(overrides.normalize().startsWith(root),"整合包 overrides 越界");
        if (!Files.isDirectory(overrides)) overrides=root.resolve("minecraft");
        override(overrides,store.gameDir(instance));
    }
    private void installRaw(Path root,String instance,Jobs.Job job) throws Exception {
        Path minecraft=Files.isDirectory(root.resolve(".minecraft")) ? root.resolve(".minecraft") : root;
        Path versions=minecraft.resolve("versions"); Util.require(Files.isDirectory(versions),"未识别整合包格式");
        List<Path> profiles=new ArrayList<>();
        try (var walk=Files.walk(versions,2)) { walk.filter(p -> p.toString().endsWith(".json")).forEach(profiles::add); }
        Util.require(!profiles.isEmpty(),"压缩包内没有版本描述");
        Set<String> parents=new HashSet<>(); Map<String,Map<String,Object>> descriptors=new LinkedHashMap<>();
        for (Path p : profiles) {
            Map<String,Object> d=Json.obj(Json.read(p)); String id=Json.str(d,"id",""); Util.fileName(id);
            descriptors.put(id,d); if (d.containsKey("inheritsFrom")) parents.add(d.get("inheritsFrom").toString());
        }
        List<String> leaves=descriptors.keySet().stream().filter(s -> !parents.contains(s)).toList();
        Util.require(leaves.size() == 1,"压缩包包含多个可启动版本，请拆分或使用 instance adopt 导入");
        String version=leaves.get(0), base=version; Set<String> seen=new HashSet<>();
        while (descriptors.containsKey(base) && seen.add(base)) {
            String parent=Json.str(descriptors.get(base),"inheritsFrom",""); if (parent.isBlank()) break; base=parent;
        }
        mc.install(base,null,job);
        for (var e : descriptors.entrySet()) if (!e.getKey().equals(base)) Util.atomic(mc.versionFile(e.getKey()),Json.pretty(e.getValue()));
        if (Files.isDirectory(minecraft.resolve("libraries"))) override(minecraft.resolve("libraries"),store.game.resolve("libraries"),false);
        for (Path dir : Util.children(versions)) {
            if (!Files.isDirectory(dir)) continue;
            String id=dir.getFileName().toString(); if (id.equals(base)) continue;
            Path jar=dir.resolve(id+".jar"); if (Files.exists(jar)) copyFile(jar,mc.versionDir(id).resolve(id+".jar"));
        }
        mc.repair(mc.resolve(version),job); mc.createInstance(instance,version,"custom");
        for (Path p : Util.children(minecraft)) if (!Set.of("versions","libraries","assets","PCL","HMCL","launcher_profiles.json","launcher_accounts.json","accounts.json").contains(p.getFileName().toString()))
            if (Files.isDirectory(p)) override(p,store.gameDir(instance).resolve(p.getFileName())); else copyFile(p,store.gameDir(instance).resolve(p.getFileName()));
    }
    public synchronized void export(String instance,Path target,boolean includeSaves,Jobs.Job job) throws Exception {
        instance=store.selected(instance); Util.require(!Files.exists(target),"输出文件已存在");
        Path stage=Files.createTempDirectory(store.cache,"export-");
        try {
            Map<String,Object> config=store.instanceConfig(instance); String version=Json.str(config,"version",""), base=content.baseGame(instance);
            Path game=store.gameDir(instance);
            Map<String,Object> safeSettings=new LinkedHashMap<>();
            for (var e : Json.child(config,"settings").entrySet()) if (Set.of("memoryMiB","minMemoryMiB","width","height","fullscreen","gc","customInfo").contains(e.getKey())) safeSettings.put(e.getKey(),e.getValue());
            Map<String,Object> meta=Json.map("format",1,"name",instance,"minecraft",base,"version",version,"loader",Json.str(config,"loader","vanilla"),
                    "description",Json.str(config,"description",""),"settings",safeSettings);
            Util.atomic(stage.resolve("cairn-pack.json"),Json.pretty(meta));
            job.stage("打包游戏内容");
            for (Path p : Util.children(game)) {
                String name=p.getFileName().toString();
                if (Set.of("logs","crash-reports","session.lock","usercache.json","usernamecache.json").contains(name) || name.startsWith("hs_err_pid") || name.equals("saves") && !includeSaves) continue;
                if (Files.isDirectory(p)) Util.copyTree(p,stage.resolve("overrides").resolve(name)); else copyFile(p,stage.resolve("overrides").resolve(name));
            }
            // Preserve loader-generated libraries and profiles; fetch the base client from Mojang on import.
            Set<String> seen=new HashSet<>(); String id=version;
            while (!id.equals(base) && seen.add(id)) {
                Path dest=stage.resolve("minecraft/versions").resolve(id); Files.createDirectories(dest);
                copyFile(mc.versionFile(id),dest.resolve(id+".json"));
                Path jar=mc.versionDir(id).resolve(id+".jar"); if (Files.exists(jar)) copyFile(jar,dest.resolve(id+".jar"));
                id=Json.str(Json.obj(Json.read(mc.versionFile(id))),"inheritsFrom",base);
            }
            for (Minecraft.Library lib : mc.libraries(mc.resolve(version))) {
                Path relative=store.game.resolve("libraries").relativize(lib.file().path());
                if (Files.isRegularFile(lib.file().path())) copyFile(lib.file().path(),stage.resolve("minecraft/libraries").resolve(relative));
            }
            Path lock=store.instance(instance).resolve("content-lock.json"); if (Files.exists(lock)) copyFile(lock,stage.resolve("content-lock.json"));
            job.check(); Util.zip(stage,target,p -> true);
            ui.note("整合包已导出: "+target+" · SHA-256 "+Util.hash(target,"SHA-256"));
        } finally { cleanup(stage); }
    }
    private void cleanup(Path path) {
        if (!Files.exists(path)) return;
        try { store.trash(path,store.cache); }
        catch (IOException e) { ui.note("临时目录暂时被占用，已保留，可稍后 cache clean: "+path); }
    }
    private void override(Path from,Path to) throws IOException {
        override(from,to,true);
    }
    private void override(Path from,Path to,boolean replace) throws IOException {
        if (!Files.isDirectory(from)) return;
        Files.createDirectories(to);
        try (var walk=Files.walk(from)) {
            for (Path p : walk.toList()) {
                Util.require(!Files.isSymbolicLink(p),"整合包不允许链接");
                if (p.equals(from)) continue;
                Path dest=Util.within(to,from.relativize(p).toString().replace('\\','/'));
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else if (replace) { Files.createDirectories(dest.getParent()); Files.copy(p,dest,StandardCopyOption.REPLACE_EXISTING); }
                else copyFile(p,dest);
            }
        }
    }
    private void copyFile(Path from,Path to) throws IOException {
        Util.require(Files.isRegularFile(from) && !Files.isSymbolicLink(from),"只允许普通文件");
        Files.createDirectories(to.getParent());
        if (Files.exists(to)) {
            // Imported packs must not silently replace shared libraries or installed manifests.
            Util.require(Util.hash(from,"SHA-256").equals(Util.hash(to,"SHA-256")),"导入内容与已有文件冲突: "+to);
        } else Files.copy(from,to);
    }
}
