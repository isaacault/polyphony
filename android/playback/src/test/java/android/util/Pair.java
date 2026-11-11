package android.util;

import java.util.Objects;

/** Test-only stand-in for android.util.Pair (the android.jar stub returns null from create). */
public class Pair<F, S> {
  public final F first;
  public final S second;

  public Pair(F first, S second) {
    this.first = first;
    this.second = second;
  }

  public static <A, B> Pair<A, B> create(A a, B b) {
    return new Pair<>(a, b);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Pair)) return false;
    Pair<?, ?> p = (Pair<?, ?>) o;
    return Objects.equals(p.first, first) && Objects.equals(p.second, second);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(first) ^ Objects.hashCode(second);
  }
}
