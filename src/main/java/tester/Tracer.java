package tester;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static tester.Tracer.Options.*;
import static tester.util.ListUtils.combine;

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
    public enum Mode {
        GST(API.GST, "gst", 0, true),
        ASGCT(API.ASGCT, "asgct", 0, false),
        ASGCT_SIGNAL_HANDLER(API.ASGCT, "asgct_signal", 0, true),
        ASGST(API.ASGST, "asgst_jni", ASGST_WALK_SAME_THREAD, false),
        ASGST_SIGNAL_HANDLER(API.ASGST, "asgst_signal", ASGST_WALK_SAME_THREAD, true),
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
        public static final int ASGST_WALK_SAME_THREAD = 8;

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
            if ((options & ASGST_WALK_SAME_THREAD) != 0) {
                sb.append("s");
            }
            return sb.toString();
        }

        public static String toLongString(int options) {
            List<String> parts = new ArrayList<>();
            if ((options & INCLUDE_C_FRAMES) != 0) {
                parts.add("C frames");
            }
            if ((options & INCLUDE_NON_JAVA_THREADS) != 0) {
                parts.add("include non-Java threads");
            }
            if ((options & INCLUDE_WALK_DURING_UNSAFE_STATES) != 0) {
                parts.add("walk during unsafe states");
            }
            if ((options & ASGST_WALK_SAME_THREAD) != 0) {
                parts.add("walk same thread");
            }
            return String.join(", ", parts);
        }
    }


    /**
     * configuration of the sampling: mode + ASGST options and optional thread if it is not the current
     */
    public record Configuration(Mode mode, int options, Thread thread) {

        public Configuration {
            assert mode != null;
            assert (options & mode.requiredOptions) == mode.requiredOptions;
            assert mode.supportSpecificThread || thread == null;
        }

        public Configuration(Mode mode, int options) {
            this(mode, options, null);
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
            return (options & ASGST_WALK_SAME_THREAD) != 0;
        }

        Configuration allowToWalkSameThread() {
            return new Configuration(mode, options | ASGST_WALK_SAME_THREAD);
        }

        Configuration withThread(Thread thread) {
            return new Configuration(mode, options, thread);
        }

        static Configuration gst() {
            return new Configuration(Mode.GST);
        }

        static Configuration asgct() {
            return new Configuration(Mode.ASGCT);
        }

        static Configuration asgctSignalHandler() {
            return new Configuration(Mode.ASGCT_SIGNAL_HANDLER);
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
            return mode + (options != 0 ? "+" + Options.toString(options) : "") + (thread == null ? "" : "@" + thread);
        }

        public String toLongString() {
            return mode + (options != 0 ? ": " + Options.toLongString(options) : "") + (thread == null ? "" :
                    " @" + thread);
        }
    }

    public static final List<Configuration> nonJavaThreadEnableConfig = List.of(
            Configuration.asgst().includeCFrames().includeNonJavaThreads(),
            Configuration.asgstSignalHandler().includeCFrames().includeNonJavaThreads(),
            Configuration.asgstSeparateThread().includeCFrames().includeNonJavaThreads()
    );

    /**
     * Full list of ASGST related configurations
     */
    public static final List<Configuration> extensiveASGSTConfigs = List.of(
            Configuration.asgst(),
            Configuration.asgst().includeNonJavaThreads().includeWalkDuringUnsafeStates(),
            Configuration.asgst().includeCFrames(),
            Configuration.asgstSignalHandler(),
            Configuration.asgstSignalHandler().includeNonJavaThreads().includeWalkDuringUnsafeStates(),
            Configuration.asgstSignalHandler().includeCFrames(),
            Configuration.asgstSignalHandler().includeNonJavaThreads().includeCFrames().includeWalkDuringUnsafeStates(),
            Configuration.asgstSeparateThread().includeNonJavaThreads().includeWalkDuringUnsafeStates(),
            Configuration.asgstSeparateThread().includeCFrames(),
            Configuration.asgstSeparateThread().includeNonJavaThreads().includeCFrames().includeWalkDuringUnsafeStates()
    );

    public static final List<Configuration> extensiveNonCASGSTConfigs = extensiveASGSTConfigs.stream()
            .filter(c -> !c.doesIncludeCFrames())
            .collect(Collectors.toList());

    /**
     * Basic list of ASGST related configurations which include C frames
     */
    public static final List<Configuration> asgstCFrameConfigs = List.of(
            Configuration.asgst().includeCFrames(),
            Configuration.asgstSignalHandler().includeCFrames(),
            Configuration.asgstSeparateThread().includeCFrames()
    );


    /**
     * All modes available
     */
    public static final List<Configuration> extensiveConfigs = combine(List.of(
            Configuration.gst(),
            Configuration.asgct(),
            Configuration.asgctSignalHandler()
    ), extensiveASGSTConfigs);

    public static final List<Configuration> extensiveNonCConfigs = extensiveConfigs.stream()
            .filter(c -> !c.doesIncludeCFrames())
            .collect(Collectors.toList());

    public static final List<Configuration> extensiveCConfigs = extensiveConfigs.stream()
            .filter(Configuration::doesIncludeCFrames)
            .collect(Collectors.toList());

    public static final List<Configuration> basicJavaConfigs = List.of(
            Configuration.gst(),
            Configuration.asgct(),
            Configuration.asgctSignalHandler(),
            Configuration.asgst(),
            Configuration.asgstSignalHandler(),
            Configuration.asgstSeparateThread()
    );

    public static final List<Configuration> nonASGSTConfigs = List.of(
            Configuration.gst(),
            Configuration.asgct()
    );

    public static final List<Configuration> extensiveSpecificThreadConfigs = extensiveConfigs.stream()
            .filter(c -> c.mode.supportSpecificThread)
            .collect(Collectors.toList());

    public static final List<Configuration> basicSeperateThreadConfigs = List.of(
            Configuration.gst(),
            Configuration.asgctSignalHandler(),
            Configuration.asgstSignalHandler()
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
        return runGST(null);
    }

    public Trace runGST(Thread thread) {
        return runGST(thread, depth);
    }

    /**
     * walk the current stack using GetStackTrace
     *
     * @param thread the thread to walk, or null for the current thread
     */
    public static native Trace runGST(Thread thread, int depth);

    public Trace runASGCT() {
        return runASGCT(depth);
    }

    /**
     * walk the current stack using ASGCT
     */
    public static native Trace runASGCT(int depth);

    public Trace runASGCTInSignalHandler() {
        return runASGCTInSignalHandler(null);
    }

    public Trace runASGCTInSignalHandler(Thread thread) {
        return runASGCTInSignalHandler(thread, depth);
    }


    /**
     * walk the current stack using ASGCT
     *
     * @param thread the thread to walk, or null for the current thread
     */
    public static native Trace runASGCTInSignalHandler(Thread thread, int depth);

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
        return runASGSTInSignalHandler(options, null, depth);
    }

    public Trace runASGSTInSignalHandler(Thread thread) {
        return runASGSTInSignalHandler(Mode.ASGST_SIGNAL_HANDLER.requiredOptions, thread, depth);
    }

    public Trace runASGSTInSignalHandler(int options, Thread thread) {
        assert ((options & Mode.ASGST_SIGNAL_HANDLER.requiredOptions) == Mode.ASGST_SIGNAL_HANDLER.requiredOptions);
        return runASGSTInSignalHandler(options, thread, depth);
    }

    /**
     * walk the current stack using ASGST in a signal handler
     *
     * @param thread thread to walk, null for current thread
     */
    public static native Trace runASGSTInSignalHandler(int options, Thread thread, int depth);

    public Trace runASGSTInSeparateThread() {
        return runASGSTInSeparateThread(Mode.ASGST_SEPARATE_THREAD.requiredOptions);
    }

    public Trace runASGSTInSeparateThread(int options) {
        return runASGSTInSeparateThread(options, null);
    }

    public Trace runASGSTInSeparateThread(Thread thread) {
        return runASGSTInSeparateThread(0, thread, depth);
    }

    public Trace runASGSTInSeparateThread(int options, Thread thread) {
        return runASGSTInSeparateThread(options, thread, depth);
    }

    /**
     * walk the current stack using ASGST in a separate thread
     *
     * @param thread thread to walk, null for current thread
     */
    public static native Trace runASGSTInSeparateThread(int options, Thread thread, int depth);

    /**
     * walk the current stack using the given config
     */
    public Trace run(Configuration config) {
        return run(config, null);
    }

    public Trace run(Configuration config, Thread thread) {
        return run(config, depth, thread);
    }

    /**
     * walk the current stack using the given config
     *
     * @param thread override the thread to walk, null for the thread from the config
     */
    public Trace run(Configuration config, int depth, Thread thread) {
        Thread t = thread == null ? config.thread : thread;
        return switch (config.mode) {
            case GST -> runGST(t, depth);
            case ASGCT -> runASGCT(depth);
            case ASGCT_SIGNAL_HANDLER -> runASGCTInSignalHandler(t, depth);
            case ASGST -> runASGST(config.options, depth);
            case ASGST_SIGNAL_HANDLER -> runASGSTInSignalHandler(config.options, t, depth);
            case ASGST_SEPARATE_THREAD -> runASGSTInSeparateThread(config.options, t, depth);
        };
    }

    /**
     * trace the current stack using the set configs
     */
    public Trace runAndCompare() {
        return runAndCompare(null);
    }

    /**
     * trace the current stack using the set configs
     *
     * @param thread override the thread to walk, null for the thread from the configs
     */
    public Trace runAndCompare(Thread thread) {
        return runAndCompare(configurations, depth, thread);
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
     * @param thread override the thread to walk, null for the thread from the configs
     * @return the longest trace
     * @throws TracesUnequalError if two of the traces are unequal
     */
    private Trace runAndCompare(List<Configuration> configs, int depth, Thread thread) {
        assert configs != null && configs.size() > 0;
        var confTraces =
                configs.stream().map(config -> new ConfiguredTrace(config, run(config, depth, thread))).toList();
        var first = confTraces.stream().max(Comparator.comparingInt(a -> a.trace.size())).orElseThrow();
        for (var other : confTraces) {
            if (other != first) {
                first.checkEquality(other);
            }
        }
        return first.trace;
    }

    /**
     * return all Java threads
     */
    public static native Thread[] getThreads();
}
