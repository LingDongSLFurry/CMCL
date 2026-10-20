package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public final class Diagnostics {
    private record Hint(Pattern pattern,String cause,String action) {}
    private static final List<Hint> HINTS=List.of(
            hint("OutOfMemoryError|Java heap space|GC overhead limit|Out of memory","游戏或原生内存不足","适当增加 memoryMiB；关闭占用内存的程序；降低材质分辨率。"),
            hint("Could not reserve enough space|Invalid maximum heap size|Could not create the Java Virtual Machine","Java 无法分配内存或 JVM 参数错误","检查 Java 是否为 64 位，以及 minMemoryMiB / memoryMiB / jvmArgs。"),
            hint("UnsupportedClassVersionError|class file version ([6-9][0-9])","Java 版本过低","通过 java scan 与 doctor 检查版本；安装游戏要求的 Java。"),
            hint("InaccessibleObjectException|does not .*opens|IllegalAccessError.*sun\\.","Java 与旧加载器不兼容","旧版 MC/Forge 优先使用 Java 8；避免直接换用更高版本。"),
            hint("GLFW error 65542|GLFW error 65543|Pixel format not accelerated|Failed to create window|OpenGL.*not supported","OpenGL 或显卡驱动问题","从显卡厂商安装驱动，检查远程桌面/虚拟机及硬件支持。"),
            hint("EXCEPTION_ACCESS_VIOLATION|SIGSEGV|SIGBUS","JVM 或本地驱动崩溃","检查 hs_err_pid 日志中的 Problematic frame，更新驱动或更换合适的 Java。"),
            hint("MixinApplyError|MixinTransformerError|InvalidMixinException|mixin.*failed","Mixin 转换失败","查看紧邻错误的 Mod 名称，核对加载器、MC 和前置版本。"),
            hint("DuplicateModsFoundException|Duplicate mod|Found duplicate mods|DuplicateModsException","重复安装 Mod","mod list 检查相同 Mod 的多个版本，禁用重复文件。"),
            hint("MissingModsException|requires .* version|Missing mandatory dependencies|Incompatible mod set|depends on.*missing","Mod 依赖缺失或版本不兼容","按照日志的 required/found 信息安装前置，核对 MC 与加载器版本。"),
            hint("ModLoadingException|Failed to load mod|Could not execute entrypoint|ExceptionInInitializerError","Mod 初始化失败","检查首个 caused by 与 Mod 标识；尝试禁用近期加入的 Mod。"),
            hint("NoClassDefFoundError|ClassNotFoundException","类或依赖缺失","repair 补全依赖；核对 Mod 是否装错加载器。"),
            hint("ZipException|invalid LOC header|invalid CEN header|checksum.*(mismatch|fail)|Hash.*mismatch","JAR/资源损坏或哈希不符","重新校验并补全文件；检查磁盘及下载网络。"),
            hint("AuthenticationException|Invalid session|Failed to verify username|InvalidCredentialsException","登录会话无效","account refresh 或重新登录；正版服务器必须使用有所有权的微软账户。"),
            hint("UnknownHostException|ConnectException|SocketTimeoutException|SSLHandshakeException","网络、DNS 或 TLS 问题","检查网络、系统时间和代理；config proxy 变更后重启启动器。"),
            hint("GL_OUT_OF_MEMORY|OpenGL Error: 1282|Texture.*too large","显存、光影或资源包问题","降低材质分辨率并暂时移除光影；更新显卡驱动。"),
            hint("NightConfig|ParsingException.*toml|Config.*failed.*load","Mod 配置解析失败","备份后检查对应 config 文件语法；可将文件移到实例回收区再重试。"),
            hint("java.lang.IllegalArgumentException:.*ID|No more entity indices|maximum.*(block|item).*ID","旧版游戏 ID 数量超限","减少 Mod 或使用与游戏版本匹配的 ID 扩展方案。"),
            hint("You are using an unsupported version of Java|Java 8 is required|requires Java 17|requires Java 21","运行时版本不匹配","按加载器要求选择 java；doctor 可给出基础版本要求。"),
            hint("Manually triggered debug crash","手动调试崩溃","日志显示通过游戏调试快捷键触发，通常不需要修复。"),
            hint("Unable to access jarfile|Could not find or load main class","启动文件缺失或路径错误","检查版本描述与 mainClass，运行 repair。"));
    private static Hint hint(String regex,String cause,String action) { return new Hint(Pattern.compile(regex,Pattern.CASE_INSENSITIVE),cause,action); }
    private final Store store;
    private final Minecraft mc;
    private final JavaRuntime java;
    private final Content content;
    private final Ui ui;
    public Diagnostics(Store store,Minecraft mc,JavaRuntime java,Content content,Ui ui) { this.store=store; this.mc=mc; this.java=java; this.content=content; this.ui=ui; }
    public Map<String,Object> doctor(String name,boolean deep) throws Exception {
        Map<String,Object> report=Json.map("launcher",Main.VERSION,"os",System.getProperty("os.name"),"arch",Util.ARCH,"home",store.home.toString(),
                "diskFree",Files.getFileStore(store.home).getUsableSpace(),"source",store.text("source"));
        List<Object> checks=new ArrayList<>(); report.put("checks",checks);
        checks.add(Json.map("item","Java 列表","status","INFO","value",java.scan().stream().map(JavaRuntime.Info::json).toList()));
        if (name == null || name.isBlank()) name=store.text("selected");
        if (!name.isBlank()) {
            Map<String,Object> m=store.instanceConfig(name); Minecraft.Version v=mc.resolve(Json.str(m,"version",""));
            int required=JavaRuntime.required(v); report.put("instance",name); report.put("version",v.id()); report.put("requiredJava",required);
            try { JavaRuntime.Info i=java.choose(required,Json.str(store.settings(name),"java","auto")); checks.add(Json.map("item","Java 兼容","status","OK","value",i.json())); }
            catch (Exception e) { checks.add(Json.map("item","Java 兼容","status","FAIL","value",e.getMessage())); }
            List<String> missing=new ArrayList<>(), corrupt=new ArrayList<>();
            Path client=mc.versionDir(v.jarId()).resolve(v.jarId()+".jar");
            if (!Files.isRegularFile(client)) missing.add(client.toString());
            else if (deep) {
                String sha1=Json.str(Json.child(Json.child(v.data(),"downloads"),"client"),"sha1","");
                if (!sha1.isBlank() && !Util.hash(client,"SHA-1").equals(sha1)) corrupt.add(client.toString());
            }
            for (Minecraft.Library lib : mc.libraries(v)) {
                if (!Files.isRegularFile(lib.file().path())) missing.add(lib.file().path().toString());
                else if (deep && !lib.file().hash().isBlank() && !Util.hash(lib.file().path(),lib.file().algorithm()).equalsIgnoreCase(lib.file().hash())) corrupt.add(lib.file().path().toString());
            }
            report.put("missing",missing); report.put("corrupt",corrupt);
            checks.add(Json.map("item","客户端与依赖","status",missing.isEmpty() && corrupt.isEmpty() ? "OK" : "FAIL","value","缺失 "+missing.size()+"，损坏 "+corrupt.size()));
            List<Object> mods=content.local(name); Map<String,List<String>> ids=new LinkedHashMap<>();
            for (Object raw : mods) {
                Map<String,Object> mod=Json.obj(raw); String id=Json.str(mod,"id","");
                if (!id.isBlank() && Json.bool(mod,"enabled",true)) ids.computeIfAbsent(id,k -> new ArrayList<>()).add(Json.str(mod,"file",""));
            }
            report.put("duplicateMods",ids.entrySet().stream().filter(e -> e.getValue().size() > 1).map(e -> Json.map("id",e.getKey(),"files",e.getValue())).toList());
            Map<String,Object> settings=store.settings(name);
            checks.add(Json.map("item","内存","status",Json.integer(settings,"memoryMiB",2048) >= Json.integer(settings,"minMemoryMiB",256) ? "OK" : "FAIL",
                    "value",Json.integer(settings,"minMemoryMiB",256)+"~"+Json.integer(settings,"memoryMiB",2048)+" MiB"));
        }
        return report;
    }
    public List<Object> analyze(Path file) throws IOException {
        String log=Util.tail(file,20000); List<Object> result=new ArrayList<>();
        for (Hint h : HINTS) {
            Matcher m=h.pattern().matcher(log);
            if (m.find()) {
                int start=log.lastIndexOf('\n',m.start()), end=log.indexOf('\n',m.end()); if (end < 0) end=log.length();
                String evidence=log.substring(start < 0 ? 0 : start+1,Math.min(end,(start < 0 ? 0 : start+1)+600));
                result.add(Json.map("cause",h.cause(),"action",h.action(),"evidence",Util.redact(Ui.clean(evidence))));
            }
        }
        if (result.isEmpty()) result.add(Json.map("cause","没有匹配到已知特征","action","保留完整日志并查看首个 Caused by；规则分析不能替代人工诊断","evidence",""));
        return result;
    }
    public Path latestLog(String name) throws Exception {
        name=store.selected(name); Path latest=null; long time=0; Path game=store.gameDir(name);
        for (Path dir : List.of(game.resolve("logs"),game.resolve("crash-reports"),store.home.resolve("logs"))) {
            for (Path f : Util.children(dir)) {
                if (!Files.isRegularFile(f) || !(f.toString().endsWith(".log") || f.toString().endsWith(".txt"))) continue;
                if (dir.equals(store.home.resolve("logs")) && !f.getFileName().toString().contains("-"+name+"-")) continue;
                long t=Files.getLastModifiedTime(f).toMillis(); if (t >= time) { latest=f; time=t; }
            }
        }
        Util.require(latest != null,"该实例还没有日志"); return latest;
    }
    public void report(String name,Path output) throws Exception {
        name=store.selected(name); Util.require(!Files.exists(output),"输出文件已存在");
        Path stage=Files.createTempDirectory(store.cache,"report-");
        try {
            Util.atomic(stage.resolve("doctor.json"),Json.pretty(doctor(name,false)));
            Map<String,Object> instance=store.instanceConfig(name);
            // Export only diagnostic settings, excluding hooks, custom arguments, account endpoints and tokens.
            Map<String,Object> settings=new LinkedHashMap<>();
            for (var e : Json.child(instance,"settings").entrySet())
                if (Set.of("memoryMiB","minMemoryMiB","width","height","fullscreen","gc","isolation").contains(e.getKey())) settings.put(e.getKey(),e.getValue());
            instance.put("settings",settings); Util.atomic(stage.resolve("instance.json"),Json.pretty(instance));
            Util.atomic(stage.resolve("mods.json"),Json.pretty(content.local(name)));
            try {
                Path log=latestLog(name); Util.atomic(stage.resolve("latest-redacted.log"),Util.redact(Util.tail(log,10000)));
                Util.atomic(stage.resolve("analysis.json"),Json.pretty(analyze(log)));
            } catch (IllegalArgumentException ignored) {}
            Util.zip(stage,output,p -> true); ui.note("诊断报告已保存（令牌字段已脱敏；分享前仍可自行检查日志中的聊天与服务器地址）: "+output);
        } finally { store.trash(stage,store.cache); }
    }
}
