/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.ipc.HBaseClientRPC;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.ipc.RemoteException;

import com.google.protobuf.ServiceException;

/**
 * Abstract class that implements {@link Callable}.  Implementation stipulates
 * return type and method we actually invoke on remote Server.  Usually
 * used inside a try/catch that fields usual connection failures all wrapped
 * up in a retry loop.
 * <p>Call {@link #connect(boolean)} to connect to server hosting region
 * that contains the passed row in the passed table before invoking
 * {@link #call()}.
 * @see HConnection#getRegionServerWithoutRetries(ServerCallable)
 * @param <T> the class that the ServerCallable handles
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public abstract class ServerCallable<T> implements Callable<T> {
  protected final HConnection connection;
  protected final byte [] tableName;
  protected final byte [] row;
  protected HRegionLocation location;
  protected ClientProtocol server;
  protected int callTimeout;
  protected long globalStartTime;
  protected long startTime, endTime;
  protected final static int MIN_RPC_TIMEOUT = 2000;

  /**
   * @param connection Connection to use.
   * @param tableName Table name to which <code>row</code> belongs.
   * @param row The row we want in <code>tableName</code>.
   */
  public ServerCallable(HConnection connection, byte [] tableName, byte [] row) {
    this(connection, tableName, row, HConstants.DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT);
  }

  public ServerCallable(HConnection connection, byte [] tableName, byte [] row, int callTimeout) {
    this.connection = connection;
    this.tableName = tableName;
    this.row = row;
    this.callTimeout = callTimeout;
  }

  /**
   * Connect to the server hosting region with row from tablename.
   * @param reload Set this to true if connection should re-find the region
   * @throws IOException e
   */
  public void connect(final boolean reload) throws IOException {
    this.location = connection.getRegionLocation(tableName, row, reload);
    this.server = connection.getClient(location.getServerName());
  }

  /** @return the server name
   * @deprecated Just use {@link #toString()} instead.
   */
  public String getServerName() {
    if (location == null) return null;
    return location.getHostnamePort();
  }

  /** @return the region name
   * @deprecated Just use {@link #toString()} instead.
   */
  public byte[] getRegionName() {
    if (location == null) return null;
    return location.getRegionInfo().getRegionName();
  }

  /** @return the row
   * @deprecated Just use {@link #toString()} instead.
   */
  public byte [] getRow() {
    return row;
  }

  public void beforeCall() {
    this.startTime = EnvironmentEdgeManager.currentTimeMillis();
    int remaining = (int)(callTimeout - (this.startTime - this.globalStartTime));
    if (remaining < MIN_RPC_TIMEOUT) {
      // If there is no time left, we're trying anyway. It's too late.
      // 0 means no timeout, and it's not the intent here. So we secure both cases by
      // resetting to the minimum.
      remaining = MIN_RPC_TIMEOUT;
    }
    HBaseClientRPC.setRpcTimeout(remaining);
  }

  public void afterCall() {
    HBaseClientRPC.resetRpcTimeout();
    this.endTime = EnvironmentEdgeManager.currentTimeMillis();
  }

  /**
   * @return {@link HConnection} instance used by this Callable.
   */
  HConnection getConnection() {
    return this.connection;
  }

  /**
   * Run this instance with retries, timed waits,
   * and refinds of missing regions.
   *
   * @return an object of type T
   * @throws IOException if a remote or network exception occurs
   * @throws RuntimeException other unspecified error
   */
  public T withRetries()
  throws IOException, RuntimeException {
    Configuration c = getConnection().getConfiguration();
    final long pause = c.getLong(HConstants.HBASE_CLIENT_PAUSE,
      HConstants.DEFAULT_HBASE_CLIENT_PAUSE);
    final int numRetries = c.getInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER,
      HConstants.DEFAULT_HBASE_CLIENT_RETRIES_NUMBER);
    List<RetriesExhaustedException.ThrowableWithExtraContext> exceptions =
      new ArrayList<RetriesExhaustedException.ThrowableWithExtraContext>();
    this.globalStartTime = EnvironmentEdgeManager.currentTimeMillis();
    for (int tries = 0; tries < numRetries; tries++) {
      try {
        beforeCall();
        connect(tries != 0);
        return call();
      } catch (Throwable t) {
        t = translateException(t);
        if (t instanceof SocketTimeoutException ||
            t instanceof ConnectException ||
            t instanceof RetriesExhaustedException) {
          // if thrown these exceptions, we clear all the cache entries that
          // map to that slow/dead server; otherwise, let cache miss and ask
          // .META. again to find the new location
          HRegionLocation hrl = location;
          if (hrl != null) {
            getConnection().clearCaches(hrl.getHostnamePort());
          }
        }
        RetriesExhaustedException.ThrowableWithExtraContext qt =
          new RetriesExhaustedException.ThrowableWithExtraContext(t,
              EnvironmentEdgeManager.currentTimeMillis(), toString());
        exceptions.add(qt);
        if (tries == numRetries - 1) {
          throw new RetriesExhaustedException(tries, exceptions);
        }
        long expectedSleep = ConnectionUtils.getPauseTime(pause, tries);
        // If, after the planned sleep, there won't be enough time left, we stop now.
        if (((this.endTime - this.globalStartTime) + MIN_RPC_TIMEOUT + expectedSleep) >
            this.callTimeout) {
          throw (SocketTimeoutException) new SocketTimeoutException(
              "Call to access row '" + Bytes.toString(row) + "' on table '"
                  + Bytes.toString(tableName)
                  + "' failed on timeout. " + " callTimeout=" + this.callTimeout +
                  ", time=" + (this.endTime - this.startTime)).initCause(t);
        }
      } finally {
        afterCall();
      }
      try {
        Thread.sleep(ConnectionUtils.getPauseTime(pause, tries));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted after tries=" + tries, e);
      }
    }
    return null;
  }

  /**
   * Run this instance against the server once.
   * @return an object of type T
   * @throws IOException if a remote or network exception occurs
   * @throws RuntimeException other unspecified error
   */
  public T withoutRetries()
  throws IOException, RuntimeException {
    this.globalStartTime = EnvironmentEdgeManager.currentTimeMillis();
    try {
      beforeCall();
      connect(false);
      return call();
    } catch (Throwable t) {
      Throwable t2 = translateException(t);
      if (t2 instanceof IOException) {
        throw (IOException)t2;
      } else {
        throw new RuntimeException(t2);
      }
    } finally {
      afterCall();
    }
  }

  protected static Throwable translateException(Throwable t) throws IOException {
    if (t instanceof UndeclaredThrowableException) {
      t = t.getCause();
    }
    if (t instanceof RemoteException) {
      t = ((RemoteException)t).unwrapRemoteException();
    }
    if (t instanceof ServiceException) {
      ServiceException se = (ServiceException)t;
      Throwable cause = se.getCause();
      if (cause != null && cause instanceof DoNotRetryIOException) {
        throw (DoNotRetryIOException)cause;
      }
    } else if (t instanceof DoNotRetryIOException) {
      throw (DoNotRetryIOException)t;
    }
    return t;
  }
}
