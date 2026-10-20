package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Shell implements AutoCloseable {
    public final Ui ui;
    public final Jobs jobs;
    private final Store store;
    private final Net net;
    private final Minecraft mc;
    private final Accounts accounts;
    private final JavaRuntime java;
    private final Launch launch;
    private final Loaders loaders;
    private final Content content;
    private final Packs packs;
    private final Instances instances;
    private final Diagnostics diagnostics;
    private final Multiplayer multiplayer;
    private final Extras extras;
    private boolean exiting;
    private int scriptDepth;
    private final List<String> history=new ArrayList<>();
    public Shell(Store store) throws Exception {
        this.store=store; ui=new Ui(store); jobs=new Jobs(ui); net=new Net(store); mc=new Minecraft(store,net,ui);
        accounts=new Accounts(store,net,ui); java=new JavaRuntime(store,net); launch=new Launch(store,mc,accounts,java,ui);
        loaders=new Loaders(store,net,mc,java,ui); content=new Content(store,net,mc,ui); packs=new Packs(store,net,mc,loaders,content,ui);
        instances=new Instances(store,mc,launch,ui); diagnostics=new Diagnostics(store,mc,java,content,ui); multiplayer=new Multiplayer(store,ui); extras=new Extras(store);
        Path h=store.home.resolve("history.txt");
        if (Files.exists(h)) history.addAll(Files.readAllLines(h,StandardCharsets.UTF_8));
    }
    public void repl() throws Exception {
        dashboard();
        while (!exiting) {
            String line=ui.read("cairn["+ (store.text("selected").isBlank() ? "home" : store.text("selected")) +"] › ");
            if (line == null) break;
            if (line.isBlank()) continue;
            if (!sensitive(line)) { history.add(Util.redact(line)); trimHistory(); Util.atomic(store.home.resolve("history.txt"),String.join("\n",history)+"\n"); }
            execute(line);
        }
        if (jobs.active()) ui.note("等待正在执行的任务；可在退出前使用 task cancel。");
    }
    private boolean sensitive(String s) {
        String l=s.toLowerCase(Locale.ROOT);
        return l.contains("account ") || l.contains("curseforgekey") || l.contains("link host") || l.contains("link join")
                || l.contains("password") || l.contains("token") || l.contains("secret") || l.contains("config import") || l.startsWith("alias set");
    }
    private void trimHistory() { while (history.size() > store.integer("maxHistory")) history.remove(0); }
    public int execute(String line) {
        int result=0; boolean skip=false;
        try {
            List<Segment> segments=segments(line);
            for (Segment segment : segments) {
                if (segment.and() && result != 0) continue;
                List<String> tokens=new ArrayList<>(segment.words()); if (tokens.isEmpty()) continue;
                try {
                    tokens=expandAliases(tokens);
                    for (int i=0;i<tokens.size();i++) tokens.set(i,variables(tokens.get(i)));
                    String cmd=tokens.remove(0).toLowerCase(Locale.ROOT);
                    if (List.of("cairn","cairn.jar","cairn.exe").contains(cmd) && !tokens.isEmpty()) cmd=tokens.remove(0);
                    if (cmd.matches("[0-9]")) { numeric(Integer.parseInt(cmd)); result=0; continue; }
                    dispatch(cmd,new Args(tokens)); result=0;
                } catch (Exception e) { result=1; ui.note("错误: "+e.getMessage()); if (ui.json) ui.data(Json.map("error",Util.redact(e.getMessage()),"exitCode",1)); }
                if (exiting) break;
            }
        } catch (RuntimeException e) { ui.note("语法错误: "+e.getMessage()); return 2; }
        return result;
    }
    private void dispatch(String cmd,Args a) throws Exception {
        switch (cmd) {
            case "help","?" -> help(a.at(0,""));
            case "commands" -> ui.data(Arrays.asList(Help.COMMANDS));
            case "complete" -> ui.data(Arrays.stream(Help.COMMANDS).filter(s -> s.startsWith(a.at(0,""))).toList());
            case "dashboard","menu","home" -> dashboard();
            case "page" -> page(a.need(0));
            case "setup" -> setup();
            case "versions","version" -> versions(a);
            case "install","download" -> runJob("安装 Minecraft "+a.need(0),j -> mc.install(a.need(0),a.at(1,defaultInstanceName(a.need(0))),j),a.flag("bg"));
            case "repair" -> {
                String name=store.selected(a.at(0,"")); instances.idle(name);
                runJob("补全 "+name,j -> mc.repair(mc.resolve(Json.str(store.instanceConfig(name),"version","")),j),a.flag("bg"));
            }
            case "instance","instances" -> instance(a);
            case "root" -> root(a);
            case "account","accounts" -> account(a);
            case "skin" -> skin(a);
            case "loader" -> loader(a);
            case "play","launch" -> play(a);
            case "sessions" -> sessionTable();
            case "stop" -> launch.stop(a.size() > 0 ? a.need(0) : store.selected(""),a.flag("force"));
            case "logs","log" -> logs(a);
            case "crash" -> {
                Path file=a.has("file") ? Path.of(a.option("file","")) : diagnostics.latestLog(a.at(0,""));
                ui.data(Json.map("file",file.toString(),"analysis",diagnostics.analyze(file)));
            }
            case "doctor" -> ui.data(diagnostics.doctor(a.at(0,""),a.flag("deep")));
            case "report" -> diagnostics.report(a.need(0),Path.of(a.need(1)).toAbsolutePath());
            case "launch-script" -> launch.script(a.need(0),Path.of(a.need(1)).toAbsolutePath());
            case "content","resource" -> content(a);
            case "mod","mods" -> mods(a);
            case "pack" -> pack(a);
            case "java" -> java(a);
            case "memory" -> ui.data(extras.memory());
            case "tasks" -> tasks();
            case "task" -> task(a);
            case "source" -> source(a);
            case "config","settings" -> config(a);
            case "cache" -> cache(a);
            case "trash" -> { if (a.at(0,"list").equals("restore")) instances.restore(a.need(1)); else ui.data(instances.trashList()); }
            case "server" -> server(a);
            case "lan" -> ui.data(multiplayer.lan(a.integer("seconds",3)));
            case "link" -> link(a);
            case "music" -> {
                if (a.at(0,"").equals("stop")) extras.stopMusic();
                else { Util.require(a.need(0).equals("play"),"music play | stop"); extras.music(Path.of(a.need(1)),Float.parseFloat(a.option("volume","0.5"))); }
            }
            case "alias" -> alias(a);
            case "script" -> { Util.require(a.need(0).equals("run"),"script run <文件>"); Util.require(script(Path.of(a.need(1))) == 0,"脚本执行失败"); }
            case "history" -> ui.data(history.subList(Math.max(0,history.size()-Integer.parseInt(a.at(0,"30"))),history.size()));
            case "clear" -> { if (System.console() != null) System.out.print("\u001b[2J\u001b[H"); }
            case "about" -> about();
            case "features" -> features();
            case "news" -> {
                Map<String,Object> manifest=mc.manifest(a.flag("refresh"));
                ui.data(Json.map("latest",Json.child(manifest,"latest"),"officialNews","https://www.minecraft.net/articles"));
            }
            case "selftest" -> SelfTest.run();
            case "exit","quit" -> exiting=true;
            default -> {
                String closest=Arrays.stream(Help.COMMANDS).min(Comparator.comparingInt(s -> distance(cmd,s))).orElse("help");
                throw new IllegalArgumentException("未知指令: "+cmd+"。可能是 "+closest+"；help 查看帮助");
            }
        }
    }
    private String defaultInstanceName(String v) { return v.replaceAll("[^A-Za-z0-9_.+-]","_"); }
    private void runJob(String name,Jobs.Work work,boolean background) throws Exception {
        Jobs.Job job=jobs.submit(name,work);
        if (background) ui.data(Json.map("task",job.id,"state","QUEUED")); else jobs.waitFor(job);
    }
    private void dashboard() throws Exception {
        long running=launch.sessions().stream().filter(Launch::alive).count();
        List<String> lines=new ArrayList<>(List.of(
                "实例  "+(store.text("selected").isBlank() ? "未选择" : store.text("selected"))+"    账户  "+(store.text("account").isBlank() ? "未选择" : store.text("account")),
                "已建实例 "+store.instances().size()+"    运行会话 "+running+"    下载源 "+Sources.active(store.config()).label(),
                "",
                "  1  实例工作区       2  版本与下载       3  账户",
                "  4  Mod 与资源       5  后台任务         6  配置",
                "  7  环境检查         8  新手配置         9  指令帮助",
                "",
                "输入数字进入工作区，或直接输入任意指令。",
                "例如: install 1.20.1 survival && account offline Player",
                "      play survival        help        exit",
                "",
                "数据: "+store.home));
        ui.frame(store.text("banner")+"  /  "+Main.VERSION,lines);
    }
    private void numeric(int n) throws Exception {
        switch(n) { case 0 -> exiting=true; case 1 -> instance(new Args(List.of("list"))); case 2 -> page("downloads"); case 3 -> account(new Args(List.of("list")));
            case 4 -> page("resources"); case 5 -> tasks(); case 6 -> config(new Args(List.of("list"))); case 7 -> ui.data(diagnostics.doctor("",false)); case 8 -> setup(); case 9 -> help(""); default -> dashboard(); }
    }
    private void page(String page) throws Exception {
        switch (page) {
            case "instances" -> { instance(new Args(List.of("list"))); ui.note("instance use <名称> · instance info · play · help instance"); }
            case "accounts" -> { account(new Args(List.of("list"))); ui.note("account offline <玩家名> · account microsoft · help account"); }
            case "downloads" -> { versions(new Args(List.of("list","--type","release","--limit","15"))); ui.note("install <版本> <实例名> · loader install · help versions"); }
            case "resources" -> ui.frame("资源工作区",List.of("content search sodium --type mod --game 1.20.1 --loader fabric","content install sodium --instance <实例>","pack import <整合包文件> <实例名>","mod list · content update · help content · help pack"));
            case "tasks" -> tasks(); case "settings" -> config(new Args(List.of("list"))); case "help" -> help("");
            default -> throw new IllegalArgumentException("page instances | accounts | downloads | resources | tasks | settings | help");
        }
    }
    private void help(String topic) {
        if (topic.isBlank()) {
            ui.frame("指令索引",List.of("help 入门       引号、脚本、连续指令、别名","help versions   原版下载、加载器、服务端","help instance   实例管理、外部目录、回收区","help account    微软/离线/第三方登录与皮肤","help play       启动、日志、诊断、会话","help content    Mod、光影、资源包、数据包","help pack       整合包导入导出","help java       Java、内存","help source     自定义下载源与切换","help tasks      下载任务、设置、缓存","help link       联机、局域网、音乐","help all        完整指令手册"));
        } else if (topic.equals("all")) ui.out(Help.all());
        else { String text=Help.TOPICS.get(topic); Util.require(text != null,"没有此主题"); ui.out(text); }
    }
    private void versions(Args a) throws Exception {
        switch (a.at(0,"list")) {
            case "installed","local" -> ui.table(new String[]{"ID","类型","继承"},mc.installed().stream().map(v -> List.of(Json.str(v,"id",""),Json.str(v,"type",""),Json.str(v,"inheritsFrom",""))).toList());
            case "info" -> {
                String id=a.need(1);
                if (Files.exists(mc.versionFile(id))) ui.data(mc.resolve(id).data());
                else {
                    List<Object> list=mc.remote(id,a.flag("refresh"));
                    Object found=list.stream().filter(v -> id.equals(Json.obj(v).get("id"))).findFirst().orElseThrow(() -> new IllegalArgumentException("找不到版本"));
                    ui.data(net.json(Json.str(Json.obj(found),"url",""),Net.Policy.OFFICIAL));
                }
            }
            case "list","remote" -> {
                String filter=a.at(1,""), type=a.option("type","");
                List<Object> data=mc.remote(filter,a.flag("refresh"));
                data=data.stream().filter(v -> type.isBlank() || type.equals(Json.obj(v).get("type"))).skip(a.integer("offset",0)).limit(a.integer("limit",30)).toList();
                ui.table(new String[]{"版本","类型","发布时间"},data.stream().map(v -> { Map<String,Object> m=Json.obj(v); return List.of(Json.str(m,"id",""),Json.str(m,"type",""),Json.str(m,"releaseTime","")); }).toList());
            }
            default -> throw new IllegalArgumentException("versions list | installed | info");
        }
    }
    private void instance(Args a) throws Exception {
        String sub=a.at(0,"list");
        switch (sub) {
            case "list" -> {
                List<List<String>> rows=new ArrayList<>();
                for (Map<String,Object> m : store.instances()) {
                    if (Json.bool(m,"hidden",false) && !a.flag("all")) continue;
                    String id=Json.str(m,"id","");
                    rows.add(List.of((id.equals(store.text("selected")) ? "> " : "  ")+(Json.bool(m,"favorite",false) ? "* " : "")+id,
                            Json.str(m,"version",""),Json.str(m,"loader",""),Json.str(m,"description","")));
                }
                ui.table(new String[]{"实例","版本","加载器","描述"},rows);
            }
            case "use" -> { String name=a.need(1); store.instanceConfig(name); store.set("selected",name); ui.note("已选择 "+name); }
            case "info" -> ui.data(store.instanceConfig(store.selected(a.at(1,""))));
            case "create" -> mc.createInstance(a.need(1),a.need(2),a.option("loader","vanilla"));
            case "clone" -> instances.clone(a.need(1),a.need(2),a.flag("saves"));
            case "rename" -> instances.rename(a.need(1),a.need(2));
            case "remove","delete" -> instances.remove(a.need(1));
            case "describe","star","hide","show" -> {
                String name=a.need(1); Map<String,Object> m=store.instanceConfig(name);
                if (sub.equals("describe")) m.put("description",a.need(2));
                if (sub.equals("star")) m.put("favorite",!Json.bool(m,"favorite",false));
                if (sub.equals("hide") || sub.equals("show")) m.put("hidden",sub.equals("hide"));
                store.saveInstance(name,m);
            }
            case "paths" -> {
                String name=store.selected(a.at(1,"")); Path game=store.gameDir(name);
                ui.data(Json.map("instance",store.instance(name).toString(),"game",game.toString(),"mods",game.resolve("mods").toString(),
                        "saves",game.resolve("saves").toString(),"screenshots",game.resolve("screenshots").toString(),"resourcepacks",game.resolve("resourcepacks").toString()));
            }
            case "set","reset" -> {
                String name=a.need(1),key=a.need(2); instances.idle(name);
                Util.require(Set.of("java","memoryMiB","minMemoryMiB","width","height","fullscreen","gc","jvmArgs","gameArgs","before","after","isolation","server","world","customInfo","authlibInjector").contains(key),"不是实例设置键");
                Map<String,Object> m=store.instanceConfig(name), settings=new LinkedHashMap<>(Json.child(m,"settings"));
                if (sub.equals("reset")) settings.remove(key);
                else { Object value=settingValue(key,a.need(3)); Store.validate(key,value); settings.put(key,value); }
                // Changing isolation would otherwise leave existing saves invisible; migrate explicitly instead.
                if (key.equals("isolation")) {
                    boolean old=Json.bool(store.settings(name),"isolation",true), now=Json.bool(settings,"isolation",Json.bool(store.config(),"isolation",true));
                    if (old != now) ui.note("隔离设置已更改；原存档保留在原游戏目录，可通过 instance paths 检查并手动复制。");
                }
                m.put("settings",settings); store.saveInstance(name,m);
            }
            default -> throw new IllegalArgumentException("help instance 查看实例指令");
        }
    }
    private void root(Args a) throws Exception {
        switch (a.at(0,"list")) {
            case "list" -> ui.data(Json.child(store.config(),"roots"));
            case "add" -> instances.rootAdd(a.need(1),Path.of(a.need(2)));
            case "versions" -> ui.data(instances.rootVersions(a.need(1)));
            case "remove" -> { Map<String,Object> roots=Json.child(store.config(),"roots"); roots.remove(a.need(1)); store.set("roots",roots); }
            case "adopt" -> runJob("导入外部实例",j -> instances.adopt(a.need(1),a.need(2),a.need(3),j),a.flag("bg"));
            default -> throw new IllegalArgumentException("root list | add | versions | adopt | remove");
        }
    }
    private void account(Args a) throws Exception {
        switch (a.at(0,"list")) {
            case "list" -> {
                List<List<String>> rows=new ArrayList<>();
                for (Object raw : accounts.list()) { Map<String,Object> m=Json.obj(raw); rows.add(List.of((Json.bool(m,"selected",false) ? "> " : "")+Json.str(m,"id",""),Json.str(m,"name",""),Json.str(m,"type",""),Json.str(m,"uuid",""))); }
                ui.table(new String[]{"ID","玩家名","类型","UUID"},rows);
            }
            case "use" -> accounts.use(a.need(1));
            case "remove","logout" -> accounts.remove(a.need(1));
            case "offline" -> accounts.offline(a.need(1));
            case "microsoft","ms" -> accounts.microsoft();
            case "refresh" -> { Map<String,Object> m=accounts.ready(a.at(1,""),true); ui.note("已更新账户 "+Json.str(m,"name","")); }
            case "auth" -> accounts.yggdrasil(a.need(1),a.need(2),a.at(3,""));
            case "nide" -> {
                String id=a.need(1); Util.require(id.matches("[A-Za-z0-9]+"),"统一通行证服务器 ID 格式无效");
                accounts.yggdrasil("https://auth.mc-user.com:233/"+id+"/",a.need(2),a.at(3,""));
            }
            default -> throw new IllegalArgumentException("help account 查看账户指令");
        }
    }
    private void skin(Args a) throws Exception {
        switch (a.need(0)) {
            case "download" -> accounts.skinDownload(a.need(1).equals("self") ? "" : a.need(1),Path.of(a.need(2)).toAbsolutePath());
            case "set-url" -> accounts.skinUrl(a.need(1),a.need(2));
            case "upload" -> accounts.skinUpload(a.need(1),Path.of(a.need(2)));
            case "reset" -> accounts.skinReset();
            case "offline" -> { String name=store.selected(a.at(2,"")); instances.idle(name); extras.offlineSkin(name,Path.of(a.need(1))); ui.note("本地皮肤资源包已启用，仅本机可见；不同 MC 版本可能显示资源包兼容提示。"); }
            default -> throw new IllegalArgumentException("skin download | set-url | upload | reset | offline");
        }
    }
    private void loader(Args a) throws Exception {
        switch (a.need(0)) {
            case "list" -> {
                List<Object> data=loaders.versions(a.need(1),a.need(2)).stream().limit(a.integer("limit",40)).toList();
                if (ui.json) ui.data(data);
                else {
                    List<List<String>> rows=new ArrayList<>();
                    for (Object raw : data.stream().limit(a.integer("limit",40)).toList()) {
                        Map<String,Object> m=Json.obj(raw), item=m.containsKey("loader") ? Json.child(m,"loader") : m;
                        rows.add(List.of(Json.str(item,"version",Json.str(item,"method","")),Json.str(item,"stable","")));
                    }
                    ui.table(new String[]{"加载器版本","稳定"},rows);
                }
            }
            case "install" -> runJob("安装加载器 "+a.need(1),j -> loaders.install(a.need(1),a.need(2),a.need(3),a.need(4),j),a.flag("bg"));
            case "local" -> runJob("安装本地加载器",j -> {
                String game=mc.canonical(a.need(3)); if (!Files.exists(mc.versionFile(game))) mc.install(game,null,j);
                String version=loaders.installLocal(a.need(1),Path.of(a.need(2)),game,j); mc.createInstance(a.need(4),version,a.need(1));
            },a.flag("bg"));
            default -> throw new IllegalArgumentException("loader list | install | local");
        }
    }
    private void play(Args a) throws Exception {
        String name=store.selected(a.at(0,"")); String version=Json.str(store.instanceConfig(name),"version",""); instances.idle(name);
        if (!a.flag("dry-run")) {
            if (!a.flag("no-repair")) runJob("启动前校验 "+name,j -> mc.repair(mc.resolve(version),j),false);
            try { java.choose(JavaRuntime.required(mc.resolve(version)),Json.str(store.settings(name),"java","auto")); }
            catch (IOException e) {
                Util.require(!a.flag("no-java-download") && Json.str(store.settings(name),"java","auto").equals("auto"),e.getMessage());
                int required=JavaRuntime.required(mc.resolve(version)); runJob("获取 Java "+required,j -> java.install(required,j),false);
            }
            Map<String,Object> session=launch.start(name,a.flag("wait"),a.option("server","")); if (ui.json) ui.data(session);
        } else {
            Launch.Plan plan=launch.plan(name,false,a.option("server",""));
            ui.data(Json.map("instance",name,"version",plan.version(),"directory",plan.directory().toString(),"command",plan.redacted()));
        }
    }
    private void sessionTable() throws Exception {
        ui.table(new String[]{"会话","实例","PID","状态","退出"},launch.sessions().stream().map(s -> List.of(Json.str(s,"id",""),Json.str(s,"instance",""),
                String.valueOf(Json.number(s,"pid",0)),Launch.alive(s) ? "RUNNING" : Json.str(s,"state",""),String.valueOf(s.getOrDefault("exitCode","")))).toList());
    }
    private void logs(Args a) throws Exception {
        String target=a.at(0,store.text("selected")); Path file=null; Map<String,Object> session=null;
        for (Map<String,Object> s : launch.sessions()) if (target.equals(s.get("id")) || target.equals(s.get("instance"))) { session=s; file=Path.of(Json.str(s,"log","")); break; }
        if (file == null) file=diagnostics.latestLog(target);
        int lines=a.integer("lines",80); Util.require(lines >= 1 && lines <= 20000,"日志行数 1~20000");
        ui.out(Util.redact(Util.tail(file,lines)));
        if (a.flag("follow")) {
            Util.require(session != null,"没有可跟踪的会话"); LogCursor cursor=new LogCursor(file,Files.exists(file) ? Files.size(file) : 0);
            while (Launch.alive(session)) {
                Thread.sleep(400);
                String text=cursor.read(); if (!text.isEmpty()) System.out.print(Ui.clean(Util.redact(text)));
            }
            String tail=cursor.read(); if (!tail.isEmpty()) System.out.print(Ui.clean(Util.redact(tail)));
        }
    }
    private void content(Args a) throws Exception {
        String sub=a.at(0,"search"),source=a.option("source","modrinth"),type=a.option("type","mod");
        switch (sub) {
            case "search" -> {
                List<Object> data=content.search(a.at(1,""),type,a.option("game",""),a.option("loader",""),source,a.integer("limit",20),a.integer("offset",0));
                List<List<String>> rows=new ArrayList<>();
                for (Object raw : data) { Map<String,Object> m=Json.obj(raw); rows.add(List.of(Json.str(m,"project_id",Json.str(m,"id","")),Json.str(m,"title",Json.str(m,"name","")),Json.str(m,"description",Json.str(m,"summary","")),Json.str(m,"downloads",Json.str(m,"downloadCount","")))); }
                ui.table(new String[]{"项目ID","名称","说明","下载量"},rows);
            }
            case "info" -> ui.data(content.project(a.need(1),source));
            case "versions" -> {
                List<Object> data=content.versions(a.need(1),source,a.option("game",""),a.option("loader",""));
                if (ui.json) ui.data(data); else ui.table(new String[]{"版本ID","版本","类型"},data.stream().limit(a.integer("limit",30)).map(raw -> { Map<String,Object> m=Json.obj(raw); return List.of(Json.str(m,"id",""),Json.str(m,"version_number",Json.str(m,"displayName","")),Json.str(m,"version_type",Json.str(m,"releaseType",""))); }).toList());
            }
            case "install" -> {
                String name=store.selected(a.option("instance","")); instances.idle(name);
                runJob("安装资源 "+a.need(1),j -> content.install(a.need(1),a.option("version","latest"),source,name,type,a.option("world",""),j),a.flag("bg"));
            }
            case "update" -> {
                String name=store.selected(a.at(1,"")); if (a.flag("apply")) instances.idle(name);
                runJob("检查资源更新 "+name,j -> content.update(name,a.flag("apply"),j),a.flag("bg"));
            }
            case "import" -> {
                String name=store.selected(a.option("instance","")); instances.idle(name);
                content.importLocal(name,a.need(1),Path.of(a.need(2)),a.option("world",""));
            }
            case "remove" -> {
                String name=store.selected(a.option("instance","")); instances.idle(name);
                Path dir=content.destination(name,a.need(1),a.option("world","")),file=Util.within(dir,Util.fileName(a.need(2)));
                ui.note("文件已移到回收区: "+store.trash(file,store.gameDir(name)).getFileName());
                Map<String,Object> lock=content.lock(name); List<Object> entries=new ArrayList<>(Json.list(lock,"files"));
                String relative=store.gameDir(name).relativize(file).toString().replace('\\','/');
                entries.removeIf(o -> relative.equals(Json.obj(o).get("path"))); lock.put("files",entries);
                Util.atomic(store.instance(name).resolve("content-lock.json"),Json.pretty(lock));
            }
            default -> throw new IllegalArgumentException("help content 查看资源指令");
        }
    }
    private void mods(Args a) throws Exception {
        switch (a.at(0,"list")) {
            case "list" -> {
                List<Object> data=content.local(store.selected(a.at(1,"")));
                if (ui.json) ui.data(data); else ui.table(new String[]{"文件","状态","ID","名称","版本"},data.stream().map(raw -> { Map<String,Object> m=Json.obj(raw); return List.of(Json.str(m,"file",""),Json.bool(m,"enabled",false) ? "启用" : "禁用",Json.str(m,"id",""),Json.str(m,"name",""),Json.str(m,"version","")); }).toList());
            }
            case "enable","disable" -> { String name=store.selected(a.option("instance","")); instances.idle(name); content.toggle(name,a.need(1),a.need(0).equals("enable")); }
            case "check" -> ui.data(diagnostics.doctor(a.at(1,""),false));
            default -> throw new IllegalArgumentException("mod list | enable | disable | check");
        }
    }
    private void pack(Args a) throws Exception {
        switch (a.need(0)) {
            case "import","install" -> runJob("导入整合包",j -> packs.install(Path.of(a.need(1)),a.need(2),a.flag("optional"),j),a.flag("bg"));
            case "export" -> {
                instances.idle(a.need(1));
                runJob("导出整合包",j -> packs.export(a.need(1),Path.of(a.need(2)).toAbsolutePath(),a.flag("saves"),j),a.flag("bg"));
            }
            case "fetch" -> runJob("下载并导入整合包",j -> {
                String source=a.option("source","modrinth"),project=a.need(1),requested=a.option("version","latest"); Map<String,Object> v;
                if (requested.equals("latest")) {
                    List<Object> versions=content.versions(project,source,"","");
                    Util.require(!versions.isEmpty(),"项目没有整合包版本"); v=Json.obj(versions.get(0));
                } else v=source.equals("curseforge") ? Json.child(content.cf("/mods/"+project+"/files/"+requested),"data")
                        : Json.obj(net.json("https://api.modrinth.com/v2/version/"+Util.enc(requested),Net.Policy.UPSTREAM));
                Path dir=Files.createTempDirectory(store.cache,"pack-download-");
                try {
                    Map<String,Object> entry=content.downloadVersion(project,v,source,dir,j);
                    packs.install(dir.resolve(Json.str(entry,"file","")),a.need(2),a.flag("optional"),j);
                } finally { store.trash(dir,store.cache); }
            },a.flag("bg"));
            default -> throw new IllegalArgumentException("pack import | export | fetch");
        }
    }
    private void java(Args a) throws Exception {
        switch (a.at(0,"scan")) {
            case "scan","list" -> ui.table(new String[]{"主版本","版本","架构","路径"},java.scan().stream().map(i -> List.of(Integer.toString(i.major()),i.version(),i.arch(),i.path().toString())).toList());
            case "add" -> {
                JavaRuntime.Info i=JavaRuntime.inspect(Path.of(a.need(1))); List<Object> paths=new ArrayList<>(Json.list(store.config(),"javaPaths"));
                if (!paths.contains(i.path().toString())) paths.add(i.path().toString()); store.set("javaPaths",paths); ui.data(i.json());
            }
            case "use" -> { String value=a.need(1); if (!value.equals("auto")) value=JavaRuntime.inspect(Path.of(value)).path().toString(); store.set("java",value); }
            case "available" -> ui.data(java.available(a.flag("refresh")));
            case "install" -> runJob("安装 Java "+a.need(1),j -> java.install(Integer.parseInt(a.need(1)),j),a.flag("bg"));
            case "for" -> {
                String name=store.selected(a.at(1,"")); ui.data(Json.map("instance",name,"requiredJava",JavaRuntime.required(mc.resolve(Json.str(store.instanceConfig(name),"version","")))));
            }
            default -> throw new IllegalArgumentException("java scan | add | use | available | install | for");
        }
    }
    private void tasks() {
        ui.table(new String[]{"ID","名称","状态","进度","下载量","阶段/错误"},jobs.list().stream().map(j -> List.of(Integer.toString(j.id),j.name,j.state,j.done+"/"+j.total,Util.bytes(j.bytes.get()),j.error.isBlank() ? j.stage : j.error)).toList());
    }
    private void task(Args a) throws Exception {
        switch (a.at(0,"list")) {
            case "list" -> tasks();
            case "cancel" -> jobs.cancel(Integer.parseInt(a.need(1)));
            case "wait" -> { if (a.at(1,"all").equals("all")) jobs.waitAll(); else jobs.waitFor(jobs.get(Integer.parseInt(a.need(1)))); }
            default -> throw new IllegalArgumentException("task list | cancel | wait");
        }
    }
    private void source(Args a) throws Exception {
        String sub=a.at(0,"list");
        if (Set.of("add","edit","use","remove").contains(sub)) Util.require(!jobs.active(),"下载任务运行中不能修改或切换下载源；先 task wait/cancel");
        Map<String,Object> profiles=Json.child(store.config(),"sources");
        switch (sub) {
            case "list" -> {
                List<Object> all=new ArrayList<>();
                Map<String,Object> official=Sources.official(); official.put("id","official"); official.put("readonly",true); all.add(official);
                for (var e : profiles.entrySet()) { Map<String,Object> p=Json.obj(Json.copy(e.getValue())); p.put("id",e.getKey()); p.put("readonly",false); all.add(p); }
                if (ui.json) ui.data(Json.map("active",store.text("source"),"profiles",all,"authenticationRouting","independent","default","official"));
                else ui.table(new String[]{"源","类型","版本清单","HTTP"},all.stream().map(raw -> {
                    Map<String,Object> p=Json.obj(raw); String id=Json.str(p,"id","");
                    return List.of((id.equals(store.text("source")) ? "> " : "  ")+id,Json.bool(p,"readonly",false) ? "内置" : "自定义",Json.str(p,"manifest",""),Json.bool(p,"allowHttp",false) ? "允许" : "禁用");
                }).toList());
            }
            case "show" -> { Sources.Selection selected=Sources.select(store.config(),a.at(1,store.text("source"))); ui.data(Json.map("id",selected.id(),"profile",selected.profile())); }
            case "add","edit" -> {
                String id=Util.id(a.need(1)); Util.require(!id.equals("official"),"official 为只读内置源");
                Util.require(sub.equals("add") ? !profiles.containsKey(id) : profiles.containsKey(id),sub.equals("add") ? "源名称已存在" : "下载源不存在");
                Map<String,Object> profile;
                if (sub.equals("add") || a.size() >= 3) profile=Sources.create(a.need(2),a.flag("allow-http"));
                else profile=Json.obj(Json.copy(profiles.get(id)));
                if (a.has("allow-http")) profile.put("allowHttp",a.flag("allow-http"));
                for (var e : Sources.OPTIONS.entrySet()) if (a.has(e.getKey())) profile.put(e.getValue(),a.option(e.getKey(),""));
                Sources.validateProfile(profile); profiles.put(id,profile); store.set("sources",profiles);
                ui.note("下载源已保存: "+id+"；source check "+id+" 检查，source use "+id+" 启用");
            }
            case "use" -> { String id=a.need(1); Sources.select(store.config(),id); store.set("source",id); ui.note("已切换下载源: "+id); }
            case "remove" -> {
                String id=a.need(1); Util.require(!id.equals("official") && profiles.containsKey(id),"不能删除内置源或不存在的源");
                profiles.remove(id); store.configure(Json.map("sources",profiles,"source",store.text("source").equals(id) ? "official" : store.text("source")));
                ui.note("下载源已移除: "+id+"；当前 "+store.text("source"));
            }
            case "check","test" -> ui.data(net.probeSource(a.at(1,store.text("source"))));
            default -> throw new IllegalArgumentException("source list | show | add | edit | use | remove | check");
        }
    }
    private Object settingValue(String key,String text) {
        Object def=Store.defaults().get(key); Util.require(def != null,"未知设置: "+key);
        if (def instanceof String) return text;
        if (def instanceof Number) return Long.parseLong(text);
        if (def instanceof Boolean) { Util.require(text.equals("true") || text.equals("false"),"布尔值为 true 或 false"); return Boolean.parseBoolean(text); }
        return Json.parse(text);
    }
    private void config(Args a) throws Exception {
        switch (a.at(0,"list")) {
            case "list" -> {
                Map<String,Object> config=store.config(); config.put("curseforgeKey",store.text("curseforgeKey").isBlank() ? "" : "<已加密保存>");
                ui.data(config);
            }
            case "get" -> { String key=a.need(1); Util.require(Store.defaults().containsKey(key),"未知设置"); ui.data(key.equals("curseforgeKey") ? "<隐藏>" : store.get(key)); }
            case "set" -> {
                String key=a.need(1),value=a.at(2,"");
                if (Set.of("source","sources").contains(key)) Util.require(!jobs.active(),"下载任务运行中不能修改下载源");
                if (key.equals("curseforgeKey")) {
                    char[] secret=value.isBlank() ? ui.secret("CurseForge API Key: ") : value.toCharArray();
                    try { store.set(key,new String(secret)); } finally { Arrays.fill(secret,'\0'); }
                } else { Util.require(a.size() >= 3,"config set <键> <值>"); store.set(key,settingValue(key,value)); }
                ui.note("设置已保存: "+key+(key.equals("proxy") ? "（重启后生效）" : ""));
            }
            case "reset" -> {
                String key=a.need(1); Util.require(Store.defaults().containsKey(key),"未知设置");
                if (Set.of("source","sources").contains(key)) Util.require(!jobs.active(),"下载任务运行中不能重置下载源");
                store.set(key,Store.defaults().get(key));
            }
            case "export" -> {
                Path p=Path.of(a.need(1)).toAbsolutePath(); Util.require(!Files.exists(p),"输出文件已存在");
                Map<String,Object> config=store.config(); config.put("curseforgeKey",""); config.put("account",""); Util.atomic(p,Json.pretty(config));
            }
            case "import" -> {
                Map<String,Object> incoming=Json.obj(Json.read(Path.of(a.need(1))));
                if (incoming.containsKey("source") || incoming.containsKey("sources")) Util.require(!jobs.active(),"下载任务运行中不能导入下载源设置");
                for (var e : incoming.entrySet()) Store.validate(e.getKey(),e.getValue());
                Map<String,Object> patch=Json.map();
                for (var e : incoming.entrySet()) if (!Set.of("curseforgeKey","account","schema").contains(e.getKey())) patch.put(e.getKey(),e.getValue());
                store.configure(patch);
            }
            default -> throw new IllegalArgumentException("config list | get | set | reset | export | import");
        }
    }
    private void cache(Args a) throws Exception {
        if (a.at(0,"info").equals("clean")) {
            Util.require(!jobs.active(),"请等待下载任务结束后清理缓存"); int count=0;
            for (Path p : Util.children(store.cache)) { store.trash(p,store.cache); count++; }
            ui.note("已将 "+count+" 个缓存项移至回收区");
        } else {
            long bytes=0; try (var walk=Files.walk(store.cache)) { for (Path p : walk.filter(Files::isRegularFile).toList()) bytes+=Files.size(p); }
            ui.data(Json.map("path",store.cache.toString(),"bytes",bytes,"display",Util.bytes(bytes)));
        }
    }
    private void server(Args a) throws Exception {
        switch (a.need(0)) {
            case "ping" -> ui.data(multiplayer.ping(a.need(1)));
            case "download" -> runJob("获取服务端",j -> mc.server(a.need(1),Path.of(a.need(2)).toAbsolutePath(),j),a.flag("bg"));
            default -> throw new IllegalArgumentException("server ping | download");
        }
    }
    private void link(Args a) throws Exception {
        switch (a.at(0,"status")) {
            case "host","join" -> {
                char[] secret=ui.secret("房间密码: ");
                try { ui.data(multiplayer.link(a.need(0),a.need(1),new String(secret),a.need(2),"localhost:"+a.integer("port",25565))); }
                finally { Arrays.fill(secret,'\0'); }
            }
            case "status" -> ui.data(multiplayer.status());
            case "stop" -> multiplayer.stop();
            case "log" -> { Map<String,Object> s=multiplayer.status(); Util.require(s.containsKey("log"),"没有联机日志"); ui.out(Util.redact(Util.tail(Path.of(s.get("log").toString()),80))); }
            default -> throw new IllegalArgumentException("link host | join | status | log | stop");
        }
    }
    private void alias(Args a) throws Exception {
        Map<String,Object> aliases=Json.child(store.config(),"aliases");
        switch (a.at(0,"list")) {
            case "list" -> ui.data(aliases);
            case "set" -> { String name=Util.id(a.need(1)); Util.require(!Arrays.asList(Help.COMMANDS).contains(name),"别名不能覆盖内置指令"); segments(a.need(2)); aliases.put(name,a.need(2)); store.set("aliases",aliases); }
            case "remove" -> { aliases.remove(a.need(1)); store.set("aliases",aliases); }
            default -> throw new IllegalArgumentException("alias list | set | remove");
        }
    }
    private List<String> expandAliases(List<String> words) {
        Map<String,Object> aliases=Json.child(store.config(),"aliases"); Set<String> seen=new HashSet<>();
        while (!words.isEmpty() && aliases.containsKey(words.get(0))) {
            String name=words.get(0); Util.require(seen.add(name) && seen.size() <= 16,"别名循环");
            List<Segment> expansion=segments(aliases.get(name).toString()); Util.require(expansion.size() == 1,"别名只允许单条指令；多条操作请用 script run");
            List<String> next=new ArrayList<>(expansion.get(0).words()); next.addAll(words.subList(1,words.size())); words=next;
        }
        return words;
    }
    private String variables(String s) {
        return s.replace("${selected}",store.text("selected")).replace("${account}",store.text("account")).replace("${home}",store.home.toString());
    }
    public int script(Path path) throws Exception {
        Util.require(++scriptDepth <= 16,"脚本递归过深");
        try {
            Util.require(Files.size(path) <= 2*1024*1024,"脚本超过 2 MiB");
            List<String> lines=Files.readAllLines(path,StandardCharsets.UTF_8);
            for (int i=0;i<lines.size();i++) {
                String line=lines.get(i); if (i == 0 && line.startsWith("\ufeff")) line=line.substring(1);
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                int code=execute(line); if (code != 0) { ui.note("脚本停止于 "+path.getFileName()+":"+(i+1)); return code; }
                if (exiting) break;
            }
            return 0;
        } finally { scriptDepth--; }
    }
    private void setup() throws Exception {
        ui.frame("首次配置",List.of("通过终端完成账户、版本与实例配置。","回车采用默认值；随时输入 cancel 取消。"));
        String login=ask("账户类型 offline / microsoft","offline");
        if (login.equals("microsoft")) {
            if (store.text("msClientId").isBlank()) store.set("msClientId",ask("Microsoft 应用 Client ID",""));
            accounts.microsoft();
        } else { Util.require(login.equals("offline"),"账户类型须为 offline 或 microsoft"); accounts.offline(ask("玩家名","Player")); }
        String version=ask("官方 MC 版本（latest 为最新正式版）","latest"),name=Util.id(ask("实例名","survival"));
        int memory=Integer.parseInt(ask("分配内存 MiB","2048")); store.set("memoryMiB",memory);
        runJob("配置首个实例",j -> {
            String installed=mc.install(version,name,j); int required=JavaRuntime.required(mc.resolve(installed));
            try { java.choose(required,store.text("java")); } catch (IOException e) { java.install(required,j); }
        },false);
        store.set("selected",name); ui.note("配置完成。输入 play "+name+" 启动，或 help 查看更多指令。");
    }
    private String ask(String prompt,String fallback) throws IOException {
        String answer=ui.read(prompt+(fallback.isBlank() ? "" : " ["+fallback+"]")+": ");
        Util.require(answer != null && !answer.equalsIgnoreCase("cancel"),"配置已取消");
        return answer.isBlank() ? fallback : answer.trim();
    }
    private void about() {
        ui.frame("Cairn / 石栈 "+Main.VERSION,List.of("独立 Java 17 启动器 · 纯字符界面与指令系统","功能对照参考: Meloong-Git/PCL 2.99.0","参考作者: 龙腾猫跃  https://github.com/Meloong-Git/PCL","原作者赞助: https://meloong.com/afd/a/LTCat","未复制 PCL 源代码、图形界面、Logo 或专属服务。","Minecraft 属于 Mojang / Microsoft；本工具非官方产品。","购买 Minecraft: https://www.minecraft.net/store/minecraft-java-bedrock-edition-pc","help all 查看完整指令；features 查看兼容边界。"));
    }
    private void features() {
        ui.data(Json.map(
                "implemented",List.of("字符工作区与指令/脚本/别名","Mojang 原版下载与修复","版本继承与跨平台参数/本地库","离线/微软设备代码/Yggdrasil 登录","皮肤下载/正版换肤/本地皮肤包","Java 扫描/官方运行时","隔离实例/收藏/隐藏/克隆/回收","Fabric/Quilt/Forge/NeoForge/LiteLoader/本地OptiFine","Modrinth/CurseForge 资源、依赖和更新","Mod 启停/元数据/重复检查","常见整合包导入与 Cairn 导出","后台任务/续传/校验/取消/限速","脱敏参数预览/脚本/后台游戏监护","日志/规则诊断/诊断包","服务器状态/LAN发现/EasyTier适配","终端主题/本地音频"),
                "requiresUserConfiguration",List.of("微软获准的公共客户端 ID","CurseForge API Key","第三方账户的 authlib-injector JAR","EasyTier 程序和自定义节点"),
                "notEquivalent",List.of("PCL 私有公告/更新/赞助/节点与密钥服务","PCL XAML主页、动画、主题解锁、窗口效果（按要求由终端工作区替代）","PCL 中文资源翻译库与全部特例兼容补丁","PCL 完整崩溃判定库、Windows内存整理/高性能GPU注册表设置","旧 Forge 安装器是否支持 --installClient 取决于上游版本","内置音频不包含 MP3/OGG 解码","全部账号、加载器、整合包与实际图形游戏运行尚须对应环境验证"),
                "sourcePolicy","默认 Mojang 官方；支持自定义清单、客户端、库、Assets、Java 源，保留哈希校验；账户请求独立"));
    }
    @Override public void close() { extras.close(); multiplayer.close(); jobs.close(); }

    public record Segment(List<String> words,boolean and) {}
    public static List<String> words(String input) {
        List<Segment> segments=segments(input);
        Util.require(segments.size() <= 1,"此处不允许多条指令"); return segments.isEmpty() ? List.of() : segments.get(0).words();
    }
    public static List<Segment> segments(String input) {
        List<Segment> list=new ArrayList<>(); List<String> words=new ArrayList<>(); StringBuilder b=new StringBuilder();
        char quote=0; boolean token=false,and=false;
        for (int i=0;i<input.length();i++) {
            char c=input.charAt(i);
            if (c == '\\' && i+1 < input.length() && (quote == '"' && (input.charAt(i+1) == '"' || input.charAt(i+1) == '\\') || quote == 0 && (input.charAt(i+1) == '"' || input.charAt(i+1) == '\''))) {
                b.append(input.charAt(++i)); token=true; continue;
            }
            if (quote != 0) {
                if (c == quote) quote=0; else b.append(c);
                token=true; continue;
            }
            if (c == '"' || c == '\'') { quote=c; token=true; continue; }
            if (Character.isWhitespace(c)) { if (token) { words.add(b.toString()); b.setLength(0); token=false; } continue; }
            if (c == ';' || c == '&' && i+1 < input.length() && input.charAt(i+1) == '&') {
                if (token) { words.add(b.toString()); b.setLength(0); token=false; }
                Util.require(!words.isEmpty(),"操作符前缺少指令"); list.add(new Segment(List.copyOf(words),and)); words.clear();
                and=c == '&'; if (and) i++; continue;
            }
            Util.require(c != '|' && c != '&',"不支持系统 shell 管道/后台语法；请用 ;、&& 与 --bg");
            b.append(c); token=true;
        }
        Util.require(quote == 0,"引号未闭合");
        if (token) words.add(b.toString());
        if (!words.isEmpty()) list.add(new Segment(List.copyOf(words),and));
        else Util.require(!and,"&& 后缺少指令");
        return list;
    }
    public static String quote(String s) {
        // Prefer the other quote when the value ends in a Windows backslash.
        if (!s.contains("'")) return "'"+s+"'";
        return Json.stringify(s);
    }
    public static int distance(String a,String b) {
        int[] dp=new int[b.length()+1]; for (int j=0;j<dp.length;j++) dp[j]=j;
        for (int i=1;i<=a.length();i++) { int previous=dp[0]; dp[0]=i; for (int j=1;j<=b.length();j++) { int saved=dp[j]; dp[j]=Math.min(dp[j]+1,Math.min(dp[j-1]+1,previous+(a.charAt(i-1) == b.charAt(j-1) ? 0 : 1))); previous=saved; } }
        return dp[b.length()];
    }
    public static final class Args {
        private static final Set<String> FLAGS=Set.of("bg","all","saves","refresh","deep","apply","optional","wait","dry-run","force","follow","no-repair","no-java-download","allow-http");
        private static final Set<String> VALUES=Set.of("type","limit","offset","loader","instance","source","world","version","game","lines","file","seconds","port","volume","server",
                "manifest","metadata","client","libraries","assets","java-index","java-metadata","java-files");
        private final List<String> position=new ArrayList<>();
        private final Map<String,String> options=new LinkedHashMap<>();
        public Args(List<String> words) {
            boolean parse=true;
            for (int i=0;i<words.size();i++) {
                String s=words.get(i);
                if (parse && s.equals("--")) { parse=false; continue; }
                if (parse && s.startsWith("--")) {
                    int eq=s.indexOf('='); String key=s.substring(2,eq < 0 ? s.length() : eq),value;
                    Util.require(FLAGS.contains(key) || VALUES.contains(key),"未知选项: --"+key);
                    if (eq >= 0) value=s.substring(eq+1);
                    else if (FLAGS.contains(key)) value="true";
                    else { Util.require(i+1 < words.size() && !words.get(i+1).startsWith("--"),"--"+key+" 缺少值"); value=words.get(++i); }
                    if (FLAGS.contains(key)) Util.require(Set.of("true","false").contains(value),"布尔选项需要 true/false");
                    Util.require(!options.containsKey(key),"选项重复: "+key); options.put(key,value);
                } else position.add(s);
            }
        }
        public String at(int i,String fallback) { return i < position.size() ? position.get(i) : fallback; }
        public String need(int i) { Util.require(i < position.size(),"缺少第 "+(i+1)+" 个参数；help 查看语法"); return position.get(i); }
        public String option(String key,String fallback) { return options.getOrDefault(key,fallback); }
        public boolean flag(String key) { return Boolean.parseBoolean(option(key,"false")); }
        public boolean has(String key) { return options.containsKey(key); }
        public int size() { return position.size(); }
        public int integer(String key,int fallback) {
            int value=Integer.parseInt(option(key,Integer.toString(fallback)));
            Util.require(value >= 0 && value <= 100000,"无效数值: "+key); return value;
        }
    }
}
