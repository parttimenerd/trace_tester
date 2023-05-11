package tester;

import org.testng.annotations.Test;
import tester.Frame.JavaFrame;
import tester.Frame.Matcher;
import tester.util.CompilationLevel;
import tester.util.Pair;
import tester.util.WhiteBoxUtil;
import tester.util.WhiteBoxUtil.CompilationLevelAndInlining;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.testng.AssertJUnit.assertFalse;

/**
 * Set the compilation level randomly for all methods and asserts that the profiling still works and returns the correct
 * compilation level. The same is done for the inlining.
 * <p>
 * It creates traces where the following holds, assuming that method n is the callee of method n+1:
 * <li>
 *     <ul>method n is only inlined if method n+1 is inlined or compiled</ul>
 *     <ul>method n has the same compilation level as method n+1 if method n+1 is inlined or compiled</ul>
 *     <ul>method n_max is not inlined</ul>
 *     <ul>every method n (schema methodN) except method n_max, calls method n-1 and nothing else</ul>
 * </li>
 * <p>
 * This test checks for API correctness with
 */
public class RandomCompLevelTest {

    static {
        JNIHelper.loadAndAttachIfNeeded();
    }

    private final int RUNS = 10;

    private static int parseMethodNameToInt(String name) {
        return Integer.parseInt(name.replaceAll("[A-Za-z]+", ""));
    }

    private static List<Executable> getSortedMethods(String prefix) {
        return Arrays.stream(RandomCompLevelTest.class.getDeclaredMethods()).filter(m -> m.getName().matches(prefix + "\\d+"))
                .sorted(Comparator.<Method>comparingInt(m -> parseMethodNameToInt(m.getName())).reversed())
                .collect(Collectors.toList());
    }

    private final List<Executable> executableTrace = getSortedMethods("method");

    private List<Pair<Executable, CompilationLevelAndInlining>> levels;

    private boolean dontCheck = false;

    @Test
    public void testWithoutInlining() {
        testRuns(false);
    }

    @Test
    public void testWithInlining() {
        testRuns(true);
    }

    public void testRuns(boolean withInlining) {
        int run = 0;
        int fail = 0;
        for (int i = 0; i < RUNS; i++) {
            int seed = i;
            try {
                testRun(i == 0, withInlining, seed);
            } catch (Throwable t) {
                System.err.println("failed with seed " + seed);
                t.printStackTrace();
                fail++;
            }
            run++;
            System.out.println("run: " + run + " fail: " + fail);
        }
        if (fail > 0) {
            throw new AssertionError("failed " + fail + " out of " + run + " runs");
        }
    }

    public void testRun(boolean isFirst, boolean withInlining, int seed) {
        var levels = WhiteBoxUtil.createRandomCompilationLevels(new Random(seed), executableTrace, 1 / 3f,
                withInlining ? 0.5 : 0);
        if (isFirst) {
            this.levels = null;
            method1();
            WhiteBoxUtil.forceCompilationLevels(levels);
            method1();
        }
        this.levels = levels;
        this.dontCheck = true;
        method1();
        this.dontCheck = false;
        if (!isFirst) {
            WhiteBoxUtil.forceCompilationLevels(levels);
        }
        System.out.println("seed: " + seed + " levels: " + levels);
        levels.forEach(p -> System.out.println("       " + p.getFirst() + " " + p.getSecond()));
        method1();
    }

    public static void main(String[] args) {
        new RandomCompLevelTest().testWithInlining();
    }

    private int method1() {
        return method2();
    }

    private int method2() {
        return method3();
    }

    private int method3() {
        return method4();
    }

    private int method4() {
        return method5();
    }

    private int method5() {
        return method6();
    }

    private int method6() {
        return method7();
    }

    private int method7() {
        return method8();
    }

    private int method8() {
        return method9();
    }

    private int method9() {
        return method10();
    }

    public int method10() {
        if (dontCheck) {
            return 1;
        }
        var matchers = IntStream.range(0, executableTrace.size()).mapToObj(i -> {
            var m = executableTrace.get(i);
            return Frame.matchesExecutable(i, m);
        }).toArray(Matcher[]::new);
        new Tracer(Tracer.nonASGSTConfigs).runAndCompare().assertTrue(matchers);
        if (levels == null) {
            // checks that the methods are correct with all profiling APIs
            new Tracer(Tracer.extensiveConfigs).runAndCompare().assertTrue(matchers);
        } else {
            // checks that the methods are correct with all profiling APIs
            var trace = new Tracer(Tracer.extensiveASGSTConfigs).runAndCompare();
            trace.assertTrue(WhiteBoxUtil.compilationLevelsToMatchers(levels));
            if (trace.stream().anyMatch(f -> f.type == JavaFrame.JAVA_INLINED)) {
                System.out.println(trace.stream().filter(f -> f.type == JavaFrame.JAVA_INLINED).count());
            }
            trace.forEach(f -> System.out.println("   " + f));
        }
        return 1;
    }

    private boolean testForceNoInlineCheckInline = false;
    private final List<Executable> testForceNoInlineMethods = getSortedMethods("methodForceNoInline");

    /**
     * A simple test to check that the inlining prevention works.
     */
    @Test
    public void testForceNoInline() {
        testForceNoInlineCheckInline = false;
        methodForceNoInline1();
        var comps =
                IntStream.range(0, testForceNoInlineMethods.size()).mapToObj(i -> new Pair<>(testForceNoInlineMethods.get(i), new CompilationLevelAndInlining(i == testForceNoInlineMethods.size() - 1 ? CompilationLevel.COMP_LEVEL_MAX : CompilationLevel.COMP_LEVEL_NONE, false))).collect(Pair.toMap());
        testForceNoInlineMethods.forEach(m -> System.out.println(m + " " + comps.get(m)));
        WhiteBoxUtil.forceCompilationLevels(comps);
        testForceNoInlineCheckInline = true;
        methodForceNoInline1();
    }

    public int methodForceNoInline1() {
        return methodForceNoInline2();
    }

    public int methodForceNoInline2() {
        return methodForceNoInline3();
    }

    public int methodForceNoInline3() {
        return methodForceNoInline4();
    }

    public int methodForceNoInline4() {
        var trace = new Tracer(Tracer.extensiveASGSTConfigs).runAndCompare().withOnlyTopMatching("methodForceNoInline" +
                ".*");
        if (testForceNoInlineCheckInline) {
            System.out.println(trace);
            assertFalse(trace.stream().anyMatch(f -> f.type == JavaFrame.JAVA_INLINED));
        }
        return 1;
    }
}
