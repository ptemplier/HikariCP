/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.proxy;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.pool.HikariPool;
import com.zaxxer.hikari.pool.PoolBagEntry;
import com.zaxxer.hikari.util.FastList;
import com.zaxxer.hikari.util.LeakTask;

/**
 * This is the proxy class for java.sql.Connection.
 *
 * @author Brett Wooldridge
 */
public abstract class ConnectionProxy implements IHikariConnectionProxy
{
   private static final Logger LOGGER;
   private static final Set<String> SQL_ERRORS;

   protected final Connection delegate;

   private final HikariPool parentPool;
   private final PoolBagEntry bagEntry;
   private final LeakTask leakTask;
   private FastList<Statement> openStatements;
   
   private boolean forceClose;
   private boolean commitStateDirty;
   private boolean isAnythingDirty;
   private boolean isAutoCommitDirty;
   private boolean isCatalogDirty;
   private boolean isClosed;
   private boolean isReadOnlyDirty;
   private boolean isTransactionIsolationDirty;

   // static initializer
   static {
      LOGGER = LoggerFactory.getLogger(ConnectionProxy.class);

      SQL_ERRORS = new HashSet<String>();
      SQL_ERRORS.add("57P01"); // ADMIN SHUTDOWN
      SQL_ERRORS.add("57P02"); // CRASH SHUTDOWN
      SQL_ERRORS.add("57P03"); // CANNOT CONNECT NOW
      SQL_ERRORS.add("01002"); // SQL92 disconnect error
      SQL_ERRORS.add("JZ0C0"); // Sybase disconnect error
      SQL_ERRORS.add("JZ0C1"); // Sybase disconnect error
   }

   protected ConnectionProxy(final HikariPool pool, final PoolBagEntry bagEntry, final LeakTask leakTask) {
      this.parentPool = pool;
      this.bagEntry = bagEntry;
      this.delegate = bagEntry.connection;
      this.leakTask = leakTask;

      this.openStatements = new FastList<Statement>(Statement.class, 16);
   }

   @Override
   public String toString()
   {
      return String.format("%s(%s) wrapping %s", this.getClass().getSimpleName(), System.identityHashCode(this), delegate);
   }

   // ***********************************************************************
   //                      IHikariConnectionProxy methods
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public final PoolBagEntry getPoolBagEntry()
   {
      return bagEntry;
   }

   /** {@inheritDoc} */
   @Override
   public final SQLException checkException(SQLException sqle)
   {
      String sqlState = sqle.getSQLState();
      if (sqlState != null) {
         forceClose |= sqlState.startsWith("08") | SQL_ERRORS.contains(sqlState);
         if (forceClose) {
            LOGGER.warn(String.format("Connection %s (%s) marked as broken because of SQLSTATE(%s), ErrorCode(%d).", delegate.toString(),
                                      parentPool.toString(), sqlState, sqle.getErrorCode()), sqle);
         }
         else if (sqle.getNextException() instanceof SQLException) {
            checkException(sqle.getNextException());
         }
      }
      return sqle;
   }

   /** {@inheritDoc} */
   @Override
   public final void untrackStatement(Statement statement)
   {
      // If the connection is not closed.  If it is closed, it means this is being
      // called back as a result of the close() method below in which case we
      // will clear the openStatements collection en mass.
      if (!isClosed) {
         openStatements.remove(statement);
      }
   }

   /** {@inheritDoc} */
   @Override
   public final void markCommitStateDirty()
   {
      commitStateDirty = true;
   }

   // ***********************************************************************
   //                        Internal methods
   // ***********************************************************************

   protected final void checkClosed() throws SQLException
   {
      if (isClosed) {
         throw new SQLException("Connection is closed");
      }
   }

   private final <T extends Statement> T trackStatement(T statement)
   {
      openStatements.add(statement);

      return statement;
   }

   private final void resetConnectionState() throws SQLException
   {
      if (isReadOnlyDirty) {
         delegate.setReadOnly(parentPool.isReadOnly);
      }

      if (isAutoCommitDirty) {
         delegate.setAutoCommit(parentPool.isAutoCommit);
      }

      if (isTransactionIsolationDirty) {
         delegate.setTransactionIsolation(parentPool.transactionIsolation);
      }

      if (isCatalogDirty && parentPool.catalog != null) {
         delegate.setCatalog(parentPool.catalog);
      }
   }

   // **********************************************************************
   //                   "Overridden" java.sql.Connection Methods
   // **********************************************************************

   /** {@inheritDoc} */
   @Override
   public final void close() throws SQLException
   {
      if (!isClosed) {
         isClosed = true;

         leakTask.cancel();

         final int size = openStatements.size();
         if (size > 0) {
            for (int i = 0; i < size; i++) {
               try {
                  openStatements.get(i).close();
               }
               catch (SQLException e) {
                  checkException(e);
               }
            }
         }

         try {
            if (commitStateDirty && !delegate.getAutoCommit()) {
               delegate.rollback();
            }

            if (isAnythingDirty) {
               resetConnectionState();
            }

            delegate.clearWarnings();
         }
         catch (SQLException e) {
            throw checkException(e);
         }
         finally {
            parentPool.releaseConnection(bagEntry, forceClose);
         }
      }
   }

   /** {@inheritDoc} */
   @Override
   public final boolean isClosed() throws SQLException
   {
      return isClosed;
   }

   /** {@inheritDoc} */
   @Override
   public Statement createStatement() throws SQLException
   {
      Statement proxyStatement = ProxyFactory.getProxyStatement(this, delegate.createStatement());
      return trackStatement(proxyStatement);
   }

   /** {@inheritDoc} */
   @Override
   public Statement createStatement(int resultSetType, int concurrency) throws SQLException
   {
      Statement proxyStatement = ProxyFactory.getProxyStatement(this, delegate.createStatement(resultSetType, concurrency));
      return trackStatement(proxyStatement);
   }

   /** {@inheritDoc} */
   @Override
   public Statement createStatement(int resultSetType, int concurrency, int holdability) throws SQLException
   {
      Statement proxyStatement = ProxyFactory.getProxyStatement(this, delegate.createStatement(resultSetType, concurrency, holdability));
      return trackStatement(proxyStatement);
   }

   /** {@inheritDoc} */
   @Override
   public CallableStatement prepareCall(String sql) throws SQLException
   {
      CallableStatement pcs = ProxyFactory.getProxyCallableStatement(this, delegate.prepareCall(sql));
      return trackStatement(pcs);
   }

   /** {@inheritDoc} */
   @Override
   public CallableStatement prepareCall(String sql, int resultSetType, int concurrency) throws SQLException
   {
      CallableStatement pcs = ProxyFactory.getProxyCallableStatement(this, delegate.prepareCall(sql, resultSetType, concurrency));
      return trackStatement(pcs);
   }

   /** {@inheritDoc} */
   @Override
   public CallableStatement prepareCall(String sql, int resultSetType, int concurrency, int holdability) throws SQLException
   {
      CallableStatement pcs = ProxyFactory.getProxyCallableStatement(this, delegate.prepareCall(sql, resultSetType, concurrency, holdability));
      return trackStatement(pcs);
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql) throws SQLException
   {
      PreparedStatement proxyPreparedStatement = ProxyFactory.getProxyPreparedStatement(this, delegate.prepareStatement(sql));
      return trackStatement(proxyPreparedStatement);
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException
   {
      PreparedStatement proxyPreparedStatement = ProxyFactory.getProxyPreparedStatement(this, delegate.prepareStatement(sql, autoGeneratedKeys));
      return trackStatement(proxyPreparedStatement);
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, int resultSetType, int concurrency) throws SQLException
   {
      PreparedStatement proxyPreparedStatement = ProxyFactory.getProxyPreparedStatement(this, delegate.prepareStatement(sql, resultSetType, concurrency));
      return trackStatement(proxyPreparedStatement);
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, int resultSetType, int concurrency, int holdability) throws SQLException
   {
      PreparedStatement proxyPreparedStatement = ProxyFactory.getProxyPreparedStatement(this, delegate.prepareStatement(sql, resultSetType, concurrency, holdability));
      return trackStatement(proxyPreparedStatement);
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException
   {
      PreparedStatement proxyPreparedStatement = ProxyFactory.getProxyPreparedStatement(this, delegate.prepareStatement(sql, columnIndexes));
      return trackStatement(proxyPreparedStatement);
   }

   /** {@inheritDoc} */
   @Override
   public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException
   {
      PreparedStatement proxyPreparedStatement = ProxyFactory.getProxyPreparedStatement(this, delegate.prepareStatement(sql, columnNames));
      return trackStatement(proxyPreparedStatement);
   }

   /** {@inheritDoc} */
   @Override
   public void commit() throws SQLException
   {
      delegate.commit();
      commitStateDirty = false;
   }

   /** {@inheritDoc} */
   @Override
   public void rollback() throws SQLException
   {
      delegate.rollback();
      commitStateDirty = false;
   }

   /** {@inheritDoc} */
   @Override
   public void rollback(Savepoint savepoint) throws SQLException
   {
      delegate.rollback(savepoint);
      commitStateDirty = false;
   }

   /** {@inheritDoc} */
   @Override
   public final boolean isValid(int timeout) throws SQLException
   {
      if (isClosed) {
         return false;
      }

      try {
         return delegate.isValid(timeout);
      }
      catch (SQLException e) {
         throw checkException(e);
      }
   }

   /** {@inheritDoc} */
   @Override
   public void setAutoCommit(boolean autoCommit) throws SQLException
   {
      delegate.setAutoCommit(autoCommit);
      isAnythingDirty = true;
      isAutoCommitDirty = (autoCommit != parentPool.isAutoCommit);
   }

   /** {@inheritDoc} */
   @Override
   public void setReadOnly(boolean readOnly) throws SQLException
   {
      delegate.setReadOnly(readOnly);
      isAnythingDirty = true;
      isReadOnlyDirty = (readOnly != parentPool.isReadOnly);
   }

   /** {@inheritDoc} */
   @Override
   public void setTransactionIsolation(int level) throws SQLException
   {
      delegate.setTransactionIsolation(level);
      isAnythingDirty = true;
      isTransactionIsolationDirty = (level != parentPool.transactionIsolation);
   }

   /** {@inheritDoc} */
   @Override
   public void setCatalog(String catalog) throws SQLException
   {
      delegate.setCatalog(catalog);
      isAnythingDirty = true;
      isCatalogDirty = (catalog != null && !catalog.equals(parentPool.catalog)) || (catalog == null && parentPool.catalog != null);
   }

   /** {@inheritDoc} */
   @Override
   public final boolean isWrapperFor(Class<?> iface) throws SQLException
   {
      return iface.isInstance(delegate) || (delegate instanceof Wrapper && delegate.isWrapperFor(iface));
   }

   /** {@inheritDoc} */
   @Override
   @SuppressWarnings("unchecked")
   public final <T> T unwrap(Class<T> iface) throws SQLException
   {
      if (iface.isInstance(delegate)) {
         return (T) delegate;
      }
      else if (delegate instanceof Wrapper) {
          return (T) delegate.unwrap(iface);
      }

      throw new SQLException("Wrapped connection is not an instance of " + iface);
   }
}
