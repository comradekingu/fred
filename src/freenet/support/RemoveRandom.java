package freenet.support;

import freenet.client.async.ClientContext;
import freenet.client.async.HasCooldownCacheItem;

public interface RemoveRandom extends HasCooldownCacheItem {

	// OPTIMISE: Would a stack-trace-less exception be faster?
	/** Either a RandomGrabArrayItem or the time at which we should try again. */
	public static final class RemoveRandomReturn {
		public final RandomGrabArrayItem item;
		public final long wakeupTime;
		RemoveRandomReturn(RandomGrabArrayItem item) {
			this.item = item;
			this.wakeupTime = -1;
		}
		RemoveRandomReturn(long wakeupTime) {
			this.item = null;
			this.wakeupTime = wakeupTime;
		}
	}
	
	/** Return a random RandomGrabArrayItem, or a time at which there will be one, or null
	 * if the RGA is empty and should be removed by the parent. */
	public RemoveRandomReturn removeRandom(RandomGrabArrayItemExclusionList excluding, ClientContext context, long now);

	/** Just for consistency checking */
	public boolean persistent();
	
	/**
	 * @param existingGrabber
	 * @param container
	 * @param canCommit If true, can commit to limit memory footprint.
	 */
	public void moveElementsTo(RemoveRandom existingGrabber, boolean canCommit);
	
	public void setParent(RemoveRandomParent newTopLevel);

}
