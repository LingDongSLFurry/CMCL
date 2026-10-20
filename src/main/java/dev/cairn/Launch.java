package dev.cairn;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

public final class Launch {
    public record Plan(List<String> command,Path directory,Map<String,Object> account,Map<String,Object> settings,String instance,String version) {
        public List<String> redacted() {
            List<String> out=new ArrayList<>(); boolean secret=false;
            for (String a : command) {
                if (secret) { out.add("<redacted>"); secret=false; continue; }
                out.add(Util.redact(a));
                if (Set.of("--accessToken","--clientToken","--userProperties","--profileProperties").contains(a)) secret=true;
            }
            return out;
        }
    }
    private final Store store;
    private final Minecraft mc;
    private final Accounts accounts;
    private final JavaRuntime java;
    private final Ui ui;
    public Launch(Store store,Minecraft mc,Accounts accounts,JavaRuntime java,Ui ui) { this.store=store; this.mc=mc; this.accounts=accounts; this.java=java; this.ui=ui; }
    public static Path self() throws Exception { return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath(); }
    public Plan plan(String name,boolean authenticate,String server) throws Exception {
        name=store.selected(name); Map<String,Object> instance=store.instanceConfig(name), settings=store.settings(name);
        String version=Json.str(instance,"version",""); Minecraft.Version v=mc.resolve(version);
        JavaRuntime.Info runtime=java.choose(JavaRuntime.required(v),Json.str(settings,"java","auto"));
        Map<String,Object> account=authenticate ? accounts.ready("",false) : accounts.find("");
        Path gameDir=store.gameDir(name), natives=mc.natives(v,name), assets=mc.assetsRoot(v,gameDir);
        List<String> cp=new ArrayList<>();
        for (Minecraft.Library lib : mc.libraries(v)) {
            Util.require(Files.isRegularFile(lib.file().path()),"缺少依赖: "+lib.file().path().getFileName()+"；请 repair "+name);
            if (lib.file().path().toString().endsWith(".jar")) cp.add(lib.file().path().toString());
        }
        Path jar=mc.versionDir(v.jarId()).resolve(v.jarId()+".jar");
        Util.require(Files.isRegularFile(jar),"缺少客户端 jar；请 repair "+name); cp.add(jar.toString());
        String userType=Json.str(account,"type","offline").equals("microsoft") ? "msa" : "mojang";
        String assetId=Json.str(Json.child(v.data(),"assetIndex"),"id",Json.str(v.data(),"assets","legacy"));
        Map<String,String> vars=new HashMap<>();
        vars.put("auth_player_name",Json.str(account,"name","")); vars.put("version_name",version);
        vars.put("game_directory",gameDir.toString()); vars.put("assets_root",store.game.resolve("assets").toString());
        vars.put("game_assets",assets.toString()); vars.put("assets_index_name",assetId);
        vars.put("auth_uuid",Json.str(account,"uuid","")); vars.put("auth_access_token",Json.str(account,"accessToken","0"));
        vars.put("auth_session","token:"+Json.str(account,"accessToken","0")+":"+Json.str(account,"uuid",""));
        vars.put("auth_xuid",Json.str(account,"xuid","")); vars.put("xuid",Json.str(account,"xuid",""));
        vars.put("clientid",Json.str(account,"clientId","")); vars.put("user_type",userType);
        vars.put("version_type",Json.str(settings,"customInfo",Json.str(v.data(),"type","release")));
        vars.put("natives_directory",natives.toString()); vars.put("launcher_name","Cairn"); vars.put("launcher_version",Main.VERSION);
        vars.put("classpath",String.join(File.pathSeparator,cp)); vars.put("classpath_separator",File.pathSeparator);
        vars.put("library_directory",store.game.resolve("libraries").toString()); vars.put("user_properties","{}"); vars.put("profile_properties","{}");
        vars.put("resolution_width",Integer.toString(Json.integer(settings,"width",960)));
        vars.put("resolution_height",Integer.toString(Json.integer(settings,"height",540)));
        vars.put("quickPlayPath",store.home.resolve("logs/quickplay-"+name+".json").toString());
        String target=server == null || server.isBlank() ? Json.str(settings,"server","") : server;
        vars.put("quickPlayMultiplayer",target); vars.put("quickPlaySingleplayer",Json.str(settings,"world","")); vars.put("quickPlayRealms","");
        Map<String,Boolean> features=Map.of("is_demo_user",false,"has_custom_resolution",!Json.bool(settings,"fullscreen",false),
                "has_quick_plays_support",!target.isBlank() || !Json.str(settings,"world","").isBlank(),
                "is_quick_play_multiplayer",!target.isBlank(),"is_quick_play_singleplayer",!Json.str(settings,"world","").isBlank(),"is_quick_play_realms",false);
        List<String> command=new ArrayList<>(); command.add(runtime.path().toString());
        int memory=Json.integer(settings,"memoryMiB",2048), min=Json.integer(settings,"minMemoryMiB",256);
        Util.require(memory >= min,"最大内存不能小于最小内存");
        Util.require(!runtime.arch().equals("x86") || memory < 1500,"32 位 Java 无法分配当前内存，请选择 64 位 Java");
        command.add("-Xms"+min+"M"); command.add("-Xmx"+memory+"M");
        String gc=Json.str(settings,"gc","g1");
        if (gc.equals("g1")) command.add("-XX:+UseG1GC");
        else if (gc.equals("zgc")) {
            Util.require(runtime.major() >= 15,"ZGC 需要 Java 15+"); command.add("-XX:+UseZGC");
            if (runtime.major() >= 21 && runtime.major() < 24) command.add("-XX:+ZGenerational");
        }
        command.add("-Dlog4j2.formatMsgNoLookups=true"); command.add("-Dfile.encoding=UTF-8");
        if (Json.str(account,"type","").equals("yggdrasil")) {
            String injector=Json.str(settings,"authlibInjector","");
            Util.require(!injector.isBlank() && Files.isRegularFile(Path.of(injector)),"第三方登录需要实例设置 authlibInjector 指向认证注入器 jar");
            command.add("-javaagent:"+Path.of(injector).toAbsolutePath()+"="+Json.str(account,"server",""));
            command.add("-Dauthlibinjector.side=client");
        }
        Map<String,Object> args=Json.child(v.data(),"arguments");
        List<String> jvm=arguments(Json.list(args,"jvm"),features,vars);
        if (jvm.isEmpty()) {
            command.add("-Djava.library.path="+natives);
            if (Util.OS.equals("osx")) command.add("-XstartOnFirstThread");
            command.add("-cp"); command.add(vars.get("classpath"));
        } else command.addAll(jvm);
        Map<String,Object> logging=Json.child(Json.child(v.data(),"logging"),"client"), logFile=Json.child(logging,"file");
        if (!logFile.isEmpty()) {
            Path log=Util.within(store.game.resolve("assets/log_configs"),Util.fileName(Json.str(logFile,"id","")));
            if (Files.isRegularFile(log)) command.add(Json.str(logging,"argument","").replace("${path}",log.toString()));
        }
        for (Object a : Json.list(settings,"jvmArgs")) command.add(expand(a.toString(),vars));
        command.add(Json.str(v.data(),"mainClass",""));
        List<String> gameArgs=args.containsKey("game") ? arguments(Json.list(args,"game"),features,vars)
                : Shell.words(Json.str(v.data(),"minecraftArguments","")).stream().map(s -> expand(s,vars)).toList();
        command.addAll(gameArgs);
        if (Json.bool(settings,"fullscreen",false)) command.add("--fullscreen");
        else if (Json.str(v.data(),"mainClass","").endsWith(".Main") || Json.str(v.data(),"mainClass","").contains("launchwrapper")) {
            if (!command.contains("--width")) command.addAll(List.of("--width",vars.get("resolution_width")));
            if (!command.contains("--height")) command.addAll(List.of("--height",vars.get("resolution_height")));
        }
        if (!target.isBlank() && !command.contains("--quickPlayMultiplayer")) {
            HostPort hp=hostPort(target,25565); command.add("--server"); command.add(hp.host()); command.add("--port"); command.add(Integer.toString(hp.port()));
        }
        for (Object a : Json.list(settings,"gameArgs")) command.add(expand(a.toString(),vars));
        return new Plan(command,gameDir,account,settings,name,version);
    }
    public record HostPort(String host,int port) {}
    public static HostPort hostPort(String s,int fallback) {
        String host=s; int port=fallback;
        if (s.startsWith("[")) {
            int end=s.indexOf(']'); Util.require(end > 1,"无效 IPv6 地址"); host=s.substring(1,end);
            if (s.length() > end+1) { Util.require(s.charAt(end+1) == ':',"无效端口"); port=Integer.parseInt(s.substring(end+2)); }
        } else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            int i=s.lastIndexOf(':'); host=s.substring(0,i); port=Integer.parseInt(s.substring(i+1));
        }
        Util.require(!host.isBlank() && port >= 1 && port <= 65535,"无效服务器地址"); return new HostPort(host,port);
    }
    public static String expand(String s,Map<String,String> vars) {
        Matcher m=Pattern.compile("\\$\\{([^}]+)}").matcher(s); StringBuffer b=new StringBuffer();
        while (m.find()) {
            Util.require(vars.containsKey(m.group(1)),"版本使用了未支持的参数: "+m.group(1));
            m.appendReplacement(b,Matcher.quoteReplacement(vars.get(m.group(1))));
        }
        m.appendTail(b); return b.toString();
    }
    public static List<String> arguments(List<Object> source,Map<String,Boolean> features,Map<String,String> vars) {
        List<String> result=new ArrayList<>();
        for (Object arg : source) {
            if (arg instanceof String s) result.add(expand(s,vars));
            else {
                Map<String,Object> a=Json.obj(arg); if (!Minecraft.allowed(a,features)) continue;
                Object value=a.get("value");
                if (value instanceof List) for (Object v : Json.arr(value)) result.add(expand(v.toString(),vars));
                else if (value != null) result.add(expand(value.toString(),vars));
            }
        }
        return result;
    }
    public Map<String,Object> start(String name,boolean wait,String server) throws Exception {
        name=store.selected(name);
        for (Map<String,Object> session : sessions()) Util.require(!name.equals(session.get("instance")) || !alive(session),"该实例已在运行");
        Plan plan=plan(name,true,server);
        Path sessions=store.home.resolve("sessions"); Files.createDirectories(sessions);
        String id=Util.timestamp()+"-"+name+"-"+UUID.randomUUID().toString().substring(0,6);
        Path sessionFile=sessions.resolve(id+".json"), log=store.home.resolve("logs").resolve(id+".log");
        Map<String,Object> data=Json.map("id",id,"instance",name,"version",plan.version(),"state","STARTING","log",log.toString(),"created",Instant.now().toString());
        Util.atomic(sessionFile,Json.pretty(data));
        String hostJava=Path.of(System.getProperty("java.home"),"bin",Util.WINDOWS ? "java.exe" : "java").toString();
        ProcessBuilder worker=new ProcessBuilder(hostJava,"-Djava.awt.headless=true","-cp",self().toString(),"dev.cairn.GameWorker",sessionFile.toString());
        worker.redirectErrorStream(true).redirectOutput(log.resolveSibling(id+".worker.log").toFile());
        Process monitor=worker.start();
        Map<String,Object> payload=Json.map("command",plan.command(),"directory",plan.directory().toString(),"session",data,
                "before",Json.list(plan.settings(),"before"),"after",Json.list(plan.settings(),"after"),
                "secrets",List.of(Json.str(plan.account(),"accessToken",""),Json.str(plan.account(),"refreshToken","")));
        try (Writer out=new OutputStreamWriter(monitor.getOutputStream(),StandardCharsets.UTF_8)) { out.write(Json.stringify(payload)); }
        long deadline=System.currentTimeMillis()+20000;
        while (System.currentTimeMillis() < deadline) {
            data=Json.obj(Json.read(sessionFile));
            if (!Json.str(data,"state","").equals("STARTING")) break;
            if (!monitor.isAlive()) throw new IOException("游戏监护进程启动失败: "+Util.tail(log.resolveSibling(id+".worker.log"),20));
            Thread.sleep(100);
        }
        Util.require(!Json.str(data,"state","").equals("STARTING"),"启动仍在进行，请通过 sessions 与 logs 检查");
        Util.require(!Json.str(data,"state","").equals("FAILED"),"游戏启动失败: "+Json.str(data,"error","")+"；logs "+id);
        ui.note("游戏会话 "+id+" · PID "+Json.number(data,"pid",0));
        if (wait) {
            LogCursor cursor=new LogCursor(log,0);
            while (monitor.isAlive()) {
                printNew(cursor); Thread.sleep(250);
            }
            printNew(cursor); data=Json.obj(Json.read(sessionFile));
            ui.note("游戏已退出，代码 "+Json.number(data,"exitCode",-1));
            Util.require(Json.number(data,"exitCode",-1) == 0,"游戏异常退出。运行 crash "+name+" 查看诊断");
        }
        return data;
    }
    private void printNew(LogCursor cursor) throws IOException {
        String text=cursor.read();
        if (!text.isEmpty()) {
            if (ui.json) ui.data(Json.map("event","log","text",Ui.clean(Util.redact(text))));
            else System.out.print(Ui.clean(Util.redact(text)));
        }
    }
    public List<Map<String,Object>> sessions() throws IOException {
        List<Map<String,Object>> result=new ArrayList<>();
        for (Path file : Util.children(store.home.resolve("sessions"))) if (file.toString().endsWith(".json")) {
            try {
                Map<String,Object> s=Json.obj(Json.read(file)); s.put("alive",alive(s)); result.add(s);
            } catch (RuntimeException ignored) {}
        }
        Collections.reverse(result); return result;
    }
    public static boolean alive(Map<String,Object> s) {
        long pid=Json.number(s,"pid",0); if (pid <= 0) return false;
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(p -> p.info().startInstant().map(i -> i.toString().equals(s.get("processStart"))).orElse(false)).isPresent();
    }
    public void stop(String target,boolean force) throws Exception {
        boolean found=false;
        for (Map<String,Object> s : sessions()) {
            if (!(target.equals(s.get("id")) || target.equals(s.get("instance")) || target.equals("all")) || !alive(s)) continue;
            ProcessHandle p=ProcessHandle.of(Json.number(s,"pid",0)).orElseThrow(); found=true;
            if (force) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); } else p.destroy();
            ui.note("停止请求已发送: "+s.get("id"));
        }
        Util.require(found,"没有匹配的运行会话");
    }
    public void script(String name,Path output) throws Exception {
        name=store.selected(name); store.instanceConfig(name);
        Util.require(!Files.exists(output),"输出已存在");
        // Export a launcher invocation so an expiring access token is never written into a script.
        String jar=self().toString(), java=Path.of(System.getProperty("java.home"),"bin",Util.WINDOWS ? "java.exe" : "java").toString();
        if (output.toString().endsWith(".cmd") || output.toString().endsWith(".bat")) {
            for (String s : List.of(jar,java,store.home.toString())) Util.require(!s.contains("%") && !s.contains("\n") && !s.contains("\r"),"此路径不能安全写入 CMD 脚本");
            Util.atomic(output,"@echo off\r\nchcp 65001 >nul\r\n\""+java+"\" -jar \""+jar+"\" --home \""+store.home+"\" play \""+name+"\" --wait\r\n");
        } else {
            Util.atomic(output,"#!/usr/bin/env sh\nexec "+shQuote(java)+" -jar "+shQuote(jar)+" --home "+shQuote(store.home.toString())+" play "+shQuote(name)+" --wait\n");
            output.toFile().setExecutable(true,false);
        }
    }
    private String shQuote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
}
