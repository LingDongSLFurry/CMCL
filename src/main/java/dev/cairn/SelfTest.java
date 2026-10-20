package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Deterministic integration checks using an isolated fake game, never an existing Minecraft directory. */
public final class SelfTest {
    private static int checks;
    private SelfTest() {}
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void ok(boolean condition,String name) { if (!condition) throw new AssertionError(name); checks++; }
    private static void rejects(Action work,String name) throws Exception {
        try { work.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("未拒绝: "+name);
    }
    public static void run() throws Exception {
        checks=0;
        Path work=Path.of(System.getProperty("cairn.testRoot",System.getProperty("java.io.tmpdir"))).resolve("cairn-selftest-"+UUID.randomUUID());
        Files.createDirectories(work);
        try {
            codec(); parser(); paths(work); rules();
            integration(work);
            System.out.println("PASS: "+checks+" assertions; JSON, parsing, path/ZIP defenses, official/custom sources, vault, inheritance, native/legacy assets, launch worker, redaction, instance/mod/pack lifecycle.");
            System.out.println("Fixture directory (contains no user credentials): "+work);
        } catch (Throwable e) {
            System.err.println("FAIL after "+checks+" assertions; fixture: "+work); e.printStackTrace(System.err); throw e;
        }
    }
    private static void codec() throws Exception {
        Object a=Json.map("text","中文\n\"\\","null",null,"items",List.of(1,true,"x"),"n",-2.5);
        ok(Json.stringify(Json.parse(Json.pretty(a))).equals(Json.stringify(a)),"JSON roundtrip");
        ok(Json.parse("\ufeff{\"a\":1}") instanceof Map,"BOM");
        for (String invalid : List.of("{\"x\":1,\"x\":2}","[1,]","{\"x\":}","01","1.","1e","true false","\"\\q\"","\"hello\nworld\"","{\"a\":1")) rejects(() -> Json.parse(invalid),"strict JSON "+invalid);
        ok(Json.str(Json.map("x",null),"x","default").equals("default"),"null fallback");
    }
    private static void parser() throws Exception {
        ok(Shell.words("one \"two three\" '四 五' \"\"").equals(List.of("one","two three","四 五","")),"quoted words");
        for (String s : List.of("C:\\Program Files\\Java\\bin\\java.exe","D:\\尾部路径\\","a'b","a\"b","both'and\"quote","; && ||","")) {
            ok(Shell.words(Shell.quote(s)).equals(List.of(s)),"quote roundtrip "+s);
        }
        List<Shell.Segment> segments=Shell.segments("one && two; three 'x;y'");
        ok(segments.size() == 3 && segments.get(1).and() && !segments.get(2).and(),"operators");
        for (String bad : List.of("one &&","'abc","a | b","a & b","; one")) rejects(() -> Shell.segments(bad),"bad syntax");
        Shell.Args args=new Shell.Args(List.of("x","--type=mod","--bg","--limit","4","--","--literal"));
        ok(args.at(1,"").equals("--literal") && args.flag("bg") && args.integer("limit",0) == 4,"argument options");
        rejects(() -> new Shell.Args(List.of("--unknown")),"unknown flag");
        rejects(() -> new Shell.Args(List.of("--source")),"missing option");
        rejects(() -> new Shell.Args(List.of("--bg=oops")),"invalid boolean");
        ok(Shell.distance("instal","install") == 1,"suggestions");
        ok(Ui.displayWidth("ABC中文") == 7,"terminal width");
        ok(!Ui.clean("abc\u001b[31mred\u001b[0m\u0007").contains("\u001b"),"ANSI sanitization");
    }
    private static void paths(Path work) throws Exception {
        Path root=work.resolve("safe"); Files.createDirectories(root);
        ok(Util.within(root,"sub/a.jar").startsWith(root),"safe relative");
        for (String bad : List.of("../escape","/outside","C:/Windows/test","a\\..\\bad",".")) rejects(() -> Util.within(root,bad),"path "+bad);
        for (String bad : List.of("CON","nul.jar","x:y","../x","last.","last ")) rejects(() -> Util.fileName(bad),"filename "+bad);
        for (String bad : List.of("..","a b","C:","NUL")) rejects(() -> Util.id(bad),"id "+bad);
        Path zip=work.resolve("bad.zip");
        try (ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../escaped.txt")); out.write(1); out.closeEntry();
        }
        rejects(() -> Util.unzip(zip,root.resolve("bad-unzip")),"ZIP slip");
        ok(!Files.exists(work.resolve("escaped.txt")),"ZIP did not escape");
        ok(Net.uri("http://resources.download.minecraft.net/a",Net.Policy.OFFICIAL).getScheme().equals("https"),"upgrade official HTTPS");
        for (String url : List.of("https://bmclapi2.bangbang93.com/test","https://piston-meta.mojang.com.evil.example/x","file:///etc/passwd","https://user:pass@piston-meta.mojang.com/x","https://s3.amazonaws.com/evil/a"))
            rejects(() -> Net.uri(url,Net.Policy.OFFICIAL),"source restriction");
        ok(Net.uri("https://meta.fabricmc.net/v2/",Net.Policy.UPSTREAM).getHost().equals("meta.fabricmc.net"),"upstream policy");
    }
    private static void rules() throws Exception {
        ok(Minecraft.allowed(Json.map(),Map.of()),"no rules");
        ok(Minecraft.allowed(Json.map("rules",List.of(Json.map("action","allow","os",Json.map("name",Util.OS)))),Map.of()),"OS allow");
        ok(!Minecraft.allowed(Json.map("rules",List.of(Json.map("action","allow","os",Json.map("name","impossible")))),Map.of()),"OS reject");
        ok(!Minecraft.allowed(Json.map("rules",List.of(Json.map("action","allow"),Json.map("action","disallow","os",Json.map("name",Util.OS)))),Map.of()),"last matching rule");
        Map<String,Object> feature=Json.map("rules",List.of(Json.map("action","allow","features",Json.map("has_custom_resolution",true))));
        ok(!Minecraft.allowed(feature,Map.of()),"missing feature");
        ok(Minecraft.allowed(feature,Map.of("has_custom_resolution",true)),"feature match");
        ok(Minecraft.mavenPath("org.example:lib:1.2:natives-windows").equals("org/example/lib/1.2/lib-1.2-natives-windows.jar"),"Maven classifier");
        ok(Minecraft.mavenPath("org.example:lib:1.2@zip").endsWith("lib-1.2.zip"),"Maven extension");
        rejects(() -> Minecraft.mavenPath("bad:../../bad:1"),"Maven traversal");
        ok(Launch.arguments(List.of("--name","${player}",Json.map("rules",List.of(Json.map("action","allow","features",Json.map("enable",true))),"value",List.of("a","b"))),
                Map.of("enable",true),Map.of("player","a b")).equals(List.of("--name","a b","a","b")),"argument array substitution");
        rejects(() -> Launch.expand("${not_supported}",Map.of()),"unknown placeholder");
        ok(Launch.hostPort("[::1]:25566",25565).port() == 25566,"IPv6 host port");
        rejects(() -> Launch.hostPort("host:99999",25565),"port range");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        for (int v : new int[]{0,1,127,128,255,65535,Integer.MAX_VALUE,-1}) Multiplayer.writeVarInt(out,v);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        for (int v : new int[]{0,1,127,128,255,65535,Integer.MAX_VALUE,-1}) ok(Multiplayer.readVarInt(in) == v,"VarInt "+v);
    }
    private static void integration(Path work) throws Exception {
        Path home=work.resolve("data with spaces");
        try (Store store=new Store(home)) {
            rejects(() -> { try (Store second=new Store(home)) { second.config(); } },"exclusive home lock");
            store.set("java",Path.of(System.getProperty("java.home"),"bin",Util.WINDOWS ? "java.exe" : "java").toString());
            store.set("threads",2); store.set("color","off");
            rejects(() -> store.set("source","mirror"),"official only setting");
            rejects(() -> store.set("threads",999),"thread bound");
            rejects(() -> store.set("threads",1.5),"integer setting type");
            rejects(() -> store.set("speedKiB",4294967296L),"integer setting overflow");
            store.set("curseforgeKey","fixture-cf-secret");
            ok(!Files.readString(home.resolve("config.json")).contains("fixture-cf-secret"),"API key not plaintext config");
            ok(store.text("curseforgeKey").equals("fixture-cf-secret"),"API vault roundtrip");
            Ui ui=new Ui(store); Net net=new Net(store); Minecraft mc=new Minecraft(store,net,ui);
            downloader(work,store,ui);
            sourceRouting(work,store,ui);
            Accounts accounts=new Accounts(store,net,ui); JavaRuntime runtime=new JavaRuntime(store,net);
            accounts.offline("TestPlayer"); Map<String,Object> account=accounts.find("");
            ok(Json.str(account,"uuid","").length() == 32,"offline UUID");
            account.put("accessToken","fixture-access-SECRET-value"); accounts.save(account);
            ok(!new String(Files.readAllBytes(home.resolve("accounts.enc")),StandardCharsets.ISO_8859_1).contains("fixture-access-SECRET-value"),"encrypted account");
            ok(accounts.find("").get("accessToken").equals("fixture-access-SECRET-value"),"account vault");
            Path base=mc.versionDir("fixture-base"); Files.createDirectories(base);
            try (JarOutputStream jar=new JarOutputStream(Files.newOutputStream(base.resolve("fixture-base.jar")))) {
                String resource=FixtureGame.class.getName().replace('.','/')+".class";
                jar.putNextEntry(new JarEntry(resource)); try (InputStream input=SelfTest.class.getResourceAsStream("/"+resource)) { input.transferTo(jar); } jar.closeEntry();
            }
            Path nativeFile=store.game.resolve("libraries").resolve(Minecraft.mavenPath("dev.fixture:native:1.0:natives-"+Util.OS));
            Files.createDirectories(nativeFile.getParent());
            try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(nativeFile))) {
                zip.putNextEntry(new ZipEntry("META-INF/skip.dll")); zip.write("skip".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("platform/fixture.dll")); zip.write("fixture-native".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            String nativePath=store.game.resolve("libraries").relativize(nativeFile).toString().replace('\\','/');
            Map<String,Object> nativeLib=Json.map("name","dev.fixture:native:1.0:natives-"+Util.OS,
                    "downloads",Json.map("artifact",Json.map("path",nativePath,"url","https://libraries.minecraft.net/"+nativePath,"sha1",Util.hash(nativeFile,"SHA-1"),"size",Files.size(nativeFile))));
            byte[] asset="fixture-resource".getBytes(StandardCharsets.UTF_8); String assetHash=Util.sha1(asset);
            Path object=store.game.resolve("assets/objects").resolve(assetHash.substring(0,2)).resolve(assetHash); Util.atomic(object,asset);
            Path index=store.game.resolve("assets/indexes/fixture-assets.json");
            Util.atomic(index,Json.stringify(Json.map("virtual",true,"objects",Json.map("sound/test.ogg",Json.map("hash",assetHash,"size",asset.length)))));
            Map<String,Object> data=Json.map("id","fixture-base","type","release","mainClass",FixtureGame.class.getName(),"releaseTime","2020-01-01T00:00:00Z",
                    "javaVersion",Json.map("majorVersion",Runtime.version().feature()),"libraries",List.of(nativeLib),
                    "assetIndex",Json.map("id","fixture-assets","url","https://piston-meta.mojang.com/fixture-assets.json","sha1",Util.hash(index,"SHA-1"),"size",Files.size(index)),
                    "minecraftArguments","--name ${auth_player_name} --gameDir ${game_directory} --accessToken ${auth_access_token}");
            Util.atomic(mc.versionFile("fixture-base"),Json.pretty(data));
            Util.atomic(mc.versionFile("fixture-child"),Json.pretty(Json.map("id","fixture-child","inheritsFrom","fixture-base",
                    "arguments",Json.map("game",List.of("--name","${auth_player_name}","--gameDir","${game_directory}","--accessToken","${auth_access_token}")))));
            ok(mc.resolve("fixture-child").jarId().equals("fixture-base"),"inherited client jar");
            Util.atomic(mc.versionFile("fixture-tweak"),Json.pretty(Json.map("id","fixture-tweak","inheritsFrom","fixture-base",
                    "arguments",Json.map("game",List.of("--tweakClass","example.Tweaker")))));
            List<Object> tweakArgs=Json.list(Json.child(mc.resolve("fixture-tweak").data(),"arguments"),"game");
            ok(tweakArgs.contains("--name") && tweakArgs.contains("${game_directory}") && tweakArgs.contains("example.Tweaker"),"legacy parent args retained by modern child");
            Util.atomic(mc.versionFile("fixture-legacy-child"),Json.pretty(Json.map("id","fixture-legacy-child","inheritsFrom","fixture-child",
                    "minecraftArguments","--name ${auth_player_name} --legacy-child")));
            List<Object> legacyChildArgs=Json.list(Json.child(mc.resolve("fixture-legacy-child").data(),"arguments"),"game");
            ok(legacyChildArgs.contains("--legacy-child") && !legacyChildArgs.contains("--gameDir"),"explicit legacy child arguments replace inherited game arguments");
            Util.atomic(mc.versionFile("cycle-a"),Json.pretty(Json.map("inheritsFrom","cycle-b")));
            Util.atomic(mc.versionFile("cycle-b"),Json.pretty(Json.map("inheritsFrom","cycle-a")));
            rejects(() -> mc.resolve("cycle-a"),"inheritance cycle");
            mc.createInstance("demo","fixture-base","vanilla");
            Launch launch=new Launch(store,mc,accounts,runtime,ui);
            Launch.Plan plan=launch.plan("demo",false,"");
            ok(plan.command().contains(store.gameDir("demo").toString()),"legacy paths remain single arguments");
            ok(Files.readString(store.instance("demo").resolve("natives-"+Util.OS+"-"+Util.ARCH+"/fixture.dll")).equals("fixture-native"),"native extraction flattens platform directory");
            ok(!Files.exists(store.instance("demo").resolve("natives-"+Util.OS+"-"+Util.ARCH+"/skip.dll")),"native metadata excluded");
            ok(Files.readString(store.game.resolve("assets/virtual/fixture-assets/sound/test.ogg")).equals("fixture-resource"),"legacy virtual assets mapping");
            Path stream=work.resolve("incremental-utf8.log"); byte[] chinese="中文\n".getBytes(StandardCharsets.UTF_8);
            Files.write(stream,Arrays.copyOf(chinese,2)); LogCursor cursor=new LogCursor(stream,0);
            ok(cursor.read().isEmpty(),"incomplete UTF-8 retained");
            Files.write(stream,Arrays.copyOfRange(chinese,2,chinese.length),StandardOpenOption.APPEND);
            ok(cursor.read().equals("中文\n") && cursor.read().isEmpty(),"UTF-8 incremental no duplicates");
            Files.writeString(stream,"new\n",StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING);
            ok(cursor.read().equals("new\n"),"log truncation");
            ok(plan.redacted().stream().noneMatch(s -> s.contains("fixture-access-SECRET-value")),"dry run redaction");
            Map<String,Object> session=launch.start("demo",true,"");
            ok(Json.number(session,"exitCode",-1) == 0,"actual child process");
            String log=Files.readString(Path.of(Json.str(session,"log","")));
            ok(log.contains("FIXTURE_GAME_OK"),"game worker captures output");
            ok(!log.contains("fixture-access-SECRET-value"),"worker redacts secret");
            ok(!Launch.alive(session),"ended process is not live");
            Content content=new Content(store,net,mc,ui); Instances instances=new Instances(store,mc,launch,ui);
            Path mod=work.resolve("example.jar");
            try (JarOutputStream jar=new JarOutputStream(Files.newOutputStream(mod))) {
                jar.putNextEntry(new JarEntry("fabric.mod.json")); jar.write(Json.stringify(Json.map("id","example","name","Example","version","1.0","depends",Json.map("minecraft","*"))).getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
            }
            content.importLocal("demo","mod",mod,"");
            ok(Json.str(Json.obj(content.local("demo").get(0)),"id","").equals("example"),"mod metadata");
            content.toggle("demo","example.jar",false); ok(Files.exists(store.gameDir("demo").resolve("mods/example.jar.disabled")),"mod disable");
            content.toggle("demo","example.jar.disabled",true); ok(Files.exists(store.gameDir("demo").resolve("mods/example.jar")),"mod enable");
            instances.clone("demo","cloned",false); ok(Files.exists(store.gameDir("cloned").resolve("mods/example.jar")),"clone copies mods");
            instances.rename("cloned","renamed"); ok(store.instanceConfig("renamed").get("id").equals("renamed"),"rename id");
            instances.remove("renamed"); ok(!Files.exists(store.instance("renamed")),"remove to trash");
            List<Object> trash=instances.trashList(); String item=Json.str(Json.obj(trash.get(trash.size()-1)),"name","");
            instances.restore(item); ok(Files.exists(store.instance("renamed")),"restore instance");
            Path script=work.resolve("safe-launch.cmd"); launch.script("demo",script);
            ok(!Files.readString(script).contains("fixture-access-SECRET-value"),"export script contains no credential");
            Diagnostics diag=new Diagnostics(store,mc,runtime,content,ui);
            Path crash=work.resolve("crash.log"); Util.atomic(crash,"java.lang.OutOfMemoryError: Java heap space\n");
            ok(Json.str(Json.obj(diag.analyze(crash).get(0)),"cause","").contains("内存"),"diagnosis evidence");
            Path report=work.resolve("report.zip"); diag.report("demo",report);
            try (ZipFile zip=new ZipFile(report.toFile())) { ok(zip.getEntry("doctor.json") != null && zip.getEntry("latest-redacted.log") != null,"report archive"); }
            Loaders loaders=new Loaders(store,net,mc,runtime,ui); Packs packs=new Packs(store,net,mc,loaders,content,ui);
            Jobs.Job job=new Jobs.Job(0,"fixture export",ui); Path pack=work.resolve("export.zip"); packs.export("demo",pack,false,job);
            try (ZipFile zip=new ZipFile(pack.toFile())) { ok(zip.getEntry("cairn-pack.json") != null && zip.getEntry("overrides/mods/example.jar") != null,"pack export"); }
            FakeClient fake=new FakeClient(new byte[0]);
            fake.routes.put(Minecraft.MANIFEST,Json.stringify(Json.map("latest",Json.map("release","fixture-base","snapshot","fixture-base"),
                    "versions",List.of(Json.map("id","fixture-base","url","https://piston-meta.mojang.com/fixture-base.json","sha1",Util.hash(mc.versionFile("fixture-base"),"SHA-1"))))).getBytes(StandardCharsets.UTF_8));
            Net fixtureNet=new Net(store,fake); Minecraft fixtureMc=new Minecraft(store,fixtureNet,ui);
            Content fixtureContent=new Content(store,fixtureNet,fixtureMc,ui);
            Packs fixturePacks=new Packs(store,fixtureNet,fixtureMc,new Loaders(store,fixtureNet,fixtureMc,runtime,ui),fixtureContent,ui);
            fixturePacks.install(pack,"roundtrip",false,new Jobs.Job(0,"fixture import",ui));
            ok(Files.exists(store.gameDir("roundtrip").resolve("mods/example.jar")),"pack import roundtrip");
            ok(Json.str(store.instanceConfig("roundtrip"),"version","").equals("fixture-base"),"imported version");
            try (Shell shell=new Shell(store)) {
                ok(shell.execute("config set width 1000 && config set height 700") == 0,"shell command chain");
                ok(store.integer("width") == 1000 && store.integer("height") == 700,"chain state");
                ok(shell.execute("no_such_command && config set width 1 ; config set height 720") == 0,"conditional continuation");
                ok(store.integer("width") == 1000 && store.integer("height") == 720,"conditional state");
                ok(shell.execute("alias set go 'instance use demo' ; go") == 0,"aliases");
                Path own=work.resolve("workflow.cairn"); Util.atomic(own,"# test\ninstance use demo\ninstance describe demo \"fixture description\"\n");
                ok(shell.script(own) == 0 && Json.str(store.instanceConfig("demo"),"description","").equals("fixture description"),"script execution");
            }
        }
        try (Store reopened=new Store(home)) { ok(reopened.text("selected").equals("demo") && reopened.accounts().size() == 1,"persisted configuration and credentials"); }
    }
    private static void downloader(Path work,Store store,Ui ui) throws Exception {
        byte[] data="real stream bytes for fixture checksum and range".getBytes(StandardCharsets.UTF_8);
        FakeClient client=new FakeClient(data); Net net=new Net(store,client); Jobs.Job job=new Jobs.Job(0,"downloader fixture",ui);
        Path dir=work.resolve("download-fixtures"); Files.createDirectories(dir);
        Path path=dir.resolve("full.bin");
        Net.File file=new Net.File("https://piston-data.mojang.com/file",path,Util.sha1(data),data.length,Net.Policy.OFFICIAL);
        net.download(file,job); ok(Arrays.equals(Files.readAllBytes(path),data),"full streamed download");
        int calls=client.calls; net.download(file,job); ok(client.calls == calls,"hash cache avoids request");
        Path resume=dir.resolve("resume.bin"); Files.write(resume.resolveSibling("resume.bin.part"),Arrays.copyOf(data,8));
        net.download(new Net.File(file.url(),resume,file.hash(),data.length,file.policy()),job);
        ok(client.lastRange.equals("bytes=8-") && Arrays.equals(Files.readAllBytes(resume),data),"Range resume");
        Path ignored=dir.resolve("ignored-range.bin"); Files.write(ignored.resolveSibling("ignored-range.bin.part"),new byte[]{0,0,0});
        client.ignoreRange=true;
        net.download(new Net.File(file.url(),ignored,file.hash(),data.length,file.policy()),job);
        ok(Arrays.equals(Files.readAllBytes(ignored),data),"server ignoring Range truncates partial"); client.ignoreRange=false;
        Path corrupt=dir.resolve("corrupt.bin"); Files.write(corrupt.resolveSibling("corrupt.bin.part"),new byte[]{0,0,0});
        int before=client.calls;
        net.download(new Net.File(file.url(),corrupt,file.hash(),data.length,file.policy()),job);
        ok(client.calls >= before+2 && Arrays.equals(Files.readAllBytes(corrupt),data),"bad partial retries from zero");
        client.redirect="https://unapproved.example/evil";
        rejects(() -> net.download(new Net.File(file.url(),dir.resolve("redirect.bin"),file.hash(),data.length,file.policy()),job),"redirect host restrictions");
        client.redirect="";
        Jobs.Job cancelled=new Jobs.Job(0,"cancelled",ui); cancelled.cancelled=true;
        rejects(() -> net.download(new Net.File(file.url(),dir.resolve("cancel.bin"),file.hash(),data.length,file.policy()),cancelled),"download cancellation");
        ok(!Files.exists(dir.resolve("cancel.bin")),"cancelled file not committed");
    }
    private static void sourceRouting(Path work,Store store,Ui ui) throws Exception {
        ok(store.text("source").equals("official") && Json.child(store.config(),"sources").isEmpty(),"official is the only built-in source");
        rejects(() -> Sources.create("http://127.0.0.1:1234",false),"HTTP source requires opt-in");
        rejects(() -> Sources.create("https://user:pass@example.com",false),"no embedded source credentials");
        rejects(() -> Sources.create("file:///tmp",true),"source protocol");
        try (LocalHttp server=new LocalHttp()) {
            String base=server.base()+"/mirror";
            Map<String,Object> profile=Sources.create(base,true);
            Map<String,Object> profiles=Json.map("localMirror",profile);
            store.set("sources",profiles);
            Sources.Selection selected=Sources.select(store.config(),"localMirror");
            ok(selected.resolve(Minecraft.MANIFEST,Sources.Kind.AUTO).toString().equals(base+"/mc/game/version_manifest_v2.json"),"manifest endpoint routing");
            profile.put("assets",server.base()+"/asset-cdn");
            profile.put("javaMetadata",server.base()+"/jmeta"); profile.put("javaFiles",server.base()+"/jfiles");
            store.set("sources",Json.map("localMirror",profile)); selected=Sources.select(store.config(),"localMirror");
            ok(selected.resolve("https://piston-meta.mojang.com/a.json",Sources.Kind.JAVA_METADATA).toString().equals(server.base()+"/jmeta/a.json"),"independent Java metadata");
            ok(selected.resolve("https://piston-data.mojang.com/runtime.bin",Sources.Kind.JAVA_FILE).toString().equals(server.base()+"/jfiles/runtime.bin"),"independent Java files");
            ok(selected.resolve("https://resources.download.minecraft.net/ab/hash",Sources.Kind.ASSET).toString().equals(server.base()+"/asset-cdn/ab/hash"),"independent asset endpoint");
            Sources.Selection custom=selected;
            rejects(() -> custom.redirect(URI.create("http://unconfigured.example/a")),"HTTP redirect origin boundary");
            rejects(() -> custom.redirect(URI.create("https://user:password@cdn.example/a")),"redirect credentials");
            ok(custom.redirect(URI.create("https://cdn.example/a")).getHost().equals("cdn.example"),"custom source HTTPS CDN support");
            byte[] client="client fixture".getBytes(StandardCharsets.UTF_8),lib="library fixture".getBytes(StandardCharsets.UTF_8),asset="asset fixture".getBytes(StandardCharsets.UTF_8);
            String clientHash=Util.sha1(client),libHash=Util.sha1(lib),assetHash=Util.sha1(asset);
            String libPath="org/fixture/source-lib/1/source-lib-1.jar";
            byte[] index=Json.stringify(Json.map("objects",Json.map("fixture.txt",Json.map("hash",assetHash,"size",asset.length)))).getBytes(StandardCharsets.UTF_8);
            Map<String,Object> version=Json.map("id","source-fixture","mainClass","fixture.Main","javaVersion",Json.map("majorVersion",17),
                    "downloads",Json.map("client",Json.map("url","https://piston-data.mojang.com/v1/objects/"+clientHash+"/client.jar","sha1",clientHash,"size",client.length)),
                    "libraries",List.of(Json.map("name","org.fixture:source-lib:1","downloads",Json.map("artifact",Json.map("url","https://libraries.minecraft.net/"+libPath,"path",libPath,"sha1",libHash,"size",lib.length)))),
                    "assetIndex",Json.map("id","source-assets","url","https://piston-meta.mojang.com/assets-fixture.json","sha1",Util.sha1(index),"size",index.length));
            byte[] metadata=Json.stringify(version).getBytes(StandardCharsets.UTF_8);
            Map<String,Object> manifest=Json.map("latest",Json.map("release","source-fixture","snapshot","source-fixture"),
                    "versions",List.of(Json.map("id","source-fixture","type","release","url","https://piston-meta.mojang.com/version-fixture.json","sha1",Util.sha1(metadata))));
            server.routes.put("/mirror/mc/game/version_manifest_v2.json",Json.stringify(manifest).getBytes(StandardCharsets.UTF_8));
            server.routes.put("/mirror/version-fixture.json",metadata); server.routes.put("/mirror/assets-fixture.json",index);
            server.routes.put("/mirror/v1/objects/"+clientHash+"/client.jar",client); server.routes.put("/mirror/"+libPath,lib);
            server.routes.put("/asset-cdn/"+assetHash.substring(0,2)+"/"+assetHash,asset);
            HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI u) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI u,SocketAddress a,IOException e) {}
            }).build();
            Net routed=new Net(store,http);
            Map<String,Object> check=routed.probeSource("localMirror");
            ok(check.get("status").equals("OK") && check.get("metadataHash").equals("verified"),"live local source check with SHA-1");
            ok(store.text("source").equals("official"),"source check preserves active source");
            store.set("source","localMirror");
            Minecraft customMc=new Minecraft(store,routed,ui); Jobs.Job job=new Jobs.Job(0,"custom source fixture",ui);
            customMc.install("source-fixture","sourceFixture",job);
            ok(Arrays.equals(Files.readAllBytes(customMc.versionDir("source-fixture").resolve("source-fixture.jar")),client),"custom client transfer with checksum");
            ok(Arrays.equals(Files.readAllBytes(store.game.resolve("libraries").resolve(libPath)),lib),"custom library transfer");
            ok(Arrays.equals(Files.readAllBytes(store.game.resolve("assets/objects").resolve(assetHash.substring(0,2)).resolve(assetHash)),asset),"custom asset transfer");
            JavaRuntime customJava=new JavaRuntime(store,routed);
            server.routes.put("/mirror"+URI.create(JavaRuntime.INDEX).getRawPath(),Json.stringify(Json.map(customJava.platform(),Json.map("fixture-runtime",List.of()))).getBytes(StandardCharsets.UTF_8));
            ok(customJava.available(false).containsKey("fixture-runtime"),"custom Java index HTTP routing");
            byte[] runtimeMeta="{}".getBytes(StandardCharsets.UTF_8),runtimeFile="runtime fixture".getBytes(StandardCharsets.UTF_8);
            server.routes.put("/jmeta/runtime-manifest.json",runtimeMeta); server.routes.put("/jfiles/runtime-file.bin",runtimeFile);
            Path metaOut=work.resolve("custom-java-manifest.json"),fileOut=work.resolve("custom-java-file.bin");
            routed.download(new Net.File("https://piston-meta.mojang.com/runtime-manifest.json",metaOut,Util.sha1(runtimeMeta),runtimeMeta.length,Net.Policy.OFFICIAL,Sources.Kind.JAVA_METADATA),job);
            routed.download(new Net.File("https://piston-data.mojang.com/runtime-file.bin",fileOut,Util.sha1(runtimeFile),runtimeFile.length,Net.Policy.OFFICIAL,Sources.Kind.JAVA_FILE),job);
            ok(Files.size(metaOut) == runtimeMeta.length && Arrays.equals(Files.readAllBytes(fileOut),runtimeFile),"custom Java metadata and file HTTP routing");
            FakeClient isolated=new FakeClient("{}".getBytes(StandardCharsets.UTF_8));
            new Net(store,isolated).json("https://api.minecraftservices.com/minecraft/profile",Net.Policy.UPSTREAM);
            ok(isolated.lastUri.getHost().equals("api.minecraftservices.com"),"authentication API bypasses download router");
            Map<String,Object> second=Json.map("latest",Json.map("release","second","snapshot","second"),"versions",List.of(Json.map("id","second")));
            server.routes.put("/mirror/alternate-manifest.json",Json.stringify(second).getBytes(StandardCharsets.UTF_8));
            profile.put("manifest",base+"/alternate-manifest.json"); store.set("sources",Json.map("localMirror",profile));
            ok(Json.str(Json.obj(Json.list(customMc.manifest(false),"versions").get(0)),"id","").equals("second"),"editing source invalidates metadata cache");
            try (Shell shell=new Shell(store)) {
                ok(shell.execute("source add duplicate "+Shell.quote(base)+" --allow-http") == 0,"source add command");
                ok(shell.execute("source edit duplicate --assets "+Shell.quote(server.base()+"/asset-cdn")) == 0,"source edit command");
                ok(shell.execute("source use duplicate") == 0 && store.text("source").equals("duplicate"),"source use command");
                CountDownLatch release=new CountDownLatch(1);
                Jobs.Job busy=shell.jobs.submit("source lock fixture",j -> { release.await(5,TimeUnit.SECONDS); });
                try { ok(shell.execute("source use official") != 0 && store.text("source").equals("duplicate"),"running jobs prevent source switching"); }
                finally { release.countDown(); shell.jobs.waitFor(busy); }
                ok(shell.execute("source remove duplicate") == 0 && store.text("source").equals("official"),"removing active source restores official");
                ok(shell.execute("source remove official") != 0,"official source cannot be removed");
                Path exported=work.resolve("source-settings.json");
                ok(shell.execute("config export "+Shell.quote(exported.toString())) == 0,"source settings export");
                store.set("source","localMirror");
                ok(shell.execute("config import "+Shell.quote(exported.toString())) == 0 && store.text("source").equals("official")
                        && Json.child(store.config(),"sources").containsKey("localMirror"),"source settings atomic import");
                rejects(() -> store.configure(Json.map("source","missing","sources",Json.map())),"source import cannot create dangling selection");
            }
            store.configure(Json.map("source","official","sources",Json.map(),"selected",""));
            ok(server.requests.get() >= 8,"source routing used actual loopback HTTP requests");
            ok(Sources.active(store.config()).resolve(Minecraft.MANIFEST,Sources.Kind.AUTO).toString().equals(Minecraft.MANIFEST),"official route remains unchanged");
        }
    }
    private static final class LocalHttp implements AutoCloseable {
        final ServerSocket listener;
        final Map<String,byte[]> routes=new ConcurrentHashMap<>();
        final AtomicInteger requests=new AtomicInteger();
        final Thread thread;
        LocalHttp() throws IOException {
            listener=new ServerSocket(0,30,InetAddress.getByName("127.0.0.1"));
            thread=new Thread(() -> {
                while (!listener.isClosed()) try (Socket socket=listener.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String first=input.readLine(),line;
                    if (first == null) continue;
                    while ((line=input.readLine()) != null && !line.isEmpty()) {}
                    String path=URI.create(first.split(" ")[1]).getRawPath(); requests.incrementAndGet();
                    byte[] body=routes.get(path); int code=body == null ? 404 : 200;
                    if (body == null) body="not found".getBytes(StandardCharsets.UTF_8);
                    OutputStream out=socket.getOutputStream();
                    out.write(("HTTP/1.1 "+code+" "+(code == 200 ? "OK" : "Not Found")+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body); out.flush();
                } catch (IOException ignored) {}
            },"source-loopback-fixture"); thread.setDaemon(true); thread.start();
        }
        String base() { return "http://127.0.0.1:"+listener.getLocalPort(); }
        @Override public void close() throws IOException { listener.close(); }
    }
    private static final class FakeClient extends HttpClient {
        final byte[] data; final Map<String,byte[]> routes=new ConcurrentHashMap<>();
        volatile int calls; volatile String lastRange="",redirect=""; volatile boolean ignoreRange; volatile URI lastUri;
        FakeClient(byte[] data) { this.data=data; }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception e) { throw new IllegalStateException(e); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
            calls++; lastUri=request.uri(); lastRange=request.headers().firstValue("Range").orElse(""); int offset=0,status=200;
            byte[] all=routes.getOrDefault(request.uri().toString(),data); Map<String,List<String>> headers=new LinkedHashMap<>();
            if (!redirect.isBlank()) { status=302; headers.put("location",List.of(redirect)); }
            else if (!lastRange.isBlank() && !ignoreRange) {
                offset=Integer.parseInt(lastRange.substring(6,lastRange.length()-1)); status=206;
                headers.put("content-range",List.of("bytes "+offset+"-"+(all.length-1)+"/"+all.length));
            }
            @SuppressWarnings("unchecked") T body=(T)new ByteArrayInputStream(Arrays.copyOfRange(all,offset,all.length));
            final int code=status;
            return new HttpResponse<>() {
                @Override public int statusCode() { return code; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(headers,(k,v) -> true); }
                @Override public T body() { return body; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h) { return CompletableFuture.completedFuture(send(r,h)); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p) { return sendAsync(r,h); }
    }
    public static final class FixtureGame {
        public static void main(String[] args) {
            if (args.length < 4) { System.err.println("missing fixture arguments"); System.exit(3); }
            System.out.println("FIXTURE_GAME_OK");
            System.out.println("access_token=fixture-access-SECRET-value");
            System.out.println("args="+String.join(" ",args));
        }
    }
}
