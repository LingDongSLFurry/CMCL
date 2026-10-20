package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.regex.*;

public final class JavaRuntime {
    public static final String INDEX = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    public record Info(Path path,int major,String version,String arch) {
        public Map<String,Object> json() { return Json.map("path",path.toString(),"major",major,"version",version,"arch",arch); }
    }
    private final Store store;
    private final Net net;
    public JavaRuntime(Store store,Net net) { this.store=store; this.net=net; }
    public static Info inspect(Path input) throws Exception {
        Path path = input.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) path=path.resolve("bin").resolve(Util.WINDOWS ? "java.exe" : "java");
        Util.require(Files.isRegularFile(path),"Java 不存在: "+path);
        Process p=new ProcessBuilder(path.toString(),"-XshowSettings:properties","-version").redirectErrorStream(true).start();
        FutureTask<String> read=new FutureTask<>(() -> {
            byte[] bytes=p.getInputStream().readNBytes(4*1024*1024+1);
            Util.require(bytes.length <= 4*1024*1024,"Java 检测输出过大"); return new String(bytes,StandardCharsets.UTF_8);
        });
        Thread drain=new Thread(read,"java-inspect-output"); drain.setDaemon(true); drain.start();
        if (!p.waitFor(12,TimeUnit.SECONDS)) { p.destroyForcibly(); read.cancel(true); throw new IOException("Java 检测超时"); }
        String text=read.get(3,TimeUnit.SECONDS);
        Matcher m=Pattern.compile("(?:java\\.version\\s*=\\s*|version\\s+\")([0-9][^\\s\"]*)").matcher(text);
        Util.require(p.exitValue() == 0 && m.find(),"无法识别 Java: "+path);
        String version=m.group(1); int major=major(version);
        Matcher a=Pattern.compile("os.arch\\s*=\\s*(\\S+)").matcher(text);
        return new Info(path,major,version,a.find() ? a.group(1) : "unknown");
    }
    static int major(String version) {
        Matcher m=Pattern.compile("^(?:1\\.)?(\\d+)").matcher(version);
        Util.require(m.find(),"无效 Java 版本: "+version); return Integer.parseInt(m.group(1));
    }
    public List<Info> scan() throws IOException {
        Set<Path> candidates=new LinkedHashSet<>();
        candidates.add(Path.of(System.getProperty("java.home"),"bin",Util.WINDOWS ? "java.exe" : "java"));
        String javaHome=System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) candidates.add(Path.of(javaHome,"bin",Util.WINDOWS ? "java.exe" : "java"));
        for (String item : System.getenv().getOrDefault("PATH","").split(Pattern.quote(File.pathSeparator))) {
            if (!item.isBlank()) try { candidates.add(Path.of(item,Util.WINDOWS ? "java.exe" : "java")); } catch (InvalidPathException ignored) {}
        }
        for (Object p : Json.list(store.config(),"javaPaths")) candidates.add(Path.of(p.toString()));
        List<Path> roots=new ArrayList<>(List.of(store.home.resolve("runtime")));
        if (Util.WINDOWS) {
            String pf=System.getenv("ProgramFiles");
            if (pf != null) for (String dir : List.of("Java","Eclipse Adoptium","Microsoft","BellSoft","Zulu")) roots.add(Path.of(pf,dir));
        } else { roots.add(Path.of("/usr/lib/jvm")); if (Util.OS.equals("osx")) roots.add(Path.of("/Library/Java/JavaVirtualMachines")); }
        for (Path root : roots) if (Files.isDirectory(root)) try (var walk=Files.walk(root,7)) {
            walk.filter(p -> p.getFileName().toString().equals(Util.WINDOWS ? "java.exe" : "java") && Files.isRegularFile(p)).limit(100).forEach(candidates::add);
        } catch (IOException ignored) {}
        List<Info> result=new ArrayList<>(); Set<Path> real=new HashSet<>();
        for (Path p : candidates) if (Files.isRegularFile(p)) try {
            if (real.add(p.toRealPath())) result.add(inspect(p));
        } catch (Exception ignored) {}
        result.sort(Comparator.comparingInt(Info::major).reversed().thenComparing(i -> i.path().toString()));
        return result;
    }
    public Info choose(int required,String explicit) throws Exception {
        if (explicit != null && !explicit.isBlank() && !explicit.equals("auto")) {
            Info i=inspect(Path.of(explicit));
            Util.require(i.major() >= required,"所选 Java "+i.major()+" 低于需要的 "+required); return i;
        }
        List<Info> list=scan();
        for (Info i : list) if (i.major() == required && !i.arch().equals("x86")) return i;
        // Old game/loader combinations often fail on newer Java, so do not guess.
        throw new IOException("没有合适的 Java "+required+"。运行 java install "+required+"（Mojang 官方运行时），或 java add <java路径>");
    }
    public static int required(Minecraft.Version v) {
        int declared=Json.integer(Json.child(v.data(),"javaVersion"),"majorVersion",0);
        if (declared > 0) return declared;
        String release=Json.str(v.data(),"releaseTime","");
        if (release.compareTo("2024-04-23") >= 0) return 21;
        if (release.compareTo("2021-12-10") >= 0) return 17;
        if (release.compareTo("2021-06-08") >= 0) return 16;
        return 8;
    }
    public String platform() {
        if (Util.OS.equals("osx")) return Util.ARM ? "mac-os-arm64" : "mac-os";
        if (Util.OS.equals("windows")) return Util.ARM ? "windows-arm64" : Util.ARCH.contains("64") ? "windows-x64" : "windows-x86";
        return Util.ARM ? "linux-arm64" : Util.ARCH.contains("64") ? "linux" : "linux-i386";
    }
    public Map<String,Object> available(boolean refresh) throws Exception {
        return Json.child(Json.obj(net.cachedJson(INDEX,Net.Policy.OFFICIAL,refresh)),platform());
    }
    public Info install(int wanted,Jobs.Job job) throws Exception {
        Map<String,Object> chosen=null; String component="";
        for (var e : available(false).entrySet()) for (Object raw : Json.arr(e.getValue())) {
            if (e.getKey().equals("minecraft-java-exe")) continue;
            Map<String,Object> entry=Json.obj(raw); String name=Json.str(Json.child(entry,"version"),"name","");
            try { if (major(name) == wanted && (chosen == null || name.compareTo(Json.str(Json.child(chosen,"version"),"name","")) > 0)) { chosen=entry; component=e.getKey(); } }
            catch (RuntimeException ignored) {}
        }
        Util.require(chosen != null,"Mojang 未提供当前平台的 Java "+wanted+"，请自行安装后 java add");
        Map<String,Object> meta=Json.child(chosen,"manifest");
        Path manifest=store.cache.resolve("runtime-"+Util.id(component)+"-"+Util.id(platform())+".json");
        job.stage("获取 Mojang Java "+wanted+" 描述");
        net.download(new Net.File(Json.str(meta,"url",""),manifest,Json.str(meta,"sha1",""),Json.number(meta,"size",-1),Net.Policy.OFFICIAL,Sources.Kind.JAVA_METADATA),job);
        Map<String,Object> files=Json.child(Json.obj(Json.read(manifest)),"files");
        Path root=store.home.resolve("runtime").resolve(component).resolve(platform()); Files.createDirectories(root);
        List<Net.File> downloads=new ArrayList<>();
        for (var e : files.entrySet()) {
            Map<String,Object> f=Json.obj(e.getValue()); Path dest=Util.within(root,e.getKey());
            if (Json.str(f,"type","").equals("directory")) Files.createDirectories(dest);
            Map<String,Object> raw=Json.child(Json.child(f,"downloads"),"raw");
            if (!raw.isEmpty()) downloads.add(new Net.File(Json.str(raw,"url",""),dest,Json.str(raw,"sha1",""),Json.number(raw,"size",-1),Net.Policy.OFFICIAL,Sources.Kind.JAVA_FILE));
        }
        job.stage("下载 Mojang Java "+wanted); net.downloadAll(downloads,job);
        for (var e : files.entrySet()) {
            Map<String,Object> f=Json.obj(e.getValue()); Path dest=Util.within(root,e.getKey());
            if (Json.bool(f,"executable",false)) dest.toFile().setExecutable(true,false);
            if (Json.str(f,"type","").equals("link") && !Files.exists(dest)) {
                Path target=dest.getParent().resolve(Json.str(f,"target","")).normalize();
                Util.require(target.startsWith(root) && Files.exists(target),"运行时链接无效");
                if (Files.isDirectory(target)) Util.copyTree(target,dest); else { Files.createDirectories(dest.getParent()); Files.copy(target,dest); }
            }
        }
        Path java;
        try (var walk=Files.walk(root,8)) {
            java=walk.filter(p -> p.getFileName().toString().equals(Util.WINDOWS ? "java.exe" : "java") && Files.isRegularFile(p)).findFirst()
                    .orElseThrow(() -> new IOException("运行时未含 java 可执行文件"));
        }
        Info info=inspect(java); Util.require(info.major() == wanted,"Java 版本不匹配");
        List<Object> paths=new ArrayList<>(Json.list(store.config(),"javaPaths"));
        if (!paths.contains(java.toString())) { paths.add(java.toString()); store.set("javaPaths",paths); }
        return info;
    }
}
