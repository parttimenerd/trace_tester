package tester.agent;

import org.testng.annotations.Test;
import tester.AgentBase;
import tester.AgentBase.WhiteBoxConfig;
import tester.JNIHelper;
import tester.Tracer;
import tester.Tracer.Configuration;
import tester.agent.programs.MathParser;

import java.util.List;

import static org.testng.Assert.assertTrue;

public class AgentBasedTest {

    static {
        JNIHelper.loadAndAttachIfNeeded();
    }

    private void basicFunctionality() {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 100) ;
    }

    @Test
    public void testBasicFunctionality() {
        var result = AgentBase.run(Tracer.extensiveSpecificThreadConfigs, 0.001f, 1024, this::basicFunctionality,
                t -> true);
        assertTrue(result.success() > 0 && result.fail() == 0);
    }

    @Test
    public void testBasicFunctionalityFails() {
        var result = AgentBase.run(Tracer.extensiveSpecificThreadConfigs, 0.001f, 1024, this::basicFunctionality,
                t -> false);
        assertTrue(result.success() == 0 && result.fail() > 0);
    }

    void testMathParser(int depth) {
        testMathParser(Tracer.extensiveSpecificThreadConfigs, depth);
    }

    void testMathParser(List<Configuration> configurations, int depth) {
        var result = AgentBase.run(configurations, 0.0001f, depth,
                () -> MathParser.run(1001, 2000000, 500), t -> true);
        assertTrue(result.success() > 0 && result.fail() == 0);
    }

    @Test
    public void testMathParserWithDepthOne() {
        testMathParser(1);
    }

    @Test
    public void testMathParserWithDepthTen() {
        testMathParser(10);
    }

    private static final List<Configuration> minimalConfig = List.of(Tracer.Configuration.asgctSignalHandler(), Tracer.Configuration.asgstSignalHandler());

    @Test
    public void testMathParserWithDepthTenLessConfigs() {
        testMathParser(minimalConfig, 10);
    }

    @Test
    public void testMathParserWithFullDepthLessConfigs() {
        testMathParser(minimalConfig, 1024);
    }

    @Test
    public void testMathParserWithDepthTenLessConfigsReversed() {
        testMathParser(minimalConfig, 10);
    }

    @Test
    public void testMathParserWithRegularDepth() {
        testMathParser(1024);
    }

    public void testMathParserWithWhiteBox(int iterations, int depth, int rounds, List<Configuration> configurations) {
        var result = AgentBase.runLoop(iterations, new WhiteBoxConfig(0.2, 0.6, 3), (predicate, methods) -> {
            return AgentBase.run(configurations, 0.001f, depth, () -> MathParser.run(1001, rounds, 500), predicate, methods, List.of());
        }, "tester.agent");
        System.out.println(result);
        assertTrue(result.success() > 0 && result.fail() == 0);
    }

    @Test
    public void testMathParserWithWhiteBox() {
        testMathParserWithWhiteBox(100, 1024, 200000, minimalConfig);
    }
}
