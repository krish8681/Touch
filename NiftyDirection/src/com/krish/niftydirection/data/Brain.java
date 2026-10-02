package com.krish.niftydirection.data;

import android.content.Context;

import com.krish.niftydirection.engine.Engine;
import com.krish.niftydirection.engine.Result;
import com.krish.niftydirection.model.Snapshot;

import java.util.concurrent.CopyOnWriteArrayList;

/** One refresh = collect → engine → save. Shared by the screen and the background watch. */
public final class Brain {
    private Brain() {}

    public static class Output { public Snapshot snap; public Result result; }
    public interface Listener { void onOutput(Output o); }

    public static volatile Output last;
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Object LOCK = new Object();

    public static void listen(Listener l) { listeners.addIfAbsent(l); }
    public static void unlisten(Listener l) { listeners.remove(l); }

    /** Today's earlier scores (for hysteresis + transitions) and the learnt weights (walk-forward). */
    public static void prepare(Snapshot s, Store st, boolean autotune) {
        if (s.live || s.minute >= 9 * 60) {
            s.history = st.timeline(s.today);
            if (!s.history.isEmpty()) s.prevRegime = Result.fromNum(s.history.get(s.history.size() - 1)[3]);
        }
        if (autotune) {
            java.util.Map<String, double[]> cal = st.calibration(s.today);
            for (java.util.Map.Entry<String, double[]> e : cal.entrySet()) if (!e.getKey().startsWith("_")) s.calibration.put(e.getKey(), e.getValue()[2]);
        }
        s.flowScale = st.flowScale(s.today);
    }

    /** Blocking. Call from a background thread. */
    public static Output refresh(Context ctx, Collector.Progress pr) {
        synchronized (LOCK) {
            Prefs prefs = new Prefs(ctx);
            Snapshot s = new Collector(ctx.getFilesDir()).gather(prefs.config(), pr);
            for (String n : s.notes) if (n.startsWith("KITE_LOGIN")) prefs.clearSession();
            Store st = new Store(ctx.getFilesDir());
            prepare(s, st, prefs.bool("autotune", true));
            Result r = Engine.run(s);
            if (r.optFlow != null && s.live) st.addFlowStat(s.today, s.minute, r.optFlow.intensity);
            if (s.nifty != null && s.nifty.ok()) { st.addPoint(s, r); st.record(s, r); }
            Recorder.record(ctx.getFilesDir(), s, r);
            Output o = new Output();
            o.snap = s; o.result = r;
            last = o;
            for (Listener l : listeners) l.onOutput(o);
            return o;
        }
    }
}
