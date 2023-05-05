package tester;

import org.testng.annotations.Test;

import static org.testng.AssertJUnit.assertTrue;

/**
 * Basic tests
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

    public static void main(String[] args) {
        TracerTest tracerTest = new TracerTest();
        tracerTest.testRunAndCompare();
    }
}
