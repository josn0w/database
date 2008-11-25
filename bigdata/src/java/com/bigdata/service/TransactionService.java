/**

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Mar 15, 2007
 */

package com.bigdata.service;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;

import com.bigdata.btree.IndexSegment;
import com.bigdata.counters.CounterSet;
import com.bigdata.counters.Instrument;
import com.bigdata.isolation.IsolatedFusedView;
import com.bigdata.journal.AbstractJournal;
import com.bigdata.journal.ITransactionManager;
import com.bigdata.journal.ITx;
import com.bigdata.journal.IsolationEnum;
import com.bigdata.journal.Journal;
import com.bigdata.journal.RunState;
import com.bigdata.journal.Tx;
import com.bigdata.journal.ValidationError;
import com.bigdata.util.MillisecondTimestampFactory;
import com.bigdata.util.concurrent.DaemonThreadFactory;

/**
 * Centalized transaction manager service. In response to a client request, the
 * transaction manager will distribute prepare/commit or abort operations to all
 * data services on which writes were made by a transaction. A transaction
 * manager is required iff transactions will be used. If only unisolated
 * operations will be performed on the bigdata federation, then each
 * {@link DataService} MAY use its own local timestamp service to generate
 * commit times.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * 
 * @see OldTransactionServer, which has lots of code and notes that bear on this
 *      implementation.
 * 
 * @todo Track which {@link IndexSegment}s and {@link Journal}s are required
 *       to support the {@link IsolatedFusedView}s in use by a {@link Tx}.
 *       Deletes of old journals and index segments MUST be deferred until no
 *       transaction remains which can read those data. This metadata must be
 *       restart-safe so that resources are eventually deleted.
 * 
 * @todo support a shutdown protocol. The transaction manager is notified that
 *       the federation will shutdown. At that point the transaction manager
 *       should refuse to start new transactions. data services should quiese as
 *       transactions complete. after a timeout, a shutdown notice should be
 *       broadcast to the data services (and the metadata service). when the
 *       transaction manager itself shuts down it must save the last assigned
 *       transaction commit time so that it can verify that time does not go
 *       backwards on restart.
 * 
 * @todo heartbeat message indicating which transactions are committing and in
 *       what order and integration with group commit in the {@link DataService}.
 * 
 * @todo on re-start, verify that the system clock will produce timestamps that
 *       are greater than the last recorded timestamp? Note that
 *       {@link AbstractJournal#commit(long)} already protects against this
 *       problem.
 * 
 * @todo Refactor to realize the server/service divide by implementing the
 *       TransactionServer class.
 * 
 * @todo robust/failover discoverable service. the service instances will need
 *       to track active/committed transactions, complain if their clocks get
 *       out of alignment, and refuse to generate a timestamp that would go
 *       backwards when compared to the timestamp generated by the last master
 *       service.
 * 
 * @todo since data services can fail and start elsewhere, what we need to
 *       locate the data service is really the index name and key range (or
 *       perhaps the partition identifier) for the write. Based on that, we can
 *       always locate the correct data service instance. The
 *       {@link InetSocketAddress} is useful if we can invalidate it if the data
 *       service instance fails.
 * 
 * @todo test for transactions that have already been completed? that would
 *       represent a protocol error. we could maintain an LRU cache of completed
 *       transactions for this purpose.
 * 
 * @todo In order to release the resources associated with a commit point
 *       (historical journals and index segments) we need a protocol by which a
 *       delegate index view is explicitly closed (or collected using a weak
 *       value cache) once it is no longer in use for an operation. The index
 *       views need to be accumulated on a commit point (aka commit record).
 *       When no index views for a given commit record are active, the commit
 *       point is no longer accessible to the read-committed transaction and
 *       should be released. Resources (journals and index segments) required to
 *       present views on that commit point MAY be released once there are no
 *       longer any fully isolated transactions whose start time would select
 *       that commit point as their ground state.
 * @todo does the api need to be synchronized?
 * 
 * @todo track ground states so that we known when we can release old journals
 *       and index segments?
 * 
 * @todo the transactional model might include a counter for the #of clients
 *       that have started work on a transaction in order to support distributed
 *       start/commit protocols. if clients use a workflow model, then they
 *       could pass the responsibility for the counter along with the
 *       transaction identifier rather than decrementing the counter themselves.
 *       It might be good to be able to identify which clients are still working
 *       on a given transaction.
 */
abstract public class TransactionService extends TimestampService implements
        ITransactionManager, IServiceShutdown {

    /**
     * Logger.
     */
    public static final Logger log = Logger.getLogger(TransactionService.class);

    /**
     * A hash map containing all active transactions. A transaction that is
     * preparing will remain in this collection until it has completed (aborted
     * or committed).
     * 
     * @todo parameterize the capacity of the map.
     */
    final Map<Long, TxMetadata> activeTx = new ConcurrentHashMap<Long, TxMetadata>(
            10000);

    /**
     * A thread that serializes transaction commits.
     */
    final protected ExecutorService commitService = Executors
            .newSingleThreadExecutor(new DaemonThreadFactory
                    (getClass().getName()+".commitService"));

    public TransactionService(Properties properties) {
        
        super(properties);
        
    }
    
    public boolean isOpen() {
        
        return ! commitService.isShutdown();
        
    }
    
    /**
     * Polite shutdown does not accept new requests and will shutdown once
     * the existing requests have been processed.
     */
    synchronized public void shutdown() {
        
        commitService.shutdown();
        
    }
    
    /**
     * Shutdown attempts to abort in-progress requests and shutdown as soon
     * as possible.
     */
    synchronized public void shutdownNow() {

        commitService.shutdownNow();
        
    }
    
    /*
     * ITransactionManager.
     */

    public long nextTimestamp() {
        
        /*
         * Wait for the next distinct millisecond.
         */
        return MillisecondTimestampFactory.nextMillis();
        
    }
    
    public long newTx(IsolationEnum level) {
        
        final long startTime = nextTimestamp();
        
        activeTx.put(startTime,new TxMetadata(level,startTime));
        
        return startTime;
        
    }

    /**
     * Abort the transaction (asynchronous).
     */
    public void abort(long startTime) {

        TxMetadata tx = activeTx.get(startTime);
        
        if (tx == null)
            throw new IllegalStateException("Unknown: " + startTime);

        if(!tx.isActive()) {
            throw new IllegalStateException("Not active: " + startTime);
        }

        // Note: do not wait for the task to run.
        commitService.submit(new AbortTask(tx));
        
    }

    /**
     * Commit the transaction (synchronous).
     * <p>
     * If a transaction has a write set, then this method does not return until
     * that write set has been made restart safe or the transaction has failed.
     */
    public long commit(final long startTime) throws ValidationError {
        
        final TxMetadata tx = activeTx.get(startTime);
        
        if (tx == null) {

            throw new IllegalStateException("Unknown: " + startTime);
            
        }

        if(!tx.isActive()) {

            throw new IllegalStateException("Not active: " + startTime);
            
        }

        final long commitTime = nextTimestamp();

        if(tx.isEmptyWriteSet()) {
            
            tx.runState = RunState.Committed;
         
            tx.commitTime = commitTime;
            
            activeTx.remove(startTime);
            
        }
        
        try {

            if (tx.isDistributed()) {

                // wait for the commit.
                commitService.submit(new DistributedCommitTask(tx)).get();

            } else {

                // wait for the commit
                commitService.submit(new SimpleCommitTask(tx)).get();
                
            }

        } catch (InterruptedException ex) {

            // interrupted, perhaps during shutdown.
            throw new RuntimeException(ex);

        } catch (ExecutionException ex) {

            Throwable cause = ex.getCause();

            if (cause instanceof ValidationError) {

                throw (ValidationError) cause;

            }

            // this is an unexpected error.
            throw new RuntimeException(cause);

        }

        return commitTime;

    }

    /**
     * Notify the journal that a new transaction is being activated on a data
     * service instance (starting to write on that data service).
     * 
     * @param tx
     *            The transaction identifier (aka start time).
     * 
     * @param locator
     *            The locator for the data service instance on which the
     *            transaction has begun writing.
     * 
     * @return true if the operation was successful. false if the transaction is
     *         not currently active (e.g., preparing, committed, or unknown).
     */
    public boolean activateTx(long tx, InetSocketAddress locator)
            throws IllegalStateException {

        Long timestamp = tx;

        TxMetadata md = activeTx.get(timestamp);
        
        if(md == null) {

            log.warn("Unknown: tx="+tx);

            return false;
            
        }
        
        if(!md.isActive()) {
            
            log.warn("Not active: tx="+tx);

            return false;
                        
        }

        md.addDataService(locator);
        
        return true;

    }

    /**
     * Metadata for the transaction state.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public static class TxMetadata {
 
        public final IsolationEnum level;
        public final long startTime;
        public final boolean readOnly;
        private final int hashCode;
        private RunState runState = RunState.Active;
        private long commitTime = 0L;
        private Set<InetSocketAddress> writtenOn = new HashSet<InetSocketAddress>();
        
        public TxMetadata(IsolationEnum level, long startTime) {
            
            assert startTime != 0L;
            assert level != null;
            
            this.startTime = startTime;
            
            this.level = level;
            
            this.readOnly = level != IsolationEnum.ReadWrite;
            
            // pre-compute the hash code for the transaction.
            this.hashCode = Long.valueOf(startTime).hashCode();

        }

        /**
         * The hash code is based on the {@link #getStartTimestamp()}.
         */
        final public int hashCode() {
            
            return hashCode;
            
        }

        /**
         * True iff they are the same object or have the same start timestamp.
         * 
         * @param o
         *            Another transaction object.
         */
        final public boolean equals(ITx o) {
            
            return this == o || startTime == o.getStartTimestamp();
            
        }

        /**
         * Declares a data service instance on which the transaction will write.
         * 
         * @param locator
         *            The locator for the data service instance.
         */
        final public void addDataService(InetSocketAddress locator) {
            
            synchronized(writtenOn) {
                
                writtenOn.add(locator);
                
            }
            
        }

        final boolean isEmptyWriteSet() {
                        
            synchronized(writtenOn) {

                return writtenOn.isEmpty();
                
            }
            
        }
        
        final boolean isDistributed() {
            
            synchronized(writtenOn) {

                return writtenOn.size() > 1;
                
            }
            
        }
        
        /**
         * Returns a string representation of the transaction start time.
         */
        final public String toString() {
            
            return ""+startTime;
            
        }

        final public IsolationEnum getIsolationLevel() {
            
            return level;
            
        }
        
        final public boolean isReadOnly() {
            
            return readOnly;
            
        }
        
        final public boolean isActive() {
            
            return runState == RunState.Active;
            
        }
        
        final public boolean isPrepared() {
            
            return runState == RunState.Prepared;
            
        }
        
        final public boolean isComplete() {
            
            return runState == RunState.Committed || runState == RunState.Aborted;
            
        }

        final public boolean isCommitted() {
            
            return runState == RunState.Committed;
            
        }
     
        final public boolean isAborted() {
            
            return runState == RunState.Aborted;
            
        }
        
    }

    /*
     * TODO read-only transactions and read-write transactions that have not
     * declared any write sets should be handled exactly like abort.
     * 
     * read-write transactions that have written on a single journal can do
     * a simple (one phase) commit.
     * 
     * read-write transactions that have written on multiple journals must
     * use a 2-/3-phase commit protocol.  Once again, latency is critical
     * in multi-phase commits since the journals will be unable to perform
     * unisolated writes until the transaction either commits or aborts.
     */
    public static class SimpleCommitTask implements Callable<Object> {

        public SimpleCommitTask(TxMetadata tx) {
            
        }
        
        public Object call() throws Exception {

            // TODO implement single point commit protocol.
            throw new UnsupportedOperationException();

        }
        
    }
    
    /*
     * @todo 2-/3-phase commit.
     */
    public static class DistributedCommitTask implements Callable<Object> {

        public DistributedCommitTask(TxMetadata tx) {
            
        }

        public Object call() throws Exception {

            // TODO implement distributed commit protocol.
            throw new UnsupportedOperationException();

        }
        
    }
    
    /*
     * TODO read-only transactions can abort immediately since they do not
     * need to notify the journals synchronously (they only need to notify
     * them in order to guide resource reclaimation, which only applies for
     * a read-only tx).
     * 
     * fully isolated transactions can also abort immediately and simply
     * notify the journals asynchronously that they should abort that tx.
     * 
     * Note: fast notification is required in case the tx has already
     * prepared since the journal will be unable to process unisolated
     * writes until the tx either aborts or commits.
     * 
     * @todo who is responsible for changing the runState?  Are multiple
     * aborts silently ignored (presuming multiple clients)?
     */
    public static class AbortTask implements Callable<Object> {
        
        public AbortTask(TxMetadata tx) {
            
        }

        public Object call() throws Exception {

            // TODO implement abort.
            throw new UnsupportedOperationException();
            
        }
        
    }

    public void wroteOn(long startTime, String[] resource) {
        // TODO Auto-generated method stub
        
    }

    /**
     * Return the {@link CounterSet}.
     */
    synchronized public CounterSet getCounters() {
        
        if (countersRoot == null) {

            countersRoot = new CounterSet();

            countersRoot.addCounter("#active", new Instrument<Integer>() {
                protected void sample() {
                    setValue(activeTx.size());
                }
            });

//            countersRoot.addCounter("#prepared", new Instrument<Integer>() {
//                protected void sample() {
//                    setValue(preparedTx.size());
//                }
//            });

        }
        
        return countersRoot;
        
    }
    private CounterSet countersRoot;

}
