package it.nextworks.nephele.OFAAService;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import it.nextworks.nephele.OFTranslator.Inventory;
import it.nextworks.nephele.appaffdb.DbManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Component
public class Processor {

    private Logger log = LoggerFactory.getLogger(this.getClass());

    @Value("${OpendaylightURL}")
    private String ODLURL;

    @Value("${OfflineEngineURL}")
    private String OEURL;

    @Value("${server.port}")
    private String serverPort;

    @Value("${concurrentRequests}")
    private int concurrency;

    @Autowired
    private DbManager db;

    private Semaphore computeSemaphore;
    private Semaphore invLock;

    private AtomicBoolean waiting = new AtomicBoolean(false);

    private ProcessingTasksTemplates templates;

    private BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

    private final Set<Service> scheduled = new HashSet<>();

    private final Set<Service> establishing = new HashSet<>();

    private final Set<Service> terminating = new HashSet<>();

    private ExecutorService executor;

    {
        executor = new ThreadPoolExecutor(8, 8, 1, TimeUnit.SECONDS, tasks);
        ((ThreadPoolExecutor) executor).prestartAllCoreThreads();
    }

    @PostConstruct
    public void templatesInit() {
        computeSemaphore = new Semaphore(concurrency);
        invLock = new Semaphore(1);

        templates = new ProcessingTasksTemplates(serverPort);
        log.debug("Processor concurrency level: {}.", computeSemaphore.availablePermits());
    }

    @PreDestroy
    public void cleanup() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exc) {
            try {
                executor.shutdownNow();
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.error("Couldn't shutdown executor, got interrupted by exception ", e);
            }
        }
    }

    private volatile boolean waitingForOfflineEngine = false;

    private volatile boolean isUpdateQueued = false;

    public Processor() {
    }

    void addTerminating(Service service) {
        terminating.add(service);
        db.updateStatus(service, ServiceStatus.TERMINATING);
    }

    void startRefreshing(Service serv) {
        scheduled.add(serv);
        startRefreshing();
    }

    void startRefreshing() {
        if (!computeSemaphore.tryAcquire()) {
            log.trace("Hit concurrency cap. Delaying execution.");
            scheduleRefresh();
        } else {
            TrafficMatGetter task = new TrafficMatGetter();
            tasks.add(task);
            log.debug("Starting Traffic matrix computation: OpId {}.", task.id);
        }
    }

    private void scheduleRefresh() {
        if (waiting.compareAndSet(false, true)) {
            tasks.add(new WaitForSchedule());
        }
        // else, there is already someone waiting, it will get our path installed.
    }

    private void callbackScheduled() {
        for (Service service : scheduled) {
            service.status = ServiceStatus.ESTABLISHING;
            establishing.add(service);
            log.debug("computed service: {}.", service.getId());
            db.updateStatus(service, ServiceStatus.ESTABLISHING);
        }
        scheduled.clear();
    }

    private void callbackEstablished() {
        for (Service service : establishing) {
            service.status = ServiceStatus.ACTIVE;
            log.debug("Established service: {}.", service.getId());
            db.updateStatus(service, ServiceStatus.ACTIVE);
        }

        establishing.clear();
    }

    private void callbackTerminated() {
        for (Service service : terminating) {
            service.status = ServiceStatus.DELETED;
            log.debug("Terminated service: {}.", service.getId());
            db.updateStatus(service, ServiceStatus.DELETED);
        }
        terminating.clear();
    }

    private class TrafficMatGetter extends FutureTask<int[][]> {
        protected UUID id;

        private TrafficMatGetter() {
            super(templates.new TrafficMatGetter());
            id = UUID.randomUUID(); 
        }

        @Override
        protected void done() {
            super.done();
            log.debug("Got traffic matrix. OpId: {}.", this.id);
            try {
                int[][] matrix = this.get();
                NetAllocIdGetter task = new NetAllocIdGetter(matrix);
                tasks.add(task);
                log.debug("Posting traffic matrix. OpId: {}.", task.id);
            } catch (InterruptedException | CancellationException intExc) {
                log.error("Computation interrupted:\n", intExc);
            } catch (ExecutionException execExc) {
                log.error("Error while GETting the traffic matrix:\n", execExc);
            }
        }
    }

    private class NetAllocIdGetter extends FutureTask<String> {
        private UUID id;

        private NetAllocIdGetter(int[][] matrix) {
            super(templates.new NetAllocGetter(matrix, OEURL));
            id = UUID.randomUUID();
        }

        @Override
        protected void done() {
            super.done();
            try {
                String netAllocId = this.get();
                if (!waitingForOfflineEngine) {
                    waitingForOfflineEngine = true;
                    AllocationMatrixGetter task = new AllocationMatrixGetter(netAllocId, this.id);
                    //So that there is only one GET to the offline engine on the queue
                    tasks.add(task);
                    log.debug("Getting network allocation with ID : " + netAllocId);
                } else isUpdateQueued = true;
            } catch (InterruptedException | CancellationException intExc) {
                log.error("Computation interrupted:\n", intExc);
            } catch (ExecutionException execExc) {
                log.error("Error while GETting the net alloc ID:\n", execExc);
            }
        }
    }

    private class InventoryGetter extends FutureTask<Inventory> {
        private UUID id;

        private InventoryGetter(int[][] matrix) {
            super(templates.new InventoryGetter(matrix));
            id = UUID.randomUUID();
        }

        @Override
        protected void done() {
            super.done();
            log.debug("Got inventory. OpId: {}.", this.id);
            try {
                Inventory inventory = this.get();
                InventoryPutter task = new InventoryPutter(inventory, ODLURL);
                tasks.add(task);
                log.debug("Sending inventory. OpId: {}.", task.id);
            } catch (InterruptedException | CancellationException intExc) {
                log.error("Computation interrupted:\n", intExc);
            } catch (ExecutionException execExc) {
                log.error("Error while translating the inventory:\n", execExc);
            }
        }
    }

    private class AllocationMatrixGetter extends FutureTask<NetSolOutput> {
        private UUID id;


        String netAllocId;

        Instant start = Instant.now();

        private AllocationMatrixGetter(String netAllocId, UUID opId) {
            super(templates.new NetAllocationMatrixGetter(netAllocId, OEURL));
            this.netAllocId = netAllocId;
            id = opId;
        }

        @Override
        protected void done() {
            super.done();
            try {
                NetSolOutput netSol = this.get();
                if (netSol != null) { //Calculation completed
                    log.debug("Got network allocation. OpId: {}.", this.id);
                    callbackScheduled();
                    invLock.acquire();
                    log.debug("Releasing computation permit.");
                    computeSemaphore.release();
                    int[][] matrix = netSol.matrix;
                    InventoryGetter task = new InventoryGetter(matrix);
                    tasks.add(task);
                    log.debug("Translating inventory. OpId: {}.", task.id);
                    waitingForOfflineEngine = false;
                    if (isUpdateQueued) {
                        tasks.add(new TrafficMatGetter());
                        isUpdateQueued = false;
                    }
                } else { //request solution again
                    long timeFromStart = Instant.now().toEpochMilli() - start.toEpochMilli();
                    if (timeFromStart < 900) {
                        //guarantees at least 0.9 seconds between retries, probably 1 sec.
                        Thread.sleep(1000 - timeFromStart);
                    }
                    tasks.add(new AllocationMatrixGetter(netAllocId, this.id));
                }
            } catch (InterruptedException | CancellationException intExc) {
                log.error("Computation interrupted:\n", intExc);
            } catch (ExecutionException execExc) {
                log.error("Error while GETting the net alloc matrix:\n", execExc);
            }
        }
    }

    private class InventoryPutter extends FutureTask<Boolean> {
        private UUID id;


        private InventoryPutter(Inventory inventory, String ODLURL) {
            super(templates.new InventoryPutter(inventory, ODLURL), true);
            id = UUID.randomUUID();
        }

        @Override
        protected void done() {
            super.done();
            log.debug("Inventory pushed. OpId: {}.", this.id);
            invLock.release();
            callbackEstablished();
            callbackTerminated();
            if (this.isDone()) {
                try {
                    log.debug("Inventory sent, status " + this.get().toString());
                    log.trace("Releasing inventory lock.");
                } catch (InterruptedException e) {// can't happen
                } catch (ExecutionException exc) {
                    log.warn("Sending inventory got exception: ", exc);
                }
            }
        }
    }

    private class WaitForSchedule extends TrafficMatGetter {

        @Override
        public void run() {
            log.debug("Waiting for a concurrency slot to free up. OpId: {}.", this.id);
            // Yeah, yeah, I know. But the only interruption is a shutdown, so...
            computeSemaphore.acquireUninterruptibly();
            waiting.set(false);
            log.trace("A slot freed up, resuming.");
            log.debug("Starting Traffic matrix computation: OpId {}.", this.id);
            super.run();
        }
    }
}
