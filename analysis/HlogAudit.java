import java.io.File;
import java.util.Locale;
import org.HdrHistogram.EncodableHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;

/** Read-only decoder used by audit_ccpe_54.py. */
public final class HlogAudit {
    private static final double NPM = 1_000_000.0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        for (String path : args) {
            Histogram aggregate = null;
            Histogram[] phases = new Histogram[5];
            int records = 0;
            int lastWindowIndex = 0;
            boolean orderedTags = true;
            long previousEnd = Long.MIN_VALUE;
            boolean nondecreasingTime = true;
            try (HistogramLogReader reader = new HistogramLogReader(new File(path))) {
                EncodableHistogram encoded;
                while ((encoded = reader.nextIntervalHistogram()) != null) {
                    if (!(encoded instanceof Histogram histogram)) {
                        throw new IllegalStateException("Unexpected histogram type: " + encoded.getClass());
                    }
                    records++;
                    int windowIndex = Integer.parseInt(histogram.getTag().substring("window-".length()));
                    orderedTags &= windowIndex > lastWindowIndex;
                    lastWindowIndex = windowIndex;
                    nondecreasingTime &= histogram.getStartTimeStamp() >= previousEnd;
                    previousEnd = histogram.getEndTimeStamp();
                    if (aggregate == null) {
                        aggregate = histogram.copy();
                    } else {
                        aggregate.add(histogram);
                    }
                    int phase = Math.min(4, (windowIndex - 1) / 30);
                    if (phases[phase] == null) {
                        phases[phase] = histogram.copy();
                    } else {
                        phases[phase].add(histogram);
                    }
                    emit("WINDOW", path, windowIndex, histogram);
                }
            }
            if (aggregate == null) {
                aggregate = new Histogram(1L, 300_000_000_000L, 3);
            }
            System.out.printf(Locale.ROOT,
                    "TOTAL\t%s\t%d\t%b\t%b\t%d\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f%n",
                    path, records, orderedTags, nondecreasingTime,
                    aggregate.getTotalCount(), aggregate.getMean() / NPM,
                    aggregate.getValueAtPercentile(50.0) / NPM,
                    aggregate.getValueAtPercentile(95.0) / NPM,
                    aggregate.getValueAtPercentile(99.0) / NPM,
                    aggregate.getValueAtPercentile(99.9) / NPM,
                    aggregate.getMinValue() / NPM,
                    aggregate.getMaxValue() / NPM);
            for (int phase = 0; phase < phases.length; phase++) {
                if (phases[phase] != null) {
                    emit("PHASE", path, phase + 1, phases[phase]);
                }
            }
        }
    }

    private static void emit(String kind, String path, int index, Histogram h) {
        System.out.printf(Locale.ROOT,
                "%s\t%s\t%d\t%d\t%d\t%d\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f\t%.9f%n",
                kind, path, index, h.getStartTimeStamp(), h.getEndTimeStamp(),
                h.getTotalCount(), h.getMean() / NPM,
                h.getValueAtPercentile(50.0) / NPM,
                h.getValueAtPercentile(95.0) / NPM,
                h.getValueAtPercentile(99.0) / NPM,
                h.getValueAtPercentile(99.9) / NPM,
                h.getMinValue() / NPM, h.getMaxValue() / NPM);
    }
}
