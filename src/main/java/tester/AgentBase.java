package tester;

import jdk.test.whitebox.WhiteBox;
import tester.Frame.JavaFrame;
import tester.Frame.MethodId;
import tester.Frame.MethodNameAndClass;
import tester.Tracer.Configuration;
import tester.Tracer.ConfiguredTrace;
import tester.Tracer.Mode;
import tester.util.Pair;
import tester.util.WhiteBoxUtil;
import tester.util.WhiteBoxUtil.CompilationLevelAndInlining;

import java.lang.reflect.Executable;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

public class AgentBase implements Runnable {

    private final Tracer tracer;

    private final float sampleInterval;

    private final int maxThreadsPerIteration = 10;

    private long success = 0;
    private long fail = 0;
    private long discarded = 0;

    private final Random random = new Random(0);
    private boolean shouldCollectMethodNames = false;


    /**
     * methods that have been executed and found during the sampling, used for compilation level randomization
     */
    private final Map<MethodId, Executable> executedMethods = new HashMap<>();

    private final Predicate<Trace> tracePredicate;

    /** methods that might appear in the bottom most frames of non-cut ASGCT traces */
    private final List<MethodNameAndClass> allowedBottomMethods = new ArrayList<>();

    public AgentBase(Tracer tracer, float sampleInterval, boolean shouldCollectMethods,
                     Predicate<Trace> tracePredicate) {
        this.tracer = tracer;
        this.sampleInterval = sampleInterval;
        this.shouldCollectMethodNames = shouldCollectMethods;
        this.tracePredicate = tracePredicate;
    }

    public void addAllowedBottomMethod(MethodNameAndClass method) {
        allowedBottomMethods.add(method);
    }

    public void printResult() {
        System.out.printf("[Agent] Success: %d, Fail: %d, Discarded: %d%n", success, fail, discarded);
    }

    protected void addMethod(MethodId methodId, Executable executable) {
        executedMethods.put(methodId, executable);
    }

    protected void collectMethodNames(Trace trace) {
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
                    addMethod(f.methodId, possibleMethods[0]);
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
                List<ConfiguredTrace> traces = tracer.runMultiple(t);
                if (tracer.hasASGCTSignalConfiguration()) {
                    var trace = traces.stream().filter(c -> c.config().mode() == Mode.ASGCT_SIGNAL_HANDLER).findFirst().get();
                    if (!trace.mightBeCutOff() && !trace.trace().isEmpty() && allowedBottomMethods.stream().noneMatch(m -> m.isSame(((JavaFrame)trace.trace().get(-1)).methodId))) {
                        System.err.println("[Agent] Discarding trace because of bottom frame " + trace.trace().get(-1));
                        discarded++;
                        printResult();
                        continue;
                    }
                }
                Trace trace = tracer.compare(traces, true);
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
                printResult();
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

    /**
     *
     * @param success count instances where the ASGCT trace is sensible (if it exists) and all traces are comparable
     * @param fail count instances where at least one trace is sensible and the others don't match
     * @param discarded count instances where all traces are not sensible
     */
    public record Result(long success, long fail, long discarded) {
        public boolean failed() {
            return fail > 0;
        }

        public Result add(Result other) {
            return new Result(success + other.success, fail + other.fail, discarded + other.discarded);
        }
    }

    public long getSuccess() {
        return success;
    }

    public long getFail() {
        return fail;
    }

    public static Result run(List<Configuration> configuration, float sampleInterval, int depth, Runnable runnable,
                             Predicate<Trace> predicate) {
        return run(configuration, sampleInterval, depth, runnable, predicate, null, List.of());
    }

    /**
     * Run the agent with the given tracer configuration on the given runnable, excluding the sampling thread
     */
    public static Result run(List<Configuration> configuration, float sampleInterval, int depth, Runnable runnable,
                             Predicate<Trace> predicate, Map<MethodId, Executable> collectedMethods, List<MethodNameAndClass> allowedBottomMethods) {
        AgentBase agent = new AgentBase(new Tracer(configuration).setDepth(depth), sampleInterval, collectedMethods != null, predicate) {
            @Override
            public List<Thread> selectThreads() {
                return super.selectThreads().stream().filter(t -> !t.getName().equals("Tester Agent")).toList();
            }

            @Override
            protected void addMethod(MethodId methodId, Executable executable) {
                super.addMethod(methodId, executable);
                if (collectedMethods != null) {
                    collectedMethods.put(methodId, executable);
                }
            }
        };
        allowedBottomMethods.forEach(agent::addAllowedBottomMethod);
        agent.addAllowedBottomMethod(new MethodNameAndClass("Ljava/lang/Thread;", "run", "()V"));
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
        agent.printResult();
        return new Result(agent.success, agent.fail, agent.discarded);
    }

    public record WhiteBoxConfig(double interpretedProbability, double inliningProbability, int maxSecondsToWait) {}
    public static Result runLoop(int iterations, WhiteBoxConfig config, BiFunction<Predicate<Trace>, Map<MethodId, Executable>, Result> body, String packagePrefix) {
        var random = new Random(0);
        var result = new Result(0, 0, 0);
        var methods = new HashMap<MethodId, Executable>();
        // the first run which collects methods and makes sure that every method has been run
        result.add(body.apply(t -> true, methods));
        for (int i = 0; i < iterations - 1; i++) {
            var levels = WhiteBoxUtil.createRandomCompilationLevels(random, methods.values().stream().filter(e -> e.getDeclaringClass().getPackageName().startsWith(packagePrefix)).collect(Collectors.toList()), config.interpretedProbability, config.inliningProbability);
            WhiteBoxUtil.forceCompilationLevels(levels, config.maxSecondsToWait);
            var newResult = body.apply(t -> {
                /*return t.stream().allMatch(f -> {
                    if (f instanceof JavaFrame javaFrame) {
                        if (javaFrame.type == JavaFrame.JAVA_INLINED) {
                            return true;
                        }
                        Executable executable = methods.get(javaFrame.methodId);
                        return WhiteBox.getWhiteBox().getCom
                    }
                    return true;
                })*/
                return true;
            }, methods);
            result = result.add(newResult);
        }
        return result;
    }

    @Override
    public void run() {
        loop();
    }
}
