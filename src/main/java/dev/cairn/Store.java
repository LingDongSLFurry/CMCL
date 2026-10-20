package dev.cairn;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class Store implements AutoCloseable {
    public final Path home, game, instances, cache, trash;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private Map<String,Object> config;
    private final SecureRandom random = new SecureRandom();
    public static Map<String,Object> defaults() {
        return Json.map("schema",1,"source","official","sources",Json.map(),"threads",8,"retries",3,"speedKiB",0,
                "memoryMiB",2048,"minMemoryMiB",256,"width",960,"height",540,"fullscreen",false,
                "isolation",true,"gc","g1","java","auto","jvmArgs",List.of(),"gameArgs",List.of(),
                "before",List.of(),"after",List.of(),"selected","","account","","msClientId","","curseforgeKey","",
                "color","auto","ascii",false,"proxy","","aliases",Json.map(),"javaPaths",List.of(),"banner","CAIRN / 石栈",
                "authlibInjector","","easyTier","","customInfo","Cairn","server","","world","",
                "roots",Json.map(),"hiddenCommands",List.of(),"maxHistory",200);
    }
    public Store(Path root) throws Exception {
        home = root.toAbsolutePath().normalize(); Files.createDirectories(home);
        Util.require(!Files.isSymbolicLink(home),"数据目录不能为符号链接");
        lockChannel = FileChannel.open(home.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock got;
        try { got = lockChannel.tryLock(); } catch (OverlappingFileLockException e) { got = null; }
        if (got == null) { lockChannel.close(); throw new IOException("已有启动器使用这个数据目录: " + home); }
        lock = got;
        game = home.resolve("minecraft"); instances = home.resolve("instances"); cache = home.resolve("cache"); trash = home.resolve("trash");
        for (Path p : List.of(game,instances,cache,trash,home.resolve("logs"),game.resolve("versions"),game.resolve("libraries"),game.resolve("assets"))) Files.createDirectories(p);
        config = defaults();
        if (Files.exists(home.resolve("config.json"))) config.putAll(Json.obj(Json.read(home.resolve("config.json"))));
        Sources.validateSelection(config);
    }
    public synchronized Map<String,Object> config() { return Json.obj(Json.copy(config)); }
    public synchronized Object get(String key) { return config.get(key); }
    public synchronized String text(String key) {
        if (key.equals("curseforgeKey")) {
            try {
                Path file=home.resolve("secrets.enc");
                return Files.exists(file) ? Json.str(Json.obj(Json.parse(new String(crypt(Cipher.DECRYPT_MODE,Files.readAllBytes(file)),StandardCharsets.UTF_8))),key,"") : Json.str(config,key,"");
            } catch (Exception e) { throw new IllegalStateException("无法解密 API 密钥，请重新设置",e); }
        }
        return Json.str(config,key,"");
    }
    public synchronized int integer(String key) { return Json.integer(config,key,0); }
    public synchronized void set(String key,Object value) throws IOException {
        validate(key,value);
        if (key.equals("curseforgeKey")) {
            try {
                Util.atomic(home.resolve("secrets.enc"),crypt(Cipher.ENCRYPT_MODE,Json.stringify(Json.map(key,value)).getBytes(StandardCharsets.UTF_8)));
                restrict(home.resolve("secrets.enc")); config.put(key,""); save(); return;
            } catch (Exception e) { throw new IOException("保存 API 密钥失败",e); }
        }
        Map<String,Object> candidate=Json.obj(Json.copy(config)); candidate.put(key,Json.copy(value)); Sources.validateSelection(candidate);
        config=candidate; save();
    }
    public static void validate(String key,Object value) {
        Map<String,Object> defs = defaults(); Util.require(defs.containsKey(key),"未知设置: " + key);
        Object def = defs.get(key);
        Util.require((def instanceof Number && value instanceof Number) || (def instanceof Boolean && value instanceof Boolean)
                || (def instanceof String && value instanceof String) || (def instanceof List && value instanceof List)
                || (def instanceof Map && value instanceof Map),"设置类型不匹配: " + key);
        if (def instanceof Number) {
            try { new java.math.BigDecimal(value.toString()).intValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("设置必须为 32 位整数: "+key); }
        }
        if (key.equals("schema")) Util.require(((Number)value).intValue() == 1,"不支持的配置 schema");
        if (key.equals("source")) Util.id(value.toString());
        if (key.equals("sources")) Sources.validateProfiles(value);
        if (Set.of("threads","retries","memoryMiB","minMemoryMiB","width","height","speedKiB","maxHistory").contains(key)) {
            int n = ((Number)value).intValue();
            int min = key.equals("speedKiB") ? 0 : key.contains("Memory") || key.equals("memoryMiB") ? 128 : 1;
            int max = switch (key) { case "threads" -> 32; case "retries" -> 8; case "width","height" -> 16384; case "memoryMiB","minMemoryMiB" -> 1048576; case "maxHistory" -> 10000; default -> 1048576; };
            Util.require(n >= min && n <= max,"设置超出范围: " + min + "~" + max);
        }
        if (key.equals("gc")) Util.require(Set.of("g1","zgc","none").contains(value),"gc = g1 | zgc | none");
        if (key.equals("color")) Util.require(Set.of("auto","on","off").contains(value),"color = auto | on | off");
        if (Set.of("jvmArgs","gameArgs","before","after","javaPaths","hiddenCommands").contains(key))
            for (Object item : Json.arr(value)) Util.require(item instanceof String,"数组只允许字符串");
        if (Set.of("aliases","roots").contains(key))
            for (var e : Json.obj(value).entrySet()) { Util.id(e.getKey()); Util.require(e.getValue() instanceof String,"映射的值必须为字符串"); }
    }
    public synchronized void save() throws IOException { Util.atomic(home.resolve("config.json"),Json.pretty(config)); }
    public synchronized void configure(Map<String,Object> patch) throws IOException {
        Map<String,Object> candidate=Json.obj(Json.copy(config));
        for (var e : patch.entrySet()) { Util.require(!e.getKey().equals("curseforgeKey"),"密钥请通过 config set 保存"); validate(e.getKey(),e.getValue()); candidate.put(e.getKey(),Json.copy(e.getValue())); }
        Sources.validateSelection(candidate); config=candidate; save();
    }
    public Path instance(String name) throws IOException { return Util.within(instances,Util.id(name)); }
    public synchronized Map<String,Object> instanceConfig(String name) throws IOException {
        Path file = instance(name).resolve("instance.json");
        Util.require(Files.isRegularFile(file),"实例不存在: " + name);
        Map<String,Object> m = Json.obj(Json.read(file));
        Util.require(name.equals(Json.str(m,"id","")),"实例 id 不匹配"); return m;
    }
    public synchronized void saveInstance(String name,Map<String,Object> m) throws IOException {
        Util.id(name); m.put("id",name); Util.atomic(instance(name).resolve("instance.json"),Json.pretty(m));
    }
    public synchronized List<Map<String,Object>> instances() throws IOException {
        List<Map<String,Object>> list = new ArrayList<>();
        for (Path p : Util.children(instances)) if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("instance.json"))) {
            try { list.add(instanceConfig(p.getFileName().toString())); } catch (Exception e) { System.err.println("跳过损坏实例 " + p.getFileName() + ": " + e.getMessage()); }
        }
        list.sort(Comparator.<Map<String,Object>,Boolean>comparing(m -> !Json.bool(m,"favorite",false)).thenComparing(m -> Json.str(m,"id","")));
        return list;
    }
    public synchronized String selected(String explicit) {
        String s = explicit == null || explicit.isBlank() ? text("selected") : explicit;
        Util.require(!s.isBlank(),"请先 instance use <名称>"); return Util.id(s);
    }
    public Path gameDir(String name) throws IOException {
        Map<String,Object> m = instanceConfig(name);
        boolean isolated = Json.bool(Json.child(m,"settings"),"isolation",Json.bool(config(),"isolation",true));
        Path p = isolated ? instance(name).resolve("game") : game.resolve("shared");
        Files.createDirectories(p); return p;
    }
    public Map<String,Object> settings(String name) throws IOException {
        Map<String,Object> m = config(); m.putAll(Json.child(instanceConfig(name),"settings")); return m;
    }
    public synchronized List<Object> accounts() throws Exception {
        Path p = home.resolve("accounts.enc");
        if (!Files.exists(p)) return new ArrayList<>();
        return Json.arr(Json.parse(new String(crypt(Cipher.DECRYPT_MODE,Files.readAllBytes(p)),StandardCharsets.UTF_8)));
    }
    public synchronized void accounts(List<Object> accounts) throws Exception {
        Util.atomic(home.resolve("accounts.enc"),crypt(Cipher.ENCRYPT_MODE,Json.stringify(accounts).getBytes(StandardCharsets.UTF_8)));
        restrict(home.resolve("accounts.enc"));
    }
    private byte[] crypt(int mode, byte[] data) throws Exception {
        Path key = home.resolve("vault.key");
        if (!Files.exists(key)) {
            Util.require(mode == Cipher.ENCRYPT_MODE,"凭据密钥丢失，请重新登录");
            byte[] bytes = new byte[32]; random.nextBytes(bytes); Util.atomic(key,bytes); restrict(key);
        }
        byte[] k = Files.readAllBytes(key); Util.require(k.length == 32,"凭据密钥损坏");
        byte[] iv = new byte[12]; if (mode == Cipher.ENCRYPT_MODE) random.nextBytes(iv); else {
            Util.require(data.length >= 28,"凭据文件损坏"); System.arraycopy(data,0,iv,0,12);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(k,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD("Cairn vault v1".getBytes(StandardCharsets.UTF_8));
        if (mode == Cipher.DECRYPT_MODE) return cipher.doFinal(data,12,data.length-12);
        byte[] encrypted = cipher.doFinal(data), out = Arrays.copyOf(iv,12+encrypted.length);
        System.arraycopy(encrypted,0,out,12,encrypted.length); return out;
    }
    private void restrict(Path p) throws IOException {
        if (!Util.WINDOWS) Files.setPosixFilePermissions(p,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        else {
            var view = Files.getFileAttributeView(p,java.nio.file.attribute.AclFileAttributeView.class);
            if (view != null) {
                var owner = Files.getOwner(p);
                var entry = java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                        .setPrincipal(owner).setPermissions(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build();
                view.setAcl(List.of(entry));
            }
        }
    }
    public synchronized Path trash(Path p,Path allowedRoot) throws IOException {
        Path root = allowedRoot.toAbsolutePath().normalize(), target = p.toAbsolutePath().normalize();
        Util.require(target.startsWith(root) && !target.equals(root) && Files.exists(target),"回收目标越界/不存在");
        Util.within(root,root.relativize(target).toString().replace('\\','/'));
        Path dest = trash.resolve(Util.timestamp()+"-"+UUID.randomUUID().toString().substring(0,8)+"-"+target.getFileName());
        Util.move(target,dest,false);
        Util.atomic(dest.resolveSibling(dest.getFileName()+".receipt.json"),Json.pretty(Json.map("from",target.toString(),"to",dest.toString())));
        return dest;
    }
    @Override public void close() throws IOException { lock.release(); lockChannel.close(); }
}
