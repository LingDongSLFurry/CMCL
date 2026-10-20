package dev.cairn;

import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class Jobs implements AutoCloseable {
    @FunctionalInterface public interface Work { void run(Job job) throws Exception; }
    public static final class Job {
        public final int id;
        public final String name;
        public final long started = System.currentTimeMillis();
        public final AtomicLong bytes = new AtomicLong();
        public final AtomicInteger done = new AtomicInteger(), total = new AtomicInteger();
        public volatile String stage = "等待", state = "QUEUED", error = "";
        public volatile Future<?> future;
        public volatile boolean cancelled;
        private volatile long lastPrint;
        private final Ui ui;
        Job(int id,String name,Ui ui) { this.id = id; this.name = name; this.ui = ui; }
        public void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("任务已取消");
        }
        public void stage(String s) { stage = s; progress(true); }
        public synchronized void progress(boolean force) {
            long now = System.currentTimeMillis();
            if (force || now-lastPrint >= 5000) {
                lastPrint = now;
                ui.note("任务 #" + id + " " + stage + "  [" + done + "/" + total + "]  " + Util.bytes(bytes.get()));
            }
        }
        public Map<String,Object> json() {
            return Json.map("id",id,"name",name,"state",state,"stage",stage,"done",done.get(),"total",total.get(),"bytes",bytes.get(),"error",error);
        }
    }
    private final ExecutorService executor = Executors.newFixedThreadPool(2,r -> { Thread t = new Thread(r,"cairn-job"); t.setDaemon(true); return t; });
    private final Map<Integer,Job> jobs = new ConcurrentSkipListMap<>();
    private final AtomicInteger next = new AtomicInteger();
    private final Ui ui;
    public Jobs(Ui ui) { this.ui = ui; }
    public Job submit(String name,Work work) {
        Job j = new Job(next.incrementAndGet(),name,ui); jobs.put(j.id,j);
        j.future = executor.submit(() -> {
            try {
                j.check(); j.state = "RUNNING"; work.run(j); j.check(); j.state = "DONE"; j.stage = "完成";
            } catch (Exception e) {
                if (j.cancelled || Thread.currentThread().isInterrupted() || e instanceof InterruptedIOException || e instanceof InterruptedException) {
                    j.state = "CANCELLED"; j.error = "已取消"; Thread.interrupted();
                } else { j.state = "FAILED"; j.error = Util.redact(e.getMessage() == null ? e.toString() : e.getMessage()); }
            } finally { ui.note("任务 #" + j.id + " " + j.name + " → " + j.state + (j.error.isEmpty() ? "" : " · " + j.error)); }
        });
        return j;
    }
    public Job get(int id) { Job j = jobs.get(id); Util.require(j != null,"任务不存在"); return j; }
    public void cancel(int id) {
        Job j = get(id);
        if (Set.of("DONE","FAILED","CANCELLED").contains(j.state)) return;
        j.cancelled = true; j.state = "CANCELLED"; j.future.cancel(true);
    }
    public void waitFor(Job j) throws Exception {
        try { j.future.get(); } catch (CancellationException e) { throw new IllegalStateException("任务已取消"); }
        Util.require(j.state.equals("DONE"),"任务 #" + j.id + " " + j.state + ": " + j.error);
    }
    public void waitAll() throws Exception {
        Exception first = null;
        for (Job j : jobs.values()) try { waitFor(j); } catch (Exception e) { if (first == null) first = e; }
        if (first != null) throw first;
    }
    public List<Job> list() { return new ArrayList<>(jobs.values()); }
    public boolean active() { return jobs.values().stream().anyMatch(j -> Set.of("QUEUED","RUNNING").contains(j.state)); }
    @Override public void close() { for (Job j : jobs.values()) if (Set.of("QUEUED","RUNNING").contains(j.state)) cancel(j.id); executor.shutdownNow(); }
}
