package soot.jimple.infoflow.data;

import soot.jimple.infoflow.collect.ConcurrentHashSet;

/**
 * Remembers seen SourceContextAndPaths.
 */
public class PathCache extends ConcurrentHashSet<SourceContextAndPath> {
	private final int generation;

	/**
	 * Creates a new path cache.
	 * 
	 * @param generation denotes the generation to distinguish
	 */
	public PathCache(int generation) {
		this.generation = generation;
	}

	/**
	 * Returns the generation field used in the initializer
	 * 
	 * @return the generation
	 */
	public int getGeneration() {
		return generation;
	}
}
