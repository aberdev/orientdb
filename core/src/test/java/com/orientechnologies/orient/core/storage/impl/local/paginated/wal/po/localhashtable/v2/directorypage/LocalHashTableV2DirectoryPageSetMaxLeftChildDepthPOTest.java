package com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.localhashtable.v2.directorypage;

import com.orientechnologies.common.directmemory.OByteBufferPool;
import com.orientechnologies.common.directmemory.ODirectMemoryAllocator.Intention;
import com.orientechnologies.common.directmemory.OPointer;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.cache.OCacheEntryImpl;
import com.orientechnologies.orient.core.storage.cache.OCachePointer;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.PageOperationRecord;
import com.orientechnologies.orient.core.storage.index.hashindex.local.v2.DirectoryPageV2;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPOTest {
  @Test
  public void testRedo() {
    final int pageSize = 64 * 1024;
    final OByteBufferPool byteBufferPool = new OByteBufferPool(pageSize);
    try {
      final OPointer pointer = byteBufferPool.acquireDirect(false, Intention.TEST);
      final OCachePointer cachePointer = new OCachePointer(pointer, byteBufferPool, 0, 0);
      final OCacheEntry entry = new OCacheEntryImpl(0, 0, cachePointer, false);

      DirectoryPageV2 page = new DirectoryPageV2(entry);
      page.setMaxLeftChildDepth(2, (byte) 24);

      entry.clearPageOperations();

      final OPointer restoredPointer = byteBufferPool.acquireDirect(false, Intention.TEST);
      final OCachePointer restoredCachePointer =
          new OCachePointer(restoredPointer, byteBufferPool, 0, 0);
      final OCacheEntry restoredCacheEntry = new OCacheEntryImpl(0, 0, restoredCachePointer, false);

      final ByteBuffer originalBuffer = cachePointer.getBufferDuplicate();
      final ByteBuffer restoredBuffer = restoredCachePointer.getBufferDuplicate();

      Assert.assertNotNull(originalBuffer);
      Assert.assertNotNull(restoredBuffer);

      restoredBuffer.put(originalBuffer);

      page.setMaxLeftChildDepth(2, (byte) 42);

      final List<PageOperationRecord> operations = entry.getPageOperations();
      Assert.assertEquals(1, operations.size());

      Assert.assertTrue(
          operations.get(0) instanceof LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO);

      final LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO pageOperation =
          (LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO) operations.get(0);

      DirectoryPageV2 restoredPage = new DirectoryPageV2(restoredCacheEntry);
      Assert.assertEquals(24, restoredPage.getMaxLeftChildDepth(2));

      pageOperation.redo(restoredCacheEntry);

      Assert.assertEquals(42, restoredPage.getMaxLeftChildDepth(2));

      byteBufferPool.release(pointer);
      byteBufferPool.release(restoredPointer);
    } finally {
      byteBufferPool.clear();
    }
  }

  @Test
  public void testUndo() {
    final int pageSize = 64 * 1024;

    final OByteBufferPool byteBufferPool = new OByteBufferPool(pageSize);
    try {
      final OPointer pointer = byteBufferPool.acquireDirect(false, Intention.TEST);
      final OCachePointer cachePointer = new OCachePointer(pointer, byteBufferPool, 0, 0);
      final OCacheEntry entry = new OCacheEntryImpl(0, 0, cachePointer, false);

      DirectoryPageV2 page = new DirectoryPageV2(entry);
      page.setMaxLeftChildDepth(2, (byte) 24);

      entry.clearPageOperations();

      page.setMaxLeftChildDepth(2, (byte) 42);

      final List<PageOperationRecord> operations = entry.getPageOperations();
      Assert.assertEquals(1, operations.size());

      Assert.assertTrue(
          operations.get(0) instanceof LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO);

      final LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO pageOperation =
          (LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO) operations.get(0);

      final DirectoryPageV2 restoredPage = new DirectoryPageV2(entry);

      Assert.assertEquals(42, restoredPage.getMaxLeftChildDepth(2));

      pageOperation.undo(entry);

      Assert.assertEquals(24, restoredPage.getMaxLeftChildDepth(2));

      byteBufferPool.release(pointer);
    } finally {
      byteBufferPool.clear();
    }
  }

  @Test
  public void testSerialization() {
    LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO operation =
        new LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO(2, (byte) 12, (byte) 21);

    operation.setFileId(42);
    operation.setPageIndex(24);
    operation.setOperationUnitId(1);

    final int serializedSize = operation.serializedSize();
    final byte[] stream = new byte[serializedSize + 1];
    int pos = operation.toStream(stream, 1);

    Assert.assertEquals(serializedSize + 1, pos);

    LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO restoredOperation =
        new LocalHashTableV2DirectoryPageSetMaxLeftChildDepthPO();
    restoredOperation.fromStream(stream, 1);

    Assert.assertEquals(42, restoredOperation.getFileId());
    Assert.assertEquals(24, restoredOperation.getPageIndex());
    Assert.assertEquals(1, restoredOperation.getOperationUnitId());

    Assert.assertEquals(2, restoredOperation.getLocalNodeIndex());
    Assert.assertEquals(12, restoredOperation.getMaxLeftChildDepth());
    Assert.assertEquals(21, restoredOperation.getPastMaxLeftChildDepth());
  }
}
