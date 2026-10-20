package dev.cairn;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Separate headless worker. Receives credentials through a pipe, never command-line or disk tickets. */
public final class GameWorker {
    private GameWorker() {}
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        Util.require(args.length == 1,"worker session required");
        Path sessionFile=Path.of(args[0]); byte[] payload=System.in.readNBytes(8*1024*1024+1);
        Util.require(payload.length <= 8*1024*1024,"worker payload too large");
        Map<String,Object> input=Json.obj(Json.parse(new String(payload,StandardCharsets.UTF_8)));
        Arrays.fill(payload,(byte)0);
        Map<String,Object> session=Json.child(input,"session");
        Path dir=Path.of(Json.str(input,"directory","")), log=Path.of(Json.str(session,"log",""));
        List<String> secrets=Json.list(input,"secrets").stream().map(Object::toString).filter(s -> s.length() > 5).toList();
        try (BufferedWriter writer=Files.newBufferedWriter(log,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND)) {
            try {
                hook(Json.list(input,"before"),dir,writer,secrets);
                List<String> command=Json.list(input,"command").stream().map(Object::toString).toList();
                Process game=new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
                session.put("pid",game.pid()); session.put("workerPid",ProcessHandle.current().pid());
                session.put("processStart",game.toHandle().info().startInstant().map(Instant::toString).orElse(""));
                session.put("state","RUNNING"); Util.atomic(sessionFile,Json.pretty(session));
                try (BufferedReader reader=new BufferedReader(new InputStreamReader(game.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;
                    while ((line=reader.readLine()) != null) { writer.write(redact(line,secrets)); writer.newLine(); writer.flush(); }
                }
                int exit=game.waitFor(); session.put("exitCode",exit); session.put("state","EXITED"); session.put("ended",Instant.now().toString());
                Util.atomic(sessionFile,Json.pretty(session));
                hook(Json.list(input,"after"),dir,writer,secrets);
            } catch (Exception e) {
                session.put("state","FAILED"); session.put("error",redact(e.getMessage() == null ? e.toString() : e.getMessage(),secrets));
                session.put("ended",Instant.now().toString()); Util.atomic(sessionFile,Json.pretty(session));
                writer.write(Json.str(session,"error","")); writer.newLine(); writer.flush();
                System.exit(1);
            }
        }
    }
    private static void hook(List<Object> args,Path dir,BufferedWriter writer,List<String> secrets) throws Exception {
        if (args.isEmpty()) return;
        Process p=new ProcessBuilder(args.stream().map(Object::toString).toList()).directory(dir.toFile()).redirectErrorStream(true).start();
        // Drain concurrently, with a finite timeout, to avoid both pipe deadlocks and stranded hooks.
        Thread drain=new Thread(() -> {
            try (BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))) {
                String line; while ((line=r.readLine()) != null) synchronized (writer) { writer.write(redact(line,secrets)); writer.newLine(); writer.flush(); }
            } catch (IOException ignored) {}
        }); drain.setDaemon(true); drain.start();
        if (!p.waitFor(5,TimeUnit.MINUTES)) { p.destroyForcibly(); throw new IOException("前/后置命令超时"); }
        drain.join(3000); Util.require(p.exitValue() == 0,"前/后置命令退出代码 "+p.exitValue());
    }
    private static String redact(String line,List<String> secrets) {
        for (String secret : secrets) line=line.replace(secret,"<redacted>");
        return Ui.clean(Util.redact(line));
    }
}
