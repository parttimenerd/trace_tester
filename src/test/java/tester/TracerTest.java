package tester;

import jdk.test.whitebox.WhiteBox;
import org.testng.annotations.Test;
import tester.util.CompilationLevel;
import tester.util.WhiteBoxUtil;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

/**
 * Basic tests that run the different APIs in their different modes on simple methods.
 * <p>
 * Inspired by <a href="https://bugs.openjdk.org/browse/JDK-8302320">a bug</a>.
 */
public class TracerTest {

    static {
        JNIHelper.loadAndAttachIfNeeded();
    }

    @Test
    public void testRunGST() {
        Trace trace = new Tracer().runGST();
        assertTrue(trace.size() > 0);
        trace.assertTrue(Frame.hasMethod(0, "testRunGST", "()V"));
    }

    private int innerGST2() {
        new Tracer().runGST().assertTrue(Frame.hasMethod(0, "innerGST2", "()I"), Frame.hasMethod(1, "testRunGST2"));
        return 0;
    }

    @Test
    public void testRunGST2() {
        innerGST2();
    }

    @Test
    public void testRunASGCT() {
        Trace trace = new Tracer().runASGCT();
        assertTrue(trace.size() > 0);
        trace.assertTrue(Frame.hasMethod(0, "testRunASGCT", "()V"));
    }

    private int innerASGCT2() {
        new Tracer().runASGCT().assertTrue(Frame.hasMethod(0, "innerASGCT2", "()I"), Frame.hasMethod(1,
                "testRunASGCT2"));
        return 0;
    }

    @Test
    public void testRunASGCT2() {
        innerASGCT2();
    }

    @Test
    public void testRunASGST() {
        Trace trace = new Tracer().runASGST();
        assertTrue(trace.size() > 0);
        trace.assertTrue(Frame.hasMethod(0, "testRunASGST", "()V"));
    }

    private int innerASGST() {
        new Tracer().runASGST().assertTrue(Frame.hasMethod(0, "innerASGST", "()I"), Frame.hasMethod(1,
                "testRunASGST2"));
        return 0;
    }

    @Test
    public void testRunASGST2() {
        innerASGST();
    }

    @Test
    public void testRunASGSTInSignalHandler() {
        Trace trace = new Tracer().runASGSTInSignalHandler();
        assertTrue(trace.size() > 0);
        trace.assertTrue(Frame.hasMethod(0, "testRunASGSTInSignalHandler", "()V"));
    }

    private int innerASGSTInSignalHandler() {
        new Tracer().runASGSTInSignalHandler().assertTrue(Frame.hasMethod(0, "innerASGSTInSignalHandler", "()I"),
                Frame.hasMethod(1, "testRunASGSTInSignalHandler2"));
        return 0;
    }

    @Test
    public void testRunASGSTInSignalHandler2() {
        innerASGSTInSignalHandler();
    }

    @Test
    public void testRunASGSTInSeparateThread() {
        Trace trace = new Tracer().runASGSTInSeparateThread();
        assertTrue(trace.size() > 0);
        trace.assertTrue(Frame.hasMethod(0, "testRunASGSTInSeparateThread", "()V"));
    }

    private int innerASGSTInSeparateThread() {
        new Tracer().runASGSTInSeparateThread().assertTrue(
                Frame.hasMethod(0, "innerASGSTInSeparateThread", "()I"),
                Frame.hasMethod(1, "testRunASGSTInSeparateThread2"));
        return 0;
    }

    @Test
    public void testRunASGSTInSeparateThread2() {
        innerASGSTInSeparateThread();
    }

    @Test
    public void testRunAndCompare() {
        new Tracer().runAndCompare().assertTrue(Frame.hasMethod(0, "testRunAndCompare", "()V"));
    }

    /**
     * First WhiteBox related test, that checks a compiled method is shown as compiled
     */
    @Test
    public void testBasicWhiteBoxCompileTest() throws NoSuchMethodException {
        var m = TracerTest.class.getDeclaredMethod("innerBasicWhiteBoxCompileTest");
        var wb = WhiteBox.getWhiteBox();
        innerBasicWhiteBoxCompileTestCompLevel = wb.getMethodCompilationLevel(m);
        innerBasicWhiteBoxCompileTest();
        WhiteBoxUtil.forceCompilationLevel(m, CompilationLevel.COMP_LEVEL_MAX);
        innerBasicWhiteBoxCompileTestCompLevel = wb.getMethodCompilationLevel(m);
        assertEquals(CompilationLevel.COMP_LEVEL_MAX, innerBasicWhiteBoxCompileTestCompLevel);
        innerBasicWhiteBoxCompileTest();
        wb.deoptimizeMethod(m);
        innerBasicWhiteBoxCompileTestCompLevel = wb.getMethodCompilationLevel(m);
        assertEquals(CompilationLevel.COMP_LEVEL_NONE, innerBasicWhiteBoxCompileTestCompLevel);
        innerBasicWhiteBoxCompileTest();
    }

    private int innerBasicWhiteBoxCompileTestCompLevel = 0;

    private boolean innerBasicWhiteBoxCompileTest() throws NoSuchMethodException {
        new Tracer().runAndCompare().assertTrue(Frame.matchesExecutable(0, TracerTest.class.getDeclaredMethod(
                "innerBasicWhiteBoxCompileTest")).hasCompilationLevel(innerBasicWhiteBoxCompileTestCompLevel),
                Frame.hasMethod(1, "testBasicWhiteBoxCompileTest"));
        return true;
    }

    public static void main(String[] args) throws Exception {
        TracerTest tracerTest = new TracerTest();
        tracerTest.testBasicWhiteBoxCompileTest();
    }
}
