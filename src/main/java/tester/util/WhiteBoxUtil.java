package tester.util;

import jdk.test.whitebox.WhiteBox;
import tester.Frame;
import tester.Frame.JavaFrame;

import java.lang.reflect.Executable;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class WhiteBoxUtil {

    /**
     * waits till the method is compiled with the given level
     * <p>
     * be aware that this assumes that the method has been called before
     */
    public static void forceCompilationLevel(Executable m, int level) {
        WhiteBox wb = WhiteBox.getWhiteBox();
        if (level == CompilationLevel.COMP_LEVEL_NONE) {
            wb.deoptimizeMethod(m);
        } else {
            wb.enqueueMethodForCompilation(m, level);
        }
        while (wb.getMethodCompilationLevel(m) != level) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public record CompilationLevelAndInlining(int level, boolean inline) {

        public static CompilationLevelAndInlining of(int level, boolean inline) {
            return new CompilationLevelAndInlining(level, inline);
        }

        public static CompilationLevelAndInlining INTERPRETED = of(CompilationLevel.COMP_LEVEL_NONE, false);

        public static CompilationLevelAndInlining COMPILED_NOT_INLINED = of(CompilationLevel.COMP_LEVEL_MAX, false);

        public static CompilationLevelAndInlining COMPILED_INLINED = of(CompilationLevel.COMP_LEVEL_MAX, true);
    }

    public static void reset(Collection<Executable> methods) {
        WhiteBox wb = WhiteBox.getWhiteBox();
        methods.forEach(wb::deoptimizeMethod);
        methods.forEach(wb::clearMethodState); // required for a reset
    }

    public static void forceCompilationLevels(Map<Executable, CompilationLevelAndInlining> levels) {
        WhiteBox wb = WhiteBox.getWhiteBox();
        reset(levels.keySet());
        List<Executable> inlinedMethods = new ArrayList<>();
        List<Executable> notInlinedMethods = new ArrayList<>();
        levels.forEach((m, level) -> {
            if (level.inline) {
                inlinedMethods.add(m);
            } else {
                notInlinedMethods.add(m);
            }
        });
        CompilerDirectives.of(
                CompilerDirectives.matches(inlinedMethods).inline(),
                CompilerDirectives.matches(notInlinedMethods).noInline()).apply();
        levels.forEach((m, level) -> {
            int currentLevel = wb.getMethodCompilationLevel(m);
            if (currentLevel == level.level()) {
                return;
            }
            if (level.level() == CompilationLevel.COMP_LEVEL_NONE) {
                wb.deoptimizeMethod(m);
            } else {
                wb.enqueueMethodForCompilation(m, level.level());
            }
            if (!level.inline) {
                wb.testSetDontInlineMethod(m, true);
                wb.testSetForceInlineMethod(m, false);
            } else {
                wb.testSetForceInlineMethod(m, true);
                wb.testSetDontInlineMethod(m, false);
            }
        });
        while (levels.entrySet().stream().anyMatch(e -> !e.getValue().inline && wb.getMethodCompilationLevel(e.getKey()) != e.getValue().level())) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public static void forceCompilationLevels(List<Pair<Executable, CompilationLevelAndInlining>> levels) {
        forceCompilationLevels(levels.stream().collect(Pair.toMap()));
    }

    @SuppressWarnings("unchecked")
    public static Pair<Integer, Predicate<JavaFrame>>[] compilationLevelsToMatchers(List<Pair<Executable,
            CompilationLevelAndInlining>> frames) {
        // compute real compilation levels in respect to inlining
        List<Integer> compLevelFrames =
                frames.stream().map(Pair::getSecond).map(CompilationLevelAndInlining::level).collect(Collectors.toList());
        int lastCompLevel = 0;
        for (int i = compLevelFrames.size() - 1; i >= 0; i--) {
            var level = frames.get(i).getSecond();
            if (level.inline) {
                compLevelFrames.set(i, lastCompLevel);
            } else {
                lastCompLevel = compLevelFrames.get(i);
            }
        }
        var res = IntStream.range(0, frames.size()).mapToObj(i -> {
            var p = frames.get(i);
            var m = p.getFirst();
            var level = compLevelFrames.get(i);
            var inline = p.getSecond().inline;
            if (inline) {
                return Frame.matchesExecutable(i, m).isInlined().hasCompilationLevel(level);
            }
            return Frame.matchesExecutable(i, m).hasCompilationLevel(level).isNotInlined();
        }).toArray(Pair[]::new);
        System.out.println("comp level matchers");
        for (var p : res) {
            System.out.println("         " + p);
        }
        return res;
    }

    /**
     * Create random compilation levels with 1/3 probability of interpreting and a 0 probability of inlining compiled
     * methods
     */
    public static List<Pair<Executable, CompilationLevelAndInlining>> createRandomCompilationLevelsWithoutInlining(Random random, List<Executable> trace) {
        return createRandomCompilationLevels(random, trace, 1.0 / 3.0, 0);
    }

    /**
     * Create random compilation levels with 1/3 probability of interpreting and 0.5 probability of inlining compiled
     * methods
     */
    public static List<Pair<Executable, CompilationLevelAndInlining>> createRandomCompilationLevels(Random random,
                                                                                                    List<Executable> trace) {
        return createRandomCompilationLevels(random, trace, 1.0 / 3.0, 0.5);
    }

    /**
     * Create random compilation levels
     *
     * @param trace                  list of methods to compile
     * @param interpretedProbability probability of interpreting a method
     * @param inliningProbability    probability of inlining a compiled method
     * @return list of pairs of method and compilation level
     */
    public static List<Pair<Executable, CompilationLevelAndInlining>> createRandomCompilationLevels(Random random,
                                                                                                    List<Executable> trace, double interpretedProbability, double inliningProbability) {
        return ListUtils.reversedDraggingMap(trace, (d) -> {
            Executable m = d.value();
            int level;
            boolean inlining = false;
            if (random.nextDouble() < interpretedProbability) { // 1/3 of the time, interpret
                level = CompilationLevel.COMP_LEVEL_NONE;
            } else {
                int low = CompilationLevel.COMP_LEVEL_NONE + 1;
                level = Math.min(CompilationLevel.COMP_LEVEL_MAX,
                        (int) (random.nextDouble() * (CompilationLevel.COMP_LEVEL_MAX + 1 - low)) + low);
                boolean isCallerInlinedOrCompiled =
                        !d.isLast() && (d.getHigher().getSecond().inline || d.getHigher().getSecond().level() != CompilationLevel.COMP_LEVEL_NONE);
                inlining = random.nextDouble() < inliningProbability && isCallerInlinedOrCompiled; // don't inline
                // the top most method
            }
            return new Pair<>(m, new CompilationLevelAndInlining(level, inlining));
        });
    }
}
