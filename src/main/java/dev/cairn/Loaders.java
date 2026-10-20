package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.net.URLClassLoader;
import java.net.URI;

public final class Loaders {
    private final Store store;
    private final Net net;
    private final Minecraft mc;
    private final JavaRuntime java;
    private final Ui ui;
    public Loaders(Store store,Net net,Minecraft mc,JavaRuntime java,Ui ui) { this.store=store; this.net=net; this.mc=mc; this.java=java; this.ui=ui; }
    public List<Object> versions(String loader,String game) throws Exception {
        if (loader.equals("fabric") || loader.equals("quilt")) {
            String host=loader.equals("fabric") ? "https://meta.fabricmc.net/v2/" : "https://meta.quiltmc.org/v3/";
            return Json.arr(net.cachedJson(host+"versions/loader/"+Util.enc(game),Net.Policy.UPSTREAM,false));
        }
        if (loader.equals("forge") || loader.equals("neoforge")) {
            boolean legacyNeo=loader.equals("neoforge") && game.equals("1.20.1");
            String url=loader.equals("forge") ? "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
                    : "https://maven.neoforged.net/releases/net/neoforged/"+(legacyNeo ? "forge" : "neoforge")+"/maven-metadata.xml";
            String xml=net.text(url,Net.Policy.UPSTREAM);
            Matcher m=Pattern.compile("<version>([^<]+)</version>").matcher(xml); List<Object> list=new ArrayList<>();
            while (m.find()) {
                String v=m.group(1);
                boolean match=loader.equals("forge") || legacyNeo ? v.startsWith(game+"-") : neoGameMatches(game,v);
                if (match) list.add(Json.map("version",v));
            }
            Collections.reverse(list); return list;
        }
        if (loader.equals("liteloader")) {
            Map<String,Object> root=Json.obj(net.cachedJson("https://dl.liteloader.com/versions/versions.json",Net.Policy.UPSTREAM,false));
            Map<String,Object> v=Json.child(Json.child(root,"versions"),game);
            Map<String,Object> builds=Json.child(Json.child(v,"artefacts"),"com.mumfrey:liteloader");
            if (builds.isEmpty()) builds=Json.child(Json.child(v,"snapshots"),"com.mumfrey:liteloader");
            Map<String,Object> unique=new LinkedHashMap<>();
            if (builds.containsKey("latest")) unique.put("latest",builds.get("latest"));
            for (var e : builds.entrySet()) if (!e.getKey().equals("latest")) {
                String version=Json.str(Json.obj(e.getValue()),"version","");
                if (unique.values().stream().noneMatch(o -> version.equals(Json.obj(o).get("version")))) unique.put(e.getKey(),e.getValue());
            }
            List<Object> result=new ArrayList<>();
            for (Object raw : unique.values()) {
                Map<String,Object> entry=Json.obj(Json.copy(raw)); entry.put("repository",Json.str(Json.child(v,"repo"),"url","https://dl.liteloader.com/repo/"));
                List<Object> common=Json.list(Json.child(v,"snapshots"),"libraries");
                if (common.isEmpty()) common=Json.list(Json.child(v,"artefacts"),"libraries");
                entry.put("commonLibraries",common);
                result.add(entry);
            }
            return result;
        }
        if (loader.equals("optifine")) {
            String html=net.text("https://optifine.net/downloads",Net.Policy.UPSTREAM);
            Matcher m=Pattern.compile("(?:adloadx|downloadx)\\?f=([^\"'<> &]+)").matcher(html); Map<String,Object> found=new LinkedHashMap<>();
            while (m.find()) {
                String file=m.group(1);
                if (file.startsWith("OptiFine_"+game+"_") || file.startsWith("preview_OptiFine_"+game+"_"))
                    found.putIfAbsent(file,Json.map("version",file,"filename",file,"stable",!file.startsWith("preview_")));
            }
            return new ArrayList<>(found.values());
        }
        throw new IllegalArgumentException("加载器: fabric | quilt | forge | neoforge | liteloader | optifine");
    }
    private boolean neoGameMatches(String game,String version) {
        String[] g=game.split("\\."); if (g.length < 2) return false;
        if (!g[0].equals("1")) return version.startsWith(game+".");
        String prefix=g[1]+"."+(g.length >= 3 ? g[2] : "0")+".";
        return version.startsWith(prefix);
    }
    public synchronized String install(String loader,String game,String version,String instance,Jobs.Job job) throws Exception {
        game=mc.canonical(game);
        if (!Files.exists(mc.versionFile(game))) mc.install(game,null,job);
        String profile;
        if (loader.equals("fabric") || loader.equals("quilt")) {
            String host=loader.equals("fabric") ? "https://meta.fabricmc.net/v2/" : "https://meta.quiltmc.org/v3/";
            if (version.equals("latest") || version.isBlank()) {
                List<Object> list=versions(loader,game); Util.require(!list.isEmpty(),"此游戏版本没有加载器");
                Map<String,Object> chosen=Json.obj(list.get(0));
                for (Object raw : list) {
                    Map<String,Object> item=Json.obj(raw), descriptor=Json.child(item,"loader");
                    String name=Json.str(descriptor,"version","");
                    if (Json.bool(descriptor,"stable",!name.contains("-"))) { chosen=item; break; }
                }
                version=Json.str(Json.child(chosen,"loader"),"version","");
            }
            Util.id(version);
            Map<String,Object> data=Json.obj(net.json(host+"versions/loader/"+Util.enc(game)+"/"+Util.enc(version)+"/profile/json",Net.Policy.UPSTREAM));
            profile=Json.str(data,"id",""); Util.fileName(profile);
            Util.require(!profile.isBlank() && game.equals(Json.str(data,"inheritsFrom","")),"加载器描述与游戏版本不符");
            Util.atomic(mc.versionFile(profile),Json.pretty(data)); mc.repair(mc.resolve(profile),job);
        } else if (loader.equals("forge") || loader.equals("neoforge")) {
            if (version.equals("latest") || version.isBlank()) {
                List<Object> list=versions(loader,game); Util.require(!list.isEmpty(),"此游戏版本没有加载器");
                version=Json.str(Json.obj(list.get(0)),"version","");
            }
            boolean legacyNeo=loader.equals("neoforge") && game.equals("1.20.1");
            if ((loader.equals("forge") || legacyNeo) && !version.startsWith(game+"-")) version=game+"-"+version;
            Util.id(version);
            String path=loader.equals("forge") ? "net/minecraftforge/forge/"+version+"/forge-"+version+"-installer.jar"
                    : legacyNeo ? "net/neoforged/forge/"+version+"/forge-"+version+"-installer.jar"
                    : "net/neoforged/neoforge/"+version+"/neoforge-"+version+"-installer.jar";
            String host=loader.equals("forge") ? "https://maven.minecraftforge.net/" : "https://maven.neoforged.net/releases/";
            Path installer=store.cache.resolve(loader+"-"+version+"-installer.jar");
            job.stage("下载 "+loader+" 官方安装器"); net.download(new Net.File(host+path,installer,"",-1,Net.Policy.UPSTREAM),job);
            profile=installLocal(loader,installer,game,job);
        } else if (loader.equals("optifine")) {
            List<Object> list=versions(loader,game);
            if (version.equals("latest") || version.isBlank()) {
                Util.require(!list.isEmpty(),"OptiFine 官方页面没有此 MC 版本");
                Map<String,Object> selected=Json.obj(list.get(0));
                for (Object raw : list) if (Json.bool(Json.obj(raw),"stable",false)) { selected=Json.obj(raw); break; }
                version=Json.str(selected,"filename","");
            }
            String filename=Util.fileName(version);
            Util.require(filename.startsWith("OptiFine_"+game+"_") || filename.startsWith("preview_OptiFine_"+game+"_"),"OptiFine 文件名与游戏版本不符");
            String html=net.text("https://optifine.net/adloadx?f="+Util.enc(filename),Net.Policy.UPSTREAM);
            Matcher link=Pattern.compile("href=[\"']([^\"']*downloadx\\?[^\"']+)[\"']").matcher(html);
            Util.require(link.find(),"OptiFine 官方页面未返回下载链接；请手动下载后使用 loader local");
            String href=link.group(1).replace("&amp;","&");
            URI url=URI.create("https://optifine.net/").resolve(href);
            Util.require("optifine.net".equals(url.getHost()),"OptiFine 下载跳转到非官网");
            Path installer=store.cache.resolve(filename); net.download(new Net.File(url.toString(),installer,"",-1,Net.Policy.UPSTREAM),job);
            profile=installLocal(loader,installer,game,job);
        } else if (loader.equals("liteloader")) {
            List<Object> list=versions(loader,game); Util.require(!list.isEmpty(),"LiteLoader 上游没有此版本");
            Map<String,Object> chosen=Json.obj(list.get(0));
            if (!version.equals("latest") && !version.isBlank()) {
                Map<String,Object> match=null;
                for (Object raw : list) { Map<String,Object> item=Json.obj(raw); if (version.equals(item.get("version")) || version.equals(item.get("build"))) match=item; }
                Util.require(match != null,"没有此 LiteLoader 版本: "+version); chosen=match;
            }
            String coordinate="com.mumfrey:liteloader:"+Json.str(chosen,"version","");
            String repo=Util.urlBase(Net.uri(Json.str(chosen,"repository",""),Net.Policy.UPSTREAM).toString());
            String artifactPath=Minecraft.mavenPath(coordinate), artifactUrl=repo+artifactPath;
            String checksum=Json.str(chosen,"md5",""),checksumAlgorithm="MD5";
            if (Json.str(chosen,"version","").endsWith("-SNAPSHOT")) {
                String meta=net.text(repo+artifactPath.substring(0,artifactPath.lastIndexOf('/')+1)+"maven-metadata.xml",Net.Policy.UPSTREAM);
                Matcher timestamp=Pattern.compile("<timestamp>([^<]+)</timestamp>").matcher(meta), build=Pattern.compile("<buildNumber>([^<]+)</buildNumber>").matcher(meta);
                Util.require(timestamp.find() && build.find(),"LiteLoader 快照元数据缺少时间或编号");
                String original=Json.str(chosen,"version",""), stamped=original.replace("-SNAPSHOT","-"+timestamp.group(1)+"-"+build.group(1));
                artifactUrl=repo+artifactPath.substring(0,artifactPath.lastIndexOf('/')+1)+"liteloader-"+stamped+".jar";
                // The old versions.json hash identifies a different distribution build.
                // Bind the timestamped Maven artifact to the repository's own SHA-1 sidecar.
                String sidecar=net.text(artifactUrl+".sha1",Net.Policy.UPSTREAM).strip();
                Matcher digest=Pattern.compile("(?i)^([0-9a-f]{40})(?:\\s.*)?$").matcher(sidecar);
                Util.require(digest.matches(),"LiteLoader Maven 快照未提供有效 SHA-1");
                checksum=digest.group(1); checksumAlgorithm="SHA-1";
            }
            Path liteJar=Util.within(store.game.resolve("libraries"),artifactPath);
            Util.require(!checksum.isBlank(),"LiteLoader 缺少校验信息");
            net.download(new Net.File(artifactUrl,liteJar,checksum,checksumAlgorithm,-1,Net.Policy.UPSTREAM),job);
            Map<String,Object> lib=Json.map("name",coordinate,"downloads",Json.map("artifact",Json.map("path",artifactPath,"url",artifactUrl,"size",Files.size(liteJar),"sha1",Util.hash(liteJar,"SHA-1"))));
            Map<String,Object> mergedLibraries=new LinkedHashMap<>();
            for (Object raw : Json.list(chosen,"commonLibraries")) mergedLibraries.put(Minecraft.libraryKey(Json.str(Json.obj(raw),"name","")),raw);
            for (Object raw : Json.list(chosen,"libraries")) mergedLibraries.put(Minecraft.libraryKey(Json.str(Json.obj(raw),"name","")),raw);
            List<Object> libraries=new ArrayList<>();
            for (Object raw : mergedLibraries.values()) libraries.add(liteLibrary(Json.obj(raw)));
            libraries.add(lib);
            profile=game+"-LiteLoader-"+Json.str(chosen,"version",""); Util.fileName(profile);
            Map<String,Object> data=Json.map("id",profile,"inheritsFrom",game,"mainClass","net.minecraft.launchwrapper.Launch",
                    "arguments",Json.map("game",List.of("--tweakClass","com.mumfrey.liteloader.launch.LiteLoaderTweaker")),"libraries",libraries,"type","release");
            Util.atomic(mc.versionFile(profile),Json.pretty(data)); mc.repair(mc.resolve(profile),job);
        } else throw new IllegalArgumentException("加载器支持 fabric/quilt/forge/neoforge/liteloader/optifine");
        if (instance != null && !instance.isBlank()) mc.createInstance(instance,profile,loader);
        return profile;
    }
    private Map<String,Object> liteLibrary(Map<String,Object> input) throws Exception {
        Map<String,Object> lib=Json.obj(Json.copy(input)); String coordinate=Json.str(lib,"name","");
        String path=Minecraft.mavenPath(coordinate),base=Util.urlBase(Json.str(lib,"url","https://libraries.minecraft.net/"));
        if (coordinate.startsWith("org.ow2.asm:") && !lib.containsKey("url")) base="https://repo.maven.apache.org/maven2/";
        String url=base+path; boolean snapshot=coordinate.split(":")[2].endsWith("-SNAPSHOT");
        if (snapshot) {
            String meta=net.text(base+path.substring(0,path.lastIndexOf('/')+1)+"maven-metadata.xml",Net.Policy.UPSTREAM);
            Matcher timestamp=Pattern.compile("<timestamp>([^<]+)</timestamp>").matcher(meta),build=Pattern.compile("<buildNumber>([^<]+)</buildNumber>").matcher(meta);
            Util.require(timestamp.find() && build.find(),"依赖快照元数据不完整: "+coordinate);
            String[] parts=coordinate.split(":"); String stamped=parts[2].replace("-SNAPSHOT","-"+timestamp.group(1)+"-"+build.group(1));
            url=base+path.substring(0,path.lastIndexOf('/')+1)+parts[1]+"-"+stamped+(parts.length > 3 ? "-"+parts[3] : "")+".jar";
        }
        if (snapshot || coordinate.startsWith("org.ow2.asm:")) {
            String sha1=net.text(url+".sha1",Net.Policy.UPSTREAM).strip().split("\\s+")[0];
            Util.require(sha1.matches("(?i)[0-9a-f]{40}"),"上游依赖缺少有效 SHA-1: "+coordinate);
            lib.put("downloads",Json.map("artifact",Json.map("path",path,"url",url,"sha1",sha1)));
        } else lib.put("url",base);
        return lib;
    }
    public synchronized String installLocal(String loader,Path installer,String game,Jobs.Job job) throws Exception {
        Util.require(Set.of("forge","neoforge","optifine").contains(loader),"本地安装器: forge | neoforge | optifine");
        Util.require(Files.isRegularFile(installer),"安装器不存在"); Minecraft.Version base=mc.resolve(game);
        Path profiles=store.game.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) Util.atomic(profiles,Json.pretty(Json.map("profiles",Json.map(),"settings",Json.map(),"version",3)));
        Map<String,String> before=new HashMap<>();
        for (Map<String,Object> v : mc.installed()) { String id=Json.str(v,"id",""); before.put(id,Util.hash(mc.versionFile(id),"SHA-1")); }
        JavaRuntime.Info runtime=java.choose(Math.max(8,JavaRuntime.required(base)),store.text("java"));
        job.stage("执行 "+loader+" 无界面安装器");
        Path log=store.home.resolve("logs").resolve("installer-"+Util.timestamp()+".log");
        List<String> command=new ArrayList<>(List.of(runtime.path().toString(),"-Djava.awt.headless=true"));
        if (loader.equals("optifine")) {
            // Run the reflection bridge using the launcher's Java 17; OptiFine's public doInstall is headless.
            command.set(0,Path.of(System.getProperty("java.home"),"bin",Util.WINDOWS ? "java.exe" : "java").toString());
            command.addAll(List.of("-cp",Launch.self().toString(),"dev.cairn.Loaders","optifine-bridge",installer.toAbsolutePath().toString(),store.game.toString()));
        } else command.addAll(List.of("-jar",installer.toAbsolutePath().toString(),"--installClient",store.game.toString()));
        Process p=new ProcessBuilder(command).directory(store.game.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long deadline=System.currentTimeMillis()+15*60*1000;
        try {
            while (!p.waitFor(300,TimeUnit.MILLISECONDS)) { job.check(); Util.require(System.currentTimeMillis() < deadline,"加载器安装超时"); }
        } catch (Exception e) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); throw e; }
        Util.require(p.exitValue() == 0,"上游安装器失败: "+Util.tail(log,15));
        List<String> candidates=new ArrayList<>();
        for (Map<String,Object> v : mc.installed()) {
            String id=Json.str(v,"id","");
            if (before.containsKey(id) && before.get(id).equals(Util.hash(mc.versionFile(id),"SHA-1"))) continue;
            if (!Json.str(v,"inheritsFrom","").equals(game)) continue;
            if (!id.equals(game)) candidates.add(id);
        }
        Util.require(candidates.size() == 1,"无法唯一识别安装器产物（"+candidates+"），请 versions installed 检查后 instance create");
        String result=candidates.get(0); mc.repair(mc.resolve(result),job); ui.note("加载器安装完成: "+result); return result;
    }
    public static void main(String[] args) throws Exception {
        Util.require(args.length == 3 && args[0].equals("optifine-bridge"),"invalid bridge args");
        System.setProperty("java.awt.headless","true");
        try (URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of(args[1]).toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            Class<?> type=Class.forName("optifine.Installer",true,loader);
            type.getMethod("doInstall",java.io.File.class).invoke(null,Path.of(args[2]).toFile());
        }
    }
}
