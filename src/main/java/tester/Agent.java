package tester;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tester.Tracer.Configuration;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An agent that walks threads, comparing the output of the different APIs.
 */
@Command(name = "Tester Agent", mixinStandardHelpOptions = true,
        description = "An agent that walks threads, comparing the output of the different APIs.")
public class Agent implements Runnable {

    static {
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

    @Option(names = {"--depth", "-d"}, description = "maximum depth of the stack trace")
    private int depth = 1024;


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
        return new Tracer(configurations).setDepth(depth);
    }

    @Override
    public void run() {
        AgentBase agentBase = new AgentBase(createTracer(), sampleInterval, false, t -> true);
        Thread t = new Thread(agentBase);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.printf("[Agent] Success: %d, Fail: %d%n", agentBase.getSuccess(), agentBase.getFail());
            agentBase.stop();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));
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
