package dev.cairn;

import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

public final class Util {
    private Util() {}
    public static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    public static final String OS = WINDOWS ? "windows" : System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? "osx" : "linux";
    public static final String ARCH = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    public static final boolean ARM = ARCH.contains("aarch") || ARCH.contains("arm");
    public static void require(boolean b, String message) { if (!b) throw new IllegalArgumentException(message); }
    public static String id(String s) {
        require(s != null && s.matches("[A-Za-z0-9_][A-Za-z0-9_.+-]{0,99}") && !s.endsWith(".")
                && !s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"), "名称只能包含字母、数字、_ . + -，长度 1~100，不能使用系统保留名");
        return s;
    }
    public static Path within(Path root, String relative) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        require(relative != null && !relative.contains("\0") && !relative.contains("\\") && !relative.matches("^[A-Za-z]:.*"), "不安全的相对路径");
        for (String segment : relative.split("/",-1)) fileName(segment);
        Path target = base.resolve(relative).normalize();
        require(target.startsWith(base) && !target.equals(base), "路径越界: " + relative);
        for (Path cur = target; cur != null && cur.startsWith(base); cur = cur.getParent()) {
            if (Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cur) && !Files.readAttributes(cur,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther(), "不允许符号链接/重解析路径: " + cur);
            }
        }
        if (Files.exists(base)) {
            Path realBase = base.toRealPath();
            Path existing = target;
            while (!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            require(existing.toRealPath().startsWith(realBase), "实际路径越界");
        }
        return target;
    }
    public static void atomic(Path p, String text) throws IOException { atomic(p,text.getBytes(StandardCharsets.UTF_8)); }
    public static void atomic(Path p, byte[] bytes) throws IOException {
        Files.createDirectories(p.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(p.toAbsolutePath().getParent(),".cairn-",".tmp");
        try {
            Files.write(tmp,bytes);
            try (FileChannel c = FileChannel.open(tmp,StandardOpenOption.WRITE)) { c.force(true); }
            move(tmp,p,true);
        } finally { Files.deleteIfExists(tmp); }
    }
    public static void move(Path from, Path to, boolean replace) throws IOException {
        Files.createDirectories(to.toAbsolutePath().getParent());
        CopyOption[] atomic = replace ? new CopyOption[]{StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING} : new CopyOption[]{StandardCopyOption.ATOMIC_MOVE};
        for (int attempt=0;;attempt++) {
            try {
                try { Files.move(from,to,atomic); }
                catch (AtomicMoveNotSupportedException e) {
                    if (replace) Files.move(from,to,StandardCopyOption.REPLACE_EXISTING); else Files.move(from,to);
                }
                return;
            } catch (FileSystemException e) {
                // Antivirus/indexing can temporarily hold newly extracted files on Windows.
                if (!WINDOWS || attempt >= 6 || !Files.exists(from) || Files.exists(to) && !replace) throw e;
                try { Thread.sleep(100L*(attempt+1)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new InterruptedIOException("移动被中断，原文件保留: "+from); }
            }
        }
    }
    public static String hash(Path p, String algorithm) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest d = MessageDigest.getInstance(algorithm); byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) >= 0) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消"); if (n > 0) d.update(b,0,n); }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String sha1(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20"); }
    public static String urlBase(String s) { return s.replaceAll("/+$","")+"/"; }
    public static String fileName(String s) {
        require(s != null && !s.isBlank() && !s.contains("/") && !s.contains("\\") && !s.contains(":") && !s.equals(".") && !s.equals("..") && !s.endsWith(".") && !s.endsWith(" "), "不安全文件名: " + s);
        require(s.chars().noneMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0), "文件名包含非法字符");
        require(!s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"),"系统保留文件名");
        return s;
    }
    public static void copyTree(Path from, Path to) throws IOException {
        require(!Files.exists(to), "目标已存在: " + to);
        Files.walkFileTree(from,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a) throws IOException {
                require(!a.isSymbolicLink() && !a.isOther(),"目录含链接"); Files.createDirectories(to.resolve(from.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f,BasicFileAttributes a) throws IOException {
                require(a.isRegularFile(),"文件不是普通文件"); Files.copy(f,to.resolve(from.relativize(f))); return FileVisitResult.CONTINUE;
            }
        });
    }
    public static void unzip(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest); long total = 0; int count = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e; Set<Path> seen = new HashSet<>(); byte[] b = new byte[65536];
            while ((e = in.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
                require(++count <= 200000, "压缩包文件过多");
                String name = e.getName();
                if (name.endsWith("/")) name = name.substring(0,name.length()-1);
                if (name.isBlank()) continue;
                Path out = within(dest,name); require(seen.add(out),"压缩包重复路径: " + name);
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent()); long fileBytes = 0;
                try (OutputStream os = Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)) {
                    int n;
                    while ((n = in.read(b)) >= 0) {
                        total += n; fileBytes += n;
                        require(total <= 32L*1024*1024*1024 && fileBytes <= 8L*1024*1024*1024,"压缩包展开过大");
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消");
                        os.write(b,0,n);
                    }
                }
            }
        }
    }
    public static void zip(Path from, Path to, java.util.function.Predicate<Path> include) throws IOException {
        require(!to.toAbsolutePath().normalize().startsWith(from.toAbsolutePath().normalize()),"导出文件不能放在被打包的目录中");
        Files.createDirectories(to.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(to.toAbsolutePath().getParent(),".cairn-export-",".zip");
        try {
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp)); var walk = Files.walk(from)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    require(!Files.isSymbolicLink(p),"不能打包符号链接");
                    if (!include.test(p)) continue;
                    z.putNextEntry(new ZipEntry(from.relativize(p).toString().replace('\\','/')));
                    Files.copy(p,z); z.closeEntry();
                }
            }
            require(!Files.exists(to),"输出文件已存在"); move(tmp,to,false);
        } finally { Files.deleteIfExists(tmp); }
    }
    public static String redact(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+","$1<redacted>")
                .replaceAll("(?i)(--accessToken[=\\s]+)\\S+","$1<redacted>")
                .replaceAll("(?i)([\"']?(?:access_token|accessToken|refresh_token|clientToken|password|network-secret|x-api-key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,\"'}]+","$1<redacted>");
    }
    public static String timestamp() { return Instant.now().toString().replace(':','-'); }
    public static List<Path> children(Path p) throws IOException {
        if (!Files.isDirectory(p)) return List.of();
        try (var s = Files.list(p)) { return s.sorted().toList(); }
    }
    public static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024*1024) return String.format(Locale.ROOT,"%.1f KiB",n/1024d);
        if (n < 1024L*1024*1024) return String.format(Locale.ROOT,"%.1f MiB",n/1048576d);
        return String.format(Locale.ROOT,"%.2f GiB",n/1073741824d);
    }
    public static String tail(Path p, int lines) throws IOException {
        if (!Files.isRegularFile(p)) return "(暂无日志)";
        long size = Files.size(p), start = Math.max(0,size-2*1024*1024);
        try (var in = Files.newByteChannel(p)) {
            in.position(start); ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[65536];
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf); int n;
            while ((n = in.read(bb)) > 0) { b.write(buf,0,n); bb.clear(); }
            String[] all = b.toString(StandardCharsets.UTF_8).split("\\R");
            return String.join(System.lineSeparator(),Arrays.copyOfRange(all,Math.max(0,all.length-lines),all.length));
        }
    }
}
