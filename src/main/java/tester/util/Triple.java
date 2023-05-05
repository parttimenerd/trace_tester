package tester.util;

public class Triple<S, T, R> extends Pair<S, T> {
    public final R third;

    public Triple(S first, T second, R third) {
        super(first, second);
        this.third = third;
    }

    public static <S, T, R> Triple<S, T, R> of(S first, T second, R third) {
        return new Triple<>(first, second, third);
    }

    public static <S, T, R> Triple<S, T, R> t(S first, T second, R third) {
        return new Triple<>(first, second, third);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ", " + third + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Triple<?, ?, ?> other) {
            return first.equals(other.first) && second.equals(other.second) && third.equals(other.third);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return first.hashCode() ^ second.hashCode() ^ third.hashCode();
    }

    public R getThird() {
        return third;
    }
}
