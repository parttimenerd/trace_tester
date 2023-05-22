package tester;

import org.testng.annotations.Test;
import tester.util.WhiteBoxUtil;
import tester.util.WhiteBoxUtil.CompilationLevelAndInlining;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Check that modifying the compilation level of a method that is already on the stack does not change the compilation
 * level of the prior frame.
 * <p>
 * (force a methods to be interpreted) -> method1 -> method2 -> method3
 * -> method4 (make method2 compiled, method 3 interpreted) -> method1 -> method2 -> method3 -> method4
 * <p>
 * this should result in the following trace:
 * <li>
 *     <ol>method4: interpreted</ol>
 *     <ol>method3: compiled and inlined</ol>
 *     <ol>method2: compiled and not inlined</ol>
 *     <ol>method1: interpreted</ol>
 *     <ol>method4: interpreted</ol>
 *     <ol>...</ol>
 *     <ol>method1: interpreted</ol>
 *     <ol>test</ol>
 * </li>
 * <p>
 * So method2 and method3 related frames are present as compiled and interpreted.
 */
public class ModifiedCompilationLevelTest {

    static {
        JNIHelper.loadAndAttachIfNeeded();
    }

    private boolean ranMethod4 = false;
    private boolean trialRun = true;

    private Map<String, Executable> methods;

    public static void main(String[] args) {
        new ModifiedCompilationLevelTest().test();
    }

    @Test
    public void test() {
        ranMethod4 = false;
        trialRun = true;
        methods =
                Arrays.stream(ModifiedCompilationLevelTest.class.getDeclaredMethods()).filter(m -> m.getName().startsWith(
                        "method")).collect(Collectors.toMap(Executable::getName, m -> m));
        // force methods to be interpreted
        method1();
        WhiteBoxUtil.forceCompilationLevels(methods.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue,
                e -> CompilationLevelAndInlining.INTERPRETED)));
        trialRun = false;
        ranMethod4 = false;
        method1();
    }

    public void method1() {
        method2();
    }

    public void method2() {
        method3();
    }

    public void method3() {
        method4();
    }

    public void method4() {
        if (ranMethod4) {
            var matchers = new Frame.Matcher[]{
                    Frame.hasMethod(0, "method4"), Frame.hasMethod(1, "method3"), Frame.hasMethod(2, "method2"),
                    Frame.hasMethod(3, "method1"), Frame.hasMethod(4, "method4"), Frame.hasMethod(5, "method3"),
                    Frame.hasMethod(6, "method2"), Frame.hasMethod(7, "method1"), Frame.hasMethod(8, "test")
            };
            new Tracer(Tracer.extensiveConfigs).runAndCompare().assertTrue(matchers);
            if (!trialRun) {
                matchers[1] = matchers[1].has(CompilationLevelAndInlining.COMPILED_INLINED);
                matchers[2] = matchers[2].has(CompilationLevelAndInlining.COMPILED_NOT_INLINED);
                new Tracer(Tracer.extensiveASGSTConfigs).runAndCompare().assertTrue(matchers);
            }
        } else {
            ranMethod4 = true;
            var compLevels = methods.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue,
                    e -> switch (e.getKey()) {
                        case "method2" -> CompilationLevelAndInlining.COMPILED_NOT_INLINED;
                        case "method3" -> CompilationLevelAndInlining.COMPILED_INLINED;
                        default -> CompilationLevelAndInlining.INTERPRETED;
                    }));
            WhiteBoxUtil.forceCompilationLevels(compLevels);
            method1();
        }
    }
}
