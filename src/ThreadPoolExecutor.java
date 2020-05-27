
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author lilei
 **/
@SuppressWarnings({"unused","SameParameterValue"})
public class ThreadPoolExecutor {
    private volatile int maxThreadSize;
    private volatile int coreThreadSize;
    private Set<Worker> workers = new CopyOnWriteArraySet<>();
    private BlockingQueue<Runnable> workerQueue = new LinkedBlockingQueue<>();
    private final ReentrantLock mainLock = new ReentrantLock();
    private AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

    private volatile boolean allowCoreThreadTimeOut = false;

    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int CAPACITY = (1 << COUNT_BITS) - 1;
    private static final int RUNNING = -1 << COUNT_BITS;
    private static final int SHUTDOWN = 0;
    private static final int STOP = 1 << COUNT_BITS;
    private static final int TIDYING    =  2 << COUNT_BITS;


    public ThreadPoolExecutor(int maxThreadSize, int coreThreadSize) {
        this.maxThreadSize = maxThreadSize;
        this.coreThreadSize = coreThreadSize;
    }

    //得到线程池状态
    private static int runStateOf(int c) {
        return c & ~CAPACITY;
    }

    //得到线程池中工作线程的数量
    private static int workerCountOf(int c) {
        return c & CAPACITY;
    }

    //返回一个代表状态和工作线程数量的整数标识
    private static int ctlOf(int rs, int wc) {
        return rs | wc;
    }


    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    private void decrementWorkerCount() {
        for (; ; ) {
            if (compareAndDecrementWorkerCount(ctl.get())) {
                break;
            }
        }
    }


    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException();
        boolean success;
        if (isRunning(ctl.get())) {
            if (workerCountOf(ctl.get()) >= coreThreadSize) {
                if (!workerQueue.offer(task)) {
                    success = addWorker(task, false);
                } else {
                    if (!isRunning(ctl.get()) && workerQueue.remove(task)) {
                        throw new ThreadPoolRejectException();
                    } else if (workerCountOf(ctl.get()) == 0) {
                        addWorker(null, false);
                    }
                    success = true;
                }
            } else {
                success = addWorker(task, true);
            }
            if (!success) {
                throw new ThreadPoolRejectException();
            }
        }
    }

    private boolean addWorker(Runnable task, boolean core) {
        for (; ; ) {
            //如果目前的线程不是运行态,或者不是shutdown且队列不为空的情况添加worker失败
            if (!isRunning(ctl.get()) && !((runStateOf(ctl.get()) == SHUTDOWN) && !workerQueue.isEmpty() && task == null)) {
                return false;
            }
            int c = ctl.get();
            int w = workerCountOf(c);
            if (core) {
                if (w >= coreThreadSize) {
                    return false;
                }
            } else {
                if (w >= maxThreadSize) {
                    return false;
                }
            }
            if (compareAndIncrementWorkerCount(c)) {
                break;
            }
        }

        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker worker = null;
        try {
            worker = new Worker(task);
            Thread t = worker.thread;
            if (t != null) {
                mainLock.lock();
                try {
                    int rs = runStateOf(ctl.get());
                    if (rs < SHUTDOWN ||
                            (rs == SHUTDOWN && task == null)) {
                        if (t.isAlive())
                            throw new IllegalThreadStateException();
                        workers.add(worker);
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }

                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (!workerStarted)
                addWorkerFailed(worker);
        }
        return workerStarted;
    }


    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                workers.remove(w);
            decrementWorkerCount();
        } finally {
            mainLock.unlock();
        }
    }

    Runnable getTask() {
//        System.out.println("获取任务");
        boolean isTimeOut = false;
        for (; ; ) {
            //如果目前的线程池处于结束状态
            int c = ctl.get();
            int rs = runStateOf(c);
            if (rs >= SHUTDOWN && (rs >= STOP || workerQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            int w = workerCountOf(c);
            boolean timed = allowCoreThreadTimeOut || w > coreThreadSize;
            if ((w > maxThreadSize || (timed && isTimeOut)) && (w > 1 || workerQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c)) {
                    return null;
                }
                continue;
            }

            try {
                Runnable r = timed ?
                        workerQueue.poll(1000, TimeUnit.MILLISECONDS) : //1s
                        workerQueue.take();
                if (r != null) {
                    return r;
                }
                isTimeOut = true;
            } catch (InterruptedException e) {
                isTimeOut = false;
            }
        }
    }

    public void setAllowCoreThreadTimeOut(boolean allowCoreThreadTimeOut) {
        this.allowCoreThreadTimeOut = allowCoreThreadTimeOut;
    }

    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            if (isRunning(c) ||
                    (runStateOf(c) == SHUTDOWN && ! workerQueue.isEmpty()))
                return;
            //在shutdown后可能有的线程阻塞在workerQueue.take(),需要线程阻塞的状态退出
            if (workerCountOf(c) != 0) {
                interruptIdleWorkers(true);
                return;
            }
            if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                return;
            }
        }
    }

    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    private class Worker extends AbstractQueuedSynchronizer implements Runnable {
        private Runnable task;
        private Thread thread;

        public Worker(Runnable task) {
            setState(-1);
            this.task = task;
            this.thread = new Thread(this);
        }

        public void lock() {
            acquire(1);
        }

        public boolean tryLock() {
            return tryAcquire(1);
        }

        public void unlock() {
            release(1);
        }

        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        @Override
        public void run() {
            Thread wt = Thread.currentThread();
            Runnable task = this.task;
            this.task = null;
            this.unlock();
            while (task != null || (task = getTask()) != null) {
                //上锁是为了shutdown命令不会中断正在运行的线程
                this.lock();
                if ((runStateAtLeast(ctl.get(), SHUTDOWN) ||
                        (Thread.interrupted() &&
                                runStateAtLeast(ctl.get(), SHUTDOWN))) &&
                        !wt.isInterrupted())
                    wt.interrupt();
                try {
                    task.run();
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                } finally {
                    task = null;
                    this.unlock();
                }
            }

            //任务队列为空
            workers.remove(this);


            tryTerminate();
            int c = ctl.get();

            if (runStateLessThan(c, STOP)) {
                int min = allowCoreThreadTimeOut ? 0 : coreThreadSize;
                if (min == 0 && !workerQueue.isEmpty())
                    min = 1;
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
                addWorker(null, false);
            }
        }
    }


    private void shutdown() {
        try {
            mainLock.lock();
            advanceRunState(SHUTDOWN);
            //阻塞没有runWorker的线程
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock())
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
            }
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    private void advanceRunState(int targetState) {
        for (; ; ) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) ||
                    ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }


    //test
    public static void main(String[] args) {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(3, 3);

//        java.util.concurrent.ThreadPoolExecutor threadPoolExecutor = new java.util.concurrent.ThreadPoolExecutor
//                (3,3,1000,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>());
//        threadPoolExecutor.allowCoreThreadTimeOut(true);
        threadPoolExecutor.execute(() -> {
            for (int i = 0; i < 10; i++) {
                System.out.println(i);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        });


        threadPoolExecutor.execute(() -> {
            for (int i = 10; i < 20; i++) {
                System.out.println(i);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        });


        threadPoolExecutor.execute(() -> {
            for (int i = 20; i < 30; i++) {
                System.out.println(i);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        });

        threadPoolExecutor.execute(() -> {
            for (int i = 30; i < 40; i++) {
                System.out.println(i);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        });




        threadPoolExecutor.shutdown();



    }
}
