package tester;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static tester.Tracer.Options.*;

/**
 * obtain traces with multiple configurations
 */
public class Tracer {

    enum API {
        GST,
        ASGCT,
        ASGST
    }

    /**
     * sampling mode
     */
    enum Mode {
        GST(API.GST, "gst", 0, true),
        ASGCT(API.ASGCT, "asgct", 0, true),
        ASGST(API.ASGST, "asgst_jni", ALLOW_TO_WALK_SAME_THREAD, false),
        ASGST_SIGNAL_HANDLER(API.ASGST, "asgst_signal", ALLOW_TO_WALK_SAME_THREAD, true),
        ASGST_SEPARATE_THREAD(API.ASGST, "asgst_separate", 0, true);

        public final String name;
        public final int requiredOptions;

        public final boolean supportSpecificThread;

        public final API api;

        Mode(API api, String name, int requiredOptions, boolean supportSpecificThread) {
            this.name = name;
            this.requiredOptions = requiredOptions;
            this.supportSpecificThread = supportSpecificThread;
            this.api = api;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * collection of different options for ASGST
     */
    public static final class Options {
        public static final int INCLUDE_C_FRAMES = 1;
        public static final int INCLUDE_NON_JAVA_THREADS = 2;
        public static final int INCLUDE_WALK_DURING_UNSAFE_STATES = 4;
        public static final int ALLOW_TO_WALK_SAME_THREAD = 8;

        public static final int ALL = -1;

        private Options() {
        }

        public static String toString(int options) {
            StringBuilder sb = new StringBuilder();
            if ((options & INCLUDE_C_FRAMES) != 0) {
                sb.append("c");
            }
            if ((options & INCLUDE_NON_JAVA_THREADS) != 0) {
                sb.append("n");
            }
            if ((options & INCLUDE_WALK_DURING_UNSAFE_STATES) != 0) {
                sb.append("u");
            }
            if ((options & ALLOW_TO_WALK_SAME_THREAD) != 0) {
                sb.append("s");
            }
            return sb.toString();
        }
    }


    /**
     * configuration of the sampling: mode + ASGST options and optional thread if it is not the current
     */
    record Configuration(Mode mode, int options, long threadId) {

        Configuration {
            assert mode != null;
            assert (options & mode.requiredOptions) == mode.requiredOptions;
            assert mode.supportSpecificThread || threadId == -1;
        }

        public Configuration(Mode mode, int options) {
            this(mode, options, -1);
        }

        Configuration(Mode mode) {
            this(mode, mode.requiredOptions);
        }

        boolean doesIncludeCFrames() {
            return (options & INCLUDE_C_FRAMES) != 0;
        }

        Configuration includeCFrames() {
            return new Configuration(mode, options | INCLUDE_C_FRAMES);
        }

        boolean doesIncludeNonJavaThreads() {
            return (options & INCLUDE_NON_JAVA_THREADS) != 0;
        }

        Configuration includeNonJavaThreads() {
            return new Configuration(mode, options | INCLUDE_NON_JAVA_THREADS);
        }

        boolean doesIncludeWalkDuringUnsafeStates() {
            return (options & INCLUDE_WALK_DURING_UNSAFE_STATES) != 0;
        }

        Configuration includeWalkDuringUnsafeStates() {
            return new Configuration(mode, options | INCLUDE_WALK_DURING_UNSAFE_STATES);
        }

        boolean doesAllowToWalkSameThread() {
            return (options & ALLOW_TO_WALK_SAME_THREAD) != 0;
        }

        Configuration allowToWalkSameThread() {
            return new Configuration(mode, options | ALLOW_TO_WALK_SAME_THREAD);
        }

        Configuration withThread(long threadId) {
            return new Configuration(mode, options, threadId);
        }

        static Configuration gst() {
            return new Configuration(Mode.GST);
        }

        static Configuration asgct() {
            return new Configuration(Mode.ASGST);
        }

        static Configuration asgst() {
            return new Configuration(Mode.ASGST);
        }

        static Configuration asgstSignalHandler() {
            return new Configuration(Mode.ASGST_SIGNAL_HANDLER);
        }

        static Configuration asgstSeparateThread() {
            return new Configuration(Mode.ASGST_SEPARATE_THREAD);
        }

        @Override
        public String toString() {
            return mode + "+" + Options.toString(options) + (threadId == -1 ? "" : "@" + threadId);
        }
    }

    public static final List<Configuration> nonJavaThreadEnableConfig = List.of(
            Configuration.asgst().includeCFrames().includeNonJavaThreads(),
            Configuration.asgstSignalHandler().includeCFrames().includeNonJavaThreads(),
            Configuration.asgstSeparateThread().includeCFrames().includeNonJavaThreads()
    );

    /**
     * extensive set of modes
     */
    public static final List<Configuration> extensiveConfigs = List.of(
            Configuration.asgct(),
            Configuration.asgst(),
            Configuration.asgct().includeNonJavaThreads().includeWalkDuringUnsafeStates(),
            Configuration.asgct().includeCFrames(),
            Configuration.asgstSignalHandler(),
            Configuration.asgstSignalHandler().includeNonJavaThreads().includeCFrames().includeWalkDuringUnsafeStates(),
            Configuration.asgstSeparateThread(),
            Configuration.asgstSeparateThread().includeNonJavaThreads().includeCFrames().includeWalkDuringUnsafeStates()
    );

    public static final List<Configuration> basicJavaConfigs = List.of(
            Configuration.gst(),
            Configuration.asgct(),
            Configuration.asgst(),
            Configuration.asgstSignalHandler(),
            Configuration.asgstSeparateThread()
    );

    public static final List<Configuration> nonASGSTConfigs = List.of(
            Configuration.gst(),
            Configuration.asgct()
    );

    public static final List<Configuration> basicAllFrameConfigs = List.of(
            Configuration.asgct(),
            Configuration.asgst().includeCFrames(),
            Configuration.asgstSignalHandler().includeCFrames(),
            Configuration.asgstSeparateThread().includeCFrames()
    );

    public final static List<Configuration> defaultConfigs = extensiveConfigs;

    private final List<Configuration> configurations;

    public Tracer() {
        this(defaultConfigs);
    }

    public Tracer(List<Configuration> configs) {
        this.configurations = configs;
    }

    public Tracer(Configuration... configs) {
        this(List.of(configs));
    }

    /**
     * default depth
     */
    private int depth = 1024;

    public Tracer setDepth(int depth) {
        this.depth = depth;
        return this;
    }

    public int getDepth() {
        return depth;
    }

    public Trace runGST() {
        return runGST(-1);
    }

    public Trace runGST(JavaThreadId threadId) {
        return runGST(getOSThreadId(threadId));
    }

    public Trace runGST(long threadId) {
        return runGST(threadId, depth);
    }

    /**
     * walk the current stack using GetStackTrace
     */
    public static native Trace runGST(long threadId, int depth);

    public Trace runASGCT() {
        return runASGCT(-1);
    }

    public Trace runASGCT(JavaThreadId threadId) {
        return runASGCT(getOSThreadId(threadId));
    }

    public Trace runASGCT(long threadId) {
        return runASGCT(threadId, depth);
    }

    /**
     * walk the current stack using ASGCT
     */
    public static native Trace runASGCT(long threadId, int depth);

    public Trace runASGST() {
        return runASGST(Mode.ASGST.requiredOptions);
    }

    public Trace runASGST(int options) {
        return runASGST(options, depth);
    }

    /**
     * walk the current stack using ASGST
     */
    public static native Trace runASGST(int options, int depth);

    public Trace runASGSTInSignalHandler() {
        return runASGSTInSignalHandler(Mode.ASGST_SIGNAL_HANDLER.requiredOptions);
    }

    public Trace runASGSTInSignalHandler(int options) {
        assert ((options & Mode.ASGST_SIGNAL_HANDLER.requiredOptions) == Mode.ASGST_SIGNAL_HANDLER.requiredOptions);
        return runASGSTInSignalHandler(options, null);
    }

    public Trace runASGSTInSignalHandler(int options, JavaThreadId threadId) {
        return runASGSTInSignalHandler(options, -1, depth);
    }

    public Trace runASGSTInSignalHandler(int options, long threadId) {
        return runASGSTInSignalHandler(options, threadId, depth);
    }

    /**
     * walk the current stack using ASGST in a signal handler
     *
     * @param threadId thread id of the thread to walk or -1 for the current thread
     */
    public static native Trace runASGSTInSignalHandler(int options, long threadId, int depth);

    public Trace runASGSTInSeparateThread() {
        return runASGSTInSeparateThread(Mode.ASGST_SEPARATE_THREAD.requiredOptions);
    }

    public Trace runASGSTInSeparateThread(int options) {
        return runASGSTInSeparateThread(options, -1);
    }

    public Trace runASGSTInSeparateThread(int options, JavaThreadId threadId) {
        return runASGSTInSeparateThread(options, -1);
    }

    public Trace runASGSTInSeparateThread(int options, long threadId) {
        return runASGSTInSeparateThread(options, threadId, depth);
    }

    /**
     * walk the current stack using ASGST in a separate thread
     */
    public static native Trace runASGSTInSeparateThread(int options, long threadId, int depth);

    /**
     * walk the current stack using the given config
     */
    public Trace run(Configuration config) {
        return run(config, depth);
    }

    /**
     * walk the current stack using the given config
     */
    public Trace run(Configuration config, int depth) {
        return switch (config.mode) {
            case GST -> runGST(config.threadId, depth);
            case ASGCT -> runASGCT(config.threadId, depth);
            case ASGST -> runASGST(config.options, depth);
            case ASGST_SIGNAL_HANDLER -> runASGSTInSignalHandler(config.options, config.threadId, depth);
            case ASGST_SEPARATE_THREAD -> runASGSTInSeparateThread(config.options, config.threadId, depth);
        };
    }

    /**
     * trace the current stack using the set configs
     */
    public Trace runAndCompare() {
        return runAndCompare(configurations, depth);
    }

    /**
     * trace with the configuration it has been obtained with
     */
    public record ConfiguredTrace(Configuration config, Trace trace) {
        @Override
        public String toString() {
            return config + ":\n" + trace;
        }

        public void checkEquality(ConfiguredTrace other) {
            if (!trace.equals(other.trace, !config.doesIncludeCFrames() || !other.config.doesIncludeCFrames())) {
                throw new TracesUnequalError(this, other);
            }
        }
    }

    /**
     * two traces don't match
     */
    public static class TracesUnequalError extends AssertionError {

        private final ConfiguredTrace a;
        private final ConfiguredTrace b;

        public TracesUnequalError(ConfiguredTrace a, ConfiguredTrace b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public String toString() {
            boolean ignoreNonJavaFrames = !a.trace.hasNonJavaFrames() || !b.trace.hasNonJavaFrames();
            Trace af = ignoreNonJavaFrames ? a.trace.withoutNonJavaFrames() : a.trace;
            Trace bf = ignoreNonJavaFrames ? b.trace.withoutNonJavaFrames() : b.trace;
            List<Integer> unequalIndexes = IntStream.range(0, Math.max(af.size(), bf.size()))
                    .filter(i -> i >= af.size() || i >= bf.size() || !af.get(i).equals(bf.get(i)))
                    .boxed()
                    .toList();
            List<String> lines = new ArrayList<>();
            unequalIndexes.forEach(i -> {
                lines.add("at %d: %s != %s\n".formatted(i, i >= af.size() ? "null" : af.get(i), i >= bf.size() ?
                        "null" : bf.get(i)));
            });
            return "Traces unequal (%s vs %s):\n".formatted(a.config, b.config) + String.join("", lines) + a + "\n" + b;
        }
    }

    /**
     * walk the current stack using the given configs, throws an error
     *
     * @return the longest trace
     * @throws TracesUnequalError if two of the traces are unequal
     */
    private Trace runAndCompare(List<Configuration> configs, int depth) {
        assert configs != null && configs.size() > 0;
        var confTraces = configs.stream().map(config -> new ConfiguredTrace(config, run(config, depth))).toList();
        var first = confTraces.stream().max(Comparator.comparingInt(a -> a.trace.size())).orElseThrow();
        for (var other : confTraces) {
            if (other != first) {
                first.checkEquality(other);
            }
        }
        return first.trace;
    }

    public record JavaThreadId(long id) {
    }

    public static native List<JavaThreadId> getJavaThreadIds();

    public static native List<Long> getOSThreadIds();

    public static long getOSThreadId(JavaThreadId id) {
        return getOSThreadId(id.id);
    }

    private static native long getOSThreadId(long javaThreadId);

    public static native long getCurrentOSThreadId();

}
