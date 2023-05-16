package tester;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tester.JNIHelper;
import tester.Tracer;
import tester.Tracer.Configuration;

import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private boolean compareWithGST = false;

    /**
     * only include the in signal handler versions with no C frames
     */
    @Option(names = "--basic", description = "only include the in signal handler versions with no C frames")
    private boolean basic = false;

    @Option(names = "--max-threads", description = "maximum number of threads to walk per iteration")
    private int maxThreadsPerIteration = 10;

    @Option(names = {"--sample-interval", "-i"}, description = "sample interval in seconds")
    private float sampleInterval = 0.001f;

    @Option(names = {"--randomize", "-r"}, description = "randomize compilation levels and inlining every N seconds " +
            "(0 to disable), checks that ASGST returns the correct information per method")
    private float randomizeCompLevelsInterval = 0;

    private long success = 0;
    private long fail = 0;

    private List<Thread> selectThreads() {
        Thread[] threads = Tracer.getThreads();
        List<Thread> threadList = new ArrayList<>(List.of(threads));
        threadList.remove(Thread.currentThread());
        Collections.shuffle(threadList);
        return threadList.subList(0, Math.min(maxThreadsPerIteration, threadList.size()));
    }

    private Tracer createTracer() {
        List<Configuration> configurations = new ArrayList<>(basic ? Tracer.basicSeperateThreadConfigs :
                Tracer.extensiveConfigs);
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
                //System.err.println("[Agent] Walking " + t.getName());
                System.out.println(new Tracer(Tracer.Configuration.asgctSignalHandler()).runAndCompare(t).size());
               // System.err.println("[Agent] [" + t.getName() + "] success: " + success + " fail: " + fail);
                success++;
            } catch (AssertionError e) {
                e.printStackTrace();
                fail++;
            }
        }
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
            System.out.print("+");
            var start = time();
            iteration(tracer);
            var elapsed = time() - start;
            var sleep = Math.max(0, sampleInterval - elapsed);
            System.out.println("[Agent] " + sleep + " took " + elapsed);
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
