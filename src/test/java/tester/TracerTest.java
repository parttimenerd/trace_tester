package tester;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

public class TracerTest {

    static {
        JNIHelper.loadAndAttach();
    }

    @Test
    public void testRunGST() {
        Trace trace = new Tracer().runGST();
        assertTrue(trace.size() > 0);
        trace.lastFrame().hasMethod("testRunGST");
    }

    public static void main(String[] args) {
        new TracerTest().testRunGST();
    }

  /* @Test
    public void testRunASGCT() {
        Trace trace = new Tracer().runASGCT();
        assertTrue(trace.size() > 0);
    }

   private void method() {
        assertTrue(new Tracer().runASGCT().lastFrame().hasMethod("method"));
    }

   @Test
   public void testLastFrame() {
       method();
   }*/
}
