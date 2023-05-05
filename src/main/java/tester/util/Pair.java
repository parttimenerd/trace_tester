package tester.util;

public class Pair<S, T> {

    public final S first;
    public final T second;

    public Pair(S first, T second) {
        this.first = first;
        this.second = second;
    }

    public static <S, T> Pair<S, T> of(S first, T second) {
        return new Pair<>(first, second);
    }

    public static <S, T> Pair<S, T> p(S first, T second) {
        return new Pair<>(first, second);
    }

    public <R> Triple<S, T, R> add(R third) {
        return Triple.of(first, second, third);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Pair<?, ?> other) {
            return first.equals(other.first) && second.equals(other.second);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return first.hashCode() ^ second.hashCode();
    }

    public S getFirst() {
        return first;
    }

    public T getSecond() {
        return second;
    }
}
