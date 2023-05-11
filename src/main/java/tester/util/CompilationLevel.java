package tester.util;

/**
 * Copied from
 * <a href="https://github.com/parttimenerd/jdk/blob/77cd917a97b184871ab2d3325ceb6c53afeca28b/test/hotspot/jtreg/compiler/whitebox/CompilerWhiteBoxTest.java#L42-L55">CompilerWhiteBoxTest.java</a>
 */
public class CompilationLevel {
    /**
     * {@code CompLevel::CompLevel_none} -- Interpreter
     */
    public static final int COMP_LEVEL_NONE = 0;
    /**
     * {@code CompLevel::CompLevel_any}, {@code CompLevel::CompLevel_all}
     */
    public static final int COMP_LEVEL_ANY = -1;
    /**
     * {@code CompLevel::CompLevel_simple} -- C1
     */
    public static final int COMP_LEVEL_SIMPLE = 1;
    /**
     * {@code CompLevel::CompLevel_limited_profile} -- C1, invocation &amp; backedge counters
     */
    public static final int COMP_LEVEL_LIMITED_PROFILE = 2;
    /**
     * {@code CompLevel::CompLevel_full_profile} -- C1, invocation &amp; backedge counters + mdo
     */
    public static final int COMP_LEVEL_FULL_PROFILE = 3;
    /**
     * {@code CompLevel::CompLevel_full_optimization} -- C2
     */
    public static final int COMP_LEVEL_FULL_OPTIMIZATION = 4;
    /**
     * Maximal value for CompLevel
     */
    public static final int COMP_LEVEL_MAX = COMP_LEVEL_FULL_OPTIMIZATION;
}
