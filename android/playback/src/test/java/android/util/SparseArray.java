package android.util;

import java.util.ArrayList;
import java.util.Collections;

/** Test-only stand-in for android.util.SparseArray: sorted int keys, as the platform keeps them. */
public class SparseArray<E> implements Cloneable {
  private final ArrayList<Integer> keys = new ArrayList<>();
  private final ArrayList<E> values = new ArrayList<>();

  public SparseArray() {}

  public SparseArray(int initialCapacity) {}

  public int size() { return keys.size(); }

  public int keyAt(int index) { return keys.get(index); }

  public E valueAt(int index) { return values.get(index); }

  public void setValueAt(int index, E value) { values.set(index, value); }

  public int indexOfKey(int key) { return Collections.binarySearch(keys, key); }

  public boolean contains(int key) { return indexOfKey(key) >= 0; }

  public E get(int key) { return get(key, null); }

  public E get(int key, E valueIfKeyNotFound) {
    int i = indexOfKey(key);
    return i >= 0 ? values.get(i) : valueIfKeyNotFound;
  }

  public void put(int key, E value) {
    int i = indexOfKey(key);
    if (i >= 0) {
      values.set(i, value);
    } else {
      keys.add(~i, key);
      values.add(~i, value);
    }
  }

  public void append(int key, E value) { put(key, value); }

  public void delete(int key) {
    int i = indexOfKey(key);
    if (i >= 0) removeAt(i);
  }

  public void remove(int key) { delete(key); }

  public void removeAt(int index) {
    keys.remove(index);
    values.remove(index);
  }

  public void clear() {
    keys.clear();
    values.clear();
  }
}
