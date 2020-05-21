import com.sun.xml.internal.bind.v2.schemagen.xmlschema.SchemaTop;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author lilei
 **/
public class ThreadPoolExecutor {
    private volatile int maxThreadSize;
    private volatile int coreThreadSize;
    private Set<Worker> workers = new CopyOnWriteArraySet<>();
    private BlockingQueue<Runnable> workerQueue = new LinkedBlockingQueue<>();
    private final ReentrantLock mainLock = new ReentrantLock();
    private AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int CAPACITY = COUNT_BITS - 1;
    private static final int RUNNING = -1 << COUNT_BITS;
    private static final int SHUTDOWN = 0;
    private static final int STOP = 1 << COUNT_BITS;

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


//    private static boolean runStateLessThan(int c, int s) {
//        return c < s;
//    }

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
                throw new ThreadPoolSizeSmallException();
            }
        }
    }

    private boolean addWorker(Runnable task, boolean core) {
        do {
            //如果目前的线程不是运行态,或者不是shutdown且队列不为空的情况添加worker失败
            if (!isRunning(ctl.get()) && !((runStateOf(ctl.get()) == SHUTDOWN) && !workerQueue.isEmpty() && task != null)) {
                return false;
            }
            int w = workerCountOf(ctl.get());
            if (core) {
                if (w >= coreThreadSize) {
                    return false;
                }
            } else {
                if (w >= maxThreadSize) {
                    return false;
                }
            }
        } while (!compareAndIncrementWorkerCount(ctl.get()));

        Worker worker = new Worker(task);
        workers.add(worker);
        worker.start();
        return true;
    }

    Runnable getTask() {
        for (; ; ) {
            System.out.println("从等待队列查找任务。。。");
            //如果目前的线程池处于结束状态
            int c = ctl.get();
            int rs = runStateOf(c);
            if (rs >= SHUTDOWN && (rs >= STOP || workerQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            //再次判断工作线程的数量，如果超过最大限制，则将线程的工作数量减一,
            if (workerCountOf(c) > maxThreadSize) {
                if (compareAndDecrementWorkerCount(c)) {
                    return null;
                }
                continue;
            }

            try {
                return workerQueue.take();
            } catch (InterruptedException e) {
                System.out.println("线程接受到终止命令");
            }
        }

    }


    private class Worker extends Thread {
        private Runnable task;

        public Worker(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            while (task != null || (task = getTask()) != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
                task = null;
            }

            //任务队列为空
            workers.remove(this);
            if (workerCountOf(ctl.get()) < coreThreadSize) {
                addWorker(null, true);
            }
        }
    }


    private void shutdown() {
        try {
            mainLock.lock();
            advanceRunState(SHUTDOWN);
            for (Worker w : workers) {
                w.interrupt();
            }
        } finally {
            mainLock.unlock();
        }
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

        threadPoolExecutor.execute(() -> {
            for (int i = 0; i < 10; i++) {
                System.out.println(i);
            }
        });



        threadPoolExecutor.execute(() -> {
            for (int i = 10; i < 20; i++) {
                System.out.println(i);
            }
        });
        threadPoolExecutor.shutdown();

        threadPoolExecutor.execute(() -> {
            for (int i = 20; i < 30; i++) {
                System.out.println(i);
            }
        });

    }
}
