import com.krish.niftydirection.data.*;
import com.krish.niftydirection.forecast.*;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
/** End-to-end against the mock Kite server: download history, train all horizons, make a live forecast. */
public class ForecastRunnerTest {
    public static void main(String[] a) throws Exception {
        Kite.ROOT = "http://127.0.0.1:" + a[0];
        File dir = new File(a[2]); dir.mkdirs();
        ForecastRunner.TrainOutput t = ForecastRunner.train(new Kite("KEY", "TOKEN"), dir, a[1], new AtomicBoolean(), (w, d, n) -> {});
        System.out.println(t.text);
        ForecastRunner.Live l = ForecastRunner.live(new Kite("KEY", "TOKEN"), dir, a[1], 11 * 60 + 2, new AtomicBoolean(), (w, d, n) -> {});
        for (Forecaster.Prediction p : l.predictions) System.out.println(p.id + " " + p.when + " pUp=" + p.pUp + " " + p.reasons);
        System.out.println("as of " + l.asOf + " price " + l.price + " note " + l.note);
        boolean ok = t.models.size() == 4 && ForecastRunner.models(dir).size() == 4 && l.predictions.size() == 4
                && !Double.isNaN(l.predictions.get(0).pUp) && t.models.get(0).info.days > 500 && !ForecastRunner.needsTraining(dir);
        ForecastRunner.Live po = ForecastRunner.live(new Kite("KEY", "TOKEN"), dir, a[1], 8 * 60, 25000, new AtomicBoolean(), (w, d, n) -> {});
        System.out.println("pre-open: " + po.asOf + " · " + po.predictions.get(3).label + " " + po.predictions.get(3).pUp);
        ok &= po.preOpen && po.predictions.size() == 4 && "Today's close".equals(po.predictions.get(3).label) && !Double.isNaN(po.predictions.get(0).pUp);
        // second call must come from the day cache (fast)
        long t0 = System.currentTimeMillis();
        ForecastRunner.history(new Kite("KEY", "TOKEN"), dir, a[1], null, (w, d, n) -> {});
        ok &= System.currentTimeMillis() - t0 < 3000;
        System.out.println(ok ? "1 passed, 0 failed" : "0 passed, 1 failed");
        if (!ok) System.exit(1);
    }
}
