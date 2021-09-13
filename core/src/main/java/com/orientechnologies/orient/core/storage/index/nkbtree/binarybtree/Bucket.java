package com.orientechnologies.orient.core.storage.index.nkbtree.binarybtree;

import com.orientechnologies.common.comparator.OComparatorFactory;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.serialization.types.OShortSerializer;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurablePage;
import java.nio.ByteBuffer;
import java.util.*;

public final class Bucket extends ODurablePage {
  private static final int RID_SIZE = OShortSerializer.SHORT_SIZE + OLongSerializer.LONG_SIZE;

  private static final int FREE_POINTER_OFFSET = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET = FREE_POINTER_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int IS_LEAF_OFFSET = SIZE_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int LEFT_SIBLING_OFFSET = IS_LEAF_OFFSET + OByteSerializer.BYTE_SIZE;
  private static final int RIGHT_SIBLING_OFFSET = LEFT_SIBLING_OFFSET + OLongSerializer.LONG_SIZE;

  private static final int NEXT_FREE_LIST_PAGE_OFFSET = NEXT_FREE_POSITION;

  private static final int POSITIONS_ARRAY_OFFSET =
      RIGHT_SIBLING_OFFSET + OLongSerializer.LONG_SIZE;

  public Bucket(OCacheEntry cacheEntry) {
    super(cacheEntry);
  }

  public void switchBucketType() {
    if (!isEmpty()) {
      throw new IllegalStateException(
          "Type of bucket can be changed only bucket if bucket is empty");
    }

    final boolean isLeaf = isLeaf();
    if (isLeaf) {
      setByteValue(IS_LEAF_OFFSET, (byte) 0);
    } else {
      setByteValue(IS_LEAF_OFFSET, (byte) 1);
    }
  }

  public void init(boolean isLeaf) {
    setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);
    setIntValue(SIZE_OFFSET, 0);

    setByteValue(IS_LEAF_OFFSET, (byte) (isLeaf ? 1 : 0));
    setLongValue(LEFT_SIBLING_OFFSET, -1);
    setLongValue(RIGHT_SIBLING_OFFSET, -1);
  }

  public boolean isEmpty() {
    return size() == 0;
  }

  public boolean isLeaf() {
    return getByteValue(IS_LEAF_OFFSET) > 0;
  }

  public int size() {
    return getIntValue(SIZE_OFFSET);
  }

  public int find(final byte[] key) {
    final ByteBuffer bufferKey = ByteBuffer.wrap(key);

    int low = 0;
    int high = size() - 1;

    while (low <= high) {
      final int mid = (low + high) >>> 1;
      final ByteBuffer midVal = getKeyBuffer(mid);
      final int cmp = midVal.compareTo(bufferKey);

      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid; // key found
      }
    }

    return -(low + 1); // key not found.
  }

  public byte[] getKey(final int index) {
    int entryPosition = getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf()) {
      entryPosition += 2 * OIntegerSerializer.INT_SIZE;
    }

    final int keySize = getShortValue(entryPosition);
    return getBinaryValue(entryPosition + OShortSerializer.SHORT_SIZE, keySize);
  }

  public ByteBuffer getKeyBuffer(final int index) {
    int entryPosition = getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf()) {
      entryPosition += 2 * OIntegerSerializer.INT_SIZE;
    }

    final int keySize = getShortValue(entryPosition);
    return getBinaryValueBuffer(entryPosition + OShortSerializer.SHORT_SIZE, keySize);
  }

  public void addAll(final List<byte[]> rawEntries) {
    final int currentSize = size();
    for (int i = 0; i < rawEntries.size(); i++) {
      appendRawEntry(i + currentSize, rawEntries.get(i));
    }

    setIntValue(SIZE_OFFSET, rawEntries.size() + currentSize);
  }

  private void appendRawEntry(final int index, final byte[] rawEntry) {
    int freePointer = getIntValue(FREE_POINTER_OFFSET);
    freePointer -= rawEntry.length;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE, freePointer);

    setBinaryValue(freePointer, rawEntry);
  }

  public void shrink(final int newSize) {
    final List<byte[]> rawEntries = new ArrayList<>(newSize);

    for (int i = 0; i < newSize; i++) {
      rawEntries.add(getRawEntry(i));
    }

    setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);

    for (int i = 0; i < newSize; i++) {
      appendRawEntry(i, rawEntries.get(i));
    }

    setIntValue(SIZE_OFFSET, newSize);
  }

  public byte[] getRawEntry(final int entryIndex) {
    int entryPosition =
        getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    final int startEntryPosition = entryPosition;

    if (isLeaf()) {
      final int keySize = getShortValue(entryPosition);

      return getBinaryValue(startEntryPosition, OShortSerializer.SHORT_SIZE + keySize + RID_SIZE);
    } else {
      entryPosition += 2 * OIntegerSerializer.INT_SIZE;

      final int keySize = getShortValue(entryPosition);

      return getBinaryValue(
          startEntryPosition,
          keySize + 2 * OIntegerSerializer.INT_SIZE + OShortSerializer.SHORT_SIZE);
    }
  }

  public ORID getValue(final int entryIndex) {
    assert isLeaf();

    int entryPosition =
        getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    // skip key
    entryPosition += getShortValue(entryPosition) + OShortSerializer.SHORT_SIZE;

    final int clusterId = getShortValue(entryPosition);
    final long clusterPosition = getLongValue(entryPosition + OShortSerializer.SHORT_SIZE);

    return new ORecordId(clusterId, clusterPosition);
  }

  public boolean addLeafEntry(final int index, final byte[] key, final byte[] serializedValue) {
    final int entrySize = OShortSerializer.SHORT_SIZE + key.length + serializedValue.length;

    assert isLeaf();
    final int size = getIntValue(SIZE_OFFSET);

    int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize
        < (size + 1) * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return false;
    }

    if (index <= size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + (index + 1) * OIntegerSerializer.INT_SIZE,
          (size - index) * OIntegerSerializer.INT_SIZE);
    }

    freePointer -= entrySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE, freePointer);
    setIntValue(SIZE_OFFSET, size + 1);

    setShortValue(freePointer, (short) key.length);
    setBinaryValue(freePointer + OShortSerializer.SHORT_SIZE, key);
    setBinaryValue(freePointer + key.length + OShortSerializer.SHORT_SIZE, serializedValue);

    return true;
  }

  public boolean addLeafEntry(final int index, final byte[] entry) {
    final int entrySize = entry.length;

    assert isLeaf();
    final int size = getIntValue(SIZE_OFFSET);

    int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize
        < (size + 1) * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return false;
    }

    if (index <= size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + (index + 1) * OIntegerSerializer.INT_SIZE,
          (size - index) * OIntegerSerializer.INT_SIZE);
    }

    freePointer -= entrySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE, freePointer);
    setIntValue(SIZE_OFFSET, size + 1);

    setBinaryValue(freePointer, entry);
    return true;
  }

  public int removeLeafEntry(final int entryIndex, int keySize) {
    final int entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE);

    final int entrySize;
    if (isLeaf()) {
      entrySize = keySize + RID_SIZE + OShortSerializer.SHORT_SIZE;
    } else {
      throw new IllegalStateException("Remove is applies to leaf buckets only");
    }

    int size = getIntValue(SIZE_OFFSET);
    if (entryIndex < size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + (entryIndex + 1) * OIntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE,
          (size - entryIndex - 1) * OIntegerSerializer.INT_SIZE);
    }

    size--;
    setIntValue(SIZE_OFFSET, size);

    final int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
    }

    setIntValue(FREE_POINTER_OFFSET, freePointer + entrySize);

    int currentPositionOffset = POSITIONS_ARRAY_OFFSET;

    for (int i = 0; i < size; i++) {
      final int currentEntryPosition = getIntValue(currentPositionOffset);
      if (currentEntryPosition < entryPosition) {
        setIntValue(currentPositionOffset, currentEntryPosition + entrySize);
      }
      currentPositionOffset += OIntegerSerializer.INT_SIZE;
    }

    return size;
  }

  public boolean addNonLeafEntry(
      final int index, final int leftChildIndex, final int newRightChildIndex, final byte[] key) {
    assert !isLeaf();

    final int keySize = key.length + OShortSerializer.SHORT_SIZE;

    final int entrySize = keySize + 2 * OIntegerSerializer.INT_SIZE;

    int size = size();
    int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize
        < (size + 1) * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return false;
    }

    if (index <= size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + (index + 1) * OIntegerSerializer.INT_SIZE,
          (size - index) * OIntegerSerializer.INT_SIZE);
    }

    freePointer -= entrySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE, freePointer);
    setIntValue(SIZE_OFFSET, size + 1);

    freePointer += setIntValue(freePointer, leftChildIndex);
    freePointer += setIntValue(freePointer, newRightChildIndex);

    setShortValue(freePointer, (short) key.length);
    setBinaryValue(freePointer + OShortSerializer.SHORT_SIZE, key);

    size++;

    if (size > 1) {
      if (index < size - 1) {
        final int nextEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (index + 1) * OIntegerSerializer.INT_SIZE);
        setIntValue(nextEntryPosition, newRightChildIndex);
      }
    }

    return true;
  }

  public int getLeft(final int entryIndex) {
    assert !isLeaf();

    final int entryPosition =
        getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    return getIntValue(entryPosition);
  }

  public int getRight(final int entryIndex) {
    assert !isLeaf();

    final int entryPosition =
        getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    return getIntValue(entryPosition + OIntegerSerializer.INT_SIZE);
  }

  byte[] getRawValue(final int entryIndex) {
    assert isLeaf();

    int entryPosition =
        getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    // skip key
    entryPosition += getShortValue(entryPosition) + OShortSerializer.SHORT_SIZE;

    return getBinaryValue(entryPosition, RID_SIZE);
  }

  public void updateValue(final int index, final byte[] value, final int keySize) {
    final int entryPosition =
        getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET)
            + keySize
            + OShortSerializer.SHORT_SIZE;

    setBinaryValue(entryPosition, value);
  }

  public boolean updateKey(final int entryIndex, final byte[] key) {
    if (isLeaf()) {
      throw new IllegalStateException("Update key is applied to non-leaf buckets only");
    }

    final int entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE);
    final int keySize = getShortValue(entryPosition + 2 * OIntegerSerializer.INT_SIZE);

    if (key.length == keySize) {
      setBinaryValue(
          entryPosition + 2 * OIntegerSerializer.INT_SIZE + OShortSerializer.SHORT_SIZE, key);
      return true;
    }

    int size = getIntValue(SIZE_OFFSET);
    int freePointer = getIntValue(FREE_POINTER_OFFSET);

    if (freePointer - key.length + keySize
        < size * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return false;
    }

    final int entrySize = keySize + 2 * OIntegerSerializer.INT_SIZE + OShortSerializer.SHORT_SIZE;

    final int leftChildIndex = getIntValue(entryPosition);
    final int rightChildIndex = getIntValue(entryPosition + OIntegerSerializer.INT_SIZE);

    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);

      int currentPositionOffset = POSITIONS_ARRAY_OFFSET;

      for (int i = 0; i < size; i++) {
        if (i == entryIndex) {
          currentPositionOffset += OIntegerSerializer.INT_SIZE;
          continue;
        }

        final int currentEntryPosition = getIntValue(currentPositionOffset);
        if (currentEntryPosition < entryPosition) {
          setIntValue(currentPositionOffset, currentEntryPosition + entrySize);
        }
        currentPositionOffset += OIntegerSerializer.INT_SIZE;
      }
    }

    freePointer = freePointer - key.length + keySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE, freePointer);

    freePointer += setIntValue(freePointer, leftChildIndex);
    freePointer += setIntValue(freePointer, rightChildIndex);

    freePointer += setShortValue(freePointer, (short) key.length);
    setBinaryValue(freePointer, key);

    return true;
  }

  public void removeNonLeafEntry(final int entryIndex, boolean removeLeftChildPointer) {
    if (isLeaf()) {
      throw new IllegalStateException("Remove is applied to non-leaf buckets only");
    }

    final int entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE);
    final int keySize = getShortValue(entryPosition + 2 * OIntegerSerializer.INT_SIZE);

    removeNonLeafEntry(entryIndex, keySize, removeLeftChildPointer);
  }

  public void removeNonLeafEntry(
      final int entryIndex, final int keySize, boolean removeLeftChildPointer) {
    if (isLeaf()) {
      throw new IllegalStateException("Remove is applied to non-leaf buckets only");
    }

    final int entryPosition =
        getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE);
    final int entrySize = keySize + 2 * OIntegerSerializer.INT_SIZE + OShortSerializer.SHORT_SIZE;
    int size = getIntValue(SIZE_OFFSET);

    final int leftChild = getIntValue(entryPosition);
    final int rightChild = getIntValue(entryPosition + OIntegerSerializer.INT_SIZE);

    if (entryIndex < size - 1) {
      moveData(
          POSITIONS_ARRAY_OFFSET + (entryIndex + 1) * OIntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE,
          (size - entryIndex - 1) * OIntegerSerializer.INT_SIZE);
    }

    size--;
    setIntValue(SIZE_OFFSET, size);

    final int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (size > 0 && entryPosition > freePointer) {
      moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);

      int currentPositionOffset = POSITIONS_ARRAY_OFFSET;

      for (int i = 0; i < size; i++) {
        final int currentEntryPosition = getIntValue(currentPositionOffset);
        if (currentEntryPosition < entryPosition) {
          setIntValue(currentPositionOffset, currentEntryPosition + entrySize);
        }
        currentPositionOffset += OIntegerSerializer.INT_SIZE;
      }
    }

    setIntValue(FREE_POINTER_OFFSET, freePointer + entrySize);

    if (size > 0) {
      final int childPointer = removeLeftChildPointer ? rightChild : leftChild;

      if (entryIndex > 0) {
        final int prevEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + (entryIndex - 1) * OIntegerSerializer.INT_SIZE);
        setIntValue(prevEntryPosition + OIntegerSerializer.INT_SIZE, childPointer);
      }
      if (entryIndex < size) {
        final int nextEntryPosition =
            getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE);
        setIntValue(nextEntryPosition, childPointer);
      }
    }
  }

  public Entry getEntry(final int entryIndex) {
    int entryPosition =
        getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (isLeaf()) {
      final int keySize = getShortValue(entryPosition);
      final byte[] key = getBinaryValue(entryPosition + OShortSerializer.SHORT_SIZE, keySize);

      entryPosition += keySize + OShortSerializer.SHORT_SIZE;

      final int clusterId = getShortValue(entryPosition);
      final long clusterPosition = getLongValue(entryPosition + OShortSerializer.SHORT_SIZE);

      return new Entry(-1, -1, key, new ORecordId(clusterId, clusterPosition));
    } else {
      final int leftChild = getIntValue(entryPosition);
      entryPosition += OIntegerSerializer.INT_SIZE;

      final int rightChild = getIntValue(entryPosition);
      entryPosition += OIntegerSerializer.INT_SIZE;

      final int keySize = getShortValue(entryPosition);
      final byte[] key = getBinaryValue(entryPosition + OShortSerializer.SHORT_SIZE, keySize);

      return new Entry(leftChild, rightChild, key, null);
    }
  }

  public void setLeftSibling(final long pageIndex) {
    setLongValue(LEFT_SIBLING_OFFSET, pageIndex);
  }

  public long getLeftSibling() {
    return getLongValue(LEFT_SIBLING_OFFSET);
  }

  public void setRightSibling(final long pageIndex) {
    setLongValue(RIGHT_SIBLING_OFFSET, pageIndex);
  }

  public int getNextFreeListPage() {
    return getIntValue(NEXT_FREE_LIST_PAGE_OFFSET);
  }

  public void setNextFreeListPage(int nextFreeListPage) {
    setIntValue(NEXT_FREE_LIST_PAGE_OFFSET, nextFreeListPage);
  }

  public long getRightSibling() {
    return getLongValue(RIGHT_SIBLING_OFFSET);
  }

  static final class Entry implements Comparable<Entry> {
    private final Comparator<byte[]> COMPARATOR =
        OComparatorFactory.INSTANCE.getComparator(byte[].class);

    protected final int leftChild;
    protected final int rightChild;
    public final byte[] key;
    public final ORID value;

    public Entry(final int leftChild, final int rightChild, final byte[] key, final ORID value) {
      this.leftChild = leftChild;
      this.rightChild = rightChild;
      this.key = key;
      this.value = value;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final Entry that = (Entry) o;
      return leftChild == that.leftChild
          && rightChild == that.rightChild
          && Arrays.equals(key, that.key)
          && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(leftChild, rightChild, key, value);
    }

    @Override
    public String toString() {
      return "CellBTreeEntry{"
          + "leftChild="
          + leftChild
          + ", rightChild="
          + rightChild
          + ", key="
          + Arrays.toString(key)
          + ", value="
          + value
          + '}';
    }

    @Override
    public int compareTo(final Entry other) {
      return COMPARATOR.compare(key, other.key);
    }
  }
}
