/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the
 * names of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.cache;

import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.collect.ImmutableMap;
import com.sleepycat.bind.tuple.TupleBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;

import java.io.File;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

import org.sirix.api.IPageWriteTrx;
import org.sirix.exception.SirixException;
import org.sirix.exception.SirixIOException;

/**
 * Berkeley implementation of a persistent cache. That means that all data is
 * stored in this cache and it is never removed. This is useful e.g. when it
 * comes to transaction logging.
 * 
 * @author Sebastian Graf, University of Konstanz
 * 
 */
public final class BerkeleyPersistenceCache extends
  AbsPersistenceCache<Long, PageContainer> {

  /**
   * Flush after defined value.
   */
  private static final long FLUSH_AFTER = 10_000;

  /**
   * Name for the database.
   */
  private static final String NAME = "berkeleyCache";

  /**
   * Berkeley database.
   */
  private final Database mDatabase;

  /**
   * Berkeley Environment for the database.
   */
  private final Environment mEnv;

  /**
   * Binding for the key, which is the nodepage.
   */
  private final TupleBinding<Long> mKeyBinding;

  /**
   * Binding for the value which is a page with related Nodes.
   */
  private final PageContainerBinding mValueBinding;

  /** Cache entries. */
  private long mEntries;

  /**
   * Constructor. Building up the berkeley db and setting necessary settings.
   * 
   * @param pPageWriteTrx
   *          page write transaction
   * @param pFile
   *          the place where the berkeley db is stored.
   * @param pRevision
   *          revision number, needed to reconstruct the sliding window in
   *          the correct way
   * @param pLogType
   *          type of log to append to the path of the log
   * @throws SirixException
   *           if a Sirix operation fails
   */
  public BerkeleyPersistenceCache(final @Nonnull IPageWriteTrx pPageWriteTrx, final @Nonnull File pFile,
    final @Nonnegative long pRevision, final @Nonnull String pLogType) throws SirixException {
    super(checkNotNull(pFile), pPageWriteTrx, pRevision, pLogType);
    try {
      // Create a new, transactional database environment.
      final EnvironmentConfig config = new EnvironmentConfig();
      config.setAllowCreate(true).setLocking(false).setCacheSize(1024 * 1024);
      mEnv = new Environment(mPlace, config);

      // Make a database within that environment.
      final DatabaseConfig dbConfig = new DatabaseConfig();
      dbConfig.setAllowCreate(true).setExclusiveCreate(false).setDeferredWrite(
        true);

      mDatabase = mEnv.openDatabase(null, NAME, dbConfig);

      mKeyBinding = TupleBinding.getPrimitiveBinding(Long.class);
      mValueBinding = new PageContainerBinding();
      mEntries = 0;
    } catch (final DatabaseException e) {
      throw new SirixIOException(e);
    }
  }

  @Override
  public void putPersistent(final @Nonnull Long pKey,
    final @Nonnull PageContainer pPage) throws SirixIOException {
    final DatabaseEntry valueEntry = new DatabaseEntry();
    final DatabaseEntry keyEntry = new DatabaseEntry();
    mEntries++;
    mKeyBinding.objectToEntry(checkNotNull(pKey), keyEntry);
    mValueBinding.objectToEntry(checkNotNull(pPage), valueEntry);
    try {
      mDatabase.put(null, keyEntry, valueEntry);
    } catch (final DatabaseException e) {
      throw new SirixIOException(e);
    }

    if (mEntries % FLUSH_AFTER == 0) {
      mDatabase.sync();
    }
  }

  @Override
  public void clearPersistent() throws SirixIOException {
    try {
      mDatabase.close();
      mEnv.removeDatabase(null, NAME);
      mEnv.close();
    } catch (final DatabaseException e) {
      throw new SirixIOException(e);
    }
  }

  @Override
  public PageContainer getPersistent(final @Nonnull Long pKey)
    throws SirixIOException {
    final DatabaseEntry valueEntry = new DatabaseEntry();
    final DatabaseEntry keyEntry = new DatabaseEntry();
    mKeyBinding.objectToEntry(checkNotNull(pKey), keyEntry);
    try {
      final OperationStatus status =
        mDatabase.get(null, keyEntry, valueEntry, LockMode.DEFAULT);
      PageContainer val = null;
      if (status == OperationStatus.SUCCESS) {
        val = mValueBinding.entryToObject(valueEntry);
      }
      return val;
    } catch (final DatabaseException e) {
      throw new SirixIOException(e);
    }
  }

  @Override
  public ImmutableMap<Long, PageContainer> getAll(
    final @Nonnull Iterable<? extends Long> keys) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putAll(final @Nonnull Map<Long, PageContainer> pMap) {
    for (final Entry<Long, PageContainer> entry : pMap.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void toSecondCache() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void remove(final @Nonnull Long pKey) {
    final DatabaseEntry keyEntry = new DatabaseEntry();
    mKeyBinding.objectToEntry(checkNotNull(pKey), keyEntry);
    final OperationStatus status = mDatabase.delete(null, keyEntry);
    if (status == OperationStatus.NOTFOUND) {
      throw new IllegalStateException();
    }
  }
}
