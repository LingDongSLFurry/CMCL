package dev.cairn;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Instances {
    private final Store store;
    private final Minecraft mc;
    private final Launch launch;
    private final Ui ui;
    public Instances(Store store,Minecraft mc,Launch launch,Ui ui) { this.store=store; this.mc=mc; this.launch=launch; this.ui=ui; }
    public void idle(String name) throws Exception {
        for (Map<String,Object> s : launch.sessions()) Util.require(!name.equals(s.get("instance")) || !Launch.alive(s),"请先停止正在运行的实例: "+name);
    }
    public synchronized void clone(String from,String to,boolean saves) throws Exception {
        from=store.selected(from); idle(from); Util.id(to); Util.require(!Files.exists(store.instance(to)),"目标实例已存在");
        Map<String,Object> old=store.instanceConfig(from), copy=Json.obj(Json.copy(old));
        Path dest=store.instance(to); Files.createDirectories(dest);
        try {
            Path source=store.gameDir(from); Files.createDirectories(dest.resolve("game"));
            for (Path p : Util.children(source)) {
                String name=p.getFileName().toString();
                if (Set.of("logs","crash-reports","session.lock").contains(name) || name.equals("saves") && !saves) continue;
                if (Files.isDirectory(p)) Util.copyTree(p,dest.resolve("game").resolve(name)); else Files.copy(p,dest.resolve("game").resolve(name));
            }
            copy.put("favorite",false); copy.put("hidden",false); copy.put("created",java.time.Instant.now().toString());
            Map<String,Object> settings=new LinkedHashMap<>(Json.child(copy,"settings")); settings.put("isolation",true); copy.put("settings",settings);
            store.saveInstance(to,copy);
            Path lock=store.instance(from).resolve("content-lock.json"); if (Files.exists(lock)) Files.copy(lock,dest.resolve("content-lock.json"));
        } catch (Exception e) { store.trash(dest,store.instances); throw e; }
        ui.note("实例已复制: "+from+" → "+to);
    }
    public synchronized void rename(String from,String to) throws Exception {
        from=store.selected(from); idle(from); Util.id(to); Util.require(!Files.exists(store.instance(to)),"目标实例已存在");
        Map<String,Object> config=store.instanceConfig(from);
        Util.move(store.instance(from),store.instance(to),false); store.saveInstance(to,config);
        if (store.text("selected").equals(from)) store.set("selected",to);
    }
    public synchronized void remove(String name) throws Exception {
        name=store.selected(name); idle(name); store.instanceConfig(name);
        Path dest=store.trash(store.instance(name),store.instances);
        if (store.text("selected").equals(name)) store.set("selected","");
        ui.note("实例已移到回收区，可用 trash restore 恢复: "+dest.getFileName());
    }
    public void rootAdd(String name,Path path) throws IOException {
        Util.id(name); Path absolute=path.toAbsolutePath().normalize();
        Util.require(Files.isDirectory(absolute.resolve("versions")),"不是 Minecraft 游戏根目录（缺少 versions）");
        Map<String,Object> roots=Json.child(store.config(),"roots"); roots.put(name,absolute.toString()); store.set("roots",roots);
    }
    public List<Object> rootVersions(String name) throws IOException {
        Path root=root(name); List<Object> result=new ArrayList<>();
        for (Path p : Util.children(root.resolve("versions"))) if (Files.isRegularFile(p.resolve(p.getFileName()+".json"))) result.add(Json.map("id",p.getFileName().toString(),"path",p.toString()));
        return result;
    }
    private Path root(String name) {
        Map<String,Object> roots=Json.child(store.config(),"roots"); Util.require(roots.containsKey(name),"未登记根目录: "+name);
        return Path.of(roots.get(name).toString());
    }
    public synchronized void adopt(String rootName,String version,String instance,Jobs.Job job) throws Exception {
        Path root=root(rootName); Util.require(!Files.exists(store.instance(instance)),"实例已存在");
        String current=version; Set<String> seen=new HashSet<>();
        while (seen.add(current)) {
            Util.fileName(current); Path source=Util.within(root.resolve("versions"),current+"/"+current+".json");
            Util.require(Files.isRegularFile(source),"外部目录缺少版本描述: "+current);
            Map<String,Object> d=Json.obj(Json.read(source)); Path target=mc.versionFile(current);
            copyUnchanged(source,target);
            Path jar=source.resolveSibling(current+".jar"); if (Files.isRegularFile(jar)) copyUnchanged(jar,mc.versionDir(current).resolve(current+".jar"));
            String parent=Json.str(d,"inheritsFrom",""); if (parent.isBlank()) break; current=parent;
            Util.require(seen.size() < 32,"版本继承循环");
        }
        Minecraft.Version v=mc.resolve(version);
        for (Minecraft.Library lib : mc.libraries(v)) {
            Path relative=store.game.resolve("libraries").relativize(lib.file().path());
            Path source=Util.within(root.resolve("libraries"),relative.toString().replace('\\','/'));
            if (Files.isRegularFile(source)) copyUnchanged(source,lib.file().path());
        }
        mc.repair(v,job); mc.createInstance(instance,version,detectLoader(v));
        Path oldGame=root.resolve("versions").resolve(version), game=store.gameDir(instance);
        for (String folder : List.of("mods","config","saves","resourcepacks","shaderpacks","screenshots")) {
            Path from=oldGame.resolve(folder); if (Files.isDirectory(from)) Util.copyTree(from,game.resolve(folder));
        }
        for (String file : List.of("options.txt","servers.dat")) if (Files.isRegularFile(oldGame.resolve(file))) Files.copy(oldGame.resolve(file),game.resolve(file));
        ui.note("已复制导入，外部目录未作写入: "+instance);
    }
    private String detectLoader(Minecraft.Version v) {
        String text=Json.stringify(v.data()).toLowerCase(Locale.ROOT);
        if (text.contains("neoforged")) return "neoforge"; if (text.contains("minecraftforge")) return "forge";
        if (text.contains("quiltmc")) return "quilt"; if (text.contains("fabricmc")) return "fabric"; if (text.contains("liteloader")) return "liteloader"; return "vanilla";
    }
    private void copyUnchanged(Path from,Path to) throws IOException {
        Util.require(!Files.isSymbolicLink(from),"不导入符号链接");
        if (Files.exists(to)) Util.require(Util.hash(from,"SHA-256").equals(Util.hash(to,"SHA-256")),"本地版本/依赖冲突: "+to);
        else { Files.createDirectories(to.getParent()); Files.copy(from,to); }
    }
    public List<Object> trashList() throws IOException {
        List<Object> result=new ArrayList<>();
        for (Path p : Util.children(store.trash)) if (p.toString().endsWith(".receipt.json")) {
            Map<String,Object> m=Json.obj(Json.read(p)); m.put("name",p.getFileName().toString().replace(".receipt.json","")); result.add(m);
        }
        return result;
    }
    public synchronized void restore(String name) throws Exception {
        Util.fileName(name); Path receipt=Util.within(store.trash,name+".receipt.json");
        Util.require(Files.isRegularFile(receipt),"回收记录不存在");
        Map<String,Object> r=Json.obj(Json.read(receipt)); Path from=Path.of(Json.str(r,"to","")), to=Path.of(Json.str(r,"from",""));
        Util.require(from.normalize().equals(store.trash.resolve(name).normalize()),"回收记录目标不一致");
        Path home=store.home.toAbsolutePath().normalize(), target=to.toAbsolutePath().normalize();
        Util.require(target.startsWith(home) && !target.equals(home),"恢复目标越界");
        Util.within(home,home.relativize(target).toString().replace('\\','/'));
        Util.require(Files.exists(from) && !Files.exists(to),"原位置已有内容或回收文件不存在");
        Util.move(from,to,false);
        Files.delete(receipt); ui.note("已恢复: "+to);
    }
}
