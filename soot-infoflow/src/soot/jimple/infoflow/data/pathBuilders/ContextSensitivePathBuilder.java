package soot.jimple.infoflow.data.pathBuilders;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import heros.solver.Pair;
import soot.jimple.Stmt;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.InfoflowManager;
import soot.jimple.infoflow.collect.ConcurrentHashSet;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.data.AbstractionAtSink;
import soot.jimple.infoflow.data.SourceContext;
import soot.jimple.infoflow.data.SourceContextAndPath;
import soot.jimple.infoflow.results.InfoflowResults;
import soot.jimple.infoflow.results.ResultSinkInfo;
import soot.jimple.infoflow.results.ResultSourceInfo;
import soot.jimple.infoflow.solver.executors.InterruptableExecutor;

/**
 * Class for reconstructing abstraction paths from sinks to source. This builder
 * is context-sensitive which makes it more precise than the
 * {@link ContextInsensitivePathBuilder}, but also a bit slower.
 * 
 * @author Steven Arzt
 */
public class ContextSensitivePathBuilder extends ConcurrentAbstractionPathBuilder {

	// Set holds all paths that reach an already cached subpath
	protected ConcurrentHashSet<SourceContextAndPath> deferredPaths = new ConcurrentHashSet<>();
	// Set holds all paths that reach a source
	protected ConcurrentHashSet<SourceContextAndPath> sourceReachingScaps = new ConcurrentHashSet<>();

	protected static final AtomicInteger GENERATION_COUNTER = new AtomicInteger();
	protected int currentGen = GENERATION_COUNTER.incrementAndGet();

	/**
	 * Creates a new instance of the {@link ContextSensitivePathBuilder} class
	 * 
	 * @param manager The data flow manager that gives access to the icfg and other
	 *                objects
	 */
	public ContextSensitivePathBuilder(InfoflowManager manager) {
		super(manager, createExecutor(manager));
	}

	private static InterruptableExecutor createExecutor(InfoflowManager manager) {
		int numThreads = Runtime.getRuntime().availableProcessors();
		int mtn = manager.getConfig().getMaxThreadNum();
		InterruptableExecutor executor = new InterruptableExecutor(mtn == -1 ? numThreads : Math.min(mtn, numThreads),
				Integer.MAX_VALUE, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<Runnable>());
		executor.setThreadFactory(new ThreadFactory() {

			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "Path reconstruction");
			}

		});
		return executor;
	}

	protected enum PathProcessingResult {
		// Describes that the predecessor should be queued
		NEW,
		// Describes that the predecessor was already queued, but we might need to merge
		// paths
		CACHED,
		// Describes that nothing further should be queued
		INFEASIBLE_OR_MAX_PATHS_REACHED
	}

	/**
	 * Task for tracking back the path from sink to source.
	 * 
	 * @author Steven Arzt
	 */
	protected class SourceFindingTask implements Runnable, Comparable<SourceFindingTask> {
		private final Abstraction abstraction;
		private final int currentGen = ContextSensitivePathBuilder.this.currentGen;

		public SourceFindingTask(Abstraction abstraction) {
			this.abstraction = abstraction;
		}

		@Override
		public void run() {
			final Set<SourceContextAndPath> paths = abstraction.getPathCacheUnsafe(currentGen);
			Abstraction pred = abstraction.getPredecessor();

			if (pred != null && paths != null) {
				for (SourceContextAndPath scap : paths) {
					// Process the predecessor
					processAndQueue(pred, scap);

					// Process the predecessor's neighbors
					Set<Abstraction> pn = pred.getNeighbors();
					if (pn != null) {
						for (Abstraction neighbor : pn) {
							processAndQueue(neighbor, scap);
						}
					}
				}
			}
		}

		private void processAndQueue(Abstraction pred, SourceContextAndPath scap) {
			PathProcessingResult p = processPredecessor(scap, pred);
			switch (p) {
			case NEW:
				// Schedule the predecessor
				assert pred.getPathCacheUnsafe(currentGen) != null;
				scheduleDependentTask(createSourceFindingTask(pred));
				break;
			case CACHED:
				// In case we already know the subpath, we do append the path after the path
				// builder terminated
				if (config.getPathConfiguration()
						.getPathReconstructionMode() != InfoflowConfiguration.PathReconstructionMode.NoPaths)
					deferredPaths.add(scap);
				break;
			case INFEASIBLE_OR_MAX_PATHS_REACHED:
				// Nothing to do
				break;
			default:
				assert false;
			}
		}

		protected PathProcessingResult processPredecessor(SourceContextAndPath scap, Abstraction pred) {
			// Shortcut: If this a call-to-return node, we should not enter and
			// immediately leave again for performance reasons.
			if (pred.getCurrentStmt() != null && pred.getCurrentStmt() == pred.getCorrespondingCallSite()) {
				SourceContextAndPath extendedScap = scap.extendPath(pred, config);
				if (extendedScap == null)
					return PathProcessingResult.INFEASIBLE_OR_MAX_PATHS_REACHED;

				if (checkForSource(pred, extendedScap))
					sourceReachingScaps.add(extendedScap);

				return pred.getPathCache(currentGen).add(extendedScap) ? PathProcessingResult.NEW
						: PathProcessingResult.CACHED;
			}

			// If we enter a method, we put it on the stack
			SourceContextAndPath extendedScap = scap.extendPath(pred, config);
			if (extendedScap == null)
				return PathProcessingResult.INFEASIBLE_OR_MAX_PATHS_REACHED;

			// Check if we are in the right context
			if (pred.getCurrentStmt() != null && pred.getCurrentStmt().containsInvokeExpr()) {
				// Pop the top item off the call stack. This gives us the item
				// and the new SCAP without the item we popped off.
				Pair<SourceContextAndPath, Stmt> pathAndItem = extendedScap.popTopCallStackItem();
				if (pathAndItem != null) {
					Stmt topCallStackItem = pathAndItem.getO2();
					// Make sure that we don't follow an unrealizable path
					if (topCallStackItem != pred.getCurrentStmt())
						return PathProcessingResult.INFEASIBLE_OR_MAX_PATHS_REACHED;

					// We have returned from a function
					extendedScap = pathAndItem.getO1();
				}
			}

			// Add the new path
			if (checkForSource(pred, extendedScap))
				sourceReachingScaps.add(extendedScap);

			final int maxPaths = config.getPathConfiguration().getMaxPathsPerAbstraction();
			Set<SourceContextAndPath> existingPaths = pred.getPathCache(currentGen);
			if (maxPaths > 0 && existingPaths.size() > maxPaths) {
				return PathProcessingResult.INFEASIBLE_OR_MAX_PATHS_REACHED;
			}

			return existingPaths.add(extendedScap) ? PathProcessingResult.NEW : PathProcessingResult.CACHED;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((abstraction == null) ? 0 : abstraction.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SourceFindingTask other = (SourceFindingTask) obj;
			return abstraction == other.abstraction;
		}

		@Override
		public int compareTo(SourceFindingTask arg0) {
			return Integer.compare(abstraction.getPathLength(), arg0.abstraction.getPathLength());
		}

	}

	/**
	 * Checks whether the given abstraction is a source. If so, a result entry is
	 * created.
	 * 
	 * @param abs  The abstraction to check
	 * @param scap The path leading up to the current abstraction
	 * @return True if the current abstraction is a source, otherwise false
	 */
	protected boolean checkForSource(Abstraction abs, SourceContextAndPath scap) {
		if (abs.getPredecessor() != null)
			return false;

		// If we have no predecessors, this must be a source
		assert abs.getSourceContext() != null;

		// A source should normally never have neighbors, but it can happen
		// with ICCTA
		// if (abs.getNeighbors() != null) {
		// we ignore this issue for now, because the neighbor's source
		// contexts seem to be equal to our own one
		// }

		// Register the source that we have found
		SourceContext sourceContext = abs.getSourceContext();
		Collection<Pair<ResultSourceInfo, ResultSinkInfo>> newResults = results.addResult(scap.getDefinitions(),
				scap.getAccessPath(), scap.getStmt(), sourceContext.getDefinitions(), sourceContext.getAccessPath(),
				sourceContext.getStmt(), sourceContext.getUserData(), scap.getAbstractionPath(), manager);

		// Notify our handlers
		if (resultAvailableHandlers != null)
			for (OnPathBuilderResultAvailable handler : resultAvailableHandlers)
				for (Pair<ResultSourceInfo, ResultSinkInfo> newResult : newResults)
					handler.onResultAvailable(newResult.getO1(), newResult.getO2());

		return true;
	}

	@Override
	public void computeTaintPaths(Set<AbstractionAtSink> res) {
		try {
			super.computeTaintPaths(res);

			// Wait for the path builder to terminate. The path reconstruction should stop
			// on time anyway. In case it doesn't, we make sure that we don't get stuck.
			long pathTimeout = manager.getConfig().getPathConfiguration().getPathReconstructionTimeout();
			if (pathTimeout > 0)
				executor.awaitCompletion(pathTimeout + 20, TimeUnit.SECONDS);
			else
				executor.awaitCompletion();
		} catch (InterruptedException e) {
			logger.error("Could not wait for executor termination", e);
		} finally {
			onTaintPathsComputed();
			cleanupExecutor();
		}
	}

	@Override
	public void reset() {
		super.reset();
		deferredPaths = new ConcurrentHashSet<>();
		sourceReachingScaps = new ConcurrentHashSet<>();
		currentGen = GENERATION_COUNTER.incrementAndGet();
	}

	/**
	 * Tries to fill up deferred paths toward a source.
	 */
	protected void buildPathsFromCache() {
		for (SourceContextAndPath deferredScap : deferredPaths) {
			for (SourceContextAndPath sourceScap : sourceReachingScaps) {
				SourceContextAndPath fullScap = deferredScap.extendPath(sourceScap);
				if (fullScap != null)
					checkForSource(fullScap.getLastAbstraction(), fullScap);
			}
		}
	}

	/**
	 * Method that is called when the taint paths have been computed
	 */
	protected void onTaintPathsComputed() {
		buildPathsFromCache();
	}

	/**
	 * Method that is called to shut down the executor
	 */
	protected void cleanupExecutor() {
		shutdown();
	}

	/**
	 * Terminates the internal executor and cleans up all resources that were used
	 */
	public void shutdown() {
		executor.shutdown();
	}

	@Override
	protected Runnable getTaintPathTask(final AbstractionAtSink abs) {
		final Abstraction ab = abs.getAbstraction();
		SourceContextAndPath scap = new SourceContextAndPath(config, abs.getSinkDefinitions(), ab.getAccessPath(),
				abs.getSinkStmt());
		scap = scap.extendPath(ab, config);

		if (ab.getPathCache(currentGen).add(scap)) {
			if (!checkForSource(ab, scap))
				return createSourceFindingTask(ab);
		}
		return null;
	}

	protected Runnable createSourceFindingTask(Abstraction abstraction) {
		return new SourceFindingTask(abstraction);
	}

	@Override
	public InfoflowResults getResults() {
		return this.results;
	}

	@Override
	protected boolean triggerComputationForNeighbors() {
		return true;
	}

	@Override
	public void runIncrementalPathComputation() {

	}

}
