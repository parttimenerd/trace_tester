package tester;

import tester.Frame.JavaFrame;
import tester.Frame.MethodId;
import tester.Tracer.Configuration;
import tester.util.WhiteBoxUtil;

import java.lang.reflect.Executable;
import java.util.*;
import java.util.function.Predicate;

public class AgentBase implements Runnable {

    private final Tracer tracer;

    private final float sampleInterval;

    private final int maxThreadsPerIteration = 10;

    private long success = 0;
    private long fail = 0;

    private final Random random = new Random(0);
    private boolean shouldCollectMethodNames = false;


    /**
     * methods that have been executed and found during the sampling, used for compilation level randomization
     */
    private final Map<MethodId, Executable> executedMethods = new HashMap<>();

    private final Predicate<Trace> tracePredicate;

    public AgentBase(Tracer tracer, float sampleInterval, boolean shouldCollectMethods,
                     Predicate<Trace> tracePredicate) {
        this.tracer = tracer;
        this.sampleInterval = sampleInterval;
        this.shouldCollectMethodNames = shouldCollectMethods;
        this.tracePredicate = tracePredicate;
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
                                .filter(m -> m.getParameterCount() == f.methodId.countParameters())
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

    public List<Thread> selectThreads() {
        Thread[] threads = Tracer.getThreads();
        List<Thread> threadList = new ArrayList<>(List.of(threads));
        threadList.remove(Thread.currentThread());
        Collections.shuffle(threadList);
        return threadList.subList(0, Math.min(maxThreadsPerIteration, threadList.size()));
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
                Trace trace = tracer.compare(tracer.runMultiple(t), true);
                if (!tracePredicate.test(trace)) {
                    System.err.println("[Agent] Trace predicate failed");
                    fail++;
                    continue;
                }
                if (shouldCollectMethodNames) {
                    collectMethodNames(trace);
                }
                success++;
            } catch (AssertionError e) {
                e.printStackTrace();
                fail++;
                System.err.println(" success: " + success + " fail: " + fail);
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

    public void stop() {
        stop = true;
    }

    public record Result(long success, long fail) {
        public boolean failed() {
            return fail > 0;
        }
    }

    public long getSuccess() {
        return success;
    }

    public long getFail() {
        return fail;
    }

    /**
     * Run the agent with the given tracer configuration on the given runnable, excluding the sampling thread
     */
    public static Result run(List<Configuration> configuration, float sampleInterval, int depth, Runnable runnable,
                             Predicate<Trace> predicate) {
        AgentBase agent = new AgentBase(new Tracer(configuration).setDepth(depth), sampleInterval, false, predicate) {
            @Override
            public List<Thread> selectThreads() {
                return super.selectThreads().stream().filter(t -> !t.getName().equals("Tester Agent")).toList();
            }
        };
        Thread agentThread = new Thread(agent);
        agentThread.setName("Tester Agent");
        agentThread.start();
        Thread mainThread = new Thread(runnable);
        mainThread.setName("Tester Main");
        mainThread.start();
        try {
            mainThread.join();
            agent.stop();
            agentThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return new Result(agent.success, agent.fail);
    }

    @Override
    public void run() {
        loop();
    }
}
