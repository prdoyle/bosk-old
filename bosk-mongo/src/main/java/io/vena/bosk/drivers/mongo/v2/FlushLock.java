package io.vena.bosk.drivers.mongo.v2;

import io.vena.bosk.drivers.mongo.MongoDriverSettings;
import io.vena.bosk.exceptions.FlushFailureException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.bson.BsonInt64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Implements waiting mechanism for revision numbers
 */
@RequiredArgsConstructor
class FlushLock {
	private final MongoDriverSettings settings;
	private final PriorityBlockingQueue<Waiter> queue = new PriorityBlockingQueue<>();
	private volatile long alreadySeen = 0; // Corresponds to REVISION_ZERO, which is always "in the past"

	@Value
	private static class Waiter implements Comparable<Waiter> {
		long revision;
		Semaphore semaphore;

		@Override
		public int compareTo(Waiter other) {
			return Long.compare(revision, other.revision);
		}
	}

	void awaitRevision(BsonInt64 revision) throws InterruptedException, FlushFailureException {
		long revisionValue = revision.longValue();
		if (revisionValue <= alreadySeen) {
			LOGGER.debug("Revision {} is in the past", revisionValue);
			return;
		}
		LOGGER.debug("Awaiting revision {}", revisionValue);
		Semaphore semaphore = new Semaphore(0);
		queue.add(new Waiter(revisionValue, semaphore));
		if (!semaphore.tryAcquire(settings.flushTimeoutMS(), MILLISECONDS)) {
			throw new FlushFailureException("Timed out waiting for revision " + revisionValue);
		}
		LOGGER.trace("Done awaiting revision {}", revisionValue);
	}

	/**
	 * @param revision can be null
	 */
	public void startedRevision(BsonInt64 revision) {
		if (revision == null) {
			return;
		}
		long revisionValue = revision.longValue();
		assert alreadySeen <= revisionValue;
		alreadySeen = revisionValue;
		LOGGER.debug("Seen {}", revisionValue);
	}

	/**
	 * @param revision can be null
	 */
	void finishedRevision(BsonInt64 revision) {
		if (revision == null) {
			return;
		}
		long revisionValue = revision.longValue();
		do {
			Waiter w = queue.peek();
			if (w == null || w.revision > revisionValue) {
				return;
			} else {
				Waiter removed = queue.remove();
				assert w == removed;
				w.semaphore.release();
			}
		} while (true);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(FlushLock.class);
}
