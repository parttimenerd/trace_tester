package tester.agent;

import org.testng.annotations.Test;
import tester.AgentBase;
import tester.JNIHelper;
import tester.Tracer;
import tester.agent.programs.MathParser;

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
        var result = AgentBase.run(Tracer.extensiveSpecificThreadConfigs, 0.0001f, depth,
                () -> MathParser.run(1001, 150000, 500), t -> true);
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

    @Test
    public void testMathParserWithRegularDepth() {
        testMathParser(1024);
    }
}
