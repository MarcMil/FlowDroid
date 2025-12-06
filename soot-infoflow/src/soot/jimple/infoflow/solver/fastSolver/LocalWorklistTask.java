package soot.jimple.infoflow.solver.fastSolver;

import java.util.ArrayDeque;

import soot.jimple.infoflow.solver.executors.SetPoolExecutor;

/**
 * This special task may run multiple tasks on the same thread if they are
 * scheduled using {@link #scheduleLocal(Runnable)}
 * 
 * @author Marc Miltenberger
 */
public abstract class LocalWorklistTask implements Runnable {
	private ArrayDeque<Runnable> localTaskList = new ArrayDeque<>();
	private SetPoolExecutor e;
	private static final ThreadLocal<LocalWorklistTask> TASKS = new ThreadLocal<>();

	public void setExecutor(SetPoolExecutor e) {
		this.e = e;
	}

	@Override
	public final void run() {
		try {
			ArrayDeque<Runnable> list = localTaskList;
			list.add(this);
			TASKS.set(this);
			while (true) {
				Runnable d = list.poll();
				if (d == null)
					break;
				if (d instanceof LocalWorklistTask) {
					LocalWorklistTask l = (LocalWorklistTask) d;
					l.runInternal();
				} else
					d.run();
			}

		} finally {
			TASKS.remove();
		}

	}

	public abstract void runInternal();

	public static void scheduleLocal(Runnable task) {
		LocalWorklistTask t = TASKS.get();
		if (t != null) {
			if (!t.e.waiting.add(task))
				throw new RuntimeException("Double");
			t.localTaskList.add(task);
		}
	}

}
