package tardis.framework;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Performer} based on a {@link PausableFixedThreadPoolExecutor}.
 * 
 * @author Pietro Braione
 *
 * @param <I> The type of the items that are read from the {@link InputBuffer}.
 * @param <O> The type of the items that are put in the {@link OutputBuffer}.
 */
public abstract class PerformerPausableFixedThreadPoolExecutor<I,O> extends Performer<I,O> {
    /**
     * The pausable thread pool of all the threads
     * that this {@link PerformerPausableFixedThreadPoolExecutor} encapsulates.
     */
    private final PausableFixedThreadPoolExecutor threadPool;
    
    /**
     * Constructor.
     * 
     * @param name a meaningful name for the performer that will be used for debugging.
     * @param in The {@link InputBuffer} from which this {@link PerformerPausableFixedThreadPoolExecutor} will read the input items. 
     * @param out The {@link OutputBuffer} where this {@link PerformerPausableFixedThreadPoolExecutor} will put the output items.
     * @param numOfThreads The number of concurrent threads that this {@link PerformerPausableFixedThreadPoolExecutor} encapsulates.
     * @param numInputs An {@code int}, the maximum number of input items that are passed as a batch
     *        to {@link #makeJob(List) makeJob}.
     * @param timeoutDuration The maximum duration of the time this {@link PerformerPausableFixedThreadPoolExecutor} will wait for 
     *        the arrival of an input item.  
     * @param timeoutTimeUnit The {@link TimeUnit} for {@code timeoutDuration}. 
     * @throws NullPointerException if {@code in == null || out == null || timeoutUnit == null}.
     * @throws IllegalArgumentException if {@code numOfThreads <= 0 || numInputs <= 0 || timeoutDuration < 0}.
     */
    public PerformerPausableFixedThreadPoolExecutor(String name, InputBuffer<I> in, OutputBuffer<O> out, int numOfThreads, int numInputs, long timeoutDuration, TimeUnit timeoutTimeUnit) {
    	super(name, in, out, numInputs, timeoutDuration, timeoutTimeUnit);
        this.threadPool = new PausableFixedThreadPoolExecutor(name, numOfThreads);
    }
    
    @Override
    protected final void onPause() {
        this.threadPool.pause();
    }
    
    @Override
    protected final void onResume() {
        this.threadPool.resume();
    }
    
    @Override
    protected final void onShutdown() {
    	this.threadPool.shutdownNow();
    }

    @Override
    protected final boolean areWorkersIdle() {
    	return this.threadPool.isIdle();
    }
    
	@Override
	protected boolean availableWorkers(int numTargets) {
    	return this.threadPool.getCorePoolSize() - this.threadPool.getActiveCount() > 0;
	}

    /**
     * Makes a {@link Runnable} job to be executed by a thread encapsulated by 
     * this performer.
     * 
     * @param items a {@link List}{@code <I>}, a batch of input items whose minimum
     *        size is 1 and whose maximum size is the {@code numInputs} parameter
     *        passed upon construction (with the possible exception of the seed items, 
     *        that are not split according to {@code numInputs} but are always passed 
     *        as a unique batch).
     * @return a {@link Runnable} that elaborates {@code items}, possibly putting
     *         some output items in the output buffer.
     */
    protected abstract Runnable makeJob(List<I> items);

    @Override
	protected void executeJob(List<I> items) {
    	Runnable job = makeJob(items);
    	this.threadPool.execute(job);		
	}
    
}
