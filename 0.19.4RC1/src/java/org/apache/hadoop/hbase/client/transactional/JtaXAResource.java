package org.apache.hadoop.hbase.client.transactional;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.ipc.TransactionalRegionInterface;

/**
 * View hbase as a JTA transactional resource. This allows it to participate in
 * transactions across multiple resources.
 * 
 * 
 */
public class JtaXAResource implements XAResource {

  static final Log LOG = LogFactory.getLog(JtaXAResource.class);

  private Map<Xid, TransactionState> xidToTransactionState = new HashMap<Xid, TransactionState>();
  private final TransactionManager transactionManager;
  private ThreadLocal<TransactionState> threadLocalTransactionState = new ThreadLocal<TransactionState>();

  public JtaXAResource(TransactionManager transactionManager) {
    this.transactionManager = transactionManager;
  }

  public void commit(Xid xid, boolean onePhase) throws XAException {
    LOG.trace("commit [" + xid.toString() + "] "
        + (onePhase ? "one phase" : "two phase"));
    TransactionState state = xidToTransactionState.remove(xid);
    if (state == null) {
      throw new XAException(XAException.XAER_NOTA);
    }
    try {
      if (onePhase) {
        transactionManager.tryCommit(state);
      } else {
        transactionManager.doCommit(state);
      }
    } catch (CommitUnsuccessfulException e) {
      throw new XAException(XAException.XA_RBROLLBACK);
    } catch (IOException e) {
      throw new XAException(XAException.XA_RBPROTO); // FIXME correct code?
    } finally {
      threadLocalTransactionState.remove();
    }

  }

  public void end(Xid xid, int flags) throws XAException {
    LOG.trace("end [" + xid.toString() + "] ");
    threadLocalTransactionState.remove();
  }

  public void forget(Xid xid) throws XAException {
    LOG.trace("forget [" + xid.toString() + "] ");
    threadLocalTransactionState.remove();
    TransactionState state = xidToTransactionState.remove(xid);
    if (state != null) {
      try {
        transactionManager.abort(state);
      } catch (IOException e) {
        throw new RuntimeException(e); // FIXME, should be an XAException?
      }
    }
  }

  public int getTransactionTimeout() throws XAException {
    return 0;
  }

  public boolean isSameRM(XAResource xares) throws XAException {
    if (xares instanceof JtaXAResource) {
      return true;
    }
    return false;
  }

  public int prepare(Xid xid) throws XAException {
    LOG.trace("prepare [" + xid.toString() + "] ");
    TransactionState state = xidToTransactionState.get(xid);
    int status;
    try {
      status = this.transactionManager.prepareCommit(state);
    } catch (CommitUnsuccessfulException e) {
      throw new XAException(XAException.XA_HEURRB); // FIXME correct code?
    } catch (IOException e) {
      throw new XAException(XAException.XA_RBPROTO); // FIXME correct code?
    }

    switch (status) {
    case TransactionalRegionInterface.COMMIT_OK:
      return XAResource.XA_OK;
    case TransactionalRegionInterface.COMMIT_OK_READ_ONLY:
      return XAResource.XA_RDONLY;
    default:
      throw new XAException(XAException.XA_RBPROTO); // FIXME correct code?
    }
  }

  public Xid[] recover(int flag) throws XAException {
    return xidToTransactionState.keySet().toArray(new Xid[] {});
  }

  public void rollback(Xid xid) throws XAException {
    LOG.trace("rollback [" + xid.toString() + "] ");
    forget(xid);
    threadLocalTransactionState.remove();
  }

  public boolean setTransactionTimeout(int seconds) throws XAException {
    return false; // Currently not supported. (Only global lease time)
  }

  public void start(Xid xid, int flags) throws XAException {
    LOG.trace("start [" + xid.toString() + "] ");
    // TODO, check flags
    TransactionState state = this.transactionManager.beginTransaction();
    threadLocalTransactionState.set(state);
    xidToTransactionState.put(xid, state);
  }

  /**
   * @return the threadLocalTransaction state.
   */
  public TransactionState getThreadLocalTransactionState() {
    return threadLocalTransactionState.get();
  }

}