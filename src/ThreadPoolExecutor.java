import java.util.HashSet;
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

    private AtomicInteger workerSize = new AtomicInteger(0);

    public ThreadPoolExecutor(int maxThreadSize, int coreThreadSize) {
        this.maxThreadSize = maxThreadSize;
        this.coreThreadSize = coreThreadSize;
    }

    public void execute(Runnable task) {
        if (task == null)
            throw new NullPointerException();

        boolean success;
        if (workerSize.get() >= coreThreadSize) {
            if (!workerQueue.offer(task)) {
                success = addWorker(task, false);
            } else {
                success = true;
            }
        } else {
            success = addWorker(task, true);
        }
        if (!success) {
            throw new ThreadPoolSizeSmallException();
        }
    }

    private boolean addWorker(Runnable task, boolean core) {
        for (; ; ) {
            int w = workerSize.get();
            if (core) {
                if (w >= coreThreadSize) {
                    return false;
                }
            } else {
                if (w >= maxThreadSize) {
                    return false;
                }
            }

            if (workerSize.compareAndSet(w, w + 1)) {
                break;
            }
        }

        Worker worker = new Worker(task);
        workers.add(worker);
        worker.start();
        return true;
    }

    Runnable getTask() {
        System.out.println("从等待队列查找任务。。。");
        try {
            return workerQueue.take();
        } catch (InterruptedException e) {
            return null;
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
                task.run();
                task = null;
            }


            for (; ; ) {
                int w = workerSize.get();
                if (workerSize.compareAndSet(w, w - 1)) {
                    break;
                }
            }

            //任务队列为空
            workers.remove(this);

            if (workerSize.get() < coreThreadSize) {
                addWorker(null, true);
            }
        }
    }


    //test
    public static void main(String[] args) {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(3, 2);

        threadPoolExecutor.execute(() -> {
            for (int i = 0; i < 10; i++) {
                System.out.println(i);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        threadPoolExecutor.execute(() -> {
            for (int i = 10; i < 20; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println(i);
            }
        });

        threadPoolExecutor.execute(() -> {
            for (int i = 20; i < 30; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                System.out.println(i);
            }
        });
    }
}
