package tester;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tester.Frame.JavaFrame;
import tester.Frame.MethodId;
import tester.Tracer.Configuration;
import tester.util.WhiteBoxUtil;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Executable;
import java.util.*;

/**
 * An agent that walks threads, comparing the output of the different APIs.
 */
@Command(name = "Tester Agent", mixinStandardHelpOptions = true,
        description = "An agent that walks threads, comparing the output of the different APIs.")
public class Agent implements Runnable {

    {
        JNIHelper.loadAndAttachIfNeeded();
    }

    @Option(names = "--gst", description = "compare with GetStackTrace, triggers safe-points")
    private final boolean compareWithGST = false;

    /**
     * only include the in signal handler versions with no C frames
     */
    @Option(names = "--basic", description = "only include the in signal handler versions with no C frames")
    private final boolean basic = false;

    @Option(names = "--max-threads", description = "maximum number of threads to walk per iteration")
    private final int maxThreadsPerIteration = 10;

    @Option(names = {"--sample-interval", "-i"}, description = "sample interval in seconds")
    private float sampleInterval = 0.001f;

    private long success = 0;
    private long fail = 0;

    private final Random random = new Random();
    private boolean shouldCollectMethodNames = false;


    /**
     * methods that have been executed and found during the sampling, used for compilation level randomization
     */
    private final Map<MethodId, Executable> executedMethods = new HashMap<>();

    public Agent(float sampleInterval, boolean shouldCollectMethods) {
        this.sampleInterval = sampleInterval;
        this.shouldCollectMethodNames = shouldCollectMethods;
    }

    public Agent(float sampleInterval) {
        this.sampleInterval = sampleInterval;
    }

    public Agent() {
    }

    private void collectMethodNames(Trace trace) {
        for (Frame frame : trace) {
            if (frame instanceof JavaFrame f && !f.isNative()) {
                if (executedMethods.containsKey(f.methodId)) {
                    continue;
                }
                Class<?> clazz = f.getDeclaringClass();
                if (clazz == null) {
                    continue;
                }
                Executable[] possibleMethods =
                        (f.methodId.methodName.equals("<init>") ? Arrays.stream(clazz.getDeclaredConstructors()) :
                                Arrays.stream(clazz.getDeclaredMethods()).filter(m -> m.getName().equals(f.methodId.methodName)))
                                .toArray(Executable[]::new);
                if (possibleMethods.length == 1) {
                    executedMethods.put(f.methodId, possibleMethods[0]);
                } else {
                    if (possibleMethods.length > 1) {
                        System.err.println("[Agent] Skipping method " + f.methodId + " because it is not unique in " +
                                "its class");
                    } else {
                        System.err.println("[Agent] Skipping method " + f.methodId + " because it was not found in " +
                                "its class");
                    }
                }
            }
        }
    }

    public void randomizeCompilationLevels() {
        if (executedMethods.isEmpty()) {
            return;
        }
        System.out.printf("[Agent] Randomizing compilation levels for %d methods%n", executedMethods.size());
        var levels = WhiteBoxUtil.createRandomCompilationLevels(random, executedMethods.values().stream().toList());
        WhiteBoxUtil.forceCompilationLevels(levels);
        System.out.println("[Agent] Done randomizing compilation levels");
    }

    private List<Thread> selectThreads() {
        Thread[] threads = Tracer.getThreads();
        List<Thread> threadList = new ArrayList<>(List.of(threads));
        threadList.remove(Thread.currentThread());
        Collections.shuffle(threadList);
        return threadList.subList(0, Math.min(maxThreadsPerIteration, threadList.size()));
    }

    protected Tracer createTracer() {
        // separate thread is not supported for basic
        List<Configuration> configurations = new ArrayList<>(basic ? Tracer.basicSeparateThreadConfigs :
                Tracer.extensiveSpecificThreadConfigs);
        if (!compareWithGST) {
            configurations.removeIf(c -> c.mode() == Tracer.Mode.GST);
        }
        return new Tracer(configurations);
    }

    private void iteration(Tracer tracer) {
        List<Thread> threads = selectThreads();
        if (selectThreads().isEmpty()) {
            System.err.println("[Agent] No threads to walk");
            return;
        }
        for (Thread t : threads) {
            try {
                if (!t.isAlive() || t.isDaemon()) {
                    continue;
                }
                Trace trace = tracer.runMultipleAndCompare(t);
                if (shouldCollectMethodNames) {
                    collectMethodNames(trace);
                }
                success++;

            } catch (AssertionError e) {
                e.printStackTrace();
                fail++;
            }
        }
        System.err.println(" success: " + success + " fail: " + fail);
    }

    private volatile boolean stop = false;

    private static void sleep(float seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static float time() {
        return System.nanoTime() / 1_000_000_000f;
    }

    private void loop() {
        Tracer tracer = createTracer();
        while (!stop) {
            var start = time();
            iteration(tracer);
            var elapsed = time() - start;
            var sleep = Math.max(0, sampleInterval - elapsed);
            if (sleep > 0) {
                sleep(sleep);
            }
        }
    }

    @Override
    public void run() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.printf("[Agent] Success: %d, Fail: %d%n", success, fail);
            stop = true;
        }));
        Thread t = new Thread(() -> {
            try {
                loop();
            } catch (Throwable t1) {
                t1.printStackTrace();
            }
        });

        t.setName("Tester Agent");
        t.setDaemon(true);
        t.start();

    }

    public static void premain(String agentArgs, Instrumentation inst) {
        int ret = new CommandLine(new Agent()).execute(agentArgs == null ? new String[0] : agentArgs.split(","));
        if (ret != 0) {
            System.exit(ret);
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        throw new UnsupportedOperationException("Attaching the agent at runtime is not supported");
    }
}
